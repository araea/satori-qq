# 纯 JNI 能力盘点

本实现端不引入 hook 引擎，全部能力经 JNI 层抵达。本文给出该层的功能边界：哪些已经在用，哪些能走通但没做，哪些走不通。
依据为本机 QQ 9.3.65（versionCode 16240）的 dex 与运行中的模块。

## JNI 面

能力经四条通道抵达。四条之外的都不做。

| 通道 | 做法 | 现状 |
| --- | --- | --- |
| 反射内核服务 | `IKernelService.getWrapperSession()` 取会话，再调各 `getXxxService()` 上的方法 | 主力，7 个服务在用 |
| 原始 OIDB / trpc 封包 | `IDependsAdapter.onSendSSORequest` 发出，SSO framing 与 QSec 签名仍由 QQ 完成 | 戳一戳、群头衔、群签到、合并转发上传下载在用 |
| 换原生方法 | `RegisterNatives` 替换 QQ 的 `native_*` 方法 | 只换 `native_onSendSSOReply` |
| 宿主 Java 与系统 API | 反射 `mqq.app.*`、`QRoute.api`，以及 `PowerManager` / `MediaCodec` 等 | 保活、语音转码、系统信息 |

不在这四条里的手段：ART hook 引擎（改 ArtMethod、内联钩子、trampoline）、任意 Java 方法的 hook、第三方 native 依赖。理由见 [README](../README.md) 的运行条件一节。

会话接口上有 54 个 `get*()` 入口，其中 51 个取服务（自 `IQQNTWrapperSession` 反编译，本机 9.3.65）。这 51 个就是反射通道的全部可达面，清单见文末。

## 证据级别

- 已核验：真机跑通，有测试脚本或线上观测。
- 静态：类、接口与方法签名取自本机 QQ 9.3.65 的 dex，未做真机调用。
- 参考：只有 NapCat 侧实现，Android 端未核。

下文未标「已核验」的条目都是静态或参考。静态只说明接口面在，不说明调用会回调。

## 已经用上

[`SATORI_SUPPORT.md`](SATORI_SUPPORT.md) 列了当前暴露的 31 个标准方法、19 个核心扩展动作，以及
0.29.0 起 `ExtraSvc` 注册表新增的约 50 个扩展动作，这里不重复。

## 可达能力清单（含已接入项）

按域列出。每项给功能、JNI 落点与证据；表内标记「已接」的项目不属于待新增清单。

### 消息

| 功能 | 落点 | 证据 |
| --- | --- | --- |
| 点击行内键盘按钮 | `IKernelMsgService.clickInlineKeyboardButton` | 静态。0.29.0 已接 `click_inline_keyboard`。入站 elementType 17 现在只投递按钮标签 |
| 输入状态（正在输入） | `IKernelMsgService.sendShowInputStatusReq` | 静态。0.29.0 已接 `typing` |
| 收藏表情增删改查 | `fetchFavEmojiList` `addFavEmoji` `deleteFavEmoji` `modifyFavEmojiDesc` | 静态，属 0.17.0 撤下的那批内核查询，未接回 |
| 消息摘要与关键词搜索 | `getMsgAbstract` `queryMsgsAndAbstractsWithFilter`；`IKernelSearchService.searchMsgWithKeywords` | 静态。0.29.0 已接 `message_abstract`（仅 `getMsgAbstract` 一条），`queryMsgsAndAbstractsWithFilter`/`searchMsgWithKeywords`（0.17.0 撤下的 `message_search`）仍未接 |
| 重新编辑撤回的消息 | `IKernelMsgService.reeditRecallMsg` | 静态。0.29.0 已接 `reedit_recall` |
| 带评论的转发 | `forwardMsgWithComment` `multiForwardMsgWithComment` | 静态。本端已用普通转发 |
| 在线文件（在线文件夹） | `getOnlineFileMsgs` `getAllOnlineFileMsgs` `refuseReceiveOnlineFileMsg` | 静态。0.29.0 已接 `online_file_list`（仅 `getOnlineFileMsgs`）/`online_file_refuse` |
| 群临时会话 | `prepareTempChat` `getTempChatInfo` | 静态。0.29.0 已接 `temp_chat_info`（仅 `getTempChatInfo`，`prepareTempChat` 未接） |
| 已读标记 | `setMsgRead` `setSpecificMsgReadAndReport` `setAllC2CAndGroupMsgRead` | 静态。0.29.0 已接 `mark_read`/`mark_read_seq`/`mark_all_read`（0.17.0 撤下的 `mark_read` 由此回归） |
| 图片 OCR | `IKernelSearchService.doOcrOnPicMsg` `searchOcrData` `getOcrDataByAIO` | 静态。0.29.0 已接 `image_ocr`/`ocr_data`/`ocr_by_aio`（参数语义未核，原样透传）。桌面端的 OCR 走 `NodeMiscService`，Android 走这条路 |

QQ 9.3.65 的 `MsgElement.walletElement` / `WalletElement` / `WalletAio` 在 dex 中可见：
可通过现有 JNI 入站消息回调**被动看到**钱包消息。只投递 `[红包]`（展示标题含「红包」）
或 `[QQ钱包消息]` 文本标记；绝不输出 `authkey`、`billNo`、跳转 URL 或钱包对象。
这是静态核验的展示能力，不是领取回执；发红包与领取红包涉及钱包业务流程、身份校验与
服务端支付确认；目前没有已核验的纯内核收发路径，不能把 `WalletElement`
当作发送参数绕过这些流程。未拿到真红包样本验证前，不做资金收发动作或自动领取。

0.17.0 撤下的 `voice_to_text` 未记落点，重开前需先核内核入口。

### 群

| 功能 | 落点 | 证据 |
| --- | --- | --- |
| 群公告收发 | `publishGroupBulletin` `deleteGroupBulletin` `getGroupBulletinList` `uploadGroupBulletinPic` | 静态。0.29.0 已接 `group_bulletin_publish`/`group_bulletin_delete`/`group_bulletin_get`/`group_bulletin_upload_pic`，写入口要 pskey |
| 群精华读取 | `fetchGroupEssenceList` `getGroupLatestEssenceList` `queryCachedEssenceMsg` | 静态。0.29.0 已接 `group_essence_list`/`group_essence_latest`/`group_essence_cached`。本端已用写侧的 `essence` |
| 群禁言名单 | `getGroupShutUpMemberList` | 静态。0.29.0 已接 `group_shut_up_list`（0.17.0 撤下后回归） |
| 群邀请链接 | `getJoinGroupLink` | 静态。0.29.0 已接 `group_join_link` |
| 群扩展信息 | `getGroupExtList` `getGroupExt0xEF0Info` `modifyGroupExtInfoV2` | 静态。0.29.0 已接只读的 `group_ext_list`（0.17.0 撤下过 `group_extra`），写侧 `modifyGroupExtInfoV2` 未接 |
| 退群 | `quitGroup` `quitGroupV2` | 静态。0.29.0 已接 `group_quit` |
| 群备注 | `modifyGroupRemark` | 静态。0.29.0 已接 `group_remark`（0.17.0 撤下后回归） |
| 转让群 | `transferGroup` `getTransferableGroupList` | 静态 |
| 加入群 | `joinGroup` `reqToJoinGroup` `getGroupInfoForJoinGroup` `getJoinGroupNoVerifyFlag` | 静态。0.29.0 已接 `group_join`/`group_join_info`/`group_join_noverify` |
| 群打卡 | `IKernelGroupSchoolService.checkInGroupSchoolTask` `getGroupSchoolTaskCheckInInfo` `publishGroupSchoolTask` | 静态。本端 `sign` 走 OIDB `0xEB7_1` |
| 群成员等级与身份 | `getGroupMemberLevelInfo` `getIdentityList` | 静态读侧。0.29.0 已接 `group_member_level`/`group_identity_list`。写侧本端已用 `setIdentityTitleInfo` / `setGroupIdentityLevelInfo`（`title_display` 内部调用，另有单次调用原语 `identity_title_info`/`identity_level_info`） |
| 群成员名片与扩展 | `getGroupMemberCardInfo` `getGroupProfileCardInfo` | 静态。0.29.0 已接 `group_member_card`/`group_profile_card`。本端已用 `getMemberExtInfo` |
| 群文件写 | `IKernelRichMediaService.createGroupFolder` `deleteGroupFile` `deleteGroupFolder` `renameGroupFile` `renameGroupFolder` `moveGroupFile` `transGroupFile` `searchGroupFile` `batchGetGroupFileCount` | 静态。0.29.0 已接 `group_file_count`/`group_file_folder_create`/`group_file_folder_delete`/`group_file_folder_rename`/`group_file_delete`/`group_file_rename`/`group_file_move`/`group_file_trans`（`searchGroupFile` 未接）。OIDB `0x6D6` / `0x6D7` / `0x6D8` 在 [`reference/PACKETS.md`](../reference/PACKETS.md) 有留档，0.17.0 撤下过 `group_file` |
| 群相册 | `IKernelAlbumService.getQunFeeds` `doQunLike` `doQunComment` `deleteQunFeed` `quoteToQzone` | 静态 |
| 群作业与组队 | `getGroupHomeworkDetailInfo` `getTeamUpDetail` `getGroupGameStatDetail` | 静态 |

### 资料与关系

| 功能 | 落点 | 证据 |
| --- | --- | --- |
| 长昵称 | `IKernelProfileService.setLongNick` | 静态。0.29.0 已接 `long_nick` |
| 昵称、头像、性别、生日 | `setNickName` `setHeader` `setGander` `setBirthday` | 静态 |
| 自身状态与签名 | `getSelfStatus` `getStatus` `getStatusInfo` `startStatusPolling` | 静态 |
| 用户详细资料 | `getUserDetailInfo` `getUserDetailInfoByUin` `getUserSimpleInfo` `getCoreAndBaseInfo` | 静态。0.29.0 已接 `user_detail`/`user_detail_by_uin`/`user_simple_info`（0.17.0 撤下的 `user_detail` 由此回归；`getCoreAndBaseInfo` 未接） |
| 好友备注 | `IKernelBuddyService.setBuddyRemark` `getBuddyRemark` | 静态。0.29.0 已接 `friend_remark_get`/`friend_remark_set`，是 0.17.0 撤下的 `friend_relation`（更大范围的好友关系批量查询）里独立出来的这一小块，不算整体回归 |
| 好友分组 | `addCategory` `delCategory` `renameCategory` `setBuddyCategory` `setBatchBuddyCategory` `resortCategory` | 静态。0.29.0 已接 `friend_category_add`/`friend_category_delete`/`friend_category_rename`/`friend_category_set`/`friend_category_set_batch`（`resortCategory` 未接） |
| 可疑好友申请 | `getDoubtBuddyReq` `approvalDoubtBuddyReq` `delDoubtBuddyReq` | 静态 |
| 主动加好友 | `reqToAddFriends` | 静态。0.29.0 已接 `friend_add` |
| 加好友黑名单 | `getAddFriendBlockedList` `clearAddFriendBlockedList` | 静态 |
| 特别关心 | `SpecialCareSetting` 结构体 | 静态 |

### 收藏与表情

| 功能 | 落点 | 证据 |
| --- | --- | --- |
| 收藏夹 | `IKernelCollectionService`：增删改查、自定义分组、搜索、转发、打包分享 | 静态 |
| 系统表情 | `IKernelBaseEmojiService.fetchFullSysEmojis` `downloadBaseEmojiById` `getRecentEmojiList` | 静态 |
| 商城表情 | `IKernelMsgService.fetchBottomEmojiTableList` `queryFavEmojiByDesc` `delMarketEmojiTab` | 静态 |

### 文件与媒体

| 功能 | 落点 | 证据 |
| --- | --- | --- |
| 闪传 | `IKernelFlashTransferService`：上传、下载、分享链接、聚类列表 | 静态 |
| 文件助手 | `IKernelFileAssistantService` | 静态 |
| 远程文件 | `IKernelRemoteFileService` | 静态 |
| 头像批量下载 | `IKernelAvatarService.forceDownloadAvatar` `forceDownloadGroupPortrait` `getAvatarPath` | 静态 |
| 媒体临时路径与群文件列表 | `IKernelRichMediaService.getPttTmpPath` `getGroupFileList` `getFileDownloadStatus` | 静态。视频播放地址已核验，走 `getVideoPlayUrlV2` |
| 中英翻译 | `IKernelRichMediaService.translateEnWordToZn` | 静态 |

### 在线状态与互动

| 功能 | 落点 | 证据 |
| --- | --- | --- |
| 自身在线状态 | `IKernelProfileService.setStatus`、`IKernelMsgService.setStatus` | 静态 |
| 自定义在线状态 | `CustomOnlineStatusManager`、`GetCustomOnlineStatusReq` | 静态 |
| 在线状态互动点赞 | `IKernelOnlineStatusService.setLikeStatus` `checkLikeStatus` `getLikeList` | 静态。是否等同资料卡点赞未核 |

### 系统与运维

| 功能 | 落点 | 证据 |
| --- | --- | --- |
| 精准清缓存 | `IKernelStorageCleanService.clearCacheDataByKeys` `getChatCacheInfo` `getFileCacheInfo`、`IKernelSettingService.clearCache` | 静态。本端 `clean_cache` 走自建临时文件清理 |
| 设置项读写 | `IKernelSettingService`：自动登录、隐私、文件自动下载、免确认开关 | 静态 |
| 迷你 App | `IKernelMiniAppService` | 静态。桌面端的 MiniApp 控制走 `NodeMiscService` |
| 公众号 | `IKernelPublicAccountService` | 静态 |
| 频道消息 | `IKernelGuildMsgService`：收发与已读 | 静态。会话上没有 `getGuildService`，频道管理不可达 |
| 机器人 | `IKernelRobotService`，含 `getRobotUinRange` `fetchAllRobots` | 静态 |
| 二维码 | `IKernelQRService` | 接口在，方法体反编译为空，未核 |

### 凭据

这些是 WebAPI 通道的前置件，本身不产生聊天动作。

| 功能 | 落点 | 证据 |
| --- | --- | --- |
| clientkey | `IKernelTicketService.forceFetchClientKey`、`IClientKeyCallback` | 入口在，方法体反编译为空，未核 |
| pskey | `IKernelTipOffService.getPskey` | 同上，未核 |
| cookies | 桌面端 `UserApi.getCookies` | Android 无对应入口，未核 |

## 主动撤下（可达但不做）

0.17.0 撤下一批 QQ 内核查询与本地会话状态接口，0.23.0 撤下 `like`。
撤下的理由是产品口径：按号向 QQ 要资料的批量查询最不像真人客户端，暴露这份面会抬高风控风险。
这不是能力缺失。上面各域的静态条目里，多数正是这批动作的落点，接口在 dex 里，重开只需接回调用。
`capabilities.removed` 会逐条报出版本与原因，客户端据此记入不可用名单。

0.17.0 名单：`group_overview` `group_extra` `member_info` `group_member_search` `recent_contacts` `contact_search` `friend_relation` `group_remark` `profile_self` `group_honor` `group_shut_up_list` `group_active` `group_anniversary` `group_detail` `group_statistic` `user_detail` `voice_to_text` `message_context` `message_search` `group_file` `get_resource` `mark_read` `session_top` `group_msg_mask` `qzone.publish` `offline`。

## 受限

本地调用正确，结果由 QQ 服务端权限或风控决定。

- 审批类：`friend.approve` `guild.approve` `guild.member.approve`。
- 撤回：只能撤自己发的。
- 改名、全员禁言、踢人、设头衔：需要群内对应身份。
- 频控：本端 `OutboundGuard` 限频，QQ 服务端另有各自的上限。
- 点赞次数、加好友次数：服务端配额。

## 不可行

| 功能 | 原因 |
| --- | --- |
| 资料卡点赞（`like`） | Android 无对应内核服务（`ProfileLike` 只有客户端类 `ZPlanProfileLikeManager`）。OIDB `0x7E5_104` 绑桌面 appid。老式 WUP `VisitorSvc.ReqFavorite` 的回执要 hook `MobileQQServiceBase.dispatchToHandler` 才能读到，发送成功也拿不到结果 |
| QQ 空间动态发布与删除 | 无 `IKernelFeedService`（该接口在本机 dex 里不存在）。走 WUP 或 WebAPI |
| 桌面专有控制 | `NodeMiscService` 的 MiniApp 控制与 Windows OCR。Android 的 OCR 另有 `IKernelSearchService` 路径 |
| rkey | 本机 dex 里没有 rkey 相关类。NapCat 走 `pkt.operation.FetchRkey`，命令号未核 |
| 协议无对应概念 | `channel.create` `channel.delete` `guild.role.*` `message.update`、清所有用户表态的 `reaction.clear` |

## 换原生方法这一面的余量

现在只换 `native_onSendSSOReply` 一个。同一手法适用于 QQ 的任意 `native_*` 方法：在 ArtMethod 里认出原 JNI 函数指针（只读），`RegisterNatives` 换成自己的，非本模块的调用再转交原指针。

可用于接管别的原生回包入口。边界：QQ 用 `RegisterNatives` 注册的方法才有可换的入口；纯 Java 方法与 native 内部的直调不在其列。

## 复核办法

- `POST /v1/internal/compat`：静态核对 128 个类型、7 个服务入口、24 个结构体字段、45 个回调形状。它是「接口面有没有变」的检查，不是「动作能不能用」的证明——类在、入口在，只说明接口面没变。
- `observed`：`ExtraSvc.call` 每次调用的结果，按 label 记录成功、超时与其它失败。哪个入口开始不回调看这里。
- 新增动作前核三件事：类名在 dex 类索引里、结构体字段名反编译核对、入口会回调。

`compat` 有一个盲区：`ExtraSvc` 里保留了一批只有字符串常量、没有调用路径的类型名，它们让自检通过而与实际动作无关。判「动作可用」要看 `observed`，不要只看 `static.ok`。

## 与 NapCat 的对照

NapCat 在桌面 QQNT 上实现约 160 个动作，通道与本文四条同源（`session.getXxxService()`、`PacketApi.pkt.operation`、`WebApi`）。
差异不在通道，在两端内核接口面的宽窄：

- Android 独有：`IKernelGroupSchoolService`（群打卡）、`IKernelStorageCleanService`（清缓存）、`IKernelAvatarService`、`IKernelOnlineStatusService`、`IKernelQRService`。
- 桌面独有：`NodeMiscService`（MiniApp 控制、Windows OCR）。
- 两端都没有：资料卡点赞的专用服务。
- 频道：Android 会话上只有 `getGuildMsgService`，没有 `getGuildService`。
- NapCat 的 `WebApi` 层（群打卡列表、空间、部分群管理）是带 cookies 的 HTTP 调用，不是内核通道。它需要先取 clientkey / pskey，取不到就不可用。

## 搭话场景的取舍

给号主自己的机器人（同类用法见 acumen 的 `oai` 与 `ambient`）做「更像真人」的补强，上面这些能力值得用的很少。

功能面已经够用。acumen 的 ambient 已有真实调用路径：发言前按配置可选调用 `typing`，并可通过 `mark_read` 标记当前群已读；互动仍走 `poke`、`reaction_summary`、标准回应，以及现有 `card`、`dice`、`rps`、`essence`、`sign` 等动作。群内素材复用已有消息元素与上传路径，不需要 QQ 收藏表情库接口。

| ambient 相关能力 | 落点 | 现状与取舍 |
| --- | --- | --- |
| 输入状态 | `IKernelMsgService.sendShowInputStatusReq` → `typing` | 已注册并由 acumen 可选调用；默认关闭，发言前 best-effort 调用，不改变消息发送结果。QQ 客户端显示与过期行为仍须目标版本真机确认 |
| 标记当前群已读 | `IKernelMsgService.setMsgRead` → `mark_read` | 已注册，可由 ambient 的 `mark_read` 动作显式使用；不自动「全部已读」 |
| 发送表情包 | 消息元素、媒体上传 | 已有能力；ambient 素材走自身表情包存储与常规发送，不需要 `fetchFavEmojiList` / `addFavEmoji` |
| 作息在线状态 | `IKernelProfileService.setStatus` / `CustomOnlineStatusManager` | 接口可达，但 ambient 没有明确的状态变更需求；不自动模拟作息或修改账号在线状态 |

因此本端对 ambient 有用的 JNI 面已由 `typing` 与 `mark_read` 覆盖；`mark_read_seq` 和 `mark_all_read` 保留为显式扩展，不由 ambient 自动触发。消息搜索、群成员批查、OCR 等接口不构成搭话所需能力，不建议为此增加调用面。

其余不推荐，分三类。

- 工具型：群公告、群文件、群相册、群作业、收藏夹、闪传、文件助手、精准清缓存、设置项读写。这些是人在操作客户端时才会用的入口，与搭话无关。
- 批量查询型：群列表、成员列表、成员搜索、用户详细资料、群统计、群荣誉、消息搜索。真人不会按号把群和成员拉一遍。0.17.0 撤下的正是这一批。
- 管理型：转让群、退群、禁言名单、群链接、审批。多数还要群内身份，动作本身也显眼。

### 全部做成内部接口可行吗

技术上可行：接口在 dex 里，接回调用即可。不推荐，理由三条。

**与目标相反。** 让人设像真人的能力，恰好是模仿正常客户端行为的那几项，即输入状态、在线状态、已读。批量查询型不是「更完整」，是「更像脚本」。全部实现会把这两类放进同一个能力面。

**风控面。** 0.17.0 撤下这批不是能力问题，是这个号在 QQ 服务端眼里像什么的问题。号主与机器人共用同一个 QQ 号，代价由号主承担。

**验证面。** 每个动作在 QQ 升级后都要重新核（类名、字段名、回调）。`compat` 的静态检查覆盖不到「入口还会不会回调」，只有 `observed` 能，而没人调的动作不会进 `observed`。没人调的动作只剩成本。

协议实现暴露多少，按「客户端真的会调」定，不按「内核里有什么」定。客户端判能力看 `capabilities`。

### 2026-09-23 接口增量复核

针对「还能否增加有趣且有意义的 QQ 内部功能」重新按实际用途筛选：搭话前的输入状态和已读、戳一戳、表态、特殊消息、群打卡及素材发送均已有 JNI 路径；群公告、群文件和群管理等也已有一批扩展动作。剩余的收藏、相册、闪传、设置读写等主要服务手动操作，并没有明确的客户端调用方；消息搜索及批量资料查询又与先前撤下时的风控取舍冲突。**本轮不新增接口**，也不为了填满 dex 方法表而发布未实测的动作。

核验口径：本机 QQ 9.3.65 上 `/healthz.compat` 静态检查为 204/204，`tests/ws-health.js` 的在线探针通过；这只证明当前接口面和链路可用，**不证明**上述未调用的候选入口会回调。将来有确定的使用者时，先核对 QQ 版本、字段、回调和权限，再用真机回执验证后决定是否接入。

## 会话服务入口

`IQQNTWrapperSession` 上的 54 个 `get*()` 入口，自本机 QQ 9.3.65 反编译。

```text
getAIService getAVSDKService getAddBuddyService getAgentSearchService getAlbumService
getApiSixService getAvatarService getBaseEmojiService getBatchTransferService
getBatchUploadService getBdhUploadService getBizKitService getBuddyService getCacheErrLog
getCertifyService getCollectionService getConfigMgrService getDataReportService getEmojiService
getFileAssistantService getFileAutoDownloadService getFileBridgeClientService
getFlashTransferService getGroupSchoolService getGroupService getGroupTabService
getGuildMsgService getHandOffService getLiteBusinessService getMiniAppService getMsgService
getNearbyProService getOnlineStatusService getPersonalAlbumService getProfileService
getPublicAccountService getQRService getRecentContactService getRemoteFileService
getRemotingMe2MeService getRichMediaService getRobotService getSearchService getSessionId
getSettingService getShortLinkBlacklist getStorageCleanService getTicketService
getTipOffService getTrafficMonitorService getUixConvertService getUnifySearchService
getUnitedConfigService getWiFiPhotoHostService
```

其中 `getCacheErrLog` `getSessionId` `getShortLinkBlacklist` 不是服务。本端在用 `getMsgService` `getGroupService` `getProfileService` `getBuddyService` `getRichMediaService` `getRecentContactService` `getRobotService`。
