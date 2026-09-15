# satori-qq

<img src="artwork/icon.svg" width="96" height="96" alt="软件图标" />

在 Android QQ 进程内提供 Satori v1 HTTP 与 WebSocket 服务，监听 `127.0.0.1:3001`，供 Koishi `adapter-satori` 等客户端连接。

已核验 QQ 9.3.55 与 9.3.60.40970（NT）。

## 外观

Material 3 Expressive 风格的双色对话图标，同步适配自适应图标、Android 13 主题单色图标与模块市场。模块没有独立设置界面；常驻通知沿用系统模板，使用同源单色图标，折叠时展示连接数量与端口，展开后展示账号与在线时长，保留唤醒锁操作。

## 运行条件

- Android 8.0 及以上
- QQ `com.tencent.mobileqq`
- 可为 QQ 启用模块作用域的 Xposed 兼容框架

## 安装

1. 安装市场发布的 `SatoriQQ.apk`
2. 在框架中启用模块，把 QQ 加入作用域
3. 重启 QQ
4. 访问 `http://127.0.0.1:3001/healthz`，`online` 为 `true` 即就绪

需要隐藏模块清单标记时，先用普通 APK 完成启用与作用域设置，再覆盖安装同版本的 `SatoriQQ.stealth.apk`。调整作用域前先装回普通 APK。

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

`selfUrl` 与 `server.port` 保持一致。模块默认不校验令牌；设置 `token` 后客户端必须使用相同值。

## 配置

按顺序读取，取第一份有效配置：

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
| `foreground_keepalive` | `true` | 在线时以前台服务保持 QQ 主进程 |
| `request_battery_exemption` | `true` | 首次在线时申请电池优化豁免 |
| `wake_lock_control` | `true` | 在通知中提供唤醒锁开关 |
| `wake_lock_auto` | `true` | 启动时自动获取唤醒锁 |
| `wifi_sustain` | `true` | 有客户端连接时保持 Wi-Fi 锁 |
| `media_retry_attempts` | `2` | 富媒体上传失败后的额外尝试次数 |
| `verbose_logs` | `false` | 输出调试日志 |
| `anti_detect` | `true` | Java 层环境检测处理 |
| `maps_hide` | `true` | Native 层进程信息过滤 |
| `block_turing_risk` | `true` | 停止 Turing 风控入口 |
| `block_server_kick` | `true` | 停止本地强制下线处理；服务端会话失效时仍需重新登录 |
| `fake_imei` / `fake_android_id` / `fake_serial` | 空 | 设备标识；留空使用真实值，设置时应保持一致 |

过检测、限频与排队的其余开关见示例文件。修改配置后重启 QQ。

## 排障

强停或划掉 QQ 会停止服务。前台服务与唤醒锁能降低进程被回收或冻结的概率，不能阻止系统在息屏期间断网。

锁屏后文字能发而图片、合并转发失败，是网络问题：文字只需在既有长连接上发一个包，富媒体上传需要新建连接并持续传输。

- 关闭系统的「睡眠待机优化」或「深度睡眠」。ColorOS 的开关未必写回配置，确认 `deep_sleep_is_disable_net_allowed` 与 `deepsleep_network_switch` 已归零。
- 整机流量经 VPN 或 TUN 转发时，只加电池优化白名单不够。Doze 进入 `IDLE` 后经 TUN 的流量会全部中断，`dumpsys deviceidle disable` 可立即恢复。Doze 没有 UI 开关，用 [`scripts/99-no-doze.sh`](scripts/99-no-doze.sh) 开机关闭，代价是待机功耗上升。

分不清断在哪一层时用 [`scripts/netwatch.sh`](scripts/netwatch.sh)，每 60 秒记录物理链路、本地代理、经 TUN 出站、模块状态与电源状态。

模块自报在线、消息却一条收不到，多半是被服务端踢线了。踢线在客户端有两条互不经过的处理链，模块两条都拦：内核那条（`IKickApi`、票据刷新、取 UID 失败）拦在 `NTKickProcessor` 与另外两个入口，MSF 那条拦在 `MainService$MyErrorHandler` 的**处理器入口**——`onKicked` / `onKickedAndClearToken` / `onKickedInternal` / `onCloneError`。

拦处理器入口而不是它最后的 `popupNotification`，是因为毁本地登录态的写在出口之前：`onKickedInternal` 里先做 `setAutoLogin(false)` 与 `MsfSdkUtils.updateSimpleAccount(uin, false)`——后者把 `files/user/u_<uin>_t` 改名成 `_f`，账号就从已登录列表里没了，下次启动没有可自动登录的对象，才会停在登录页要短信验证。这是**盘上的**状态，跟 `blocked_kicks` 一样不随进程重启消失。

所以除了拦踢线，模块在踢线后的 15 分钟"善后期"里还做两件事：把要摘账号/关自动登录的写入顶回去（`login_state.kept` 记命中次数），以及在离线时把 `u_<uin>_t` 与自动登录开关修回来（`qk_guard.log` 里的 `self-heal` 行）。善后期跨进程重启成立——判据除了内存计数还看 `qk_kick.log` 的修改时间，因为看守恰好在窗口里 force-stop QQ。用户自己按的退出登录（reason `user`/`switchAccount`）会让善后期立即停手。

`/healthz` 里：`kick_hook` 为 0 表示这一版没拦住踢线（0.8.9.46 起正常为 12），`logout_guard.hooks` 为 0 表示没拦住踢线后的登出（0.8.9.46 起正常为 5），`login_state.hooks` 为 0 表示没拦住摘账号（正常为 2）。逐条原文在 `kick_log` / `logout_guard.log`，落盘到 `qk_kick.log` / `qk_guard.log`，`kick_log` 里现在带 `kickType=`（0.10.0 起名字按 QQ 的 `KickedType` 声明顺序取，仍带 `?`；判定口径与不确定性写在 [`docs/ANTIDETECT.md`](docs/ANTIDETECT.md)）、`sigKick=`、`seqno=`、`sigLen=`、`sameDevice=`，内核那条路还带 `appId=`（哪一端的登录把本机顶了）；每行的 `up=<秒>` 是这次登录活了多久——同一个数反复出现说明是会话/票据寿命，长短不一才像行为打分。`sso.session_errors` 是模块自己发的 SSO 请求撞上 QQ 认「票据失效」那组错误码的次数——它涨了才说明踢线是接口调用把会话打废的，而不是环境检测；原文在 `qk_sso.log`。用 [`scripts/qq-revive.sh`](scripts/qq-revive.sh) 看守：它按踢线记录行数增长、`online=false` 与「MSF 进程没有上游连接」判断，必要时重启 QQ，装法见 [`scripts/98-qq-revive.sh`](scripts/98-qq-revive.sh) 开头。

## 构建与测试

首次构建需准备 Android 35 平台、R8 与 `org.json`：

```sh
curl -fsSL -o libs/r8.jar https://maven.google.com/com/android/tools/r8/8.9.35/r8-8.9.35.jar
curl -fsSL -o libs/json.jar https://repo1.maven.org/maven2/org/json/json/20250517/json-20250517.jar
./build.sh
./test.sh
```

产物为 `build/SatoriQQ.apk` 与 `build/SatoriQQ.stealth.apk`。`test.sh` 先跑 JVM 单测，再编译运行 `tests/mapshide-filter-test.c`。真机巡检脚本（需要 QQ 已上线）：

```sh
node tests/ws-health.js            # 健康与自检
node tests/ws-kernel-extras.js     # 扩展读取动作
node tests/ws-kernel-writes.js     # 扩展写入动作，自带清理
node tests/internal-kernel-probe.js # 官方 internal 路由 + 0.8.9.39 新增动作
node tests/media-live-probe.js voice # 语音条/文件/群文件能不能真发出去，见脚本头注释
```

升级 QQ 之后先跑一次接口面自检，它会指出断在哪个类、哪个字段或哪个回调，再跑上面的巡检：

```sh
curl -s -X POST http://127.0.0.1:3001/v1/internal/compat -d '{}' | head -c 400
```

版本 APK 与变更记录发布在[模块市场](https://github.com/Xposed-Modules-Repo/com.satori.qq)。

## 版本号

`versionName` 走语义化版本 `主.次.补丁`，从 **0.9.0** 开始：

- **主**：Satori 方法表或 `/v1/internal` 动作有破坏性变更（删方法、改参数含义、改事件字段）。
- **次**：新增方法 / 动作 / 事件，或对 QQ 的行为适配、反检测策略这类会影响兼容性的改动。
- **补丁**：修 bug、改文案、改默认值，调用方不用动。
- 需要区分同一天发的多次构建时，第 4 段临时当构建号用（`0.9.1.2`），并在下一次发版时并回三段。

配一个数字递增的 `versionCode`：Android 判升级、市场判更新、Xposed 管理器判"有新版本"用的都是它，
`versionName` 只负责给人看。发布 tag 是 `{versionCode}-{versionName}`。

0.9.0 之前的三段固定成了 `0.8.9`、只递增第 4 段（`0.8.9.1` 一直到 `0.8.9.46`），
文档与变更记录里那些 `0.8.9.x 起` 的说法指的是旧编号，两套编号的对应关系见
[`marketplace/`](marketplace/) 下的变更记录。

版本号要同步改三处：`AndroidManifest.xml`、`AndroidManifest.stealth.xml`、`SatoriHub.APP_VERSION`。
`tests/ManifestStealthTest` 会校验三者一致、且 `versionName` 符合上面的形状。

## 文档

- [`docs/SATORI_SUPPORT.md`](docs/SATORI_SUPPORT.md)：协议方法、事件与消息元素
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)：内部结构与 QQ 升级检查项
- [`docs/ANTIDETECT.md`](docs/ANTIDETECT.md)：QQ 检测面与模块的处理边界

## 致谢与许可

过检测实现参考 [QQEnhancedBypass](https://github.com/Xalsace/QQEnhancedBypass)。模块清单的 stealth 变体是为避开 [Duck Detector](https://github.com/eltavine/Duck-Detector-Refactoring) 的安装包元数据检查而加。

本项目可按 [Apache-2.0](LICENSE-APACHE) 或 [MIT](LICENSE-MIT) 许可证使用。
