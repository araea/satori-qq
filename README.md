# 知弦 · Satori QQ

在 Android QQ 进程内提供 Satori v1 服务（HTTP + WebSocket），监听 `127.0.0.1:3001`，供 Koishi
`adapter-satori` 等客户端连接。已核验 QQ 9.3.65（NT）。

## 运行条件

- Android 8.0 及以上
- QQ `com.tencent.mobileqq`
- Zygisk Next（模块由它注入，不需要 LSPosed 或任何 Xposed 框架）

模块由 Zygisk 注入，进程内没有框架、也不带 ART hook 引擎：引导、内核会话与 SSO 回包都走 JNI，
一条 ArtMethod 都不改写。这是为了人脸核身——QQ 的慧眼 SDK 与 TuringFace 会检测运行环境，
识别到 LSPosed 注入或 hook 引擎（LSPlant/Dobby）的痕迹就把设备判为异常，服务端随即收回人脸验证。
绕开这两样，真机人脸验证可用。

## 安装

1. 安装 `SatoriQQ.apk`（管理界面与设置接口）
2. 把 `SatoriQQ-module.zip` 作为 Magisk / KernelSU 模块刷入，或解包后放到
   `/data/adb/modules/satori_qq/`（`module.prop`、`zn_modules.txt`、`zygisk/arm64-v8a.so`）
3. 重启手机（Zygisk 模块只在启动时注册）
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

`selfUrl` 与 `server.port` 保持一致。默认不校验令牌；设置 `token` 后客户端必须使用相同值。

## 配置

管理页可改端口、令牌、状态通知、唤醒锁、Wi-Fi 保持与手动消息投递，保存后在 QQ 下次启动时生效，
并覆盖文件同名项；「使用文件配置」解除覆盖。其余项只认文件。

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

QQ 被冻住或杀掉就掉线。三层保护：

1. **进程内**（`Keepalive`）：会话就绪后每 10 分钟重新 `start` QQ 自己的服务，把
   `oom_score_adj` 压在 ~700（SERVICE_ADJ，低于冻结阈值 900），并持 CPU 唤醒锁。状态见
   `GET /healthz` 的 `keepalive`：`adj` 当前优先级、`wchan` 在等什么（`do_freezer_trap` 即被冻）、
   `service` 起服务的结果。
2. **root 看守**（[`scripts/qq-revive.sh`](scripts/qq-revive.sh)，装法见
   [`scripts/98-qq-revive.sh`](scripts/98-qq-revive.sh)）：每 60 秒看一次 `/healthz`、MSF 上游连接
   与踢线记录。被冻时直接写 freezer cgroup 解冻，进程真没了才重启（两次间隔 ≥10 分钟、每小时 ≤3 次）。
   手动强停的 QQ 不拉起，判据是包状态 `stopped=true`。
3. **系统**：电池优化白名单、关闭 Doze（[`scripts/99-no-doze.sh`](scripts/99-no-doze.sh)）与 ColorOS
   「睡眠待机优化」，见排障。

`wifi_sustain` 持的是 `WifiLock`，只在 Wi-Fi 下有效；蜂窝下靠前两层，另需确认系统没限制 QQ 的
「后台数据」。

## 群资料写操作

`channel.update` 支持换群头像与改群名。改名只走 NapCat 同款的一条内核路径
`modifyGroupName(group, name, isNormalMember)`，结果码 1287 时把身份参数翻成 true 再试一次。不做
二次写入，也不回读校验：内核缓存滞后是常态，而一次改名写两次才会撞上 QQ 的改名频率限制与平台处置。
空名字一律拒绝（那是不可逆的「把群名清掉」）。

名字守卫只做观察：发现某个群名字为空先全量刷新，刷新后仍为空就记一行
`name_guard=empty:<群>(见过=<名字>)`，一个字也不写。

## 测试群

回归脚本（`tests/ws-*.js`）要求显式给测试群；改群名、全员禁言这类破坏性用例还要额外开关：

```bash
SATORI_TEST_GROUP=<群号> node tests/ws-feature-sweep.js
SATORI_TEST_GROUP=<群号> SATORI_DESTRUCTIVE=1 node tests/ws-write-sweep.js
```

## 排障

- 强停或划掉 QQ 会停止服务，看守不会把手动停掉的 QQ 拉回来。
- 锁屏后文字能发而图片、合并转发失败，是网络问题：文字走既有长连接，富媒体要新建连接持续传输。
  关闭系统的「睡眠待机优化」或「深度睡眠」，ColorOS 的开关未必写回配置，确认
  `deep_sleep_is_disable_net_allowed` 与 `deepsleep_network_switch` 已归零。
- 整机流量经 VPN 或 TUN 转发时，只加电池优化白名单不够：Doze 进入 `IDLE` 后经 TUN 的流量全部中断，
  `dumpsys deviceidle disable` 可立即恢复。Doze 没有 UI 开关，用
  [`scripts/99-no-doze.sh`](scripts/99-no-doze.sh) 开机关闭，代价是待机功耗上升。
- 分不清断在哪一层时用 [`scripts/netwatch.sh`](scripts/netwatch.sh)，每 60 秒记录物理链路、本地代理、
  经 TUN 出站、模块状态与电源状态。
- 模块自报在线、消息却一条收不到，多半是被服务端踢线，由 QQ 自己处理。
- 设置读不到：ColorOS 会拦关联启动，在**系统设置 → 应用 → 关联启动**里允许知弦，再重启 QQ。QQ
  会退回文件或默认配置，页面有提示。

## 构建与测试

首次构建需准备 Android 35 平台、R8 与 `org.json`：

```sh
curl -fsSL -o libs/r8.jar https://maven.google.com/com/android/tools/r8/8.9.35/r8-8.9.35.jar
curl -fsSL -o libs/json.jar https://repo1.maven.org/maven2/org/json/json/20250517/json-20250517.jar
./build.sh
./test.sh
```

产物为 `build/SatoriQQ.apk` 与 `build/SatoriQQ-module.zip`。模块不含第三方原生依赖：`native/satori.cpp`
用 Termux 的 clang 编译，dex 用 `.incbin` 内嵌进 `.so`，NEEDED 只有 `liblog/libdl/libm/libc`。

`test.sh` 跑 JVM 单测（含 `Reflect` 反射层的语义测试）。真机巡检脚本需要 QQ 已上线：

```sh
node tests/ws-health.js             # 健康与自检
node tests/ws-ayjx-smoke.js         # 客户端视角的冒烟：协议方法、事件与扩展动作
node tests/ws-poke.js               # 戳一戳：出站 OIDB、入站灰条事件与参数校验
node tests/media-live-probe.js voice # 语音条与文件能不能真发出去，见脚本头注释
```

升级 QQ 后先跑接口面自检，它会指出断在哪个类、哪个字段或哪个回调：

```sh
curl -s -X POST http://127.0.0.1:3001/v1/internal/compat -d '{}' | head -c 400
```

## 文档

- [`docs/SATORI_SUPPORT.md`](docs/SATORI_SUPPORT.md)：协议方法、事件与消息元素
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)：内部结构、QQ 升级检查项与版本号规则

## 许可

本项目可按 [Apache-2.0](LICENSE-APACHE) 或 [MIT](LICENSE-MIT) 许可证使用。
