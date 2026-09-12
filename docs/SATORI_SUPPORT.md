# Satori v1 接口

列出 QQ 9.3.55 与 9.3.60.40970（NT）已核验的接口、事件与消息元素。

## 连接约定

- HTTP：`POST /v1/{resource}.{method}`，请求体为 JSON，`upload.create` 使用 multipart
- 事件：`GET /v1/events` 升级为 WebSocket。客户端须在 10 秒内发送 `IDENTIFY`，服务端随后发送 `READY` 与 `EVENT`。省略 `sn` 创建新会话，显式指定 `sn=0` 可回放缓冲区内 `sn > 0` 的事件
- 元信息：`POST /v1/meta`；资源代理：`GET /v1/proxy/{url}`
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
| `channel.mute` | 支持 | 全员禁言；`duration=0` 解除，正值到时自动解除 |
| `user.channel.create` | 支持 | 创建 `private:{uin}` 私聊频道 |
| `guild.get` / `guild.list` | 支持 | 查询群 |
| `guild.member.get` / `guild.member.list` | 支持 | 查询成员及 owner、admin、member 角色 |
| `guild.member.kick` | 支持 | `permanent` 对应拒绝再次加群 |
| `guild.member.mute` | 支持 | `duration` 单位为毫秒 |
| `guild.member.role.set` / `unset` | 支持 | 仅支持 `role_id=admin` |
| `guild.role.list` | 支持 | 返回 owner、admin、member |
| `user.get` | 支持 | 查询用户资料 |
| `friend.list` / `friend.delete` | 支持 | 查询或删除好友，删除时不额外拉黑 |
| `friend.approve` | 受限 | 处理好友申请，`message_id` 为申请 flag |
| `guild.approve` / `guild.member.approve` | 受限 | 处理群邀请或加群申请 |
| `reaction.create` / `delete` / `list` | 支持 | `emoji_id` 为表情 ID，只能删除自己的表态 |
| `upload.create` | 支持 | 上传多个文件，返回 `internal:red/{uin}/_tmp/{id}` |
| `message.update` / `channel.create` / `channel.delete` | 不支持 | 返回 404 |
| `reaction.clear` / `guild.role.create` / `update` / `delete` | 不支持 | 返回 404 |

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
| 好友 | `friend_remark` | 查询或设置好友备注 |
| 好友 | `friend_top` | 置顶或取消置顶与好友的会话 |
| 好友 | `friend_msg_notify` | 开启或关闭单个好友的消息提醒 |
| 好友 | `friend_block` | 拉黑或取消拉黑好友 |
| 好友 | `friend_relation` | 查询是否为好友、是否已拉黑及备注 |
| 好友 | `friend_add` | 发送好友申请 |
| 联系人 | `recent_contacts` | 查询最近联系人及未读数 |
| 能力查询 | `capabilities` / `help` | 返回扩展动作与参数清单 |
| 状态查询 | `status` / `version` | 返回健康状态或版本 |
| QQ 空间 | `qzone.publish` / `qzone.create` | 发布说说 |
| QQ 空间 | `qzone.delete` / `qzone.list` | 删除或列出说说 |
| QQ 空间 | `qzone.clear` / `qzone.delete_all` / `qzone.delete-all` | 删除全部说说 |
| QQ 空间 | `qzone.auth` | 读取调试用鉴权信息 |
| 维护 | `restart` / `clean_cache` | 退出 QQ 进程或清理临时文件 |

完整参数用 `capabilities` 或 `help` 查询。`group_member_search.next` 可直接用于下一次扩展调用，`next_offset` 供 HTTP 客户端分页。

个人资料、群设置与好友类动作只接受一个目标（`user_id` 或 `guild_id`）与少量开关，默认值取「不改变现状」的一侧：`friend_top` 缺省置顶，`friend_msg_notify` 缺省开启提醒，`friend_block` 缺省拉黑。`friend_remark` 带 `remark` 时写入、`op=get` 时读取，空字符串表示清除备注。`group_msg_mask` 除 `mask` 外也接受 `shield` 布尔简写。这些动作都能回读：`friend_relation` 带回备注，`profile_self` 带回个性签名与当前在线状态。这些动作受 QQ 自身权限与账号状态限制。

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
