# 过检测

记录 QQ 在本机自己做了哪些检测、模块挡在哪里、哪些挡不住。结论按真机 QQ 9.3.55 的二进制核实。

## 检测从哪来

QQ 的检测分三条路。

- `libfekit.so`：QSec 的宿主。读 `/proc/self/maps`、`/proc/self/smaps`、`/proc/self/mountinfo`、`/proc/self/cmdline`，并逐个读 `/proc/<pid>/cmdline` 找别的进程。字符串里带 root 管理器黑名单，以及 `cat /proc/mounts | grep magisk`。
- `libturingxq.so` 与 `libturingmfa.so`：Turing 风控。读 `/sys/fs/selinux/enforce`，枚举 `/data/local/bin`、`/data/local/xbin`、`/sbin/`、`/system/xbin/`、`/system/sd/xbin/`，看 `/system/bin/ddexe`、`debuggerd`、`debuggerd64` 与 `/system/etc/install-recovery.sh` 是否存在。
- `libmsfbootV2.so`：启动期上报。读 `/data/data/com.tencent.mobileqq/files/imei`，并用 `dl_iterate_phdr` 看模块列表。

`libMSFKernel.so` 读 `/proc/loadavg`、`/proc/stat`、`/proc/self/stat` 与 `/sys/.../scaling_cur_freq`，这些是遥测，不是环境判定，模块不碰它。

Java 侧在 `com.tencent.mobileqq.qsec.qsecurity` 下。`QSec` 的 `detectMethod`、`getXpsInfo`、`execTasks`、`reportLog` 是检测入口；`getSign`、`getLiteSign`、`getSignEntry` 是签名链路，不能动；`getFeKitAttach` 是登录附件，只记录不改。`SocketStatus.checkSocket(name)` 连一个抽象本地 socket，连上或权限被拒返回 1，连不上返回 0，用于探 LSPosed、Zygisk、Shamiko 这类守护进程。`ProcessCheck` 读 `/proc/self/cmdline` 取自己的进程名。

上报命令里，`trpc.o3.report.*`、`trpc.o3.mobile_security.*`、`trpc.gc_indust.device_report.*`、`trpc.ilive_cdn.report.*`、`OidbSvc.0xd79` 是环境上报，模块丢弃。`trpc.o3.ecdh_access.*` 是登录密钥交换，保留。`wtlogin.device_lock` 是账号设备锁服务，不是环境上报，模块不动它。

## 模块怎么挡

Java 层在 `qq/AntiDetect`，装在每个 QQ 进程：

- QSec 检测入口按返回类型置安全值：`detectMethod` 恒假，`getXpsInfo` 返回空，`execTasks` 与 `reportLog` 返回 0
- `SocketStatus.checkSocket` 命中框架名字时返回 0，其余名字原样放行
- ChannelProxy、ChannelManager、MsfCore 的收发口按命令白名单丢环境上报，并向服务端回空成功，避免上报器重试
- Root、Xposed、调试器、模拟器、包管理、堆栈、Pandora、Turing、MSF 遥测与强制下线处理逐项覆盖
- 设备标识只在配置了假值时改写，多路径保持同一值

Native 层在 `native/mapshide.c`，只对检测库改 GOT，不动其它库：

- 命中库：libfekit、libturingxq、libturingmfa、ckguard、wtecdh、libQSec、dandelion、libmsfbootV2
- 接管这些库 import 的 `open`、`openat`、`fopen`、`stat`、`access`、`readlink`、`getdents64`、`readdir`、`syscall`、`__system_property_get` 等符号，换成自己的包装
- 包装里对 `/proc` 的 maps、smaps、mountinfo、status、environ、cmdline、tcp 做行过滤，路径命中黑名单直接返回 `ENOENT`
- 无路径的 RX 映射改名成 `dalvik-jit-code-cache`，模块自己的 `.so` 从 memfd 以 `jit-cache` 载入
- 用 seccomp 对模块自身文本段里的裸 svc 做 TRAP 兜底，覆盖 openat、openat2、faccessat、faccessat2、newfstatat、statx、readlinkat、getdents64
- 装完后自己再读一遍 maps、tcp、environ，把 `leak_*` 与 `loop_ok` 落盘自证

清单分两份：`AndroidManifest.xml` 带 Xposed 元数据用于注册，`AndroidManifest.stealth.xml` 不带，启用后覆盖安装 stealth 版本。按安装包元数据里有没有 `xposed*` 键判定模块的工具，看到的是 stealth 版本。

真机实测：主进程 GOT 补丁 62 个 slot，MSF 进程 39 个，maps、tcp、environ 泄漏 0。

## 诊断口径

`envProcessKey` 把除 `:MSF` 之外的进程都记成 `main`。`:qzone`、`:gameservice` 这类子进程与主进程共用同一份 `qk_env_*.json`，后启动的会覆盖先写的，`/healthz` 里的 `maps.patched` 不一定来自主进程。

对齐办法：`qk_env_main.json` 有 `pid` 字段，与 `ps -A | grep mobileqq` 里的主进程比对；或在重启 QQ 后立刻读一次。`qk_env_maps_main.json` 由 native 层写，没有 pid 字段。外部的 `scripts/qq-satori-exposure-audit.sh` 取主进程 pid 的 `/proc/<pid>/maps`，不受这份文件影响。

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
