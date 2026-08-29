# 反检测 (掉线绕过) 说明 — 请务必读完再判断预期

> **2026-08-29 起这是唯一活跃主线。** 协议面冻结见 `ONEBOT11_SUPPORT.md`。
> **换机复现、层说明、QQ 升版本：`STACK.md`。** 本文是本机证据日记，不要当操作手册逐条重做 A/B。
> 不要 hook `getSign` / 改 `getFeKitAttach` 返回。即时健康 ≠ 防踢有效。能登录就不卸 scope。

## 2026-08-29 07:58：seccomp 胶水已确认（未冷启）

上一包 `send-msg-forward` 的 native 与 tree `libmapshide.so` 同哈希
`e912d3aa73460806f366b5f00a7ee28ed5bc865f2081bd4bdf5ddde1de65f766`（也等于 v4 / video-get-file）。
独立进程 `tests/seccomp-filter-test.c`：缺 `BPF_JMP` → EINVAL 22；带 `BPF_JMP` 与 v4+TSYNC 均 install 成功。

未再冷启。当前 QQ PID **18120**（`/proc` 目录 07:43，约 send-msg-forward 那次）已具备：

- 主进程 `Seccomp=2` `Seccomp_filters=2` `NoNewPrivs=1`
- `:MSF` PID 18282：`filters=1` `NoNewPrivs=0`（MapsHide 只进主进程；多出来的 1 层 + NNP 是胶水证据）
- `maps_memfd_jit_rx=1`，`maps_mapshide=0`，`anon_exec_excess=0`
- `account_kicks=10` 未增加；WS login/online

Java 路径：`Main` → `MapsHide.tryLoad` → memfd `jit-cache` → JNI `install()` → `install_seccomp()`。
logcat 已滚掉，所以用 `/proc` + audit，不靠 `seccomp cloak on` 那行。

**不能**把本 PID 的存活写成防踢有效：native 是跟着协议包上来的，归因混了。即时健康 ≠ 防踢有效。
快照 `exposure-snapshots/20260829-075751.status`（reason=`seccomp-glue-confirm`，`format_version=5`）。
audit 现记 `seccomp_*` / `nonewprivs` / `msf_*` / `maps_memfd_jit_rx`。

## 2026-08-28 21:39：第九次真实踢号（v4 PID 19867）

用户确认下线但仍能登录。watchdog 21:39:14 记了 kicks=9。**能登录就不要卸注入**（误止损已打回）。证据 `evidence/kick-user-20260828-v4-k9/`。

v4 从 20:48 起中间经过 21:21 `qq_down` 和 21:33 `installPackageLI`，到 21:39 踢号，**不能把 33 min 进程存活写成防踢有效**。

21:34 测试群语音点不开：发出的是 MediaCodec AMR（`#!AMR`），本机可点开的语音是 `\x02#!SILK_V3`。发送路径改为 QQ `SilkCodecWrapper.encode`，进下一包。

## 2026-08-28 20:48：maps_hide v4（VMA 命名 + memfd；seccomp 未装上）

用户 20:40 确认被踢并已重登。当时 PID 26942 仍 `login=true`，logcat/events 未见 `ACCOUNT_KICKED`，watchdog 未加计。未卸注入。证据 `evidence/kick-user-20260828-v31/`。

作者要求加强反检测，检测面尽量全 hook，root 能击穿就击穿。**签名路径仍未动**（`getSign` / `getFeKitAttach` 返回）。

v4 已部署 PID **25366**：

- `prctl(PR_SET_VMA_ANON_NAME)` 把 3 条注入无名 RX 命名为 `[anon:dalvik-jit-code-cache]`。真实 maps：`anon_exec_excess=0`（与干净 QQ 对齐）。
- `libmapshide.so` 改为 `/memfd:jit-cache` 加载。`maps_onebot=0` `maps_mapshide=0`。
- 检测库 GOT 扩到 access/faccessat/readlink/readlinkat，共 **12** 槽。
- Java：`File.exists` 对 magisk/ksu/lsposed/`/data/adb`/su 返回 false；`Debug.isDebuggerConnected` false；`startrail.T.ad` 空。
- 进程内 seccomp 拦裸 `svc`：运行中 APK **EINVAL -22，未装上**。根因是 classic BPF 跳转写成了 `BPF_JEQ|BPF_K`（code `0x10`），内核 `seccomp_check_filter` 只认 `BPF_JMP|BPF_JEQ|BPF_K`（code `0x15`）。`SECCOMP_RET_TRAP` 本身可用。树里已改，独立进程 + TSYNC 装上。**未打进当前 APK**（观察窗内不冷启）。VMA 命名后真实 maps 已无注入差量，不要把 seccomp 失败写成 v4 没上场。

即时健康 ≠ 防踢有效。对照窗口仍是注入基线 16–31 min。21:05 快照 `v4-17min` 仍 `anon_exec_excess=0`。PID 25366 存活约 **33 min** 后于 21:21 `qq_down`（无 `ACCOUNT_KICKED`、无新 tombstone、当时 lmkd 报内存充足）。ColorOS **Hans** 会把 QQ uid 冻住（`OplusHansManager F enter` / `onUidFrozenChanged frozen=true`）；`cmd activity unfreeze --sticky` **挡不住**。watchdog 现每轮 unfreeze 主进程和 `:MSF`，并往 `/data/oplus/os/bpm/bpm.xml` 写入 persist（该名单原先有 Termux 无 QQ）。21:33 另一次 `qq_down` 的决定性原因是 `Force stopping com.tencent.mobileqq ... installPackageLI` / `pkg removed`（Coolapk 在前台），watchdog 拉起 PID 19867，maps 仍 excess=0。进程没了 ≠ 踢号。

## 2026-08-28 20:33：maps_hide v3.1 + watchdog 冷启误计收紧

作者强调踢号严重影响体验，本轮在全栈不减开关的前提下修了两处**已被证据指向的错滤/误计**：

1. **maps_hide v3.1**：干净 QQ 自带一条 nameless **RWX** ShadowHook（~1828 KiB）。v2 只滤这条 RWX，10 分钟被踢（短于基线）。v3 把 RWX 和注入新增的 nameless **RX** 一起滤掉，存活仍落在注入基线 16–31 min。v3.1 只滤无名 RX 和路径指纹（vector/zygisk/mapshide/onebot/`/data/adb`），**把 ShadowHook RWX 留给 fekit 看**。真实 maps 仍是 `anon_rwx=1` / `anon_exec_excess=3` / `maps_mapshide=3`（audit 读未过滤视图）。即时健康 ≠ 防踢有效。
2. **watchdog**：19:38 `account_kicks=8` 是冷启窗口，随后 `login=true`，未止损。根因是 `login_active` 匹配 dumpsys 里任意 LoginActivity（含 recents / `t-1` 残留）。现改为 Splash/Chat 作为 topResumed 或 lastPaused 则**不算 login**；kick 计数要求 `online→login`、自 launch 180s 内忽略、300s 去抖。计数 8 **未回退**。

20:33 已部署 PID **26942**，APK `OneBotQQ-0.5.0-mapshide-v31.apk`，scope 在，watchdog PID 31904 `online` / `login_activity=no`，`ws-health` login/online。快照 `full-stack-v31`。配置仍全开。

## 2026-08-28 19:06：废止 A/B，完整注入全栈

作者明确要求不再做单一变量窗口。当前默认全开：
`anti_detect` + `maps_hide` v3 + `block_qsec_tasks` + `block_qsec_reports` + `observe_fekit_attach`（只计数）
+ Zygisk anonymous + watchdog。踢号后卸注入重登，再把全栈打回去，不减开关。

19:07 已部署：PID **29051**，scope `com.tencent.mobileqq/0`，watchdog PID 31071 online，
`ws-health` login/online，`fekit_attach.enabled=true`。快照 `full-stack-up`。
即时健康 ≠ 防踢有效。下面各节仍是历史证据，不再当作「一次只开一个」操作手册。

## 2026-08-28 18:48：maps_hide v3 未跨过注入基线（kick-7），已止损

- 用户 19:00 确认 QQ 被下线。决定性快照 `20260828-184851.status`：reason=`online-to-login`，
  PID **31667**，前台 **`.activity.LoginActivity`**，`port_3001=up`，`maps_mapshide=3`，
  `maps_onebot=3`，`maps_anon_exec=4` / `excess=3`。不是崩溃。
- 时间：18:28 v3 冷启 → 18:30:29 watchdog `login -> online` → **18:48:50** `online -> login`。
  存活约 **18 分钟**。kick-5/6 注入基线是 16–31 分钟，**v3 没有优于这条基线**。
  不得写成“有帮助但不够”，也不得拿“比 v2 的 10 分钟长”洗白——对照对象是注入基线，不是 v2。
- 当时唯一新反检测变量是 `maps_hide=true`（v3）；`observe_fekit_attach` 一直 false。
  GOT 6 槽此前已读到指向 mapshide RX。真实 maps 仍可见 `libmapshide.so`。
- watchdog **没有**打 `server account kick observed`，`account_kicks` 当时仍为 6。
  `login_active` 看到了 `LoginActivity`，但 `recent_account_kick` 的 120s events 窗口
  未见 `ACCOUNT_KICKED`（logcat 缓冲随后也滚掉了）。已按用户确认 + LoginActivity 快照
  **回填 `account_kicks=7`**。这是检测缺口，不是“没踢”。事后加宽 kick 判定，不要在登录窗改 watchdog。
- 证据：`/data/adb/onebot-qq/evidence/kick-7-20260828-190129/`。
- 已止损（19:01）：`maps_hide=false`；scope rm + watchdog stop + force-stop + 无注入冷启。
  干净 PID **18529**，scope 空，watchdog disabled，SplashActivity，3001 down；
  maps onebot=0 mapshide=0 vector=0 zygisk=0，`anon_exec_excess=0`。
  快照 `20260828-190217.status`（`clean-after-kick7-v3-rollback`）。
- 用户设备**不能用人脸识别**。进聊天前不得加 scope、不得开 watchdog、不得开 observer。
  恢复 OneBot 时改装 `OneBotQQ-0.5.0-forward-ui.apk`，只 `scope add`。

## 2026-08-28 18:28：maps_hide v3 曾单独启用（该窗口已结束）

- 用户完成干净登录后，先留快照 `clean-login-before-v3`：PID 19915，3001 down，
  `anon_exec=1/excess=0`，`anon_rwx=1/excess=0`，再开始实验。
- 唯一新反检测变量是 `maps_hide=true`；`observe_fekit_attach` 保持默认 false，
  `anti_detect=true` / `block_qsec_tasks=true` 是旧基线。已安装 APK SHA-256
  `1a197f3c43c7b7c90b2fc8e0acd697192ccb90e1367b454291e13e80023b0eb0`。
- 新 PID **31667**，18:28 起；WS online/login、QQ 登录、端口均正常，watchdog PID 4932
  已在健康后启动。`account_kicks=6`，未增加。
- 不依赖日志猜测：直接读取进程 GOT 验证 **6 个槽全部指向 libmapshide RX**：
  libfekit `open/fopen/syscall/dl_iterate_phdr` 4 个，libckguard `fopen/dl_iterate_phdr` 2 个。
- 真实 maps 快照仍会看到 mapshide/onebot=3（audit 读未过滤视图）；检测库的
  proc/linker 读取已由上述 GOT 定向 v3。
- 回滚：配置备份 `/data/adb/onebot-qq/onebot-qq.pre-mapshide-v3.json`；将 `maps_hide`
  改回 false，安装 `OneBotQQ-0.5.0-forward-ui.apk`，scope rm + 冷启。
- **该窗口已于 18:48 kick-7 结束。** 当时只能结论“部署与即时健康通过”；跨观察窗前
  禁止写成防踢有效。见上一节。

## 2026-08-28 18:16：第六次真实踢号（仍是旧基线，候选未安装）

- 18:16:09 watchdog `server account kick observed`，`online -> login`，PID **29121**；快照
  `20260828-181609.status` 前台 `NotificationActivity`，`account_kicks=6`。之后进入
  `LoginActivity` / `IdentificationFragmentActivity`。不是崩溃，3001 当时仍 up。
- 该 PID 17:45:12 恢复 online 后约 **31 分钟**被踢，与 kick-5 基线同量级。当时
  已安装 APK SHA-256 与 `OneBotQQ-0.5.0-forward-ui.apk` 完全一致；
  `maps_hide=false`，新 `observe_fekit_attach` 代码尚未安装，因此 kick-6 **不能归因 v3/observer**。
- 踢号快照：vector/zygisk/onebot/mapshide=0，fekit=3，`anon_exec=4`，
  `anon_exec_excess=3`，`anon_rwx=1`，`anon_rwx_excess=0`，shadowhook=8。
- 证据：`/data/adb/onebot-qq/evidence/kick-6-20260828-182120/`。
- 已止损：scope rm + watchdog stop + force-stop + 无注入冷启。当前干净 PID **19915**，
  scope 空，watchdog disabled，已从 `LoginActivity` 拉到 `SplashActivity`，3001 无监听。
  用户确认进聊天前不得恢复 scope/watchdog。

## 2026-08-28 18:12：纠正 svc/RWX 归因，v3 与只计数观测已离线完成

- 18:12 只读复核当时 PID **29121** 仍 online，`account_kicks=5`；后台冻结/WS 超时
  确实不是踢号。但同 PID 随后在 18:16:09 发生了独立的 kick-6，见上节。
- 对本机 `libfekit.so` 用 `llvm-objdump` 全量复核：实际是 **44** 条 `svc`，不是旧清单约 112 条。
  两处明确引用 `/proc/self/maps` 的代码分别走导入的 `fopen` 与 `open/read`；不能再用“存在裸 svc”
  推导“maps 必然绕过 GOT”。其它直接 `openat/read` 仍存在，但尚无证据把它们连到 maps。
- 旧“匿名 RWX 是注入硬指纹”归因错误：无 OneBot、3001 down 的干净 QQ 快照
  `20260828-155015.status` 同样为 `maps_anon_rwx=1`；当前 1828 KiB 映射只使用约 48 字节，跳到 QQ
  自带 `[anon:shadowhook-hub-trampo]`。干净 QQ `anon_exec=1`，注入后 `anon_exec=4`，真正增量是
  **3 条匿名可执行映射**。审计新增 `maps_anon_{exec,rwx}_excess`；当前为 `3/0`。
- 这也解释 v2 的覆盖缺口：它只过滤匿名 **RWX**，没有过滤真正新增的匿名 **RX**；且没包装
  `dl_iterate_phdr`。v2 的 10 分钟失败结论不变，但“失败即裸 svc”解释被证伪。
- 已离线实现 **maps_hide v3**：保持只 patch fekit/ckguard，过滤 maps/smaps/mountinfo 中的无路径可执行
  映射（smaps 整段过滤），并包装检测库的 `dl_iterate_phdr` 回调。默认仍关；单测、完整构建通过，
  **未安装、未真机启用**。候选 APK：`/data/adb/onebot-qq/OneBotQQ-0.5.0-mapshide-v3-observe.apk`。
- 新增 `observe_fekit_attach=false`：启用时只在原方法返回后统计 `0x...` cmd/subcmd、长度、错误数，
  不保存 attachment 内容/账号参数，不改参数、返回值或异常；统计随 `get_status.fekit_attach` 返回。
  这只计数、不改返回；现已与 v3 一起打进全栈。
- ColorOS 会把后台 QQ 主进程停在 `do_freezer_trap`：3001 仍 listen，但 WS 不回帧；仅拉到前台即恢复。
  这是 OneBot 可用性/health 误判，不是账号踢号，后续单独修 watchdog，不混入反检测结论。

## 2026-08-28 16:49：第五次真实踢号（恢复注入对照，无新反检测变量）

现场操作以 `/storage/emulated/0/Dev/onebot-qq-接手提示词.md` 的「此刻」为准。

- 16:49:45 watchdog `server account kick observed`（`online -> login`），PID **21832**。
  Intent `mqq.intent.action.KICK_TO_LOGIN` → `LoginActivity`。不是崩溃；3001 当时仍 up。
- 本 PID 存活约 **16 min**（forward-rich 冷启 ~16:33 → 16:49:45）。自 16:18 只 `scope add`
  恢复 OneBot 起约 **31 min**（中间有 upload-file-id / forward-rich 两次冷启）。
- **没有**新的反检测布尔：`maps_hide=false`，`anti_detect=true`，`block_qsec_tasks=true`，
  Zygisk anonymous 仍开。踢号 5 是「恢复注入对照」失败，不是 maps_hide v2 的重做。
- 快照：`maps_vector=0`、`maps_zygisk=0`、`maps_mapshide=0`、`maps_fekit=3`、
  `maps_anon_exec=4`、`maps_anon_rwx=1`，可疑线程=0。当时写的“硬指纹是匿名 RWX”已被
  18:12 的干净 QQ 对照纠正；实际注入增量是 `anon_exec_excess=3`。
- **不得**把群文件写 / 合并转发真机测试写成踢号原因；也不得把 ~31 min 写成
  “block_qsec_tasks 有帮助”——kick3 已证伪 execTasks，样本不能交叉洗白。
- 证据：`/data/adb/onebot-qq/evidence/kick-5-20260828-164945/`，
  snapshot `20260828-164945.status`（online-to-login）与 `kick-5-login`。
- 止损：scope rm + watchdog stop + 干净冷启。登录稳定前不要加回 scope、不要开 watchdog、
  不要开任何新反检测变量。累计 `account_kicks=5`。

## 2026-08-28 15:37：maps_hide v2 已证伪，已止损卸载注入

现场操作（scope/watchdog/登录）以
`/storage/emulated/0/Dev/onebot-qq-接手提示词.md` 的「此刻」为准。

- 15:37:13 watchdog 记录 `server account kick observed`（`online -> login`），PID **733**，
  NotificationActivity/`ACCOUNT_KICKED`。存活约 **603 秒（~10 分钟）**，短于 anonymous 基线
  ~33 分钟和 `block_qsec_tasks` 窗口 ~75 分钟。**不得**写成“有帮助但不够”；更短窗口更像
  GOT 篡改 / `libmapshide.so` 入真实 maps 增加了 native 篡改信号。
- 踢号快照：`maps_vector=0`、`maps_zygisk=0`、`maps_mapshide=3`、`maps_fekit=5`、
  `maps_anon_exec=4`、`maps_anon_rwx=1`。不是崩溃。证据目录：
  `/data/adb/onebot-qq/evidence/kick-4-20260828-153713/`。
- 当时静态清单把数据/反汇编口径误报成约 **112** 条 aarch64 `svc`；18:12 全量复核为 **44**。
  直接 `svc` 确实绕过 GOT，但两条已定位的 maps 读取均走导入 libc。匿名 RWX 也已证实属于
  干净 QQ 的 ShadowHook 基线，不是注入增量。
- 止损（已做）：`maps_hide=false`；当时 `scope rm` + 干净冷启。16:17 用户确认进聊天后
  **只** `scope add` 恢复 OneBot，未重开 maps_hide。
- watchdog 无注入时仍会因 `port_missing` 误杀正在登录的 QQ；登录窗口必须停。
- **不要**重开 maps_hide，也不要在本对照窗口叠新反检测变量。

## 2026-08-28 15:26：maps_hide v2 启用（随后 15:37 失败，已被 v3 取代）


- 跨 linker namespace：不再用本模块 `dl_iterate_phdr`（v1 patched 0），改为解析
  `/proc/self/maps` 定位 `libfekit.so` / `libckguard.so` 基址，再解析 ELF 打 GOT。
- 只替换检测库的 `open`/`openat`/`fopen`/`syscall`。本 so 自己的读写走自身 GOT 的 libc
  `syscall()`，不递归。过滤关键词 + **匿名 RWX 行**。
- 真机：登录/WS 正常。第一次冷启 patched **5→6** slots（fekit 然后 ckguard）；安静版再启
  时先打到 fekit **5** slots，`Q.Maps` 只在计数变化时打日志。PID 733 上 ckguard 已加载，
  本进程启动循环可能早于它；下一次冷启的 20s 重扫会补上。
- 真实 maps 会出现 `libmapshide.so`（`maps_mapshide=3`），这是模块 so 路径；过滤后
  **libfekit 不应再读到**。audit 读的是未过滤 maps，数字升高不代表过滤失败。
- 当前配置：`maps_hide=true` 为**唯一新增变量**；`block_qsec_tasks=true` 保持不变作对照。
  用 `account_kicks` 判断。回滚：配置改回 false 后冷启，或
  `OneBotQQ-0.5.0-group-file-writes.apk`。APK：
  `/data/adb/onebot-qq/OneBotQQ-0.5.0-mapshide-v2.apk`。
- 直接 `svc` 仍可能绕过。匿名 RWX 仍在真实 maps 里，只是希望检测库看不到。

## 2026-08-28 14:51：block_qsec_tasks 短窗口已证伪

- 14:51:14 watchdog 记录 `server account kick observed`，状态 `online -> login`；同 PID 15023
  在 14:51:59 用户重登后回到 online。累计 `account_kicks=3`，`last_account_kick_epoch=1787899874`。
- 当时唯一新增变量仍是 `block_qsec_tasks=true`（13:36 起，约 75 分钟），maps 精确口径保持
  vector=0、zygisk=0、fekit=3，可疑线程/旧日志均为 0。踢号不是崩溃。
- 结论：阻断 `QSec.execTasks` **不能**阻止服务器踢号。它只是 Java 对 native `doSomething` 的包装；
  签名/心跳仍走 `getSign/getFeKitAttach/Dandelion/QsecEst`，libfekit 自己的定时扫描不依赖这个入口。
  75 分钟 vs 此前 anonymous 基线约 33 分钟，样本太少，**不得**写成“有延迟效果”。
- 本轮**不**再叠加新的反检测变量，也未 scope rm：账号已由用户完成验证并回到 ChatActivity，WS
  health 正常。保持当前配置作对照，下一变量必须在单独窗口启用。
- 证据：`/data/adb/onebot-qq/exposure-snapshots/20260828-145114.status`（online-to-login）与
  `20260828-145159.status`（login-to-online）；watchdog.log 同步记录。

## 2026-08-28 15:10：libfekit 无注入静态观测（下一层方向）

对 QQ 9.3.50 的 `libfekit.so` 与 PID 15023 maps 做只读分析，没有新 hook、没有新 so。

- 导入了 libc：`open`、`fopen`、`read`、`access`、`stat`、`dl_iterate_phdr`、`dlopen`、`dlsym`，
  **以及 `syscall`**。动态节 `BIND_NOW`。因此跨 namespace 的 GOT patch **有机会**拦住 libc 路径，
  包括 `syscall()` 包装；直接 `svc` 仍可能绕过，需后续反汇编确认。
- 明文检测面：`/proc/self/maps`、`smaps`、`mountinfo`、`cmdline`、`parse_libart.cpp`、
  `lsposed`、`zygisk`、`libriru`、`frida`、`.magisk`。`getXpsInfo`/`detectMethod` 盖不住这些。
- Zygisk anonymous 消掉了路径名。旧观察把 **匿名 RWX 约 1828 KiB** 当成注入指纹；后续同版本
  干净 QQ 对照和跳板内容证明它是 QQ 自带 ShadowHook。应比较 `anon_exec_excess`，当前注入增量为 3。
- `QSec.getFeKitAttach` 把 native `getXwDebugID` 附到 SSO cmd 上，是“签名附带风险数据”的 Java
  可见入口；**不要阻断**，只允许计数观测。
- 下一步（后来做成 v3，现已打进全栈）：跨 ns 定位 libfekit → patch 其 GOT 的 `open/fopen/read/syscall/
  dl_iterate_phdr`，过滤 maps/smaps 中的匿名可执行增量与 framework 特征。旧 GOT maps_hide 因命名空间看不到 libfekit 而
  patched 0 slots，这条路线是对那次失败的定向修正，不是重做全局 libc hook。
- 只读清单脚本：`scripts/qq-onebot-fekit-inventory.sh`。

## 2026-08-28 13:27：anonymous 路径隐藏与 execTasks 基线

- 13:27:36 再次捕获 `KICK_TO_LOGIN` + `ACCOUNT_KICKED`，发生在群文件 APK 部署之前；排除构建/冷启
  导致本次踢号。此时 Zygisk anonymous 已运行约 33 分钟，精确 maps 仍为 vector=0、zygisk=0。
- 结论：anonymous 显著消除了路径级暴露，但不能单独阻止 libfekit/服务器风控；旧的
  “anonymous + reportLog-block”基线在短窗口内已失败，`account_kicks` 回填为 2。
- 为避免带注入完成验证，已临时移除 QQ scope、停止 watchdog，并在干净进程中确认 QQ 自动恢复且没有
  登录 Activity；随后才重新加入 scope。
- 当前严格只新增 `block_qsec_tasks=true`，其它变量保持 anonymous + reportLog-block +
  detectMethod/getXpsInfo replacement。重新注入后登录、WS、群文件查询和资源 URL 均正常，开始新的
  短窗口 A/B。异常时先 scope rm；关闭本变量只需把配置改回 false 后冷启。
- watchdog 的近期事件查询也已修复：Android logcat 不接受 `-T 2m`，现改用 epoch 计算绝对时间，避免
  后续真实 `ACCOUNT_KICKED` 因时间参数无效而漏计。

## 2026-08-28 12:47：真实踢号证据与 Zygisk 匿名映射基线

- Android events 明确记录 `mqq.intent.action.KICK_TO_LOGIN`，随后创建
  `mqq.intent.action.ACCOUNT_KICKED` 的 NotificationActivity；本次是服务器令牌失效/踢号，
  不是 QQ Java/native 崩溃。用户完成验证后登录与 OneBot 均恢复。
- 当时已启用 reportLog 阻断约 5 小时，`block_qsec_tasks=false`。这说明 reportLog 单点阻断不足以
  消灭踢号。后续不再按「一次只开一个」操作。
- Zygisk Next 1.4.5 原为 `memory_type=0`，QQ maps 直接暴露
  `zygisk_vector/zygisk/arm64-v8a.so` 与 `zygisksu/lib64/libzygisk.so`。改用官方
  `zygiskd memory-type anonymous` 后冷启，登录、WS 与 OneBot health 均通过；精确计数
  `maps_vector=0`、`maps_zygisk=0`，且无新增错误。
- 旧审计把系统通用 `[anon:InternalMmapVector]` 误算成 Vector；短信、Termux、桌面等干净进程也有
  同一行。新审计的 `maps_vector` 只统计框架特征，`maps_vector_generic` 保留旧关键词口径用于对照。
- anonymous 路径收敛继续保留。回滚：
  `/data/adb/modules/zygisksu/bin/zygiskd memory-type default`，随后冷启 QQ；原值另存于
  `/data/adb/onebot-qq/zygisksu-memory_type.pre-anonymous`。
- watchdog 已修复“登录页存在但 3001 仍开着就误报 online”的逻辑：登录任务优先，新增
  `account_kicks` 与 `last_account_kick_epoch`。已将本次事件作为第一条基线计入。

## 2026-08-28：双主线重新开放后的 0.5.0 进展

旧实验说明保留用于避免重复踩坑。当前策略是完整注入全栈，踢号后卸注入重登再打回，同时保留 watchdog。

- 默认静默日志，tag 改为中性 `Q.Kernel`；XposedBridge verbose 日志仅显式开启。
- `getXpsInfo` 从 after-hook 改为 replacement：原实现中的 `T.ad(...)` 不再先执行，避免采集副作用。
- `QSec.reportLog` 阻断、`execTasks` 阻断、maps_hide v3、getFeKitAttach 只计数：**全部默认开**。
- `getSign/getSignEntry/getEstInfo/Dandelion` 以及 `getFeKitAttach` 的返回值仍保持原样。
- 新增 root `qq-onebot-exposure-audit.sh`，watchdog 状态切换自动记录 maps、线程与日志指纹。

## 症状
QQ 被注入后，报"设备环境不安全"，隔一阵把账号踢下线，要重新登录 + 验证。很烦。

## 根因（诚实版）
QQ NT 的安全核心是 **native 的 `libfekit.so`**（腾讯 QSec/fekit SDK）。Java 层的
`QSec.getSign / doSomething / getEstInfo`、`Dandelion.energy`、`QsecEst.d` **全是 JNI**，
真正的环境扫描（读 `/proc/self/maps` 找注入的 .so、查 hook 框架、反调试）在 native 里做，
把"设备风险"信号塞进登录/心跳签名上报给服务器，**服务器**据此触发踢下线+验证。

**结论：纯 Java 的 Xposed 模块无法阻止 native 扫描。** 这是能力边界，别被任何"一键绕过"忽悠。

## 本模块做了什么（best-effort，`qq/AntiDetect.java`，配置 `anti_detect` 默认开）
中和 QQ **Java 层**的检测缝隙（这些能安全 hook，且不影响登录）：
- `QSec.detectMethod(cls, method)` → 恒 `false`：QQ 用它反射探测 hook 框架类（如
  `de.robv.android.xposed.XposedBridge.log` 是否存在），返回 false 让它探不到。
- `QSec.getXpsInfo()` → 返回空 `byte[]`：`Xps`=Xposed，这是 Xposed 信息采集器，清空它。

**绝不 hook** `getSign/getSignEntry/getEstInfo/doSomething/energy`——那是登录签名，动了直接登不上。

已在设备验证这两个 hook 成功安装（logcat：`AntiDetect: neutralised QSec.detectMethod / getXpsInfo`）。
**能减少 Java 层暴露面，但不保证消灭 native 触发的掉线。** 请观察一段时间看频率是否下降。

## 想要更彻底？三条路（按性价比）
1. **框架级隐藏（最有效，推荐先试）**：把 vector 注入的 .so 从 QQ 进程的 `/proc/self/maps` 里藏掉，
   native 扫描就找不到。这属于 **vector 框架**能力，不是本模块能写的。查 vector 是否有 hide/隐身：
   `sh /data/adb/modules/zygisk_vector/cli config get <key>` / 看 vector manager。
   另外确保**只有本模块**把 QQ 加了作用域（别让别的模块也注入 QQ，否则多一份暴露）。
2. **native maps 过滤 hook（治本但重）**：给本模块配一个 zygisk 伴生 native .so，
   inline-hook libc 的 `open/openat/read`（或 `fopen`），把读 `/proc/self/maps` 的结果里
   含 `vector/xposed/zygisk/lspd/riru` 的行滤掉，喂给 libfekit 一份"干净"的 maps。
   需要写 ARM64 native + Dobby/inline-hook，版本敏感，工程量大。参考现成"maps hider"实现思路。
3. **降低自身指纹（锦上添花）**：本模块的线程名叫 `onebot-*`、开了 3001 端口、建了实现 QQ 接口的
   动态 Proxy——这些不是主要检测项（主检测项是 Xposed 框架本身），但洁癖的话可改随机线程名、
   端口只绑 127.0.0.1。

## 设备侧现状（背景，来自本机既有配置）
这台已做了很多 root 隐藏：tricky_store keystore 证明、HMA、prop 伪装、zygisk denylist。
但那些针对的是**其它风控 App**；QQ 的掉线是 **Xposed 注入**被 QSec native 抓到，和上面那套是两码事。
KernelSU 的 umount-default / denylist 对"被注入的 QQ"无效（要注入就不能 denylist QQ）。

---

## Route 2 已实现：native maps 隐藏 (`maps_hide`, 全栈默认开)
`native/mapshide.c` → 编译成 `lib/arm64-v8a/libmapshide.so`，由 `qq/MapsHide.java` 在 QQ 进程里
`System.load` + `install()`。原理：用 `dl_iterate_phdr` 遍历所有已加载 ELF，patch 它们 GOT 里的
`open`/`openat`，重定向到我们的实现——读 `/proc/self/maps` 时返回一份**过滤掉**
`vector/zygisk/xposed/lspd/riru/magisk/mapshide/onebot/data/adb` 行的 memfd。
构建：`build.sh` 里 termux clang `--target=aarch64-linux-android24` 直接编 bionic .so。

**⚠️ 诚实警告**：libfekit 是专业 native 反篡改，**很可能用裸系统调用 (svc #openat) 直接读 maps，
绕过 libc**——那样 GOT hook 拦不到，`maps_hide` 就无效。这是 best-effort，不保证。
真要拦裸 syscall，得做 seccomp/inline-hook syscall entry，工程量再上一个量级。

### 测试步骤（**先确保主号已在干净 QQ 上登录稳定**）
1. 写配置 `/sdcard/Android/data/com.tencent.mobileqq/files/onebot-qq.json`：`{"maps_hide": true, "token": "..."}`
2. 把 QQ 加回作用域：`sh /data/adb/modules/zygisk_vector/cli scope add com.onebot.qq com.tencent.mobileqq/0`
3. 冷启动 QQ：`am force-stop com.tencent.mobileqq; monkey -p com.tencent.mobileqq 1`
4. 看日志：`logcat -s OneBotQQ:* MapsHide:*`，确认 `MapsHide: loaded ... patched N GOT slots`（N>0）。
5. 观察 QQ 是否还掉线：
   - **不掉了** → GOT 级过滤够用，成了。
   - **还掉** → 基本可确认 libfekit 走裸 syscall；关掉 `maps_hide` + `scope rm` 停手，
     下一步只能上 syscall/inline hook（大工程，见上）。
> 出事随时一键停：`sh .../cli scope rm com.onebot.qq com.tencent.mobileqq/0` 让 QQ 立刻回到干净无注入。

### 2026-08-27 实测结论（重要，别再走这条弯路）
maps_hide 外科版（只 patch libfekit）在真机测了：**patched 0 slots，无效**。两个原因：
1. **linker 命名空间隔离**：我们的 `libmapshide.so` 经 `System.load` 加载在**模块 classloader 的
   命名空间**里，`dl_iterate_phdr` 只枚举本命名空间，**看不到 QQ app 命名空间里的 libfekit.so**
   （虽然 libfekit 确实在同进程、/proc/pid/maps 里能看到）。要 patch 它得改成：读真实
   `/proc/self/maps` 定位 libfekit 基址 → 直接解析其 ELF 内存 → mprotect+patch GOT（跨 ns 同地址空间可行）。
2. **就算 patch 到了，libfekit 是专业反篡改，极可能用裸 svc syscall 读 maps**，根本不走 libc GOT。
好消息：**外科版不会再卡死 QQ**（只碰检测库，ART 读真 maps）。坏消息：**GOT 路线基本走死**。
真要继续：只剩(a) 直接内存 patch libfekit + 赌它走 libc；(b) seccomp/inline-hook 拦裸 syscall（大工程）；
(c) **换思路：别藏，改成压制"踢下线"动作**——但"设备异常要人脸"是**服务器**签发票据控制的，
本地压制大概率让会话变成死号（能显示登录但发不出消息）。

### 当前可用状态（2026-08-27）
注入 + `anti_detect`(Java hook detectMethod/getXpsInfo) + maps_hide 关。实测 QQ **保持登录数分钟、
会话活(get_login_info 返回真实昵称)、未被踢**。是否长期稳定需观察数小时。**诚实底线：libfekit 的
native 检测无法保证绕过**；现实选择是"接受偶尔重登"或"换小号"。

## 历史阶段的一句话结论
当时 Java 层只做了 detectMethod/getXpsInfo，GOT maps_hide 也未奏效。该结论仅说明旧方案失败；当前继续
从 replacement 副作用、遥测入口、框架加载暴露和更精确的 native 观测推进，详见本文开头 0.5.0 更新。

## 2026-08-27 最终收敛审计

- vector 7 个启用模块中，QQ scope **只有 `com.onebot.qq`**，没有其它模块叠加注入。
- QQ maps 计数：`vector=4`、`zygisk=6`，`xposed/lspd/riru/onebot/mapshide=0`；vector 2.2 CLI/Manager
  没有 hide 配置。这 10 条 native 暴露是当前天花板。
- 安全收敛已做：WS 只绑 `127.0.0.1`；工作线程名不含 onebot/xposed/vector；maps_hide 保持关闭；
  仅保留 `detectMethod/getXpsInfo` 两个低风险 Java hook。
- 禁止项不变：不碰 QSec 签名 JNI、不做 ART/全局 maps 欺骗、不因“反检测”引入更高崩溃风险。
- 防掉线采用“降低表面指纹 + 量化观察 + 快速恢复”：真实 lifecycle/heartbeat、同进程重登、
  root watchdog、`set_restart` 均已主号真机验证。
