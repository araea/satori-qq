# 架构（QQ 9.3.55）

```
Main            只在 QQ 主进程：MapsHide → AntiDetect → OneBotHub → QQClient
Cfg / L         配置；日志 tag Q.Kernel（verbose 才打 XposedBridge）
net/            手写正向 WS，只绑 127.0.0.1
core/           动作分发、事件、lifecycle、message_id / file_id 注册表
qq/             NT 桥、段转换、Silk、GOT+seccomp
packet/         OIDB / 长消息 / 群文件
native/         libmapshide.so
```

**收** `IKernelMsgListener.onRecvMsg` → 事件。**发** `send_msg`：整段 `node` 走合并转发，否则 `sendMsg`。**OIDB** 走 `onSendSSORequest`（不要 `onSendOidbRequest`，会把 0x8FC 拼成 `0x2300`）。QQ 自己做 SSO / QSec 签名。

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
- QSec：`getSign(String, byte[])` 不动；`detectMethod` / `getXpsInfo` 可中和；`getFeKitAttach` 只计数

升版本清单：[`STACK.md`](STACK.md#qq-升版本)。
