# 0.32.1 — 拆开 SatoriHub，构建升到 Java 17

没有功能变化。`core/SatoriHub` 长到 4898 行，HTTP 路由、方法分派、事件广播、内核回调、发消息、合并转发、
历史、表情回应、群管、状态循环全在一个类里，改哪一处都要翻整个文件。这一版按职责拆开。

- **十八个部件，`SatoriHub` 只剩接线**（85 行）：`HttpRoutes` / `Dispatcher` 管 HTTP 与方法名，
  `Events` / `Inbound` / `Requests` 管事件，`Messages` / `Sender` / `Forwards` / `History` / `Reactions` /
  `GroupOps` / `Directory` / `Resources` 实现各类 API，`OutboundGate` 是所有写动作的限频与熔断，
  `Monitor` 管状态循环与诊断。职责表见 `docs/ARCHITECTURE.md`。
- **错误码改静态工厂**：`ApiError.badRequest(…)`、`notFound(…)`、`failed(…)` 取代满处的 `new ApiError(1400, …)`；
  「没有这个方法」与「曾经有、现已移除」并进 `ApiError`，不再各占一个异常类。
- **换号时的缓存清理收口**：每个部件一个 `reset()`，由 `Events.accountChanged` 在同一把锁里统一调用
  （历史文本快照现在也一并清掉）。
- **删掉的死代码**：`message.list` 内部返回里没人读的 `_hist` / `_keys` / `_raw0` 等调试字段、
  `applyTextMap` 里恒为空操作的分支、`localReactionUsers`，以及永远匹配不上的带连字符的内部动作别名
  （名字在匹配前已把 `-` 归一成 `_`）。
- **构建**：`javac --release 17`，用上 record、switch 表达式与 `instanceof` 模式匹配；内联的全限定名收成
  import 并按 ASCII 排序。`.editorconfig` 与 `native/satori.cpp` 的 `.clang-format` 和 satori-wx 同一份。

验证：33 项 JVM 测试与 qqguard 状态机测试通过（两个 Hub 测试改为直接调用部件）。改动在 Java 层，
代码内嵌在 Zygisk 的 `.so` 里、开机时钉住：装机要覆盖模块文件后**重启手机**才生效。
