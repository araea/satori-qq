# Satori v1 接口

本文列出 QQ 9.3.55 与 9.3.60.40970（NT）已核验的接口、事件和消息元素。

## 连接约定

- HTTP：`POST /v1/{resource}.{method}`，请求体为 JSON；`upload.create` 使用 multipart。
- 事件：`GET /v1/events` 升级为 WebSocket。客户端须在 10 秒内发送 `IDENTIFY`，服务端随后发送
  `READY` 和 `EVENT`。省略 `sn` 创建新会话；显式指定 `sn=0` 可回放缓冲区内 `sn > 0` 的事件。
- 元信息：`POST /v1/meta`；资源代理：`GET /v1/proxy/{url}`。
- 平台为 `red`，适配器为 `satori-qq`。
- 群频道的 `channel.id` 和 `guild.id` 均为群号，`channel.type=0`。
- 私聊频道为 `private:{uin}`，`channel.type=1`。
- 消息 ID 使用 QQ NT `msgId` 字符串；历史游标使用 `message_seq`。
- `<quote>` 与 `[CQ:reply]` 的 `id` 可直接用于 `message.get` 和 `message.delete`。

QQ 没有等价能力的方法返回 404。事件仅通过 WebSocket 提供。下表中“受限”表示本地调用正确，
但结果可能受 QQ 服务端权限或风控限制。

## 标准方法

| 方法 | 状态 | 说明 |
| --- | --- | --- |
| `login.get` | 支持 | 登录账号、在线状态和功能列表 |
| `message.create` | 支持 | 发送消息；标准 `<message>` 可拆分多条，`<message forward>` 使用合并转发 |
| `message.get` | 支持 | 按 QQ `msgId` 查询；兼容当前进程内的旧式存储 ID |
| `message.list` | 支持 | `before`、`after`、`around` 双向分页；游标为 `message_seq` |
| `message.delete` | 支持 | 按 QQ `msgId` 撤回 |
| `channel.get` / `channel.list` | 支持 | 每个群映射为一个文字频道 |
| `channel.update` | 支持 | 群主或管理员修改群名和群头像 |
| `channel.mute` | 支持 | 全员禁言；`duration=0` 解除，正值到时自动解除 |
| `user.channel.create` | 支持 | 创建 `private:{uin}` 私聊频道 |
| `guild.get` / `guild.list` | 支持 | 查询群 |
| `guild.member.get` / `guild.member.list` | 支持 | 查询成员及 owner、admin、member 角色 |
| `guild.member.kick` | 支持 | `permanent` 对应拒绝再次加群 |
| `guild.member.mute` | 支持 | `duration` 单位为毫秒 |
| `guild.member.role.set` / `unset` | 支持 | 仅支持 `role_id=admin` |
| `guild.role.list` | 支持 | 返回 owner、admin、member |
| `user.get` | 支持 | 查询用户资料 |
| `friend.list` / `friend.delete` | 支持 | 查询或删除好友；删除时不额外拉黑 |
| `friend.approve` | 受限 | 处理好友申请；`message_id` 为申请 flag |
| `guild.approve` / `guild.member.approve` | 受限 | 处理群邀请或加群申请 |
| `reaction.create` / `delete` / `list` | 支持 | `emoji_id` 为表情 ID；只能删除自己的表态 |
| `upload.create` | 支持 | 上传多个文件，返回 `internal:red/{uin}/_tmp/{id}` |
| `message.update` / `channel.create` / `channel.delete` | 不支持 | 返回 404 |
| `reaction.clear` / `guild.role.create/update/delete` | 不支持 | 返回 404 |

`message.create` 的 `forward_mode` 可设为 `auto`、`native` 或 `fake`。`auto` 优先使用 QQ 原生
合并转发；原生结果返回可撤回的真实 `msgId`。`channel.update.data.avatar` 接受本地路径、
`file:`、`http(s):`、`data:` 和 `internal:`。全员禁言的自动解除计时不会跨 QQ 进程重启保留。

## QQ 扩展方法

扩展方法使用 `POST /v1/internal/{name}`。写操作按顺序执行，并受限频和熔断保护。

| 类别 | 方法 | 说明 |
| --- | --- | --- |
| 互动 | `poke` / `like` / `invite` | 戳一戳、资料卡点赞、邀请入群 |
| 群成员 | `special_title` / `card` | 设置群头衔或群名片 |
| 群显示 | `title_display` / `honor_display` | 设置成员群头衔或群荣誉显示开关 |
| 群设置 | `group_remark` / `group_extra` | 设置本地群备注，或读取群扩展标志 |
| 群管理 | `group_refresh` / `group_leave` | 刷新群列表；退群须指定 `confirm=true` |
| 群消息 | `sign` / `essence` | 群打卡；设置或取消精华消息 |
| 群概览 | `group_overview` | 返回频道、成员、角色、活跃统计和展示开关 |
| 成员查询 | `group_member_search` / `member_info` | 搜索群成员或读取单个成员详情 |
| 活跃度 | `group_active` / `group_anniversary` | 查询活跃排行或入群周年日历 |
| 随机选择 | `random_member` / `random_team` | 无重复抽取成员或均衡随机分队 |
| 联系人 | `contact_search` | 按号码、昵称、备注或群名搜索好友与群 |
| 群文件 | `group_file` | 查询、上传、移动、重命名或删除群文件和目录 |
| 消息读取 | `get_forward` / `get_resource` | 读取合并转发或已登记资源 |
| 消息查询 | `message_context` / `message_search` | 查询消息上下文或近期本地历史 |
| 特殊消息 | `dice` / `rps` | 发送 QQ 原生骰子或猜拳 |
| 能力查询 | `capabilities` / `help` | 返回扩展动作和参数清单 |
| 状态查询 | `status` / `version` | 返回健康状态或版本 |
| QQ 空间 | `qzone.publish` / `qzone.create` | 发布说说 |
| QQ 空间 | `qzone.delete` / `qzone.list` | 删除或列出说说 |
| QQ 空间 | `qzone.clear` / `qzone.delete_all` / `qzone.delete-all` | 删除全部说说 |
| QQ 空间 | `qzone.auth` | 读取调试用鉴权信息 |
| 维护 | `restart` / `clean_cache` | 退出 QQ 进程或清理临时文件 |

完整参数可通过 `capabilities` 或 `help` 查询。`group_member_search.next` 可直接用于下一次扩展
调用，`next_offset` 可供 HTTP 客户端分页。

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

QQ 客户端手动发出的消息以 `qq-client:{selfUin}` 作为虚拟作者，并在
`satori_qq.manual_self` 中携带实际身份；机器人 API 的发送回声会去重。登录期间的群列表同步
不会生成群变更事件。

## 消息元素与媒体

| 方向 | 元素 |
| --- | --- |
| 发送 | `text` `at` `sharp` `quote` `emoji` `a` `br` `p` `img` `audio` `video` `file`、修饰元素和 `<message>`；兼容 `face` `json` `mface` `poke` |
| 接收 | `text` `at` `quote` `emoji` `img` `audio` `video` `file`；合并转发为 `<message forward id="resid"/>` |

媒体 `src` 接受 `http(s):`、`data:`、`file:`、本地路径、`upload.create` 返回的 `internal:`，
以及本端的 `/v1/assets/{id}`。入站图片优先返回无需 Bearer 令牌的本地资源地址；头像使用
QQ 头像 CDN。

## 客户端注意事项

- Koishi 的 `server.selfUrl` 须与 `server.port` 一致，确保 QQ 进程可读取资源地址。
- `manual_self_messages=true` 时会投递 QQ 客户端手动发送的消息；
  `manual_self_user_id` 可覆盖其虚拟作者 ID。
- 私聊自己产生的消息不投递。
