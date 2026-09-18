# 知弦 · Satori QQ

在 Android QQ 进程内提供 Satori v1 的 HTTP 与 WebSocket 服务。服务监听 `127.0.0.1:3001`，供 Koishi `adapter-satori` 等客户端连接。

已核验 QQ 9.3.65（NT）。

## 运行条件

- Android 8.0 及以上
- QQ `com.tencent.mobileqq`
- 支持 libxposed API 102 的框架（LSPosed 2.x 起）

## 安装

1. 安装 `SatoriQQ.apk`
2. 在框架中启用模块。作用域由模块固定为 QQ，不需要手工添加
3. 重启 QQ

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

端口、令牌、状态通知、前台保活、自动唤醒与 Wi-Fi 保持在知弦设置页里改，保存后重启 QQ 生效。其余高级选项写在：

```text
/sdcard/Android/data/com.tencent.mobileqq/files/satori-qq.json
```

| 设置 | 默认值 | 说明 |
| --- | --- | --- |
| `port` | `3001` | 本地服务端口 |
| `token` | 空 | HTTP 与 WebSocket 鉴权令牌 |
| `media_retry_attempts` | `2` | 富媒体上传失败后的额外尝试次数 |
| `verbose_logs` | `false` | 输出调试日志 |
| `anti_detect` | `true` | Java 层环境检测处理 |
| `maps_hide` | `true` | Native 层进程信息过滤 |
| `block_turing_risk` | `true` | 停止 Turing 风控入口 |
| `block_server_kick` | `true` | 停止本地强制下线处理 |
| `fake_imei` / `fake_android_id` / `fake_serial` | 空 | 设备标识；留空用真实值，设置时应保持一致 |

过检测、限频与排队的其余开关见源码仓库里的示例文件。修改配置后重启 QQ。

## 排障

强停或划掉 QQ 会停止服务。锁屏后文字能发而图片、合并转发失败，是网络问题。应关闭系统的「睡眠待机优化」或「深度睡眠」，并把 QQ 及所用代理或 VPN 加入电池优化白名单。

模块自报在线、消息却一条收不到，多半是被服务端踢线。模块在多条处理链上拦截，并保住盘上的登录态。

部分 ColorOS 设备会拦截知弦的配置提供程序。该程序由 QQ 在后台拉起。在**系统设置 → 应用 → 关联启动**里允许知弦，再重启 QQ。设置读不到时页面会提示，QQ 继续使用文件或默认配置。

## 源码

[araea/satori-qq](https://github.com/araea/satori-qq)

过检测实现参考 [QQEnhancedBypass](https://github.com/Xalsace/QQEnhancedBypass)。
