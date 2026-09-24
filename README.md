# 知弦 · Satori QQ

知弦在 Android QQ 进程内提供 Satori v1 HTTP 与 WebSocket 服务，供本机 Koishi 等客户端连接。当前核验环境为 QQ 9.3.65（NT）。

## 运行条件

- Android 8.0 或更高版本
- QQ：`com.tencent.mobileqq`
- Zygisk Next，用于注入模块；不需要 LSPosed 或 Xposed 框架

QQ 会检查运行环境。检测到 LSPosed 等注入框架或 hook 引擎时，可能触发登录风控、账号掉线或反复验证。知弦使用 Zygisk 注入，不包含 ART hook 引擎；消息与会话通道通过 JNI 实现。

## 安装

1. 安装管理应用 `SatoriQQ.apk`。
2. 将 `SatoriQQ-module.zip` 刷入 Magisk / KernelSU，或解压到 `/data/adb/modules/satori_qq/`。
3. 安装或更新模块后重启设备。重启 QQ 或热重载不能替代整机重启。
4. 打开「知弦」，在首页确认 QQ 账号、服务和客户端的连接状态。

模块基于 Zygisk API v4，已在 Zygisk Next 1.5.0 验证。管理应用的改动需单独安装 `build/SatoriQQ.apk`。

## 连接 Koishi

以下示例使用 `adapter-satori`：

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

`selfUrl` 与 `server.port` 必须一致。知弦默认不校验令牌；配置 `token` 后，客户端必须使用相同值。默认服务只监听 `127.0.0.1:3001`。

## 配置

优先在知弦的「连接设置」中配置端口、令牌、状态通知和唤醒锁。其他设置读取首个有效配置文件：

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
| `manual_self_messages` | `true` | 将 QQ 手动发送的消息也作为事件投递 |
| `forward_mode` | `auto` | 合并转发格式：`auto`、`native` 或 `fake` |
| `media_retry_attempts` | `2` | 富媒体上传失败后的额外尝试次数 |
| `verbose_logs` | `false` | 输出调试日志 |

应用内「连接设置」会覆盖文件中的同名字段；选择「改用文件配置」可取消覆盖。修改配置后重启 QQ。

## 保持在线

QQ 被系统冻结或结束时，Satori 服务会断开。知弦包含进程内 Keepalive；需要设备级恢复时，可用 root 看守 `qqguard` 检测进程、解冻 QQ 并按预算重启。安装、命令与策略见[常驻守护](docs/GUARD.md)。

`wifi_sustain` 只在 Wi-Fi 下生效。蜂窝网络需允许 QQ 使用后台数据；ColorOS 等系统还可能需要关闭深度睡眠或睡眠待机优化。保持在线会增加耗电。

## 排障

- 强行停止 QQ 后，看守会尊重系统的停止状态并暂停；重新启动保活请运行 `qqguard start`。
- 锁屏后文字可发但图片或合并转发失败，优先检查后台网络和深度睡眠设置。
- 知弦读不到应用内设置时，在系统设置中允许知弦的关联启动，再重启 QQ。
- 区分 QQ 断线、模块故障和客户端连接问题，可查看 `/healthz`；网络诊断脚本见 `scripts/netwatch.sh`。

## 文档

- [Satori v1 方法、事件和消息元素](docs/SATORI_SUPPORT.md)
- [架构、构建与测试](docs/ARCHITECTURE.md)
- [常驻守护 qqguard](docs/GUARD.md)
- [JNI 能力范围](docs/JNI_CAPABILITIES.md)
- [OIDB 与封包参考](reference/PACKETS.md)

## 许可证

可按 [Apache-2.0](LICENSE-APACHE) 或 [MIT](LICENSE-MIT) 使用。
