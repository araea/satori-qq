# 反检测全栈 · 换机复现 · QQ 升版本

把 Xposed 注入藏到腾讯 QSec 不太容易扫到的程度。目标是「少掉线」，不是「永不踢」。
踢号票据由服务器签发，本地无法作废。操作面以本文为准。

## 原则

- 全栈一起开。下面「证伪」不要再当银弹单开。
- 不改 `QSec.getSign` / `getSignEntry` / `getEstInfo` / `Dandelion.energy` 的行为。
- `getFeKitAttach` 只允许 after-hook **计数**（cmd / 长度 / 错误），不改参数、返回值、异常。
- 不全局 hook ART 或 libc；只打检测库（`libfekit` / `libckguard`）的 GOT。
- 能登录就不卸 QQ scope。必须卸时：先 `scope rm` → 密码或 PIN 重登 → 再把全栈打回去。
- 即时健康（WS / `get_login_info`）≠ 防踢有效。对照窗口按注入后 16–31 分钟量级看踢号，不要看进程活了多久。

## 层（按依赖顺序）

换一套 root 时，每一层都要对上等价物。缺一层就回到那一层失败时的暴露面。

| 层 | 本机做法 | 换机时 |
| --- | --- | --- |
| 1. Root | KernelSU 系 | Magisk / KernelSU / APatch 均可，要有 Zygisk |
| 2. Zygisk | Zygisk Next，`memory-type anonymous` | 必须能把框架 so 从 maps **路径**里拿掉。没有 anonymous 就回到 vector/zygisk 路径暴露 |
| 3. Xposed | vector（`zygisk_vector`），LSPosed API | LSPosed / vector 都行。**QQ scope 里只能有本模块** |
| 4. 模块表面 | 日志 tag `Q.Kernel` / `Q.Maps`，线程名无 onebot/xposed，WS 只绑 127.0.0.1 | 不要把 verbose / XposedBridge 日志默认打开 |
| 5. Java | `detectMethod`→false；`getXpsInfo` replacement；`reportLog` 与 `execTasks` 阻断；`File` / `Debug` 探针 | `execTasks` 阻断不能防踢，只是全栈的一部分 |
| 6. maps_hide v4 | 跨 linker ns 打 fekit/ckguard GOT；滤 maps/smaps/mountinfo；包装 `dl_iterate_phdr`；无名 RX 命名为 `[anon:dalvik-jit-code-cache]`；so 从 `/memfd:jit-cache` 加载 | native 必须随 APK 进 QQ 进程。审计读的是未过滤 maps，`maps_mapshide=0` 才说明路径藏住了 |
| 7. seccomp | 自身 text 范围内裸 `openat` / `faccessat` / `stat` / `readlinkat` 等 `SECCOMP_RET_TRAP` | BPF 跳转必须带 `BPF_JMP`（code `0x15`），否则 EINVAL。主进程 `Seccomp_filters` 应比 `:MSF` 多 1，且 `NoNewPrivs=1` |
| 8. watchdog | `/data/adb/service.d` 拉起；每轮 unfreeze QQ+MSF；`account_kicks` | OEM 冻结策略不同。ColorOS Hans 的 sticky unfreeze **无效**，必须每轮 `cmd activity unfreeze` |

干净 QQ 自己就有一条无名 RWX（ShadowHook，约 1828 KiB）和 `anon_exec=1`。注入增量看 `maps_anon_exec_excess`，不是「有没有 RWX」。

### 本机已证伪、不要再当银弹

- 只阻断 `QSec.reportLog` 或 `execTasks`：仍会踢。
- Zygisk anonymous 单独开：路径没了，仍会踢。
- maps_hide v2（只滤匿名 RWX、真实 maps 里留着 `libmapshide.so`）：约 10 分钟，短于基线。
- 改签名 JNI / 改 `getFeKitAttach` 返回：登录会坏，不要做。

## 换 root / 换机

1. **先干净登录 QQ**，确认无注入也能待着。不要带着模块过验证码。
2. 装 Zygisk + Xposed 框架。QQ **不要**进 denylist / umount 名单（要注入就不能藏 root 给 QQ 看）。
3. Zygisk Next：

   ```sh
   /data/adb/modules/zygisksu/bin/zygiskd memory-type anonymous
   ```

   冷启后 `/proc/$(pidof com.tencent.mobileqq)/maps` 里不应再出现 `zygisk_vector`、`libzygisk.so` 路径。回滚：`memory-type default`。
4. 构建并安装模块（见 README）。vector：

   ```sh
   sh /data/adb/modules/zygisk_vector/cli modules enable com.onebot.qq
   sh /data/adb/modules/zygisk_vector/cli scope add com.onebot.qq com.tencent.mobileqq/0
   sh /data/adb/modules/zygisk_vector/cli scope ls com.onebot.qq
   ```

   经典 LSPosed：在 Manager 里启用模块，作用域只勾 QQ。改 db 而不通知 daemon **不会生效**。
5. 配置 `onebot-qq.json`（全栈 true）。写 watchdog：

   ```sh
   mkdir -p /data/adb/onebot-qq
   cp scripts/qq-onebot-watchdog.sh scripts/qq-onebot-exposure-audit.sh /data/adb/onebot-qq/
   cp scripts/99-onebot-qq-watchdog.sh /data/adb/service.d/
   chmod 755 /data/adb/onebot-qq/*.sh /data/adb/service.d/99-onebot-qq-watchdog.sh
   touch /data/adb/onebot-qq/watchdog.enabled
   sh /data/adb/onebot-qq/qq-onebot-watchdog.sh start
   ```

6. 冷启 QQ，确认：

   ```sh
   logcat -d | grep -E 'Q\.Maps|Q\.Kernel'
   # 期望：MapsHide patched N GOT slots（N>0）；seccomp cloak on 或主进程 Seccomp_filters 比 MSF 多 1
   node tests/ws-health.js          # login/online
   sh /data/adb/onebot-qq/qq-onebot-exposure-audit.sh snapshot
   ```

   审计里 `maps_vector=0`、`maps_zygisk=0`、`maps_mapshide=0`、`maps_onebot=0`、`anon_exec_excess=0`、`maps_memfd_jit_rx=1`。`maps_fekit` 有几条是 QQ 自己的。
7. OEM 冻结：ColorOS 把 QQ 写进 `/data/oplus/os/bpm/bpm.xml` persist，watchdog 已做。其它系统改成对应的「后台保活 / 不解冻名单」，不要改 `oom_score_adj`（fekit 会读）。

### 健康 ≠ 踢号

| 现象 | 是不是踢号 |
| --- | --- |
| `ACCOUNT_KICKED` / `KICK_TO_LOGIN` / 前台 `LoginActivity` 且 `account_kicks` +1 | 是 |
| 端口在、WS 超时、log 有 `F enter` / `frozen=true` | 否，冻进程 |
| `am force-stop` / `installPackageLI` / 进程没了 | 否 |
| watchdog `qq_down` 后拉起 | 否 |

## QQ 升版本

新版本必须重新 jadx。不要用 NapCat / Lagrange / QQ.hap / 上一版字段代替。

停注入、干净登录后再装 QQ。对照 [`ARCHITECTURE.md`](ARCHITECTURE.md) 至少核：`CppProxy` 构造、各 `get*Service`、`sendMsg` / `Contact`、`IKernelMsgListener` 全方法、元素字段、`RichMediaElementGetReq`、`SilkCodecWrapper`、`onSendSSORequest` / `onSendSSOReply`、QSec 签名、`libfekit` 是否仍导入 `open` `fopen` `syscall` `dl_iterate_phdr`。

对不上就改桥，不要猜。冷启后看 GOT `patched N`（N>0）、seccomp、`ws-health.js`、发一条群消息。闪退则 `scope rm`。不要在升版本的同一冷启里夹新反检测。

## 回滚

```sh
# 只卸注入，QQ 进程变干净
sh /data/adb/modules/zygisk_vector/cli scope rm com.onebot.qq com.tencent.mobileqq/0

# 关 maps_hide：配置改 false 后冷启
# 关 Zygisk anonymous：
#   /data/adb/modules/zygisksu/bin/zygiskd memory-type default
```
