# 知弦 · Satori QQ

在 Android QQ 进程内提供 Satori v1 服务（HTTP + WebSocket），监听 `127.0.0.1:3001`，供 Koishi
`adapter-satori` 等客户端连接。已核验 QQ 9.3.65（NT）。

## 运行条件

- Android 8.0 及以上
- QQ `com.tencent.mobileqq`
- Zygisk Next（模块由它注入，不需要 LSPosed 或任何 Xposed 框架）

QQ 会检测运行环境：一旦发现 LSPosed 注入或 hook 引擎（LSPlant/Dobby）的痕迹，就把设备与运行环境判为
异常。后果不只是人脸验证被禁，登录身份也会失效、被服务端踢下线，需要反复重新登录。本模块因此由 Zygisk
注入，进程内没有框架、也不带 ART hook 引擎——引导、内核会话与 SSO 回包都走 JNI，一条 ArtMethod 都
不改写。

## 安装

1. 安装 `SatoriQQ.apk`（管理界面与设置接口）
2. 把 `SatoriQQ-module.zip` 作为 Magisk / KernelSU 模块刷入，或解包后放到
   `/data/adb/modules/satori_qq/`（`module.prop`、`zn_modules.txt`、`zygisk/arm64-v8a.so`）
3. 重启手机（Zygisk 模块只在启动时注册）
4. 打开「知弦」，在首页的「连接链路」确认 QQ 账号、本机服务与客户端都已就绪

## Zygisk API 兼容性

当前模块使用 Zygisk API v4，仅在注入回调中读取 `AppSpecializeArgs::nice_name`；
[上游公开的 v5 头文件](https://github.com/topjohnwu/zygisk-module-sample/blob/master/module/jni/zygisk.hpp)
相对 v4 增加了可选的 `mount_sysprop_overrides` 参数，本模块不需要它。已在 Zygisk Next 1.5.0
上核对现有 v4 模块的部署与运行，因此暂不升级 API 版本，避免无收益的兼容性风险。

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

`selfUrl` 与 `server.port` 保持一致。默认不校验令牌；设置 `token` 后客户端必须使用相同值。

## 配置

知弦的「连接设置」可改端口、令牌、状态通知、唤醒锁、Wi-Fi 保持与手动消息投递，保存后在 QQ 下次启动时生效
（有 Root 时可直接点「重新启动 QQ」），并覆盖文件同名项；「改用文件配置」解除覆盖。其余项只认文件。

文件按顺序读取，取第一份有效配置（完整示例见 [`satori-qq.sample.json`](satori-qq.sample.json)）：

```text
/sdcard/Android/data/com.tencent.mobileqq/files/satori-qq.json
/storage/emulated/0/Android/data/com.tencent.mobileqq/files/satori-qq.json
/sdcard/satori-qq.json
/storage/emulated/0/satori-qq.json
```

| 设置 | 默认值 | 说明 |
| --- | --- | --- |
| `port` | `3001` | 本地服务端口 |
| `token` | 空 | HTTP 与 WebSocket 鉴权令牌 |
| `status_notification` | `true` | 显示运行状态通知，点击切换到 QQ |
| `request_battery_exemption` | `true` | 首次在线时申请电池优化豁免 |
| `wake_lock_control` / `wake_lock_auto` | `true` | 唤醒锁；`auto` 为启动即持有，否则等通知栏按钮 |
| `wifi_sustain` | `true` | 有客户端连接时保持 Wi-Fi 锁 |
| `manual_self_messages` / `manual_self_user_id` | `true` / 空 | QQ 里手打的消息也作为事件投递，作者用独立身份，默认 `qq-client:{selfUin}` |
| `forward_mode` | `auto` | 合并转发：`auto` / `native` / `fake` |
| `media_retry_attempts` | `2` | 富媒体上传失败后的额外尝试次数 |
| `verbose_logs` | `false` | 输出调试日志 |

限频与排队的开关见示例文件。改配置后重启 QQ。

## 常驻

QQ 被冻住或杀掉就掉线，三层保护：

1. **root 看守 `qqguard`**（[`scripts/qqguard.sh`](scripts/qqguard.sh)，详见
   [`docs/GUARD.md`](docs/GUARD.md)）：基于 KernelSU / ReSukiSU 的原生能力，以 root 独立运行，
   不依赖 Termux、LSPosed 或 Zygisk。默认每 30 秒判一次：进程不在就按预算拉起，被冻住只写 freezer
   cgroup 解冻（有冷却），`/healthz` 挂死或掉登录才在「最近在线过」的前提下触发自动登录。
   开机由模块自带的 `service.sh` 调 `qqguard boot` 恢复上次的 ARMED / PAUSED 状态。
   常用控制：`qqguard start`（开启）/ `stop`（暂停，不关 QQ）/ `restart` / `status`；
   `qqguard kill` 是「停止保活并关闭 QQ」。快捷设置里也有「知弦守护」与「停止保活并关闭 QQ」两个磁贴。
2. **模块**（`Keepalive`）：会话就绪后每 10 分钟重新启动 QQ 自己的服务，让进程保持在前台服务优先级，
   并持 CPU 唤醒锁。状态见 `/healthz.keepalive`。
3. **系统**：`qqguard start` 会应用电池优化（Doze）白名单、必要 AppOps（`RUN_IN_BACKGROUND` /
   `RUN_ANY_IN_BACKGROUND` 等）、App Standby Bucket 与 Data Saver 白名单；ColorOS
   「睡眠待机优化」见排障。需要关掉整机 Doze 时另见 [`scripts/99-no-doze.sh`](scripts/99-no-doze.sh)。

看守的完整参数（检测间隔、重启预算、退避、解冻冷却等）见
[`scripts/guard.conf.sample`](scripts/guard.conf.sample)，拷成 `/data/adb/satori-qq/guard.conf` 即可。
不用模块 zip 时，把 [`scripts/98-qqguard.sh`](scripts/98-qqguard.sh) 放进 `/data/adb/service.d/` 也能开机恢复。

`wifi_sustain` 持的是 `WifiLock`，只在 Wi-Fi 下有效；蜂窝下靠前两层，另需确认系统没限制 QQ 的
「后台数据」。

## 排障

- 强停或划掉 QQ 会停止服务。看守默认尊重系统里的「强行停止」（`stopped=true`），会自动转入
  PAUSED 而不是跟你抢；想重新保活就 `qqguard start` 或点「知弦守护」磁贴。
- 状态通知在 QQ 前台时消失过：0.24.0 起每轮会检查条目是否还在，被 QQ 自己清掉后自动补发；
  看 `/healthz.notice` 的 `reposts`。
- 锁屏后文字能发而图片、合并转发失败，是网络问题：文字走既有长连接，富媒体要新建连接持续传输。关闭
  系统的「睡眠待机优化」或「深度睡眠」，ColorOS 的开关未必写回配置，确认
  `deep_sleep_is_disable_net_allowed` 与 `deepsleep_network_switch` 已归零。
- 整机流量经 VPN 或 TUN 转发时，只加电池优化白名单不够：Doze 进入 `IDLE` 后经 TUN 的流量全部中断，
  `dumpsys deviceidle disable` 可立即恢复。Doze 没有 UI 开关，用
  [`scripts/99-no-doze.sh`](scripts/99-no-doze.sh) 开机关闭，代价是待机功耗上升。
- 分不清断在哪一层时用 [`scripts/netwatch.sh`](scripts/netwatch.sh)，每 60 秒记录物理链路、本地代理、
  经 TUN 出站、模块状态与电源状态。
- 模块自报在线、消息却一条收不到，多半是被服务端踢线，由 QQ 自己处理。
- 设置读不到：ColorOS 会拦关联启动，在**系统设置 → 应用 → 关联启动**里允许知弦，再重启 QQ。QQ 会
  退回文件或默认配置，页面有提示。

## 文档

- [`docs/DESIGN.md`](docs/DESIGN.md)：设计体系、Design Tokens、组件契约与无障碍验证范围

- [`docs/SATORI_SUPPORT.md`](docs/SATORI_SUPPORT.md)：协议方法、事件与消息元素
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)：内部结构、构建与测试、QQ 升级检查项

- [`docs/JNI_CAPABILITIES.md`](docs/JNI_CAPABILITIES.md)：纯 JNI 层的功能边界，可达与不可行清单

## 许可

本项目可按 [Apache-2.0](LICENSE-APACHE) 或 [MIT](LICENSE-MIT) 许可证使用。
