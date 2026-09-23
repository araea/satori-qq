# 架构

记录维护模块所需的结构、QQ 接口与升级检查项。按 QQ 9.3.65（NT，versionCode 16240）核验。

## 进程与组件

| 组件 | 职责 |
| --- | --- |
| `Main` | 只在 QQ 主进程启动 Satori 服务，其他进程不加载模块代码 |
| `Cfg` / `L` | 文件配置与日志，详细日志默认关闭 |
| `ui` | 知弦管理界面与两个快捷设置磁贴，在应用自身进程运行，前台只读探测状态 |
| `guard` | App → root `qqguard` 的调用桥：只拼写死的 `su -c` 命令，不接收任何界面/网络输入 |
| `control` | 私有设置、UID 校验的 Provider、启动时配置同步 |
| `net` | HTTP 与事件 WebSocket，只监听 `127.0.0.1` |
| `core` | 方法分发、事件、消息与文件标识管理 |
| `satori` | Satori 元素与数据结构转换 |
| `qq` | NT 会话、消息、媒体与保活 |
| `packet` | OIDB 与合并转发封包 |

HTTP 服务、消息监听与保活只在主进程运行，APK 里不含 native 库。

## 知弦管理通道

`ui.MainActivity` 与 `control.ControlProvider` 在应用自身进程。Provider 只允许模块自身 UID 与当前
安装的 `com.tencent.mobileqq` UID 调用（其余调用方含 shell 抛 `SecurityException`），只支持读配置与
运行快照，不接受文件路径、命令或 HTTP 写设置。

`Main` 尽早安装 QQ hooks。`SatoriHub.start()` 在独立工作线程等 Application 基础 Context attach
（至多 10 秒），Provider 短暂不可用时再重试至多 1.2 秒；从 Provider 读到的端口、令牌与四个运行偏好
应用后才绑定 HTTP 端口，其余高级配置不被覆盖，Provider 不可用时保留原文件或默认行为。
`/healthz.config_revision` 是此进程读到的配置修订号，用来区分已保存与已生效。

运行快照写进 `noBackupFilesDir/zhixian-control.json`，用 AtomicFile 原子更新；不用 SharedPreferences，
它可能被模块框架重定向。首页读现有 `/healthz`，限定回环地址、超时与响应大小，不跟随重定向。
诊断报告按字段白名单构造，排除账号、令牌与消息。保存配置不强停 QQ，也不热切换端口。

## 管理界面

界面分三层，依赖单向：**令牌**（`res/values/tokens.xml` 颜色、`res/values/dimens.xml` 其余尺度）→
**组件**（`ui/Tokens` 唯一读口，`Btn` `Item` `Field` `Notice` `Dialogs` `Snackbar` `TopBar` `Toggle`
`LoadingIndicator`，形状 `Shape`、图标 `Icon`、弹簧 `Spring`、响应式 `Layout`）→ **页面**（`HomePage`、
`SettingsPage`，由 `MainActivity` 组织导航、数据与系统集成）。`ui/Status` 是纯函数的状态推导，只依赖
org.json，JVM 上可测。设计取舍、令牌取值与组件契约见 [`DESIGN.md`](DESIGN.md)。

`build.sh` 先用 aapt 生成 `R.java` 再 javac：令牌经 `R` 引用，名字拼错在编译期失败。视图不解析 XML 布局，
直接构造；设置页首次进入时才建。关键视图的稳定 id 在 `res/values/ids.xml`，真机验收按 id 查找。

线程：设置文件读写与 `/healthz` 探测走一条工作线程，`su` 走另一条（最长 20 秒超时，不能堵住保存与刷新），
主线程不做 I/O。只在前台轮询；root 状态只在回到前台与操作后读取。

「重新启动 QQ」（`GuardCommand.relaunchQQ`）不能直接 `am force-stop`：看守把系统的强行停止当作用户意愿，
会自己转入 PAUSED。所以它先记下模式、保活中就先暂停，关掉 QQ 并用 `monkey` 重新打开，再恢复保活。

APK 里的 dex 包含全部代码；注入 QQ 的那份（内嵌进 `libsatori.so`）去掉了 `ui/`、`guard/` 与 `R`，
QQ 进程从不加载它们。

验收分两段：`./test.sh` 跑令牌合约、状态推导与界面契约；`bash tests/ui/run.sh` 装机跑真机验收并把深浅色、
大字号、宽屏的长页截图写进 `build/design-tests/design-review/`。真机用例只读写知弦自己的设置文件，跑完还原。

## HTTP 路由

三条通道各自独立，都在 `core/SatoriHub` 的 `onHttp` 里分派：

| 路径 | 鉴权 | 用途 |
| --- | --- | --- |
| `POST /v1/{resource}.{method}` | 需要（配了 token 时） | Satori 标准方法 |
| `POST /v1/internal/{name}` | 需要 | 模块自身的 QQ 扩展简写，`name` 可用 `.` / `_` / `-` 分隔，也接受 camelCase。0.17.0 起只剩出站动作、合并转发解析与运维口，其余一律 404 |
| `POST /v1/internal/{platform}/{selfId}/_api/{name}` | 需要 | `@satorijs/adapter-satori` 的 `bot.internal.*` 走法，参数按 `JsonForm` 编码，`Satori-Pagination: true` 时回 `{data, …}` |
| `GET /v1/internal/{platform}/{selfId}/_tmp/{id}` | 免 | `upload.create` 返回的 `internal:` 资源回落地址 |
| `GET /v1/assets/{id}` | 免 | 无令牌可达的本地图片资源（Koishi 渲染 `<img>` 用） |
| `GET /v1/proxy/{url}` | 免 | 只代理本机登录自己的 `internal:` 资源 |
| `GET /healthz` | 免 | 运维探针 |

免鉴权的三条只服务本机登录自己，且只认模块签发的不透明 id；请求指向别的 platform 或 selfId 一律 404。

`qq/ExtraSvc` 承载扩展动作的内核服务调用：个人资料、群设置、好友关系、最近联系人、富媒体与机器人。
这些服务主线程亲和，从 HTTP 工作线程直接调用会立即返回而回调永不触发，所以 `ExtraSvc` 把调用投递到
主 Looper，再由工作线程等回调。回调按 `(int code, String msg, <payload>...)` 的形状匹配，不按方法名：
`IOperateCallback` 的签名是 `onResult(int, String)`，不带结果，需要返回结构的读取要用各自的回调接口，
例如 `IGroupMemberHonorCallback`、`IKernelRecentGetContactCallback`，第三个参数才是 payload。
不回调的入口一律不进模块，否则调用方要等满 15 秒超时。新增动作前先核三件事：类名在不在
QQ 的 dex 类索引里、参数结构体的字段名（对单个类做反编译核对）、入口会不会回调
（现场探测）。`packet` 只在协议需要直接发包时使用，不与内核服务混用。

## 消息链路

- 接收：`IKernelMsgListener.onRecvMsg` → 元素转换 → `message-created`
- 发送：`message.create` → 元素转换 → `sendMsg`；合并转发由 `forward_mode` 选原生或兼容路径
- 历史：`getMsgs`，按 `message_seq` 分页；撤回：`recallMsg`
- 媒体：`IKernelRichMediaService` 下载；发送文件先写进 QQ 媒体目录，再交给内核上传
- OIDB：`onSendSSORequest` 发送，SSO 元数据与 QSec 签名仍由 QQ 负责

在线状态同时要求账号、NT 会话、消息服务与当前监听器可用，且前台不是登录页。离线写操作返回 Satori
状态码 `1500`。

## 群资料写入

`channel.update` 换群头像走 `setHeader`；改群名只走 NapCat 同款的一条内核路径
`modifyGroupName(group, name, isNormalMember)`：先按 `false` 调一次，结果码 1287 时把身份参数翻成
`true` 再试一次。不做二次写入，也不回读校验——内核缓存滞后是常态，回读不匹配不代表没生效，而一次改名
写两次才会撞上 QQ 的改名频率限制与平台处置。空名字一律拒绝。

名字守卫只观察：发现某个群名字为空先全量刷新，刷新后仍为空只记一行
`name_guard=empty:<群>(见过=<名字>)`，一个字也不写。

## QQ Native 接口

会话由反射 `IKernelService.getWrapperSession()` 取得，并通过 `getMsgService` 与 `getGroupService`
补获已存在的会话。

| 能力 | 入口 → 接口 |
| --- | --- |
| 消息 | `getMsgService` → `IKernelMsgService` |
| 群与成员 | `getGroupService` → `IKernelGroupService` |
| 用户资料 | `getProfileService` → `IKernelProfileService` |
| 好友 | `getBuddyService` → `IKernelBuddyService` |
| 富媒体 | `getRichMediaService` → `IKernelRichMediaService` |
| 最近联系人 | `getRecentContactService` → `IKernelRecentContactService` |
| 机器人 | `getRobotService` → `IKernelRobotService` |

UIN 转 UID 走资料服务上的 `getUidByUin`。读操作的返回结构由 `SatoriHub.toJson` 反射导出，QQ 增删
字段时跟着变，不会静默丢字段。

会话上共有 54 个 `get*()` 入口，模块只用上面 7 个。完整入口表、各服务的方法面，以及本层的功能边界
（可达、受限、不可行）见 [`JNI_CAPABILITIES.md`](JNI_CAPABILITIES.md)。

私聊 `Contact` 使用 UID，群聊使用群号。可选字段必须通过 `Ref.getOrNull` 探测。QQ 9.3.60 已移除
`MsgRecord.senderRoleType` 与 `RevokeElement.senderUid`；群成员角色来自 `getAllMemberList` 缓存，
不用 `MsgRecord.roleType` 或 `roleId`。

## 常驻与诊断

在线时模块保持 QQ 的服务处于「已启动」状态（见 README 常驻），写入期间自动持 CPU 与 Wi-Fi 锁，
有客户端连接时可长期持 Wi-Fi 锁。`GET /healthz` 返回在线、监听、配置修订、保活、心跳、唤醒锁、SSO、
compat 与名字守卫状态；`keepalive` 报 `adj`（当前优先级，低于冻结阈值 900 就不会被冻）、`wchan`
（`do_freezer_trap` 即被冻）与 `service`（起服务结果）。`heartbeat` 报服务端 WebSocket ping 的
`pings / pongs / reaped`：`WsConn` 记录最后入站帧，两个心跳周期没回包的半开连接会被关掉。

root 侧的 `qqguard`（`scripts/qqguard.sh`，详见 [`GUARD.md`](GUARD.md)）与注入层解耦：以
`/system/bin/sh` 运行，只依赖系统与 KernelSU 原生能力，负责进程死亡恢复、freezer 解冻、Doze/
AppOps/Data Saver 配置与开机状态恢复。它以落盘的 ARMED/PAUSED 区分保活与用户主动停止，重启有
冷却、每小时预算、指数退避与连续崩溃保护。状态与日志在 `/data/adb/satori-qq/`，模块自带的
`service.sh` 在 late_start 阶段调 `qqguard boot`。

状态通知的补发：QQ 回到前台会清自家通知，`StatusNotice.update` 每轮用
`getActiveNotifications()` 检查自己那条是否还在，被清掉就用同样内容补发；
`/healthz.notice` 的 `reposts` 记录补发次数。

状态通知的点击目标是宿主包的 launcher activity。`PendingIntent` 用 `getLaunchIntentForPackage`
解析一次后缓存，返回的 Intent 带 `FLAG_ACTIVITY_NEW_TASK`，QQ 在后台时回到原任务而不是新建。

ColorOS 会把通知小图标换成**发通知那个应用**的 launcher 图标。`OplusNotificationFixHelper.fixSmallIcon`
把原图标挪到 `oplus_small_icon` 附加项、写 `oplus_smallicon_use_app_icon=true`，再取
`getApplicationInfoAsUser(pkg).icon`（系统应用、平台签名、营销通知与 OPLUS 自家包名跳过）；
SystemUI 的 `OplusNotificationSmallIconUtil.useAppIconForSmallIcon` 只读那个布尔项。模块在 QQ 进程里
发通知，`pkg` 与 `opPkg` 都是 QQ，且 `android.appInfo` 会被 system_server 覆写、伪造 `opPkg` 过不了
uid 校验，所以常驻通知只能显示 QQ 图标；要显示模块自己的图标，只能由 `com.satori.qq` 自己的进程发。

## QQ 升级检查

升级 QQ 后先跑一次自检，再逐项核：

```sh
curl -s -X POST http://127.0.0.1:3001/v1/internal/compat -d '{}'
```

`internal/compat` 用反射核对模块依赖的内核接口面，不发任何内核请求，几毫秒出结果：

- `static.types`：模块引用到的 128 个内核类（接口、结构体、枚举）在不在。表由源码里的类名字面量生成，
  重新生成的办法写在 `qq/Compat` 的注释里
- `static.services`：会话上 7 个服务入口（`getMsgService` 等）与它们必须实现的接口
- `static.structs`：模块会写入的结构体字段名。字段改名是静默失败：`Ref.put` 找不到字段就退化，
  值写不进去，服务端只回参数错误
- `static.callbacks`：回调接口有没有 `on*` 方法（参数个数按「至少」算，内核回调常多带参数）
- `observed`：`ExtraSvc.call` 记录的每次内核调用结果（成功、超时、其它失败），按 label 分列。
  哪个入口开始不回调看这里

`static.ok` 为 `false` 时 `missing` 逐条给出断点。`/healthz` 里的 `compat` 是同一份报告的摘要
（按 QQ 版本缓存 10 分钟），`internal/compat` 传 `force=true` 可强制重算。

接口面干净之后再逐项核：`nativeinterface` 类名、构造函数与方法签名；消息元素常量与可选字段；
`IKernelMsgListener` 回调集合；OIDB 命令号、子命令与响应字段；`ExtraSvc` 用到的回调接口名与结构体
字段名，以及哪些入口开始或停止回调（看 `internal/compat` 的 `observed`）。

ColorOS 的关联启动策略可能拒绝冷启动 Provider。桥接有限重试后回退原文件配置，并通过 `config_status`
暴露不含敏感信息的原因；设置页给出系统设置里的路径（应用 → 关联启动）。引导线程在 QQ 主线程初始化
任务之后启动，不阻塞 Application 创建。

## 构建与测试

首次构建需 Android 35 平台、R8 与 `org.json`：

```sh
curl -fsSL -o libs/r8.jar https://maven.google.com/com/android/tools/r8/8.9.35/r8-8.9.35.jar
curl -fsSL -o libs/json.jar https://repo1.maven.org/maven2/org/json/json/20250517/json-20250517.jar
./build.sh
./test.sh
```

产物为 `build/SatoriQQ.apk` 与 `build/SatoriQQ-module.zip`。模块不含第三方原生依赖：`native/satori.cpp`
用 Termux 的 clang 编译，不含界面代码的 dex 用 `.incbin` 内嵌进 `.so`，NEEDED 只有 `liblog/libdl/libm/libc`。

`test.sh` 跑 JVM 单测（含 `Reflect` 反射层的语义测试）。真机巡检脚本要求 QQ 已上线，且显式给测试群；
破坏性用例（改群名、全员禁言）再加 `SATORI_DESTRUCTIVE=1`：

```bash
SATORI_TEST_GROUP=<群号> node tests/ws-feature-sweep.js
SATORI_TEST_GROUP=<群号> SATORI_DESTRUCTIVE=1 node tests/ws-write-sweep.js
node tests/ws-health.js              # 健康与自检
node tests/ws-acumen-smoke.js        # 客户端视角的冒烟：协议方法、事件与扩展动作
node tests/ws-poke.js                # 戳一戳：出站 OIDB、入站灰条事件与参数校验
node tests/media-live-probe.js voice # 语音条与文件能不能真发出去，见脚本头注释
```

管理界面另有一套真机验收（需要已装机与 root，出深浅色、大字号与宽屏截图）：

```bash
bash tests/ui/run.sh
```

## 版本与发布

`versionName` 走语义化版本 `主.次.补丁`，从 0.9.0 开始。主版本对应 Satori 方法表或 `/v1/internal`
动作的破坏性变更；次版本对应新增方法、动作、事件，以及对 QQ 的行为适配这类影响兼容性的改动；补丁版本
对应修 bug、改文案、改默认值。需要区分同一天发的多次构建时，第 4 段临时当构建号用（`0.9.1.2`），
下一次发版并回三段。

`versionCode` 独立递增。Android 判升级与模块管理器判「有新版本」用的都是它，`versionName` 只负责给
人看。版本号要同步改两处：`AndroidManifest.xml` 与 `SatoriHub.APP_VERSION`，`tests/ManifestTest`
会校验两者一致。

发布只在 [araea/satori-qq](https://github.com/araea/satori-qq) 上打 GitHub Release，tag 为
`{versionCode}-{versionName}`，正文取 `changelogs/CHANGELOG-{versionName}.md`。0.23.0 起不再是 Xposed
模块，不再向 Xposed 市场发布。

0.9.0 之前的三段固定成 `0.8.9`、只递增第 4 段（`0.8.9.1` 到 `0.8.9.46`），文档里那些 `0.8.9.x 起`
的说法指的是旧编号。两套编号的对应关系见 [`changelogs/`](../changelogs/) 下的变更记录。
