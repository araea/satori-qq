# 反检测全栈 · 换机复现 · QQ 升版本

把 Xposed 注入藏到腾讯 QSec 不太容易扫到的程度。目标是「少掉线」，不是「永不踢」。
踢号票据由服务器签发，本地无法作废。操作面以本文为准。

## 原则

- 全栈一起开。下面「证伪」不要再当银弹单开。
- 不改 `QSec.getSign` / `getSignEntry` / `getEstInfo` / `Dandelion.energy` 的行为。
- `getFeKitAttach` 只允许 after-hook **计数**（cmd / 长度 / 错误），不改参数、返回值、异常。
- 不全局 hook ART 或 libc；只打检测库（`libfekit` / `libckguard` / `libturingxq`）的 GOT。
- `trpc.o3.ecdh_access.*`（`SsoSecureAccess` / `SsoEstablishShareKey`）不要拦，登录会坏。
- 能登录就不卸 QQ scope。必须卸时：先 `scope rm` → 密码或 PIN 重登 → 再把全栈打回去。
- 即时健康（WS / `login.get`）≠ 防踢有效。对照窗口按注入后 16–31 分钟量级看踢号，不要看进程活了多久。
- 主线是反检测（少被服务端踢）。协议是 **Satori v1**（Koishi `adapter-satori` → `http://127.0.0.1:3001`）。不扩新 Satori 动作。阶段路线见仓库外 `satori-qq-后续反检测增强方案.md`。

## 层（按依赖顺序）

换一套 root 时，每一层都要对上等价物。缺一层就回到那一层失败时的暴露面。

| 层 | 本机做法 | 换机时 |
| --- | --- | --- |
| 1. Root | KernelSU 系 | Magisk / KernelSU / APatch 均可，要有 Zygisk |
| 2. Zygisk | Zygisk Next，`memory-type anonymous` | 必须能把框架 so 从 maps **路径**里拿掉。没有 anonymous 就回到 vector/zygisk 路径暴露 |
| 3. Xposed | vector（`zygisk_vector`），LSPosed API | LSPosed / vector 都行。**QQ scope 里只能有本模块** |
| 4. 模块表面 | 日志 tag `Q.Kernel` / `Q.Maps`，线程名无 satori/xposed，WS 只绑 127.0.0.1 | 不要把 verbose / XposedBridge 日志默认打开 |
| 5. Java | `detectMethod`→false；`getXpsInfo` replacement；`reportLog`/`execTasks` 阻断；`File.exists/canRead/...`（**不** hook `list`）；`pm` 点查 + **拷贝过滤**已装列表；`adb` settings；Runtime.exec。出站拦 `trpc.o3.report.*` / `mobile_security.*` / `gc_indust.device_report.*`。入站只对上述前缀空成功（QQNTHookBypass）。**禁止** `trpc.o3.*` 通配、禁止全局 `SystemProperties`、禁止 `Class.forName` | 不要拦 `ecdh_access`。0.5.4 通配 + 入站清空导致登录「请求超时」 |
| 6. maps_hide v5.11 | 主进程 **和 MSF**：v5.10 全部 + 去掉路径里的 ZWSP/软连字符后再匹配；检测库 GOT 把 `persist.sys.usb.config` 读成 `mtp`。`loop_ok=1` 当 `dlsym≥1` 且 `leak_maps=0` 且 tcp/env 没有 **正数** 泄漏。登录超时回 0.8.5 | 0.8.8 路径 `/.`。0.8.9 对的是 Duck Detector 报告里的 I/O 项，不是 TEE |
| 7. seccomp | 自身 text 范围外的裸 `openat` / `faccessat` / `stat` / `readlinkat` 等 `SECCOMP_RET_TRAP` | BPF 跳转必须带 `BPF_JMP`（code `0x15`），否则 EINVAL。主进程 `Seccomp_filters` 应比 `:MSF` 多 1，且 `NoNewPrivs=1`。fekit 读 `/proc/self/status` 时会被改写成 filters=1 |
| 8. watchdog | `/data/adb/service.d` 拉起；**1s** 解冻 QQ+MSF 的 uid **和 pid_*** cgroup；`account_kicks` | OEM 冻结策略不同。ColorOS Hans 的 sticky unfreeze **无效**，必须反复写 `cgroup.freeze=0`。进程卡在 `do_freezer_trap` 时 MSF 心跳发不出去，服务端会当掉线踢 |

干净 QQ 自己就有一条无名 RWX（ShadowHook，约 1828 KiB）和 `anon_exec=1`。注入增量看 `maps_anon_exec_excess`，不是「有没有 RWX」。

### 本机已证伪、不要再当银弹

- 只阻断 `QSec.reportLog` 或 `execTasks`：仍会踢。
- Zygisk anonymous 单独开：路径没了，仍会踢。
- maps_hide v2（只滤匿名 RWX、真实 maps 里留着 `libmapshide.so`）：约 10 分钟，短于基线。
- 改签名 JNI / 改 `getFeKitAttach` 返回：登录会坏，不要做。
- 拦 `trpc.o3.ecdh_access.*`：登录 / 签名会坏，不要做。
- 全局 hook `Class.forName`：vector 下递归，QQ 秒崩（`QQCoreBridgeImpl` CNFE）。
- `trpc.o3.*` 通配（只放过 ecdh）+ 入站清空：0.5.4 登录「请求超时」。登录还走其它 o3 cmd。
- 全局 `SystemProperties.get`、`File.list`/`listFiles`、PM `iterator.remove()`、把 `/proc/net/tcp` 当暴露路径、GOT 打 `getdents`/`readdir`：未单独 A/B 前不要开。`dlsym` 已在 0.8.0 单独打；若登录「请求超时」就回退 0.7.0，记入证伪。
- 0.5.6 maps 干净仍会在注入后约 20 分钟真踢（#13，13:38:55）。同进程重登后约 101 分钟无第二票，直至 15:21 主进程 cached 回收（`am_proc_died`，不是踢号）。该 0.5.6 进程整段 `env_report.dropped=0`。
- 阶段 1（入站白名单 + property_find）未能挡住真踢：#14 17:39（0.6.2，启动后 6 分钟）、#15 18:17（0.6.4，25 分钟）、#16 20:10（0.7.0，23 分钟）。主进程 `dropped=0`，MSF 在丢 `device_report` / `SsoReport`。maps 仍干净。0.8.0 起单独 A/B `dlsym`。
- 9.3.55 Channel 路径（jadx 本机 dex）：`ChannelProxyExt.sendMessage(cmd,body,uin,id)` 是抽象的；native/FEKit 打的是 `O3MainProcessChannel$4`（主进程，经 `O3BusinessHandler.P2`，回包 `ChannelManager.onNativeReceive`）和 `msf.core.security.a$a`（MSF，再进 `MsfCore.sendSsoMsg`，回包 `ChannelManager.onReceive`）。Java `ChannelReport` 走 `ChannelManager.sendMessage` → `sendMessageInner`。0.5.7.2 钩这些；拦出站后空成功 ack 仍走 `onNativeReceive`（与 `$4$1$1` 一致）。不要 hook `handleRespMsg`。`trpc.shadow_qq...sso_o3_security_check` 在 dex 里有，未单独 A/B 前不要拦。

主线是反检测（阶段 2：`dlsym`，等自然加载）。协议是 Satori v1，不扩新动作。路线见仓库外 `satori-qq-后续反检测增强方案.md`。

## 思路来源（仍在维护或可对照）

- [QQNTHookBypass](https://github.com/jhl337/QQNTHookBypass)：出站拦 `trpc.o3.report.*` / `mobile_security.*`，入站伪造空成功。本仓库同样拦 `trpc.gc_indust.device_report.*`。**不要**对 `trpc.o3.*` 通配。
- fekit 9.3.55 导入 `dlsym`：可绕过 GOT。0.8.0 只在检测库 GOT 上打这一槽；`my_dlsym` 只把已包装的 open/syscall/… 指回来，不带 getdents。
- turingxq 导入 `__system_property_find`：v5.1 已打，只对 magisk/zygisk 等名字返回空。
- fekit 导入 `getenv`/`freopen`，turingxq 导入 `getenv`/`fdopen`：0.8.3 打检测库 GOT；`MAGISK_VER` 等键返回空。`/proc/self/environ` 按 NUL 过滤。不要全局 hook libc `getenv`。
- `:MSF` 进程自己也加载 `libfekit.so`。v4 只打主进程，MSF 的 GOT 是裸的。

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
   sh /data/adb/modules/zygisk_vector/cli modules enable com.satori.qq
   sh /data/adb/modules/zygisk_vector/cli scope add com.satori.qq com.tencent.mobileqq/0
   sh /data/adb/modules/zygisk_vector/cli scope ls com.satori.qq
   ```

   经典 LSPosed：在 Manager 里启用模块，作用域只勾 QQ。改 db 而不通知 daemon **不会生效**。
5. 配置 `satori-qq.json`（全栈 true）。写 watchdog：

   ```sh
   mkdir -p /data/adb/satori-qq
   cp scripts/qq-satori-watchdog.sh scripts/qq-satori-exposure-audit.sh \
      scripts/qq-satori-coldstart-check.sh scripts/qq-satori-observe.sh /data/adb/satori-qq/
   cp scripts/99-satori-qq-watchdog.sh scripts/98-satori-qq-observe.sh /data/adb/service.d/
   chmod 755 /data/adb/satori-qq/*.sh /data/adb/service.d/99-satori-qq-watchdog.sh \
      /data/adb/service.d/98-satori-qq-observe.sh
   touch /data/adb/satori-qq/watchdog.enabled
   sh /data/adb/satori-qq/qq-satori-watchdog.sh start
   sh /data/adb/satori-qq/qq-satori-observe.sh start
   ```

6. 冷启 QQ，确认：

   ```sh
   logcat -d | grep -E 'Q\.Maps|Q\.Kernel'
   # 期望：MapsHide patched N GOT slots（N>0）；seccomp cloak on 或主进程 Seccomp_filters 比 MSF 多 1
   node tests/ws-health.js          # login/online；看 env_report.dropped / hooks / msf
   sh /data/adb/satori-qq/qq-satori-coldstart-check.sh
   # result=loaded → 进程已吃上已装 APK；waiting-natural-restart → 仍是旧进程
   # 自然重登、watchdog 重启、或 QQ pid 在 15s 内被换掉时，会把 ws-health 追加到 /data/adb/satori-qq/online-health.log
   sh /data/adb/satori-qq/qq-satori-exposure-audit.sh snapshot
   ```

   审计里 `maps_vector=0`、`maps_zygisk=0`、`maps_mapshide=0`、`maps_satori=0`、`anon_exec_excess=0`、`maps_memfd_jit_rx=1`。`maps_fekit` 有几条是 QQ 自己的。`module_version`/`apk_version` 是已装 APK，不是进程里跑的 `login.get` / `internal/version`。`maps_vector_generic` 含 ART `chunk-info vector`（对照 `maps_art_vector`）。
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
sh /data/adb/modules/zygisk_vector/cli scope rm com.satori.qq com.tencent.mobileqq/0

# 关 maps_hide：配置改 false 后冷启
# 关 Zygisk anonymous：
#   /data/adb/modules/zygisksu/bin/zygiskd memory-type default
```
