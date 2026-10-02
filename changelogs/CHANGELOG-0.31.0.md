# 0.31.0 — 错误体统一、暂时不可用的标准表达、限额与能力声明

对着 acumen 的日志找摩擦：一天里十几次「QQ session stabilizing」让指令回复直接失败、
大视频被 413 拒掉而 acumen 事先并不知道上限。这一版把实现端该说清楚的事说清楚。

- **错误体统一**：每个非 2xx 响应都是 `{"code", "message"}`（之前有的是纯文本、有的只有
  `message`）。`code` 是机器可读的短名，缺省按状态码取。
- **暂时不可用用标准方式说**：内核离线、上线后的稳定期、出站队列满、熔断中回 **503**，超出
  每分钟发送额度回 **429**（之前都是 500）；能给出恢复时间的带 `Retry-After`。这些情况消息没有
  发出，客户端等够时间重发是安全的——acumen 已经这么做了。
- **`internal/capabilities` 与 satori-wx 共用口径**：新增 `adapter`、`platform`、
  `standard_methods`、`unsupported`、`event_types`、`message_elements`、`limits.upload_bytes`；
  `standard_methods` 与 `login.features` 出自同一个方法。acumen 连上后读它，按声明办事。
- **没有的方法永远是 404**：之前内核离线时，`channel.create` 这类本来就没有的方法也回 503，
  客户端分不清「没有这个」和「现在不行」。现在先判方法在不在，再判内核在不在线。
- **413 写明上限**：请求体超过 64 MiB 回 JSON 错误，`message` 里是单个文件的上限
  （63 MiB，扣掉 multipart 的封装余量）。
- **`/v1/proxy`、`/v1/assets`、`/v1/internal/.../_tmp` 支持 `HEAD`**：与 GET 同路由，只回头。
- 新增 `HttpServerTest`（真套接字：HEAD、503 + `Retry-After`、404 与 413 的 JSON 体），
  `HubHttpTest` 与 `OutboundGuardTest` 钉住错误码与能力声明。

验证：33 组 JVM 测试通过。改动在 Java 层，代码内嵌在 Zygisk 的 `.so` 里、开机时钉住：
装机要覆盖模块文件后**重启手机**才生效。
