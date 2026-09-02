# 架构（QQ 9.3.55）

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

会话：`IQQNTWrapperSession$CppProxy` 构造。服务：`getMsgService` / `getGroupService` / `getProfileService` / `getBuddyService` / `getRichMediaService`。

- 发：`sendMsg(msgId, Contact, elems, emptyAttrs, cb)`；`Contact(chatType, peerUid, "")`；群 peer=群号，私聊 peer=**uid**
- uin→uid：`getUidByUin`
- 听：`IKernelMsgListener`（~40 方法，Proxy 漏一个就挂）
- 历史：`getMsgs`；撤回：`recallMsg`
- 好友：`getBuddyListFromCache`（枚举拼写 `KNOMAL`）+ `getCoreInfo`
- `MsgConstant.KELEMTYPE*`：TEXT1 PIC2 FILE3 PTT4 VIDEO5 FACE6 REPLY7 GRAYTIP8 ARK10 MARKETFACE11 MULTIFORWARD16
- 图：`genFileMd5Hex` → `getRichMediaFilePathForMobileQQSend` → `copyFile` → `PicElement` → sendMsg 自动上传
- 语音：`SilkCodecWrapper.encode`（本机 VideoElement **无** fileWidth/fileHeight）
- QSec：`getSign(String, byte[])` 不动；`detectMethod` / `getXpsInfo` 可中和；`getFeKitAttach` 只计数；`trpc.o3.report` 可丢（不要动 `ecdh_access`）

升级 QQ 后需重新核验上述 JNI 类、方法签名、元素字段和 QSec 命令白名单。
