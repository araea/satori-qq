## 0.8.9.17

- 合并转发支持策略配置，新增 `forward_mode`：`auto`（原生优先，`fake` 兜底）/ `native`（自聊脚手架）/ `fake`（SsoSendLongMsg 卡片）。QQNT 9.3.55 实测只有原生卡片能正常点开。
- 原生合并转发卡片发送后返回真实 QQ `msgId`，可被 `message.delete` 撤回；卡片自动入本地存档，可按 QQ `msgId` 回查与快照。
- 修正 CQ 码解析：整段 `[CQ:json]` / `[CQ:lightapp]` 卡片不再因载荷含逗号或括号而截断，`reply`/`forward` 等段类型补齐。
- CQ 码参数仅在 `key=` 边界分割，值内含逗号不再误切。
- 发送合并转发时按「目的会话已有消息即早于卡片」的基线复用 `message.list` 查询，保证新发卡片可靠可见、可管理。
