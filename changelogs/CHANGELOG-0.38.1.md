# 0.38.1 — 清理失败不再挡启动

启动时的痕迹清理（迁移旧开关、删 `files/satori-*`）原来在 `Main.onHostReady` 的启动路径上，抛异常就会连桥接一起停掉——清理是收尾工作，不该是前置条件。这一版单独兜住它，失败只记一行 `footprint cleanup failed`。

同时把 `Paths` 拆出可测入口，用 `tests/PathsTest` 钉住：只删 `satori-` 与 `qk_sso.log`、只搬开关的值、宿主文件与目录不动、重复执行无副作用。
