# OneBot 11 支持矩阵（QQ 9.3.55）

> `✅` 已由主号真机验证；`⚠️` 本地实现正确但受外部限制；`—` 明确不做或尚未排期。

## 动作

| 类别 | 动作 | 状态 | 备注 |
|---|---|---:|---|
| 元信息 | `get_login_info`, `get_status`, `get_version_info` | ✅ | online 来自 LoginActivity + NT session + listener |
| 能力 | `can_send_image`, `can_send_record`, `clean_cache`, `set_restart` | ✅ | restart 由 root watchdog 拉起 QQ |
| 消息 | `send_msg`, `send_group_msg`, `send_private_msg` | ✅ | 群/好友真机发送 |
| 消息 | `delete_msg`, `get_msg`, `get_group_msg_history` | ✅ | 历史最多 100 条；超时/内核错误现返回 1500，不再伪装成空数组 |
| 消息 | `get_friend_msg_history` | ✅ | 与群历史同一内核路径；真机返回 `messages`（count=3） |

| 资源 | `get_image`, `get_file` | ✅ | 群历史 file、`280183116` 1s H264 视频、私聊 `upload_private_file` 真实 `file_id` 均 `get_file` 本地路径 retcode 0。旧自然视频样本服务器 `170019015 File is Invalid`，不能当失败证据 |
| 资源 | `get_record` | ✅ | 收发均已真机：WAV→QQ `SilkCodecWrapper`→`\x02#!SILK_V3`，同 `message_id` 历史回查为真实 `record`，QQ 原生播放器气泡正常渲染，`get_record` 下载 1542 字节 |
| 转发 | `send_*_forward_msg`, `get_forward_msg`；`send_msg` 的 `node` 段 | ✅ | 节点 text/at/face/reply/image/file 真机往返。ayjx 的 ping/gif/image_split/ai_news 用 `send_msg`+`node`：`280183116` 两节点文本 `native_forward` + `get_forward_msg` 已通过。QQ 点开走内核 `multiForwardMsg` |
| 资料 | `get_friend_list`, `get_stranger_info`, `send_like` | ✅/⚠️ | 好友 153 人实测；点赞被服务器 appid 319 拒绝 |
| 群查询 | `get_group_list`, `get_group_info`, `get_group_member_info/list` | ✅ | Buddy/Profile/GroupService 缓存与回调 |
| 群管理 | `set_group_kick/ban/whole_ban/card/admin/name/leave` | ✅ | 现等待内核 callback。`280183116` 踢人+拉回已验证 |
| 动作 | `invite_group` | ✅ | 内核 `inviteToGroup`；踢人后拉回真机 retcode 0 且产生 `group_increase` |
| 群管理 | `set_group_special_title`, `set_msg_emoji_like` | ✅ | 0x8FC_2 与内核 emoji API |
| 动作 | `send_poke` | ✅ | 0xED3_1；群/好友。`280183116` 真机产生 `notify.poke` |
| 文件 | `upload_group_file` | ✅ | 聊天通道上传后轮询群文件系统，返回真实 `file_id`/`busid`（兼 `message_id`）；`280183116` 上传→改名→移动→删除已清理 |
| 文件 | `upload_private_file` | ✅ | 聊天通道上传后回查消息，返回真实 `file_id`（兼 `message_id`）；测试群好友私聊 `get_file` 本地路径已验证 |
| 群文件 | `get_group_file_system_info`, `get_group_root_files`, `get_group_files_by_folder`, `get_group_file_url` | ✅ | 0x6D8/0x6D6；空根、现有文件 URL、自然子目录均真机通过 |
| 群文件 | `create_group_file_folder`, `rename_group_folder`, `delete_group_folder` | ✅ | 0x6D7；`280183116` 自建自改自删，根目录无残留 |
| 群文件 | `delete_group_file`, `rename_group_file`, `move_group_file` | ✅ | 0x6D6_3/4/5；`280183116` 上传样本后改名/移动/删除并清理 |
| notice | `group_recall` | ✅ | `280183116` 自撤回：`delete_msg` 后收到 `notice_type=group_recall` |
| notice | `notify.poke` | ✅ | `send_poke`（0xED3_1）后 XML 灰字 subType=12（nudgeaction）解析为 poke。桌面 JSON busiId 1061 仍兼容 |
| notice | `group_ban` / `lift_ban` | ✅ | `280183116` 禁言 10 秒→解除；XML「禁言N秒」灰字 |
| notice | `group_increase` / `group_decrease` | ✅ | `280183116` 踢人→`kick`，`invite_group` 拉回→`invite`。走 `onMemberListChange` ADD/REMOVE，XML 灰字兜底。测完已拉回 |
| notice | `friend_recall` | ✅ | 私聊 `send_private_msg` → `delete_msg` 后收到 `notice_type=friend_recall` |
| request | 好友/加群请求事件 + `set_friend_add_request`/`set_group_add_request` | 🧪 | listener + 内核回查已接。空/未知 flag **真机** 1400/1404。**事件待申请人样本** |
| 凭据 | cookies/csrf/credentials | — | 不暴露主号敏感凭据；原生 QQ backend 也不需要 |

## 消息段

| 方向 | 已支持段 |
|---|---|
| 发送 | `text`, `at`, `face`, `reply`, `image`, `record`, `video`, `file`, `json/lightapp`, `mface`, `poke`, `node`（`send_msg` 里整段 node 会改走合并转发） |
| 接收 | `text`, `at`, `face`, `reply`, `image`, `record`, `video`, `file`, `json`, `mface`, `forward` |

`record` 输入若不是 SILK，会由 Android MediaExtractor/MediaCodec 解码、重采样为 8kHz mono，
再用 QQ 自带 `SilkCodecWrapper` 以 12kbps 编码为 Tencent SILK。测试群发送、历史回查、下载及
QQ 原生播放器气泡均已通过；媒体暂存必须走 QQ cache，不能使用会被进程内过滤掉的模块名路径。

## ayjx 实际协议面（2026-08-29 对照 `/storage/emulated/0/Dev/ayjx` 源码）

ayjx 是 **OneBot 11 正向 WS 客户端**，只发动作名 `send_msg`（不用 `send_group_msg`），Bearer 鉴权。

**插件会调用的动作（均已真机）：**
`get_login_info`，`send_msg`，`get_msg`，`delete_msg`，`get_group_list`，`get_group_member_info`，
`set_group_special_title`，`set_msg_emoji_like`，`upload_group_file` / `upload_private_file`。

**api.rs 有封装、插件未调用：** `get_forward_msg`，`send_like`（后者服务器 319，即使调用也会失败）。

**会发送的段：** `text` / `reply` / `at` / `image` / `video`；合并转发是 **`send_msg` 里一组 `node`**（ping、gif、image_split、ai_news），不是 `send_forward_msg`。
**会解析的接收段：** `text` / `at` / `face` / `image` / `record` / `video` / `reply` / `json` / `file` / `poke` / `forward`。
**事件：** 只认真消费 `message`。`meta_event` 被丢掉。notice / request **无插件依赖**（撤回走 `/撤回` 命令调 `delete_msg`）。

**结论：ayjx 用到的动作和段均已覆盖。** 未再排期的 OneBot 项（request live 事件、`get_record.out_format`、历史游标）不挡 ayjx。此后默认 **不再推进新协议功能**，主线只做反检测 / 防掉线 / 防设备异常；协议只修 ayjx 回归。

## 明确边界

- `send_like` 的 319 是腾讯 appid 规则，不是本地封包错误。
- `group_recall`、`notify.poke`、`group_ban`/`lift_ban`、`group_decrease`/`group_increase`、`friend_recall`、`get_record`、群/私聊 `get_file`（含视频）已真机通过。request 动作错误码已真机，live 事件仍待申请人样本。作者授权测试可踢人，测完邀请拉回。
- QQ 凭据类 API 不提供伪数据，也不导出主号 cookies。
- 新 QQ 版本必须重新核对 JNI 字段和签名，不能直接相信桌面 QQ/NapCat 类型。
