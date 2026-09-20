## 0.24.0 · 知弦

这一版把「常驻」从上到下重做了一遍：通知在前台不再消失，root 侧多了一个只依赖
KernelSU/ReSukiSU 原生能力的看守（不依赖 LSPosed/Zygisk），协议层补上真正的心跳，并给
快捷设置加了两个磁贴。

### 修复：QQ 回到前台时，常驻状态通知消失

QQ 每次把自己的主窗口带到前台，都会清一遍自家通知（`NotificationManager.cancelAll()`）。
知弦的状态条目是从 QQ 进程里、用 QQ 的身份发的，于是被一并清掉；而旧实现只在「可见内容变了」
时才重新 `notify`，内容没变就永远不补发——表现就是「一开 QQ，通知就没了」。

现在每轮状态检查都会问一次「我们自己这条还在不在」（`getActiveNotifications()` 只返回本应用
自己发的通知，不需要任何监听权限）。条目被清掉、且距上次发布超过 3 秒，就用同样的内容补发；
内容变化仍然照旧重绘。通知被用户禁用时不补发，等重新启用后由同一条判据自愈。
`/healthz.notice` 里多了 `reposts=N`，能一眼看出补发过几次。

### 新增：qqguard —— 基于 KernelSU / ReSukiSU 的 7×24 看守

`scripts/qqguard.sh`，以 `/system/bin/sh` 运行，工具全部取 `/system/bin`（toybox）与 KSU
自带 busybox，不依赖 Termux、不依赖 LSPosed/Zygisk，也不带任何 ART hook。看守与注入层解耦：
即使 Zygisk 注入暂时失效，QQ 进程的异常死亡仍能被恢复。

- **保活与用户意图分开**：`ARMED` / `PAUSED` 落盘在 `/data/adb/satori-qq/guard.state`，
  模块升级、看守重启、手机重启都不会丢。只有 ARMED 才恢复；PAUSED 一律不碰 QQ；
  用户在系统设置里手动强停（`stopped=true`）默认被尊重并自动转入 PAUSED。
- **进程不在了才拉起**：崩溃或被系统回收（LMK）会按预算重启；被冻住只写 freezer cgroup
  解冻（有冷却），绝不因为冻结就强杀重启。
- **重启有刹车**：两次重启至少间隔 `MIN_GAP`（默认 120s），任意 1 小时最多 4 次，
  连续失败按 `60s → 120s → … → 900s` 指数退避；`CRASH_WINDOW`（默认 600s）内重启达到
  `CRASH_LIMIT`（默认 3）次就判定连续崩溃，转入 PAUSED 等人工处理，不再无限快速重启。
- **系统配置一键应用**（`qqguard apply`，`start` 时自动跑）：Doze 白名单、必要 AppOps
  （`RUN_IN_BACKGROUND` / `RUN_ANY_IN_BACKGROUND` / `WAKE_LOCK` / `START_FOREGROUND`）、
  App Standby Bucket 尽量 active、Data Saver 白名单。**不动 `deviceidle` 全局开关、不碰全局
  LMK、不长期强占 `oom_score_adj`、不用永久亮屏**；`QQGUARD_OEM=1` 时才额外尝试厂商自启动
  AppOps（不同 ROM 支持不一致，失败会被忽略）。
- **开机恢复**：模块自带 `service.sh`（KernelSU late_start 阶段）调用 `qqguard boot`，
  按上次落盘的状态恢复：ARMED 才应用配置并启动 watchdog，PAUSED 什么都不做。
  也可不用模块 zip，改用 `scripts/98-qqguard.sh` 放进 `/data/adb/service.d/`。
- **命令行**：`qqguard start|stop|status|restart|toggle|kill|apply|log`。
  `stop` 先暂停 watchdog，再允许你正常关闭 QQ；`kill` 才是「停止保活并关闭 QQ」。
  `status --json` 给磁贴与管理页解析。
- 日志与状态都在 `/data/adb/satori-qq/`（root-only），有大小轮转；`guard.conf` 可调全部参数，
  见 `scripts/guard.conf.sample`。

### 新增：快捷设置磁贴

- 「知弦守护」：单击在 Guard ON/OFF 之间切换。关闭默认只暂停保护，不关 QQ。
- 「停止保活并关闭 QQ」：先暂停 watchdog，再强停 QQ；之后不会再被拉起。

两个磁贴都通过 `su -c` 调 root 侧的 `qqguard status --json` 取真实状态，路径与参数全是写死的
字面量。管理页首页也加了「常驻守护」卡片，三件事都能在这里做。

### 协议层心跳与退避

- `WsConn` 记录每个客户端的最后入站帧，服务端按 `heartbeat_ms` 主动发 WebSocket ping：
  两个心跳周期内一个字节都没回的连接会被关掉。老实现只在客户端主动 ping 时回 pong，
  半开连接（NAT 超时、掉射频、对端冻结）会一直挂在 `identified` 里吞事件。
  `/healthz.heartbeat` 报 `pings / pongs / reaped`。
- 富媒体重试从线性退避（`backoff * i`）改成指数退避（`base, 2×base, …`，以重试预算封顶），
  逻辑抽到纯函数 `core/RetryPolicy`，单测钉住。
- 登录状态恢复沿用既有路径（`trackLoginChange` 换号回收客户端、READY 等账号就绪再发、
  在线稳定期后再写出站），这版没有改动，只在 `/healthz` 里补齐心跳字段。

### 测试

- `tests/StatusNoticeTest`：把「内容没变但条目没了要补发、内容没变且还在就不发、禁用时不补发、
  同一内容有最小补发间隔」这些判据全部钉住。
- `tests/RetryPolicyTest`：钉住指数退避与预算判断。
- `tests/qqguard-test.sh`：source 看守脚本的纯函数（`QQGUARD_LIB_ONLY=1`），在临时目录验证
  ARMED/PAUSED 计数、退避翻倍与封顶、冷却、每小时预算、连续崩溃窗口计数，不需要 root。
  `test.sh` 现在会一并跑它。
