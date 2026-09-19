## 0.8.9.34

- 修复只能接收、不能发送的问题：QQ 账号尚未可知时，`READY` 会把登录账号写成 `0`。客户端把这次 `READY` 里的账号当作自己的身份存下，之后每个请求都带 `Satori-User-ID: 0`；模块把 `0` 当成指向另一个登录，一律回 `404 unknown Satori-User-ID: 0`，于是 `message.create`、`message.list` 等写读接口全部失败，接收不受影响。文字消息与图片消息失败的是同一个原因
  - 账号未知时不再回 `READY`，改为挂起，账号可知后（每秒轮询）补发，客户端拿到的是真实 QQ 号
  - 请求头里的选择器为 `0` 或留空时按未指定处理，照常应答；只有明确指向另一账号的选择器才回 404。已经存了 `0` 的客户端不必重启即可恢复
- 新增 `tests/LoginSelectorTest`，覆盖 `0`、空值、本机账号与另一账号四种选择器
- [`docs/SATORI_SUPPORT.md`](https://github.com/araea/satori-qq/blob/master/docs/SATORI_SUPPORT.md) 的连接约定补记 `READY` 与 `Satori-User-ID` 的口径
- 版本号 0.8.9.34（versionCode 69），沿用同一签名密钥，支持从 0.8.9.33 直接覆盖升级
