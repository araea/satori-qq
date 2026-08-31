satori-qq
=========

[<img alt="github" src="https://img.shields.io/badge/github-araea/satori--qq-8da0cb?style=for-the-badge&labelColor=555555&logo=github" height="20">](https://github.com/araea/satori-qq)

把本机 QQ 做成 [Satori](https://satori.js.org/zh-CN/protocol/api.html) v1 实现端，供 [Koishi](https://koishi.chat/) 的 `adapter-satori` 连接。

当前按 QQ 9.3.55（NT）核验。

## 能力

- Satori v1：登录、消息收发/历史/撤回、群与成员、好友、表态、文件上传和 WebSocket 事件。
- QQ 消息：文本、图片、语音、视频、文件、回复、@、表情、合并转发；消息 ID 使用可跨进程引用的 QQ `msgId`。
- QQ 客户端手动发出的消息会以独立操作者身份进入 Koishi，可直接触发命令并让 bot 在原会话回复；API 发送回声不会回环。
- QQ 扩展：戳一戳、资料卡点赞、群签到、精华、名片、头衔、群荣誉、群文件管理、邀请/退群、骰子/猜拳和 QZone 说说。
- 实用查询：群概览、成员/联系人搜索、消息前后文、资源下载和实现端健康状态。
- 稳定性：写操作串行、限频和熔断；发送回声去重；短暂断线按事件序号恢复。

标准方法、事件、消息元素与全部内部接口见 [`docs/SATORI_SUPPORT.md`](docs/SATORI_SUPPORT.md)。

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
  server:
    port: 5140
    selfUrl: 'http://127.0.0.1:5140'
  assets-local: {}
  adapter-satori:
    endpoint: 'http://127.0.0.1:3001'
    token: ''   # 与 satori-qq.json 一致；空则不鉴权
```

`selfUrl` 让 Koishi 本地资产使用 QQ 进程可访问的 HTTP 地址；请确保它与 `server.port` 一致。

- HTTP：`POST http://127.0.0.1:3001/v1/{resource}.{method}`
- 事件：`ws://127.0.0.1:3001/v1/events`（`IDENTIFY` → `READY` → `EVENT`）
- 恢复：省略 `IDENTIFY.sn` 表示新会话；显式传入最后收到的 `sn` 才回放断线事件

`platform` 为 `red`，`adapter` 为 `satori-qq`。可选配置见 `satori-qq.sample.json`。

Koishi Core 默认忽略 `userId === selfId` 的消息。模块因此只对 QQ 客户端手动发送的
消息使用稳定虚拟身份 `qq-client:{QQ号}`，真实 QQ 号保留在
`session.event.satoriQq.actualUserId`；普通入站消息和 `message.create` 不改写。
可用 `manual_self_messages=false` 关闭，或用 `manual_self_user_id` 自定义虚拟身份。

Koishi 可直接通过官方适配器调用 QQ 内部接口：

```js
const overview = await bot.internal.groupOverview({ guild_id: '123456' })
const members = await bot.internal.group.member.search({
  guild_id: '123456', query: '群名片', limit: 20,
})
const context = await bot.internal.messageContext({
  channel_id: '123456', message_id: '7000000000000000000', before: 5, after: 5,
})
```

可用动作及群文件子操作可从 `POST /v1/internal/capabilities` 动态获取。

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
