# 接手

现场以仓库外 `/storage/emulated/0/Dev/satori-qq-接手提示词.md` 为准（与 `Dev/satori-qq-接手提示词.md` 同一份）。

读：`HANDOFF.md` 构建、`STACK.md` 反检测与升版本、`ARCHITECTURE.md` JNI、`SATORI_SUPPORT.md` 协议。

产品名 **satori-qq**：Android 原生 QQ 的 Satori v1 实现端，给 Koishi `adapter-satori`。Java/Android 包名为 `com.satori.qq`。

主线是反检测；协议是 Satori v1（Koishi `adapter-satori` → `http://127.0.0.1:3001`）。能登录就不卸 scope。不改 `getSign` / `getFeKitAttach` 返回，不拦 `trpc.o3.ecdh_access`，不要 `trpc.o3.*` 通配。构建走 `/data/media/0/dev/satori-qq`。**0.8.9 已定稿**（见 `STACK.md`）。进程 `version` 必须是 0.8.9 才算吃上新代码。**不要开 0.8.10。**

**装完立刻重启 QQ。** `pm install -r` 后马上 `am force-stop com.tencent.mobileqq` 再 `am start`，不要等自然重启，不要问。`force-stop` 不是踢号。下一件事只观察 16–31 分钟真踢窗口。`loop_ok=1` 表示 fekit 已打上且过滤后的 maps 无泄漏。登录超时回 `SatoriQQ-0.8.5.apk`。
