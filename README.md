onebot-qq
=========

[<img alt="github" src="https://img.shields.io/badge/github-araea/onebot--qq-8da0cb?style=for-the-badge&labelColor=555555&logo=github" height="20">](https://github.com/araea/onebot-qq)

把本机 QQ 做成 OneBot 11 正向 WebSocket 实现端。供 [ayjx](https://github.com/araea/ayjx) 使用。

当前按 QQ 9.3.55（NT）核验。

## 阅读前

需要先了解这些词，不熟的请自行查阅：

- **OneBot 11**：机器人与 QQ 之间的动作 / 事件约定
- **正向 WebSocket**：实现端在本地开端口，机器人当客户端连上来
- **QQNT**：现在手机 QQ 的内核
- **root / Zygisk**：手机已取得最高权限，并能在 App 启动时注入
- **Xposed / LSPosed / vector**：往正在运行的 App 里挂钩子；本仓库用的是 vector
- **作用域 (scope)**：注入进哪一个 App，这里是 QQ
- **冷启动**：杀掉 QQ 再打开，模块才会进入新进程

## 使用

1. 准备已 root、带 Zygisk 的安卓，以及兼容 LSPosed API 的框架。
2. 构建并安装模块，将 QQ（`com.tencent.mobileqq`）加入作用域。
3. 冷启动 QQ。
4. 机器人连接 `ws://127.0.0.1:3001`。

配置可选，放到 QQ 能读到的 `onebot-qq.json`。`token` 一旦填写，两端必须一致。

构建与换机细节见 [`docs/HANDOFF.md`](docs/HANDOFF.md)、[`docs/STACK.md`](docs/STACK.md)。

## 注意事项

- 这是官方 QQ 的外壳，不是桌面协议栈，也不是无 root 方案。
- 注入会被腾讯的环境检查看见，账号可能被踢下线、要求验证。能登录时不要急着卸作用域。
- QQ 换版本必须重新核对接口，不能沿用上一版的假设。
- 「私聊自己」不会真正投递。测发送请用真实群或好友。
- 想停手：从作用域里移除 QQ。

动作与消息段见 [`docs/ONEBOT11_SUPPORT.md`](docs/ONEBOT11_SUPPORT.md)。内核映射见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

## 致谢

- [ayjx](https://github.com/araea/ayjx) - 消费端
- [vector](https://github.com/JingMatrix/LSPosed) - Xposed 框架
- [OpenShamrock](https://github.com/whitechi73/OpenShamrock) - hook 思路参考

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
