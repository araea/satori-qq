# satori-qq

<img src="artwork/icon.svg" width="96" height="96" alt="软件图标" />

在 Android QQ 进程内提供 Satori v1 HTTP 与 WebSocket 服务，默认监听 `127.0.0.1:3001`，供 Koishi `adapter-satori` 等客户端连接。

已核验 QQ 9.3.55 与 9.3.60.40970（NT）。

## 运行条件

- Android 8.0 及以上
- QQ `com.tencent.mobileqq`
- 可为 QQ 启用模块作用域的 Xposed 兼容框架

## 安装

1. 安装市场发布的 `SatoriQQ.apk`
2. 在框架中启用模块，并把 QQ 加入作用域
3. 重启 QQ
4. 访问 `http://127.0.0.1:3001/healthz`，确认 `online` 为 `true`

需要隐藏模块清单标记时，先用普通 APK 完成启用与作用域设置，再覆盖安装同版本的 `SatoriQQ.stealth.apk`。调整作用域前应先装回普通 APK。

## 客户端连接

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

`selfUrl` 应与 Koishi 的 `server.port` 一致。模块默认不要求令牌；设置 `token` 后，客户端必须使用相同值。

## 配置

模块按顺序读取以下文件，取第一份有效配置：

```text
/sdcard/Android/data/com.tencent.mobileqq/files/satori-qq.json
/storage/emulated/0/Android/data/com.tencent.mobileqq/files/satori-qq.json
/sdcard/satori-qq.json
/storage/emulated/0/satori-qq.json
```

完整示例见 [`satori-qq.sample.json`](satori-qq.sample.json)。常用设置如下：

| 设置 | 默认值 | 说明 |
| --- | --- | --- |
| `port` | `3001` | 本地服务端口 |
| `token` | 空 | HTTP 与 WebSocket 鉴权令牌 |
| `status_notification` | `true` | 显示运行状态通知 |
| `foreground_keepalive` | `true` | 在线时以前台服务保持 QQ 主进程 |
| `request_battery_exemption` | `true` | 首次在线时申请电池优化豁免 |
| `wake_lock_control` | `true` | 在通知中提供唤醒锁开关，写操作期间自动持锁 |
| `wake_lock_auto` | `true` | 启动时自动获取唤醒锁，无需点按通知按钮 |
| `wifi_sustain` | `true` | 有客户端连接时保持 Wi-Fi 锁 |
| `media_retry_attempts` | `2` | 富媒体上传失败后的额外尝试次数 |
| `anti_detect` | `true` | Java 层环境检测处理 |
| `maps_hide` | `true` | Native 层进程信息过滤 |
| `block_turing_risk` | `true` | 停止 Turing 风控入口 |
| `block_server_kick` | `true` | 停止本地强制下线处理；服务端会话失效时仍需重新登录 |
| `fake_imei` / `fake_android_id` / `fake_serial` | 空 | 设备标识；留空使用真实值，设置时应保持一致 |

修改配置后重启 QQ。

## 排障

健康检查返回登录状态、监听状态、连接数、保活状态与过检测自检结果：

```sh
curl http://127.0.0.1:3001/healthz
```

强停或划掉 QQ 会停止服务。前台服务与唤醒锁可降低进程被回收或冻结的概率，但无法阻止系统在息屏期间关闭网络。锁屏后文字能发而图片或合并转发失败，是网络问题，不是模块问题：文字只需在既有长连接上发一个包，而富媒体上传在 `sendMsg` 内同步进行，需要新建连接并持续传输，会先失败。

按两层排查。厂商层：关闭「睡眠待机优化」或「深度睡眠」，ColorOS 的开关未必会写回配置，需确认 `deep_sleep_is_disable_net_allowed` 与 `deepsleep_network_switch` 已归零。系统层：整机流量经 VPN/TUN 转发时，把相关应用加入电池优化白名单并不够。实测 Doze 进入 `IDLE` 后，即便 QQ、Termux 与代理应用全部豁免，经 TUN 的流量仍全部中断，而链路、上游与代理进程本身均正常；`dumpsys deviceidle disable` 后立即恢复。Doze 无 UI 开关，可用 [`scripts/99-no-doze.sh`](scripts/99-no-doze.sh) 开机关闭，代价是待机功耗上升。

分不清断在哪一层时用 [`scripts/netwatch.sh`](scripts/netwatch.sh)：每 60 秒分别记录物理链路、本地代理、经 TUN 出站、模块状态与电源状态的结果。

协议方法、事件与消息元素见 [`docs/SATORI_SUPPORT.md`](docs/SATORI_SUPPORT.md)，内部结构与升级检查项见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

## 构建与测试

首次构建需准备 Android 35 平台、R8 与 `org.json`：

```sh
curl -fsSL -o libs/r8.jar https://maven.google.com/com/android/tools/r8/8.9.35/r8-8.9.35.jar
curl -fsSL -o libs/json.jar https://repo1.maven.org/maven2/org/json/json/20250517/json-20250517.jar
./build.sh
./test.sh
```

构建结果为 `build/SatoriQQ.apk` 与 `build/SatoriQQ.stealth.apk`。版本 APK 与变更记录统一发布在[模块市场](https://github.com/Xposed-Modules-Repo/com.satori.qq)。

## 致谢与许可

过检测实现参考 [QQEnhancedBypass](https://github.com/Xalsace/QQEnhancedBypass)。

本项目可按 [Apache-2.0](LICENSE-APACHE) 或 [MIT](LICENSE-MIT) 许可证使用。
