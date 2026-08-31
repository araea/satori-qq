# 接手

现场以仓库外 `/storage/emulated/0/Dev/satori-qq-接手提示词.md` 为准。

| 文档 | 内容 |
| --- | --- |
| [`HANDOFF.md`](HANDOFF.md) | 构建与本机环境 |
| [`STACK.md`](STACK.md) | 反检测与换机 |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | JNI 与模块结构 |
| [`SATORI_SUPPORT.md`](SATORI_SUPPORT.md) | 协议 |

主线：反检测。协议：Satori v1 → `http://127.0.0.1:3001`。能登录就不卸 scope。不改 `getSign` / `getFeKitAttach` 返回，不拦 `trpc.o3.ecdh_access`，不要 `trpc.o3.*` 通配。构建目录 `/data/media/0/dev/satori-qq`。当前定稿 **0.8.9**（见 `STACK.md`）。

装完立刻重启 QQ：`pm install -r` 后 `am force-stop` 再 `am start`。`force-stop` 不是踢号。登录超时回 `SatoriQQ-0.8.5.apk`。
