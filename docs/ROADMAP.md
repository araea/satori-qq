# onebot-qq — 里程碑 3 设计 (待实现动作的具体打法)

> 已实现的见 HANDOFF.md。这里只讲**没做的**，以及每个该怎么下手（附 QQNT 线索）。

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

## D. 需要 OIDB 原始封包子系统的（最重，单独一块）
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
