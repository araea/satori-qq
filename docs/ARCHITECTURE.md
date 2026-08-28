# onebot-qq — 架构 & QQNT 内核映射表

## 代码结构 (src/com/onebot/qq/)
```
Main.java            Xposed 入口 (IXposedHookLoadPackage)；只在 QQ 主进程跑；
                     装 AntiDetect → 起 OneBotHub → QQClient.installHooks()
Cfg.java             配置 (port/host/token/heartbeat/anti_detect)，从 QQ 可读路径的 json 读，有默认值
L.java               日志 (logcat tag=OneBotQQ + XposedBridge.log)
net/WsServer.java    手写 RFC6455 正向 WS 服务端 (握手/鉴权/分帧/掩码/连接生命周期)，无第三方依赖
net/WsConn.java      单连接，服务端→客户端不掩码，同步写帧 (含 64-bit 长度)
core/OneBotHub.java  OneBot 协议中枢：动作分发 + 事件下发 + 生命周期/真实在线心跳 + 响应封包
core/MsgStore.java   OneBot int32 message_id ↔ QQ NT (chatType/peer/msgId/msgSeq) 映射 + uin↔uid 缓存
                     + 富媒体 file_id ↔ 下载上下文/本地路径/URL 注册表
qq/QQClient.java     QQ 桥：捕获会话、收发、监听、身份、群查询、uid 解析
qq/Convert.java      段↔MsgElement 转换；MsgRecord→OneBot 事件
qq/Media.java        file 解析(路径/file://http/base64) + 构建 PicElement (富媒体自动上传)
qq/AudioTranscoder.java Android MediaCodec 解码/重采样/AMR-NB 编码
qq/AntiDetect.java   best-effort 反检测 (hook QSec.detectMethod/getXpsInfo)
qq/Ref.java          反射门面 (绑定 QQ classloader；new/call/get/set/neuTyped)
packet/Pb.java       零依赖 protobuf wire 编解码器 + OIDB 辅助方法
packet/PacketSvc.java QQNT 原始 OIDB 传输：IDependsAdapter 发包 + requestId 回包关联
stubs/de/robv/...    Xposed API 桩 (仅编译期，不进 dex)
scripts/*watchdog*   root 进程外守护 + KernelSU/Magisk service.d 入口
scripts/*audit*      maps/线程/日志指纹快照，供反检测 24h/72h A/B
```

## 数据流
- **收**：`IKernelMsgService.addKernelMsgListener(动态Proxy实现IKernelMsgListener)` →
  `onRecvMsg(ArrayList<MsgRecord>)` → `Convert.recordToEvent` → `WsServer.broadcast(事件JSON)` → ayjx。
- **发**：ayjx 发 `send_msg` → `OneBotHub.dispatch` → `Convert.toElements` → `QQClient.sendMsg`
  → `IKernelMsgService.sendMsg(...)` → 回执经 `IOperateCallback` → 返回 `{message_id}`。
- **OIDB**：OneBot 动作组 raw protobuf body → `PacketSvc` 加 OIDB 外层 →
  `KernelServiceImpl.getIDependsAdapter().onSendSSORequest(...)` → QQ 的 `KernelSendObserver`/
  `KernelServlet`/MSF（由 QQ 做 SSO framing/QSec 签名）→ `CppProxy.onSendSSOReply(...)` 按自分配 requestId 收回包。
  只消费 `PacketSvc` 自己的 requestId，QQ 原生请求不受影响。
  不用 `onSendOidbRequest`：本机实现会把命令 int 直接十进制拼到 `0x` 后（0x8FC 变 `0x2300`），
  真机返回 236 `cmd not found`；显式 SSO serviceCmd 后已进入正确业务路由。

## 韧性与在线状态（2026-08-27，在线路径已由主号真机验证）
- `QQClient.isOnline()` 只有在当前账号、NT session、MsgService、当前 session 的消息监听全部就绪，
  且 QQ 自己任务栈顶部不是 `com.tencent.mobileqq.activity.LoginActivity` 时才为真；`selfUin()` 的缓存
  不再被用作在线判据。任务栈通过 `ActivityManager.getAppTasks()` 读取本应用任务，不依赖跨应用权限。
- 每个 WS 客户端连接后先收到 lifecycle `connect` 和一次携带真实 `status.online/good` 的 heartbeat；
  后续定时 heartbeat 与 online↔offline 转换事件由 `OneBotHub` 的 1 秒状态监视器产生。
- `get_status` 离线时仍可调用；其它动作离线时统一快速失败 `retcode=1500`，避免阻塞/误成功。
- 捕获到替换 NT session 时会清理旧注册状态和群缓存，并把消息/群监听绑定到新 session；这覆盖 QQ
  进程仍存活时的重登录。若整个 QQ 进程被杀，WS 会断开，恢复拉起必须由进程外完成。
- root watchdog 每 15 秒区分 `online/login/qq_down/port_missing`，在模块端口长期缺失时按 5 分钟退避
  冷启 QQ，但绝不对 LoginActivity 重启循环；状态快照与累计计数分别落盘为
  `/data/adb/onebot-qq/watchdog.status` 和 `watchdog.counters`。
- `login` 判定优先于 3001 端口，并扫描 QQ 自己的后台任务栈而不是只看系统全局前台 Activity；
  近期 events 若出现 `ACCOUNT_KICKED`，额外累计 `account_kicks` 和时间戳。这样服务器踢号后即使
  模块进程/端口尚存也不会误报 online/recovered。
- 主号真机已验证 lifecycle `connect`、即时/15 秒周期 heartbeat、`get_status`、`get_login_info`，以及
  设置页强制退出→LoginActivity offline/disable→一键登录 online/enable 的完整往返。WS 未断，离线动作
  返回 1500，恢复后持续收群消息。QQ 此次复用了同一进程/CppProxy，替换成全新 session 对象的分支未触发。

## 富媒体取回（0.5.0）

- `Convert` 收到 PIC/PTT/VIDEO/FILE 时，用消息上下文和 elementId 注册 opaque file_id。
- `get_image/get_record/get_file` 先复用本地路径；缺失时构造 Android 9.3.50
  `RichMediaElementGetReq(msgId,peerUid,chatType,elementId,1,0,"",0,0,1)`。
- `QQClient` 调 `IKernelMsgService.downloadRichMedia(req)`，以 msgId+elementId 等待
  `IKernelMsgListener.onRichMediaDownloadComplete`，校验 fileErrCode 后返回 QQ 生成的 filePath；最后才用 URL。
- 字段由本机 jadx + NapCat 当前源码 + QQ.hap/libkernel 三方核对。图片/文件已真机只读通过。

## QQNT 内核映射（QQ 9.3.50 实测；均为稳定 JNI 名）
> `api.*` 服务接口是**混淆**的（如 `IKernelService.getMsgService`→返回 `api.ac`），**避开**；
> 一律 hook `IQQNTWrapperSession$CppProxy` 构造函数拿会话，再用下面 `nativeinterface` 名取服务。

### 会话与服务
- 会话：`com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession$CppProxy`（hook 构造函数捕获实例）
- `session.getMsgService()` → `IKernelMsgService`
- `session.getGroupService()` → `IKernelGroupService`
- `session.getProfileService()` → `IKernelProfileService`
- `session.getBuddyService()` → `IKernelBuddyService`
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
- 历史：`getMsgs(Contact,long,int,boolean,IMsgOperateCallback)`；回调
  `onResult(int,String,ArrayList<MsgRecord>)`，OneBot 侧最多返回 100 条。

### 好友 / 资料
- `IKernelBuddyService.getBuddyListFromCache("", BuddyListReqType.KNOMAL)`（QQ 枚举拼写就是 KNOMAL）
  → `BuddyListCategory.buddyUids`。
- uid 每 200 个分块调 `IKernelProfileService.getCoreInfo("", uids)`；`CoreInfo` 字段
  `uid,uin,nick,remark`。主号真机好友列表 153 人。
- 陌生人：`getUidByUin` 后同样走同步 `getCoreInfo`。

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
`com.tencent.mobileqq.qsec.qsecurity.QSec`：本机 9.3.50 的签名是 `getSign(String, byte[])`/
`getSignEntry(String, byte[])`，核心 native（以及 `doSomething/getEstInfo`）**别 hook**（登录要用）；
可安全中和的：`detectMethod(String,String)→false`、`getXpsInfo()→空`。
native 库 `libfekit.so`。`Dandelion.energy`、`QsecEst.d` 也是 native 采集。
