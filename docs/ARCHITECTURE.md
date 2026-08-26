# onebot-qq — 架构 & QQNT 内核映射表

## 代码结构 (src/com/onebot/qq/)
```
Main.java            Xposed 入口 (IXposedHookLoadPackage)；只在 QQ 主进程跑；
                     装 AntiDetect → 起 OneBotHub → QQClient.installHooks()
Cfg.java             配置 (port/host/token/heartbeat/anti_detect)，从 QQ 可读路径的 json 读，有默认值
L.java               日志 (logcat tag=OneBotQQ + XposedBridge.log)
net/WsServer.java    手写 RFC6455 正向 WS 服务端 (握手/鉴权/分帧/掩码/心跳)，无第三方依赖
net/WsConn.java      单连接，服务端→客户端不掩码，同步写帧 (含 64-bit 长度)
core/OneBotHub.java  OneBot 协议中枢：动作分发(WS in) + 事件下发(WS out) + 响应封包
core/MsgStore.java   OneBot int32 message_id ↔ QQ NT (chatType/peer/msgId/msgSeq) 映射 + uin↔uid 缓存
qq/QQClient.java     QQ 桥：捕获会话、收发、监听、身份、群查询、uid 解析
qq/Convert.java      段↔MsgElement 转换；MsgRecord→OneBot 事件
qq/Media.java        file 解析(路径/file://http/base64) + 构建 PicElement (富媒体自动上传)
qq/AntiDetect.java   best-effort 反检测 (hook QSec.detectMethod/getXpsInfo)
qq/Ref.java          反射门面 (绑定 QQ classloader；new/call/get/set/neuTyped)
stubs/de/robv/...    Xposed API 桩 (仅编译期，不进 dex)
```

## 数据流
- **收**：`IKernelMsgService.addKernelMsgListener(动态Proxy实现IKernelMsgListener)` →
  `onRecvMsg(ArrayList<MsgRecord>)` → `Convert.recordToEvent` → `WsServer.broadcast(事件JSON)` → ayjx。
- **发**：ayjx 发 `send_msg` → `OneBotHub.dispatch` → `Convert.toElements` → `QQClient.sendMsg`
  → `IKernelMsgService.sendMsg(...)` → 回执经 `IOperateCallback` → 返回 `{message_id}`。

## QQNT 内核映射（QQ 9.3.50 实测；均为稳定 JNI 名）
> `api.*` 服务接口是**混淆**的（如 `IKernelService.getMsgService`→返回 `api.ac`），**避开**；
> 一律 hook `IQQNTWrapperSession$CppProxy` 构造函数拿会话，再用下面 `nativeinterface` 名取服务。

### 会话与服务
- 会话：`com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession$CppProxy`（hook 构造函数捕获实例）
- `session.getMsgService()` → `IKernelMsgService`
- `session.getGroupService()` → `IKernelGroupService`
- `session.getProfileService()` → `IKernelProfileService`
- `session.getUixConvertService()` / `session.getRichMediaService()`
- 自身 uin/昵称：`mqq.app.MobileQQ.getMobileQQ()` → 字段 `mAppRuntime` →
  `getCurrentUin()` / `getAccount()` / `getCurrentNickname()`

### 发消息
`IKernelMsgService.sendMsg(long msgId, kernelpublic.nativeinterface.Contact contact,`
`  ArrayList<MsgElement> elems, HashMap<Integer,MsgAttributeInfo> attrs(空即可), IOperateCallback cb)`
- `msgId = msgService.generateMsgUniqueId(int chatType, System.currentTimeMillis())`
- `Contact(int chatType, String peerUid, String guildId="")`；
  chatType：`MsgConstant.KCHATTYPEC2C=1`、`KCHATTYPEGROUP=2`。
  群：peerUid = 群号字符串；私聊：peerUid = 对方 **uid**（不是 uin！）。
- uin→uid：`IKernelProfileService.getUidByUin(String "", ArrayList<Long> uins)` → `HashMap<Long,String>`（同步）。

### 收消息 / 撤回
- 监听接口 `IKernelMsgListener`（~40 方法，用 `java.lang.reflect.Proxy` 实现，只处理需要的）：
  `onRecvMsg(ArrayList<MsgRecord>)`、`onMsgInfoListAdd/Update`、`onAddSendMsg`(自身发的)、
  `onMsgRecall(int,String,long)`。
- 撤回：`recallMsg(Contact, ArrayList<Long> msgIds, IOperateCallback)`。

### MsgRecord 公有字段
`chatType, peerUin(群号/对方uin), peerUid, senderUin, senderUid, sendNickName, sendMemberName,`
`sendRemarkName, msgId(long), msgSeq, msgTime, msgType, subMsgType, ArrayList<MsgElement> elements`

### MsgElement / 段
`elementType(int)` + 分类型子对象字段：`textElement, picElement, faceElement, replyElement,`
`pttElement, videoElement, fileElement, arkElement, marketFaceElement, multiForwardMsgElement, markdownElement`
- 类型常量 `MsgConstant.KELEMTYPE*`：TEXT=1, PIC=2, FILE=3, PTT=4, VIDEO=5, FACE=6, REPLY=7,
  GRAYTIP=8, ARKSTRUCT=10, MARKETFACE=11, MARKDOWN=14, MULTIFORWARD=16
- `TextElement`：`content`；@ 时 `atType`(1=@全体,2=@某人)、`atUid(long)`、`atNtUid(String)`
- `FaceElement`：`faceIndex(int)`, `faceType(int)`, `faceText`
- `PicElement`：`md5HexStr, sourcePath, fileName, fileSize(long), picWidth(int), picHeight(int),`
  `picType(Integer:1000jpg/1001png/2000gif), picSubType(int), original(boolean), originImageUrl`
- `ReplyElement`：`replayMsgSeq(Long), replayMsgId(long), senderUid(Long), senderUidStr, sourceMsgText`

### 图片发送（QQNT 在 sendMsg 时自动上传，无需单独 upload）
1. `md5 = QQNTWrapperUtil$CppProxy.genFileMd5Hex(path)`（静态 native）
2. `origPath = msgService.getRichMediaFilePathForMobileQQSend(RichMediaFilePathInfo(2,0,md5,fileName,1,0,null,"",true))`
   （fileType 1=原图/2=缩略图；缩略图用 `(...,2,720,...)`）
3. `QQNTWrapperUtil$CppProxy.copyFile(path, origPath)`（原图+缩略图各拷一份）
4. 建 `PicElement`（填上面字段）→ `MsgElement.elementType=2, picElement=pic` → sendMsg，QQ 自动上传。
   其它静态 native：`fileIsExist(String)`, `getFileSize(String)`。

### 群
- 成员全量：`IKernelGroupService.getAllMemberList(long groupCode, boolean force, IGroupMemberListCallback cb)`
  → `cb.onResult(int, String, GroupMemberListResult)`；`GroupMemberListResult.infos` = `HashMap<uid, MemberInfo>`。
- 群列表：`getGroupList(boolean, IOperateCallback)` 触发 → 结果经 `IKernelGroupListener.onGroupListUpdate(type,`
  `ArrayList<GroupSimpleInfo>)` 回来（我们注册 group 监听做缓存）。`GroupSimpleInfo`：`groupCode, groupName,`
  `memberCount, maxMember`。
- `MemberInfo`：`uin(long), uid, nick, remark, cardName, role(MemberRole枚举→name(): OWNER/ADMIN/MEMBER/STRANGER),`
  `memberSpecialTitle, specialTitleExpireTime, joinTime(int), lastSpeakTime(int), memberLevel(int), shutUpTime, isRobot`
- 其它已知：`kickMember(long, ArrayList<String> uids, boolean, String, cb)`、`setGroupShutUp(long, boolean, cb)`、
  `setMemberShutUp(long, ArrayList<GroupMemberShutUpInfo>, cb)`、`modifyMemberRole(long, String uid, MemberRole, cb)`、
  `modifyMemberCardName(long, String uid, String card, cb)`。（这些是 notice/后续动作用的）

### 贴表情回应
`IKernelMsgService.setMsgEmojiLikes(Contact, long msgSeq, String emojiId, long emojiType, boolean set, cb)`
（emojiType：QQ 表情=1，unicode emoji=2）

### 安全 SDK（反检测相关，见 ANTIDETECT.md）
`com.tencent.mobileqq.qsec.qsecurity.QSec`：核心 native（`getSign/doSomething/getEstInfo`）**别 hook**（登录要用）；
可安全中和的：`detectMethod(String,String)→false`、`getXpsInfo()→空`。
native 库 `libfekit.so`。`Dandelion.energy`、`QsecEst.d` 也是 native 采集。
