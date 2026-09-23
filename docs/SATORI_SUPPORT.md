# Satori v1 接口

列出 QQ 9.3.65（NT）已核验的接口、事件与消息元素。

0.29.1：恢复会话时，待审批申请的连接本地投递与广播事件统一在投递锁内分配序列号。
连接本地申请不会进入历史缓冲，序列号允许有空洞，但单连接收到的事件不会倒序。
`typing` 与 `mark_read` 是实验性 JNI 扩展（已注册，内核回调仍须现场验证），
不属于标准 Satori；客户端应按需启用并以回执为准，超时不可推断为成功或失败。

## 连接约定

- HTTP：`POST /v1/{resource}.{method}`，JSON body，`upload.create` 用 multipart。`Satori-Platform` 与
  `Satori-User-ID` 省略、留空或为 `0` 时按未指定处理，只有明确指向别的平台（非 `red`）或别的账号才回 404
- 事件：`GET /v1/events` 升级 WebSocket，须在 10 秒内发 `IDENTIFY`，服务端回 `READY` 与 `EVENT`。省略
  `sn` 建新会话，`sn=0` 回放缓冲区内 `sn>0` 的事件。账号未知时不发 `READY`，可知（每秒轮询）后补发，
  不把占位账号发给客户端
- `READY` 里的登录账号就是之后每个请求要带回来的 `Satori-User-ID`
- 官方客户端的登录域内路由：动作 `POST /v1/internal/{platform}/{selfId}/_api/{name}`（`bot.internal.*`，
  参数按 `JsonForm` 编码，带 `Satori-Pagination: true` 时回 `{data, …}`）、资源
  `GET /v1/internal/{platform}/{selfId}/_tmp/{id}`（`upload.create` 返回的 `internal:` 回落地址，免令牌）。
  两者只服务本机登录自身，其他登录 404；`POST /v1/internal/{name}` 是模块自己的简写
- `POST /v1/meta` 取元信息；`GET /v1/proxy/{url}` 代理本机资源
- 分页：`guild.list`、`guild.member.list`、`guild.role.list`、`guild.member.role.list`、`channel.list`、
  `friend.list` 返回 `{data, next?}`；不带 `next`/`limit` 时一次给完，带时按偏移分页，`next` 是下一次的
  偏移量；非法令牌返回 400，不会悄悄从头重放。`message.list` 双向分页，见下表
- `platform` 为 `red`，`adapter` 为 `satori-qq`；群频道的 `channel.id` 与 `guild.id` 均为群号、
  `channel.type=0`，私聊频道为 `private:{uin}`、`channel.type=1`
- 消息 ID 用 QQ NT `msgId` 字符串，历史游标用 `message_seq`；`<quote>` 与 `[CQ:reply]` 的 `id` 可直接
  用于 `message.get` 与 `message.delete`

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
| `reaction.clear` | 不支持 | 标准语义是清除所有用户的表态，QQ JNI 无此能力；清自己的用 `internal/reaction_clear` |
| `upload.create` | 支持 | 上传多个文件，返回 `internal:red/{uin}/_tmp/{id}` |
| `message.update` / `channel.create` / `channel.delete` | 不支持 | 返回 404 |
| `guild.role.create` / `update` / `delete` 及其余表态管理 | 不支持 | 返回 404 |

QQ 只能为当前登录号添加或撤销表态，因此 `reaction.delete` 传其他 `user_id` 时返回 400，
`reaction.list` 必须提供 `emoji_id`；只在第一页合并自己的表态，明确的内核错误不会伪装成空列表。

`message.create` 的 `forward_mode` 可设为 `auto`、`native` 或 `fake`，`auto` 优先使用 QQ 原生合并转发。
`channel.update.data.avatar` 接受本地路径、`file:`、`http(s):`、`data:` 与 `internal:`。全员禁言的自动
解除计时不跨 QQ 进程重启保留。

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

两个字段必须一起提供。`expires_at` 为 Unix 毫秒截止时间，`if_latest_message_id` 必须仍是本进程最近
向应用推送的该频道消息 ID。取得出站队列发送权后，以及媒体转换、重试等待后交给 QQ 内核前，各检查一次。
任意新消息都会让旧条件失效；过期、频道状态未知或已被淘汰时跳过发送并返回 `[]`，不计作 QQ 发送失败，
不触发熔断。多条拆分发送只返回已发送的部分。

调用方与实现端的系统时钟应保持一致。频道状态最多保留 4096 项，历史回放不更新频道状态。

## QQ 扩展方法

`POST /v1/internal/{name}`。写操作按顺序执行，受限频与熔断保护。

0.17.0 起只剩「会真的产生一次出站动作」的动作与合并转发的解析路径：原先那批按号问 QQ 要资料的
内核接口（群详情、群统计、成员搜索、收藏表情、未读、群文件、QQ 空间…）整批撤掉，它们是最不像真人
客户端的一类请求。0.23.0 又去掉 `like`（点赞走 WUP，本实现只走 JNI）。0.29.0 起这批按号查资料的
内核接口经 `ExtraSvc` 的动作注册表重新接回一部分（见下方「扩展动作注册表」一节），产品口径没变，
只是当年撤下时还没有这套统一登记、限频与熔断都接得上的机制。仍然移除、没有回来的动作一律 404，
`capabilities` 的 `removed` 段写明版本与原因。

| 类别 | 方法 | 说明 |
| --- | --- | --- |
| 互动 | `poke` / `invite` | 戳一戳、邀请入群；poke 支持 `channel_id`（群号或 `private:QQ号`） |
| 表态 | `reaction_summary` / `reaction_clear` | 消息的回应计数与自己是否回应；仅清除自己的回应 |
| 群成员 | `special_title` / `card` | 设置群头衔或群名片；`special_title` 只设头衔，不带显示开关参数 |
| 群显示 | `title_display` / `honor_display` | 群头衔、群荣誉的显示开关；不带 `show`/`enable` 时只读当前状态，不写 |
| 群消息 | `sign` / `essence` | 群打卡；设置或取消精华消息 |
| 特殊消息 | `dice` / `rps` | 发送 QQ 原生骰子或猜拳（超级表情） |
| 消息读取 | `get_forward` | 读取合并转发。`id` 是转发卡片里的 resId，或 `native:<父消息 ID>`。resId 走伪造节点协议，NT 客户端发的图片会整段丢失；`native:` 走 QQ 内核，图片、逐条消息 ID 与时间都在，优先使用。父消息不在模块缓存里时（模块重启或消息较旧）附带 `channel_id` 即可读取 |
| 能力查询 | `capabilities` / `help` / `compat` | 扩展动作与参数清单；内核接口面静态自检与运行时调用观测，QQ 升级后先跑它，`force=true` 强制重算 |
| 状态查询 | `status` / `version` | 健康状态或版本 |
| 维护 | `restart` / `clean_cache` | 退出 QQ 进程、清理临时文件 |

### 扩展动作注册表（0.29.0 起）

上表是长期维护、逐条手写文档的核心动作；`ExtraSvc` 另有一份代码里登记的注册表，`capabilities`
的目录、参数说明、读写分类都从它生成，这里只按域列出方法名，完整参数以 `capabilities`/`help`
的返回为准：

| 域 | 方法（部分带别名，见 `capabilities`） |
| --- | --- |
| 消息 | `typing`、`mark_read`、`mark_read_seq`、`mark_all_read`、`message_abstract`、`click_inline_keyboard`、`reedit_recall`、`image_ocr`、`ocr_data`、`ocr_by_aio` |
| 群管理 | `group_shut_up_list`、`group_join_link`、`group_essence_list`、`group_essence_latest`、`group_essence_cached`、`group_remark`、`group_quit`、`group_join`、`group_join_info`、`group_join_noverify`、`group_bulletin_publish`、`group_bulletin_delete`、`group_bulletin_upload_pic`、`group_bulletin_get`、`group_ext_list`、`group_member_level`、`group_identity_list`、`group_member_card`、`group_profile_card` |
| 群文件 | `group_file_count`、`group_file_folder_create`、`group_file_folder_delete`、`group_file_folder_rename`、`group_file_delete`、`group_file_rename`、`group_file_move`、`group_file_trans` |
| 头衔/身份原语 | `identity_title_info`、`identity_level_info`——`setIdentityTitleInfo`/`setGroupIdentityLevelInfo` 的单次调用，不做读回校验；日常切换显示开关用上表的 `title_display`，它在这两条之外还加了本地 DB 补写与读回确认 |
| 资料与好友 | `user_detail`、`user_detail_by_uin`、`user_simple_info`、`long_nick`、`friend_remark_get`、`friend_remark_set`、`friend_category_add`、`friend_category_delete`、`friend_category_rename`、`friend_category_set`、`friend_category_set_batch`、`friend_add` |
| 其它 | `online_file_list`、`online_file_refuse`、`temp_chat_info` |

`group_remark`、`group_shut_up_list`、`user_detail`、`mark_read` 是 0.17.0 撤下后以扩展动作的
形式回归。这批动作均走反射内核服务这一条 JNI 通道，不发新的原始封包；批量查询与管理类的具体
参数结构体字段名随 QQ 版本变化的风险比核心动作更高，调用前建议先用 `capabilities` 核对，是否
接进日常调用按「客户端真的会调」取舍（见 [`JNI_CAPABILITIES.md`](JNI_CAPABILITIES.md) 的
「搭话场景的取舍」一节）。

完整参数用 `capabilities` 或 `help` 查询，返回里的 `params` 字段逐条列出参数。扩展动作返回的内核原始
数据由反射导出，QQ 增删字段时跟着变而不是静默丢字段。

## 内核可用性

0.16.0 之前在这里记过一批「内核入口接受调用但不回调」的条目（群详情、群统计、成员等级、收藏表情、
群文件搜索、在线设备、地区枚举…）。那些入口对应的动作已在 0.17.0 整批撤掉，不再有可用性问题。

`reaction_summary` 通过 JNI 消息服务读取本地消息快照，不批量查询用户；返回的计数可能晚于刚完成的动作。

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

QQ 客户端手动发出的消息以 `qq-client:{selfUin}` 作为虚拟作者，并在 `satori_qq.manual_self` 中携带实际
身份；机器人 API 的发送回声会去重。登录期间的群列表同步不生成群变更事件。

每个事件的顶层都带 `sn`、`type`、`timestamp`、`login`，以及 `self_id` 与 `platform`（`Event` 类型里
与 `login` 并列的扁平字段。只有 `login` 时客户端也能工作，但按协议类型实现的一方读的是扁平字段）。
`login.get` 与 `READY` 里的 login 带 `sn`、`adapter`、`platform`、`self_id`、`hidden`、`status`、
`user` 与 `features`。

表态事件用 `reaction-added` / `reaction-removed`，与 `@satorijs/core` 声明的 `Events` 一致；
`@satorijs/protocol` 的 `EventName` 写的是 `reaction-deleted`。模块跟随核心事件表，不两头发。

## 消息元素与媒体

| 方向 | 元素 |
| --- | --- |
| 发送 | `text` `at` `sharp` `quote` `emoji` `a` `br` `p` `img` `audio` `video` `file`、修饰元素与 `<message>`；兼容 `face` `json` `mface` `poke` |
| 接收 | `text` `at` `quote` `emoji` `face` `img` `audio` `video` `file` `json` `mface`；合并转发为 `<message forward id="resid"/>` |

### 入站元素类型

QQ 内核的 `elementType` 与本端收到的元素一一对应（2026-09-16 在 9.3.60.40970 上逐个核过）：

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

图片的 `picSubType` 带在 `img` 的 `sub-type` 上（0.27.1 起）：1 是收藏/自定义表情，群里斗图多半
是这种；普通图片不带这个属性。`summary`（QQ 给的「[动画表情]」这类会话列表摘要）同理。发送时
`<img sub-type="1">` 会按表情的样子发出（小图、无相框，会话列表显示「[动画表情]」），客户端把
收到的表情再发一遍就还是表情，而不是一张大图。

14 与 17 是 QQ 官方机器人与 AI 助手发的「Markdown 卡片 + 按钮」。正文及按钮标签现在作为
`text` 元素进入事件流；按钮只供阅读，本端没有点击 QQ 官方机器人的回调能力。
字段结构依据 QQ NT `MsgElement` 类型；这一路径的单元测试已通过，仍待收到真实的官方机器人卡片做现场复核。

### 卡片载荷

ark 卡整段载荷原样放在 `json` 元素的 `data` 属性里，`raw_message` 里则是 `[CQ:json,data=…]`。客户端
读它要注意两点：

- 载荷里的斜杠是转义的（`"qqdocurl":"https:\/\/b23.tv\/xxx"`），在字符串上找不到 `https://`，要按
  JSON 解析
- 「点开这张卡会去哪」写在固定字段里，按可信度取：`meta.detail_1.qqdocurl`（小程序真正打开的那个
  页面）→ `meta.*.jumpUrl`（分享卡的落地地址）→ `meta.*.url`（多是小程序自己的路由
  `m.q.qq.com/a/s/<hash>`）。`icon` / `preview` / `tagIcon` 是图，不是落地地址

本端不改写载荷，也不替客户端挑地址：Satori 没有卡片元素，裁剪一次就回不去，而字段优先级由客户端按用途
定（同机的 acumen 在 `command::card_target_url` 里按此序取）。

媒体 `src` 接受 `http(s):`、`data:`、`file:`、本地路径、`upload.create` 返回的 `internal:`，以及本端的
`/v1/assets/{id}`。入站图片优先返回无需 Bearer 令牌的本地资源地址，头像使用 QQ 头像 CDN。

`<audio src>` 先转成 QQ 的 SILK（mp3、wav、amr 都走这条路），整条带重试、最多三次：`MediaCodec`
的解码器在 QQ 进程里会被系统回收，`queueInputBuffer` 抛一个空消息的 `CodecException`，换个解码器重来
即可。重试按时间收口——上次耗时乘二超过 20 秒就不试（实测 59 秒的语音转一次，前台约 8 秒、后台约
34 秒，多试两次客户端就 HTTP 超时）。`node tests/media-live-probe.js voicerepeat` 能量这件事。
`<video>` 需本地取到视频帧做缩略图；`<file>` 既发聊天气泡，也进群文件列表。

### 顺媒体必须单独成条（2026-09-17）

`audio` / `video` / `file` 在 QQ 里是「顺媒体」：**一条消息带了其中一种，就只能有它自己**——再挂
`quote`、`at`、`text`、`img`，客户端渲染不出同条内容（顺媒体显示成空，引用与文字一起乱）。实测的现场
是「引用 + 视频」在群里只剩一个空气泡。

本端把这种拼法拆成几条发出去（`Batching.splitChunkMedia`，对所有批次生效）：按原顺序拆开，其余段落
合成一条先发，每个顺媒体各成一条，只剩 `quote` 的空壳丢掉。顺序与内容不丢，客户端照常写它想写的那条
消息即可；拆开后一次 `message.create` 会真的发出多条，回执数组里就有多个消息 ID。

图片不在其列：`img` 可与引用、文字同条（`<quote/><img/>` 是常见形状）。

`message.create` 同步等 QQ 内核回调：语音约 1 秒，文件要等上传，慢时会占满 20 秒的等待窗口（超时后按
「无回调」放行，消息通常已经发出）。客户端超时不要低于 30 秒。

## 客户端注意事项

- Koishi 的 `server.selfUrl` 须与 `server.port` 一致，QQ 进程才读得到资源地址
- `manual_self_messages=true` 时投递 QQ 客户端手动发送的消息，`manual_self_user_id` 可覆盖其虚拟作者 ID
- 私聊自己产生的消息不投递

## 与官方协议的对照

2026-09-19 逐条核对过。对照物：`@satorijs/protocol@1.7.0`（协议类型定义就是规范本体）、
`@satorijs/core@4.6.0`（客户端框架）、`@satorijs/adapter-satori@1.5.1`（官方客户端）与
`@satorijs/server` 里那份 satori 服务端实现。

对齐的（对着实现核过，不是照文档猜的）：

| 面 | 结论 |
| --- | --- |
| 传输 | `GET /v1/events` 升级 WebSocket；动作 `POST /v1/<method>`；扩展 `POST /v1/internal/<name>`（官方服务端同样只暴露 `/v1/internal/*`）。`GET` 打到动作上回 405，文案与官方服务端同义 |
| 网关 | `IDENTIFY{token,sn}` / `READY{logins,proxy_urls}` / `PING`→`PONG` / `EVENT`，opcode 与协议一致；`sn` 递增，重连时带 `sn` 会补发错过的动作回执 |
| 认证 | HTTP 用 `Authorization: Bearer`，WS 用 IDENTIFY 里的 `token`；`Satori-User-ID` / `Satori-Platform` 必须指向本实现端的登录 |
| 对象 | `Login{sn,adapter,platform,status,features,user}`、`Guild`、`Channel`、`GuildMember`、`Message`、`List{data,next?}`、`Meta{logins,proxy_urls}` |
| 方法 | 官方 37 个里实现 31 个，`features` 只列实现得了的（客户端据此判断能力） |
| 上传 | `upload.create` 是 multipart，回 `{<字段名>: <引用>}`，引用形如 `internal:<platform>/<selfId>/_tmp/<id>`，客户端按该路径取回——与官方服务端一致 |
| 事件名 | `message-created`（官方客户端显式认这个，`message` 只是框架里的别名）、`guild-member-added/updated/removed`、`guild-request`、`guild-member-request`、`friend-request` |
| 非标准事件 | 纯自定义走 `type=internal` + `_type`/`_data`；标准事件加细节走 `type=guild-member-updated` + `_type=satori-qq/mute`。前者被框架的 `dispatch` 直接派发成 `_type` 事件，后者被 `setInternal` 记成内部数据——两种约定都按框架的实现走 |

刻意不同的（连同理由）：

- **缺 6 个方法**：`channel.create`、`channel.delete`、`guild.role.create|update|delete`、
  `message.update`。QQ 的群就是频道、没有自定义角色、也不支持改消息，实现只能是假的；回 404，能力表里
  也不列
- **`channel.update` 的改名只有一条写入路径**：照 NapCat 只调内核的 `modifyGroupName`，**不做二次
  写入、也不回读校验**；空名字一律拒绝。理由与实现见
  [`ARCHITECTURE.md`](ARCHITECTURE.md#群资料写入)
- **`reaction-removed` 而不是 `reaction-deleted`**：协议包写的是 `reaction-deleted`，但框架给插件的
  事件表（`@satorijs/core` 的 `Events`）与官方 QQ 适配器用 `reaction-added`/`reaction-removed`，两套
  名字在框架里**不是别名**，按「插件实际会监听哪个」选了后者。对接严格照协议包写的客户端时应在客户端兼容 `reaction-deleted` 别名，不同时广播两份相同事件
- **`login-updated`**：不在协议包的 `EventName` 里，但官方客户端的 WS 分支显式处理它（还有
  `login-added`/`login-removed`），换号时靠它通知客户端重连
- **列表分页**：不带 `next`/`limit` 一次给完；指定分页后按 `next` 继续读取
- **`channel.get` 多回一个 `avatar`**：协议里 `Channel` 没有这个字段，客户端会原样忽略
- **错误码**：令牌不对回 401（官方回 403）、未知 `Satori-User-ID` 回 404（官方回 403）；客户端两种都
  当失败处理，没有实际差别


## 0.28.0 与 Acumen 的协作约定

以 [Satori 事件规范](https://satori.chat/zh-CN/protocol/events.html)、
[表态规范](https://satori.chat/zh-CN/resources/reaction.html) 和
[扩展规范](https://satori.chat/zh-CN/advanced/internal.html) 为准：

- READY、历史回放和实时事件在同一把投递锁内串行完成。所有广播事件在投递时统一分配 sn；
  多条 JNI 回调不会令客户端先看到较大的 sn 再看到较小的 sn。重复 IDENTIFY 不重新回放。
- 恢复连接不重复投递待审批申请；登录事件不进历史缓冲。换号清除回放与表态缓存。
- READY 附加 `satori_qq.session_id` 和 `satori_qq.sn`，供 Acumen 识别模块进程重启。
  sn 从当前毫秒时间起算；缓冲仍为当前进程最近 4096 条，重启不恢复丢失的历史。
- `reaction.clear` 从 features 移除并返回 404。原来“只清自己”的非标准行为迁移到
  `POST /v1/internal/reaction_clear`，参数 `channel_id, message_id, emoji_id?`；返回
  `{cleared, scope:"self"}`。无自己表态时返回 0；部分失败明确报错，不报完全成功。
- `POST /v1/internal/reaction_summary` 参数 `channel_id, message_id`；返回
  `{message_id, data:[{emoji_id,count,self}], source:"kernel_cache", observed_at}`。
  count 来自 QQ 本地缓存；self 优先使用本模块已确认的动作。两者更新时间可能不同。
- 表态事件的 `_type=satori-qq/reaction`、`_data={before,count,delta}` 解释数量变化；没有
  可靠操作者时不填写 user，不能把消息作者认作回应者。
- QQ 专有元素输出 `satori-qq:json`、`satori-qq:mface`、`satori-qq:poke`，输入仍接受旧的裸名称。
  骰子、猜拳继续使用标准 `<emoji>`。HTTP poke 是头像戳一戳，消息里的 poke 是表情元素。
- 排队中的写入及媒体转换后的发送会复核登录账号；旧账号请求不能自动改为新账号执行。

功能未增加 ART hook、Java hook 框架或第三方 native 依赖。真实 QQ 权限、回应缓存时延、
服务端频率限制仍以回执为准；超时是结果未知，客户端不能盲目重发动作。
