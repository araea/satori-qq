## 0.8.9.22

- 常驻机制改为合作式前台服务保活，替代反复拉起进程的看门狗思路：服务在线时，把 QQ 主进程里一个已声明 `dataSync` 的 service（`QQDataSyncService`，免运行时权限）提升为真正的前台服务，进程随即进入前台服务优先级档（真机实测 `oom_score_adj` 由约 450 降到约 50，且后台时保持），系统低内存回收与 OEM 后台冻结默认放过它
- 合作而非对抗：用户强停或划掉 QQ，前台服务随进程结束，Satori 干净断开且不复活，控制权交回用户；不再反复拉起、不跟 cgroup freezer 硬掰、不改系统名单
- 一次性授权：首次在线且未加 Doze 白名单时，弹一次系统「忽略电池优化」对话框（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）申请豁免，授予后后台启动前台服务始终放行。新增开关 `foreground_keepalive`、`request_battery_exemption`
- `/healthz` 增加 `keepalive`（前台服务状态）与 `notice`（通知投递状态）诊断字段
- 真机复核（QQ 9.3.60 / Android 16）：在线、发送与事件接收正常；前台服务 `isForeground=true`，通知随状态刷新；强停 QQ 后端口下线且不自动拉起，重开约 6 秒恢复在线。反检测栈、签名、maps 隐藏与封包上报路径均未改动

升级提示：若从 0.8.9.20 及更早（不同签名密钥）升级，需先卸载旧版再安装本版，并在 vector/LSPosed 重新启用模块、勾选 QQ 作用域后重启 QQ；0.8.9.21 起同密钥可直接覆盖安装。
