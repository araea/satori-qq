## 0.8.9.21

- 新增常驻状态通知：Satori 服务运行时在 QQ 通知栏常驻一条静默（`IMPORTANCE_LOW`）通知，随内核状态实时切换「运行中 / 等待登录 / 服务异常」，并显示账号、本地端口、事件连接数与在线时长。以 QQ 自身 Context 与通知权限发出，模块不声明任何权限；它不是前台服务，不提供保活权重。可用 `status_notification: false` 关闭。因随 QQ 身份发出，需 QQ 具备通知权限（Android 13+ 的 POST_NOTIFICATIONS，默认已授予）
- 新增 `GET /healthz`（本地免鉴权）健康探针：直接返回 `online`、`listening`、`self_id` 与在线时长，把「真在线」和「端口在听但内核离线」分开，与通知栏状态同源
- `docs/ARCHITECTURE.md` 新增可观测性说明：进程内不做保活，只做 HTTP 自愈（accept 循环 3 秒重绑）与状态上报
- 在 QQ 9.3.60 真机复核：HTTP、登录态、发送与事件接收正常，常驻通知随重启与登录态正确刷新；反检测栈、签名、maps 隐藏与封包上报路径均未改动

升级提示：本版签名密钥变更，无法覆盖安装。先卸载旧版模块，再安装本版 APK，然后在 vector/LSPosed 中重新启用模块并勾选 QQ 作用域，最后重启 QQ。
