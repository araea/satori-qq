## 0.8.9.44

这一版修的是「还是会掉线」。上一版拦住了三个踢线入口，漏了 MSF 侧那条，而且没人注意到腾讯在踢线时还会顺手把「下次自动登录」关掉——关掉之后重启 QQ 也只会停在登录页。

### 漏掉的那条踢线路

服务端下发的强制下线在客户端有两条互不经过的路。之前只拦了内核 `IKickApi` 那条（`NTKickProcessor`），漏了 MSF 那条：MSF 把强踢当错误事件抛给 `mqq.app.MainService$MyErrorHandler`，`onKicked` / `onKickedAndClearToken` / `onUserTokenExpired` / `onServerSuspended` / `onCloneError` / `onGrayError` 各自解完包，最后都落进 `popupNotification` / `popupNotificationEx`，在那里 `appRuntime.logout(reason, true)` 再跳登录页。走这条路的踢线，旧版一次都拦不住。

现在拦这两个出口，按 `LogoutReason` 分：拦 `kicked`、`secKicked`、`forceLogout`、`suspend`；放行 `user`（用户自己退出）、`switchAccount`（切号）、`expired`（票据自然过期，QQ 自己会重登）、`tips`、`gray`、`restartProcess`。

同时把 `NTKickProcessor` 的拦截点从私有的 `b(...)` 提到接口实现 `a(...)`。只拦 `b` 的话，`a` 在它之前已经把本地账号标成下线（`updateSimpleAccount`、`reportClearLoginData`）。

`/healthz` 的 `kick_hook` 因此从 3 变成 7。

### 踢线之后自动登录被关掉

`QQAppInterface.setAutoLogin(false)` → `mqq.app.AutoLoginUtil.setAutoLogin(uin, false)` 会把 `common_mmkv_configurations` 里的 `mqq_account_auto_login_<uin>` 写成 1（2 才是自动）。这个值是落盘的：踢线之后哪怕把 QQ 拉起来，它也停在登录页，外部看守重启多少次都一样。`NTKickProcessor.a` 与 `MainService$MyErrorHandler.onKickedInternal` 里都有这一句。

模块在拦下踢线后的 60 秒内把这次的 `setAutoLogin(false)` 顶回 `true`（`/healthz` 的 `auto_login_kept` 记命中次数）。窗口之外不改，用户自己退出登录不受影响。

踢线原文（时间、入口、reason、标题、正文）逐条落盘到 `qk_kick.log`（app 私有目录，0600，只 root 可读），`/healthz` 的 `kick_log` 给最近 12 条。

### 过检测

- `BLOCK` 表多一条 `/memfd:dalvik-jit-code-cache`：模块自己的 `.so` 是从 memfd 载进来的，maps/smaps 里有三行映射（r-xp/r--p/rw-p，同一个 inode）。ART 的 memfd 只叫 `jit-cache` 与 `jit-zygote-cache`，`dalvik-jit-code-cache` 只作为它们的 `[anon_shmem:...]` 名字出现，所以这条只打模块那三行，ART 自己的行照旧放行。libfekit 读的正是 maps 与 smaps
- `readlink` / `readlinkat` 除输入路径外还看返回值。`/proc/self/fd/<n>`、`/proc/self/map_files/<range>` 这类入口本身没有可拦的关键字，泄漏全在目标路径上
- `fdinfo`（`name:\t<路径>` 行）与 `numa_maps`（`file=<路径>` 行）纳入按行过滤的 `/proc` 路径
- `dl_iterate_phdr` 的过滤除按名字，还按加载基址跳过模块自己：libmapshide 从 memfd 载入，`dlpi_name` 就是当初 `dlopen` 的参数 `/proc/self/fd/<n>`，名字过滤认不出来
- 没有路径的可执行映射一律滤掉，`rwxp` 也算（此前只滤 `r-xp`）。设备上核对过：主进程有两条无路径 `rwxp`，读出来的字节都是 aarch64 蹦床（`ldr x17,#8; br x17` 一类），也就是 inline hook 的落地页；带 `[anon:...]` 名字的映射不受影响

自检文件 `qk_env_maps_*.json` 改成先写 `.tmp` 再 `rename`：原生那份原来是 `O_TRUNC` 直写，而它每秒钟被重写一次，读侧（`/healthz`、`/v1/internal/status`）撞上中间那一下就会读到空文件，看板上表现为 `maps` 整段消失。

### 看守

`scripts/qq-revive.sh` 的踢线判据改看落盘的 `qk_kick.log` 行数——`/healthz` 的 `blocked_kicks` 会随进程重启归零，而"踢线被拦下 = 服务端会话已作废"这件事在重启之后依然成立。另外补了 `online=false` 连续三轮就重启 QQ：退出登录时模块还活着、端口还在，原来的判据（要求 `online=true`）看不出来，只能靠重启登回来。

### 验证

真机 QQ 9.3.60.40970：`/healthz` 报 `kick_hook: 7`、`hardening: 56`，`internal/compat` 204/204，主进程 GOT 65 槽、MSF 42 槽，`loop_ok=1`、`leak_*` 全 0。内核探测 39/39、读动作 28/28、写动作 11/11 无回归，JVM 单测 19 项通过，native 过滤单测新增 20 项（memfd 三行、ART 自有两行、fdinfo/numa_maps 归类、readlink 返回值、权限位识别）。

顺带核出一条与本次改动无关的既有现象：`group_member_level` 在一个 QQ 进程里只有第一次会回调，之后再调就 15 秒超时。拿 0.8.9.43 干净包复现过同样行为（第一次 28/28、之后几次 27/28），判回归时不要拿这一项当判据。

版本号 0.8.9.44（versionCode 79），沿用同一签名密钥，支持从 0.8.9.43 直接覆盖升级。
