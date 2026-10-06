# 架构

模块结构、QQ 接口与升级检查项。按 QQ 9.3.70（NT，versionCode 16410）核验。

## 进程与组件

| 组件 | 职责 |
| --- | --- |
| `Main` | 只在 QQ 主进程启动 Satori 服务，其他进程不加载模块代码 |
| `Cfg` / `L` | 文件配置与日志，详细日志默认关闭 |
| `ui` | 知弦管理界面与两个快捷设置磁贴，在应用自身进程运行，前台只读探测状态 |
| `guard` | App → root `qqguard` 的调用桥：只拼写死的 `su -c` 命令，不接收任何界面 / 网络输入 |
| `control` | 私有设置、UID 校验的 Provider、启动时配置同步 |
| `net` | HTTP 与事件 WebSocket，只监听 `127.0.0.1` |
| `core` | Satori 服务本体：HTTP 路由与方法分发、事件广播、消息与文件标识管理（部件见下） |
| `satori` | Satori 元素与数据结构转换 |
| `qq` | NT 会话、消息、媒体与保活 |
| `packet` | OIDB 与合并转发封包 |

HTTP 服务、消息监听与保活只在主进程运行，APK 里不含 native 库。

## 管理通道

`ui.MainActivity` 与 `control.ControlProvider` 在应用自身进程。Provider 只允许模块自身 UID 与当前安装的 `com.tencent.mobileqq` UID 调用，其余调用方含 shell 抛 `SecurityException`。Provider 只支持读配置与运行快照，不接受文件路径、命令或 HTTP 写设置。

`Main` 尽早安装 QQ hooks。`SatoriHub.start()` 交给 `Monitor.start`，在独立工作线程等 Application 基础 Context attach，至多 10 秒。Provider 短暂不可用时重试至多 1.2 秒。从 Provider 读到端口、令牌与四个运行偏好并应用后才绑定 HTTP 端口，其余高级配置不被覆盖。Provider 不可用时保留原文件或默认行为。`/healthz.config_revision` 是本进程读到的配置修订号，用来区分已保存与已生效。

运行快照写进 `noBackupFilesDir/zhixian-control.json`，用 AtomicFile 原子更新，不用 SharedPreferences。首页读现有 `/healthz`，限定回环地址、超时与响应大小，不跟随重定向。诊断报告按字段白名单构造，排除账号、令牌与消息。保存配置不强停 QQ，也不热切换端口。

ColorOS 的关联启动策略可能拒绝冷启动 Provider。桥接重试失败后回退原文件配置，并通过 `config_status` 暴露不含敏感信息的原因。设置页给出系统设置里的路径（应用 → 关联启动）。引导线程在 QQ 主线程初始化任务之后启动，不阻塞 Application 创建。

## core 的部件

`SatoriHub` 只做接线：按依赖顺序建好下面这些部件，把 `HttpServer.Handler` 的回调转给它们。部件都是包内可见，互相只通过构造参数依赖，没有循环。

| 部件 | 职责 |
| --- | --- |
| `HttpRoutes` | 鉴权、路由、请求体解析、`upload.create`、资源回读（含 `Range`），错误统一成 `{code, message}` |
| `Dispatcher` | 标准方法与 `internal/*` 的方法名 → 实现；写动作一律经 `OutboundGate` |
| `Capabilities` | `internal/capabilities` 目录与「曾经有过」的动作清单 |
| `Events` | WebSocket 握手（IDENTIFY → READY）、事件广播与断线重放、传输层心跳、换号时的断开重连 |
| `Inbound` | 内核回调 → 事件：消息、撤回、成员变动、申请、群列表；自己在 QQ 里发的消息的补偿轮询 |
| `Requests` | 好友与入群申请：事件生成、待处理表、按 flag 同意或拒绝 |
| `Messages` / `Sender` | `message.create`、`message.delete`；`Sender` 是最底层的发送：转元素、过内核、重试媒体上传、登记回执 |
| `Forwards` | 合并转发的发出（原生或 fake）与读回（res_id 或内核父消息） |
| `History` | `message.get`、`message.list`、聊天截图，以及补全空壳记录的整形 |
| `Reactions` | 表情回应的增、撤、列，别人回应变化的事件 |
| `GroupOps` | 踢人、禁言、设管理、改群、头衔、签到、精华、戳一戳、骰子猜拳 |
| `Directory` | 群、成员、好友、资料的查询，uin 与 uid 的互查，群名缓存 |
| `Lookup` / `Resources` | `message_id` 找回本地登记的消息；资源 id、`internal:` 链接与本地文件的换算 |
| `OutboundGate` | 写动作的必经之路：等会话稳定、限频与熔断、占着唤醒锁 |
| `Monitor` | 每秒一拍的状态循环（在线、心跳、换号、常驻通知）与 `/healthz`、`internal/status` 的诊断 |
| `ApiError` / `Workers` / `Ids` / `Json` | 错误码的静态工厂；外部可见的线程命名；松散 id 的解析；org.json 的小零件 |

换号时 `Monitor` 在 `Events.accountChanged` 的锁里调用每个部件的 `reset()`，旧账号的消息、资源、申请与回应缓存一并作废。

## 界面构建

`build.sh` 先用 aapt 生成 `R.java` 再 javac，令牌经 `R` 引用，名字拼错在编译期失败。视图不解析 XML 布局，直接构造。关键视图的稳定 id 在 `res/values/ids.xml`，真机验收按 id 查找。

APK 里的 dex 包含全部代码。注入 QQ 的那份内嵌进 `libsatori.so`，去掉了 `ui/`、`guard/` 与 `R`，QQ 进程从不加载它们。

`GuardCommand.relaunchQQ` 不直接 `am force-stop`：看守把系统的强行停止当作用户意愿，会自己转入 PAUSED。它先记下模式、保活中就先暂停，关掉 QQ 并用 `monkey` 重新打开，再恢复保活。

## HTTP 路由

三条通道各自独立，都在 `core/HttpRoutes` 里分派，方法名再交给 `core/Dispatcher`：

| 路径 | 鉴权 | 用途 |
| --- | --- | --- |
| `POST /v1/{resource}.{method}` | 需要（配了 token 时） | Satori 标准方法 |
| `POST /v1/internal/{name}` | 需要 | 模块自身的 QQ 扩展简写，`name` 可用 `.` / `_` / `-` 分隔，也接受 camelCase；白名单没命中时转去 `ExtraSvc` 的动作注册表查表执行，仍未命中的一律 404 |
| `POST /v1/internal/{platform}/{selfId}/_api/{name}` | 需要 | `@satorijs/adapter-satori` 的 `bot.internal.*` 走法，参数按 `JsonForm` 编码，`Satori-Pagination: true` 时回 `{data, …}` |
| `GET /v1/internal/{platform}/{selfId}/_tmp/{id}` | 免 | 官方客户端的登录域内路由，读 `internal:` 资源（收到的媒体与 `upload.create` 的结果同一份存储） |
| `GET /v1/proxy/{url}` | 免 | 代理本机登录自己的 `internal:` 资源，支持 `HEAD` 与单段 `Range` |
| `GET /healthz` | 免 | 运维探针 |

免鉴权的两条只服务本机登录自己，且只认模块签发的不透明 id。请求指向别的 platform 或 selfId 一律 404。

`qq/ExtraSvc` 承载扩展动作的内核服务调用：个人资料、群设置、好友关系、最近联系人、富媒体与机器人。这些服务主线程亲和，从 HTTP 工作线程直接调用会立即返回而回调永不触发，所以 `ExtraSvc` 把调用投递到主 Looper，再由工作线程等回调。

回调按 `(int code, String msg, <payload>...)` 的形状匹配，不按方法名。`IOperateCallback` 的签名是 `onResult(int, String)`，不带结果。需要返回结构的读取要用各自的回调接口，例如 `IGroupMemberHonorCallback`、`IKernelRecentGetContactCallback`，第三个参数才是 payload。不回调的入口一律不进模块，否则调用方要等满 15 秒超时。

`ExtraSvc` 维护一份动作注册表（`ExtraSvc.Spec`）：名字、别名、是否写、参数说明、调用体各登记一次。`Dispatcher.extension` 按名字查表执行，写动作复用 `OutboundGate.guarded` 的限频与熔断。`Capabilities.build` 的目录、参数、读写分类也从这份表生成。

新增扩展动作前核三件事：类名在不在 QQ 的 dex 类索引里、参数结构体的字段名、入口会不会回调。`packet` 只在协议需要直接发包时使用，不与内核服务混用。

## 消息链路

- 接收：`IKernelMsgListener.onRecvMsg` → 元素转换 → `message-created`
- 发送：`message.create` → 元素转换 → `sendMsg`。合并转发由 `forward_mode` 选原生或 `fake` 路径
- 历史：`getMsgs`，按 `message_seq` 分页。撤回：`recallMsg`
- 媒体：`IKernelRichMediaService` 下载。发送文件先写进 QQ 媒体目录，再交给内核上传
- OIDB：`onSendSSORequest` 发送，SSO 元数据与 QSec 签名仍由 QQ 负责

在线状态同时要求账号、NT 会话、消息服务与当前监听器可用，且前台不是登录页。离线写操作返回 Satori 状态码 `1500`。

## 群资料写入

`channel.update` 换群头像走 `setHeader`。改群名只走 `modifyGroupName(group, name, isNormalMember)`：先按 `false` 调一次，结果码 1287 时把身份参数翻成 `true` 再试一次。不做二次写入，也不回读校验。空名字一律拒绝。

名字守卫只观察：发现某个群名字为空先全量刷新，刷新后仍为空只记一行 `name_guard=empty:<群>(见过=<名字>)`，一个字也不写。

## QQ Native 接口

会话由反射 `IKernelService.getWrapperSession()` 取得，并通过 `getMsgService` 与 `getGroupService` 补获已存在的会话。

| 能力 | 入口 → 接口 |
| --- | --- |
| 消息 | `getMsgService` → `IKernelMsgService` |
| 群与成员 | `getGroupService` → `IKernelGroupService` |
| 用户资料 | `getProfileService` → `IKernelProfileService` |
| 好友 | `getBuddyService` → `IKernelBuddyService` |
| 富媒体 | `getRichMediaService` → `IKernelRichMediaService` |
| 最近联系人 | `getRecentContactService` → `IKernelRecentContactService` |
| 机器人 | `getRobotService` → `IKernelRobotService` |

UIN 转 UID 走资料服务上的 `getUidByUin`。读操作的返回结构由 `Json.reflect` 反射导出，QQ 增删字段时跟着变。

会话上共有 55 个 `get*()` 入口，模块只用上面 7 个。完整入口表、各服务的方法面与本层的功能边界见 [`JNI_CAPABILITIES.md`](JNI_CAPABILITIES.md)。

私聊 `Contact` 使用 UID，群聊使用群号。可选字段必须通过 `Ref.getOrNull` 探测。群成员角色来自 `getAllMemberList` 缓存，不用 `MsgRecord.roleType` 或 `roleId`。

## 常驻与诊断

在线时模块保持 QQ 的服务处于已启动状态。写入期间自动持 CPU 与 Wi-Fi 锁，有客户端连接时可长期持 Wi-Fi 锁。`GET /healthz` 返回在线、监听、配置修订、保活、心跳、唤醒锁、SSO、compat 与名字守卫状态：

- `keepalive` 报 `adj`（当前优先级，低于冻结阈值 900 就不会被冻）、`wchan`（`do_freezer_trap` 即被冻）与 `service`（起服务结果）
- `heartbeat` 报服务端 WebSocket ping 的 `pings / pongs / reaped`。`WsConn` 记录最后入站帧，两个心跳周期没回包的半开连接会被关掉

root 侧的 `qqguard`（`scripts/qqguard.sh`，见 [`GUARD.md`](GUARD.md)）与注入层解耦，以 `/system/bin/sh` 运行，只依赖系统与 KernelSU 原生能力。它负责进程死亡恢复、freezer 解冻、Doze / AppOps / Data Saver 配置与开机状态恢复，以落盘的 ARMED / PAUSED 区分保活与用户主动停止，重启有冷却、每小时预算、指数退避与连续崩溃保护。状态与日志在 `/data/adb/satori-qq/`，模块自带的 `service.sh` 在 late_start 阶段调 `qqguard boot`。

状态通知的补发：QQ 回到前台会清自家通知。`StatusNotice.update` 每轮用 `getActiveNotifications()` 检查自己那条是否还在，被清掉就用同样内容补发，`/healthz.notice` 的 `reposts` 记录补发次数。点击目标是宿主包的 launcher activity，`PendingIntent` 用 `getLaunchIntentForPackage` 解析一次后缓存。返回的 Intent 带 `FLAG_ACTIVITY_NEW_TASK`，QQ 在后台时回到原任务。

ColorOS 会把通知小图标换成发通知那个应用的 launcher 图标。`OplusNotificationFixHelper.fixSmallIcon` 把原图标挪到 `oplus_small_icon` 附加项、写 `oplus_smallicon_use_app_icon=true`，再取 `getApplicationInfoAsUser(pkg).icon`。系统应用、平台签名、营销通知与 OPLUS 自家包名跳过。SystemUI 的 `OplusNotificationSmallIconUtil.useAppIconForSmallIcon` 只读那个布尔项。模块在 QQ 进程里发通知，`pkg` 与 `opPkg` 都是 QQ，所以常驻通知只能显示 QQ 图标。要显示模块自己的图标，只能由 `com.satori.qq` 自己的进程发。

## QQ 升级检查

升级 QQ 后先跑一次自检：

```sh
curl -s -X POST http://127.0.0.1:3001/v1/internal/compat -d '{}'
```

`internal/compat` 用反射核对模块依赖的内核接口面，不发任何内核请求，几毫秒出结果：

- `static.types`：模块引用到的 128 个内核类在不在。表由源码里的类名字面量生成，重新生成的办法写在 `qq/Compat` 的注释里
- `static.services`：会话上 7 个服务入口与它们必须实现的接口
- `static.structs`：模块会写入的结构体字段名。字段改名是静默失败，`Ref.put` 找不到字段就退化，服务端只回参数错误
- `static.callbacks`：回调接口有没有 `on*` 方法，参数个数按「至少」算
- `observed`：`ExtraSvc.call` 记录的每次内核调用结果，按 label 分列

`static.ok` 为 `false` 时 `missing` 逐条给出断点。`/healthz` 里的 `compat` 是同一份报告的摘要，按 QQ 版本缓存 10 分钟。`internal/compat` 传 `force=true` 可强制重算。

接口面干净之后再逐项核：`nativeinterface` 类名、构造函数与方法签名，消息元素常量与可选字段，`IKernelMsgListener` 回调集合，OIDB 命令号、子命令与响应字段，以及 `ExtraSvc` 用到的回调接口名与结构体字段名。

## 构建与测试

首次构建需 Android 35 平台、R8 与 `org.json`：

```sh
curl -fsSL -o libs/r8.jar https://maven.google.com/com/android/tools/r8/8.9.35/r8-8.9.35.jar
curl -fsSL -o libs/json.jar https://repo1.maven.org/maven2/org/json/json/20250517/json-20250517.jar
./build.sh
./test.sh
```

产物为 `build/SatoriQQ.apk` 与 `build/SatoriQQ-module.zip`。模块不含第三方原生依赖：`native/satori.cpp` 用 Termux 的 clang 编译，不含界面代码的 dex 用 `.incbin` 内嵌进 `.so`，NEEDED 只有 `liblog/libdl/libm/libc`。

`test.sh` 跑 JVM 单测，含 `Reflect` 反射层的语义测试，开头先查 import 规范。Zygisk 把线上那份钉在内存里，改了 Java 层又不想重启手机时，
`tests/art/run.sh`（root）在真 ART 的 `app_process` 里起一个没有 QQ 内核的 `SatoriHub`，再用黑盒探针 `tests/conformance.py` 打它：
验 D8 脱糖、类库差异与 HTTP / 事件面，探针里 `kernel_offline` 的 6 个 503 是预期的。真机巡检脚本要求 QQ 已上线，且显式给测试群。破坏性用例（改群名、全员禁言）再加 `SATORI_DESTRUCTIVE=1`：

```bash
SATORI_TEST_GROUP=<群号> node tests/ws-feature-sweep.js
SATORI_TEST_GROUP=<群号> SATORI_DESTRUCTIVE=1 node tests/ws-write-sweep.js
node tests/ws-health.js              # 健康与自检
node tests/ws-acumen-smoke.js        # 客户端视角的冒烟：协议方法、事件与扩展动作
node tests/ws-poke.js                # 戳一戳：出站 OIDB、入站灰条事件与参数校验
node tests/media-live-probe.js voice # 语音条与文件能不能真发出去，见脚本头注释
```

管理界面另有一套真机验收，需要已装机与 root：

```bash
bash tests/ui/run.sh
```

它输出深浅两色、大字号与宽屏的长页截图到 `build/design-tests/design-review/`。真机用例只读写知弦自己的设置文件，跑完还原。
