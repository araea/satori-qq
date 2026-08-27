# OneBot 11 支持矩阵（QQ 9.3.50）

> `✅` 已由主号真机验证；`⚠️` 本地实现正确但受外部限制；`—` 明确不做或尚未排期。

## 动作

| 类别 | 动作 | 状态 | 备注 |
|---|---|---:|---|
| 元信息 | `get_login_info`, `get_status`, `get_version_info` | ✅ | online 来自 LoginActivity + NT session + listener |
| 能力 | `can_send_image`, `can_send_record`, `clean_cache`, `set_restart` | ✅ | restart 由 root watchdog 拉起 QQ |
| 消息 | `send_msg`, `send_group_msg`, `send_private_msg` | ✅ | 群/好友真机发送 |
| 消息 | `delete_msg`, `get_msg`, `get_group_msg_history` | ✅ | 历史最多 100 条 |
| 资源 | `get_image`, `get_file` | ✅ | 历史 file_id→QQNT 内核下载→真实本地路径已验证 |
| 资源 | `get_record` | 🧪 | 同一内核下载实现已部署，当前历史无语音样本 |
| 转发 | `send_*_forward_msg`, `get_forward_msg` | ✅ | v1 伪造节点内容以文本为主 |
| 资料 | `get_friend_list`, `get_stranger_info`, `send_like` | ✅/⚠️ | 好友 153 人实测；点赞被服务器 appid 319 拒绝 |
| 群查询 | `get_group_list`, `get_group_info`, `get_group_member_info/list` | ✅ | Buddy/Profile/GroupService 缓存与回调 |
| 群管理 | `set_group_kick/ban/whole_ban/card/admin/name/leave` | ✅ | 群名用原值写回验证 |
| 群管理 | `set_group_special_title`, `set_msg_emoji_like` | ✅ | 0x8FC_2 与内核 emoji API |
| 文件 | `upload_group_file`, `upload_private_file` | ✅ | FileElement rich-media auto-upload |
| notice/request | 撤回、戳一戳、进退群、加好友请求事件 | 📋 | 已重新纳入双主线后续计划 |
| 凭据 | cookies/csrf/credentials | — | 不暴露主号敏感凭据；原生 QQ backend 也不需要 |

## 消息段

| 方向 | 已支持段 |
|---|---|
| 发送 | `text`, `at`, `face`, `reply`, `image`, `record`, `video`, `file`, `json/lightapp`, `mface`, `poke` |
| 接收 | `text`, `at`, `face`, `reply`, `image`, `record`, `video`, `file`, `json`, `mface` |

`record` 输入若不是 SILK/AMR，会由 Android MediaExtractor/MediaCodec 解码、重采样为 8kHz mono，
再用系统 AMR-NB encoder 编码。MP3→AMR→测试群发送→撤回已真机通过。

## 明确边界

- `send_like` 的 319 是腾讯 appid 规则，不是本地封包错误。
- notice/request 按可确认的内核事件源逐步实现，并与 ayjx/其它 OneBot 客户端联调。
- QQ 凭据类 API 不提供伪数据，也不导出主号 cookies。
- 新 QQ 版本必须重新核对 JNI 字段和签名，不能直接相信桌面 QQ/NapCat 类型。
