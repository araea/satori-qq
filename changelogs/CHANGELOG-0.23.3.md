## 0.23.3 · 知弦

### 接口自述与真实能力对齐

- `internal/capabilities` 不再列 `like`：0.23.0 起资料卡点赞随着「只走 JNI 层」一起没了，但能力表里还留着，
  客户端照着调只会拿到 404 再白试一轮。现在单独给一段 `removed`，逐项写明「哪个版本、为什么」。
- 曾经有过、后来移除的动作（0.17.0 的内核查询、0.23.0 的 `like`）报错时与「方法名写错」分开：
  404 的响应体里带 `code=removed_action`，客户端按机器可读的字段判断，不必匹配会变的中文文案。

### 群名兜底

内核的 `GroupSimpleInfo.groupName` 对某些群是空的（实测测试群 `280183116` 就是，`guild.list`
与 `guild.get` 一起读回来都是空），于是 `guild.get` / `channel.get` 的名字字段整个是缺的。
现在依次退到 `remarkName` 与群详情里的真名，且会话一就绪就自己预热一批（数量封顶）：
单个群的显式查询最多等 3 秒，列表接口仍走异步，不为一个群把整页拖住。

`getGroupDetailInfo` 的 `IOperateCallback` 只有 `onResult(int, String)`——**回调里没有值**，
名字落在内核缓存里；能读到值的是 `batchQueryCachedGroupDetailInfo` 的
`onResult(ArrayList<GroupDetailInfo>)`。所以是「先读缓存，空了拉一次，再读缓存」两步。
第一版只调前者并指望回调带列表，于是 latch 永不释放、每次都超时——`internal/status` 现在有
`group_name_probe` 记着最近一次的过程，别再犯这种「回调形状猜错就静默超时」的错。

### 入站计数

`internal/status` 新增 `inbound`：`recv` / `add` / `update` / `gray_tip` / `emitted` /
`notices` / `manual_self`。事件收不到时先看这几个——能分开「记录压根没进来」「进来了但没转成
事件」「转了但客户端没消费」三种情况。

### 测试

`tests/ws-ayjx-smoke.js` 原先连的是 `/` 上的 OneBot 风格动作，实现端早就不提供，脚本会静默
零输出地「通过」。按 ayjx（Rust 端）实际调用的 25 个动作重写：只读项要读到对的形状，会改状态的
项只用参数校验确认「路由到了真实实现」，`internal/like` 这类已知缺口单独断言文案能被认出来。
