# onebot-qq — 里程碑 3 设计 (待实现动作的具体打法)

> 已实现的见 HANDOFF.md。这里只讲**没做的**，以及每个该怎么下手（附 QQNT 线索）。

> **2026-08-27 新增已实现（群管理，现成内核方法，fire-and-forget via IOperateCallback）：**
> `get_group_info`、`set_group_kick`(kickMember)、`set_group_ban`(setMemberShutUp)、
> `set_group_whole_ban`(setGroupShutUp)、`set_group_card`(modifyMemberCardName)、
> `set_group_admin`(modifyMemberRole + MemberRole 静态枚举 ADMIN/MEMBER)、`set_group_leave`(quitGroup)。
> 见 QQClient 群管理区 + OneBotHub。**注意：需 QQ 处于登录态才有 NT 会话**，登出时全部无效。

## A. notice 事件（优先，ayjx recall 插件要用）
OneBot notice：群撤回/戳一戳/进退群/禁言/贴表情通知。来源：
- **群撤回**：`IKernelMsgListener.onMsgRecall(int,String,long)`；更可靠的是收到的 `MsgRecord` 里
  带 `grayTipElement`（elementType=8, subType=REVOKE）——解析它拿撤回者/被撤消息。
  → 发 `notice_type=group_recall`（operator_id/user_id/message_id）。
- **进退群/禁言/管理员变更**：注册 `IKernelGroupListener`（已注册做群列表缓存），处理
  `onMemberListChange(GroupMemberListChangeInfo)` / `onMemberInfoChange(long, DataSource, HashMap<uid,MemberInfo>)`
  / `onGroupDetailInfoChange`。禁言看 MemberInfo.shutUpTime 变化。
- **戳一戳**：C2C/群里是 `grayTipElement`（poke，JSON/PB 内容）或 `FaceElement.pokeType`。解析 grayTip。
- 落点：`OneBotHub` 已有 `onRecall(...)` 空实现 + `onGroupNotice` 可扩展；把 group 监听回调接进来建 notice JSON。

## B. 富媒体发送剩余：视频
> **2026-08-27 已实现（真机 retcode 0）**：**语音 record**（`Media.buildPttElement`，silk/amr passthrough，不转码）+ **文件 upload_group_file/upload_private_file/`file` 段**（`Media.buildFileElement`）。都走图片同款 richmedia auto-upload：`RichMediaFilePathInfo(elementType, subType, md5, fileName, downloadType=1, thumbSize=0, null, "", true)`，首参 elementType=PIC2/FILE3/PTT4/VIDEO5，copyFile 到返回路径后 sendMsg，QQ 自动 highway 上传。**剩视频**（下面），需缩略图+分辨率+时长（Android `MediaMetadataRetriever`）。
和图片同套路（copy 到 `getRichMediaFilePathForMobileQQSend` 路径 → 建对应 Element → sendMsg 自动上传）：
- **语音 record**：`PttElement`。要把音频转 **silk/amr**（QQ 语音格式）。elementType=KELEMTYPEPTT(4)。
  字段：filePath/fileName/md5HexStr/fileSize/duration；RichMediaFilePathInfo 的 elemType 用 ptt 对应值。
  转码可用现成 silk encoder（native）或让 ayjx 端传已编码的 silk。
- **视频 video**：`VideoElement`（elementType=5）。要生成缩略图 + 时长/分辨率。
- **文件 file / upload_group_file / upload_private_file**：群文件走 `IKernelRichMediaService` 或
  `IKernelMsgService` 的文件发送；或建 `FileElement`(elementType=3) sendMsg。群文件上传可能要
  `IKernelGroupService`/`RichMediaService` 的 upload 接口 + 进度回调。先解 `FileElement` 字段。

## C. get_forward_msg / 合并转发
> **2026-08-27 已实现「发」合并转发（真机 retcode 0）**：见 `packet/LongMsg.java` + `OneBotHub.sendForward`。
> 不走内核 `multiForwardMsgElement`（只能转已存在消息，办不了伪造节点），而是拼 im_msg_body 假节点
> → gzip → `PacketSvc.sendSso("trpc.group.long_msg_interface.MsgService.SsoSendLongMsg", req)` 拿 resId
> → 组 `com.tencent.multimsg` LightApp 卡片 → 复用现有 json/ark 发送。字段号抄自 LagrangeDev/LagrangeGo
> message proto。v1 仅文本节点。**取回** `get_forward_msg`（`SsoRecvLongMsg` + gunzip payload）**已实现并真机往返验证**：见 `LongMsg.buildDownloadReq/parseDownload` + `OneBotHub.getForwardMsg`；send_forward 响应带 `res_id`。伪造节点非本人 uin 被服务器归一(反伪造)。

- **发**合并转发：QQNT 用 `multiForwardMsgElement`（elementType=16）+ 先把子消息用
  `msgService` 造成 fake record 再打包；较绕。参考 OpenShamrock 的 `MsgSvc.uploadMultiMsg`。
- **收/取** `get_forward_msg`：从 `MultiForwardMsgElement` 里拿 resId，再
  `msgService.getMultiMsg(...)`/下载解析。字段名要反编译确认。

## D. OIDB/封包子系统（设计 + 进度）
> **2026-08-27 重要更新**：命令号**不必**啃 QQ.hap 的 abc——直接参考 **NapCatQQ**(维护中)/**Lagrange.Core** 源码，body protobuf 与安卓一致。已抄到 0x8FC_2(头衔)/0xED3_1(poke)/SsoSendLongMsg(转发) 等，见 `reference/PACKETS.md`。`Pb` 数据层及 `PacketSvc` 发送/回包层已经完成，并获得真机服务器回包。


**已建（2026-08-27）**：`packet/Pb.java` — 零依赖 protobuf 编解码器（varint/bytes/fixed/message/嵌套 +
`Pb.oidb(cmd,svcType,body)` 打 OIDBSSOPkg）。离线往返测试通过。**合并转发也归这里**（伪造节点要把多条
消息拼成 protobuf 上传拿 resId，QQNT 内核 `multiForwardMsg` 只能转已存在的消息，办不了伪造）。

**发包链路（QQ 9.3.50 实际反编译 + 已实现）**：
- `IKernelService` 的实现持有私有 `getIDependsAdapter()`。最终使用其 `onSendSSORequest`，显式传
  `OidbSvcTrpcTcp.0x{HEX}_{sub}` 与 `Pb.oidb(...)`；入口继续进入 `KernelSendObserver`/`KernelServlet`/MSF，
  QQ 自己完成 SSO framing 和 QSec 签名。
- **不要用 `onSendOidbRequest`**：本机实现把 int 命令直接以十进制拼到 `0x` 后，0x8FC 会变成
  `OidbSvcTrpcTcp.0x2300_2`，真机返回 236 `cmd not found`。
- 因而**不需要也不应该手工 QSign**；旧笔记写的 `getSign(cmd,body,seq)` 也不符合本机 9.3.50，实际
  `QSec` 是 `getSign(String,byte[])`。
- 回包到 `IQQNTWrapperSession$CppProxy.onSendSSOReply(requestId,ssoCmd,resultCode,errorMsg,MsfRspInfo)`；
  `PacketSvc` 只截获自身分配的 requestId 并用 latch 唤醒调用线程，其余 QQ 请求原样放行。
- 当前 `set_group_special_title` 已按 0x8FC_2 接入。旧群中账号为 member 时返回业务码 1013；改在
  内部测试群 `675983807`（账号为 owner）把本人空头衔原值写回，真机返回 retcode 0，成功分支已验证。
  传输层现可复用于 send_like 和 SsoSendLongMsg。

## D2. 需要 OIDB 原始封包子系统的具体动作（组包细节）

### 原细节：
`send_like`(点赞资料卡)、`set_group_special_title`(专属头衔) 在 QQNT **没有内核 service 方法**，
必须发 **OIDB protobuf 封包**：
- 打法：hook QQ 的 MSF/SSO 发送通道（`sendOidb`/`sendSSO`），或找到 `IKernelMSFService`/
  `ProfileService` 里能发 oidb 的入口，自己拼 protobuf（cmd + body）。
- `send_like`：oidb 0x7373/0x488 之类（版本相关，抓包或看 QQ.hap 协议）。
- `set_group_special_title`：oidb 0x8fc（改群名片/头衔）。
- **QQ.hap（`/data/media/0/dev/QQ.hap`）是这里的金矿**：鸿蒙版源码里能翻到 OIDB 命令字、
  protobuf 字段定义、业务流程。解包 `unzip QQ.hap`，看 ArkTS(`ets/`) 里的协议定义。
- 这是独立大工程：需要一个"发任意 oidb 包 + 收回包"的基础设施，之后 send_like/special_title/
  很多群管操作都能在上面搭。

## A2. 已实现的发送段（2026-08-27）
`json`/`lightapp`→ArkElement(卡片)、`mface`→MarketFaceElement、`poke`→FaceElement(pokeType)。
真机测 json 卡片发送+撤回 retcode 0。**注意：ayjx 实测不消费任何 notice 事件**（它的 recall 是
/撤回命令，不是撤回通知），所以 A 节的 notice 事件对 ayjx 无用，已降级为"按需/其它客户端才需要"。

## E. 其它 OneBot 动作（按需）
`get_stranger_info`/`get_friend_list`(BuddyService)、`set_group_kick`/`set_group_ban`/
`set_group_card`/`set_group_admin`(GroupService 已有方法，见 ARCHITECTURE.md 群小节)、
`get_group_info`(getGroupDetailInfo)。这些多数是现成 kernel service 方法 + 回调，照 get_group_member_info 的
"Proxy 回调 + latch 同步"套路即可，工作量小，可先顺手补。

## 建议顺序
1. GroupService 现成方法的动作（kick/ban/card/admin/get_group_info）——最快见效。
2. notice 事件（A）——ayjx 插件要用。
3. 语音/文件发送（B）。
4. OIDB 子系统（D）——最后啃，啃下来 send_like/special_title/群管全解锁。
