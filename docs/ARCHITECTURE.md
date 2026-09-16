# 架构

记录维护模块所需的结构、QQ 接口与升级检查项。按 QQ 9.3.55 与 9.3.60.40970（NT）核验。

## 进程与组件

| 组件 | 职责 |
| --- | --- |
| `Main` | 在所有 QQ 进程安装过检测；仅在主进程启动 Satori 服务 |
| `Cfg` / `L` | 文件配置与日志，详细日志默认关闭 |
| `ui` | 知弦管理页，应用自身进程运行；前台只读探测状态 |
| `control` | 私有设置、UID 校验的 Provider、启动时配置同步 |
| `net` | HTTP 与事件 WebSocket，仅监听 `127.0.0.1` |
| `core` | 方法分发、事件、消息与文件标识管理 |
| `satori` | Satori 元素与数据结构转换 |
| `qq` | NT 会话、消息、媒体、保活与过检测 |
| `packet` | OIDB、长消息与群文件封包 |
| `native` | 检测库 GOT、`/proc` 与直接系统调用过滤 |

主进程与 `:MSF` 进程都加载 `AntiDetect` 与 `MapsHide`。HTTP 服务、消息监听与保活组件只在主进程运行。

## 知弦管理通道

普通 APK 声明 `ui.MainActivity`；stealth APK 不声明 Activity，两者都声明 `control.ControlProvider`。Provider 只允许模块自身 UID 与当前安装的 `com.tencent.mobileqq` UID 调用，其他调用方（包括 shell）抛出 `SecurityException`。只支持获取配置和发布运行快照，不接受文件路径、命令或 HTTP 写设置。

`Main` 保持尽早安装 QQ hooks。`SatoriHub.start()` 在独立工作线程等待 Application 基础 Context 完成 attach（至多 10 秒，Provider 短暂不可用时额外重试至多 1.2 秒），然后通过 Provider 读取经完整校验的端口、令牌和四个运行偏好，应用后才绑定 HTTP 端口。其他高级配置不被覆盖。Provider 不可用时保留原文件/默认行为。`/healthz.config_revision` 表示此进程读取的配置修订号；应用用它区分已保存与已生效。

运行快照包含实际端口和配置，与设置共同写入 `noBackupFilesDir/zhixian-control.json`，通过 AtomicFile 原子更新；不使用可能被模块框架重定向的 SharedPreferences，保证普通版与 stealth 版切换使用同一份私有配置。状态页读取现有 `/healthz`，限定回环地址、超时与响应大小，不跟随重定向。诊断报告按字段白名单构造，排除账号、令牌与消息。配置保存不会强停 QQ，也不热切换端口，避免破坏正在工作的客户端会话。

## HTTP 路由

三条通道各自独立，都在 `core/SatoriHub` 的 `onHttp` 里分派：

| 路径 | 鉴权 | 用途 |
| --- | --- | --- |
| `POST /v1/{resource}.{method}` | 需要（配了 token 时） | Satori 标准方法 |
| `POST /v1/internal/{name}` | 需要 | 模块自身的 QQ 扩展简写，`name` 可用 `.`/`_`/`-` 分隔，也接受 camelCase |
| `POST /v1/internal/{platform}/{selfId}/_api/{name}` | 需要 | `@satorijs/adapter-satori` 的 `bot.internal.*` 走法，参数按 `JsonForm` 编码，`Satori-Pagination: true` 时回 `{data, …}` |
| `GET /v1/internal/{platform}/{selfId}/_tmp/{id}` | 免 | `upload.create` 返回的 `internal:` 资源回落地址 |
| `GET /v1/assets/{id}` | 免 | 无令牌可达的本地图片资源（Koishi 渲染 `<img>` 用） |
| `GET /v1/proxy/{url}` | 免 | 只代理本机登录自己的 `internal:` 资源 |
| `GET /healthz` | 免 | 运维探针 |

免鉴权的三条只服务本机登录自己、且只认模块签发的不透明 id；请求指向别的 platform 或 selfId 一律 404。

`qq/ExtraSvc` 承载扩展动作的内核服务调用：个人资料、群设置、好友关系、最近联系人、富媒体与机器人。这些服务是主线程亲和的。从 HTTP 工作线程直接调用会立即返回，回调永不触发，因此 `ExtraSvc` 把调用投递到主 Looper，再由工作线程等待回调。`IOperateCallback` 的签名是 `onResult(int, String)`，不带结果；需要返回结构的读取要用各自的回调接口，例如 `IGroupMemberHonorCallback`、`IKernelRecentGetContactCallback`，第三个参数才是 payload。部分方法在 9.3.55 上不回调，例如 `getGroupShutUpMemberList`，改用同类替代方法（`queryGroupMuteMemberList`、`getRecentContactInfos`）。`packet` 只在协议需要直接发包时使用，不与内核服务混用。

新增动作前先核三件事：接口在不在（`~/tmpqq/dexindex.txt` 查类名）、参数结构体的字段名（`~/tmpqq/dec_class.sh` 单类反编译）、**这个入口会不会回调**（现场探测，不回调的一律不进模块，否则调用方白等 15 秒）。`getOnLineDev`、`getNextMemberList`、`prepareRegionConfig` 就是这样被排除的，逐条记在 [`SATORI_SUPPORT.md`](SATORI_SUPPORT.md#内核可用性)。

## 消息链路

- 接收：`IKernelMsgListener.onRecvMsg` → 元素转换 → `message-created`
- 发送：`message.create` → 元素转换 → `sendMsg`；合并转发由 `forward_mode` 选择原生或兼容路径
- 历史：`getMsgs`，按 `message_seq` 分页
- 撤回：`recallMsg`
- 媒体：由 `IRichMediaService` 下载；发送文件先写入 QQ 媒体目录，再交给内核上传
- OIDB：通过 `onSendSSORequest` 发送，SSO 元数据与 QSec 签名仍由 QQ 负责

在线状态同时要求账号、NT 会话、消息服务与当前监听器可用，且前台不是登录页。离线写操作返回 Satori 状态码 `1500`。

## QQ Native 接口

会话从 `IQQNTWrapperSession$CppProxy` 构造函数捕获，并通过 `getMsgService` 与 `getGroupService` 补获已存在的会话。

| 能力 | 接口 |
| --- | --- |
| 消息 | `IMsgService` |
| 群与成员 | `IGroupService` |
| 用户资料 | `IProfileService` |
| 好友 | `IBuddyService` |
| 富媒体 | `IRichMediaService` |
| UIN 转 UID | `getUidByUin` |

`qq/ExtraSvc` 里所有内核回调都按 `(int code, String msg, <payload>...)` 的形状匹配，不按方法名匹配：`IFetchFavEmojiListCallback` 的回调叫 `onFetchFavEmojiListCallback`，只认 `onResult` 的写法会让这类调用每次都等到超时。读操作的返回结构由 `SatoriHub.toJson` 反射导出，QQ 增删字段时跟着变。

内核入口是否接线要现场核。9.3.60 上 `fetchFavEmojiList`、`queryFavEmojiByDesc`、`getGroupExtList`、`searchGroupFileByWord` 接受调用但不回调，`searchGroupFile` 同步返回 -1，`setGander` 回「暂未实现」。未接线又会让调用方白等 15 秒的入口不提供动作，只在 [`SATORI_SUPPORT.md`](SATORI_SUPPORT.md#内核可用性) 里记录。

私聊 `Contact` 使用 UID，群聊使用群号。可选字段必须通过 `Ref.getOrNull` 探测。QQ 9.3.60 已移除 `MsgRecord.senderRoleType` 与 `RevokeElement.senderUid`。群成员角色来自 `getAllMemberList` 缓存，不使用 `MsgRecord.roleType` 或 `roleId`。

## 过检测

Java 层处理 Root、Xposed、调试器、包、堆栈、Pandora、Turing 与环境上报。Native 层只修补检测库的 GOT，并过滤文件、属性、命令、符号、目录、进程映射与风险上报。QSec 的 `getSign` 保持原样，`getFeKitAttach` 只记录计数。检测面、逐项对应与挡不住的部分见 [`ANTIDETECT.md`](ANTIDETECT.md)。

模块提供两份清单：

- `AndroidManifest.xml` 含 Xposed 元数据，用于首次注册与设置作用域
- `AndroidManifest.stealth.xml` 不含 `xposed*` 元数据，用于启用后的覆盖安装

`build.sh` 同时生成两份 APK，并检查两份清单的包名、版本与元数据数量。

## 常驻与诊断

在线时，模块使用 QQ 已声明的 `QQDataSyncService` 启动 `dataSync` 前台服务。强停或划掉 QQ 会结束服务，不自动拉起。写操作期间自动持有 CPU 与 Wi-Fi 锁，有客户端连接时可长期持有 Wi-Fi 锁。

状态通知的点击目标是宿主包的 launcher activity，也就是 QQ 自己。`PendingIntent` 用 `getLaunchIntentForPackage` 解析一次后缓存，返回的 Intent 带 `FLAG_ACTIVITY_NEW_TASK`，QQ 在后台时回到原任务而不是新建。

`GET /healthz` 返回登录、监听、保活、唤醒锁、环境上报与 Native 隐藏自检状态。Native 自检结果写入 QQ 的**应用私有**目录 `/data/data/com.tencent.mobileqq/files/`（0.8.9.39 之前写在外部 `Android/data`，会留下可被枚举的残留），主进程可同时读取主进程与 MSF 进程状态。

## QQ 升级检查

升级 QQ 后先跑一次自检，再按下面逐项核：

```sh
curl -s -X POST http://127.0.0.1:3001/v1/internal/compat -d '{}'   # 或走客户端 client.callOk('internal/compat')
```

`internal/compat` 用反射核对模块依赖的内核接口面，不发任何内核请求，几毫秒出结果：

- `static.types`：模块引用到的 128 个内核类（接口、结构体、枚举）在不在。表由源码里的类名字面量生成，重新生成的办法写在 `qq/Compat` 的注释里
- `static.services`：会话上 7 个服务入口（`getMsgService` 等）与它们必须实现的接口
- `static.structs`：模块会写入的结构体字段名。字段改名是静默失败——`Ref.put` 找不到字段就退化，值写不进去，服务端只回参数错误
- `static.callbacks`：回调接口是否有 `on*` 方法（参数个数按「至少」算，内核回调常多带参数）
- `observed`：`ExtraSvc.call` 记录的每次内核调用结果（成功/超时/其它失败），按 label 分列。哪个入口开始不回调看这里，比对着 15 秒超时猜快得多

`static.ok` 为 `false` 时 `missing` 逐条给出断点。`/healthz` 里的 `compat` 是同一份报告的摘要（按 QQ 版本缓存 10 分钟）。`internal/compat` 传 `force=true` 可强制重算。

接口面干净之后，再按下面逐项核：

1. `nativeinterface` 类名、构造函数与方法签名
2. 消息元素常量与可选字段
3. `IKernelMsgListener` 回调集合
4. OIDB 命令号、子命令与响应字段
5. QSec、Turing 与环境上报的命令白名单，以及 QSec 检测入口的类名与方法签名
6. 主进程与 MSF 进程的 `/healthz` hook 计数及 `loop_ok`，以及 `internal/status` 的 `env_report.maps.libs` 里每个检测库各补了多少槽（某个库改名或消失会直接体现为对应项变 0）
7. `libfekit.so`、`libturingxq.so`、`libmsfbootV2.so` 导出的 libc 符号与路径字符串，见 [`ANTIDETECT.md`](ANTIDETECT.md)
8. `ExtraSvc` 用到的回调接口名与结构体字段名，以及哪些入口开始或停止回调（跑 `tests/internal-kernel-probe.js` 之后看 `internal/compat` 的 `observed`）
9. 检测库 import 的字符串搜索符号有没有变（`llvm-nm -D lib*.so | grep ' U '` 看是否新增 `strcasecmp`/`strnstr` 一类），变了就把 `native/mapshide.c` 的 `BLOCK` 判定接到同一个入口上，见 [`ANTIDETECT.md`](ANTIDETECT.md)
10. Turing 的 POSIX ERE 黑名单（进程名/线程名/路径）有没有新增模式

ColorOS 的关联启动策略可能拒绝冷启动 Provider。桥接有限重试后回退原文件配置，并通过 `config_status` 暴露无敏感信息的原因；管理页给出系统设置中的路径（应用 → 关联启动）。引导线程在 QQ 主线程初始化任务之后启动，不阻塞 Application 创建。
