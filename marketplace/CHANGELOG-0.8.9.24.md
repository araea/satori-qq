## 0.8.9.24

针对「锁屏后图片/合并转发发不出去，纯文字却正常」这一类深夜定时推送翻车的问题。

**成因（请先看这段，本版的代码改动只是兜底）**：厂商 ROM 的「睡眠待机优化 / 深度睡眠」会在夜间
预测的睡眠窗口里**直接切断数据通路**——不是限速，是关网。ColorOS 的实现在
`/data/user_de/0/com.oplus.battery/shared_prefs/DeepSleepSharepref.xml`：
`deep_sleep_is_disable_net_allowed=true`、`deepsleep_network_switch=3`（3 = Wi-Fi 与移动数据
一起关，所以**换成移动数据同样复现**），另有 `com.oplus.deepsleep.RestoreNetworkReceiver` 负责
事后恢复。实测一晚的时间线：预测窗口 23:30→07:43，01:49 首次进入失败、02:33 进入「等待流量停止」、
03:37 真正进入深度睡眠、03:53:25 退出——QQ 积压一小时的消息正是 03:53:27 一次性涌进来的。

为什么偏偏是图片和合并转发：文字一个包顺着已建立的 MSF 长连接就出去了；而 QQ 内核的富媒体上传是在
`sendMsg` 里同步完成的，要新建连接 + 持续吞吐，断网时第一个失败，回调
`code=-1 / rich media transfer failed`。合并转发同理（native 路径先把内层消息发进自己的私聊，
再 `multiForwardMsg`）。

> **因此：真正的修复是去系统设置里关掉「睡眠待机优化 / 深度睡眠」。** 若整机流量走本地代理 / VPN
> （Clash 之类），那个应用也要加进电池优化白名单——它被限制时所有应用一起断网。本版的重试与自动
> 持锁只能提高成功率，断网期间照样发不出去，区别是不会再一次失败就静默降级成纯文字。

- **Wi-Fi 锁模式改回 `WIFI_MODE_FULL_HIGH_PERF`**（次要因素：息屏 Wi-Fi 省电把到 AP 的 RTT 从
  4ms 抬到平均 27ms / 峰值 48ms，实测）。0.8.9.23 在 API 29+ 用的是
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
