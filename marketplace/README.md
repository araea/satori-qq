# 知弦 · Satori QQ

在 Android QQ 进程内提供 Satori v1 的 HTTP 与 WebSocket 服务。服务监听 `127.0.0.1:3001`，供 Koishi `adapter-satori` 等客户端连接。

已核验 QQ 9.3.65（NT）。

## 运行条件

- Android 8.0 及以上
- QQ `com.tencent.mobileqq`
- Zygisk Next（模块由它注入；不需要 LSPosed，进程里也不带任何 hook 引擎）

## 安装

1. 安装 `SatoriQQ.apk`（管理界面）
2. 把 `SatoriQQ-module.zip` 作为 Magisk / KernelSU 模块刷入；或解包后把 `module.prop`、
   `zn_modules.txt`、`zygisk/arm64-v8a.so` 放到 `/data/adb/modules/satori_qq/`
3. 重启手机（Zygisk 模块只在开机时注册）
4. 打开「知弦」，在状态页确认 QQ、本机服务与客户端已连上

## 连接

```yaml
plugins:
  server:
    port: 5140
    selfUrl: 'http://127.0.0.1:5140'
  assets-local: {}
  adapter-satori:
    endpoint: 'http://127.0.0.1:3001'
    token: ''
```

`selfUrl` 与 `server.port` 保持一致。模块默认不校验令牌。设置 `token` 后，客户端必须使用相同值。

## 配置

端口、令牌、状态通知、自动保持唤醒与 Wi-Fi 保持在知弦设置页里改，保存后重启 QQ 生效，高级选项继续按文件配置。

文件按顺序读取，取第一份有效配置：

```text
/sdcard/Android/data/com.tencent.mobileqq/files/satori-qq.json
/storage/emulated/0/Android/data/com.tencent.mobileqq/files/satori-qq.json
/sdcard/satori-qq.json
/storage/emulated/0/satori-qq.json
```

| 设置 | 默认值 | 说明 |
| --- | --- | --- |
| `port` | `3001` | 本地服务端口 |
| `host` | `127.0.0.1` | 监听地址，需外部访问时改成 `0.0.0.0` |
| `token` | 空 | HTTP 与 WebSocket 鉴权令牌 |
| `status_notification` | `true` | 在 QQ 通知栏留一条常驻状态 |
| `wake_lock_control` / `wake_lock_auto` | `true` | 唤醒锁；`auto` 为启动即持有，否则等通知栏按钮 |
| `wifi_sustain` | `true` | 有客户端连接时保持 Wi-Fi 锁，避免息屏后上传被掐 |
| `heartbeat` / `heartbeat_ms` | `true` / `15000` | 客户端心跳 |
| `outbound_min_interval_ms` | `1000` | 两次写操作之间的最小间隔 |
| `outbound_max_per_minute` | `20` | 每分钟写操作上限 |
| `outbound_max_queued` / `outbound_queue_timeout_ms` | `8` / `30000` | 写队列长度与排队超时 |
| `online_stabilize_ms` | `30000` | 会话恢复后等待多久才允许写 |
| `media_retry_attempts` / `media_retry_backoff_ms` / `media_retry_budget_ms` | `2` / `4000` / `45000` | 富媒体上传失败后的重试 |
| `manual_self_messages` / `manual_self_user_id` | `true` / 空 | 把手机上手动发的消息也作为事件投递，使用独立身份 |
| `forward_mode` | `auto` | 合并转发策略：`auto` / `native` / `fake` |
| `verbose_logs` | `false` | 输出调试日志 |

修改配置后重启 QQ。

## 排障

强停或划掉 QQ 会停止服务。锁屏后文字能发而图片、合并转发失败，是网络问题。应关闭系统的「睡眠待机优化」或「深度睡眠」，并把 QQ 及所用代理或 VPN 加入电池优化白名单。

模块自报在线、消息却一条收不到，多半是登录态已失效：服务端强制下线之后，收发都会停。

部分 ColorOS 设备会拦截知弦的配置提供程序。该程序由 QQ 在后台拉起。在**系统设置 → 应用 → 关联启动**里允许知弦，再重启 QQ。设置读不到时页面会提示，QQ 继续使用文件或默认配置。

## 源码

[araea/satori-qq](https://github.com/araea/satori-qq)
