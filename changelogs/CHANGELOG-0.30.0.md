# 0.30.0 — 状态码、代理路由与事件形状对齐 Satori 协议

拿 Satori 官方文档和官方服务端的实现逐条对照后，把和它们不一致的几处改齐。对着同机的
satori-wx 与 acumen 一起核过：三者现在用同一套协议约定协作。

- **鉴权状态码**：缺失令牌 401、令牌不对 403（原先都是 401）；WebSocket 的 IDENTIFY 令牌不对
  用 4004 关闭（原先不带关闭码）。错误体统一是 JSON。
- **未知的登录**：`Satori-User-ID` / `Satori-Platform` 指向别的账号回 403（官方服务端的
  `login not found`），不再是 404。客户端不会再把「登录不对」当成「方法不存在」。
- **`/v1/proxy/{url}`** 照 resource.md 的顺序：不是合法 URL 回 400、`internal:` 格式不对回 400、
  登录或资源不存在 404、合法但不在 `proxy_urls` 的外链 403（原先 `not a url` 是 403，
  `internal:bad` 是 404）。
- **事件遵守资源提升**：推送的事件里，`message` 不再重复 `channel`、`guild`、`user`、
  `member`，`member` 里也没有 `user`；这些资源只在事件顶层。`message.get` / `message.list`
  返回的 `Message` 仍是嵌套形态（必需资源在它里面）。**这是线上事件形状的变化**：读
  `event.message.user` 的客户端要改读 `event.user`（acumen 一直读顶层，不受影响）。
- **验收探针** `tests/conformance.py`：只读黑盒，不发任何聊天消息；对着在跑的实现端核对
  状态码表、`features` 与 404、分页信封、`message.list` 的方向与令牌、`upload.create` 到
  代理路由的往返、WebSocket 的 PING 与旧 `sn` 恢复、驼峰键，`--listen` 时核对实时事件形状。
  satori-wx 带同一份，两个实现用同一把尺子量。
- `HubHttpTest` 钉住状态码表、代理语义、关闭码与事件形状；`ElementsTest` 钉住资源提升。

验证：32 组 JVM 测试、qqguard 测试通过。改动都在 Java 层，但代码内嵌在 Zygisk 的 `.so`
里，开机时钉住：装机要覆盖模块文件后**重启手机**才生效（APK 覆盖安装即可，不必重启 QQ 之外的东西）。
