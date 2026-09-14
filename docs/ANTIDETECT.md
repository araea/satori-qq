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

清单分两份：`AndroidManifest.xml` 带 Xposed 元数据用于注册，`AndroidManifest.stealth.xml` 不带，启用后覆盖安装 stealth 版本。按安装包元数据里有没有 `xposed*` 键判定模块的工具，看到的是 stealth 版本。

真机实测（16070）：主进程 libfekit 的 GOT 逐槽核对全部指向 libmapshide 的包装（`dlsym`/`open`/`fopen`/`getenv`/`readdir`/`freopen` 六个槽都在），MSF 进程 40 个 slot，maps、tcp、environ 泄漏 0。

## 诊断口径

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
