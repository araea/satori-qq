satori-qq
=========

[<img alt="github" src="https://img.shields.io/badge/github-araea/onebot--qq-8da0cb?style=for-the-badge&labelColor=555555&logo=github" height="20">](https://github.com/araea/onebot-qq)

把本机 QQ 做成 [Satori](https://satori.js.org/zh-CN/protocol/api.html) v1 实现端，供 [Koishi](https://koishi.chat/) 的 `adapter-satori` 连接。

当前按 QQ 9.3.55（NT）核验。

## 使用

1. 构建并安装模块（见「构建」），在 vector 启用 `com.satori.qq`，把 QQ（`com.tencent.mobileqq`）加入作用域。
2. 装完立刻重启 QQ：

```sh
am force-stop com.tencent.mobileqq
am start -n com.tencent.mobileqq/com.tencent.mobileqq.activity.SplashActivity
am startservice -n com.tencent.mobileqq/.app.CoreService
am startservice -n com.tencent.mobileqq/.msf.service.MsfService
```

3. Koishi 启用 `adapter-satori`：

```yaml
plugins:
  adapter-satori:
    endpoint: 'http://127.0.0.1:3001'
    token: ''   # 与 satori-qq.json 一致；空则不鉴权
```

- HTTP：`POST http://127.0.0.1:3001/v1/{resource}.{method}`
- 事件：`ws://127.0.0.1:3001/v1/events`（`IDENTIFY` → `READY` → `EVENT`）

`platform` 为 `red`，`adapter` 为 `satori-qq`。可选配置见 `satori-qq.sample.json`。

方法与内部接口见 [`docs/SATORI_SUPPORT.md`](docs/SATORI_SUPPORT.md)。

## 构建

```sh
./build.sh
cp build/SatoriQQ.apk /data/local/tmp/SatoriQQ.apk
pm install -r -d /data/local/tmp/SatoriQQ.apk
```

克隆后先下 R8：

```sh
curl -fsSL -o libs/r8.jar https://maven.google.com/com/android/tools/r8/8.9.35/r8-8.9.35.jar
```

换机、工具链路径与 vector scope 见 [`docs/HANDOFF.md`](docs/HANDOFF.md)。

## 致谢

- [Satori](https://satori.js.org/) / [Koishi](https://koishi.chat/) - 协议与消费端
- [vector](https://github.com/JingMatrix/LSPosed) - Xposed 框架
- [OpenShamrock](https://github.com/whitechi73/OpenShamrock) - hook 思路参考
- [Chronocat](https://github.com/chrononeko/chronocat) - QQNT 上的 Satori 先例

## QQ 群

- 956758505

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
