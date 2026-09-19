## 0.23.6 · 知弦

### 接口自述与真实能力对齐

- `internal/capabilities` 不再列 `like`：0.23.0 起资料卡点赞随着「只走 JNI 层」一起没了，但能力表里还留着，
  客户端照着调只会拿到 404 再白试一轮。现在单独给一段 `removed`，逐项写明「哪个版本、为什么」。
- 曾经有过、后来移除的动作（0.17.0 的内核查询、0.23.0 的 `like`）报错时与「方法名写错」分开：
  404 的响应体里带 `code=removed_action`，客户端按机器可读的字段判断，不必匹配会变的中文文案。

### 骰子与猜拳改成超级表情（按 NapCat 的实现对齐）

之前发的是 `FaceElement.faceType=1`，群里收到的是一个 16px 的小脸。对照
[NapCat](https://github.com/NapNeko/NapCatQQ) 的实现改对，要点有两处：

- `FaceType` 的取值是 `1 老表情 / 2 常规表情 / **3 动画贴纸 = 超级表情** / 4 Lottie / 5 poke`
  （`packages/napcat-core/types/msg.ts`）。先前猜的 2 是「常规表情」，所以还是小脸。
- 光有 `faceType=3` 不够，还要带贴纸身份：`faceText`（`[骰子]` / `[包剪锤]`）、`packId='1'`、
  `stickerId`（骰子 `33`、猜拳 `34`）、`stickerType=2`、`sourceType=1`；线路上 QQ 会把它编成
  `commonElem{serviceType:37}` 里的一段 `QBigFaceExtra`（`aniStickerPackId` / `aniStickerId` /
  `faceId` / `sourceType` / `resultId` / `preview` / `randomType`），但那是内核自己做的转换，
  交给内核的仍然是 `FaceElement`，照着 NapCat 的 dice / rps 段填即可。

顺带把普通表情的 `faceType` 也按同一份实现的选法补齐：id ≥ 222 用 2，小于 222 用 1。
先前一律发 1，id 大于 222 的表情在 QQ 里是画不出来的。

### 参数错的请求不再打开出站熔断

出站熔断的判据是「内核是不是在拒绝干活」，但 1400/1404（调用方参数不对）也被算了进去：那是
实现端进内核之前自己回绝的，跟内核无关。后果是任何客户端**连发三个缺参请求**就能把出站通道锁
`circuit_open_ms`（默认两分钟），期间连正常发送也被拒（实测：巡检里的缺参用例正好触发，后面
十几项全挂）。现在 1400/1404 与从未实现的方法（404）都不计进熔断。

### 群名：一段追错了方向的兜底，已收掉

0.23.2/0.23.3 加过一层「内核 simple-info 没名字就去要群详情」的兜底（最后自己写了三个内核
接口的调用）。事后查明那个空名字是**测试自己改坏的**——`ws-write-sweep` 的改名用例把原名读成
空串，还原时写回空串，群里就留下一个没名字的群。兜底本身没有存在的理由，已收回，只保留
「`groupName` 空了退 `remarkName`」这一行。

真正的修复在测试侧：改名用例现在两个来源都读一遍原名、读到空串就不改名、**还原放进 `finally`**、
读回带重试，还原后读不到原名会明确报错要人工确认。

### 入站计数

`internal/status` 新增 `inbound`：`recv` / `add` / `update` / `gray_tip` / `emitted` /
`notices` / `manual_self`。事件收不到时先看这几个——能分开「记录压根没进来」「进来了但没转成
事件」「转了但客户端没消费」三种情况。

### 测试

`tests/ws-ayjx-smoke.js` 原先连的是 `/` 上的 OneBot 风格动作，实现端早就不提供，脚本会静默
零输出地「通过」。按 ayjx（Rust 端）实际调用的 25 个动作重写：只读项要读到对的形状，会改状态的
项只用参数校验确认「路由到了真实实现」，`internal/like` 这类已知缺口单独断言文案能被认出来。
