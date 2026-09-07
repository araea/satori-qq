# 架构（QQ 9.3.60.40970）

```
Main            QQ 所有进程：MapsHide + AntiDetect；仅主进程再 SatoriHub → QQClient
Cfg / L         配置；日志 tag Q.Kernel（verbose 才打 XposedBridge）
net/            手写 HTTP + 事件 WS，只绑 127.0.0.1
core/           Satori 方法分发、事件、message id / file id 注册表
satori/         元素解析与事件映射
qq/             NT 桥、段转换、Silk、GOT+seccomp
packet/         OIDB / 长消息 / 群文件
native/         libmapshide.so
```

## 双清单（Duck Detector 对抗）

`AndroidManifest.xml`（引导）带全套 Xposed meta-data 供 vector 列模块与注册；
`AndroidManifest.stealth.xml`（隐身）零 `xposed*` 键，仅保留 `assets/xposed_init`。
依据：LSPosed 守护进程解析已启用模块只看入口文件（`ConfigManager.getModuleApkPath`
+ `ConfigFileManager.loadModule`），`PACKAGE_REPLACED` 不在其广播处理之列，
meta-data 仅管理器 UI 与 `PACKAGE_ADDED` 注册路径需要。先启用引导包，再
`pm install -r` 隐身包；升级照旧（build.sh 步骤 6/7 产出并断言双包）。

**收** `IKernelMsgListener.onRecvMsg` → Satori `message-created`。**发** `message.create`：`<message forward>` 走合并转发，否则 `sendMsg`。**OIDB** 走 `onSendSSORequest`（不要 `onSendOidbRequest`，会把 0x8FC 拼成 `0x2300`）。QQ 自己做 SSO / QSec 签名。

在线：账号 + NT session + MsgService + 当前 listener，且栈顶不是 `LoginActivity`。离线动作 1500。进程被杀只能靠 watchdog 拉起。

富媒体：`RichMediaElementGetReq(msgId, peerUid, chatType, elementId, 1, 0, "", fileModelId, 0, 1)` → `downloadRichMedia`；视频失败再 `getVideoPlayUrlV2`。群文件 0x6D8 查、0x6D6 文件、0x6D7 目录。

## JNI（`nativeinterface`，避开混淆 `api.*`）

会话：hook `IQQNTWrapperSession$CppProxy` 构造捕获。兜底：另 hook `getMsgService` / `getGroupService`（无锁身份校验短路），捕获 hook 安装前已建、构造函数漏抓的活动会话——否则会出现 QQ 在线、HTTP 端口在听、却 `isOnline()` 恒 false、发送全 1500 的隐性离线。服务：`getMsgService` / `getGroupService` / `getProfileService` / `getBuddyService` / `getRichMediaService`。

- 发：`sendMsg(msgId, Contact, elems, emptyAttrs, cb)`；`Contact(chatType, peerUid, "")`；群 peer=群号，私聊 peer=**uid**
- uin→uid：`getUidByUin`
- 听：`IKernelMsgListener`（~40 方法，Proxy 漏一个就挂）
- 历史：`getMsgs`；撤回：`recallMsg`
- 好友：`getBuddyListFromCache`（枚举拼写 `KNOMAL`）+ `getCoreInfo`
- `MsgConstant.KELEMTYPE*`：TEXT1 PIC2 FILE3 PTT4 VIDEO5 FACE6 REPLY7 GRAYTIP8 ARK10 MARKETFACE11 MULTIFORWARD16
- 图：`genFileMd5Hex` → `getRichMediaFilePathForMobileQQSend` → `copyFile` → `PicElement` → sendMsg 自动上传
- 语音：`SilkCodecWrapper.encode`（本机 VideoElement **无** fileWidth/fileHeight）
- QSec：`getSign(String, byte[])` 不动；`detectMethod` / `getXpsInfo` 可中和；`getFeKitAttach` 只计数；`trpc.o3.report` 可丢（不要动 `ecdh_access`）

QQ 9.3.60 移除了 `MsgRecord.senderRoleType` 及 `RevokeElement.senderUid`；可选兼容字段必须经
`Ref.getOrNull` 探测，不能让缺字段中断整条消息事件。升级 QQ 后需重新核验上述
JNI 类、方法签名、元素字段和 QSec 命令白名单。新版 `MsgRecord.roleType/roleId`
不是群成员身份，群消息角色从 `getAllMemberList` 缓存解析。

## 常驻 / 防杀 / 网络保持（分层评估）

分层清晰，各司其职，改进只补短板、不越界：

- **进程存活 / 防后台杀**：交给外部 root 看门狗（`scripts/qq-satori-watchdog.sh`，
  KernelSU/Magisk `service.d` 起）。这是唯一有效的层级——国产 ROM 的后台冻结
  （ColorOS `OplusHansManager` cgroup v2 freezer）与 o-stop 杀进程只有 root 能对抗：
  1s 解冻循环写 `uid_*/cgroup.freeze` 及 `pid_*` 子组、`bpm.xml`/`key_proc.xml` 持久
  白名单、deviceidle/standby/appops 放行、进程死后 monkey 拉起。进程内任何"保活"技巧
  都打不过内核级冻结，故不重复造轮子；也刻意不写 `oom_score_adj`（fekit 会读）。
- **网络保持**：Satori HTTP/WS 只绑 `127.0.0.1`，无对外连接可掉；真正的长连是 QQ 自身
  的 MSF，由看门狗解冻 + CoreService/MsfService 保活维系。HTTP accept 循环自带 3s 重绑，
  端口丢失可自愈（除非宿主进程被杀——那是看门狗的活）。
- **可观测性**（本版新增，补"隐性离线"盲区）：以前"在线"只能从端口 + activity dump
  反推，正是本文反复提到的"端口在听却 `isOnline()` 恒 false"盲区。现补两个同源状态出口：
  - `GET /healthz`（本地免鉴权）直吐 `online` / `listening` / `self_id` / 在线时长，
    机器可读，看门狗与运维可据此把"真在线"与"端口在听但内核离线"分开。
  - QQ 通知栏常驻一条静默（`IMPORTANCE_LOW`）通知，随状态切换 运行中 / 等待登录 /
    服务异常，人可读。以 QQ 自身 `Context` + 通知权限发出，模块不声明任何权限；它不是
    前台服务、不提供保活权重，只作状态指示。可用 `status_notification: false` 关闭。
    发出需 QQ 的 `POST_NOTIFICATIONS`（看门狗 `protect_qq` 自动 `pm grant`）；且 QQ 被
    ColorOS 后台冻结时系统会丢弃其通知投递——故这条通知能否显示，本身就是"看门狗是否
    在解冻 QQ"的粗粒度信号，与 `/healthz` 互为人/机读双证。

结论：把只有 root 能做的重活外置于脚本、进程内只做自愈与状态上报，是合理且优雅的分层。
本版改进集中在补齐可观测性，而非新增易被检测、又打不过内核冻结的进程内保活。
