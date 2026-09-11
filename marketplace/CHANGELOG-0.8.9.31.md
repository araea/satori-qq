## 0.8.9.31

- 修复名片点赞：`internal/like` 此前走的是 PC 端的封包
  `OidbSvcTrpcTcp.0x7E5_104`，在安卓端永远失败——安卓 QQ 根本没有
  `NodeIKernelProfileLikeService`，而服务端把 0x7E5_104 这个 rule 绑定在桌面 appid 上，
  手机 appid 发过去一律被回 `oidb=319 "[oidb] rule type not match appid"`。
  现在改走手机客户端自己的老式 WUP 链路 `VisitorSvc.ReqFavorite`
  （新增 `qq/LegacySvc`）：按客户端 `CardHandler#d4` / `NearbyCardHandler#M2`
  的写法构造 `ToServiceMsg`（`selfUin` / `targetUin` / `favoriteSource=66` / `iCount`），
  交给 `AppInterface#sendToService`，让 QQ 自己完成 `QQService.ReqFavorite` 的 JCE 编码、
  签名与 appid 填写，不再手工拼包。
- 通过 hook `MobileQQServiceBase.dispatchToHandler` 取回回包：按 `RespFavorite.lMID`
  与目标 uin 关联，成功判据为客户端同款 `stHeader.iReplyCode == 0`；
  失败时把服务端原始 `strResult` 一并返回，例如“由于对方权限设置，点赞失败”（10003）、
  “禁止给自己点赞”（54）。
- 版本号升到 0.8.9.31（versionCode 66），沿用同一签名密钥，支持覆盖升级。
