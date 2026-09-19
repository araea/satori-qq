## 0.8.9.31

0.8.9.30 未在模块市场发布，其改动一并列在本次。

- 修复名片点赞：`internal/like` 此前走 PC 端封包 `OidbSvcTrpcTcp.0x7E5_104`，在安卓端永远失败——安卓 QQ 根本没有 `NodeIKernelProfileLikeService`，而服务端把 0x7E5_104 这个 rule 绑定在桌面 appid 上，手机 appid 发过去一律被回 `oidb=319 "[oidb] rule type not match appid"`。现在改走手机客户端自己的老式 WUP 链路 `VisitorSvc.ReqFavorite`（新增 `qq/LegacySvc`）：按客户端 `CardHandler#d4` / `NearbyCardHandler#M2` 的写法构造 `ToServiceMsg`（`selfUin`、`targetUin`、`favoriteSource=66`、`iCount`），交给 `AppInterface#sendToService`，让 QQ 自己完成 `QQService.ReqFavorite` 的 JCE 编码、签名与 appid 填写，不再手工拼包
- 通过 hook `MobileQQServiceBase.dispatchToHandler` 取回回包：按 `RespFavorite.lMID` 与目标 uin 关联，成功判据为客户端同款 `stHeader.iReplyCode == 0`；失败时把服务端原始 `strResult` 一并返回，例如「由于对方权限设置，点赞失败」（10003）、「禁止给自己点赞」（54）
- 修复 `<dice/>` 与 `<rps/>` 元素被整段丢弃的问题。QQ 的骰子和猜拳是两个魔法表情（358 / 359），Satori 元素表里没有对应标签，这两个空元素原先落进未知元素分支后被直接丢掉：`message.create` 返回空列表、不报错，调用方却以为消息发出去了。现在 `Codec` 认下这两个标签并映射到对应表情，`internal/dice`、`internal/rps` 与 `internal/capabilities` 改用同一组常量
- 修正 `internal/version` 与 `healthz` 上报的版本号：0.8.9.29 只改了清单，代码里的 `APP_VERSION` 还停在 0.8.9.28
- 版本号 0.8.9.31（versionCode 66），沿用同一签名密钥，支持从 0.8.9.29 直接覆盖升级
