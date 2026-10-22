# 0.38.0 — 痕迹收敛

模块在 QQ 自己的目录、线程表与通知渠道里不再留名字。功能不变。

### 变化

- **落盘**：QQ 数据目录下只留一个隐藏目录 `files/.svc`（0700）。开关 `enabled`、native 日志 `native.log`、SSO 失败 `sso.log`、群名快照 `group-names.tsv`、写操作审计 `writes.log`、发送诊断 `last-send.txt` / `history.txt` 都搬进去。`native.log` 不再无限长：下次启动时丢掉超过 256 KiB 的旧内容。
- **升级**：第一次启动把旧的 `files/satori-qq/enabled` 的值搬进 `.svc/enabled`，再删掉 `files/` 下所有 `satori-` 开头的条目与 `qk_sso.log`。
- **线程**：bootstrap 线程不再叫 `pool-4-bootstrap`，按其它线程的 `pool-N-thread-M` 风格取；native 引导线程也从继承来的 zygote 名字改成同名风格。
- **通知**：状态渠道 id 改成 `svc_status_channel`，显示名仍是「知弦服务状态」。
- **`ctl.sh purge`**：卸载模块前跑一次，把 `.svc` 与旧条目一起删掉。
- 边界（唯一一处 JNI 改动、回环端口、不伪造 QSec 结论）记在 [docs/STEALTH.md](https://github.com/araea/satori-qq/blob/master/docs/STEALTH.md)。

### 升级

刷入新模块并重启 QQ。端口、令牌与开关状态原样保留。
