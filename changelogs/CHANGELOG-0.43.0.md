# 0.43.0 — 适配 QQ 9.3.80

QQ 升到 9.3.80（versionCode 16708）。接口面只增不减，但 pskey 的取法在真机上炸了，这版一并修掉。

### 9.3.80 核对

- 新旧 dex 对比：模块用到的 90 个内核类型全在，变化只有新增
  （`IQQNTWrapperSession.onGetAppActiveStatus`、`MsgAttributeInfo.kuoLieScene`），没有删改。
  `IDependsAdapter.getAppActiveStatus` 与 `IKernelMsgService.setKernelMsgStatusDelegate` 换了签名，
  但模块没用它们。
- `internal/compat` 覆盖到 169 项（91 类型 / 7 服务入口 / 27 结构体 / 40 回调 / 3 生命周期 / 1 pskey），
  在 9.3.80 的 `base.apk` 上全过。
- 真机全量巡检 58 项过 57；没过的仍是戳一戳，服务端回 `Process_Nudge failed`（换成员照常能戳），
  与本次升级无关。
- 群公告 / 精华的内核入口（`getGroupBulletinList` / `fetchGroupEssenceList`）在 9.3.80 上依旧一调即
  段错误，继续走 qun.qq.com 的网页 CGI。

### pskey 修复

- 9.3.80 上 `getTipOffService()` 不再回 null，但 `IKernelTipOffService.getPskey` 一调就 signal 11
  （`libkernel.so+0x192f918`），CrashDefend 把 QQ 重启。`internal/pskey` 以及依赖它的群公告读写、
  群精华读、空间说说全部中断。
- 改成只走登录服务：`IKernelLoginService.getLoginService()` 是接口上的静态入口（原先错当会话方法，
  永远取不到），再用 `guestA2ToPskey(long, ArrayList, IGuestPskeyCallback)` 要票。原来这一步传的是
  `IGetPskeyCallback`，接口不对，就算走到也会让内核按错误类型回调。
- `internal/compat` 新增 pskey 一项：静态 `getLoginService()` 与 `guestA2ToPskey` 的三参签名在不在。
  `TYPES` / `CALLBACKS` 随之重生成。

### 其他

- 删掉不再使用的 `QQClient.getTipOffService()` 与 `IGetPskeyCallback` 表项。
- 文档版本口径改到 9.3.80，并把 0.41.1 起「公告 / 精华走网页 CGI」写对。
- `ReflectTest` 增加接口静态方法的解析用例，守住 pskey 的入口形状。

改动在 Java 层，代码内嵌在 Zygisk 的 `.so` 里：装机后**重启手机**才生效。重启后可用
`internal/pskey`、`group_bulletin_get`、`group_essence_list` 做三项验收；本版发布前已完成静态接口核对
与 9.3.80 上的其余功能巡检。
