# 0.37.0 — 群公告能直接发了

### 变化

- **发群公告不再需要调用方自备 p_skey。** `internal/group_bulletin_publish` 现在自己向 QQ 内核取 `qun.qq.com` 的 p_skey（`IKernelTipOffService.getPskey`），参数只要 `guild_id`、`text`，可选 `pinned`（置顶）与 `image`（配图：本地路径、`file:`、`data:`、`http(s):` 或 `internal:`）。
- 公告正文按内核要求先做 URL 编码再提交，中文与换行原样显示。
- 配图在发布时一并上传（原先单独的 `group_bulletin_upload_pic` 并入发布，不再单列）。
- `group_bulletin_delete` 同样自取 p_skey。

账号须是群主或管理员。知微（acumen）1.3.0 起，群管理开放时智能体可以直接发公告。

### 升级

刷入新模块并重启手机生效。
