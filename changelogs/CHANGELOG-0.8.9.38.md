## 0.8.9.38

对齐 Satori v1 规范与 Koishi `adapter-satori` 用的协议方法表，并按 QQ 9.3.60.40970 的内核接口补上一批查询与设置能力。

### 协议

- 补上 `guild.member.role.list`
- 补上 `reaction.clear`：不带 `emoji_id` 时清除该消息上自己加过的全部表态。QQ 只能为当前登录号增删表态，因此清除范围限于自己加的那部分
- `channel.mute` 同时接受 `duration`（毫秒，规范文档的写法）与 `enable`（`@satorijs/protocol` 方法表的写法）。只传 `enable=true` 时表示停到手动解除，按 30 天上限计时；`enable=false` 或 `duration=0` 解除
- 列表类接口统一返回 `{data, next}`，涉及 `guild.list`、`guild.member.list`、`guild.role.list`、`guild.member.role.list`、`channel.list`、`friend.list`。不带 `next` 与 `limit` 时返回完整结果，与之前一致；带其中之一时按偏移分页
- 未实现的方法仍返回 404，响应体由纯文本改为 JSON，与其余错误一致
- `login.features` 与实际能力对齐，不再漏报已支持的方法

### QQ 能力扩展（新增 27 个 `internal/*` 动作）

- 群：`group_detail`、`group_all_info`、`group_bulletin`、`group_essence_list`、`group_statistic`、`group_member_level`、`group_avatar_wall`、`group_medal`、`member_identity`、`member_common`
- 好友：`buddy_category`（查询与增删改名、移动好友）、`buddy_nick`、`special_care`、`add_me_setting`、`doubt_buddy`、`buddy_req_unread`
- 资料：`user_detail`、`vas_info`、`profile_status`、`profile_intimate`、`profile_relation_flag`、`profile_set_birthday`
- 消息：`voice_to_text`（语音转文字）、`fav_emoji`、`auto_reply`、`unread_summary`、`mark_read`
- `group_msg_mask` 不带 `mask` 时读取当前设置
- `internal/capabilities` 新增 `params` 字段，逐条列出上述动作的参数

### 实现

- 内核读操作的返回结构由反射导出，QQ 增删字段时跟着变，不再随版本静默丢字段
- 内核回调按 `(int code, String msg, <payload>)` 的形状匹配，不再只认 `onResult`。`IFetchFavEmojiListCallback` 的回调名为 `onFetchFavEmojiListCallback`，按名字匹配会让这类调用每次等到超时
- 好友分组读的是 `KNOMAL`（用户自己的分组表），此前 `KLETTER` 给的是通讯录里的 A–Z 分组，写操作新建的分组不会出现在里面；写操作返回前会先拉取一次分组表
- 核过但没有提供动作的内核入口，以及原因，记在 `docs/SATORI_SUPPORT.md` 的「内核可用性」一节：群公告列表、无勋章群的勋章查询、收藏表情、修改性别、`getGroupExtList`、群文件搜索

### 验证

- JVM 单测 18 项通过，新增 `tests/SatoriListTest` 覆盖分页与禁言时长的两种入参
- 新增 `tests/ws-kernel-extras.js` 与 `tests/ws-kernel-writes.js`，在测试群 280183116 上逐项实测：读动作 28/28 通过，写动作 11/11 通过（好友分组建改删自清理，特别关心开关后还原，生日按读回的原值写回，会话标记已读）
- 与 0.8.9.37 对比跑既有巡检脚本，失败项完全相同（8/52），无回归

版本号 0.8.9.38（versionCode 73），沿用同一签名密钥，支持从 0.8.9.37 直接覆盖升级。
