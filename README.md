# 知弦（satori-qq）

QQ 的 Satori v1 实现端。Zygisk 注入 QQ NT 内核，本机提供 Satori 接口，收发消息与媒体。

[![GitHub](https://img.shields.io/badge/GitHub-araea%2Fsatori--qq-181717?logo=github&logoColor=white)](https://github.com/araea/satori-qq)
[![Release](https://img.shields.io/github/v/release/araea/satori-qq?logo=github&logoColor=white&color=2ea44f)](https://github.com/araea/satori-qq/releases)

## 安装

- Android 8.0 或更高版本，arm64
- QQ `com.tencent.mobileqq`
- Zygisk Next 1.5.0 或更高。不依赖 LSPosed 或 Xposed

1. 在 KernelSU / APatch / Magisk 中刷入 `SatoriQQ-module.zip`。
2. 重启设备。重启 QQ 或热重载不能替代整机重启。
3. 在 root 管理器的模块列表里点「知弦」打开管理页，确认 QQ 账号与连接状态。

不需要另装应用。Magisk 没有模块网页界面，可以直接用模块自带的 `ctl.sh`。

## 使用

服务默认监听 `127.0.0.1:3001`，默认不校验令牌。端口、令牌与状态通知在管理页的「连接设置」里改，保存后重启 QQ 生效。

Koishi 用 `adapter-satori` 连接：

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

`selfUrl` 与 `server.port` 必须一致。配置令牌后，客户端要使用相同的值。

管理页的「模块开关」是总开关。关闭后不监听端口、不显示通知、不保活，效果与没有安装模块相同；开关写在 QQ 数据目录的 `files/.svc/enabled`，拨动后一两秒内生效。

## 接口

已核验的方法、事件与消息元素见 [Satori v1 接口](docs/SATORI_SUPPORT.md)。

HTTP 请求带 `Authorization: Bearer <token>`。缺失令牌返回 401，令牌不对返回 403。每个非 2xx 响应都是 `{"code": "...", "message": "..."}`，客户端按 `code` 判断。QQ 没有对应能力的方法返回 404。

## 限制 / 风险

- QQ 会检查运行环境。检测到注入框架或 hook 引擎时可能触发登录风控、掉线或反复验证。知弦用 Zygisk 注入，不含 ART hook 引擎。
- 发送与群管理靠反射调用混淆过的类，QQ 升级后可能失配。接收不受影响。升级后模块会自己察觉并在 `/healthz` 的 `qq_upgrade` 里报出断在哪，核对办法见 [QQ 升级后怎么核对](docs/SATORI_SUPPORT.md#qq-升级后怎么核对)。
- QQ 被系统冻结或结束时服务断开。知弦带进程内保活，root 侧另有看守 `qqguard`。保持在线会增加耗电。
- 媒体最多等 45 秒确认，文字 20 秒。超时返回 `send outcome unknown`，原消息可能稍后送达，不要盲目重发。
- 资源链接（收到的媒体与 `upload.create` 的结果，形如 `internal:red/{uin}/_tmp/{id}`）经 `/v1/proxy` 回读。该路由只监听本机、不带鉴权，不要公开转发。
- 落盘只在 QQ 数据目录的隐藏目录 `files/.svc`，线程名与通知渠道 id 不带模块名。
- 写操作没有开关，成功表示已交给 QQ 派发，不代表投递确认。

## 链接

- [Satori v1 接口](docs/SATORI_SUPPORT.md)
- 群身份头衔：`internal/title_list` 读目录与角色，`internal/title_wear` 给自己佩戴身份头衔，或由群主写一枚「群主 / 管理员」专属头衔
- [Satori 协议](https://satori.chat/zh-CN/protocol/api.html)
- [MIT](LICENSE-MIT) / [Apache-2.0](LICENSE-APACHE)
