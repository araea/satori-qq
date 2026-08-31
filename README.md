satori-qq
=========

[<img alt="github" src="https://img.shields.io/badge/github-araea/satori--qq-8da0cb?style=for-the-badge&labelColor=555555&logo=github" height="20">](https://github.com/araea/satori-qq)

本机 QQ 的 Satori v1 实现端，供 Koishi `adapter-satori` 连接。

当前按 QQ 9.3.55（NT）核验。

## 使用

1. 构建并安装模块，在 vector 启用并勾选 QQ 作用域。
2. 重启 QQ。
3. Koishi 配置 `adapter-satori`，`endpoint` 指向 `http://127.0.0.1:3001`。

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

## 构建

```sh
./build.sh
pm install -r -d build/SatoriQQ.apk
```

克隆后下载 R8：

```sh
curl -fsSL -o libs/r8.jar https://maven.google.com/com/android/tools/r8/8.9.35/r8-8.9.35.jar
```

## 文档

| 文档 | 内容 |
| --- | --- |
| [`docs/SATORI_SUPPORT.md`](docs/SATORI_SUPPORT.md) | 协议、方法与事件 |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 模块结构与 JNI |
| [`docs/HANDOFF.md`](docs/HANDOFF.md) | 构建与本机环境 |
| [`docs/STACK.md`](docs/STACK.md) | 反检测与换机 |
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
