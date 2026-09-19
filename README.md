# 知弦 · Satori QQ

在 Android QQ 进程内提供 Satori v1 的 HTTP 与 WebSocket 服务。服务监听 `127.0.0.1:3001`，供 Koishi `adapter-satori` 等客户端连接。

已核验 QQ 9.3.65（NT）。

## 运行条件

- Android 8.0 及以上
- QQ `com.tencent.mobileqq`
- Zygisk Next（模块由它注入，不需要 LSPosed 或任何 Xposed 框架）

## 安装

不是 Xposed 模块：注入由自带的 Zygisk 模块完成，进程里不需要任何框架在场——框架在场会让 QQ
的人脸验证失败。

0.23.0 起进程内**不挂钩子引擎**：引导、内核会话与回包通道都走 JNI 层，一条 ArtMethod 都不改写。

1. 安装发布包里的 `SatoriQQ.apk`（管理界面与设置接口）
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

`selfUrl` 与 `server.port` 保持一致。模块默认不校验令牌。设置 `token` 后，客户端必须使用相同值。

## 配置

首次使用按文件或默认值运行。管理页保存后，页面中的设置在 QQ 下次启动时覆盖文件同名项，高级选项继续按文件配置。「使用文件配置」可解除页面覆盖，同样在下次启动生效。

文件按顺序读取，取第一份有效配置：

```text
/sdcard/Android/data/com.tencent.mobileqq/files/satori-qq.json
/storage/emulated/0/Android/data/com.tencent.mobileqq/files/satori-qq.json
/sdcard/satori-qq.json
/storage/emulated/0/satori-qq.json
```

完整示例见 [`satori-qq.sample.json`](satori-qq.sample.json)。常用设置：

| 设置 | 默认值 | 说明 |
| --- | --- | --- |
| `port` | `3001` | 本地服务端口 |
| `token` | 空 | HTTP 与 WebSocket 鉴权令牌 |
| `status_notification` | `true` | 显示运行状态通知，点击切换到 QQ |
| `request_battery_exemption` | `true` | 首次在线时申请电池优化豁免 |
| `wake_lock_control` | `true` | 在通知中提供唤醒锁开关 |
| `wake_lock_auto` | `true` | 启动时自动获取唤醒锁 |
| `wifi_sustain` | `true` | 有客户端连接时保持 Wi-Fi 锁 |
| `manual_self_messages` | `true` | 把在 QQ 客户端里手打的消息也作为事件投递，作者是 `qq-client:{selfUin}` |
| `manual_self_user_id` | 空 | 上面那个身份的 id；留空用 `qq-client:{selfUin}` |
| `forward_mode` | `auto` | 合并转发的实现：`auto` / `native` / `fake` |
| `media_retry_attempts` | `2` | 富媒体上传失败后的额外尝试次数 |
| `verbose_logs` | `false` | 输出调试日志 |

限频与排队的其余开关见示例文件。修改配置后重启 QQ。

## 常驻与抗冻

QQ 被系统冻住或杀掉，机器人就掉线，所以这里分三层：

1. **模块（进程内，不用 hook）**：会话就绪后每隔十分钟把它自己（QQ）的服务重新 `start` 一遍
   （`Keepalive`）。本机实测 app freezer 的判据是 `oom_score_adj ≥ 900`，而 QQ 主进程在有已启动
   服务时是 ~700（SERVICE_ADJ），冻不着；服务全停才会掉进可冻结区间。每轮同时持唤醒锁（CPU）。
   `GET /healthz` 的 `keepalive` 字段就是这层的状态：`adj=` 当前优先级、`wchan=` 在等什么
   （`do_freezer_trap` 就是被冻住）、`service=` 起服务的结果（后台启动被系统挡住会显示异常类名）。
2. **root 侧看守**（`scripts/qq-revive.sh`，装法见 [`scripts/98-qq-revive.sh`](scripts/98-qq-revive.sh)）：
   每 60 秒看一次 /healthz、MSF 上游连接与踢线记录。真被冻住时不拉前台、不重启，直接写进程自己的
   freezer cgroup 解冻（`0` → `/sys/fs/cgroup/apps/uid_<uid>/cgroup.freeze` 与各 `pid_*`）；
   进程真的不在了才按预算重启（两次间隔 ≥10 分钟、每小时 ≤3 次）。
   **手动停掉 QQ 就不拉起**：判据是包状态的 `stopped=true`（设置里的「强行停止」会置位，系统自己
   杀进程不会），所以想关 QQ 就关得掉。
3. **系统侧**：电池优化白名单、Doze 关闭（[`scripts/99-no-doze.sh`](scripts/99-no-doze.sh)）、
   ColorOS 的「睡眠待机优化」关掉，见下面排障。

**移动数据没有对应的锁**：`wifi_sustain` 只在 Wi-Fi 下有意义（它持的是 `WifiLock`）。走蜂窝网络时
不掉线靠的是上面前两层——进程活着、没被冻、后台数据没被限制。若发现只有蜂窝下会掉，先确认系统的
「后台数据」/「数据节省」没有限制 QQ。

## 排障

强停或划掉 QQ 会停止服务（看守不会把手动停掉的 QQ 拉回来）。唤醒锁能降低进程被回收的概率，
但不能阻止系统在息屏期间断网。

锁屏后文字能发而图片、合并转发失败，是网络问题。文字只需在既有长连接上发一个包。富媒体上传要新建连接并持续传输。

- 关闭系统的「睡眠待机优化」或「深度睡眠」。ColorOS 的开关未必写回配置，确认 `deep_sleep_is_disable_net_allowed` 与 `deepsleep_network_switch` 已归零。
- 整机流量经 VPN 或 TUN 转发时，只加电池优化白名单不够。Doze 进入 `IDLE` 后经 TUN 的流量全部中断，`dumpsys deviceidle disable` 可立即恢复。Doze 没有 UI 开关，用 [`scripts/99-no-doze.sh`](scripts/99-no-doze.sh) 开机关闭，代价是待机功耗上升。

分不清断在哪一层时用 [`scripts/netwatch.sh`](scripts/netwatch.sh)，每 60 秒记录物理链路、本地代理、经 TUN 出站、模块状态与电源状态。

模块自报在线、消息却一条收不到，多半是被服务端踢线，由 QQ 自己处理。

[`scripts/qq-revive.sh`](scripts/qq-revive.sh) 看守 QQ：按踢线记录行数增长、`online=false` 与「MSF 进程没有上游连接」判断，必要时重启 QQ。重启有预算——两次间隔至少 10 分钟、每小时最多 3 次，超了只记日志。装法见 [`scripts/98-qq-revive.sh`](scripts/98-qq-revive.sh) 开头。

配置提供程序由 QQ 在后台拉起。部分 ColorOS 设备会拦截关联启动，在**系统设置 → 应用 → 关联启动**里允许知弦，再重启 QQ。设置读不到时页面会提示，QQ 继续使用文件或默认配置。

## 构建与测试

首次构建需准备 Android 35 平台、R8 与 `org.json`：

```sh
curl -fsSL -o libs/r8.jar https://maven.google.com/com/android/tools/r8/8.9.35/r8-8.9.35.jar
curl -fsSL -o libs/json.jar https://repo1.maven.org/maven2/org/json/json/20250517/json-20250517.jar
./build.sh
./test.sh
```

模块不含任何第三方原生依赖：`native/satori.cpp` 用 Termux 的 clang 直接编译，dex 用 `.incbin`
内嵌进 `.so`，产物的 NEEDED 只有 `liblog/libdl/libm/libc`。产物为 `build/SatoriQQ.apk` 与
`build/SatoriQQ-module.zip`。

`test.sh` 先跑 JVM 单测（含 `Reflect` 反射层的语义测试）。真机巡检脚本需要 QQ 已上线，入口在 `tests/`：

```sh
node tests/ws-health.js             # 健康与自检
node tests/ws-ayjx-smoke.js         # 客户端视角的冒烟：协议方法、事件与扩展动作
node tests/media-live-probe.js voice # 语音条与文件能不能真发出去，见脚本头注释
```

升级 QQ 之后先跑一次接口面自检，它会指出断在哪个类、哪个字段或哪个回调：

```sh
curl -s -X POST http://127.0.0.1:3001/v1/internal/compat -d '{}' | head -c 400
```

## 文档

- [`docs/SATORI_SUPPORT.md`](docs/SATORI_SUPPORT.md)：协议方法、事件与消息元素
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)：内部结构、QQ 升级检查项与版本号规则

## 致谢与许可


本项目可按 [Apache-2.0](LICENSE-APACHE) 或 [MIT](LICENSE-MIT) 许可证使用。
