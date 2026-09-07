## 0.8.9.24

针对「锁屏后图片/合并转发发不出去，纯文字却正常」这一类深夜定时推送翻车的问题。

成因：息屏后 Wi-Fi 进入省电节奏（本机实测到 AP 的 RTT 从 4ms 劣化到平均 27ms、峰值 48ms，链路本身
再差一点就会 DATA_STALL 到断连）。文字消息顺着已建立的 MSF 长连接一个包就出去了，而 QQ 内核在
`sendMsg` 里同步完成的富媒体上传需要新建连接和持续吞吐，是最先失败的一环，回调
`code=-1 / rich media transfer failed`。

- **Wi-Fi 锁模式改回 `WIFI_MODE_FULL_HIGH_PERF`**。0.8.9.23 在 API 29+ 用的是
  `WIFI_MODE_FULL_LOW_LATENCY`，而框架只在**亮屏且持锁应用在前台**时才激活低延迟锁
  （`WifiLockManager.getStrongestLockMode`），息屏时等同于「没有任何锁」。高性能锁是唯一会被映射
  成「关闭省电」的模式。**但要说清楚它能买到什么**：Android 14+ 会把高性能请求重映射成低延迟
  （`config_wifiHighPerfLockDeprecated`），所以在新 ROM 上光靠这枚锁并不能让网卡脱离息屏省电——
  本机实测息屏后到 AP 的 RTT 都是 4ms → 平均 27ms / 峰值 48ms。它真正的作用是给
  `cmd wifi force-hi-perf-mode enabled` 提供一枚可作用的锁：一把锁都没有时
  `getStrongestLockMode()` 直接短路到 NO_LOCKS_HELD，强制模式无从生效。彻底关掉息屏省电是设备侧
  的决定，Android 14+ 上应用自己做不到。
- **发送期间自动持锁**：所有会写出去的接口（`message.create` 等，即 `OutboundGuard` 认定的
  mutation）在进入 QQ 内核前自动持有 CPU + Wi-Fi 锁，结束即释放；不再依赖用户手动点通知按钮。
  自动持有的 CPU 锁带 3 分钟超时兜底，卡死也不会长期赖着。
- **`wifi_sustain`（新增，默认开）**：只要有 Satori 客户端连着，就长期持有那枚高性能 Wi-Fi 锁。
  收消息同样会被息屏省电拖住——实测息屏一小时后事件积压，解锁瞬间才一次性涌入。不需要可关。
- **富媒体上传失败自动重试**：`rich media transfer failed` 发生在消息投递之前，对端什么都没收到，
  重试不会重复发送。默认额外重试 2 次（退避 4s/8s，总预算 45s，控制在客户端 HTTP 超时之内），
  每次都从原始 segment 重建 MsgElement（内核会往传进去的 element 里写上传状态，半成品不能复用）。
  开关：`media_retry_attempts` / `media_retry_backoff_ms` / `media_retry_budget_ms`。
- **`/healthz` 的 `wakelock` 字段不再说谎**：旧版无论 `acquire()` 是否真的成功都标成 `held`。现在
  按 `WakeLock.isHeld()` 如实上报，形如 `reg/user=off/auto=1/cpu=held/wifi=held`。

> ⚠️ 若从 0.8.9.20 及更早（不同签名密钥）升级，需先卸载旧版再安装本版，并在 vector/LSPosed 重新
> 启用模块、勾选 QQ 作用域后重启 QQ；0.8.9.21 起同密钥可直接覆盖安装。
