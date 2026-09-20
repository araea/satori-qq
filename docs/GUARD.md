# 常驻守护（qqguard）

`qqguard` 是知弦在 root 侧独立运行的看守：让 QQ（`com.tencent.mobileqq`）在一台已 root 的
Android 上尽可能不被冻结、不被断网、不被回收；异常死亡能自动恢复；用户随时可以明确停止保活并
正常关闭 QQ。

它基于 KernelSU / ReSukiSU 的原生能力，以 `/system/bin/sh` 运行，工具全部取 `/system/bin`
（toybox）与 KSU 自带 busybox；不依赖 Termux、LSPosed、Zygisk，不改写任何 ArtMethod。它与
Zygisk 注入层解耦：即使注入暂时失效，进程死亡仍能被恢复。

## 安装

由模块 zip 自带，刷入后开机自动恢复：

```sh
# KernelSU / Magisk 刷入 build/SatoriQQ-module.zip 即可。
# 模块里的 service.sh 在 late_start 阶段把 qqguard.sh 复制到 /data/adb/satori-qq/ 并调用 boot。
```

不用模块 zip 时的最小安装：

```sh
mkdir -p /data/adb/satori-qq
cp scripts/qqguard.sh    /data/adb/satori-qq/qqguard.sh
cp scripts/98-qqguard.sh /data/adb/service.d/98-qqguard.sh
chmod 0755 /data/adb/satori-qq/qqguard.sh /data/adb/service.d/98-qqguard.sh
```

以后所有命令都走 `/data/adb/satori-qq/qqguard.sh`。

## 控制

```sh
su -c 'qqguard start'     # 进入 ARMED：应用系统配置并启动 watchdog
su -c 'qqguard stop'      # 进入 PAUSED：先暂停 watchdog，再允许你正常关闭 QQ（不杀 QQ）
su -c 'qqguard restart'   # 重启 watchdog（保持当前 ARMED/PAUSED）
su -c 'qqguard toggle'    # 在 ARMED / PAUSED 之间切换（KernelSU 模块「操作」按钮也是这个）
su -c 'qqguard kill'      # 进入 PAUSED 并强停 QQ（停止保活并关闭 QQ）
su -c 'qqguard status'    # 人读状态
su -c 'qqguard status --json'
su -c 'qqguard apply'     # 只重配系统项，不动 ARMED/PAUSED
su -c 'qqguard once'      # 只跑一轮探针，不动 QQ
su -c 'qqguard log 50'    # 最近 50 行日志
```

Android 快捷设置里另有两个磁贴：

- **知弦守护**：单击切换 ON/OFF。关闭默认只暂停保护，**不关 QQ**。
- **停止保活并关闭 QQ**：先暂停 watchdog，再强停 QQ。

磁贴与管理页首页的「常驻守护」卡片都通过 `su -c` 调用 root 侧的 `qqguard status --json`，
显示的是看守的真实状态。

## 状态模型

状态落盘在 `/data/adb/satori-qq/guard.state`，模块升级、看守重启、手机重启都不会丢。

| 状态 | 含义 | watchdog | 会不会拉起 QQ |
| --- | --- | --- | --- |
| `ARMED` | 保活中 | 运行 | 崩溃 / 被系统回收 → 按预算拉起 |
| `PAUSED` | 已暂停 | 不运行 | 一律不拉起 |

另外，**用户在系统设置里手动「强行停止」QQ**（`dumpsys package` 里 `stopped=true`）默认被尊重：
即使处于 ARMED，看守也会记录一行、转入 `PAUSED`，不再跟用户抢。想重新保活就 `qqguard start`。
要改成「强停也拉」，设 `QQGUARD_RESPECT_FORCE_STOP=0`。

## 判据与动作

每 `INTERVAL` 秒（默认 30）一轮：

1. `stopped=true` → 尊重用户，转 PAUSED。
2. 主进程不在 → 崩遗或被回收。宽限期内继续等；设备没网先不动；`CRASH_WINDOW` 内重启达到
   `CRASH_LIMIT` 次则转 PAUSED 等人工；否则按预算拉起。
3. 进程在但被冻住（`uid_*/cgroup.freeze=1`、`pid_*/cgroup.freeze=1` 或
   `wchan=do_freezer_trap`）→ 写 freezer cgroup 解冻，同一冷却期（默认 60s）内只写一次。
   **不因为冻结就强杀重启。**
4. 进程在、没冻，但 `/healthz` 连续 `UNRESPONSIVE_LIMIT` 轮无响应 → 判定挂死，按预算强拉起。
5. 进程在、服务在，但 `/healthz.online=false` 连续 `OFFLINE_LIMIT` 轮，且
   `LAST_ONLINE` 在 `OFFLINE_RESTART_WINDOW` 内（即「刚刚还在线」）→ 按预算拉起，触发自动登录。
   从没在线过（开机等扫码）不会触发，避免反复重启。

被冻住只解冻不重启，是因为 ColorOS 这类 ROM 的冻结是振荡的：解冻后很快会再冻住，解冻次数多并
不代表 QQ 坏了。真正的根治在注入层（让 QQ 自己的服务保持已启动，进程停在 SERVICE_ADJ）与系统
配置（白名单、AppOps、Data Saver）；冻结解冻只是兜底。

## 重启刹车

- `MIN_GAP`：两次重启最小间隔，默认 120s。
- `MAX_RESTARTS`：任意 1 小时最多重启次数，默认 4。
- 指数退避：连续失败按 `BACKOFF_BASE` 起步翻倍，封顶 `BACKOFF_MAX`（默认 60s → 900s）。
- 连续崩溃保护：`CRASH_WINDOW`（默认 600s）内重启达到 `CRASH_LIMIT`（默认 3）次，转 PAUSED。
- `GRACE`：拉起后默认 180s 内不判「又死了」。
- `STABLE_WINDOW`：稳定运行超过默认 600s 后，清零连续失败与退避。

## 系统配置（`apply`）

`start` 会先跑一次，也可以单独调：

- Doze 白名单：`dumpsys deviceidle whitelist +com.tencent.mobileqq`（只加白名单，**不动
  `deviceidle` 全局开关**）。
- AppOps：`RUN_IN_BACKGROUND`、`RUN_ANY_IN_BACKGROUND`、`WAKE_LOCK`、`START_FOREGROUND` 设为 allow。
- App Standby Bucket：尽量 `active`（Doze 白名单下通常是 `EXEMPTED=5`，比 active 更高）。
- Data Saver：`cmd netpolicy add restrict-background-whitelist <uid>`，锁屏 / 省流量模式下仍允许
  后台联网。
- `QQGUARD_OEM=1` 时额外尝试厂商自启动 / 关联启动 AppOps（不同 ROM 支持不一致，失败被忽略）。

**不改全局**：不碰全局 LMK、不长期强占 `oom_score_adj`、不用永久亮屏。整机 Doze 是否关闭是另一个
决定，见 `scripts/99-no-doze.sh`。

## 配置

拷 [`scripts/guard.conf.sample`](../scripts/guard.conf.sample) 到
`/data/adb/satori-qq/guard.conf`，改完 `qqguard restart` 或重启手机生效。每一项都能用同名
`QQGUARD_` 环境变量临时覆盖，环境变量优先。

## 文件

| 路径 | 用途 |
| --- | --- |
| `/data/adb/satori-qq/qqguard.sh` | 看守脚本（稳定路径，CLI 与 App 都调它） |
| `/data/adb/satori-qq/guard.state` | `MODE` / 计数 / 最近重启与在线时间 |
| `/data/adb/satori-qq/guard.log` | 日志，按大小轮转 |
| `/data/adb/satori-qq/guard.restarts` | 重启历史（预算与连续崩溃保护用） |
| `/data/adb/satori-qq/guard.conf` | 可选配置 |
| `/data/adb/modules/satori_qq/service.sh` | KernelSU 开机恢复入口 |
| `/data/adb/modules/satori_qq/action.sh` | 模块「操作」按钮 = `qqguard toggle` |

## 和旧脚本的关系

0.23.x 及更早用的是 [`scripts/qq-revive.sh`](../scripts/qq-revive.sh)：每 60 秒查
`/healthz`、MSF 上游连接与踢线记录。它偏重「僵尸会话」的判定，逻辑复杂、耦合 Termux 与
`qk_kick.log`，且会 force-stop 后重启。`qqguard` 改为以「进程死亡 / 冻结」为主、协议层自己管
心跳与重连，重启预算更保守。**两者不要同时跑**：升级到 0.24.0 后请停掉旧的
`/data/adb/service.d/98-qq-revive.sh` 并删掉，避免两个看守抢 QQ。
