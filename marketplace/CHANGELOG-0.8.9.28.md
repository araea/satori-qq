## 0.8.9.28

让合并转发的内核读取不再依赖模块自己的消息缓存。

- `internal/get_forward` 的 `native:<父消息 ID>` 接受 `channel_id`：父消息不在模块内存缓存里（模块刚重启，或消息较旧）时用会话组出 contact 继续走内核，不再直接 404
- 内核路径返回逐条消息 ID、发送者、时间与图片资源；resId 那条旧协议会丢掉 NT 客户端发出的图片，现在只作为内核也查不到时的兜底
- `docs/SATORI_SUPPORT.md` 补上两种 `id` 的差别与 `channel_id` 用法
