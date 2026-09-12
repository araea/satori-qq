# 架构

记录维护模块所需的结构、QQ 接口与升级检查项。当前实现按 QQ 9.3.55 与 9.3.60.40970（NT）核验。

## 进程与组件

| 组件 | 职责 |
| --- | --- |
| `Main` | 在所有 QQ 进程安装过检测；仅在主进程启动 Satori 服务 |
| `Cfg` / `L` | 配置与日志，详细日志默认关闭 |
| `net` | HTTP 与事件 WebSocket，仅监听 `127.0.0.1` |
| `core` | 方法分发、事件、消息与文件标识管理 |
| `satori` | Satori 元素与数据结构转换 |
| `qq` | NT 会话、消息、媒体、保活与过检测 |
| `packet` | OIDB、长消息与群文件封包 |
| `native` | 检测库 GOT、`/proc` 与直接系统调用过滤 |

主进程与 `:MSF` 进程均加载 `AntiDetect` 与 `MapsHide`；HTTP 服务、消息监听与保活组件只在主进程运行。

`qq/ExtraSvc` 承载扩展动作所需的内核服务调用：个人资料、群设置、好友关系与最近联系人。这些服务都是主线程亲和的，从 HTTP 工作线程直接调用会立即返回但回调永不触发，因此 `ExtraSvc` 统一把调用投递到主 Looper，再由工作线程等待回调。`IOperateCallback` 是 `onResult(int, String)`，不带结果；需要返回结构的读取要用各自的回调接口（`IGroupMemberHonorCallback`、`IKernelRecentGetContactCallback` 等，第三个参数才是 payload）。`IKernelGroupService`、`IKernelBuddyService`、`IKernelProfileService` 与 `IKernelRecentContactService` 都走这条路径；部分方法（例如 `getGroupShutUpMemberList`）在 QQ 9.3.55 上不回调，改用同类替代方法（`queryGroupMuteMemberList`、`getRecentContactInfos`）。`packet` 只在协议需要直接发包时使用，不要与内核服务混用。

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

私聊 `Contact` 使用 UID，群聊使用群号。可选字段必须通过 `Ref.getOrNull` 探测；QQ 9.3.60 已移除 `MsgRecord.senderRoleType` 与 `RevokeElement.senderUid`。群成员角色来自 `getAllMemberList` 缓存，不使用 `MsgRecord.roleType` 或 `roleId`。

## 过检测

Java 层处理 Root、Xposed、调试器、包、堆栈、Pandora、Turing 与环境上报。Native 层只修补检测库的 GOT，并过滤文件、属性、命令、符号、目录、进程映射与风险上报。QSec 的 `getSign` 保持原样，`getFeKitAttach` 只记录计数。

模块提供两份清单：

- `AndroidManifest.xml` 含 Xposed 元数据，用于首次注册与设置作用域
- `AndroidManifest.stealth.xml` 不含 `xposed*` 元数据，用于启用后的覆盖安装

`build.sh` 同时生成两份 APK，并检查两份清单的包名、版本与元数据数量。

## 常驻与诊断

在线时，模块使用 QQ 已声明的 `QQDataSyncService` 启动 `dataSync` 前台服务。强停或划掉 QQ 会结束服务，不自动拉起。写操作期间自动持有 CPU 与 Wi-Fi 锁，有客户端连接时可长期持有 Wi-Fi 锁。

`GET /healthz` 返回登录、监听、保活、唤醒锁、环境上报与 Native 隐藏自检状态。Native 自检结果写入 QQ 外部文件目录，主进程可同时读取主进程与 MSF 进程状态。

## QQ 升级检查

升级 QQ 后应重新核验：

1. `nativeinterface` 类名、构造函数与方法签名
2. 消息元素常量与可选字段
3. `IKernelMsgListener` 回调集合
4. OIDB 命令号、子命令与响应字段
5. QSec、Turing 与环境上报的命令白名单
6. 主进程与 MSF 进程的 `/healthz` hook 计数及 `loop_ok`
