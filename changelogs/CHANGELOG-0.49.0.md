## 0.49.0

### 消息检索与上下文

- 新增 `internal/message_search`：在本进程见过的最近四千条消息里按关键词找，可按频道、发送者、时间窗过滤，按时间倒序返回最多 50 条。参数 `keyword` 必填，`channel_id` / `guild_id`、`user_id`、`before` / `after`（unix 秒）、`limit` 可选。
- 新增 `internal/message_context`：以一条消息为中心，取同频道前后各最多 40 条，返回同一频道里的消息序列；引用旧消息、判断「他这句在回谁」时不用再只靠游标前后翻。
- 两条都是只读动作，不进出站队列、不占发送额度，已登记进 `internal/capabilities` 的 `actions`、`catalog` 与 `params`；它们从 0.17.0 的 `removed` 名单里移出，客户端（acumen 1.61 起）可以直接按目录调用。
- 检索文本优先取内核元素表里的纯文本，退回本地保存的 Satori 元素串；不返回资源直链，只给消息 ID、频道、发送者、时间与文本。
