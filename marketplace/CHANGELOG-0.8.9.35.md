## 0.8.9.35

- 查清 2026-09-13 22:10 那次「模块自报在线、消息一条收不到、24 分钟不恢复」：`block_server_kick` 挡掉的是 `NTKickProcessor.b`，也就是服务端踢线（别处登录、改密码、版本过低）时 QQ 唯一的处理入口。挡掉之后不会被踢下线，但服务端会话已经作废，本机停在「内核仍报在线、上游早已断开」的状态，既不重连也不提示，只有重启 QQ 才能恢复
- 把这件事变成看得见的：
  - 拦下踢线时按 `L.e` 记一行（不开 `verbose_logs` 也进 logcat），内容含 `kickedType`、`securityKickedType`、`sameDevice` 与提示语
  - `/healthz` 新增 `blocked_kicks`、`kick_hook`、`last_kick_epoch_ms`、`last_kick`，`kick_hook` 为 0 表示这一版没拦住踢线（被踢会正常退出登录）
  - 过检测自检文件 `qk_env_*.json` 的 `hooks` 里也加 `server_kick`
- 新增看守 [`scripts/qq-revive.sh`](https://github.com/araea/satori-qq/blob/master/scripts/qq-revive.sh)：每 60 秒看一次 MSF 进程到服务端的存活连接数、`/healthz` 的在线状态与被拦踢线计数。踢线计数一涨就立刻重启 QQ；「在线但没有上游连接」连续 3 轮、或模块端口连续 5 轮不通，也会重启（设备自己没网时只记一行，不动 QQ）。`--check` 只打印一轮判据
- 新增 `scripts/98-qq-revive.sh`，放进 `/data/adb/service.d/` 开机拉起看守（以 root 运行）
- 版本号 0.8.9.35（versionCode 70），沿用同一签名密钥，支持从 0.8.9.34 直接覆盖升级
