# 0.29.9 — 适配 QQ 9.3.70

QQ 客户端 9.3.65（16240）→ 9.3.70（16410）。模块实现代码零改动，本轮只做核验与口径更新：

- 反射接口面：`internal/compat` 静态自检 207/207 全过、无缺失；对 9.3.70 的 dex 复核
  `IKernelGroupService` 与 `IQQNTWrapperSession`，签名与 9.3.65 记录一致。会话入口从 54 个变
  55 个（多一个非服务的 `getAccountPath`），51 个取服务入口不变。
- 真机巡检：`tests/ws-feature-sweep` 58/58 通过（消息收发、表态、戳一戳、骰子、撤回、已读等）。
- 巡检脚本修正：`group_remark` / `group_shut_up_list` / `user_detail` / `mark_read` 已在 0.29.0
  以扩展动作回归，从「已移除应 404」名单里去掉，另补 `user_detail_by_uin` 与 `mark_read`
  两个正向用例。
- 真机核验记录写入 `docs/JNI_CAPABILITIES.md`：`group_shut_up_list`、`group_ext_list`、
  `group_essence_list` 三个读动作在 9.3.70 上内核不回调（静态接入后从未真机通过，非本次更新
  所致）；`group_essence_latest` 回 `code=2 system error!`。文档版本口径统一改到 9.3.70。

验证：31 组 JVM 测试、qqguard 测试、真机全量巡检 58/58 通过。本轮未改 native 层，装机只需
覆盖安装 APK + 重启 QQ 进程，不必重启手机。
