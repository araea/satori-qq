satori-qq
=========

[<img alt="github" src="https://img.shields.io/badge/github-araea/satori--qq-8da0cb?style=for-the-badge&labelColor=555555&logo=github" height="20">](https://github.com/araea/satori-qq)

本机 QQ 的 Satori v1 实现端，供 Koishi `adapter-satori` 连接。

当前按 QQ 9.3.60.40970（NT）核验。

## 使用

1. 构建并安装模块（`build/SatoriQQ.apk`），在 vector 启用并勾选 QQ 作用域。
2. 反检测加固（可选，针对 Duck Detector 类包扫描）：启用成功后执行
   `pm install -r -d build/SatoriQQ.stealth.apk`。该变体清单不含任何
   `xposed*` meta-data，跨应用包扫描（`getInstalledApplications(GET_META_DATA)`）
   不再能把它识别为模块；LSPosed 守护进程对已启用模块只认
   `assets/xposed_init`，覆盖安装与后续升级均照常加载。改作用域时先装回
   引导包再操作。
3. 重启 QQ。
4. Koishi 配置 `adapter-satori`，`endpoint` 指向 `http://127.0.0.1:3001`。

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

**常驻(合作式保活)。** 服务在线时,模块把 QQ 主进程提升为**前台服务**(复用 QQ 自身
一个声明了 `dataSync` 的 service),进程随即进入前台服务优先级档,系统的低内存回收与
OEM 后台冻结默认放过它——像 VPN 一样「开着就一直连着」。这不是反复拉起进程的看门狗:
**用户强停或划掉 QQ,服务就随之干净断开、不复活**,控制权在用户手里。首次在线若未加
电池优化白名单,会弹一次系统「忽略电池优化」对话框申请 Doze 豁免(授予后后台保活更稳)。
`foreground_keepalive: false` 可关,`request_battery_exemption: false` 可只关一次性申请。

**状态通知。** 前台服务的通知即一条静默常驻通知,随状态切换「运行中 / 等待登录 /
服务异常」,显示账号、端口、连接数与在线时长。以 QQ 自身身份发出,模块不声明任何权限;
需 QQ 具备通知权限(Android 13+ 的 `POST_NOTIFICATIONS`,默认已授予)。
`status_notification: false` 可关闭独立通知(开启保活时它仍作为前台服务通知存在)。

另有本地免鉴权探针 `GET http://127.0.0.1:3001/healthz`,返回 `online`/`listening`/
在线时长与 `keepalive`/`notice` 诊断,机器可读地区分「真在线」与「端口在听但内核离线」。

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
