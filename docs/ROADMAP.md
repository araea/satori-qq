# onebot-qq — 里程碑 3 设计（协议面已冻结，这里只作历史打法）

> **2026-08-29：ayjx 用到的 OneBot 11 动作/段已覆盖，默认不再推进新协议功能。**
> 未做项（request live 事件、`get_record.out_format`、历史游标）不挡 ayjx，不要再当下一轮任务。
> 当前主线是反检测，见 `FUTURE_PLAN.md` / `ANTIDETECT.md`。已实现的见 `HANDOFF.md` / `ONEBOT11_SUPPORT.md`。

> 已实现的见 HANDOFF.md。下面各节是当时「没做」的打法备忘，大部分已真机闭环。

> **2026-08-27 新增已实现（群管理，现成内核方法，fire-and-forget via IOperateCallback）：**
> `get_group_info`、`set_group_kick`(kickMember)、`set_group_ban`(setMemberShutUp)、
> `set_group_whole_ban`(setGroupShutUp)、`set_group_card`(modifyMemberCardName)、
> `set_group_admin`(modifyMemberRole + MemberRole 静态枚举 ADMIN/MEMBER)、`set_group_leave`(quitGroup)。
> 见 QQClient 群管理区 + OneBotHub。**注意：需 QQ 处于登录态才有 NT 会话**，登出时全部无效。

> **2026-08-27 韧性层第一阶段已实现（在线路径已由主号真机验证）：** `get_status`、真实在线
> heartbeat、WS lifecycle、离线动作快速失败，以及重登录替换 NT session 后的消息/群监听自动重绑。
> lifecycle/connect、即时与周期 heartbeat、登录/状态查询、强制退出后的 offline/disable、离线 1500、
> 一键登录后的 online/enable 与收消息恢复均已通过，WS 未断。此次复用同一 CppProxy，新对象替换分支未触发。
> 未做进程外自动拉起：模块随 QQ 进程死亡，必须由外部守护或用户重新启动 QQ。

## A. notice / request 事件

> **2026-08-28 真机**：灰字 XML → poke/禁言；`onMemberListChange` ADD/REMOVE → 进退群；`group_recall` / `friend_recall` 已通过。
> `send_poke` 走 0xED3_1。`invite_group` 拉回。request 动作 1400/1404 已真机；live 事件仍待申请人样本。

OneBot notice：群撤回/戳一戳/进退群/禁言。来源：
- **群撤回**：`IKernelMsgListener.onMsgRecall(int,String,long)`；更可靠的是收到的 `MsgRecord` 里
  带 `grayTipElement`（elementType=8, subType=REVOKE）。
- **进退群/禁言**：灰字 `groupElement` type 1/3/8；GroupListener 成员变化仍可作补充。
- **戳一戳**：`jsonGrayTipElement` busiId 1061。
- **好友/加群请求**：Buddy `onBuddyReqChange`；Group `onGroupNotifiesUpdated` / V2 / SingleScreen。

## B. 富媒体发送：语音/文件/视频 —— 全部已实现(2026-08-27 真机 retcode 0)
> **2026-08-29 已实现并在 9.3.55 再次真机闭环**：**语音 record** 优先调用官方
> `IMsgUtilApi.createPttElement`；非 SILK 输入经 MediaCodec 解码和 QQ `SilkCodecWrapper` 编码。
> 测试群发送、同 ID 历史 `record`、下载和原生播放器气泡均通过。文件/视频仍走对应 richmedia
> 路径；实测 VideoElement.fileSize=long 且**无 fileWidth/fileHeight 字段**。
和图片同套路（copy 到 `getRichMediaFilePathForMobileQQSend` 路径 → 建对应 Element → sendMsg 自动上传）：
- **语音 record**：`PttElement`。要把音频转 **silk/amr**（QQ 语音格式）。elementType=KELEMTYPEPTT(4)。
  字段：filePath/fileName/md5HexStr/fileSize/duration；RichMediaFilePathInfo 的 elemType 用 ptt 对应值。
  **已完成**：非 SILK 输入经 Android MediaExtractor/MediaCodec 解码、8k mono 重采样，再由 QQ
  `SilkCodecWrapper` 以正确的码率参数编码 Tencent SILK；无需外部 silk 服务。媒体暂存走 QQ cache，
  避免模块名路径在宿主进程内不可见。
- **视频 video**：`VideoElement`（elementType=5）。要生成缩略图 + 时长/分辨率。
- **文件 file / upload_group_file / upload_private_file**：群文件走 `IKernelRichMediaService` 或
  `IKernelMsgService` 的文件发送；或建 `FileElement`(elementType=3) sendMsg。群文件上传可能要
  `IKernelGroupService`/`RichMediaService` 的 upload 接口 + 进度回调。先解 `FileElement` 字段。

## C. get_forward_msg / 合并转发
> **2026-08-28**：Android QQNT 点开靠内核 `multiForwardMsg` 从本地真实消息做的卡片，`getMultiMsg(msgId)` 才能解析。假 SsoSendLongMsg ark/16 打不开。用户已确认点开。回查必须 `recordToEvent(rec, 0)` 否则 skip-self 丢掉自己的卡片。
> **2026-08-27 已实现「发」合并转发（真机 retcode 0）**：见 `packet/LongMsg.java` + `OneBotHub.sendForward`。
> 不走内核 `multiForwardMsg`（只能转已存在消息，办不了伪造节点），而是拼 im_msg_body 假节点
> → gzip → `PacketSvc.sendSso("trpc.group.long_msg_interface.MsgService.SsoSendLongMsg", req)` 拿 resId
> → 组 type-16 卡片发出。字段号抄自 LagrangeDev/LagrangeGo
> message proto。节点已支持 text/at/face/reply/image/file。**取回** `get_forward_msg`（`SsoRecvLongMsg` + gunzip payload）**已实现并真机往返验证**：见 `LongMsg.buildDownloadReq/parseDownload` + `OneBotHub.getForwardMsg`；send_forward 响应带 `res_id`。伪造节点非本人 uin 被服务器归一(反伪造)。

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

> **已完成补齐**：`get_friend_list`、`get_stranger_info`、`get_group_msg_history`、`set_group_name`、
> `get_version_info`、`can_send_image/record`、`clean_cache`、`set_restart`。详见 `ONEBOT11_SUPPORT.md`。

## 建议顺序
1. GroupService 现成方法的动作（kick/ban/card/admin/get_group_info）——最快见效。
2. notice 事件（A）——ayjx 插件要用。
3. 语音/文件发送（B）。
4. OIDB 子系统（D）——最后啃，啃下来 send_like/special_title/群管全解锁。
