07 · 手艺
=========

## 无 Android Studio 出包

**为什么。** 这是一个 Xposed 模块 APK，不是给用户点图标的 App。用系统里的 `aapt`、JDK 的 `javac`、r8 的 D8，可以在 Termux 里编出来。Xposed 的桩类只在编译期存在，**不能打进 dex**——框架看见会拒载。

**怎么用到。** `build.sh`。`/sdcard` 在本机是 FUSE，不能拿来构建；要走有完整 POSIX 的路径。keystore 本地生成，不要提交。

**去学。** APK 是 zip：`AndroidManifest.xml`、`classes.dex`、`lib/arm64-v8a/`。javac 的 classpath 与 bootclasspath 不是一回事（lambda 会踩坑）。不必先学完整 Gradle。把 `build.sh` 读懂，比装两遍 Android Studio 有用。

## 进程外守护

**为什么。** 模块不能在 QQ 被杀之后还自己 `monkey` 起来。OEM 还会把后台进程冻住：端口还在，WebSocket 不回。这不是踢号。

**怎么用到。** `scripts/qq-onebot-watchdog.sh` 放进 `service.d`。区分 online / login / 进程没了 / 端口没了。踢号只认系统事件里的账号踢出，加上登录页。冻住就 unfreeze，不要当 crash 重启。

**去学。** shell、`pidof`、`/proc`、`logcat` 过滤。写一个「进程没了就拉起来、登录页不要乱杀」的脚本，比背 watchdog 源码重要。注意：乱重启正在验证的 QQ，会把人锁在登录页。

## 观测

**为什么。** 反检测没有手感，只有快照。maps 计数、seccomp 层数、线程名，都要能留下可对比的一行。

**怎么用到。** `scripts/qq-onebot-exposure-audit.sh`。状态切换时 watchdog 会叫它。你自己也要会读 `/proc/pid/maps` 和 `/proc/pid/status`。

**去学。** 养成「改一处、留一份前后快照」的习惯。没有快照的结论，这个仓库不当作结论。
