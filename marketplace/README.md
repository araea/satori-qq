# satori-qq

把本机 QQ 做成 [Satori](https://satori.js.org/zh-CN/protocol/api.html) v1 实现端，供 [Koishi](https://koishi.chat/) 的 `adapter-satori` 连接。

当前按 QQ 9.3.55（NT）核验。

## 能力

- Satori v1：登录、消息收发/历史/撤回、群与成员、好友、表态、文件上传和 WebSocket 事件。
- QQ 消息：文本、图片、语音、视频、文件、回复、@、表情、合并转发。
- QQ 客户端手动发出的消息会以独立操作者身份进入 Koishi，可直接触发命令并让 bot 在原会话回复。
- QQ 扩展：戳一戳、资料卡点赞、群签到、精华、名片、头衔、群荣誉、群文件管理、邀请/退群、骰子/猜拳和 QZone 说说。

## 使用

1. 在 LSPosed / vector 中启用本模块，作用域勾选 **QQ**（`com.tencent.mobileqq`）。
2. 安装模块 APK 后重启 QQ。
3. Koishi 启用 `adapter-satori`，`endpoint` 指向 `http://127.0.0.1:3001`（默认端口，可在 `satori-qq.json` 修改）。

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

可选配置见源码仓库中的 `satori-qq.sample.json`。

## 源码与文档

- 源码：https://github.com/araea/satori-qq
- 协议与内部 API：https://github.com/araea/satori-qq/blob/master/docs/SATORI_SUPPORT.md

## 致谢

- [Satori](https://satori.js.org/) / [Koishi](https://koishi.chat/)
- [OpenShamrock](https://github.com/whitechi73/OpenShamrock)
- [Chronocat](https://github.com/chrononeko/chronocat)
