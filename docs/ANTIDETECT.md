# 过检测

记录 QQ 在本机自己做了哪些检测、模块挡在哪里、哪些挡不住。结论按真机 QQ 9.3.55 与 9.3.60.40970（versionCode 16070）的二进制核实。

## 检测从哪来

QQ 的检测分三条路。

- `libfekit.so`：QSec 的宿主。读 `/proc/self/maps`、`/proc/self/smaps`、`/proc/self/mountinfo`、`/proc/self/cmdline`，并逐个读 `/proc/<pid>/cmdline` 找别的进程。字符串里带 root 管理器黑名单，以及 `cat /proc/mounts | grep magisk`。
- `libturingxq.so` 与 `libturingmfa.so`：Turing 风控。读 `/sys/fs/selinux/enforce`，枚举 `/data/local/bin`、`/data/local/xbin`、`/sbin/`、`/system/xbin/`、`/system/sd/xbin/`，看 `/system/bin/ddexe`、`debuggerd`、`debuggerd64` 与 `/system/etc/install-recovery.sh` 是否存在。
- `libmsfbootV2.so`：启动期上报。读 `/data/data/com.tencent.mobileqq/files/imei`，并用 `dl_iterate_phdr` 看模块列表。

`libMSFKernel.so` 读 `/proc/loadavg`、`/proc/stat`、`/proc/self/stat` 与 `/sys/.../scaling_cur_freq`，这些是遥测，不是环境判定，模块不碰它。16070 起它还用 `inotify_init1`/`inotify_add_watch` 盯文件变化，模块没接管。

Java 侧在 `com.tencent.mobileqq.qsec.qsecurity` 下。`QSec` 的 `detectMethod`、`getXpsInfo`、`execTasks`、`reportLog` 是检测入口；`getSign`、`getLiteSign`、`getSignEntry` 是签名链路，不能动；`getFeKitAttach` 是登录附件，只记录不改。`SocketStatus.checkSocket(name)` 连一个抽象本地 socket，连上或权限被拒返回 1，连不上返回 0，用于探 LSPosed、Zygisk、Shamiko 这类守护进程。`ProcessCheck` 读 `/proc/self/cmdline` 取自己的进程名。16070 的 `qsecurity` 包与 16030 逐类相同，没有新增检测入口。

上报命令里，`trpc.o3.report.*`、`trpc.o3.mobile_security.*`、`trpc.gc_indust.device_report.*`、`trpc.ilive_cdn.report.*`、`OidbSvc.0xd79`、`OidbSvcTrpcTcp.0x9c00_*`/`0x9c01_*`/`0x9c02_*`/`0x9c0c_*`/`0x9cdf_*` 是环境上报，模块丢弃。`trpc.o3.ecdh_access.*` 是登录密钥交换，保留。`wtlogin.device_lock` 是账号设备锁服务，不是环境上报，模块不动它。

### 16070 新增的 QSec_Channel 上报器

16030 → 16070 只有 `libfekit.so` 与 `libMSFKernel.so` 变了，turing 系、`libQSec.so`、`libmsfbootV2.so` 的 md5 完全一致。fekit 的新 import 只有 `inet_addr`、`recv`、`send`、`setsockopt`（`send` 已在 GOT 拦截范围内），检测路径与黑名单关键字一条没加。真正的变化在上报：

- 二进制里新增 `channel/src/reporter/{async,delay,high_reliability,super}_reporter.cpp`、`super_reporter_cache.h`，以及 `HighReliabilityReporter loadFromDisk/saveToDisk/report`、`QSec_Channel report retry!,%d`、`QSec_Channel report error! retry 3 times!`、`QSec_Channel cached size:%d`、`sendRequestPB:cmd:%s,datalen:%d`。
- 命令表从 312 条变成 317 条，新增的 5 条是 `OidbSvcTrpcTcp.0x9c00_0`、`0x9c01_0`、`0x9c02_0`、`0x9c0c_0`、`0x9cdf_1`。前四条全 APK 只出现在 libfekit，`0x9cdf_1` 与 libMSFKernel 共用。
- 这套上报器会把报不通的内容落盘、之后重发，重试 3 次。只按旧的 `trpc.o3.report.*` 名单丢包挡不住它，这是 16070 之后「又被检测设备风险」的直接嫌疑。模块把这 5 条一起丢，并向服务端回空成功，避免它继续重试。

`0x9cdf_1` 是这 5 条里唯一与 libMSFKernel 共用的，命令表里挨着 `wtlogin.log_report`，所以它的影响面比另外四条大。真要回滚，配置里把 `block_o3_report` 置 false 即可整体关掉这套丢包逻辑。

## 模块怎么挡

Java 层在 `qq/AntiDetect`，装在每个 QQ 进程：

- QSec 检测入口按返回类型置安全值：`detectMethod` 恒假，`getXpsInfo` 返回空，`execTasks` 与 `reportLog` 返回 0
- `SocketStatus.checkSocket` 命中框架名字时返回 0，其余名字原样放行
- ChannelProxy、ChannelManager、MsfCore 的收发口按命令白名单丢环境上报，并向服务端回空成功，避免上报器重试
- Root、Xposed、调试器、模拟器、包管理、堆栈、Pandora、Turing、MSF 遥测与强制下线处理逐项覆盖
- 设备标识只在配置了假值时改写，多路径保持同一值

### 踢回登录页的三个入口

服务端踢线在客户端有不止一个处理入口，它们互不经过对方。只挡一个，被踢时界面照样退回登录页：

| 入口 | 触发方 | 拦下的方法 | 计数标签 |
| --- | --- | --- | --- |
| `com.tencent.mobileqq.kick.NTKickProcessor` | 内核 `IKickApi` 收到的踢线 | `b(AppRuntime, KickedInfo, LogoutReason)` | `nt-kick` |
| `com.tencent.mobileqq.login.ntlogin.ao` | `NTLoginTicketManager` 刷新登录票据失败，错误码 140022014/140022015/140022016 或 `refreshMethodNeedKick` | `f(int, String)` | `ticket-refresh` |
| `com.tencent.mobileqq.login.api.impl.UidServiceImpl` | 取不到 UID | `kickToLoginPage()` | `uid-fail` |

后两个都会先 `ntTriggerLogout`，再拿 `LoginActivity` 发 `ACTION_KICK_TO_LOGIN`，界面上的表现就是「刚登录就被弹回登录页」。`/healthz` 的 `kick_hook` 是三个入口的 hook 数之和（正常为 3），`last_kick_source` 记下最近一次是哪个入口拦下的，`last_kick` 记下参数（`ticket-refresh` 会带上服务端错误码和原文）。

Native 层在 `native/mapshide.c`，只对检测库改 GOT，不动其它库：

- 命中库：libfekit、libturingxq、libturingmfa、ckguard、wtecdh、libQSec、dandelion、libmsfbootV2
- 接管这些库 import 的 `open`、`openat`、`fopen`、`stat`、`access`、`readlink`、`getdents64`、`readdir`、`syscall`、`__system_property_get` 等符号，换成自己的包装
- 包装里对 `/proc` 的 maps、smaps、mountinfo、status、environ、cmdline、tcp 做行过滤，路径命中黑名单直接返回 `ENOENT`
- 无路径的 RX 映射改名成 `dalvik-jit-code-cache`，模块自己的 `.so` 从 memfd 以 `jit-cache` 载入
- 用 seccomp 对模块自身文本段里的裸 svc 做 TRAP 兜底，覆盖 openat、openat2、faccessat、faccessat2、newfstatat、statx、readlinkat、getdents64
- 装完后自己再读一遍 maps、tcp、environ，把 `leak_*` 与 `loop_ok` 落盘自证

0.8.9.39 起 token 扫描按「符号集合」而不是单个符号接管。libfekit 同时 import `strstr`、`strcasestr`、`memmem`，此前只接管了 `strstr`，而且只在 needle 恰好等于黑名单里的某个词时才返回 NULL：`strstr(maps_line, "com.topjohnwu.magisk-1.2")` 这类更长的 needle 会照常命中。现在三个入口共用一条规则——**needle 里只要出现 `BLOCK` 表中任一条（子串、大小写不敏感），就返回未找到**，判定集与 `path_denied` 完全一致。实测主进程补丁数 63 → 65，MSF 40 → 42。

检测库自己扫字符串的入口不止这三个。libturingxq 还 `regcomp`/`regexec` 一批 POSIX ERE，用在进程名、线程名与路径上：

```text
^/system/xbin/ku[.]sud$   ^daemonsu:   ^kworker/[1-9][0-9]*:[0-9]+$
^kr_worker/…  ^km_worker/…  ^tworker/…  ^tu_worker/…  ^tq_worker/…
^permmgrd$   ^360sguard$   ^/data/data/[[:alnum:]_.-]+/   ^/system/bin/[.]   ^[.]
```

这些正则不走 libc 字符串函数，模块的 `strstr` 家族接管不到。目前靠「别撞上」：模块自己起的线程名是 `pool-N-thread-M`（JVM 默认风格），路径不带 `sator-`/`magisk`/`kworker` 之类字样，所以没有一条能匹配。**改动线程名、临时文件名或模块目录名时要照这份清单核一遍**。

线程名是外部可见的，`/proc/<pid>/task/*/comm` 直接读得到。0.8.9.39 里自检到两处带模块名的线程（`satori-self-send`、`satori-channel-unmute`），0.8.9.40 改成 `pool-8-thread-N` 与 `pool-9-thread-1`；QQ 自己也有若干 `pool-N-thread-M`，这种名字不显眼。**新起线程时不要用模块名**，要能过 `scripts/qq-satori-exposure-audit.sh` 的 `suspicious_thread_names`（它匹配 satori/xposed/vector/zygisk/mapshide）。

logcat 是接受的暴露：Java 侧 `L.e` 与 native 的 `Q.Maps` 用 `Q.` 前缀的标签，消息里会提到 patch 计数与 seccomp。QQ 自己进程写下的日志，自己的进程（以及 root）读得到，拦不掉，而排障要用它；详细数字另外落盘到只 root 可读的 `qk_env_maps_*.json`。同理，常驻通知的渠道名与标题带「Satori」是给人看的，属于必要暴露。

清单分两份：`AndroidManifest.xml` 带 Xposed 元数据用于注册，`AndroidManifest.stealth.xml` 不带，启用后覆盖安装 stealth 版本。按安装包元数据里有没有 `xposed*` 键判定模块的工具，看到的是 stealth 版本。Duck Detector 的 LSPosed 卡片就是按这条查的：同一台机器上它扫出了便签模块与「雹」的 `xposedmodule` 元数据，而已覆盖安装 stealth 变体的本模块不在那张清单里。

真机实测（16070，0.8.9.39）：主进程 libfekit 的 GOT 逐槽核对全部指向 libmapshide 的包装（`dlsym`/`open`/`fopen`/`getenv`/`readdir`/`freopen`/`strcasestr`/`memmem` 等），主进程 65 个 slot、MSF 42 个，maps、tcp、environ 泄漏 0，`loop_ok=1`。

### 模块自己在盘上留下的东西

过检测不只是拦读取，模块自己写下的文件同样是暴露面。0.8.9.39 收了三处：

| 东西 | 原来在哪 | 现在在哪 | 为什么 |
| --- | --- | --- | --- |
| `qk_env_*.json` 自检 | `/storage/emulated/0/Android/data/com.tencent.mobileqq/files` | `/data/data/com.tencent.mobileqq/files`（0600） | 外部存储的 `Android/data` 有绕过存储沙箱的枚举手法，而这份文件的字段名直接写着模块做了什么。升级后首次写盘会把旧位置的同名文件删掉 |
| 看守日志与 pid | `/data/local/tmp`（0771，libfekit 二进制里带着这个路径字符串） | `/data/adb/satori-qq/`（0600，普通应用进不去） | 文件名与内容能反推模块 |
| `satori-last-send.txt`、`satori-history.txt` | 每次发消息、每次查历史都重写 | 只在 `verbose_logs=true` 时写 | 逐次 I/O 与残留；诊断信息在 logcat 的 `Q.Kernel` 里仍然有 |

配置文件 `satori-qq.json` 仍留在外部目录：它要能被人直接编辑，这是必要暴露；换到只有 root 能改的路径，维护成本高过收益。

## 与公开检测器逐项对照

拿 [Duck Detector](https://github.com/eltavine/Duck-Detector-Refactoring) 的源码逐项过了一遍，只挑**在目标进程内部成立**的检查（也就是 QQ 自己可以照抄的那部分），看模块挡不挡得住。

模块挡得住的（都是「靠读文本、认名字」这一类）：

| 检测方做的事 | 模块的对策 |
| --- | --- |
| `maps`/`heap` 里找 lsposed、libxposed、xposedbridge、lsplant、lspatch 等关键字 | 模块的 `.so` 不带自己的路径，Java 侧类名不进 maps；`BLOCK` 表覆盖这些词，检测库读到的 maps 行被丢掉 |
| 认模块自己的路径或库名（`libmapshide.so`、`/data/adb/...`、模块包名） | GOT 接管 `open`/`read`/`readlink`/`getdents64`/`dl_iterate_phdr`，命中即 `ENOENT` 或从列表里去掉 |
| `TracerPid`、`/proc/*/task/*/comm` 里的 zygisk/magisk/riru | 模块线程名是 `pool-N-thread-M`；`status` 里的 `Seccomp_filters`/`NoNewPrivs` 被改写 |
| 环境变量 `TMP_PATH` 带 `/data/adb` + zygisk | `scrub_environ` 把命中黑名单的变量从本进程环境里删掉 |
| 内核关键字（`kallsyms`/`modules`/`uname` 里的 ksu_/apatch/magisk） | 本机内核没这些字样；即便有，命中 `BLOCK` 的行也会被过滤 |
| root 属性残留、`/data/local/tmp` 里的临时提权产物 | `prop_denied` 直接吞掉这些读；看守日志已迁出 `/data/local/tmp` |
| 属性四源交叉（reflection / `getprop` 子进程 / `System.getProperty` / native `__system_property_get`） | 只在配置了假值时改写，且 Java 与 native 两条路径给同一个值 |
| 可执行映射里出现第二个 `jit-cache` memfd（加载器藏在 memfd 的判据） | 模块的 memfd 改名为 `dalvik-jit-code-cache`，ART 自己的 `jit-cache` 仍是唯一一个 |
| 用 `dladdr` 反查函数地址属于哪个模块 | `my_dladdr` 把模块自己的名字换成 `[anon:dalvik-jit-code-cache]` |
| 数 `/proc/<pid>/mem` 里的 dalvik 堆窗口找关键字 | 0.8.9.39 起 `/proc/*/mem`、`/proc/*/pagemap`、`/proc/kcore` 直接 `ENOENT`（偏移读没法行过滤） |

挡不住的，以及为什么：

| 检测方做的事 | 为什么挡不住 |
| --- | --- |
| 自己装 seccomp TRAP 探钩子、`setresuid` 的 SIGKILL 语义、`/dev/pts` 的 uid 与 xattr、KSU supercall 的 ioctl 返回值 | 都是内核侧语义的结果，不是文本；模块只能保证自己不去伪造 |
| 用裸 syscall 或多后端（libc / syscall / asm）交叉读同一个东西 | 裸 svc 不过 GOT，多后端一对比就露出被改写的那一条 |
| 直接扫描自己进程的堆或栈内存找 Xposed 类名 | Xposed 模块必须引用 `de.robv.android.xposed.**`，类名一定在堆里；读内存不是读文件，过滤器管不到 |
| TEE / attestation / bootloader 属性 | 见下面「挡不住的部分」，属设备层 |
| 服务端按行为与设备指纹打分 | 客户端拦得再干净也不影响这一层 |

最后一条里的「堆关键字扫描」值得单独说：本模块在 QQ 进程里是 Xposed 模块，`XposedHelpers`、`XposedBridge` 这些类名天然存在于堆中，任何在**自己进程内**扫内存的检测都能找到。这不是实现缺陷，是这种模块形态的下限。

## 诊断口径

自检文件在 `/data/data/com.tencent.mobileqq/files/qk_env_*.json`（0.8.9.39 之前写在外部 `Android/data`，已迁走；root 才能读，模块自己读自己的）。

`envProcessKey` 把除 `:MSF` 之外的进程都记成 `main`。`:qzone`、`:gameservice` 这类子进程与主进程共用同一份 `qk_env_*.json`，后启动的会覆盖先写的，`/healthz` 里的 `maps.patched` 不一定来自主进程。

对齐办法：`qk_env_maps_main.json` 从 0.8.9.37 起也带 `pid` 字段，`/healthz` 与 `/v1/internal/status` 读到 pid 不等于自己时会补一个 `owner` 字段说明这份数据是别的进程写的（例如 `pid 23905 (not this process)`）。没有 `owner` 才是本进程的真实数字。`qk_env_main.json` 一直有 `pid`，可与 `ps -A | grep mobileqq` 里的主进程比对。

实测过一次误判：文件里是 `patched: 21`、`dlsym: 0`、`loop_ok: 0`，看着像 native 层退化，实际是 `:qzone` 写的；主进程的 libfekit GOT 用 `/proc/<pid>/mem` 逐槽读出来是补全的。核实本进程的真实状态别只信这个文件。

外部的 `scripts/qq-satori-exposure-audit.sh` 取主进程 pid 的 `/proc/<pid>/maps`，不受这份文件影响。

## 挡不住的部分

这些不在模块里做。列出来是为了知道边界。

- Bootloader 解锁。本机 `ro.boot.verifiedbootstate` 是 `orange`。QQ 自己的库只读 `ro.bootloader` 与 `ro.boot.hardware`，不读这个值，模块不伪造它。Duck Detector 会读。单独把它改成 `green` 会与 `ro.boot.flash.locked`、vbmeta 状态矛盾。查看：`getprop ro.boot.verifiedbootstate`。要在设备层处理，用带 bootloader 伪造的 root 方案。
- SELinux。Turing 读 `/sys/fs/selinux/enforce`。模块的 native 过滤只覆盖 `/proc`，不覆盖 `/sys`。本机是 enforcing，正常。查看：`getprop ro.boot.selinux; cat /sys/fs/selinux/enforce`。
- 内核与挂载命名空间。Magisk、KernelSU、APatch 的挂载点由内核层暴露。模块只在检测库进程内过滤 maps 与 mountinfo，检测方换一条模块没接管的通道，或直接读内核，就绕开了。对比：在进程内和进程外各读一次 `/proc/self/mountinfo`。
- ArtMethod 完整性。`libfekit.so` 带 `parse_libart.cpp` 与整套 `art::CheckJNI` 符号，可以对比运行时方法入口与磁盘上的 `libart.so`。Xposed 与 LSPlant 的 ArtMethod 改写不在本模块覆盖范围。核实：`strings -a libfekit.so | grep -E 'CheckJNI|parse_libart'`。
- 服务端风控。客户端拦得再干净，腾讯仍按历史行为、设备指纹变化与网络环境打分。
- 进程内内存关键字扫描。检测方读自己进程的堆/栈（`memchr` 扫一段内存），模块引用的 Xposed 类名就在里面；这条路不经过文件，GOT 与 `/proc` 过滤都用不上。`/proc/*/mem` 那条读法已经堵掉，直接扫内存堵不掉。
- 多后端交叉校验。同一个事实用 libc、裸 syscall、汇编三种方式各读一次再比对，模块只改得了其中 libc 那条。libfekit 现在只用 libc，一旦它照着这个思路改，`/proc` 文本过滤的收益会明显下降。

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
