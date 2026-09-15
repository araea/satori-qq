# Satori v1 接口

列出 QQ 9.3.55 与 9.3.60.40970（NT）已核验的接口、事件与消息元素。

## 连接约定

- HTTP：`POST /v1/{resource}.{method}`，请求体为 JSON，`upload.create` 使用 multipart
  - 请求头 `Satori-Platform` 与 `Satori-User-ID` 省略、留空或为 `0` 时都按未指定处理，照常应答；只有明确指向另一账号（平台不是 `red`，账号不是本机 QQ 号）的选择器才回 404
- 事件：`GET /v1/events` 升级为 WebSocket。客户端须在 10 秒内发送 `IDENTIFY`，服务端随后发送 `READY` 与 `EVENT`。省略 `sn` 创建新会话，显式指定 `sn=0` 可回放缓冲区内 `sn > 0` 的事件
  - `READY` 里的登录账号就是客户端之后每个请求要带回来的 `Satori-User-ID`。QQ 账号尚未可知时不发 `READY`，等账号可知（每秒轮询）后补发，不把占位账号发给客户端
- 元信息：`POST /v1/meta`；资源代理：`GET /v1/proxy/{url}`
- 分页：`guild.list`、`guild.member.list`、`guild.role.list`、`guild.member.role.list`、`channel.list`、`friend.list` 返回 `{data, next}`。不带 `next` 与 `limit` 时返回完整结果；带 `limit` 或 `next` 时按偏移分页，`next` 为下一次要传回的偏移量。`message.list` 是双向分页，另见下表
- 平台为 `red`，适配器为 `satori-qq`
- 群频道的 `channel.id` 与 `guild.id` 均为群号，`channel.type=0`
- 私聊频道为 `private:{uin}`，`channel.type=1`
- 消息 ID 使用 QQ NT `msgId` 字符串，历史游标使用 `message_seq`
- `<quote>` 与 `[CQ:reply]` 的 `id` 可直接用于 `message.get` 与 `message.delete`

QQ 没有等价能力的方法返回 404。下表中「受限」表示本地调用正确，结果可能受 QQ 服务端权限或风控限制。

## 标准方法

| 方法 | 状态 | 说明 |
| --- | --- | --- |
| `login.get` | 支持 | 登录账号、在线状态与功能列表 |
| `message.create` | 支持 | 发送消息；标准 `<message>` 可拆分多条，`<message forward>` 使用合并转发 |
| `message.get` | 支持 | 按 QQ `msgId` 查询，兼容当前进程内的旧式存储 ID |
| `message.list` | 支持 | `before`、`after`、`around` 双向分页，游标为 `message_seq` |
| `message.delete` | 支持 | 按 QQ `msgId` 撤回 |
| `channel.get` / `channel.list` | 支持 | 每个群映射为一个文字频道 |
| `channel.update` | 支持 | 群主或管理员修改群名与群头像 |
| `channel.mute` | 支持 | 全员禁言。`duration` 为毫秒，`0` 解除；也可只传 `enable`，`false` 解除、`true` 表示停到手动解除（按 30 天上限计时） |
| `user.channel.create` | 支持 | 创建 `private:{uin}` 私聊频道 |
| `guild.get` / `guild.list` | 支持 | 查询群 |
| `guild.member.get` / `guild.member.list` | 支持 | 查询成员及 owner、admin、member 角色 |
| `guild.member.kick` | 支持 | `permanent` 对应拒绝再次加群 |
| `guild.member.mute` | 支持 | `duration` 单位为毫秒 |
| `guild.member.role.set` / `unset` | 支持 | 仅支持 `role_id=admin` |
| `guild.member.role.list` | 支持 | 返回该成员的角色，取值同 `guild.role.list` |
| `guild.role.list` | 支持 | 返回 owner、admin、member |
| `user.get` | 支持 | 查询用户资料 |
| `friend.list` / `friend.delete` | 支持 | 查询或删除好友，删除时不额外拉黑 |
| `friend.approve` | 受限 | 处理好友申请，`message_id` 为申请 flag |
| `guild.approve` / `guild.member.approve` | 受限 | 处理群邀请或加群申请 |
| `reaction.create` / `delete` / `list` | 支持 | `emoji_id` 为表情 ID，只能操作自己的表态 |
| `reaction.clear` | 支持 | 不带 `emoji_id` 时清除该消息上自己加过的全部表态 |
| `upload.create` | 支持 | 上传多个文件，返回 `internal:red/{uin}/_tmp/{id}` |
| `message.update` / `channel.create` / `channel.delete` | 不支持 | 返回 404 |
| `reaction.clear` 之外的其余表态管理 / `guild.role.create` / `update` / `delete` | 不支持 | 返回 404 |

QQ 只能为当前登录号添加或撤销表态，因此 `reaction.delete` 传其他 `user_id` 时返回 400，`reaction.clear` 也只清除自己的那部分。

`internal/get_forward` 的 `id` 有两种：转发卡片里的 resId，或 `native:<父消息 ID>`。resId 走伪造节点协议，NT 客户端发的图片会整段丢失；`native:` 走 QQ 内核，图片、逐条消息 ID 与时间都在，优先使用。`native:` 需要知道会话，父消息不在模块缓存里时（模块重启或消息较旧）附带 `channel_id` 即可读取。

`message.create` 的 `forward_mode` 可设为 `auto`、`native` 或 `fake`，`auto` 优先使用 QQ 原生合并转发。`channel.update.data.avatar` 接受本地路径、`file:`、`http(s):`、`data:` 与 `internal:`。全员禁言的自动解除计时不跨 QQ 进程重启保留。

### 有时效的消息

`message.create` 的 `satori_qq` 扩展用于复读等允许丢弃的即时回复：

```json
{
  "channel_id": "123",
  "content": "哈哈",
  "satori_qq": {
    "if_latest_message_id": "触发本次回复的 message.id",
    "expires_at": 1788870003000
  }
}
```

两个字段必须一起提供。`expires_at` 为 Unix 毫秒截止时间，`if_latest_message_id` 必须仍是本进程最近向应用推送的该频道消息 ID。取得出站队列发送权后，以及媒体转换、重试等待后交给 QQ 内核前，各检查一次条件。任意新消息都会让旧条件失效，过期、频道状态未知或已被淘汰时跳过发送并返回 `[]`，不计作 QQ 发送失败，不触发熔断。多条拆分发送只返回已发送的部分。

调用方与实现端的系统时钟应保持一致。频道状态最多保留 4096 项，历史回放不更新频道状态。

## QQ 扩展方法

`POST /v1/internal/{name}`。写操作按顺序执行，受限频与熔断保护。

| 类别 | 方法 | 说明 |
| --- | --- | --- |
| 互动 | `poke` / `like` / `invite` | 戳一戳、资料卡点赞、邀请入群 |
| 群成员 | `special_title` / `card` | 设置群头衔或群名片 |
| 群显示 | `title_display` / `honor_display` | 设置成员群头衔或群荣誉显示开关 |
| 群设置 | `group_remark` / `group_extra` | 设置本地群备注，或读取群扩展标志 |
| 群管理 | `group_refresh` / `group_leave` | 刷新群列表；退群须指定 `confirm=true` |
| 群消息 | `sign` / `essence` | 群打卡；设置或取消精华消息 |
| 群概览 | `group_overview` | 返回频道、成员、角色、活跃统计与展示开关 |
| 成员查询 | `group_member_search` / `member_info` | 搜索群成员或读取单个成员详情 |
| 活跃度 | `group_active` / `group_anniversary` | 查询活跃排行或入群周年日历 |
| 随机选择 | `random_member` / `random_team` | 无重复抽取成员或均衡随机分队 |
| 联系人 | `contact_search` | 按号码、昵称、备注或群名搜索好友与群 |
| 群文件 | `group_file` | 查询、上传、移动、重命名或删除群文件与目录 |
| 消息读取 | `get_forward` / `get_resource` | 读取合并转发或已登记资源 |
| 消息查询 | `message_context` / `message_search` | 查询消息上下文或近期本地历史 |
| 特殊消息 | `dice` / `rps` | 发送 QQ 原生骰子或猜拳 |
| 个人资料 | `profile_set_signature` / `profile_set_nickname` | 修改个性签名或昵称 |
| 个人资料 | `profile_set_avatar` | 修改头像，接受本地路径、`file:`、`http(s):`、`data:` 与 `internal:` |
| 个人资料 | `profile_self` | 读取自己的资料、个性签名与在线状态 |
| 群设置 | `group_msg_mask` | 设置群消息提醒方式：`notify`、`assistant`、`shield`、`receive` |
| 群查询 | `group_shut_up_list` | 查询群内被禁言的成员 |
| 群查询 | `group_honor` | 查询群荣誉 |
| 群详情 | `group_detail` / `group_all_info` | 群容量、等级、群主与扩展标志 |
| 群详情 | `group_bulletin` | 读取群公告；`op=list` 读公告列表（见限制） |
| 群详情 | `group_essence_list` | 按页读取精华消息 |
| 群详情 | `group_statistic` / `group_member_level` | 群统计与成员等级刻度 |
| 群详情 | `group_avatar_wall` / `group_medal` | 群头像墙与群勋章（见限制） |
| 群成员 | `member_identity` | 成员群身份：等级、头衔、互动与业务标签 |
| 群成员 | `member_common` | 成员缓存之外的扩展字段 |
| 好友 | `friend_remark` | 查询或设置好友备注 |
| 好友 | `friend_top` | 置顶或取消置顶与好友的会话 |
| 好友 | `friend_msg_notify` | 开启或关闭单个好友的消息提醒 |
| 好友 | `friend_block` | 拉黑或取消拉黑好友 |
| 好友 | `friend_relation` | 查询是否为好友、是否已拉黑及备注 |
| 好友 | `friend_add` | 发送好友申请 |
| 好友 | `buddy_category` | 查询好友分组；`op=add/delete/rename/set` 增删改名或移动好友 |
| 好友 | `buddy_nick` | 读取指定好友的昵称 |
| 好友 | `special_care` | 设置特别关心及其铃声、空间开关 |
| 好友 | `add_me_setting` | 读取或修改加好友设置 |
| 好友 | `doubt_buddy` | 查询陌生好友申请，或通过、拒绝其中一条 |
| 好友 | `buddy_req_unread` | 未读好友申请数 |
| 资料 | `user_detail` | 资料详情：等级、会员、生日、地区、标签 |
| 资料 | `vas_info` | 会员、铭牌、字体等增值信息 |
| 资料 | `profile_status` | 在线状态：设备、网络、电量、自定义状态 |
| 资料 | `profile_intimate` | 亲密关系 |
| 资料 | `profile_relation_flag` | 拉黑、置顶、免打扰、特别关心等关系标志 |
| 资料 | `profile_set_birthday` | 修改生日 |
| 消息 | `voice_to_text` | 语音转文字 |
| 消息 | `fav_emoji` | 表情栏（内核只开放最近使用表情） |
| 消息 | `auto_reply` | 读取自动回复文本 |
| 消息 | `unread_summary` | 查询指定频道的未读数 |
| 消息 | `mark_read` | 将会话标记为已读 |
| 联系人 | `recent_contacts` | 查询最近联系人及未读数 |
| 能力查询 | `capabilities` / `help` | 返回扩展动作与参数清单 |
| 状态查询 | `status` / `version` | 返回健康状态或版本 |
| QQ 空间 | `qzone.publish` / `qzone.create` | 发布说说 |
| QQ 空间 | `qzone.delete` / `qzone.list` | 删除或列出说说 |
| QQ 空间 | `qzone.clear` / `qzone.delete_all` / `qzone.delete-all` | 删除全部说说 |
| QQ 空间 | `qzone.auth` | 读取调试用鉴权信息 |
| 维护 | `restart` / `clean_cache` | 退出 QQ 进程或清理临时文件 |

完整参数用 `capabilities` 或 `help` 查询，返回里的 `params` 字段逐条列出参数。`group_member_search.next` 可直接用于下一次扩展调用，`next_offset` 供 HTTP 客户端分页。

扩展动作返回的内核原始数据由反射导出，因此 QQ 增删字段时会跟着变而不是静默丢字段。读操作的返回形如 `{guild_id?, ok, result, <数据>}`，其中 `result` 是内核回调的原文；写操作的返回带 `result` 与写入后的值。

## 内核可用性

以下项在 9.3.60.40970 上核验过，属于 QQ 自身限制而非模块缺陷：

| 项 | 现状 |
| --- | --- |
| `group_bulletin` 的 `op=list` | 服务端回 `code=1 server get bulletin list err`，单条公告可读 |
| `group_medal` | 服务端回 `code=2 system error!` 并给出空勋章列表；无勋章的群即如此 |
| `fav_emoji` | `fetchFavEmojiList` 与 `queryFavEmojiByDesc` 接受调用但不回调，只有 `getRecentUseEmojiList` 有返回，因此该动作给的是最近使用表情 |
| 修改性别 | `setGander` 回 `code=-1 暂未实现`，未提供该动作 |
| `getGroupExtList` | 两个刷新标志都不回调，未提供对应动作 |
| `searchGroupFile` / `searchGroupFileByWord` | 前者同步返回 -1，后者不回调，未提供群文件搜索动作 |

个人资料、群设置与好友类动作只接受一个目标（`user_id` 或 `guild_id`）与少量开关，默认值取「不改变现状」的一侧：`friend_top` 缺省置顶，`friend_msg_notify` 缺省开启提醒，`friend_block` 缺省拉黑，`special_care` 缺省开启。`friend_remark` 带 `remark` 时写入、`op=get` 时读取，空字符串表示清除备注。`group_msg_mask` 不带 `mask` 时读取当前设置，除 `mask` 外也接受 `shield` 布尔简写。`buddy_category` 的写操作会先拉取一次分组成员表再返回，否则调用方会看到写入成功而列表里没有新分组。这些动作都能回读：`friend_relation` 带回备注，`profile_self` 带回个性签名与当前在线状态，`profile_relation_flag` 带回拉黑与特别关心标志。这些动作受 QQ 自身权限与账号状态限制。

## 事件

| 事件 | 来源 |
| --- | --- |
| `message-created` / `message-deleted` | 收到、发出或撤回消息 |
| `guild-added` / `guild-updated` / `guild-removed` | 加群、群资料变化或退群 |
| `channel-added` / `channel-updated` / `channel-removed` | 对应群的单频道变化 |
| `guild-member-added` / `guild-member-updated` / `guild-member-removed` | 成员加入、禁言或离开 |
| `reaction-added` / `reaction-removed` | 消息表态计数变化；QQ 回调不提供操作者 |
| `friend-request` / `guild-member-request` / `guild-request` | 好友申请、加群申请或群邀请 |
| `internal`，`_type=satori-qq/poke` | 戳一戳 |
| `login-updated` | QQ 内核上线或离线 |

QQ 客户端手动发出的消息以 `qq-client:{selfUin}` 作为虚拟作者，并在 `satori_qq.manual_self` 中携带实际身份；机器人 API 的发送回声会去重。登录期间的群列表同步不生成群变更事件。

## 消息元素与媒体

| 方向 | 元素 |
| --- | --- |
| 发送 | `text` `at` `sharp` `quote` `emoji` `a` `br` `p` `img` `audio` `video` `file`、修饰元素与 `<message>`；兼容 `face` `json` `mface` `poke` |
| 接收 | `text` `at` `quote` `emoji` `img` `audio` `video` `file`；合并转发为 `<message forward id="resid"/>` |

媒体 `src` 接受 `http(s):`、`data:`、`file:`、本地路径、`upload.create` 返回的 `internal:`，以及本端的 `/v1/assets/{id}`。入站图片优先返回无需 Bearer 令牌的本地资源地址，头像使用 QQ 头像 CDN。

## 客户端注意事项

- Koishi 的 `server.selfUrl` 须与 `server.port` 一致，QQ 进程才读得到资源地址
- `manual_self_messages=true` 时投递 QQ 客户端手动发送的消息，`manual_self_user_id` 可覆盖其虚拟作者 ID
- 私聊自己产生的消息不投递
