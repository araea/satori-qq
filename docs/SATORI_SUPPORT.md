# Satori v1 接口

列出 QQ 9.3.70（NT）已核验的接口、事件与消息元素。

`typing` 与 `mark_read` 是实验性 JNI 扩展，不属于标准 Satori。内核回调以现场回执为准，超时不可推断为成功或失败。

## 连接约定

- **HTTP**：`POST /v1/{resource}.{method}`，JSON body，`upload.create` 用 multipart。`Satori-Platform` 与 `Satori-User-ID` 省略、留空或为 `0` 时按未指定处理，只有明确指向别的平台（非 `red`）或别的账号才回 403（官方服务端的 `login not found`）。
- **状态码**：缺失鉴权 401，令牌不对 403（WebSocket 的 IDENTIFY 令牌不对用 4004 关闭）。标准方法在 QQ 上没有对应能力回 404（不是 501），`GET` 打到方法路由回 405，参数缺失或格式错误 400，请求体超过 64 MiB 回 413。
- **错误体**：每个非 2xx 响应都是 `{"code": "<机器可读的短名>", "message": "<给人看的>"}`，客户端按 `code` 判断。常用的：`missing_token` / `invalid_token`、`login_not_found`、`unsupported_method`（404）、`invalid_request`、`payload_too_large`（413，`message` 里写着单个文件的上限）、`kernel_offline`、`session_stabilizing`、`outbound_queue_full` / `outbound_queue_timeout` / `outbound_circuit_open`（503）、`outbound_rate_limited`（429）。
- **暂时不可用**：请求在交给 QQ 之前被挡下（内核离线、上线后的稳定期、出站队列满、熔断中）回 **503**，超出每分钟发送额度回 **429**。能给出恢复时间的带 `Retry-After`（秒）。这几种情况消息没有发出，原样再发一次是安全的。内核离线没有可承诺的时间，不带 `Retry-After`。发出之后才出的错（`send outcome unknown`）不在此列。
- **事件**：`GET /v1/events` 升级 WebSocket，须在 10 秒内发 `IDENTIFY`，服务端回 `READY` 与 `EVENT`。省略 `sn` 建新会话，`sn=0` 回放缓冲区内 `sn>0` 的事件。账号未知时不发 `READY`，可知后补发。
- `READY` 里的登录账号就是之后每个请求要带回来的 `Satori-User-ID`。
- **官方客户端的登录域内路由**：动作 `POST /v1/internal/{platform}/{selfId}/_api/{name}`（`bot.internal.*`，参数按 `JsonForm` 编码，带 `Satori-Pagination: true` 时回 `{data, …}`）、资源 `GET /v1/internal/{platform}/{selfId}/_tmp/{id}`（`upload.create` 返回的 `internal:` 回落地址，免令牌）。两者只服务本机登录自身，其他登录 404。`POST /v1/internal/{name}` 是模块自己的简写。
- `POST /v1/meta` 取元信息。`GET`（及 `HEAD`，不带正文、`Content-Length` 照旧）`/v1/proxy/{url}` 代理本机资源，不需要任何头：不是合法 URL 或 `internal:` 格式不对回 400，登录或资源不存在 404，合法但不在 `proxy_urls` 的外链 403。本实现不下载外链，`proxy_urls` 恒为空。回包按块从文件读出，不整份进堆；支持单段 `Range`（`bytes=a-b`、`a-`、`-n` 回 206，越界 416，多段忽略当整份回）。
- **分页**：`guild.list`、`guild.member.list`、`guild.role.list`、`guild.member.role.list`、`channel.list`、`friend.list` 返回 `{data, next?}`。不带 `next` / `limit` 时一次给完，带时按偏移分页，`next` 是下一次的偏移量。非法令牌返回 400，不会悄悄从头重放。`message.list` 双向分页，见下表。
- `platform` 为 `red`，`adapter` 为 `satori-qq`。群频道的 `channel.id` 与 `guild.id` 均为群号、`channel.type=0`，私聊频道为 `private:{uin}`、`channel.type=1`。
- 消息 ID 用 QQ NT `msgId` 字符串，历史游标用 `message_seq`。`<quote>` 与 `[CQ:reply]` 的 `id` 可直接用于 `message.get` 与 `message.delete`。

QQ 没有等价能力的方法返回 404。下表中「受限」表示本地调用正确，结果可能受 QQ 服务端权限或风控限制。

## 标准方法

| 方法 | 状态 | 说明 |
| --- | --- | --- |
| `login.get` | 支持 | 登录账号、在线状态与功能列表 |
| `message.create` | 支持 | 发送消息；标准 `<message>` 可拆分多条，`<message forward>` 使用合并转发 |
| `message.get` | 支持 | 按 QQ `msgId` 查询，也接受当前进程内的旧式存储 ID |
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
| `reaction.clear` | 不支持 | 标准语义是清除所有用户的表态，QQ JNI 无此能力；清自己的用 `internal/reaction_clear` |
| `upload.create` | 支持 | 上传多个文件，返回 `internal:red/{uin}/_tmp/{id}`；请求体流式落盘，进堆的只有一个 128 KiB 窗口，单个文件上限见 `limits.upload_bytes` |
| `message.update` / `channel.create` / `channel.delete` | 不支持 | 返回 404 |
| `guild.role.create` / `update` / `delete` 及其余表态管理 | 不支持 | 返回 404 |

QQ 只能为当前登录号添加或撤销表态，因此 `reaction.delete` 传其他 `user_id` 时返回 400，`reaction.list` 必须提供 `emoji_id`，只在第一页合并自己的表态。明确的内核错误不会伪装成空列表。

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

两个字段必须一起提供。`expires_at` 为 Unix 毫秒截止时间，`if_latest_message_id` 必须仍是本进程最近向应用推送的该频道消息 ID。取得出站队列发送权后，以及媒体转换、重试等待后交给 QQ 内核前，各检查一次。任意新消息都会让旧条件失效。过期、频道状态未知或已被淘汰时跳过发送并返回 `[]`，不计作 QQ 发送失败，不触发熔断。多条拆分发送只返回已发送的部分。

调用方与实现端的系统时钟应保持一致。频道状态最多保留 4096 项，历史回放不更新频道状态。

## QQ 扩展方法

`POST /v1/internal/{name}`。写操作按顺序执行，受限频与熔断保护。

| 类别 | 方法 | 说明 |
| --- | --- | --- |
| 互动 | `poke` / `invite` | 戳一戳、邀请入群；poke 支持 `channel_id`（群号或 `private:QQ号`） |
| 表态 | `reaction_summary` / `reaction_clear` | 消息的回应计数与自己是否回应；仅清除自己的回应 |
| 群成员 | `special_title` / `card` | 设置群头衔或群名片；`special_title` 只设头衔，不带显示开关参数 |
| 群显示 | `title_display` / `honor_display` | 群头衔、群荣誉的显示开关；不带 `show` / `enable` 时只读当前状态，不写 |
| 群消息 | `sign` / `essence` | 群打卡；设置或取消精华消息 |
| 特殊消息 | `dice` / `rps` | 发送 QQ 原生骰子或猜拳（超级表情） |
| 消息读取 | `get_forward` / `chat_screenshot` | `chat_screenshot` 的范围渲染见下；`get_forward` 读取合并转发。`id` 是转发卡片里的 resId，或 `native:<父消息 ID>`。resId 走伪造节点协议，NT 客户端发的图片会整段丢失；`native:` 走 QQ 内核，图片、逐条消息 ID 与时间都在，优先使用。父消息不在模块缓存里时（模块重启或消息较旧）附带 `channel_id` 即可读取 |
| 能力查询 | `capabilities` / `help` / `compat` | `capabilities` 与 satori-wx 共用一份口径：`adapter`、`version`、`platform`、`standard_methods`（与 `login.features` 同源）、`unsupported`、`event_types`、`message_elements`（`message.create` 接受的元素）、`limits`（`upload_bytes`：`upload.create` 单个文件的字节上限，acumen 取片时按它收窄），再加本实现端的扩展动作清单（`actions`、`params`、`removed`）；`compat` 是内核接口面静态自检与运行时调用观测，QQ 升级后先跑它，`force=true` 强制重算 |
| 状态查询 | `status` / `version` | 健康状态或版本 |
| 维护 | `restart` / `clean_cache` | 退出 QQ 进程、清理临时文件 |

### 范围截图（离屏渲染）

`POST /v1/internal/chat_screenshot`（或登录域 `_api/chat_screenshot`），JSON 例如 `{"channel_id":"123456","start_message_id":"QQ消息ID","end_message_id":"QQ消息ID"}`。首尾必须是**同一频道**可从 QQ 本地历史查到的 NT 消息 ID，按 `message_seq` 正序，最多 40 条且含两端。返回 `file`（`internal:red/{selfId}/_tmp/{id}`）、`mime=image/png`、`count`。

由 QQ 内核历史读记录，经 Android `Canvas` 在 QQ 进程内渲染文字气泡。图片、语音、视频、文件只画类型占位，没有 QQ 客户端聊天页的皮肤、头像、媒体像素或跨屏截取，也不调用 MediaProjection 与截屏权限。内核历史漏掉起止消息、游标不前进、超过数量或画布超过 8192px 时失败，不返回截断图。图片在 QQ 的本地临时缓存中，资源 URL 仅本机监听且不带鉴权，任何能访问本机端口并拿到不透明 ID 的程序可读取。不要把 URL 公开转发，过期文件可用 `clean_cache` 清理。

### 扩展动作注册表

上表是逐条手写文档的核心动作。`ExtraSvc` 另有一份代码里登记的注册表，`capabilities` 的目录、参数说明、读写分类都从它生成。这里只按域列出方法名，完整参数以 `capabilities` / `help` 的返回为准：

| 域 | 方法（部分带别名，见 `capabilities`） |
| --- | --- |
| 消息 | `typing`、`mark_read`、`mark_read_seq`、`mark_all_read`、`message_abstract`、`click_inline_keyboard`、`reedit_recall`、`image_ocr`、`ocr_data`、`ocr_by_aio` |
| 群管理 | `group_shut_up_list`、`group_join_link`、`group_essence_list`、`group_essence_latest`、`group_essence_cached`、`group_remark`、`group_quit`、`group_join`、`group_join_info`、`group_join_noverify`、`group_bulletin_publish`、`group_bulletin_delete`、`group_bulletin_upload_pic`、`group_bulletin_get`、`group_ext_list`、`group_member_level`、`group_identity_list`、`group_member_card`、`group_profile_card` |
| 群文件 | `group_file_count`、`group_file_folder_create`、`group_file_folder_delete`、`group_file_folder_rename`、`group_file_delete`、`group_file_rename`、`group_file_move`、`group_file_trans` |
| 头衔 / 身份原语 | `identity_title_info`、`identity_level_info`：`setIdentityTitleInfo` / `setGroupIdentityLevelInfo` 的单次调用，不做读回校验；日常切换显示开关用上表的 `title_display`，它在这两条之外还加了本地 DB 补写与读回确认 |
| 资料与好友 | `user_detail`、`user_detail_by_uin`、`user_simple_info`、`long_nick`、`friend_remark_get`、`friend_remark_set`、`friend_category_add`、`friend_category_delete`、`friend_category_rename`、`friend_category_set`、`friend_category_set_batch`、`friend_add` |
| 其它 | `online_file_list`、`online_file_refuse`、`temp_chat_info` |

`group_remark`、`group_shut_up_list`、`user_detail`、`mark_read` 走反射内核服务这一条 JNI 通道，不发新的原始封包。批量查询与管理类的参数结构体字段名随 QQ 版本变化的风险比核心动作更高，调用前先用 `capabilities` 核对。

完整参数用 `capabilities` 或 `help` 查询，返回里的 `params` 字段逐条列出参数。扩展动作返回的内核原始数据由反射导出，QQ 增删字段时跟着变。`reaction_summary` 通过 JNI 消息服务读取本地消息快照，不批量查询用户，返回的计数可能晚于刚完成的动作。

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

QQ 客户端手动发出的消息以 `qq-client:{selfUin}` 作为虚拟作者，并在 `satori_qq.manual_self` 中携带实际身份。机器人 API 的发送回声会去重。登录期间的群列表同步不生成群变更事件。

推送的事件遵守协议的资源提升：`channel`、`guild`、`user`、`member` 只在事件顶层，`message` 里不再重复，`member` 里也没有 `user`。`message.get` / `message.list` 返回的 `Message` 才是嵌套形态，必需资源在它里面。

`user` 是这个人本身，`name` 与 `nick` 都是 QQ 昵称，在每个群里一样；群名片归成员，在 `member.nick`。

每个事件的顶层都带 `sn`、`type`、`timestamp`、`login`，以及 `self_id` 与 `platform`（Event 类型里与 `login` 并列的扁平字段。只有 `login` 时客户端也能工作，但按协议类型实现的一方读的是扁平字段）。`login.get` 与 `READY` 里的 login 带 `sn`、`adapter`、`platform`、`self_id`、`hidden`、`status`、`user` 与 `features`。

## 消息元素与媒体

| 方向 | 元素 |
| --- | --- |
| 发送 | `text` `at` `sharp` `quote` `emoji` `a` `br` `p` `img` `audio` `video` `file`、修饰元素与 `<message>`；另接受 `face` `json` `mface` `poke` |
| 接收 | `text` `at` `quote` `emoji` `face` `img` `audio` `video` `file` `json` `mface`；合并转发为 `<message forward id="resid"/>` |

### 入站元素类型

QQ 内核的 `elementType` 与本端收到的元素一一对应：

| elementType | 内核元素 | 本端 |
| --- | --- | --- |
| 1 | `TextElement`（含 @） | `text` / `at` |
| 2 / 3 / 4 / 5 | 图片 / 文件 / 语音 / 视频 | `img` / `file` / `audio` / `video` |
| 6 / 11 | 表情 / 商城表情 | `emoji` / `mface` |
| 7 | 引用 | `quote` |
| 8 | 灰条（撤回、打卡、成员变动、戳一戳…） | 通知事件 |
| 10 | `ArkElement`（小程序卡、分享卡、音乐卡，合并转发的原生卡也在这一类） | `json`；`com.tencent.multimsg` 转 `<message forward>` |
| 13 / 16 | 长消息 / 合并转发 | `<message forward>` |
| 14 | `MarkdownElement` | 正文以文本投递，保留 Markdown 标记 |
| 17 | `InlineKeyboardElement` | 按钮标签以只读文本投递，不暴露回调数据 |

图片的 `picSubType` 带在 `img` 的 `sub-type` 上：1 是收藏 / 自定义表情，群里斗图多半是这种，普通图片不带这个属性。`summary`（QQ 给的「[动画表情]」这类会话列表摘要）同理。发送时 `<img sub-type="1">` 会按表情的样子发出（小图、无相框，会话列表显示「[动画表情]」），客户端把收到的表情再发一遍就还是表情，而不是一张大图。

14 与 17 是 QQ 官方机器人与 AI 助手发的「Markdown 卡片 + 按钮」。正文及按钮标签作为 `text` 元素进入事件流。按钮只供阅读，本端没有点击 QQ 官方机器人的回调能力。字段结构依据 QQ NT `MsgElement` 类型。

### 卡片载荷

ark 卡整段载荷原样放在 `json` 元素的 `data` 属性里，`raw_message` 里则是 `[CQ:json,data=…]`。客户端读它要注意两点：

- 载荷里的斜杠是转义的（`"qqdocurl":"https:\/\/b23.tv\/xxx"`），在字符串上找不到 `https://`，要按 JSON 解析。
- 「点开这张卡会去哪」写在固定字段里，按可信度取：`meta.detail_1.qqdocurl`（小程序真正打开的那个页面）→ `meta.*.jumpUrl`（分享卡的落地地址）→ `meta.*.url`（多是小程序自己的路由 `m.q.qq.com/a/s/<hash>`）。`icon` / `preview` / `tagIcon` 是图，不是落地地址。

本端不改写载荷，也不替客户端挑地址。Satori 没有卡片元素，裁剪一次就回不去，字段优先级由客户端按用途定（同机的 acumen 在 `command::card_target_url` 里按此序取）。

媒体 `src` 接受 `http(s):`、`data:`、`file:`、本地路径与 `internal:`。收到的图片、语音、视频、文件在事件里是 `internal:red/{selfId}/_tmp/{id}`（与 `upload.create` 同一份存储），不带本机地址：换主机或端口不会让旧链接失效，调用方经 `/v1/proxy/{url}` 读取，原样放进回复里时本端直接从磁盘解析，不请求自己。头像使用 QQ 头像 CDN。

`<audio src>` 先转成 QQ 的 SILK（mp3、wav、amr 都走这条路），整条带重试、最多三次：`MediaCodec` 的解码器在 QQ 进程里会被系统回收，`queueInputBuffer` 抛一个空消息的 `CodecException`，换个解码器重来。重试按时间收口：上次耗时乘二超过 20 秒就不试。`node tests/media-live-probe.js voicerepeat` 能量这件事。`<video>` 需本地取到视频帧做缩略图。`<file>` 既发聊天气泡，也进群文件列表。

### 顺媒体必须单独成条

`audio` / `video` / `file` 在 QQ 里是「顺媒体」：一条消息带了其中一种，就只能有它自己，再挂 `quote`、`at`、`text`、`img`，客户端渲染不出同条内容（顺媒体显示成空，引用与文字一起乱）。实测的现场是「引用 + 视频」在群里只剩一个空气泡。

本端把这种拼法拆成几条发出去（`Batching.splitChunkMedia`，对所有批次生效）：按原顺序拆开，其余段落合成一条先发，每个顺媒体各成一条，只剩 `quote` 的空壳丢掉。顺序与内容不丢，客户端照常写它想写的那条消息即可。拆开后一次 `message.create` 会真的发出多条，回执数组里就有多个消息 ID。

图片不在其列：`img` 可与引用、文字同条（`<quote/><img/>` 是常见形状）。

`message.create` 同步等 QQ 内核回调：语音约 1 秒，文件要等上传，慢时会占满 20 秒的等待窗口（超时后按「无回调」放行，消息通常已经发出）。客户端超时不要低于 30 秒。

## 客户端注意事项

- Koishi 的 `server.selfUrl` 须与 `server.port` 一致，QQ 进程才读得到资源地址。
- `manual_self_messages=true` 时投递 QQ 客户端手动发送的消息，`manual_self_user_id` 可覆盖其虚拟作者 ID。
- 私聊自己产生的消息不投递。

## 与官方协议的对照

对照物：`@satorijs/protocol@1.7.0`、`@satorijs/core@4.6.0`、`@satorijs/adapter-satori@1.5.1` 与 `@satorijs/server` 的 satori 服务端实现。

| 面 | 结论 |
| --- | --- |
| 传输 | `GET /v1/events` 升级 WebSocket；动作 `POST /v1/<method>`；扩展 `POST /v1/internal/<name>`。`GET` 打到动作上回 405 |
| 网关 | `IDENTIFY{token,sn}` / `READY{logins,proxy_urls}` / `PING`→`PONG` / `EVENT`，opcode 与协议一致。`sn` 递增，重连时带 `sn` 会补发错过的动作回执 |
| 认证 | HTTP 用 `Authorization: Bearer`，WS 用 IDENTIFY 里的 `token`。`Satori-User-ID` / `Satori-Platform` 必须指向本实现端的登录 |
| 对象 | `Login{sn,adapter,platform,status,features,user}`、`Guild`、`Channel`、`GuildMember`、`Message`、`List{data,next?}`、`Meta{logins,proxy_urls}` |
| 方法 | 官方 37 个里实现 31 个，`features` 只列实现得了的 |
| 上传 | `upload.create` 是 multipart，回 `{<字段名>: <引用>}`，引用形如 `internal:<platform>/<selfId>/_tmp/<id>` |
| 事件名 | `message-created`、`guild-member-added/updated/removed`、`guild-request`、`guild-member-request`、`friend-request` |
| 非标准事件 | 纯自定义走 `type=internal` + `_type` / `_data`。标准事件加细节走 `type=guild-member-updated` + `_type=satori-qq/mute` |

刻意不同的：

- **缺 6 个方法**：`channel.create`、`channel.delete`、`guild.role.create|update|delete`、`message.update`。QQ 的群就是频道、没有自定义角色、也不支持改消息，回 404，能力表里也不列。
- **`channel.update` 的改名只有一条写入路径**：只调内核的 `modifyGroupName`，不做二次写入，也不回读校验。空名字一律拒绝。实现见 [`ARCHITECTURE.md`](ARCHITECTURE.md#群资料写入)。
- **`reaction-removed`**：事件名是 `reaction-removed`。严格照协议包的客户端要兼容 `reaction-deleted` 别名。
- **`login-updated`**：不在协议包的 `EventName` 里，官方客户端的 WS 分支显式处理它，换号时靠它通知客户端重连。
- **列表分页**：不带 `next` / `limit` 一次给完，指定分页后按 `next` 继续读取。
- **`channel.get` 多回一个 `avatar`**：协议里 `Channel` 没有这个字段，客户端会原样忽略。

对照可以直接跑：`python3 tests/conformance.py --base http://127.0.0.1:3001`，只读黑盒探针，不发聊天消息。`--listen 60` 再核对实时事件的形状。

## 与 Acumen 的协作约定

以 [Satori 事件规范](https://satori.chat/zh-CN/protocol/events.html)、[表态规范](https://satori.chat/zh-CN/resources/reaction.html) 和 [扩展规范](https://satori.chat/zh-CN/advanced/internal.html) 为准：

- READY、历史回放和实时事件在同一把投递锁内串行完成。所有广播事件在投递时统一分配 sn。重复 IDENTIFY 不重新回放。
- 恢复连接不重复投递待审批申请。登录事件不进历史缓冲。换号清除回放与表态缓存。
- READY 附加 `satori_qq.session_id` 与 `satori_qq.sn`，供 Acumen 识别模块进程重启。带旧 `sn` 的 IDENTIFY 永远得到 READY。sn 从当前毫秒时间起算，缓冲为当前进程最近 4096 条。
- `reaction.clear` 从 features 移除并返回 404。只清自己的非标准行为在 `POST /v1/internal/reaction_clear`，参数 `channel_id, message_id, emoji_id?`，返回 `{cleared, scope:"self"}`。无自己表态时返回 0，部分失败明确报错。
- `POST /v1/internal/reaction_summary` 参数 `channel_id, message_id`，返回 `{message_id, data:[{emoji_id,count,self}], source:"kernel_cache", observed_at}`。count 来自 QQ 本地缓存，self 优先使用本模块已确认的动作，两者更新时间可能不同。
- 表态事件的 `_type=satori-qq/reaction`、`_data={before,count,delta}` 解释数量变化。没有可靠操作者时不填写 user。
- QQ 专有元素输出 `satori-qq:json`、`satori-qq:mface`、`satori-qq:poke`，输入也接受裸名称。骰子、猜拳使用标准 `<emoji>`。HTTP poke 是头像戳一戳，消息里的 poke 是表情元素。
- 排队中的写入及媒体转换后的发送会复核登录账号，旧账号请求不会自动改为新账号执行。

真实 QQ 权限、回应缓存时延与服务端频率限制以回执为准。超时是结果未知，客户端不能盲目重发动作。
