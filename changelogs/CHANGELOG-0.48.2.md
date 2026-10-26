# 知弦 0.48.2：入群事件带全群名与名字

## 入群欢迎为什么慢了两秒

客户端收到 `guild-member-added` 后才知道有人进群，而这条事件原来只有群号和号码：群名实现端明明已经补进了消息、转成事件时又被丢掉了，新人的昵称更是完全没有。于是想 @ 一句「欢迎 xxx 来到 xxx」的客户端只能先各问一次 `guild.get` 与 `guild.member.get`，问到才开始说——慢的正是这两次往返。

- `guild-member-added` / `guild-member-removed` / `guild-member-updated` 与撤回、戳一戳一样带上群名：`guild.name` 与 `channel.name` 不再是空的，客户端渲染 `{group}` 不必再问一次
- 成员变动事件带上当事人的名字：`user.name`、`user.nick` 与 `member.nick`，取自本地成员表，表里没有就问一次资料服务（本地缓存，不联网）；都拿不到就不带，形状不变
- `guild.member.get` 先读本地成员表，表里确实没有这个人时才向服务器重拉一遍，而且最多等 4 秒。原来单人查询每次都强制重拉整份名单、按 15 秒的耐心等，一次欢迎能因此卡上十几秒；`guild.member.list` 的 `refresh: true` 语义不变
- 顺带把 `getAllMemberList` 的等待时间做成参数：整份名单照旧 15 秒，单人查询用 4 秒

客户端拿到的事件本身就够了的话，欢迎可以在收到事件的那一刻直接发出，不必再等任何查询。

## 附带

- `Compat.CALLBACKS` 补上 `IGetGroupIdentityManageSettingCallback`（群身份管理设置的读取入口），`TYPES` 按源码重新生成——`test.sh` 之前卡在这一步
- `ExtraSvc` 里内联的 `java.util.Locale` 收成 import
