## 0.8.9.20

- 修复「QQ 显示在线、Satori 连接成功、却无法发送」的隐性离线：会话仅由构造函数 hook 捕获，一旦内核会话在 hook 安装前已建立（模块热重载、或 QQ 客户端升级后的时序竞争），构造函数不再触发，`session` 长期为空，所有发送以 `kernel offline or not ready` 失败，而 HTTP 端口与 QQ 前台状态均无从区分该情形。
- 新增会话兜底捕获：额外 hook 会话 CppProxy 的常用无参 getter（`getMsgService`/`getGroupService`），QQ 正常运行时会频繁调用它们，据此捕获活动会话对象，数秒内自愈且无需重启 QQ；已捕获时经无锁身份校验短路，热路径零额外开销。
- 在 QQ 9.3.60（versionCode 16030）真机复核：HTTP、登录态、发送与事件接收正常；反检测栈、签名、maps 隐藏与封包上报路径均未改动。
