satori-qq
=========

[<img alt="github" src="https://img.shields.io/badge/github-araea/satori--qq-8da0cb?style=for-the-badge&labelColor=555555&logo=github" height="20">](https://github.com/araea/satori-qq)

把本机 QQ 做成 [Satori](https://satori.js.org/zh-CN/protocol/api.html) v1 实现端，供 [Koishi](https://koishi.chat/) 的 `adapter-satori` 连接。

当前按 QQ 9.3.55（NT）核验。

## 阅读前

需要先了解这些词，不熟的请自行查阅：

- **Satori**：Koishi 使用的跨平台机器人协议（HTTP 调方法，WebSocket 收事件）
- **adapter-satori**：Koishi 侧连上本实现端的适配器
- **QQNT**：现在手机 QQ 的内核
- **root / Zygisk**：手机已取得最高权限，并能在 App 启动时注入
- **Xposed / LSPosed / vector**：往正在运行的 App 里挂钩子；本仓库用的是 vector
- **作用域 (scope)**：注入进哪一个 App，这里是 QQ
- **冷启动**：杀掉 QQ 再打开，模块才会进入新进程

## 使用

1. 准备已 root、带 Zygisk 的安卓，以及兼容 LSPosed API 的框架。
2. 构建并安装模块，将 QQ（`com.tencent.mobileqq`）加入作用域。
3. 冷启动 QQ。
4. 在 Koishi 启用 `adapter-satori`：

```yaml
plugins:
  adapter-satori:
    endpoint: 'http://127.0.0.1:3001'
    token: ''   # 与 satori-qq.json 的 token 一致；空则不鉴权
```

连接关系：

- HTTP API：`POST http://127.0.0.1:3001/v1/{resource}.{method}`
- 事件流：`ws://127.0.0.1:3001/v1/events`（先发 `IDENTIFY`，收到 `READY` 后收 `EVENT`）

`platform` 为 `red`（与 Chronocat 一脉，Koishi 插件可按 QQ 非官方实现识别），`adapter` 为 `satori-qq`。

配置可选，放到 QQ 能读到的 `satori-qq.json`。`token` 一旦填写，HTTP `Authorization: Bearer` 与 WebSocket `IDENTIFY.token` 必须一致。

为避免多个本地客户端同时向 QQ 内核写入，状态变更类方法默认全局串行，并在两次动作之间至少等待 1 秒。默认每分钟最多放行 20 个写操作；连续 3 次失败后熔断 2 分钟，再用一次半开探测决定是否恢复。账号恢复在线后的前 30 秒只开放查询。配置项见 `satori-qq.sample.json`；这些保护用于降低突发请求和恢复抖动，不能保证账号不受平台限制。

构建与换机细节见 [`docs/HANDOFF.md`](docs/HANDOFF.md)、[`docs/STACK.md`](docs/STACK.md)。

## 注意事项

- 这是官方 QQ 的外壳，不是桌面协议栈，也不是无 root 方案。
- 注入会被腾讯的环境检查看见，账号可能被踢下线、要求验证。能登录时不要急着卸作用域。
- QQ 换版本必须重新核对接口，不能沿用上一版的假设。
- 「私聊自己」不会真正投递。测发送请用真实群或好友。
- 想停手：从作用域里移除 QQ。

标准方法与消息元素见 [`docs/SATORI_SUPPORT.md`](docs/SATORI_SUPPORT.md)。内核映射见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

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
