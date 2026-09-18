# 过检测

记录 QQ 在本机自己做了哪些检测、模块挡在哪里、哪些挡不住。结论按真机 QQ 9.3.55 与 9.3.60.40970（versionCode 16070）的二进制核实。

## 检测来源

- `libfekit.so`：QSec 的宿主。读 `/proc/self/maps`、`smaps`、`mountinfo`、`cmdline`，并逐个读 `/proc/<pid>/cmdline` 找别的进程。字符串里带 root 管理器黑名单，以及 `cat /proc/mounts | grep magisk`
- `libturingxq.so` 与 `libturingmfa.so`：Turing 风控。读 `/sys/fs/selinux/enforce`，枚举 `/data/local/bin`、`/data/local/xbin`、`/sbin/`、`/system/xbin/`、`/system/sd/xbin/`，看 `/system/bin/ddexe`、`debuggerd`、`debuggerd64` 与 `/system/etc/install-recovery.sh` 是否存在
- `libmsfbootV2.so`：启动期上报。读 `/data/data/com.tencent.mobileqq/files/imei`，并用 `dl_iterate_phdr` 看模块列表
- `libMSFKernel.so`：读 `/proc/loadavg`、`/proc/stat`、`/proc/self/stat` 与 `/sys/.../scaling_cur_freq`，这些是遥测，不是环境判定，模块不碰它。16070 起它还用 `inotify_init1` / `inotify_add_watch` 盯自己的 `.MSF*` 数据文件，模块没接管

Java 侧在 `com.tencent.mobileqq.qsec.qsecurity` 下。`QSec` 的 `detectMethod`、`getXpsInfo`、`execTasks`、`reportLog` 是检测入口；`getSign`、`getLiteSign`、`getSignEntry` 是签名链路，不能动；`getFeKitAttach` 是登录附件，只记录不改。`SocketStatus.checkSocket(name)` 连一个抽象本地 socket，连上或权限被拒返回 1，连不上返回 0，用于探 LSPosed、Zygisk、Shamiko 这类守护进程。`ProcessCheck` 读 `/proc/self/cmdline` 取自己的进程名。16070 的 `qsecurity` 包与 16030 逐类相同，没有新增检测入口。

上报命令里，`trpc.o3.report.*`、`trpc.o3.mobile_security.*`、`trpc.gc_indust.device_report.*`、`trpc.ilive_cdn.report.*`、`OidbSvc.0xd79`、`OidbSvcTrpcTcp.0x9c00_*` / `0x9c01_*` / `0x9c02_*` / `0x9c0c_*` / `0x9cdf_*` 是环境上报，模块丢弃。`trpc.o3.ecdh_access.*` 是登录密钥交换，保留。`wtlogin.device_lock` 是账号设备锁服务，不是环境上报，模块不动它。

### 16070 新增的 QSec_Channel 上报器

16030 到 16070 只有 `libfekit.so` 与 `libMSFKernel.so` 变了，turing 系、`libQSec.so`、`libmsfbootV2.so` 的 md5 完全一致。fekit 的新 import 只有 `inet_addr`、`recv`、`send`、`setsockopt`（`send` 已在 GOT 拦截范围内），检测路径与黑名单关键字一条没加。变化在上报：

- 二进制里新增 `channel/src/reporter/{async,delay,high_reliability,super}_reporter.cpp`、`super_reporter_cache.h`，以及 `HighReliabilityReporter loadFromDisk/saveToDisk/report`、`QSec_Channel report retry!,%d`、`QSec_Channel report error! retry 3 times!`、`QSec_Channel cached size:%d`、`sendRequestPB:cmd:%s,datalen:%d`
- 命令表从 312 条变成 317 条，新增的 5 条是 `OidbSvcTrpcTcp.0x9c00_0`、`0x9c01_0`、`0x9c02_0`、`0x9c0c_0`、`0x9cdf_1`。前四条全 APK 只出现在 libfekit，`0x9cdf_1` 与 libMSFKernel 共用
- 这套上报器会把报不通的内容落盘、之后重发，重试 3 次。只按旧的 `trpc.o3.report.*` 名单丢包挡不住它。模块把这 5 条一起丢，并向服务端回空成功，避免它继续重试

`0x9cdf_1` 是这 5 条里唯一与 libMSFKernel 共用的，命令表里挨着 `wtlogin.log_report`，影响面比另外四条大。要整体关掉这套丢包逻辑，把配置里的 `block_o3_report` 置 false。

## 模块的处理方式

Java 层在 `qq/AntiDetect`，装在每个 QQ 进程：

- QSec 检测入口按返回类型置安全值：`detectMethod` 恒假，`getXpsInfo` 返回空，`execTasks` 与 `reportLog` 返回 0
- `SocketStatus.checkSocket` 命中框架名字时返回 0，其余名字原样放行
- ChannelProxy、ChannelManager、MsfCore 的收发口按命令白名单丢环境上报，并向服务端回空成功
- Root、Xposed、调试器、模拟器、包管理、堆栈、Pandora、Turing、MSF 遥测与强制下线处理逐项覆盖
- 人脸核身那条链路单独覆盖：TuringFace/turingcam 的设备信息与错误串、turingcam 的进程表扫描、慧眼的 `face_detect` / `camera_detect` 上报（见下节）
- SO 加载 / native hook 监控（SoMonitor）拦安装点，并把 `native_monitor_*` 事件从 Beacon 出口丢掉（见「SO 加载监控」一节）
- 设备标识只在配置了假值时改写，多路径保持同一值

`/proc` 文本过滤覆盖四种读法：`BufferedReader.readLine`、`RandomAccessFile.readLine`、
`Files.readAllLines`、`Files.readString`。四种共用同一套行判据（`AntiDetect.sanitizeProcLine`
/ `sanitizeProcText`），与 native 的 `line_blocked` 是同一张词表。

Native 层在 `native/mapshide.c`，只对检测库改 GOT，不动其它库：

- 命中库分两组。第一组是检测/风控本体：libfekit、libturingxq、libturingmfa、ckguard、wtecdh、libQSec、dandelion、libmsfbootV2。
  第二组是 2026-09-18 补的「进程内会读 `/proc/self/maps`」的 QQ 监控库：`libnative-memory-library-lib`（NativeMemoryMonitor 本体）、
  `librmonitor_base` / `librmonitor_memory`、`libmatrix-hookcommon` / `libmatrix-traffic`、`libshadowhook` / `libbugly_shadowhook`、
  `libthreadsuspend`、`liblogcathook`、`libunusedcodecheck`。逐库计数在 `qk_env_maps_*.json` 的 `libs` 里，键名
  `fekit / turingxq / turingmfa / msfbootV2 / qsec / ckguard / wtecdh / natmem / rmonitor / matrixhook / shadowhook /
  threadsuspend / logcathook / unusedcodecheck / other`。
  崩溃与符号化那几家（`libBugly_Native`、`libwechatbacktrace`、`libwechatcrash`）**刻意不补**：它们读 maps 与 `/proc/self/mem`
  是为了出崩溃报告，挡住会让报告本身坏掉。
- 逐库计数在新一轮 `install()` 开始时由 `reset_lib_patched()` 清零。**这个循环不能写死数字**：2026-09-18 加库那次写成
  `for (i = 0; i < 8; i++)`，新加的桶只增不减，而 Java 侧每秒调一次 `install()`，`libs` 里 rmonitor/shadowhook 几分钟就涨到
  416 / 288，与 `patched`（99）差一个数量级。`tests/mapshide-filter-test.c` 现在钉住「所有桶都被清掉」。
- 接管这些库 import 的 `open`、`openat`、`fopen`、`stat`、`access`、`readlink`、`getdents64`、`readdir`、`syscall`、`__system_property_get` 等符号，换成自己的包装
- 包中的 `/proc` 路径做行过滤：maps、smaps、smaps_rollup、mountinfo、mounts、status、environ、cmdline、tcp/tcp6，0.8.9.44 起加上 `fdinfo`（`name:\t<路径>` 行）与 `numa_maps`（`file=<路径>` 行）；路径命中黑名单直接返回 `ENOENT`
- 目录列举的条目名也过同一张表（`dent_name_blocked`，`getdents64` 与 `readdir` 共用）。0.8.9.46 起多一条前缀规则：`qk_` 开头的条目一律不列出。模块自己在 `files/` 下的 `qk_env_*.json`、`qk_kick.log`、`qk_guard.log`、`qk_sso.log` 属这一类。检测库不需要读 `/proc` 也能在自己进程内 `getFilesDir().listFiles()` 看见它们，而 `qk_env_maps_main.json` 直接写着模块打了多少 GOT、拦了哪些库、`loop_ok` 是多少。这个前缀是模块独占的：41 个 dex 里一个 `qk_` 字符串都没有（`libfekit.so` 里 grep 到的 4 处是 AArch64 指令字节的假命中）
- 无路径的可执行映射一律从行过滤里去掉（0.8.9.44 起 `rwxp` 也算，此前只滤 `r-xp`）。真机上核对过主进程那两条无路径 `rwxp`：一条 4KB（与 `:MSF` 同地址）、一条约 1.9MB（只有主进程有），读出来的字节都是 aarch64 蹦床（`ldr x17,#8; br x17` 这类），也就是 inline hook 的落地页。带 `[anon:...]` / `[anon_shmem:...]` 名字的映射不受影响
- 无路径的 RX 映射改名成 `dalvik-jit-code-cache`，模块自己的 `.so` 从 memfd 以同名载入；`BLOCK` 表里多一条 `/memfd:dalvik-jit-code-cache`，把模块那三行映射（r-xp / r--p / rw-p，同一个 inode）从 maps、smaps 里一并去掉
- `readlink` / `readlinkat` 除输入路径外还看返回值：`/proc/self/fd/<n>`、`/proc/self/map_files/<range>` 这类入口本身没有可拦的关键字，泄漏全在目标路径上
- `dl_iterate_phdr` 的过滤除按名字，还按加载基址跳过模块自己：libmapshide 从 memfd 载入，`dlpi_name` 就是当初 `dlopen` 的参数 `/proc/self/fd/<n>`，名字过滤认不出来
- 用 seccomp 对模块自身文本段里的裸 svc 用 TRAP 拦住，覆盖 openat、openat2、faccessat、faccessat2、newfstatat、statx、readlinkat、getdents64
- 装完后自己再读一遍 maps、tcp、environ，把 `leak_*` 与 `loop_ok` 落盘自证

memfd 名字的由来：ART 自己的两份 JIT 缓存是 `/memfd:jit-cache` 与 `/memfd:jit-zygote-cache`，`dalvik-jit-code-cache` 只作为它们的 `[anon_shmem:...]` 名字出现，带 `/memfd:` 前缀的从来没有。用 `dalvik-jit-code-cache` 当 memfd 名，再用 `/memfd:dalvik-jit-code-cache` 把模块那三行映射从检测库视野里去掉，ART 自己的行不受影响。早先用 `jit-cache` 会与 ART 撞成同进程两个同名不同 inode，那正是「加载器藏在 memfd」的判据。

0.8.9.39 起 token 扫描按「符号集合」而不是单个符号接管。libfekit 同时 import `strstr`、`strcasestr`、`memmem`，此前只接管 `strstr`，且只在 needle 恰好等于黑名单里的某个词时才返回 NULL：`strstr(maps_line, "com.topjohnwu.magisk-1.2")` 这类更长的 needle 会照常命中。现在三个入口共用一条规则：needle 里只要出现 `BLOCK` 表中任一条（子串、大小写不敏感），就返回未找到，判定集与 `path_denied` 一致。实测主进程补丁数 63 → 65，MSF 40 → 42。

检测库自己扫字符串的入口不止这三个。libturingxq 还 `regcomp` / `regexec` 一批 POSIX ERE，用在进程名、线程名与路径上：

```text
^/system/xbin/ku[.]sud$   ^daemonsu:   ^kworker/[1-9][0-9]*:[0-9]+$
^kr_worker/…  ^km_worker/…  ^tworker/…  ^tu_worker/…  ^tq_worker/…
^permmgrd$   ^360sguard$   ^/data/data/[[:alnum:]_.-]+/   ^/system/bin/[.]   ^[.]
```

这些正则不走 libc 字符串函数，`strstr` 家族接管不到，目前靠「别撞上」：模块自己起的线程名是 `pool-N-thread-M`（JVM 默认风格），路径不带 `sator-` / `magisk` / `kworker` 之类字样，没有一条能匹配。改动线程名、临时文件名或模块目录名时要照这份清单核一遍。

线程名外部可见，`/proc/<pid>/task/*/comm` 直接读得到。0.8.9.39 自检到两处带模块名的线程（`satori-self-send`、`satori-channel-unmute`），0.8.9.40 改成 `pool-8-thread-N` 与 `pool-9-thread-1`；QQ 自己也有若干 `pool-N-thread-M`，这种名字不显眼。新起线程不要用模块名，要能过 `scripts/qq-satori-exposure-audit.sh` 的 `suspicious_thread_names`（它匹配 satori / xposed / vector / zygisk / mapshide）。

logcat 是接受的暴露：Java 侧 `L.e` 与 native 的 `Q.Maps` 用 `Q.` 前缀的标签，消息里会提到 patch 计数与 seccomp。QQ 自己进程写下的日志，自己的进程与 root 读得到，拦不掉，而排障要用它。详细数字另外落盘到只 root 可读的 `qk_env_maps_*.json`。常驻通知的渠道名与标题带「Satori」是给人看的，属必要暴露。

清单带 `xposed*` 元数据用于注册。按安装包元数据判定模块的工具能看到它：Duck Detector 的 LSPosed 卡片按 `xposed*` 键扫已安装应用，本模块因此列在那张清单里，与便签模块和「雹」并列。QQ 进程内不受影响：`AntiDetect` 的 PackageManager 钩子在本进程内摘掉 `com.satori.qq`，并剥掉所有查询结果里的 `xposed*` 元数据。

真机实测（16070，0.8.9.39）：主进程 libfekit 的 GOT 逐槽核对全部指向 libmapshide 的包装（`dlsym`、`open`、`fopen`、`getenv`、`readdir`、`freopen`、`strcasestr`、`memmem` 等），主进程 65 个 slot、MSF 42 个，maps、tcp、environ 泄漏 0，`loop_ok=1`。

### 踢下线的入口

服务端踢线在客户端有不止一个处理入口，它们互不经过对方。少挡一个，被踢时界面照样退回登录页：

| 入口 | 触发方 | 拦下的方法 | 计数标签 |
| --- | --- | --- | --- |
| `com.tencent.mobileqq.kick.NTKickProcessor` | 内核 `IKickApi` 收到的踢线 | `a(AppRuntime, KickedInfo)`、`b(AppRuntime, KickedInfo, LogoutReason)` | `nt-kick` |
| `com.tencent.mobileqq.login.ntlogin.ao` | `NTLoginTicketManager` 刷新登录票据失败，错误码 140022014 / 140022015 / 140022016 或 `refreshMethodNeedKick` | `f(int, String)` | `ticket-refresh` |
| `com.tencent.mobileqq.login.api.impl.UidServiceImpl` | 取不到 UID | `kickToLoginPage()` | `uid-fail` |
| `mqq.app.MainService$MyErrorHandler` | MSF 把强踢当错误事件抛上来 | `onKicked` / `onKickedAndClearToken` / `onKickedInternal` / `onCloneError`（处理器入口） | `msf-kick-entry` |
| `mqq.app.MainService$MyErrorHandler` | 同上，最后一步的界面出口 | `popupNotification(...)`（6 参与 8 参两个重载）、`popupNotificationEx(...)` | `msf-kick` |
| `mqq.app.MainService$MyErrorHandler` | 账号进风控灰名单 | `onGrayError(...)`，只当软事件 | `soft-kick` |

`NTKickProcessor` 那条：`a` 是接口 `IKickApi.b` 的实现，`b` 是它调用的私有方法。只拦 `b` 的话，`a` 里在它之前做的几件事照旧执行：`kick.a` 线程（清登录数据）、`updateSimpleAccount(uin, false)`、`reportClearLoginData(uin, "2004")`、`setSortAccountList`，本地账号列表当场被标成已下线。两个都拦。

MSF 那条要拦处理器入口，不拦 `popupNotification`。`popupNotification` / `popupNotificationEx` 是 `MyErrorHandler` 一堆回调（`onKicked`、`onKickedAndClearToken`、`onUserTokenExpired`、`onServerSuspended`、`onCloneError`、`onGrayError`）共同的最后一环，里面做的是 `appRuntime.logout(reason, true)` 再拿 `LoginActivity` 发 `ACTION_KICK_TO_LOGIN`。但毁本地登录态的写在它前面，只拦出口等于只挡住界面动作。0.8.9.46 起四个处理器入口整体 no-op，出口那两个继续挂着，负责按 reason 过滤 `onUserTokenExpired` 与 `onServerSuspended`。

`onGrayError` 单独处理：它兼管 `wt_GetStViaSMSVerifyLogin` 与 `wt_loginAuth` 的响应，整条拦掉会把「被踢之后靠短信验证登回来」封死；而它又可能反复发生，当成硬踢线会让看守每来一次就 force-stop QQ 一次。所以只做两件事：放行（除上述登录命令外），并把善后窗口打开、记一行 `soft-kick` 到 `qk_guard.log`（不算 `blocked_kicks`、不写 `qk_kick.log`）。

不是所有 `LogoutReason` 都该拦。拦的是 `kicked`、`secKicked`、`forceLogout`、`suspend`。放行 `user`（用户自己退出）、`switchAccount`（切号）、`expired`（票据自然过期，QQ 自己会重登）、`tips`、`gray`、`restartProcess`。拦这些会造成真正的问题。判定在 `AntiDetect.kickReasonBlocked`，有单测。

`/healthz` 的 `kick_hook` 是踢线入口的 hook 数之和（0.8.9.46 起正常为 **12**：nt-kick 2 + ticket-refresh 1 + uid-fail 1 + msf 入口 4 + msf 出口 3 + 灰名单软事件 1），`last_kick_source` 记最近一次是哪个入口拦下的，`last_kick` 记参数，`kick_log` 是最近 12 次的原文。另有一个独立的登出守卫，hook 数在 `logout_guard.hooks`（0.8.9.46 起正常为 **5**），以及保住盘上登录态的守卫，在 `login_state.hooks`（正常为 **2**）。

0.13.2 起还有两个**只观测、不拦**的计数，用来判「账号是谁摘掉的、走的哪条路」：

| 字段 | 是什么 |
| --- | --- |
| `token_expired` | `MyErrorHandler.onUserTokenExpired` 被调用了几次、最近一次带的 `ssoErr=` 与 `branch=`。`ssoErr` 属于 {-10135, 10136} 走 `kicked` 支（账号标记写 `_t`，出口被拦），其余走 `expired` 支（写 `_f`，出口放行 → 跳登录页）。分不清这两支就分不清「被踢之后是谁把人送回登录页的」 |
| `allowed_logout` | 被放行的登出（reason 不在要拦的那几种里）。只有 `expired` 那一支会把账号摘掉再跳登录页，以前这条完全静默 |

这两项各落一行到 `qk_guard.log`（动作名 `token-expired` / `allowed-logout`），**不写 `qk_kick.log`**——那份是看守「立刻重启 QQ」的判据，观测项混进去会变成重启风暴。`login_state` 的 `kept-login-state` 行 0.13.2 起带上 `by=` 与 `frames=`，用来回答是哪个调用点摘的账号（`updateSimpleAccount*(uin,false)` 不止一个调用点）。

**`by=` 的来历要说清（0.13.2 写错过，0.13.3 修）**：第一版只做栈扫描，过滤表里漏了框架自己那两类帧——单段类名（Vector dex 里的混淆类，例如 `g.a`）与 `org.matrix.vector.*`。Xposed 旧式钩子里回调上方先经过框架的分派帧，它比 QQ 的调用点更靠近回调，于是每一次命中都返回同一个与调用点无关的混淆帧：看起来像答案，其实是常量。0.13.3 起改为**入口打标记**：`onUserTokenExpired` 的钩子在 before 里写 `token-expired`、`logoutWhenReqUidFail` 的钩子写 `uid-fail`（两条路径都是同线程内联调 `updateSimpleAccount*`），after 清除；`by=` 读这个标记，取不到写 `by=-`；`frames=` 是栈兜底（跳过单段类名与 `org.matrix.vector.*`，最多记三帧），留给还没点名的第三方调用点。

读法（0.13.3 起）：

| 台账里看到 | 结论 |
| --- | --- |
| `token-expired` 行 | 一定是 `onUserTokenExpired` 走过（`uid-fail` 那条路绝不会产生这行） |
| `by=token-expired` + `kept-login-state` | `onUserTokenExpired` 的 `expired` 支在摘账号（`kicked` 支传 `true`，守卫第一句就 return，不会有这行） |
| `by=uid-fail` 或 `blocked-logout uid.logoutWhenReqUidFail` | `UidServiceImpl.logoutWhenReqUidFail` 那条路 |
| `by=-` 但 `frames=` 有真类名 | 第三个没点名的调用点，按 `frames=` 追 |
| `(kick marker <N>s ago)` | 这一行发生在某次踢线的善后期里（内存里那份被重启清掉时走这个口径） |

`/healthz` 的 `token_expired.hooks` / `allowed_logout.hooks` 用来区分「没发生过」与「钩子没挂上」：`count=0` 有两种意思，`hooks` 才有唯一解释。

`last_kick` 带的字段：`entry=` 是被拦下的处理器入口名（`onKickedAndClearToken` 是带清票据的那一支，`onKicked` 是另一支），`args=` 是 QQ 传进来的那几个布尔（`onKickedInternal` 的 `isTokenExpired` 与 `isSameDevice`），`svcCmd=` 是这个响应的服务命令、`ssoErr=` 是它带的 SSO 错误码。加上服务端那个包里的 `kickType=`（`RequestMSFForceOffline.bKickType`，名字按 `KickedType` 的声明顺序取）与 `sigKick=`（1 表示带 `vecSigKickData` 的安全强踢，reason 取 `secKicked`；0 是普通强踢），另有 `seqno=` / `sigLen=` / `sameDevice=`。内核那条路（`nt-kick`）参数是 `KickedInfo`，字段比 MSF 包多，单独记 `appId=` / `instanceId=` / `securityKickedType=`。只记 reason 与服务端文案的话，现场分不出「在别处登录被顶」和「风控打击」。

`cmd=` 那一项要按 2026-09-16 19:39 的实测读：**`cmd=unknown` 不是「取不到」，它是 `MsfCommand` 枚举里的 `unknown` 常量**（`FromServiceMsg.getMsfCommand()` 在这个包里没被设值，默认就是它）。真正有用的是 `svcCmd=`。也就是说旧记录里的 `cmd=unknown` 一直是有意义的：它说明这个包不是按 MSF 命令解析的，而是按服务命令（`getServiceCmd`）走的。0.13.0 起 `cmd=` 取不到时会写成 `cmd=-`，那是另一个含义（反射失败）。

2026-09-16 19:39:26 那次踢线是带新字段的第一条，形态可以直接当模板用：

```text
msf-kick-entry entry=onKickedAndClearToken kickType=0(KKICKBYMULTIINST?) sigKick=0 sameDevice=0
seqno=3721231761 sigLen=0 title=下线通知 msg=你的账号当前登录已失效，请重新登录。
uin=3373167460 args=false svcCmd=StatSvc.ReqMSFOffline cmd=unknown up=637s pid=20713
```

读法：`entry=onKickedAndClearToken` 说明走的是 `onInterceptKicked(..., isTokenExpired=true)` 那一支（也就是会 `updateSimpleAccount(uin, false)` 摘账号的那支），`svcCmd=StatSvc.ReqMSFOffline` 是服务端下发的 MSF 命令名，`args=false` 是 QQ 传进来的那个布尔。**没有 `token-expired` 行**，说明它没经过 `onUserTokenExpired`——即这一次是纯服务端踢线，不是票据过期那条路。

同一次踢线的 3 分半后（19:42:49，pid 14266）`qk_guard.log` 里出现了 8 行 `kept-login-state ... (last kick  -)`：那个进程内存里一次踢线都没记到，靠的是 `qk_kick.log` 的 mtime——也就是 0.13.1 的跨重启窗口在真机上第一次被看见生效。要是没有那次改动，这几行守卫会直接 return，账号就被摘成 `_f` 了。

踢线原文用 `Packet.decodePacket(buf, "RequestMSFForceOffline", new RequestMSFForceOffline())` 解：那就是 `MainService` 自己解这个包用的入口。别的回调带的是另一种包，硬解会得到垃圾字段，所以解完要校验（标题或正文至少一个非空，或 uin 非 0），过不了就只记 `cmd=` 与 `uin=`。QQ 自己那两份 `QQXlog_*.qqxlog` 解不开，要证据读模块自己落盘的 `qk_kick.log` / `qk_guard.log` / `qk_sso.log`。

### `kickType` 能读到的程度

2026-09-15 在 16070 上核。`classes.dex` 里 `com.tencent.qqnt.kernel.nativeinterface.KickedType` 的声明顺序是：

```text
KKICKBYMULTIINST(0), KKICKBYMOBILE(1), KKICKBYPASSWORDCHANGE(2), KKCIKBYLOWVERSION(3)
```

`KickedInfo` 的字段是 `appId, instanceId, kickedType, sameDevice, securityKickedType, tipsDesc, tipsTitle`。其中 `appId` 是判「谁把我顶了」的唯一字段（PC、手机、平板各有自己的 appId），MSF 那个包里没有它。

仍然不确定的两件事，别当结论用：

- 「服务端那个字节就是枚举序号」是推定。没有任何 Java 类读这个字段（映射在 native），而 native 里连这几个枚举名的字符串都没有（`libMSFKernel.so`、`libkernel.so` 等逐个 `strings` 过，0 命中），所以核不到映射代码。日志里名字后面一直带 `?`
- `0` 有歧义：`KickedInfo` 的默认构造就是 `KickedType.values()[0]`，服务端没填时同样取到 0

## 人脸核身（慧眼 + TuringFace）

2026-09-18 现场：做身份认证时人脸核身页报
`你的设备环境异常，无法使用人脸识别能力。请更换成安全设备重试。`

### 这条路是怎么走的（都核过）

- **页面**：`com.tencent.mobileqq.activity.IdentificationGuideFragment`（`pg_bas_face_verify`，
  按钮 `em_bas_next_btn`）→ `IdentificationResultFragment`（`pg_bas_real_name_limit`）。
  天枢页面轨迹落在 `databases/beacon_db_com.tencent.mobileqq:openSdk` 里，
  `udf_kv` 的 `cur_pg` 带着 `fail_reason`。
- **那句话不是客户端的字符串**：41 个 dex、`resources.arsc`（UTF-8 与 UTF-16 都试过）里都搜不到
  `设备环境异常` / `安全设备`。它出现在 `beacon_db_...:MSF` 的 `errorMsg` 字段里，
  即**服务端 SSO 响应带回的错误串**，客户端只是照显。
- **SDK**：`IdentificationHuiyanSDKInitHelper` → `HuiYanAuth`（慧眼
  `com.tencent.could.huiyansdk` 1.0.9.32，模型与 so 落在
  `files/qqidentification/huiyan/`，日志在
  `Android/data/com.tencent.mobileqq/files/cloud-huiyan/log/`）。慧眼内部用
  `com.tencent.turingcam.TuringFaceDefender`（TuringFace 2.3.0，244 个混淆类在
  classes19.dex，上报地址 `https://sdk.faceid.qq.com/api/turing_new`）采设备风险，
  native 落在 `libturingmfa.so`（native 层按 `turing` 命中，本来就在补 GOT）。
  慧眼自己那两个下载下来的 `libYTLiveness.so` / `libYTCommonLiveness.so`
  `strings` 过一遍只有活体检测，没有任何环境判定，不用管。
- **上报通道**：`HuiYanPublicEventCallBack.mainAuthEvent(String)` →
  `IdentificationIpcServer` 的 `action_report` → `IQSecChannel.feEnvReport(runtime, tmpKey, report)`
  → `QSecChannelImpl.feEnvReport` → `MainProcess2Fe.k(runtime, "face_detect", {key, content})`
  → `O3BusinessHandler.P2("notify", ...)` → `MsfServiceSdk.getSecDispatchEventMsg`
  （`cmd_sec_dispatch_event` + `MsfCommand.msf_sec_dispatch_event`）→ MSF。
  摄像头那条是同一个形状，事件名 `camera_detect`。
- **命令名过滤拦不到它**：这条路不走 `ChannelProxy.sendMessage`，`isEnvReportCmd` 一条都不匹配；
  而 `cmd_sec_dispatch_event` 同时承载 `FaceQueryAppConf`、`FaceGetRecognitionResult`
  这些必须放行的请求，不能整条命令丢。

### 模块现在挡在哪

| 入口 | 处置 |
| --- | --- |
| `QSecChannelImpl.feEnvReport` / `feCameraActionReport` | no-op（QQ 传的回调是 null，不会挂住调用方） |
| `MainProcess2Fe.k(..., "face_detect"\|"camera_detect", ...)` | 丢弃并记 `face.dropped` / `face.events` |
| `TuringFaceDefender.getDeviceInfo`、`TuringSdkImp.b()` | 结果保证非 null（null 会被慧眼当成「采集失败」写进上报） |
| `TuringSdkImp.a()`（SDK 错误串） | 回空串，避免 `turing init error code: N` 被当成环境证据 |
| `com.tencent.turingcam.oqKCa.a(int)`（进程表扫描） | **留真名，只滤敏感条目**（见下） |

`oqKCa` 那条是 0.14.0 修的：此前走 `hookSafeDefaults`，把 `a(int)` 一律置成 `null`，
等于**整张进程表清空**。而空进程表正是虚拟机/沙箱的长相——把「这台机器上没有任何进程」
交给服务端，比让它读到真进程名更可疑，而真进程名里本来也不会出现 root 管理器的字样
（本机没装）。现在改成 `afterHookedMethod` 过滤：`PROCESS_NAME_DENY` 命中的回空串，
其余原样放行。

### 还没解决的部分（别当成已修）

**2026-09-18 追到根因了：这条提示是硬件认证（Key Attestation）拿不到，服务端给的 413。**

- 服务端返回的码是 **`ret=413`**，客户端没有对 413 的任何分支（`grep 413` 在
  identification/activity 里只有无关命中），所以它一路进 bundle → `errMsg` → 结果页。
- 读法（可重复）：把 `databases/beacon_db_com.tencent.mobileqq:MSF*` 拷出来，搜
  `tagIdentificationErrorCode`，里面就是 `errorCode` 与 `errorMsg` 两个字段。2026-09-18 02:37
  那次是 `errorCode=413`、`param_appid=537389189`（人脸服务 appId）。
- 人脸的风险引擎 TuringFace（`com.tencent.turingcam`，244 个混淆类）里带一整套**硬件认证**：
  `wiSNn` 用 `android.security.keystore.KeyGenParameterSpec` 建带 challenge 的密钥、
  `k7FCJ` 通过 Binder 连 **Soter** 服务取认证、`nq6Fd`/`d3EI1` 解析证书里的 attestation extension。
  `d3EI1` 把**整条证书链**（每一张都 base64）都放进上报的 JSON，也就是链是要送到服务端校验的。
- 本机拿不到这条链。2026-09-18 用机内 Java 测试台（`~/ddwork/envharden/kt.dex`）当场复现：
  带 `ATTESTATION_CHALLENGE` 的 EC 密钥生成抛
  `ProviderException: Failed to generate key pair` → `KeyStoreException: -74`
  → `Error::Km(ATTESTATION_KEYS_NOT_PROVISIONED)`。原因是解锁 bootloader 后厂商撤销了认证密钥供给，
  而补供给要走 Google 的 RKP（本机不可达）。
- 所以「安全设备」= 能出有效硬件认证的设备。这条**客户端改不了**：签名在 TEE 里做、链要
  服务端对着 Google 硬件根校。改属性、改 GOT、hook Java 都到不了这一层。就算哪天 RKP 把密钥领回来，
  认证里照样写着 `bootloader_state=unlocked` / `verified_boot_state=unverified`（`cmd remote_provisioning
  csr default` 的 CSR 明文），服务端还是不会认。回到「TEE 卡是终局」那条结论。
- 能真解决的两条路只有：换一台没解过 bootloader 的设备做这次认证，或者把这台机器刷回锁定的官方系统
  （等于放弃 root）。**不要在这个方向上继续加 hook。**
- 另一条独立的现象：两次现场里慧眼自己的活体也没过（`errorcode:1007 活体检测没通过，请重试`，
  见 `Android/data/com.tencent.mobileqq/files/cloud-huiyan/log/*.ailog`）。这和 413 是两件事，
  413 是 QQ 自己那条人脸请求的结论。

- **判定在服务端**，客户端做的只是「别把脏数据送上去」。服务端如果已经存了这台设备的历史
  风险结论，本地怎么改都不会变。
- 因此 `block_face_report` 是**单独开关**，默认 true。关掉它等于把慧眼采集的那批数据放行；
  服务端如果依赖这批数据做判定，关掉反而更脏。要不要关只能 A/B：`/healthz` 的
  `face.dropped` 能看出这批数据是不是真被丢了，`face.hooks.*` 为 0 说明钩子没挂上
  （QQ 改了类名/方法名），要按 0 处理而不是「没发生过」。
  **2026-09-18 02:37 的现场里 `face.dropped=0`**：这条通道根本没被用到，0.14.0 加的钩子
  不是这次失败的原因（留着是因为那批数据确实不该出站）。
- 未覆盖：`com.tencent.turingcam` 的混淆类里那些直接读 `Build.*`、`/proc` 的采集
  （`AV6dE`、`FxCVY`、`LwgsO`、`QjsR0` 等）没有逐个接管；它们的数据最终经 native
  `libturingmfa` 拼装，native 那一层靠 GOT 过滤。

## SO 加载监控（SoMonitor / NativeMonitor）

2026-09-18 读 9.3.65 的 dex 时发现的另一条上报通道，不在 QSec 那条线上。

- **装配**：启动步骤 `com.tencent.mobileqq.startup.step.OpenThreadCreateHook`（classes.dex）在
  `ThreadManager.getSubThreadHandler().postDelayed` 里判断
  `config != null && System.currentTimeMillis() % 10000 < config.soHook`，成立才调
  `NativeMonitorConfigHelper.setupSoLoadHook()`。配置来自 united config 组 `100458`
  （`NativeMonitorConfig`，字段 `soHook` 默认 **-3**）。
- **它挂什么**：`setupSoLoadHook()` → `NativeMemoryMonitor.getInstance().setupSoLoadHook(context, cb, true)`
  → native `nativeSoLoadHook(pkg, nativeLibraryDir, Runtime.nativeLoad 的 ArtMethod, true)`；native 侧
  在 `Runtime.nativeLoad` 上挂钩子，每次 SO 加载回调 `onSoLoad(path, backtrace)`。
- **报什么**：回调链是 `NativeMonitorConfigHelper$3` → `isSoFileLegal(file)`（比对 mmkv 里登记的
  name/length，返回 `0` legal / `1` name_illegal / `2` path_illegal / `3` length_illegal / `-2` error）
  → `QQBeaconReport.report`，事件名 `native_monitor_so_load` / `native_monitor_native_hook` /
  `native_monitor_all_so_load`，字段含 `so_name` / `so_path` / `so_md5` / `so_length` / `is_legal` /
  `legal_detail` / `backtrace`。另有 `soMonitorCollectorReport*` 一套 IPC 名字。
  默认上报率 `soLoadReportRate=10`、`soLoadIllegalReportRate=1`——**非法加载是每次都报**。
- **为什么与本模块有关**：`isNeedCheck(stack)` 只在栈里出现 `java.lang.System.loadLibrary` 时放过，
  而模块的 `.so` 是 `System.load("/proc/self/fd/<n>")`（memfd），栈里是 `System.load` →
  会被检查。路径 `/proc/self/fd/<n>` 的 `getName()` 是 fd 号，与登记表里的名字对不上 →
  `is_legal=1`，并且 `md5` 是**真的把那个 fd 读完算出来的**。
- **本机现状（证据，2026-09-18）**：`files/mmkv/so_monitor_so_file_infocommon_mmkv_configurations`
  里有 **203** 条登记（`{"a":"libark.so","c":"/data/data/com.tencent.mobileqq/txlib/…","d":…,"f":"<md5>"}`），
  **没有模块的 `.so`**；所有 `beacon_db_*` 里 `native_monitor` 一条都没有。也就是说这条钩子现在
  **没装上**——默认 `soHook=-3`，`% 10000 < -3` 恒假。这是**服务端可随时打开的开关**，不是稳定的安全状态。
- **模块的做法**（两层，都在 Java）：
  1. 拦安装点，`XC_MethodReplacement` 置空：`NativeMonitorConfigHelper.setupSoLoadHook`、
     `NativeMemoryMonitor.setupSoLoadHook(Context, ExternalProvider, boolean)`、
     `NativeMemoryMonitor.setNativeHookMonitor(INativeHookMonitor)`。不装钩子就没有数据。
     **刻意不动** `setupFileHook` / `setupOpenDexFileHook` / `initJniHook` / `initThreadHook`：
     那几项是 QQ 自己的文件、dex、线程监控，与检测无关，停了只会改变行为。
  2. 兜底：`QQBeaconReport.report` 的每个静态重载（事件名在第 1 或第 2 个参数上）按前缀丢
     `native_monitor*` 与 `soMonitorCollectorReport*`。别的 beacon 事件原样放行。
- **观测**：`/healthz` 与 `internal/status` 的 `env_report.native_monitor`：`hooks` 是拦下的安装点数
  （正常 **3**），`beacon_dropped` 是被丢掉的事件数，`last` 是最近一条事件名。`hooks=0` 说明这一版没挂上，
  按 0 处理，不要读成「没发生过」。
- **判据**：想确认它到底有没有被打开，看 `beacon_db_*` 里有没有 `native_monitor` 行，
  以及 mmkv 里登记条数有没有涨；两个都没动就是没装。

## 设备侧现状

2026-09-15 实测。判断环境是否异常用这几条。

| 项 | 本机 | 说明 |
| --- | --- | --- |
| `ro.boot.verifiedbootstate` | `green` | 设备层已处理（KSU 侧模块改的），与 `ro.boot.flash.locked=1` 一致 |
| `ro.boot.flash.locked` | `1` | 同上 |
| `ro.boot.hardware` / `ro.bootloader` | `qcom` / `unknown` | libfekit 只读这两个，没有异常 |
| `ro.debuggable` / `ro.kernel.qemu` | 空 / 空 | libfekit 读；空是正常 |
| `ro.build.tags` / `ro.build.type` | `release-keys` / `user` | 正常 |
| `ro.vivo.oem.name` | 空 | libfekit 读它（跨厂商 ROM 检查）；空是正常 |
| root 管理器应用 | 一个都没装 | libfekit 自带名单：magisk / kernelsu / apatch / kingroot / kingo / shuame / smedialink / zhiqupk / cleanmaster 系，`pm list packages` 逐个对过 |
| `/sys/module/kernelsu`、`/sys/module/apatch` | 不存在 | 本机 KSU 编进内核（GKI），没有模块目录 |
| `files/imei`（libmsfbootV2 启动读它） | `4538aeb7a1c7d631`，2026-09-07 起没变 | 设备指纹稳定，模块也没设 `fake_imei`（配置文件不存在，全用默认值） |
| 时钟偏差 | 与 baidu / tencent / deepseek 回包的 `Date` 差 1 秒内 | SSO 签名对时间敏感，偏差大的设备会「登录已失效」 |
| libfekit 读的 `/sys` 路径 | `/sys/devices/soc0/serial_number`（应用读是 EACCES）、`cpu0/cpufreq/{cpuinfo_max,cpuinfo_min,scaling_cur}_freq` | 前者普通应用拿不到（QQ 也拿不到），后者是遥测 |
| libturingxq / libturingmfa 读的 `/sys` | `/sys/fs/selinux/enforce`、`cpu%d/cpu_capacity` | 本机 enforcing，正常 |
| libMSFKernel 的关键字 | 只有 `loadavg` / `meminfo` / `stat` 与它自己的 `.MSF*` 数据文件 | 没有 root / hook 关键字名单；inotify 盯的是它自己那些文件，不是检测面 |

结论：客户端这边看不到能解释「被设备异常整下线」的脏东西。再堆过检测的收益有限，先把「下一次踢线是什么性质」的证据抓全，不先改策略。

### 踢线之后 · 本机毁的是盘上的登录态

`MainService$MyErrorHandler.onKickedInternal(ToServiceMsg, FromServiceMsg, isTokenExpired, isSameDevice)` 分两支：

```text
isTokenExpired == false（onKicked 进来）
    mApplication.setAutoLogin(false);                     // mmkv 落盘
    popupNotification(..., forceLogout, ...)              // 界面出口
isTokenExpired == true （onKickedAndClearToken 进来）
    MsfSdkUtils.updateSimpleAccount(uin, false);          // files/user/u_<uin>_t → _f  ← 要害
    mApplication.setSortAccountList(...);                 // 已登录列表当场少一个号
    popupNotification(..., LogoutReason.kicked, ...)      // 界面出口
```

毁本地登录态的写在界面出口之前，所以拦出口只能挡住界面动作。2026-09-15 那次踢线走的是第二支（`qk_kick.log` 记 `reason=kicked`、`bSigKick != 1`）：出口拦下了（`blocked_kicks=1`），但账号标记已经改名、自动登录已经关掉，重启多少次都停在登录页。

两个落盘位置：

| 状态 | 在哪 | 谁写它 |
| --- | --- | --- |
| 账号是否在已登录列表 | `/data/data/com.tencent.mobileqq/files/user/u_<uin>_t`（`_f` = 已登出） | `MsfSdkUtils.updateSimpleAccount*(uin, boolean)` |
| 下次是否自动登录 | `common_mmkv_configurations` 的 `mqq_account_auto_login_<uin>`（2 = 自动，1 = 手动） | `mqq.app.AutoLoginUtil.setAutoLogin(uin, boolean)` |

mmkv 的条目是「varint 键长 + 键 + varint 值长 + 值」，键不是 NUL 结尾，取键后面两字节即可（`0102` = 开、`0101` = 被关）。

所以 0.8.9.46 三层一起上：

1. **拦入口**：四个处理器入口整体 no-op，上面那些写操作一次都不会发生
2. **顶回去**：善后期内 `updateSimpleAccount` / `updateSimpleAccountNotCreate` 的 `false` 顶成 `true`（改名仍然发生，但改成 `_t`，账号留在列表里），`AutoLoginUtil.setAutoLogin(uin, false)` 顶成 `true`
3. **修回来**：离线时把 `u_<uin>_f` 改回 `u_<uin>_t`、把自动登录开关写回 2，各记一行 `self-heal`。要修哪个号只认踢线原文里的 `uin=`（内存里没有就读 `qk_kick.log` 末行）。不要拿「`user/` 里唯一那个 `_f`」当判据，本机就有一个用户 2026-09-11 自己注销掉的 `u_1665757132_f`，按这个猜会把早就登出的号重新标成已登录

善后期是 15 分钟且跨重启成立。判据除内存里的 `lastKickMs`，还看 `qk_kick.log` 的修改时间（带 5 秒缓存）。看守恰好在踢线后 force-stop QQ，重启之后内存计数归零，只靠内存的话善后期在新进程里等于不存在。

### 踢线之后 · 不许本机自己登出

拦下踢线入口并不覆盖所有登出路径，所以另有一层登出守卫。`AppRuntime.logout(boolean)` 底下会把 reason 写死成 `user`，`QQAppInterface.logout(boolean)` 是它的 override 且带 kickPC 的注释。这两个入口既服务「用户点退出登录」（主线程），也服务「踢线路径的登出」（`UidServiceImpl` 在工作线程上调的就是它）。reason 分不出来，按调用线程分：主线程那次当用户点的，放行并记下「用户主动退出」；工作线程那次窗口内 no-op。

| 入口 | 拦法 |
| --- | --- |
| `UidServiceImpl.logoutWhenReqUidFail()` | 5 分钟窗口内整体 no-op（连带摘账号、报清数据一起停） |
| `QQAppInterface.logout(boolean)` / `AppRuntime.logout(boolean)` | 窗口内且非主线程才拦；主线程那次视为用户主动退出 |
| `AppRuntime.logout(LogoutReason, boolean)` | 窗口内且 reason 是 `kicked` / `secKicked` / `forceLogout` / `suspend`；reason 是 `user` / `switchAccount` 时只记「用户主动退出」 |
| `AppRuntime.ntTriggerLogout(LogoutReason)` | 同上 |

**窗口判据要跨重启成立**（0.13.1 修）：`inLogoutGuardWindow` 原先只读内存里的 `lastKickMs`，而这一层存在的理由恰恰是「拦住踢线之后 QQ 顺手把自己登出」——看守每次都会在踢线后 force-stop 重启 QQ，新进程里 `lastKickMs` 是 0，于是 5 个钩子在新进程里一律直接 return，真正的登出没人拦。台账的形状就是「账号标记被顶回去了、人还是被登出」，`qk_guard.log` 里每一行都带 `(last kick  -)`。现在除了内存那一份还读 `qk_kick.log` 的 mtime（和善后期同一套来源），窗口长度仍是 `LOGOUT_GUARD_MS`（5 分钟）。

只有 `user` 与 `switchAccount` 算「用户主动退出」（`AntiDetect.userInitiatedLogout`）。`expired` / `gray` / `tips` / `restartProcess` 是 QQ 自己的生命周期，把它们当成用户意图会让善后期在一件跟用户无关的事上失效。

被拦下的登出记在 `/healthz` 的 `logout_guard`（`hooks` / `blocked` / `log`），落盘到 app 私有目录的 `qk_guard.log`，故意不写进 `qk_kick.log`：那份是看守「踢线 = 会话已作废，立刻重启」的判据，混进去会让看守反复重启 QQ。

### 踢线之后 · 「补一次干净下线」这条路试过了，默认不走

这一版试过一件事，真机验证之后结论是**有害，已默认关掉**。记在这里，免得以后有人再试一遍。

想法是这样的：`AppRuntime.logout(LogoutReason, boolean)` 里有服务端真正需要的那两句——

```text
logout(reason, true)
    reason != kicked → userLogoutWhenSendState() → IKernelService.offLine(new UnregisterInfo(deviceInfo))
    sendOnlineStatus(Status.offline, Status.online, ...)
    userLogoutReleaseData() → sendBindUinOffline()
    isLogin = false
```

`onKicked*` 整条被 no-op 之后上面这几件一件都不会发生；看守再 force-stop 一次，连进程都是被强杀的。服务端那边这条会话就一直挂着，紧接着同一个号又登进来，看着就像 `RequestMSFForceOffline` 描述的那种「同账号第二个登录实例」。于是模块在拦下踢线后用 `restartProcess` 这个 reason 补发一次（它不属于要拦的那几种，所以走得到内核那条 `offLine`，也不跳登录页）。

真机实测（2026-09-16，0.13.0，用当时的 `POST /v1/internal/offline` 单独触发）：

```text
18:22:45  posted=true；qk_guard.log 记 clean-offline，
          login=true->false（runtime=com.tencent.mobileqq.app.QQAppInterface account=3373167460）
18:22:52  /healthz online=false，群消息停止入库（近 30s 0 条）
18:24:53  看守按 online=false 连续 3 轮重启 QQ
18:25:22  online=true，消息恢复（近 30s 2 → 16 → 17 条）
```

第一次看着挺好，又走一轮，第二次就没回来：

```text
18:36:43  看守再次重启之后
18:40      仍 online=false，群消息 0 条
18:42      files/user/u_3373167460_t 变成 u_3373167460_f（账号被摘出已登录列表）
           把 _f 改回 _t 再重启，20 秒看一次看满 3 分钟：online 仍是 false
           topResumedActivity = com.tencent.mobileqq/.activity.LoginActivity
```

也就是说：`logout(restartProcess, true)` 会把登录票据一起放掉、账号从已登录列表里摘掉，**此后连自动登录都回不来，只能手动登一次**。它把「被拦下的踢线」变成了「必须重新登录」，正是这个模块一直在防的事。所以：

- `clean_offline_on_kick` 默认 **false**；本机看守的 `QQ_REVIVE_OFFLINE_FIRST` 默认 **0**，重启前不再调它
- `AntiDetect.cleanOffline` 的兜底调用与 `/healthz` 的 `clean_offline` 计数留着；`POST /v1/internal/offline` 这个动作在 0.17.0 随那批内核接口一起撤掉了——它本来就是「确认凭据已经没救」时才用的，而模块里没有哪条路会自己走到这一步
- 顺带记一条待查：`MainService$MyErrorHandler.onKickedInternal` 里那条 `expired` 分支（reason `LogoutReason.expired` → `logout(expired, true)` → `KICK_TO_LOGIN`）**没有被拦**。按这次的结论，它落地同样是「账号被登出、票据被放掉、停在登录页」，而文档一直写着「expired 时 QQ 自己会重登」——这个假设在本机没成立过。要动它得单独验证，别顺手改。0.13.2 给这条路加了只观测的钩子（`token_expired` / `allowed_logout`，见上面那节），下一次事件就能判定它带的是哪个 SSO 错误码、属于哪一支。

### 踢线之后 · `/healthz` 的 online 认 AppRuntime 的登录态

这一条是上面那次试验的副产品，留下来了。`AppRuntime` 已经登出时，内核那个 session 对象短期还是活的、`getCurrentUin()` 也还回得出号，MSF 上游连接也没断（实测 `upstream_links` 仍是 1）。旧版 `QQClient.isOnline()` 只看后面这几样，于是账号已经下线了它还一直报 `online=true`、消息却一条不进——看守三条判据一条都不会触发，只能等人发现。

0.13.0 起 `isOnline()` 多问一句 `AppRuntime.isLogin()`，取不到运行时或调不通时不下结论（fail-open：宁可晚重启一次，也不要因为一次反射失败把 QQ 反复重启）。另外 `appRuntime()` 改成先问 `peekAppRuntime()`、问不到才退回 `mAppRuntime` 字段——实测那个字段在重登/切号期间会指着上一个对象，拿它调 `logout()` 会静默空转（日志里记成 `login=true->true`，什么都没发生）。

### 踢线之后 · 重启由看守做，而且有预算

这一层 0.13.0 起改过，理由在实测里：

- 09-16 11:19→13:09，看守 force-stop 了 QQ 约 34 次，每 3~4 分钟一次。那段时间 QQ 正在登录窗口里（force-stop 之后登上去要几分钟），于是每轮都把它掐断重来，永远登不完。计数来自 `/data/adb/satori-qq/qq-revive.log` 的 `restart:` 行。
- 旧版的宽限期看 `main_age`（`/proc/<pid>/stat` 的 starttime）。算法本身没错——改完这天实测它与 `ps -o etime` 的 ELAPSED 对得上——但风暴里它每轮都报 518s / 578s / 638s 这种值，也就是它取到的进程不是刚拉起来的那个（同一时刻机内确实有两个名字对得上的进程），于是 `GRACE=300` 保护不到登录窗口。所以新版宽限期不看它，改看看守自己写的 `qq-revive.state`，`main_age` 只留在日志里做参考。

新版的三条：

| 判据 | 动作 |
| --- | --- |
| `qk_kick.log` 增行 | 记一行，等 `AFTER_KICK_WAIT`（默认 120s）再按预算决定是否重启 |
| `online=false` 连续 `LOGOUT_LIMIT` 轮（默认 3） | 按预算重启，靠自动登录登回来 |
| `online` 且 MSF 无上游连接连续 `STALE_LIMIT` 轮（默认 5） | 按预算重启 |

预算：两次重启间隔至少 `MIN_RESTART_GAP`（默认 600s），任何 1 小时内最多 `MAX_RESTARTS_PER_HOUR`（默认 3）次；超预算只记一行 `skip: ... budget N/N in 1h`，不动 QQ。（`QQ_REVIVE_OFFLINE_FIRST` 能在重启前请模块补一次干净下线，默认 0，理由见上一节。）

再有一条停手规则：连续 `FAIL_LIMIT`（默认 2）次重启都没换来 `online=true`，就看守记一行 `giveup` 并停止自动重启。那种情形不是「会话作废、自动登录能救」，多半是服务端要求重新验证、只能人工登录；继续重启只会变成一串没人需要的登录尝试。账号自己回到在线、或 `qk_kick.log` 再涨一行（服务端又开始跟这个客户端打交道）时，这条状态自动清掉。`--check` 里能直接看到 `giveup:` 与连续失败次数。

这条状态**落盘**在 `qq-revive.flags`（`giveup=` / `failures=` 两行，0600）。不落盘就没有意义：看守本身由 `service.d/98-qq-revive.sh` 在开机时拉起，重启一次内存里的计数就归零，「连续两次就停手」会退化成「每 10 分钟再试一次，永远试下去」。

#### 看守现在的判据一览（2026-09-16 深夜定稿）

| 看到什么 | 做什么 |
| --- | --- |
| `/healthz` 无响应，且进程还在 | 先**打到前台解冻**（不 force-stop、不吃重启预算）。窗口内连续 `THAW_FAILS`(2) 次失败就动手，不必等 `OFFLINE_LIMIT`(5)；连续 `THAW_LIMIT`(3) 次解冻无效才升级到重启 |
| `/healthz` 无响应，进程不在 | 按预算重启 |
| `qk_kick.log` 增行 / `blocked_kicks` 增 | 等 `AFTER_KICK_WAIT`(120s) 再按预算重启 |
| `online=false`，但 `qk_guard.log` **刚写过且尾部有 `token-expired`** | **不重启**：服务端已把会话判死，重启救不回来，只会让新进程拿着死凭据锤一遍服务端（21:43:51 重启后 13 条命令全被拒）。记一行「等人工」 |
| `online=false`，没有上面那个信号 | 按预算重启（这种是自动登录能救的情况，18:25 验过） |
| `online=true` 但 MSF 无上游连接 | 连续 `STALE_LIMIT`(5) 轮后按预算重启 |

时间参数：`INTERVAL=60`、`RECOVER_WAIT=90`、`GRACE=300`、`RECOVER_CHECK=300`（重启后给这么久才判失败）、`MIN_RESTART_GAP=600`、`MAX_RESTARTS_PER_HOUR=3`。

两条容易踩的坑（都是实测来的）：

- `pgrep` 的模式一律锚定成 `^包名`。不锚的话它会匹配到任何命令行里提到包名的进程（诊断用的 shell、`monkey`/`am` 的临时进程），`kill -9` 兜底会误杀它们 —— 干跑时实测误杀了发起测试的那个 shell
- 一次重启后要给足登录时间（`RECOVER_CHECK`）才判失败。21:06:39 重启、21:06:51 就 `giveup`（12 秒），而账号 21:10:22 自己回来了

恢复时间因此有了上限，也有了代价：刚重启过的那次故障要等满 `MIN_RESTART_GAP` 才动手，最坏情况是 `LOGOUT_LIMIT × INTERVAL + GRACE` 约 8 分钟，其中不含被冷却推迟的部分（2026-09-16 实测有一次被 `cooldown 137s` 推迟到第 12 分钟）。判据本身每轮都要重新数满 `LOGOUT_LIMIT`。要更快就把 `QQ_REVIVE_MIN_RESTART_GAP` 调小，代价是重启更密。

这套预算的实测（A/B 干跑，`am` / `monkey` / `/healthz` 全换成桩，同一事件序列跑 90 秒）：旧版 force-stop 8 次，新版 3 次并开始记 `budget 3/3`。

排障纪律照旧：不要反复 force-stop。每强停再拉起一次，QQ 都要重新握手、重新上报设备信息、重新做一次登录。装完新版本重启一次是必要的，其余反复重启没有收益。

这一层能保证的是盘上那两处状态没被改坏（账号留在已登录列表、自动登录开关没被关成手动），计数在 `login_state.kept` / `auto_login_kept` / `qk_guard.log`。不能保证「被踢之后本机自己登回来」，本机重新上线主要是用户自己手动登录的。所以判「这套机制有没有生效」只看那几个计数，不要拿「online 又变 true 了」当判据，那个时间点可能是人做的动作。要证的「踢线后能自动重登」这条链，目前未验证。

一个已知副作用：善后期内（被拦下踢线后的 15 分钟）用户按退出登录，QQ 的账号标记可能已经被顶成 `_t`，于是下次启动会自己登回来，用户得再退一次（那时已在窗口之外）。窗口很短，且比「被踢之后退不出来、登不回去」轻，不做额外处理。

### 下一个可做的实验（唯一一个还没试过的客户端侧变量）

踢线频率是本机唯一还没动过的量。客户端的可控变量只剩一个：丢弃名单里那条 `OidbSvcTrpcTcp.0x9cdf_`。它与 `libMSFKernel` 共用（另外四条 `0x9c00/0x9c01/0x9c02/0x9c0c` 只出现在 `libfekit`），命令表里挨着 `wtlogin.log_report`。丢它的理由是把 QSec_Channel 上报器压住，而它同时挂在 MSF 内核那条线上——如果服务端把「这条上报缺失」当成设备校验没做完，丢它本身就可能换来一次强下线。这一条**方向不明，只能量，不能推理**。

做法（可回滚）：把 `AntiDetect.FEKIT_CHANNEL_REPORT_CMDS` 里的 `"OidbSvcTrpcTcp.0x9cdf_"` 去掉，只留另外四条，跑 3~7 天，比 `qk_kick.log` 的日均行数；同时看 `qk_env_maps_*.json` 的 `reports_dropped` 里 0x9cdf 那一项是否真的归零（确认改动生效）。对照期取改动前的同期天数。这个 A/B 只有一台设备，只能看出「差得很多」与「看不出差别」，看不出小效应；结论按「日均踢线次数是否减半以上」判。

### 进程被冻住（不是踢线，也不是模块坏了）

2026-09-16 20:31 实测。表现和踢线很像（`/healthz` 不回、消息不进），但处置完全不同，所以判据要分开：

```text
端口 3001 仍在 LISTEN（pid 是 QQ 主进程）
curl 能连上、5 秒超时不出数据
/proc/<pid>/wchan = do_freezer_trap
/sys/fs/cgroup/apps/uid_10420/cgroup.freeze = 1      ← 按 uid 冻
/sys/fs/cgroup/apps/uid_10420/pid_<pid>/cgroup.freeze = 0  ← 逐 pid 反而是 0
dumpsys activity services com.tencent.mobileqq → QQDataSyncService isForeground=true
dumpsys notification → 模块的常驻通知在（channel satori-qq-status，带 FOREGROUND_SERVICE）
```

冻它的是 **ColorOS 的 OplusHansManager**，按 uid 写 cgroup，而且它自己的日志同一行写着 `mForegroundCnt=0 isFg=false`——**前台服务不在它的判据里**。所以「常驻通知 + FGS + WakeLock + 电池白名单」四张牌同时成立也照样被冻，别把 FGS 当免冻牌。AOSP 的 CachedAppOptimizer 可以排除（它只冻逐 pid 的 cached 进程，而 QQ 当时 `curProcState=4`、`cached=false`，events 里也没有针对这个 uid 的 `am_freeze`）。Doze 与息屏也排除了（`mState=ACTIVE`、`mScreenOn=true`、`mCharging=true`）。

而且**不是持续冻**：`:MSF` 每约 5 分钟被 `startmsf` 广播唤醒一次，窗口内 `freeze=0`、`/healthz` 直接返 200，窗口一过又被冻。所以抓现场要看那个窗口，别只测一次就下结论。

处置：

- 打到前台就能解冻（实测 20:47:17 触发、20:48 起 `/healthz` 恢复），**不需要 force-stop**——强停会多一次重新登录，也挡不住下一次冻结。本机看守的 `thaw_qq()` 走的就是这条，独立于重启预算
- 判据别用 `oom_score_adj=200`：200 是前台服务/PERCEPTIBLE 档，cached 从 900 起，被冻时才抬到 1001。用 `wchan=do_freezer_trap` + `uid_<uid>/cgroup.freeze=1`
- 判「模块的常驻通知在不在」也不要 `dumpsys notification | grep 包名`（dump 会截断，20:32 就这么误判过一次），看 `logcat -b events` 里的 `notification_enqueue` / `notification_canceled` 序列

治本只能动 ColorOS 的后台策略（本机没有可读的 settings key，只能对着 Hans 的 `freeze uid: ... scene: |miniMultiWindow|StrictMode-3|LcdOn` 那行日志试）。

### 会话被判死那条路（`expired`）：不拦，以及被标记之后的边界

2026-09-16 晚拿到两类真机样本，把这条路的性质定下来了（`qk_guard.log` 的 `token-expired` / `allowed-logout` 行）：

```text
20:24:46（踢线后 0 秒）token-expired ssoErr=-10003 branch=expired
         svcCmd=SafeCenterSvr.CMD_FACE2FACE_FLAG_REQ   ×3
20:52:40（踢线后 1673s）token-expired ssoErr=-10003 branch=expired
         svcCmd=OidbSvcTrpcTcp.0x9067_202
         msg=身份验证失败，请你重新登录。(w21)
21:08:39（踢线后 2632s）同一秒四条命令全部回同一个错：
         trpc.msg.register_proxy.RegisterProxy.SsoInfoSync
         OidbSvcTrpcTcp.0xfe1_2
         trpc.rdelivery.config_pull_server.ConfigPullServer.SsoPullV3
         TianShu.GetAds
         msg=身份验证失败，请你重新登录。(w21)
```

读法：**不是某一个请求失败，是整个会话被判死**——QQ 从手上任意一个回包得知（连广告请求都被同一个理由拒）。所以 `expired` 是**合法信号**，拦它等于让 QQ 抱着死票据说自己在线上（僵尸），没有任何好处。**不拦。**

三个已知缺口的处置：

| 缺口 | 处置 |
| --- | --- |
| 晚到的 `expired`（20:52 那次 1673s）出了 15 分钟善后期窗口，账号标记被摘成 `_f`，下一次登录不再是一键 | 0.13.4 起把「会话被判死」也当善后锚点（`sessionDeadMs`），窗口长度不变；21:08 那次（2632s）实测 `kept=4`、标记保住，对照成立 |
| `by=` 在 token-expired 支取不到（只给 uid-fail 打了标记） | 补 `markCaller("token-expired")`；21:08 起日志里是 `by=token-expired` |
| 反复重登会把服务端推向「要求短信验证」 | 客户端没有解药：21:10 那次一键登录成功后立刻被判死，服务端要求短信验证，而本地账号标记全程是 `_t`（一次都没丢）——**这一步是服务端的要求，不是本机凭据坏了** |

**所以被标记之后的正确姿势是「别再添信号」**：让它一直在线、不主动强停、不反复重登；看守那边正好也只做「先解冻（不产生新登录）」，重启有预算且连续两次没换回在线就 `giveup` 停手。这一层的边界写清楚：模块能保住**盘上**的登录态（账号标记、自动登录开关），保不住**服务端**那侧的会话判定。

#### `ssoErr=-10003` 到底是什么（2026-09-16 反编译核实）

`-10003` = **`com.tencent.qphone.base.BaseConstants.SSO_CODE_INVALID_D2`**，意思是「**D2 凭据被判无效**」，属服务端错误码命名空间（同表挨着 `-10001` D2 过期、`-10004` D2KEY 不存在、`-10005` D2 缺失；还有 `-10104`/`-10105` 令客户端杀进程这种只可能由服务端下发的指令）。链路是：

```text
服务端回 SSO 码 -10003
  → MSF 的 msf.core.t.handleSsoFailCode 把它归入票据类：getAccountCenter().p(uin) 摘账号、
     businessFailCode 改写成 2001(CODE_NO_LOGIN)、原始码塞进属性 attr_sso_error_code、向各进程广播
  → App 侧 msf.sdk.g.e（classes5.dex）只按 businessFailCode 分支：2001 → onUserTokenExpired；
     2012/2013/2008/2009/2011/2014/2903/3003 各有专调
  → 我们的 token-expired 行读的就是那个属性（不是 businessFailCode —— 它在 2001 上没有信息量）
```

三条由此定下来的口径：

- **客户端对 `-10003` 没有自救路径**：MSF 不重试、不换票（唯一带「重载 D2」的分支挂在 `-10005` 上）。所以「拦住 `expired` 等票据自己回来」是死路，本地拦截改不了服务端对 D2 的裁决
- **`SafeCenterSvr.CMD_FACE2FACE_FLAG_REQ` 是体温计不是病因**：全 APK 只有一个发送点（App 启动时配置步骤 `ConfigHandler.R2()`，条件是换账号或距上次成功 >1 天），与踢线路径没有任何调用关系。同秒出现 3 条是**三个回包**（`ProtoReqManagerImpl.sendProtoReq` 一次预建 `tryCount=9` 个重试、预调度 0/160/320s 三个，`2901` 回包还会立刻补发一个）——所以 `token_expired.count` 会被重发扇出放大，别当成「三次独立事件」
- **别把 `-10003` 与 `MsfSdkUtils.MODE_ERR_MANU`（也是 -10003）混为一谈**，也**不要拿 `-10005` 的行为类推**到它（只有 `-10005` 会先试 reload D2）

#### 几个会把人带偏的读法（都踩过）

- **日志行号不可信，只用类名/方法名定位**：`msf.sdk.g.e` 的调用点在本地 dex 副本里映到第 48 行而日志报 31；`MyErrorHandler.onUserTokenExpired` 在本地副本里 `debug_info_off=0`（没有行号）而日志报 139
- **`login_state.kept` 是进程内的，QQ 重启就归零**：别把 `kept=0` 当成「守卫没生效」（21:08 那次实际写了 4 行 `kept-login-state`，但那是上一个进程）
- **`online_since` 不能当恢复时刻用**：实测看守 21:10:22 记 `recovered`，而 `/healthz` 的 `online_since` 是 21:26:09
- **僵尸态（`online=true` 但 `upstream_links=0`）实测存在且能持续 20 分钟**（20:30:22→20:50:50），期间一条消息都到不了。任何让 `AppRuntime.isLogin()` 保持 true 的拦截都会扩大这个盲区，这也是「不拦 `expired`」的一条硬理由
- **看守要给的判定时间**：一次重启后不能立刻定罪。实测 21:06:39 重启、21:06:51 就判失败停手（12 秒），而账号 21:10:22 自己回来了 —— 所以 `RECOVER_CHECK`（默认 300s）是必要的

### 踢线成因 · 环境检测还是接口把会话打废

- **环境检测**：设备风险被打分，服务端主动下发强制下线
- **接口把会话打废**：某个请求回来的错误码让客户端认定登录票据失效，于是走「刷新票据失败 → 踢回登录页」。QQ 只在少数几个码上这么做：`login.ntlogin.ao.f(int, String)` 的 140022014 / 140022015 / 140022016，以及 `MainService$MyErrorHandler.onUserTokenExpired` 的 `ssoErrorCode` 为 -10135 或 10136

两者用三个字段分开：

| 看哪里 | 环境检测 | 接口打废会话 |
| --- | --- | --- |
| `last_kick_source` | `msf-kick-entry` / `nt-kick` / `msf-kick` | `ticket-refresh` |
| `last_kick` 的 `sigKick` | 0（普通强踢）或 1（带签名数据的安全强踢） | — |
| `sso.session_errors` | 保持 0 | 涨 |

`sso.session_errors` 是模块自己发的 SSO 请求（`PacketSvc` 的 OIDB 与裸 trpc）撞上那组码的次数，判定在 `PacketSvc.sessionFamilyCode`，有单测钉住这张表（多认一个码会把业务失败误报成会话失效，少认一个码就分不出来了）。原文落盘 `qk_sso.log`（app 私有目录 0600，超 64KB 只留尾部 32KB），时间戳可以直接和 `qk_kick.log` 对。超时只计数不落盘，网络慢时那是噪声。这条只观测，不改任何行为。真机实测（2026-09-15，0.8.9.46）全程为 0，模块自己的调用没撞上会话类错误码。

### 模块在盘上留下的文件

| 文件 | 位置 | 原因 |
| --- | --- | --- |
| `qk_env_*.json` 自检 | `/data/data/com.tencent.mobileqq/files`（0600） | 字段名直接写着模块做了什么。0.8.9.39 之前写在外部 `Android/data`，那里有绕过存储沙箱的枚举手法；升级后首次写盘会删掉旧位置的同名文件 |
| 看守日志与 pid | `/data/adb/satori-qq/`（0600） | 文件名与内容能反推模块。0.8.9.39 之前在 `/data/local/tmp`（0771，libfekit 二进制里带着这个路径字符串） |
| `satori-last-send.txt`、`satori-history.txt` | 只在 `verbose_logs=true` 时写 | 逐次 I/O 与残留；诊断信息在 logcat 的 `Q.Kernel` 里仍然有 |
| `qk_kick.log` 踢线记录 | 同 `files/`（0600，超过 64KB 只留尾部 32KB） | 踢线原文（入口、reason、`kickType`、`sigKick`、`seqno`、`sigLen`、`sameDevice`、标题、正文、`up=<秒>`）。要看守在进程重启后仍能判断「刚被踢过」，所以落盘而不是只放内存。`up=` 是这次登录活了多久，用来分「周期性（票据或会话寿命）」与「事件驱动（行为打分）」 |
| `qk_guard.log` 善后动作 | 同上目录（0600，同口径截断） | 被拦下的登出、被顶回去的摘账号、补发的干净下线（`clean-offline`）、自愈修回来的东西。不与 `qk_kick.log` 合并，那份是看守的重启判据 |
| `qk_sso.log` 模块自己 SSO 请求的失败 | 同上目录（0600，同口径截断） | 分开「环境检测」与「接口把会话打废」两种踢线成因，要与 `qk_kick.log` 对时间 |

这些 `qk_*` 名字对检测库不可见：`getdents64` / `readdir` 的条目过滤按 `qk_` 前缀去掉。`satori-last-send.txt` / `satori-history.txt` 走通用黑名单（名字里有 `satori`），`verbose_logs` 关着时本来也不会生成。

配置文件 `satori-qq.json` 仍留在外部目录：它要能被人直接编辑，这是必要暴露；换到只有 root 能改的路径，维护成本高过收益。

## 与公开检测器逐项对照

对照 [Duck Detector](https://github.com/eltavine/Duck-Detector-Refactoring) 的源码逐项核过，只挑在目标进程内部成立的检查（也就是 QQ 自己可以照抄的那部分）。

挡得住的，都是「靠读文本、认名字」这一类：

| 检测方做的事 | 模块的对策 |
| --- | --- |
| `maps` / `heap` 里找 lsposed、libxposed、xposedbridge、lsplant、lspatch 等关键字 | 模块的 `.so` 不带自己的路径，Java 侧类名不进 maps；`BLOCK` 表覆盖这些词，检测库读到的 maps 行被丢掉 |
| 认模块自己的路径或库名（`libmapshide.so`、`/data/adb/...`、模块包名） | GOT 接管 `open` / `read` / `readlink` / `getdents64` / `dl_iterate_phdr`，命中即 `ENOENT` 或从列表里去掉 |
| `TracerPid`、`/proc/*/task/*/comm` 里的 zygisk / magisk / riru | 模块线程名是 `pool-N-thread-M`；`status` 里的 `Seccomp_filters` / `NoNewPrivs` 被改写 |
| 环境变量 `TMP_PATH` 带 `/data/adb` + zygisk | `scrub_environ` 把命中黑名单的变量从本进程环境里删掉 |
| 内核关键字（`kallsyms` / `modules` / `uname` 里的 ksu_ / apatch / magisk） | 本机内核没这些字样；即便有，命中 `BLOCK` 的行也会被过滤 |
| root 属性残留、`/data/local/tmp` 里的临时提权产物 | `prop_denied` 直接吞掉这些读；看守日志已迁出 `/data/local/tmp` |
| 属性四源交叉（reflection、`getprop` 子进程、`System.getProperty`、native `__system_property_get`） | 只在配置了假值时改写，且 Java 与 native 两条路径给同一个值 |
| 可执行映射里出现第二个 `jit-cache` memfd | 模块的 memfd 改名为 `dalvik-jit-code-cache`，ART 自己的 `jit-cache` 仍是唯一一个 |
| 用 `dladdr` 反查函数地址属于哪个模块 | `my_dladdr` 把模块自己的名字换成 `[anon:dalvik-jit-code-cache]` |
| 数 `/proc/<pid>/mem` 里的 dalvik 堆窗口找关键字 | 0.8.9.39 起 `/proc/*/mem`、`/proc/*/pagemap`、`/proc/kcore` 直接 `ENOENT`（偏移读没法行过滤） |
| 枚举 `/proc/self/fd`、`/proc/self/map_files` 逐个 `readlink` | `readlink` / `readlinkat` 的返回值命中 `BLOCK` 就回 `ENOENT`；`fdinfo`（`name:` 行）与 `numa_maps`（`file=` 行）这两条等价的文本路走同一张表 |
| 读 `/proc/self/maps` 数 `/memfd:` 映射，或把 `dl_iterate_phdr` 与 maps 的条目对上 | 模块那三行带 `/memfd:dalvik-jit-code-cache` 的映射被 `BLOCK` 滤掉；`dl_iterate_phdr` 按加载基址跳过模块自己 |
| 在自己进程内列举 app 私有目录（`getFilesDir().listFiles()`、`readdir(/data/data/<pkg>/files)`）找模块留下的文件 | `getdents64` / `readdir` 的条目名走同一张表：`qk_` 前缀与含 `satori` 的名字都不列出 |
| 用 `RandomAccessFile` / `Files.readAllLines` / `Files.readString` 读 `/proc/self/maps`（turing 那三处的方法体被混淆，读法无法从源码判断） | 四种读法与 `BufferedReader` 共用 `sanitizeProcLine` / `sanitizeProcText`；native 侧同理只认文本行 |
| 在进程内挂 `Runtime.nativeLoad` 的 ArtMethod hook，数每次 SO 加载并上报路径 / md5 / 合法性（SoMonitor） | 拦三个安装点（不装钩子就没有数据），并在 `QQBeaconReport.report` 上兜底丢 `native_monitor*` 事件 |
| 用 QQ 自己的监控库读 maps（`libnative-memory-library-lib`、`librmonitor_*`、`libmatrix-hook*`、`libshadowhook`、`libthreadsuspend`、`liblogcathook`、`libunusedcodecheck`） | 这七个库一并纳入 GOT 补丁集；它们看到的 maps 与 fekit 看到的是同一份被过滤过的副本 |

挡不住的见下面「挡不住的部分」。

## 诊断口径

自检文件在 `/data/data/com.tencent.mobileqq/files/qk_env_*.json`（0.8.9.39 之前写在外部 `Android/data`，已迁走；root 才能读）。

`envProcessKey` 把除 `:MSF` 之外的进程都记成 `main`。`:qzone`、`:gameservice` 这类子进程与主进程共用同一份 `qk_env_*.json`，后启动的会覆盖先写的，`/healthz` 里的 `maps.patched` 不一定来自主进程。

对齐办法：`qk_env_maps_main.json` 从 0.8.9.37 起也带 `pid` 字段，`/healthz` 与 `/v1/internal/status` 读到 pid 不等于自己时会补一个 `owner` 字段说明这份数据是别的进程写的（例如 `pid 23905 (not this process)`），没有 `owner` 才是本进程的真实数字。`qk_env_main.json` 一直有 `pid`，可与 `ps -A | grep mobileqq` 里的主进程比对。实测过一次误判：文件里是 `patched: 21`、`dlsym: 0`、`loop_ok: 0`，看着像 native 层退化，实际是 `:qzone` 写的；主进程的 libfekit GOT 用 `/proc/<pid>/mem` 逐槽读出来是补全的。核实本进程的真实状态别只信这个文件。

外部的 `scripts/qq-satori-exposure-audit.sh` 取主进程 pid 的 `/proc/<pid>/maps`，不受这份文件影响。它读的是进程外视图，验证不了进程内过滤，所以 `maps_anon_*_excess=1` 这类计数一直存在，不随过滤变化。

人脸那条链路的状态在 `/healthz` 的 `face` 段（0.14.0 起）：`dropped` 是慧眼上报被丢掉的次数，`events` 按事件名分列（`face_detect` / `camera_detect`），`hooks` 是三个钩子的挂载数（`face_report` / `turing_face` / `turing_process`）。计数不落盘，只在主进程里累计——慧眼 SDK 的日志 pid 与 `IdentificationIpcServer` 都在主进程。`enabled=true` 而 `hooks.*=0` 表示这一版没挂上钩子，按 0 处理。

排障时不要反复 force-stop：每强停再拉起一次，QQ 都要重新握手、重新上报设备信息、重新做一次登录。而「服务端认为同一账号出现了第二个登录实例」正是 `KKICKBYMULTIINST` 那类强下线的语义。装完新版本重启一次是必要的，除此之外的反复重启没有收益。看守现在有重启预算（见上面那节），一分钟内的连续故障最多换一次重启。

## 挡不住的部分

这些不在模块里做，列出来是为了知道边界。

- Bootloader 解锁。本机 `ro.boot.verifiedbootstate` 已是 `green`、`ro.boot.flash.locked` 是 `1`，这两个值由 root 方案那一侧的模块改（设备层，不在本模块里）。QQ 自己的库只读 `ro.bootloader` 与 `ro.boot.hardware`，不读这两个值；Duck Detector 之类会读。要在设备层处理，用带 bootloader 伪造的 root 方案
- SELinux。Turing 读 `/sys/fs/selinux/enforce`。native 过滤只覆盖 `/proc`，不覆盖 `/sys`。本机是 enforcing，正常。查看：`getprop ro.boot.selinux; cat /sys/fs/selinux/enforce`
- 内核与挂载命名空间。Magisk、KernelSU、APatch 的挂载点由内核层暴露。模块只在检测库进程内过滤 maps 与 mountinfo，检测方换一条模块没接管的通道，或直接读内核，就绕开了
- ArtMethod 完整性。`libfekit.so` 带 `parse_libart.cpp` 与整套 `art::CheckJNI` 符号，可以对比运行时方法入口与磁盘上的 `libart.so`。Xposed 与 LSPlant 的 ArtMethod 改写不在本模块覆盖范围。核实：`strings -a libfekit.so | grep -E 'CheckJNI|parse_libart'`
- 服务端风控。这一层在客户端拦不住，腾讯仍按历史行为、设备指纹变化与网络环境打分。2026-09-15 那次排查把客户端能看的都看了一遍，没有异常项，所以那两次踢线（`kickType=0` + `sameDevice=0` + 普通强踢）更可能是服务端侧的判断
- 人脸核身的「设备环境异常」判定。2026-09-18 追到根因：服务端 `ret=413`，理由是**本机出不了有效的硬件认证**（TuringFace 要 Key Attestation，本机 `generateKey` 报 `-74 ATTESTATION_KEYS_NOT_PROVISIONED`，链要服务端对着 Google 硬件根校）。改属性/GOT/Java hook 都到不了这一层，换设备或刷回锁定系统才行（见上面那节）
- 进程内内存关键字扫描。检测方读自己进程的堆或栈（`memchr` 扫一段内存），模块引用的 Xposed 类名就在里面；这条路不经过文件，GOT 与 `/proc` 过滤都用不上
- 多后端交叉校验。同一个事实用 libc、裸 syscall、汇编三种方式各读一次再比对，模块只改得了其中 libc 那条。libfekit 现在只用 libc，一旦它照着这个思路改，`/proc` 文本过滤的收益会明显下降

## 复现审计

反编译检测库：

```sh
LIB=$(su -c "ls -d /data/app/~~*/com.tencent.mobileqq-*/lib/arm64")
su -c "cat $LIB/libfekit.so" > /tmp/libfekit.so
llvm-nm -D /tmp/libfekit.so | grep ' U '
strings -a /tmp/libfekit.so | grep -aE '^/(proc|sys)|magisk|lsposed|zygisk|superuser'
```

反编译 Java 检测类：

```sh
~/tools/jadx/bin/jadx --single-class com.tencent.mobileqq.qsec.qsecurity.QSec \
  -d ~/tmpqq/qsecout ~/tmpqq/dex/classes2.dex
~/tools/jadx/bin/jadx --single-class com.tencent.mobileqq.qsec.qsecurity.utils.SocketStatus \
  -d ~/tmpqq/qsecout ~/tmpqq/dex/classes3.dex
```

采集暴露面快照并对比：

```sh
su -c "sh scripts/qq-satori-exposure-audit.sh snapshot post-<版本>"
su -c "sh scripts/qq-satori-exposure-audit.sh latest"
su -c "sh scripts/qq-satori-exposure-audit.sh diff 旧快照 新快照"
```

离线检查：

```sh
./test.sh
```

`test.sh` 跑 JVM 单测后，用 clang 编译 `tests/mapshide-filter-test.c` 并执行，校验黑名单、`/proc` 路径分类、属性改写与风险载荷匹配。该测试用 aarch64 的 `svc` 指令，只能在设备上跑。

## 9.3.65（versionCode 16240）适配核对

2026-09-18 04:21 QQ 自动更新到 **9.3.65 / 16240**（上一版 9.3.60.40970 / 16070）。逐个核过，
**结论是不用改代码**，证据如下。

- **Java 相关包逐类相同**：把两版 dex 里 `com/tencent/mobileqq/{qsec,dt,channel,kick,identification,msf,qqsec}`、
  `com/tencent/{turingcam,turingfd,tfd,could,qmethod,soter}` 与 `mqq/app/MainService` 的类描述符抽出来比，
  都是 **3404 条，无增无减**。所以没有新检测入口，也没有改名。
- **native 只有 libfekit 变了**，其余检测库 md5 全同（libturingxq / libturingmfa / libQSec /
  libmsfbootV2 / libMSFKernel / libYTCommon）。新 libfekit：
  - 导入符号 **232 条逐条一致**（mapshide 补的 GOT 槽一个没少）→ `is_detector_path` 与包装符号不用动；
  - 命令表 **431 条一致**（QSec_Channel 那 5 条 `0x9c00/0x9c01/0x9c02/0x9c0c/0x9cdf_1` 还在）；
  - 字符串集合无新增检测关键字（新增 59 条全是代码字节噪声）。
- **真机装完照常绑**（下面是 **0.15.0 的基线数字**，0.18.0 起以文末那节为准）：
  `hardening=73`、`kick_hook=12`、`logout_guard=5`、`login_state=2`、
  `token_expired=1`、`allowed_logout=3`、`face.hooks={face_report:3,turing_face:3,turing_process:1}`；
  native 自检 `patched=63 / fekit=37 / turingxq=21 / msfbootV2=5 / loop_ok=1`（与 16070 一致）。
- **新增**：`Compat` 多一张「非内核面」表（36 项），把踢线入口、检测面（QSec / ChannelProxy /
  dt 的 O3 派发 / MSF 收发）与人脸链路（慧眼 + TuringFace）的类与方法也纳入静态自检。上面四张表
  只管 `com.tencent.qqnt.kernel*`，而升级时真正静默坏掉的往往是这一批——现在 `/healthz` 的
  `compat.missing` 会直接点名（条目格式 `{类, 方法, 参数个数, 标签}`，参数个数 `-1` 表示只查名字）。
- 这张表第一次跑就抓到一条**表本身的错**：`ChannelProxy.sendMessage`。出站口其实在
  `ChannelProxyExt`（4 参）与 `ChannelManager`（3 参）上，`ChannelProxy` 只有抽象的
  `sendMessageInner`（3 参）——9.3.60 与 9.3.65 都是这个形状，模块的动态发现
  （`ChannelManager.mChannelProxy`）本来就绕开了这一层。条目已按实测形状改掉。
  `POST /v1/internal/compat {"force":true}` 能拿到完整报告（`missing` 会点名到方法）。

## 0.18.0（2026-09-18 晚）：过检测面扩建

用户要求按公开的开源 QQ 过检测实现逐项对照、把本模块的能力拉满。对照对象是
[QQEnhancedBypass](https://github.com/Xalsace/QQEnhancedBypass)（GPL-3.0，含 Java 与 native 两层）与
[QQNTHookBypass](https://github.com/jhl337/QQNTHookBypass)，逐条核到本机 9.3.65 的 dex 与 so 上——
**对照结论是本模块在两层都已是超集**（native 侧它用 ByteHook 全进程 PLT hook，本模块是逐检测库 GOT
补丁 + seccomp + memfd，覆盖面更大），但它与本轮自查暴露了三处真缺口，这一版补齐。

### 补的三处缺口

1. **`com.tencent.gathererga.core.UserInfoImpl` 这个类不存在。** 0.14.0 那批从 QQEnhancedBypass
   抄过来的 Java root 检测名单里有这一条，但全 APK 里的真实类是
   `com.tencent.gathererga.core.internal.provider.impl.UserInfoImpl`，而且它的 `isRooted(e)` 返回
   `com.tencent.gathererga.core.internal.provider.f`（不是 boolean），照抄的 `returnConstant(false)`
   即使命中也只会抛 ClassCastException。那条钩子一直空转。它的判定读的是
   `{"/system/app/Superuser.apk","/sbin/su",…,"/su/bin/su"}` 的 `new File(...).exists()`，
   已被 `File.exists` 覆盖，所以直接删掉那条钩子并在代码里写明原因。
   同一批里真正缺的是 **`com.tencent.camerasdk.avreport.DeviceInfo.isDeviceRooted()`**
   （classes19，返回 boolean，QQEnhancedBypass 的 README 说 9.3.50 已移除，实测 9.3.65 还在）——已补。
2. **SO 加载监控（SoMonitor）整条通道没有覆盖**——见上面「SO 加载监控」一节。这一条是本轮
   自查（读 dex 找新检测入口）发现的，公开的对照项目里没有。
3. **Java 侧 `/proc` 文本过滤只有一种读法。** 原来只接 `BufferedReader.readLine`。9.3.65 里读
   `/proc/self/maps` 的 Java 代码有三处（`com.tencent.turingcam.Stvdh`、`com.tencent.tfd.sdk.wxa.volatile`、
   `com.tencent.turingfd.sdk.xq.Caelum`），方法体被混淆，不能确定用的是哪一种读法。现在把
   `RandomAccessFile.readLine`、`Files.readAllLines`、`Files.readString` 一起接上，四种读法共用
   `sanitizeProcLine` / `sanitizeProcText`。`volatile` 里能直接看到 `java.io.BufferedReader.<init>`。

### native：补丁的库从 8 个扩到 15 个

扫 `qq16240/lib/*.so` 的字符串，**28 个库引用 `/proc/self/maps`**。其中承担检测/监控、且读法走 libc
（`fopen`/`open`/`strstr`，GOT 补得上）的七个已纳入补丁集：`libnative-memory-library-lib`、
`librmonitor_base`/`librmonitor_memory`、`libmatrix-hookcommon`/`libmatrix-traffic`、
`libshadowhook`/`libbugly_shadowhook`、`libthreadsuspend`、`liblogcathook`、`libunusedcodecheck`。
崩溃与符号化那几家（`libBugly_Native`、`libwechatbacktrace`、`libwechatcrash`）**不补**：
它们读 maps 与 `/proc/self/mem` 是为了出崩溃报告，挡住会让报告本身坏掉。网络栈与 v8 那几家
（`libXNet`、`librawquic_jni`、`libv8jni`）也不补，读 maps 的用途不明而改动风险高。

### 真机验收（0.18.0，QQ 9.3.65，2026-09-18 22:00）

- `/healthz`：`version=0.18.0`、`online=true`、`connections=1`（ayjx 在）、
  `compat={ok:true,passed:249,total:249,missing:0}`（240 → 249，新增 9 条见下）、
  `kick_hook=12`、`logout_guard.hooks=5`、`login_state.hooks=2`、
  `face.hooks={face_report:3,turing_face:3,turing_process:1}`、`sso` 全 0。
- `env_report.hooks.hardening` **73 → 88**：+3 是 SoMonitor 的三个安装点，+12 是
  `QQBeaconReport.report` 挂上的 12 个静态重载。
- `env_report.native_monitor`：`{hooks:3, beacon_dropped:0, last:""}`。3 表示三个安装点都替换成功。
- native 自检（父进程）：`patched=112`、`loop_ok=1`、`leak_maps=0`，
  `libs={fekit:37, turingxq:21, turingmfa:0, msfbootV2:5, qsec:0, ckguard:2, wtecdh:0,
  natmem:14, rmonitor:13, matrixhook:0, shadowhook:20, threadsuspend:0, logcathook:0,
  unusedcodecheck:0, other:0}`，**逐库之和 = 112 = patched**（这是本轮修掉的计数 bug 的验收口径）。
  `:MSF` 进程照旧 `patched=42 / fekit=37`。
- 稳定性：`logcat` 里 `Exception in hooker` 计数 **0**；`/data/system/dropbox` 无新增
  `data_app_native_crash`（最上面几条是 09-16 的旧账）；主进程与 `:MSF` 都在跑；
  往测试群 280183116 发一条并撤回，回执正常。
- **修的计数 bug**：`install()` 里逐库清零的循环写死成 `for (i = 0; i < 8; i++)`，而数组已扩到 15，
  于是 8..14 号桶只增不减（Java 侧每秒调一次 `install()`），第一次装完看到的
  `rmonitor=416 / shadowhook=288` 就是这么来的。改成 `reset_lib_patched()`（用 `LIB_SLOTS`），
  并在 `tests/mapshide-filter-test.c` 里钉住「所有桶都被清掉」与「`LIB_NAMES` 项数 == `LIB_SLOTS`」。

### 这一版新加的静态自检条目（Compat 非内核面表 36 → 45 项）

| 条目 | 为什么 |
| --- | --- |
| `NativeMonitorConfigHelper.setupSoLoadHook` (0) | SoMonitor 装配点 |
| `NativeMemoryMonitor.setupSoLoadHook` (3) / `setNativeHookMonitor` (1) | native 侧同名入口与 native hook 回调注册 |
| `QQBeaconReport.report` (-1) | 事件出口（20+ 个重载，只查名字） |
| `DeviceInfo.isDeviceRooted` (0) | 摄像头 SDK 的 root 检测 |
| `LightDeviceUtils.isRooted` (-1) / `ChargeV2Utils.isDeviceRooted` (-1) | 既有的两处 root 检测 |
| `gathererga…UserInfoImpl.isRooted` (1) | 真实类名（写错的那个已从代码里删掉） |
| `oicq.wlogin_sdk.tools.util.isFileExist` (1) | wlogin 的 root 判定（`w` 的字段 `Y` 由它算） |

`Compat` 总数 240 → 249。条目格式与其它表一致：`{类, 方法, 参数个数, 标签}`，`-1` = 只查名字。

### 边界（这一版没有改变的部分）

- 硬件认证（Key Attestation）与 TEE：本机 `-74 ATTESTATION_KEYS_NOT_PROVISIONED`，见「人脸核身」一节，
  客户端改不了。
- ArtMethod 完整性校验、进程内堆/栈内存关键字扫描、多后端 syscall 交叉、SELinux 与内核命名空间：
  仍在「挡不住的部分」里，本版没有动。
- 服务端风控：客户端这边能数能藏的都做了；踢线成因仍以服务端判定为主。
