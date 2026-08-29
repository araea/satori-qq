# Satori v1（QQ 9.3.55）

本模块是 Satori **实现端**（SDK）：Koishi 的 `adapter-satori` 连 `http://127.0.0.1:3001`。

- HTTP：`POST /v1/{resource}.{method}`，JSON 请求体，成功 200；`upload.create` 使用 multipart。
- 事件：`GET /v1/events` 升级为 WebSocket；10s 内 `IDENTIFY`，回复 `READY`，之后推 `EVENT`。
- 元信息：`POST /v1/meta`；内部资源代理：`GET /v1/proxy/{url}`。
- `platform`：`red`；`adapter`：`satori-qq`。
- 群频道：`channel.id = 群号`，`type = 0`（TEXT），`guild.id` 同群号（`guild.plain`）。
- 私聊频道：`channel.id = private:{uin}`，`type = 1`（DIRECT）。
- 消息 `content` 为 Satori 元素串，例如 `hello <at id="123"/> <img src="https://..."/>`。

`ok` 主号真机通过（此前已核过的内核路径）；`limited` 本地正确但被服务器拦；`-` 明确不做。

“支持完整”按 **QQ 能提供的能力 + Satori SDK 通用回退** 计算。QQ 没有消息编辑、多频道、
自定义群角色或清空他人表态，因此对应标准 API 按协议返回 404，而不是伪造成功。
WebHook 属于协议可选功能，本实现只提供 WebSocket 事件流。

## 标准方法

| 方法 | 状态 | 对应能力 |
| --- | --- | --- |
| `login.get` | ok | 登录号、在线状态、`features` |
| `message.create` | ok | 发送；标准 `<message>` 可拆成多条，`<message forward>` 走合并转发 |
| `message.get` | ok | 按消息 id 取一条 |
| `message.list` | ok | 双向分页；`before` / `after` / `around`、`asc` / `desc`，游标用消息 seq |
| `message.delete` | ok | 撤回 |
| `channel.get` / `channel.list` | ok | 群即单一文字频道 |
| `channel.update` | ok | 改群名（`data.name`） |
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
| `special_title` | 群头衔 |
| `group_file` | `op=info\|list\|url\|upload` |
| `get_forward` | 按 resid 取合并转发 |
| `get_resource` | 下载已登记的图/语音/文件 |
| `status` / `version` | 实现端健康与版本 |
| `restart` / `clean_cache` | 退出 QQ 等 watchdog 拉起；清临时文件 |

## 事件

| Satori | 来源 |
| --- | --- |
| `message-created` | 收消息（含自己在 QQ 客户端发出的；机器人 API 发出的回声会去重） |
| `message-deleted` | 撤回 |
| `guild-added` / `updated` / `removed` | QQ 群列表变化 |
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

```yaml
plugins:
  server:
    port: 5140
    selfUrl: 'http://127.0.0.1:5140'   # 让 assets-local 产出 http 而不是 file:
  adapter-satori:
    endpoint: 'http://127.0.0.1:3001'
    token: '与 satori-qq.json 相同'
```

`selfUrl` 缺失时 assets 会变成 `file:///data/data/com.termux/...`，QQ 进程读不到，表现为发图失败。

私聊自己不投递。测发送用真实群或好友。
