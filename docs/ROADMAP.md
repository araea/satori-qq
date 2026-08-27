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

## B. 富媒体发送剩余：语音/视频/文件
和图片同套路（copy 到 `getRichMediaFilePathForMobileQQSend` 路径 → 建对应 Element → sendMsg 自动上传）：
- **语音 record**：`PttElement`。要把音频转 **silk/amr**（QQ 语音格式）。elementType=KELEMTYPEPTT(4)。
  字段：filePath/fileName/md5HexStr/fileSize/duration；RichMediaFilePathInfo 的 elemType 用 ptt 对应值。
  转码可用现成 silk encoder（native）或让 ayjx 端传已编码的 silk。
- **视频 video**：`VideoElement`（elementType=5）。要生成缩略图 + 时长/分辨率。
- **文件 file / upload_group_file / upload_private_file**：群文件走 `IKernelRichMediaService` 或
  `IKernelMsgService` 的文件发送；或建 `FileElement`(elementType=3) sendMsg。群文件上传可能要
  `IKernelGroupService`/`RichMediaService` 的 upload 接口 + 进度回调。先解 `FileElement` 字段。

## C. get_forward_msg / 合并转发
- **发**合并转发：QQNT 用 `multiForwardMsgElement`（elementType=16）+ 先把子消息用
  `msgService` 造成 fake record 再打包；较绕。参考 OpenShamrock 的 `MsgSvc.uploadMultiMsg`。
- **收/取** `get_forward_msg`：从 `MultiForwardMsgElement` 里拿 resId，再
  `msgService.getMultiMsg(...)`/下载解析。字段名要反编译确认。

## D. OIDB/封包子系统（设计 + 进度）
> **2026-08-27d 重要更新**：命令号**不必**啃 QQ.hap 的 abc——直接参考 **NapCatQQ**(维护中)/**Lagrange.Core** 源码，body protobuf 与安卓一致。已抄到 0x8FC_2(头衔)/0xED3_1(poke)/SsoSendLongMsg(转发) 等，见 `reference/PACKETS.md`。`Pb.oidb` 已改成正确的 trpc 格式(1/2/4/12)并离线验证。剩发送链路(PacketSvc+QSign)是硬骨头。


**已建（2026-08-27）**：`packet/Pb.java` — 零依赖 protobuf 编解码器（varint/bytes/fixed/message/嵌套 +
`Pb.oidb(cmd,svcType,body)` 打 OIDBSSOPkg）。离线往返测试通过。**合并转发也归这里**（伪造节点要把多条
消息拼成 protobuf 上传拿 resId，QQNT 内核 `multiForwardMsg` 只能转已存在的消息，办不了伪造）。

**发包链路（QQNT，实测线索）**：
- 主进程经 MSF SDK 发：`com.tencent.mobileqq.msf.sdk.o` 里 `this.q.sendToServiceMsg(ToServiceMsg)`
  （`q` 是 IBaseService binder 代理，走到 :MSF 进程的 `MsfService.sendToServiceMsg`）。`o` 是混淆名，要动态定位。
- `ToServiceMsg(String appId, String uin, String serviceCmd)` 构造 + 设 wupBuffer(SSO body)。
- **回包**：注册 `mqq.app.MSFServlet` 或 hook FromServiceMsg 分发；按 seq 关联。Shamrock 的做法是**注入
  :MSF 进程** + 跨进程广播 IPC（`PacketReceiver`/`PacketHandler`，hook `FromServiceMsg` 的
  `internalOnReceive`）——更稳但更重。**先试主进程 MSFServlet 路线**，不行再学 Shamrock 注入 :MSF。
- **签名（关键坑）**：现代 trpc/`OidbSvcTrpcTcp.0xXXXX_Y` 包**必须带 libfekit 签名**，否则服务器拒收。
  调 `QSec.getInstance().getSign(cmd, body, seq)` 拿 sign（Shamrock 的 QSign.kt 就干这个）。注意 AntiDetect
  hook 的是 detectMethod/getXpsInfo，**没碰 getSign，安全可用**。

**待办组件**：
1. `packet/PacketSvc.java`：定位 MSF SDK 发送对象 + 构造 ToServiceMsg + 发送 + 按 seq 收回包（latch 同步）。
2. `packet/QSign.java`：反射调 QSec.getSign 给包签名。
3. 命令号：从 QQ.hap 的 modules.abc（需 abc 反汇编器）或社区/抓包拿。已知形态 `OidbSvcTrpcTcp.0x{cmd}_{svc}`。
4. 各动作组包：send_like（oidb 点赞）、set_group_special_title（oidb 0x8fc 改头衔）、
   合并转发（SsoSendLongMsg 上传 PbMultiMsgTransmit → resId → 发引用它的 ark）。

**注意进程模型**：当前模块只跑主进程（Main 里 `if(!mainProcess) return`）。若走 Shamrock 的 :MSF 路线，
要放开 :MSF 进程并做 IPC。走主进程 MSFServlet 路线则不用。

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
