satori-qq
=========

[<img alt="github" src="https://img.shields.io/badge/github-araea/satori--qq-8da0cb?style=for-the-badge&labelColor=555555&logo=github" height="20">](https://github.com/araea/satori-qq)

本机 QQ 的 Satori v1 实现端。任何 Satori 协议客户端均可连接（如 Koishi `adapter-satori`）。

当前按 QQ 9.3.60.40970（NT）核验。

## 使用

1. 构建并安装模块（`build/SatoriQQ.apk`），在 vector 启用并勾选 QQ 作用域。
2. 反检测加固（可选，针对 Duck Detector 类包扫描）：启用成功后执行
   `pm install -r -d build/SatoriQQ.stealth.apk`。该变体清单不含任何
   `xposed*` meta-data，跨应用包扫描（`getInstalledApplications(GET_META_DATA)`）
   无法将其识别为模块；LSPosed 加载已启用模块只依赖 `assets/xposed_init`，
   覆盖安装与升级照常生效。需要改作用域时，先装回引导包再操作。
3. 重启 QQ。
4. 客户端连接 `http://127.0.0.1:3001`。Koishi 配置示例：

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

## 常驻与通知

- **前台保活**（`foreground_keepalive`，默认开）：服务在线时将 QQ 主进程提升为
  前台服务，降低被系统低内存回收与后台冻结的概率。用户强停或划掉 QQ 后服务随之
  断开，不会自动复活。首次在线若未加入电池优化白名单，会弹出一次「忽略电池优化」
  授权对话框（`request_battery_exemption: false` 可关闭该申请）。
- **状态通知**（`status_notification`，默认开）：以 QQ 身份显示一条静默常驻通知，
  随状态切换「运行中 / 等待登录 / 服务异常」，含账号、端口、连接数与在线时长。
  关闭后若保活开启，仍保留前台服务通知。
- **唤醒锁开关**（`wake_lock_control`，默认开）：常驻通知上带「获取 / 释放唤醒锁」
  按钮，以 QQ 身份持有 `PARTIAL_WAKE_LOCK` 与高性能 Wi-Fi 锁。手动持有默认关闭；但
  **每次发送等写操作都会自动持锁**（带 3 分钟超时兜底），不再依赖你记得去点按钮。
- **Wi-Fi 常驻锁**（`wifi_sustain`，默认开）：只要有 Satori 客户端连着就长期持有
  Wi-Fi 锁。收消息同样会被息屏省电拖住，不只是发送。
- **富媒体重试**（`media_retry_attempts`，默认 2）：图片 / 语音 / 视频的上传由 QQ 内核
  在 `sendMsg` 里同步完成，是网络变差时最先失败的一环，回调
  `code=-1 / rich media transfer failed`。上传失败发生在消息投递之前，对端什么都没
  收到，因此重试不会重复发送。退避 `media_retry_backoff_ms`，总预算
  `media_retry_budget_ms`（默认 45s，留在调用方 HTTP 超时之内）。
- **健康检查**：`GET http://127.0.0.1:3001/healthz`，本地免鉴权，返回 `online` /
  `listening` / 在线时长与 `keepalive` / `notice` / `wakelock` 状态，用于区分
  「真在线」与「端口在听但内核离线」。

## 系统省电策略（重要）

模块能做的都做了，但有一类问题**在应用层无解**，只能靠系统设置：

> **建议关闭厂商 ROM 的「睡眠待机优化 / 深度睡眠」。**
> ColorOS 等国产 ROM 会在夜间预测的睡眠窗口里**直接切断数据通路**——不是限速，是关网，
> 且 Wi-Fi 与移动数据一起关，所以换网络也复现。

典型症状很好认：**纯文字消息正常，图片和合并转发失败**。文字一个包顺着已建立的 MSF
长连接就出去了；富媒体上传要新建连接 + 持续吞吐，断网时第一个失败。深夜定时推送最容易
撞上这一条，而白天手动发一切正常。

排查（需 root）：

```sh
# 是否允许深度睡眠关网、关哪几种网络（3 = Wi-Fi + 移动数据）
grep -E 'disable_net_allowed|network_switch|predict_(start|end)_time' \
  /data/user_de/0/com.oplus.battery/shared_prefs/DeepSleepSharepref.xml
# 模块 / QQ / 代理应用是否都在电池优化白名单里
dumpsys deviceidle whitelist | grep -E 'mobileqq|<你的代理应用>'
```

若整机流量走本地代理 / VPN（Clash 之类），那个应用**也要**加进电池优化白名单——它被
限制时，所有应用一起断网：`dumpsys deviceidle whitelist +<包名>`。

次要因素：息屏后 Wi-Fi 省电会把到 AP 的 RTT 从 ~4ms 抬到 ~27ms（实测）。这个应用层同样
管不了——Android 14+ 把 `WIFI_MODE_FULL_HIGH_PERF` 重映射成低延迟锁，而低延迟锁只在
亮屏且持锁应用在前台时才激活。需要的话由设备侧 `cmd wifi force-hi-perf-mode enabled`
处理（模块常驻的那枚 Wi-Fi 锁正是给它提供作用对象——一把锁都没有时该强制模式无从生效）。

## 构建

```sh
./build.sh
pm install -r -d build/SatoriQQ.apk
```

克隆后下载 R8 与 org.json（都在 `libs/`，均被 gitignore）：

```sh
curl -fsSL -o libs/r8.jar https://maven.google.com/com/android/tools/r8/8.9.35/r8-8.9.35.jar
curl -fsSL -o libs/json.jar https://repo1.maven.org/maven2/org/json/json/20250517/json-20250517.jar
```

## 测试

`./test.sh` 跑 JVM 单元测试，覆盖不碰 QQ 内核的纯逻辑部分。真机行为另见
`tests/ws-health.js` 与 `scripts/` 下的现场脚本。

## 发布

APK 与版本记录统一发布在模块市场
[Xposed-Modules-Repo/com.satori.qq](https://github.com/Xposed-Modules-Repo/com.satori.qq)，
本仓库不维护 release。

## 文档

| 文档 | 内容 |
| --- | --- |
| [`docs/SATORI_SUPPORT.md`](docs/SATORI_SUPPORT.md) | 协议、方法与事件 |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 模块结构与 JNI |
| [`satori-qq.sample.json`](satori-qq.sample.json) | 配置项 |

## QQ 群

956758505

<br>

#### License

<sup>
Licensed under either of <a href="LICENSE-APACHE">Apache License, Version
2.0</a> or <a href="LICENSE-MIT">MIT license</a> at your option.
</sup>

<br>

<sub>
Unless you explicitly state otherwise, any contribution intentionally submitted
for inclusion in this crate by you, as defined in the Apache-2.0 license, shall
be dual licensed as above, without any additional terms or conditions.
</sub>
