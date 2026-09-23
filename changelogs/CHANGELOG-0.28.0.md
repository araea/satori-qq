# 0.28.0 · Acumen 协作与 QQ 互动

- 修复 READY/历史回放/实时事件竞态；统一 sn 分配，避免并发事件乱序及重连重复审批事件。
- READY 给出进程会话标识；换号清理旧回放、自己的回应缓存，出站队列复核账号。
- 新增 `internal/reaction_summary`：查询回应数量和自己是否回应；表态事件附带数量变化。
- 新增 `internal/reaction_clear`：明确只撤销自己的回应，缓存滞后也不恢复已取消的回应。
- `internal/poke` 支持群聊及私聊 `channel_id`，拒绝冲突目标。
- QQ 扩展消息元素使用 `satori-qq:` 前缀，继续接受旧输入。

## 迁移

标准 `reaction.clear` 的含义是清所有用户的回应，QQ 无对应能力，因此不再声明支持并返回 404。
需要清自己的回应请改用 `internal/reaction_clear`；单个回应继续用标准 `reaction.delete`。
`reaction.list` 按协议要求提供 `emoji_id`。Acumen 已同步适配上述变化。

安装新 APK 和模块后重新启动 QQ；Acumen 重新构建并重启。协作范围、逐项修复与限制见配套仓库的
`docs/SATORI_INTEGRATION_AUDIT.md`。
