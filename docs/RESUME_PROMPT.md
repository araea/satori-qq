# 接手

现场（PID / scope / 下一步）以仓库外 `/storage/emulated/0/Dev/onebot-qq-接手提示词.md` 为准。

读：`HANDOFF.md` 构建、`STACK.md` 反检测与升版本、`ARCHITECTURE.md` JNI、`ONEBOT11_SUPPORT.md` 协议。

协议已冻结，主线只做反检测（旧「不再加隐藏/拦上报」已取消）。OneBot 新动作先不做。不改 `getSign` / `getFeKitAttach` 返回，不拦 `trpc.o3.ecdh_access`，不要 `trpc.o3.*` 通配。能登录就不卸 scope、不 `force-stop`。构建走 `/data/media/0/dev/onebot-qq`。路线：仓库外 `onebot-qq-后续反检测增强方案.md`。
