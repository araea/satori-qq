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

1. 将 `SatoriQQ-module.zip` 刷入 KernelSU / APatch / Magisk。
2. 安装或更新模块后重启设备。重启 QQ 或热重载不能替代整机重启。
3. 在 KernelSU / APatch 的模块列表里点知弦，打开管理页，确认 QQ 账号、服务和客户端的连接状态。

不需要另装应用：管理页是模块自带的网页（`webroot/`），由 root 管理器打开。页面的每个 root 动作都是模块自带 `ctl.sh` 的一条子命令（`ctl.sh probe` 可以直接在终端里跑）；Magisk 没有模块网页界面，可以直接用 `ctl.sh`。

## 更新

管理页会检查更新：打开时检查（成功后 6 小时、失败后 30 分钟内不重复，可以关掉），也可以手动「检查更新」。有新版本时页首出现「更新」卡，点「更新」由 `ctl.sh` 下载、核对 SHA-256，再交给 root 管理器自己的安装命令（`ksud` / `apd` / `magisk`）；`config.json` 由 `customize.sh` 原样保留。模块更新要重启手机才生效，装好后页首给出「立即重启」。模块的 `module.prop` 带 `updateJson`，root 管理器的模块列表本身也会提示更新。

发布时用 `scripts/release.sh`（先 `./build.sh`）：核对版本、生成 `update.json`、创建 GitHub release。版本只写在 `module.prop`（`SatoriHub.APP_VERSION` 要与它一致，构建时核对）。

## 快速使用

想直接用机器人，装 [知微（acumen）](https://github.com/araea/acumen)：在 Termux 里运行下面一行，它会跟随本模块的端口与令牌自动连上，无需配置。

```sh
curl -fsSL https://github.com/araea/acumen/releases/latest/download/install.sh | bash
```

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

## 模块开关

管理页的「模块开关」是总开关，关闭后不监听端口、不显示常驻通知、不持唤醒锁、不重启 QQ 的核心服务，也不保活；效果与没有安装模块一样。开关值写在 QQ 数据目录的 `files/.svc/enabled`（`1` / `0`），模块每秒读一次，拨动后一两秒内生效，不用重启 QQ。关闭时会把 root 看守一并暂停；重新开启时恢复原来的保活意图。

开关不对外暴露端口或控制通道，只有 root 能写。

## 配置

优先在管理页的「连接设置」中配置端口、令牌、状态通知和唤醒锁。管理页把设置写进模块目录的 `config.json`（root 0600，模块升级时保留），Zygisk 在 QQ 启动时（`preAppSpecialize`，那时还拿得到模块目录）读出交给 Java，所以保存后要重新启动 QQ 才生效；`config.json` 里也可以手写下面的任何高级键，管理页保存时原样保留。`config.json` 读不出来时服务保持关闭（不会带着无令牌的默认值跑起来）。没有 `config.json` 时读取首个有效配置文件：

```text
/sdcard/Android/data/com.tencent.mobileqq/files/satori-qq.json
/storage/emulated/0/Android/data/com.tencent.mobileqq/files/satori-qq.json
/sdcard/satori-qq.json
/storage/emulated/0/satori-qq.json
```

完整示例见 [`satori-qq.sample.json`](satori-qq.sample.json)。常用字段：

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 模块总开关；关闭时不监听、不通知、不保活 |
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
- 痕迹：落盘只在 QQ 数据目录的隐藏目录 `files/.svc`，线程名与通知渠道 id 不带模块名，升级时清掉旧的 `files/satori-*`。能做到什么、做不到什么见[痕迹](docs/STEALTH.md)。
- QQ 被系统冻结或结束时，Satori 服务会断开。知弦包含进程内 Keepalive；需要设备级恢复时用 root 看守 `qqguard`，见 [常驻守护](docs/GUARD.md)。保持在线会增加耗电。
- 媒体最多等待 45 秒确认，文字最多 20 秒。发送回调或消息状态更新均可确认。超时返回 `send outcome unknown`，不会假报成功或自动重发，原消息仍可能稍后送达。
- 资源链接（收到的媒体与 `upload.create` 的结果都是 `internal:red/{uin}/_tmp/{id}`）经 `/v1/proxy` 回读，该路由仅本机监听、不带鉴权，不要把代理地址公开转发。
- 配置改动需重启 QQ 后生效，热切换端口不被支持。

## 必要链接

- [Satori v1 方法、事件和消息元素](docs/SATORI_SUPPORT.md)
- [架构、构建与测试](docs/ARCHITECTURE.md)
- [常驻守护 qqguard](docs/GUARD.md)
- [JNI 能力范围](docs/JNI_CAPABILITIES.md)
- [代码规范](docs/STYLE.md)
- [OIDB 与封包参考](reference/PACKETS.md)
- [MIT](LICENSE-MIT) / [Apache-2.0](LICENSE-APACHE)
