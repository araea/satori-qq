06 · 隐身
=========

## QSec 与 maps

**为什么。** 注入会在 `/proc/self/maps` 留下 so 路径、匿名可执行段、异常名字。`libfekit` 会读这些，塞进心跳。踢号发生在服务器，不发生在你的 `if` 里。

**怎么用到。** 先减少路径（Zygisk anonymous），再骗检测库看到的那一份 maps（GOT 过滤），再给无名 RX 起一个像 ART jit 的名字，so 自身用 `memfd:jit-cache` 加载。Java 层再挡一批探针。全开；单独开某一项，本机都踢过。

**去学。** 读一份 `/proc/self/maps`。认出路径、权限位（`r-xp`）、匿名映射。知道「干净 QQ 也有一条 RWX」这种基线思维。不要一上来就写过滤。

## GOT 与链接器命名空间

**为什么。** 钩 `open` 的 GOT，才能在检测库走 libc 读 maps 时换一份过滤后的内容。模块的 so 和 `libfekit` 往往不在同一个 linker namespace，`dl_iterate_phdr` 看不见对方——所以要按 maps 里的基址去打，而不是只扫自己的命名空间。

**怎么用到。** `native/mapshide.c`。只打 `fekit` / `ckguard` 的导入槽，不打全局 libc。审计脚本读的是未过滤的真实 maps，数字高不代表过滤失败。

**去学。** 动态链接：符号、GOT、PLT。一篇 ELF 入门加 `readelf -d` / `llvm-objdump -T` 即可。在玩具 so 上改一个 GOT 槽、看见函数走了你的实现，再碰 QQ。全局 hook libc、inline hook 所有 `svc`，不是这个仓库的做法。

## seccomp

**为什么。** 专业检测可以不走 libc，直接 `svc`。GOT 拦不住。seccomp 能在内核门口拦指定系统调用。这是后手，不是第一层。

**怎么用到。** 只对自身代码段范围内的若干 `openat` / `stat` 类调用 `TRAP` 到自己的处理函数。classic BPF 的跳转必须带 `BPF_JMP`，否则内核直接 EINVAL。装没装上：看主进程的 `Seccomp_filters` 是否比 `:MSF` 多一层。

**去学。** 什么是 seccomp、什么是 BPF 过滤器、什么是 `NO_NEW_PRIVS`。`tests/seccomp-filter-test.c` 是最小实验。不要在不懂过滤器的情况下往 QQ 进程里装；装错会把进程锁死。

即时健康（WS 通）不等于防踢有效。对照要看踢号，不要看进程活了多久。详见 `docs/STACK.md`。
