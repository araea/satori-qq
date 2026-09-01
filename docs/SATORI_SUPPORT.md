# Satori v1（QQ 9.3.55）

`adapter-satori` 连 `http://127.0.0.1:3001`。

- HTTP：`POST /v1/{resource}.{method}`，JSON 请求体，成功 200；`upload.create` 使用 multipart。
- 事件：`GET /v1/events` 升级为 WebSocket；10s 内 `IDENTIFY`，回复 `READY`，之后推 `EVENT`。省略 `sn` 表示新会话；显式 `sn=0` 回放缓冲区内 `sn > 0` 的事件。
- 消息 `id` 为 QQ NT `msgId` 字符串；历史游标为 `message_seq`。
- `<quote>` / `[CQ:reply]` 的 `id` 同样是 `msgId`，可直接喂给 `message.get`、`message.delete`。
- 元信息：`POST /v1/meta`；资源代理：`GET /v1/proxy/{url}`。
- `platform`：`red`；`adapter`：`satori-qq`。
- 群频道：`channel.id = 群号`，`type = 0`；`guild.id` 同群号。
- 私聊频道：`channel.id = private:{uin}`，`type = 1`。
- 消息 `content` 为 Satori 元素串，如 `hello <at id="123"/> <img src="https://..."/>`。

QQ 无消息编辑、多频道、自定义群角色或清空他人表态时，对应 API 返回 404。仅提供 WebSocket 事件流。

`ok` 真机通过；`limited` 本地正确但被服务器拦；`-` 不做。

## 标准方法

| 方法 | 状态 | 对应能力 |
| --- | --- | --- |
| `login.get` | ok | 登录号、在线状态、`features` |
| `message.create` | ok | 发送；标准 `<message>` 可拆成多条，`<message forward>` 走合并转发 |
| `message.get` | ok | 按 QQ `msgId` 字符串取一条；也认旧进程内 store id |
| `message.list` | ok | 双向分页；`before` / `after` / `around`、`asc` / `desc`，游标用 `message_seq`；`message.id` / `user.id` 与实时事件一致 |
| `message.delete` | ok | 撤回；`message_id` 为 QQ `msgId` |
| `channel.get` / `channel.list` | ok | 群即单一文字频道 |
| `channel.update` | ok | 群主/管理改群名（`data.name`）和/或群头像（`data.avatar`：本地路径 / `file:` / `http(s)` / `data:` / `internal:`） |
| `channel.mute` | ok | 全员禁言；`duration=0` 解除，正值到时自动解除（进程重启会丢失计时） |
| `user.channel.create` | ok | 得到 `private:{uin}` |
| `guild.get` / `guild.list` | ok | 群 |
| `guild.member.get` / `guild.member.list` | ok | 成员；`roles` 为 owner/admin/member |
| `guild.member.kick` | ok | `permanent` 对应拒加群 |
| `guild.member.mute` | ok | `duration` 为**毫秒** |
| `guild.member.role.set` / `unset` | ok | 仅 `role_id=admin` |
| `guild.role.list` | ok | 固定返回 owner/admin/member |
| `user.get` | ok | 资料 |
| `friend.list` | ok | `{ user, nick }` |
| `friend.delete` | ok | 删除好友，不额外拉黑 |
| `friend.approve` | partial | `message_id` 为申请 flag |
| `guild.approve` / `guild.member.approve` | partial | 邀请 / 加群申请 |
| `reaction.create` / `delete` / `list` | ok | `emoji_id` 为表情 id；只能删除自己的表态；列表使用 QQ continuation cookie |
| `upload.create` | ok | 标准 multipart，多文件返回 `internal:red/{uin}/_tmp/{id}` |
| `message.update` / `channel.create` / `channel.delete` | - | QQ 无对应能力，404 |
| `reaction.clear` / `guild.role.create/update/delete` | - | QQ 无等价能力，404 |

## 内部方法

`POST /v1/internal/{name}`，给 QQ 特有能力：

| 路径 | 作用 |
| --- | --- |
| `poke` | 戳一戳。`user_id` + 可选 `guild_id` |
| `like` | 资料卡点赞（服务器常 319） |
| `invite` | 邀请用户入群。`guild_id` + `user_id` |
| `special_title` | 群头衔；可带 `show=true` 同时打开群管理「展示成员群头衔」 |
| `title_display` | 开/关群管理「成员群头衔」（`userShowFlag` / `0x8FC_0`，不是群标识）。`guild_id` + `show` |
| `honor_display` | 开/关群聊资料中的「群荣誉 / 群标识」（`groupFlagExt3`），与成员群头衔分离。`guild_id` + `show` |
| `card` | 改群名片。`guild_id` + 可选 `user_id` + `card` |
| `sign` | 群打卡（`0xEB7_1`）。`guild_id` |
| `essence` | 设/取消精华。`message_id` + 可选 `op=add\|remove` |
| `group_remark` | 自己对这个群的备注。`guild_id` + `remark` |
| `group_extra` | 读群扩展标志，含 `honor_open` |
| `group_overview` | 群仪表盘：群/频道、成员角色与活跃统计、自己的成员信息、展示开关；可选 `include_members` / `include_files` |
| `group_member_search` | 按 UIN、昵称、群名片、头衔和角色搜索群成员，支持 `offset` / `limit` |
| `group_active` | 群潜水榜 / 活跃榜：按最后发言时间排序（`order=inactive\|active`），可选 `days` 阈值、`role`、`limit`，含 `never_spoke` 统计。只读 |
| `member_info` | 单个群成员详情（角色、群名片、头衔、等级、入群与最后发言时间）。`guild_id` + `user_id`。只读 |
| `random_member` | 群随机点名 / 抽奖：`count` 名，可 `exclude_self` / `exclude_bots`（管理）/ `active_within_days`；Fisher–Yates 无重复。只读 |
| `random_team` | 群随机分队：`team_count` 队，可用 `user_ids` 指定参赛者，并按自己、管理、角色、近期活跃度过滤；安全随机、人数均衡。只读 |
| `group_anniversary` | 未来 `days` 天的入群周年日历，含周年数与倒计时；按设备时区计算，闰日成员在非闰年按 2 月 28 日纪念。只读 |
| `contact_search` | 按号码、昵称、备注或群名搜索好友与群；`type=all|friend|guild` |
| `group_refresh` | 强制刷新 QQ 内核群列表并返回最新群列表 |
| `group_leave` | 退出群；`guild_id` + 必须显式 `confirm=true` |
| `group_file` | 完整群文件管理：`op=info\|list\|url\|upload\|create_folder\|rename_folder\|delete_folder\|rename_file\|move_file\|delete_file` |
| `get_forward` | 按 resid 取合并转发 |
| `get_resource` | 下载已登记的图/语音/文件 |
| `message_context` | 按 `channel_id` + `message_id` 返回消息及其前后文；`before` / `after` 各最多 50 条 |
| `message_search` | 在单个 `channel_id` 的近期本地历史中按 `query` / `user_id` / 时间筛选；`scan_limit` 默认 200、最多 1000，返回续查游标。只读 |
| `dice` / `rps` | 向 `channel_id`、`guild_id` 或 `user_id` 发送 QQ 原生随机骰子 / 猜拳 |
| `capabilities` / `help` | 返回内部动作、群文件操作和特殊表情 ID 的机器可读清单 |
| `status` / `version` | 实现端健康与版本 |
| `qzone.publish` / `qzone.create` | 发说说。`content` + 可选 `ugc_right` |
| `qzone.delete` | 删一条说说。`tid` |
| `qzone.list` | 列说说（含仅自己可见）。`limit?` |
| `qzone.clear` / `qzone.delete_all` / `qzone.delete-all` | 清空全部说说 |
| `qzone.auth` | 调试 QZone 鉴权（pskey 等） |
| `restart` / `clean_cache` | 退出 QQ 等 watchdog 拉起；清临时文件 |

所有内部写操作经过串行、限频与熔断保护。

Koishi 示例：

```js
const overview = await bot.internal.groupOverview({ guild_id: '123456' })
const members = await bot.internal.group.member.search({
  guild_id: '123456', query: '群名片', limit: 20,
})
const context = await bot.internal.messageContext({
  channel_id: '123456', message_id: '7000000000000000000', before: 5, after: 5,
})
```

`group_member_search.next` 是下一次 internal 调用所需的参数数组，可直接被适配器的异步迭代器消费；
同时提供数值型 `next_offset` 方便直接 HTTP 客户端。

## 事件

| Satori | 来源 |
| --- | --- |
| `message-created` | 收消息；QQ 客户端手发消息使用 `qq-client:{selfUin}` 虚拟作者并携带 `satori_qq.manual_self`，机器人 API 回声会去重 |
| `message-deleted` | 撤回 |
| `guild-added` / `updated` / `removed` | 加群 / 退群 / 少量真实改名；登录拉全表（INIT/SYNC/REFRESH）不刷 |
| `channel-added` / `updated` / `removed` | 同一群变化的 `guild.plain` 单频道映射 |
| `guild-member-added` / `removed` | 进群 / 退群或踢人 |
| `guild-member-updated` | 禁言（`_type=satori-qq/mute`） |
| `reaction-added` / `reaction-removed` | QQ 消息表态计数变化（平台回调不提供操作者） |
| `friend-request` / `guild-member-request` / `guild-request` | 好友 / 加群 / 邀请 |
| `internal` + `_type=satori-qq/poke` | 戳一戳 |
| `login-updated` | 内核上线或掉线 |

## 消息元素

| 方向 | 元素 |
| --- | --- |
| 发送 | 标准 `text` `at` `sharp` `quote` `emoji` `a` `br` `p` `img` `audio` `video` `file`、修饰元素、`<message>`；并兼容 `face` `json` `mface` `poke` |
| 接收 | 标准 `text` `at` `quote` `emoji` `img` `audio` `video` `file`；合并转发卡片为 `<message forward id="resid"/>` |

媒体 `src` 可以是 http(s)、`data:`、`file:`、本地路径、`upload.create` 返回的 `internal:`，或本端 `http://127.0.0.1:3001/v1/assets/{id}`。

- `login.user.avatar` / 成员 `user.avatar` 为 QQ 头像 CDN（`q.qlogo.cn`，spec=640）。
- 入站图片优先给出 `/v1/assets/{id}`（GET 无需 Bearer，供 Koishi / puppeteer `<img>` 渲染）；过期的 qpic 直链不再优先。
- 出站：`data:` 与 `base64://` 会落盘；HTTP 按魔数识别 png/jpeg/gif/webp（RIFF+WEBP 不再误判成 wav）。

## Koishi

`selfUrl` 须与 `server.port` 一致，否则 assets 会变成 QQ 进程读不到的 `file://` 路径。

`manual_self_messages=true`（默认）时，QQ 客户端手发消息以 `qq-client:{QQ号}` 投递，真实身份在 `session.event.satoriQq.actualUserId`。`manual_self_user_id` 可覆盖。

私聊自己不投递。
