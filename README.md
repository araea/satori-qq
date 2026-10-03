# 知弦（satori-qq）

QQ 的 Satori 实现端：通过 Zygisk 注入把 QQ NT 内核暴露为统一 Satori 接口，支持收发消息与媒体

[![GitHub](https://img.shields.io/badge/GitHub-araea%2Fsatori--qq-181717?logo=github&logoColor=white)](https://github.com/araea/satori-qq)
[![Release](https://img.shields.io/github/v/release/araea/satori-qq?logo=github&logoColor=white&color=2ea44f)](https://github.com/araea/satori-qq/releases)

## 安装

运行环境：

- Android 8.0 或更高版本
- QQ 包名 `com.tencent.mobileqq`
- Zygisk Next 1.5.0 或更高，用于注入模块。不依赖 LSPosed 或 Xposed 框架

步骤：

1. 安装管理应用 `SatoriQQ.apk`。
2. 将 `SatoriQQ-module.zip` 刷入 Magisk / KernelSU，或解压到 `/data/adb/modules/satori_qq/`。
3. 安装或更新模块后重启设备。重启 QQ 或热重载不能替代整机重启。
4. 打开「知弦」，在首页确认 QQ 账号、服务和客户端的连接状态。

## 快速使用

本机 Koishi 用 `adapter-satori` 连接：

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

`selfUrl` 与 `server.port` 必须一致。知弦默认不校验令牌，配置 `token` 后客户端必须使用相同值。默认服务只监听 `127.0.0.1:3001`。

## 配置

优先在知弦的「连接设置」中配置端口、令牌、状态通知和唤醒锁。其余设置读取首个有效配置文件：

```text
/sdcard/Android/data/com.tencent.mobileqq/files/satori-qq.json
/storage/emulated/0/Android/data/com.tencent.mobileqq/files/satori-qq.json
/sdcard/satori-qq.json
/storage/emulated/0/satori-qq.json
```

完整示例见 [`satori-qq.sample.json`](satori-qq.sample.json)。常用字段：

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `port` | `3001` | 本机服务端口 |
| `token` | 空 | HTTP 与 WebSocket 鉴权；留空表示不鉴权 |
| `status_notification` | `true` | 显示运行状态通知 |
| `request_battery_exemption` | `true` | 首次在线时申请电池优化豁免 |
| `wake_lock_control` / `wake_lock_auto` | `true` | 启用唤醒锁；`auto` 控制是否自动持有 |
| `wifi_sustain` | `true` | 有客户端连接时保持 Wi-Fi 锁 |
| `kernel_foreground` | `true` | 有已鉴权客户端连接时，每 5 秒维持 QQ 内核前台调度 |
| `manual_self_messages` | `true` | 将 QQ 手动发送的消息也作为事件投递 |
| `forward_mode` | `auto` | 合并转发格式：`auto`、`native` 或 `fake` |
| `media_retry_attempts` | `2` | 富媒体上传失败后的额外尝试次数 |
| `verbose_logs` | `false` | 输出调试日志 |

应用内「连接设置」会覆盖文件中的同名字段，选择「改用文件配置」可取消覆盖。修改配置后重启 QQ。

## 限制 / 风险

- QQ 会检查运行环境。检测到 LSPosed 等注入框架或 hook 引擎时，可能触发登录风控、账号掉线或反复验证。知弦使用 Zygisk 注入，不包含 ART hook 引擎。消息与会话通道通过 JNI 实现。
- QQ 被系统冻结或结束时，Satori 服务会断开。知弦包含进程内 Keepalive；需要设备级恢复时用 root 看守 `qqguard`，见 [常驻守护](docs/GUARD.md)。保持在线会增加耗电。
- 媒体最多等待 45 秒确认，文字最多 20 秒。发送回调或消息状态更新均可确认。超时返回 `send outcome unknown`，不会假报成功或自动重发，原消息仍可能稍后送达。
- 资源 URL（`/v1/assets/{id}`、`internal:` 回落地址）仅本机监听、不带鉴权，不要把 URL 公开转发。
- 配置改动需重启 QQ 后生效，热切换端口不被支持。

## 必要链接

- [Satori v1 方法、事件和消息元素](docs/SATORI_SUPPORT.md)
- [架构、构建与测试](docs/ARCHITECTURE.md)
- [常驻守护 qqguard](docs/GUARD.md)
- [JNI 能力范围](docs/JNI_CAPABILITIES.md)
- [OIDB 与封包参考](reference/PACKETS.md)
- [MIT](LICENSE-MIT) / [Apache-2.0](LICENSE-APACHE)
