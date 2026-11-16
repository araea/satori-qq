# 0.55.0 — 适配 QQ 9.3.85

QQ 客户端 9.3.80 → 9.3.85（versionCode 16920）。内核接口面静态自检 185/185 通过，没有运行时签名错位。dex-diff 报 98 行成员变化，源码点名的类一个没少。

### 变化

- 9.3.85 删掉了 `IKernelLoginService.getLoginService` 静态入口。访客票 `guestA2ToPskey` 那条 p_skey 退路随之失效。它本就只给访客模式发票，正常登录的账号拿不到，移除。取票现在只走票据管理器缓存与 `IPskeyManager.getPskey`。
- `IPskeyManager.getPskey` 的回调接口从 `oe2.a` 重排到 `rh2.a`。两个方法仍是 `onSuccess(Map)` 与 `onFail(String)`，动态代理按参数形状认，不用改。
- `QQNTWrapperUtil` 基类被清空，`$CppProxy` 不变。四个文件助手 `genFileMd5Hex`、`fileIsExist`、`getFileSize`、`copyFile` 仍是静态方法，媒体发送不受影响。
- `QRoute` 字段类型、`LoginActivity` 混淆方法名、会话增删的服务入口都不在模块调用范围内。

### 自检补盲

- `internal/compat` 原来只核内核 nativeinterface 的类型、字段、回调与会话服务。`IKernelLoginService.getLoginService` 被删时自检仍报全过，缺口在这里。
- 新增 `qq_side` 表：`QRoute.api`、`AppRuntime.getRuntimeService` / `getManager`、`QQNTWrapperUtil$CppProxy` 四个文件助手，按名字 + 参数个数核对。
- `pskey` 一项加核回调形状：`getPskey` 第二个参数的类型里要有一个 `on*` 方法收一个参数。回调接口每版重排混淆名，形状变了要能报出来。
- `scripts/compat-table.py --check` 现在也核 `qq_side` 的类名在源码里出现过。

改动在 Java 层，dex 内嵌在 `libsatori.so` 里：装机后**重启手机**才生效。
