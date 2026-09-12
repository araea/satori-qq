## 0.8.9.33

- 按真机 QQ 9.3.55 的检测库核实，补齐剩余的环境检测通路
  - 包与路径黑名单增补 `com.koushikdutta.superuser` 与 `install-recovery.sh`，两者都在 `libfekit.so` 里
  - Native GOT 接管 `sendmsg`，风险数据不能再走散列发送出去
  - Native GOT 接管 `stat64`、`lstat64`、`fstat`、`fstat64`、`fstatat`，用 64 位名字探路径时不再绕开 `stat` 与 `lstat`
  - `/proc/<pid>/cmdline` 按内容过滤，挡住枚举全机进程名找 root 管理器
  - `libmsfbootV2.so` 纳入检测库集合，它 import 的 `dl_iterate_phdr` 与 `open` 随之接管
  - Native 侧读 `ro.debuggable`、`ro.kernel.qemu`、`ro.secure` 与 Java 层返回一致
  - QSec `SocketStatus.checkSocket` 命中 LSPosed、Zygisk、Shamiko 等名字时返回「不存在」，其余名字原样放行
- 真机实测：主进程 GOT 补丁 33 → 62，MSF 进程 31 → 39，Java 加固 hook 48 → 49，maps、tcp、environ 泄漏仍为 0
- `test.sh` 跑完 JVM 单测后，编译并运行 `tests/mapshide-filter-test.c`
- 新增 [`docs/ANTIDETECT.md`](https://github.com/araea/satori-qq/blob/master/docs/ANTIDETECT.md)，记录检测面、模块逐项对应、挡不住的部分与复现命令
- 版本号 0.8.9.33（versionCode 68），沿用同一签名密钥，支持从 0.8.9.32 直接覆盖升级
