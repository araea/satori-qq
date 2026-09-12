# 过检测

记录 QQ 在本机自己做了哪些检测、模块挡在哪里、哪些挡不住。结论按真机 QQ 9.3.55 的二进制核实，不照抄 PC 端实现。

## 检测从哪来

QQ 的检测分三条路。

- `libfekit.so`：QSec 的宿主。读 `/proc/self/maps`、`/proc/self/smaps`、`/proc/self/mountinfo`、`/proc/self/cmdline`，也逐个读 `/proc/<pid>/cmdline` 找别的进程。字符串里带 root 管理器黑名单和一条 `cat /proc/mounts | grep magisk`。
- `libturingxq.so` 与 `libturingmfa.so`：Turing 风控。读 `/sys/fs/selinux/enforce`，枚举 `/data/local/bin`、`/data/local/xbin`、`/sbin/`、`/system/xbin/`、`/system/sd/xbin/`，看 `/system/bin/ddexe`、`debuggerd`、`debuggerd64` 与 `/system/etc/install-recovery.sh` 在不在。
- `libmsfbootV2.so`：启动期上报。读 `/data/data/com.tencent.mobileqq/files/imei`，并 `dl_iterate_phdr` 看模块列表。

`libMSFKernel.so` 读 `/proc/loadavg`、`/proc/stat`、`/proc/self/stat`、`/sys/.../scaling_cur_freq`，这些是遥测，不是环境判定，模块不碰它。

Java 侧在 `com.tencent.mobileqq.qsec.qsecurity` 下。`QSec` 的 `detectMethod`、`getXpsInfo`、`execTasks`、`reportLog` 是检测入口；`getSign`、`getLiteSign`、`getSignEntry` 是签名链路，不能动；`getFeKitAttach` 是登录附件，只记录不改。`SocketStatus.checkSocket(name)` 连一个抽象本地 socket，连得上或权限拒绝都返回 1，连不上返回 0，用来探 LSPosed、Zygisk、Shamiko 这类守护进程。`ProcessCheck` 读 `/proc/self/cmdline` 只为取自己的进程名。

上报命令里，`trpc.o3.report.*`、`trpc.o3.mobile_security.*`、`trpc.gc_indust.device_report.*`、`trpc.ilive_cdn.report.*`、`OidbSvc.0xd79` 是环境上报，模块丢弃。`trpc.o3.ecdh_access.*` 是登录密钥交换，必须保留。`wtlogin.device_lock` 是账号设备锁服务，不是环境上报，模块不动它。

## 模块怎么挡

Java 层在 `qq/AntiDetect`，装在每个 QQ 进程：

- QSec 检测入口按返回类型置安全值，`detectMethod` 恒假、`getXpsInfo` 返回空、`execTasks` 与 `reportLog` 返回 0
- `SocketStatus.checkSocket` 命中框架名字时返回 0，其余名字原样放行
- ChannelProxy、ChannelManager、MsfCore 的收发口按命令白名单丢环境上报，并向服务端回一个空成功，避免上报器重试
- Root、Xposed、调试器、模拟器、包管理、堆栈、`Pandora`、`Turing`、MSF 遥测、强制下线处理逐项覆盖
- 设备标识只在配置了假值时改写，多路径保持同一值

Native 层在 `native/mapshide.c`，只对检测库改 GOT，不动其它库：

- 把检测库 import 的 `open`、`openat`、`fopen`、`stat`、`access`、`readlink`、`getdents64`、`readdir`、`syscall`、`__system_property_get` 等换成自己的包装
- 包装里对 `/proc` 的 maps、smaps、mountinfo、status、environ、cmdline、tcp 做行过滤，路径命中黑名单直接返回 `ENOENT`
- 无路径的 RX 映射改名成 `dalvik-jit-code-cache`，模块自己的 `.so` 从 memfd 以 `jit-cache` 载入，maps 里不出现 `com.satori.qq`
- 用 seccomp 给本进程文本段里裸发的 `openat`、`faccessat`、`statx`、`getdents64` 等装 SIGSYS 兜底
- 装完后自己再读一遍 maps、tcp、environ，把 `leak_*` 与 `loop_ok` 落盘自证

清单分两份：`AndroidManifest.xml` 带 Xposed 元数据用于注册，`AndroidManifest.stealth.xml` 不带，启用后覆盖安装 stealth 版本。Duck Detector 一类工具按「安装包的元数据里有 `xposed*` 键」判定模块，stealth 版本没有这些键。

## 0.8.9.33 补的几处

对照本机二进制，0.8.9.32 还剩几条通路。

- 包黑名单少了 `com.koushikdutta.superuser`，路径黑名单少了 `install-recovery.sh`，两者都写在 `libfekit.so` 里
- 检测库 import 的 `sendmsg` 没有接管，Turing 风险数据可以走它出去
- `stat64`、`lstat64`、`fstat`、`fstat64`、`fstatat` 没有接管，检测库用 64 位名字探路径时绕开了 `stat`/`lstat`
- `/proc/<pid>/cmdline` 不在过滤范围，枚举出来的 pid 是数字，目录名过滤挡不住，得按内容过滤
- `libmsfbootV2.so` 没进检测库集合，它 import 的 `dl_iterate_phdr` 与 `open` 没被接管
- Native 侧读 `ro.debuggable`、`ro.kernel.qemu`、`ro.secure` 时返回真值，与 Java 层返回的 0/0/1 不一致

0.8.9.33 逐条补上，并让 `test.sh` 顺带编译运行 `tests/mapshide-filter-test.c`，把 native 过滤器纳入离线检查。

真机实测：

| 项 | 0.8.9.32 | 0.8.9.33 |
| --- | --- | --- |
| 主进程 GOT 补丁数 | 33 | 62 |
| MSF 进程 GOT 补丁数 | 31 | 39 |
| Java 加固 hook 数 | 48 | 49 |
| maps/tcp/environ 泄漏 | 0 | 0 |
| 进程内 maps 里的模块痕迹 | 0 | 0 |

## 挡不住的部分

这些不在模块里做，因为做了要么伤自己，要么反而暴露。列出来是为了知道边界。

- Bootloader 解锁。本机 `ro.boot.verifiedbootstate` 是 `orange`。QQ 自己的库只读 `ro.bootloader` 与 `ro.boot.hardware`，不读这个值，所以模块不伪造它。Duck Detector 会读。单独把 `ro.boot.verifiedbootstate` 改成 `green` 会和 `ro.boot.flash.locked`、vbmeta 状态互相矛盾，跨信号校验时会露。要看当前值：`getprop ro.boot.verifiedbootstate`。要在设备层处理就用带 bootloader 伪造的 root 方案。
- SELinux。Turing 读 `/sys/fs/selinux/enforce`。模块的 native 过滤只覆盖 `/proc`，不覆盖 `/sys`。本机是 enforcing，正常。要看：`getprop ro.boot.selinux; cat /sys/fs/selinux/enforce`。要处理就在设备层保持 enforcing。
- 内核与挂载命名空间。Magisk、KernelSU、APatch 的挂载点由内核层暴露。模块只在检测库进程内过滤 maps 与 mountinfo，检测方若换一条模块没接管的通道，或直接读内核，就绕开了。看差异：在进程内和进程外各读一次 `/proc/self/mountinfo` 对比。
- ArtMethod 完整性。`libfekit.so` 带 `parse_libart.cpp` 与整套 `art::CheckJNI` 符号，理论可以对比运行时方法入口与磁盘上的 `libart.so`。Xposed 与 LSPlant 的 ArtMethod 改写不在本模块覆盖范围。核实：`strings -a libfekit.so | grep -E 'CheckJNI|parse_libart'`。要处理用框架自带的隐藏能力。
- 服务端风控。客户端拦得再干净，腾讯仍按历史行为、设备指纹变化与网络环境打分，这一步不在客户端。

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

`test.sh` 跑 JVM 单测后，用 clang 编译 `tests/mapshide-filter-test.c` 并直接执行，校验黑名单、`/proc` 路径分类、属性改写与风险载荷匹配。该测试靠 aarch64 的 `svc` 指令，只能在设备上跑。
