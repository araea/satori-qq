## 0.8.9.32

- 新增一组 QQ 内核扩展动作，全部在真机 QQ 9.3.55 上实测可用
  - 个人资料：`internal/profile_set_signature`、`profile_set_nickname`、`profile_set_avatar` 分别修改个性签名、昵称与头像；`internal/profile_self` 读回自己资料、个性签名与在线状态
  - 群设置：`internal/group_msg_mask` 设置群消息提醒方式（`notify` / `assistant` / `shield` / `receive`）；`internal/group_honor` 查询群荣誉；`internal/group_shut_up_list` 查询群内被禁言成员
  - 好友：`internal/friend_remark` 查询或设置好友备注；`friend_top` 置顶会话；`friend_msg_notify` 开关单个好友的消息提醒；`friend_block` 拉黑或取消拉黑；`friend_relation` 查询好友与拉黑状态及备注；`friend_add` 发送好友申请
  - 联系人：`internal/recent_contacts` 查询最近联系人、未读数与最后一条消息
- 修复内核服务调用不返回的问题：QQ 的内核服务是主线程亲和的，从 HTTP 工作线程直接调用会立即返回，但回调永不触发，通话内所有依赖 `IOperateCallback` 的动作（含此前的 `channel.mute`、`guild.member.kick`、`guild.member.mute`、`internal/card`）都会 15 秒超时。新增 `qq/ExtraSvc` 统一把调用投递到主 Looper，再由工作线程等待回调，扩展动作与本模块既有的群管理写操作随之恢复
- 内核接口按真机回包修正：`getGroupShutUpMemberList` 在 QQ 9.3.55 上不回调，改用 `queryGroupMuteMemberList`；`getRecentContactListSync` 回 `msg service is nullptr`，改用 `getRecentContactInfos`
- 唤醒锁默认开启：新增配置 `wake_lock_auto`（默认 `true`），模块启动即自动获取 CPU 与 Wi-Fi 锁，不再需要点按通知按钮。锁在取消通知或关闭保活时也照常获取，通知按钮仍可随时释放
- `internal/capabilities` 同步列出新动作，并新增 `group_msg_masks` 取值清单
- 版本号 0.8.9.32（versionCode 67），沿用同一签名密钥，支持从 0.8.9.31 直接覆盖升级
