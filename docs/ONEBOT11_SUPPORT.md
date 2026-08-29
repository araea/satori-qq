# OneBot 11（QQ 9.3.55）

协议面已按 [ayjx](https://github.com/araea/ayjx) 冻结：所用动作和段均已覆盖，默认不再加新功能。

`ok` 主号真机通过；`limited` 本地正确但被服务器拦；`partial` 动作通、live 事件缺样本；`-` 明确不做。

## 动作

| 类别 | 动作 | 状态 | 备注 |
| --- | --- | --- | --- |
| 元信息 | `get_login_info` `get_status` `get_version_info` | ok | `online` 看登录页 + NT session + listener |
| 能力 | `can_send_image` `can_send_record` `clean_cache` `set_restart` | ok | restart 由 watchdog 拉起 QQ |
| 消息 | `send_msg` `send_group_msg` `send_private_msg` | ok | 私聊自己不投递，测真实群/好友 |
| 消息 | `delete_msg` `get_msg` `get_group_msg_history` `get_friend_msg_history` | ok | 历史最多 100 条；内核失败 1500 |
| 资源 | `get_image` `get_record` `get_file` | ok | 本地 → `downloadRichMedia` → 视频 URL 兜底 |
| 转发 | `send_*_forward_msg` `get_forward_msg` | ok | ayjx 用 `send_msg` + `node`，不是 `send_forward_msg` |
| 资料 | `get_friend_list` `get_stranger_info` | ok | |
| 资料 | `send_like` | limited | 服务器 oidb 319 / appid，非本地封包错误 |
| 群 | `get_group_list` `get_group_info` `get_group_member_info` `get_group_member_list` | ok | |
| 群管理 | `set_group_kick` `set_group_ban` `set_group_whole_ban` `set_group_card` `set_group_admin` `set_group_name` `set_group_leave` `invite_group` | ok | |
| 群管理 | `set_group_special_title` `set_msg_emoji_like` | ok | 0x8FC_2；内核 emoji |
| 互动 | `send_poke` | ok | 0xED3_1 |
| 文件 | `upload_group_file` `upload_private_file` | ok | 返回真实 `file_id` |
| 群文件 | `get_group_file_system_info` `get_group_root_files` `get_group_files_by_folder` `get_group_file_url` | ok | 0x6D8 / 0x6D6_2 |
| 群文件 | `create_group_file_folder` `rename_group_folder` `delete_group_folder` | ok | 0x6D7 |
| 群文件 | `delete_group_file` `rename_group_file` `move_group_file` | ok | 0x6D6_3/4/5 |
| notice | `group_recall` `friend_recall` `notify.poke` `group_ban` `group_increase` `group_decrease` | ok | ayjx 不消费 notice |
| request | `set_friend_add_request` `set_group_add_request` | partial | 空 flag 真机 1400/1404；live 事件缺申请人样本 |
| 凭据 | cookies / csrf / credentials | - | 不导出 |

## 消息段

| 方向 | 段 |
| --- | --- |
| 发送 | `text` `at` `face` `reply` `image` `record` `video` `file` `json` `mface` `poke` `node` |
| 接收 | `text` `at` `face` `reply` `image` `record` `video` `file` `json` `mface` `forward` |

`send_msg` 里若整段是 `node`，改走合并转发。`record` 非 SILK 时经 MediaCodec → QQ `SilkCodecWrapper`；媒体必须落在 QQ cache。

## ayjx

只连正向 WS，动作名用 `send_msg`。会调：`get_login_info`、`send_msg`、`get_msg`、`delete_msg`、`get_group_list`、`get_group_member_info`、`set_group_special_title`、`set_msg_emoji_like`、`upload_group_file`、`upload_private_file`。只消费 `message` 事件。

未排期（不挡 bot）：request live 事件、`get_record.out_format`、历史游标。
