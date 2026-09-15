## 0.9.1

修语音条偶发发不出去。

### 现象

同一条语音连发几条，会有一条回 500 `record transcode/send failed`，群里也就没有这条。测试群 280183116 上复现过：58.8 秒的 mp3 连发三条，第二条这样失败。之前"音乐房间四条语音里丢一条"是同一件事。

### 原因

`AudioTranscoder.decodeWithMediaExtractor` 的解码器在使用中途抛 `MediaCodec$CodecException`（消息是空的），位置在 `queueInputBuffer`。QQ 进程里同时有它自己的编解码在跑，模块的解码器不是独占的，被系统回收或实例额度用尽就会这样。原来一次不成就返回空，`Convert.addRecord` 再抛 `record transcode/send failed`。

### 改法

语音转码（mp3/wav/amr → SILK）整条带重试，最多三次，间隔 300 / 600 毫秒。重试按时间收口：上一次失败花掉的时间乘二超过 20 秒就不再试。理由是两个实测数——失败都是几秒内抛的（值得重来），而一条 59 秒的语音转一次，QQ 在前台时约 8 秒、在后台时约 34 秒（多试两次客户端那条 HTTP 就超时了，调用方给的是 30–65 秒）。`IllegalArgumentException`（文件里没有音频轨这种）不重试。

失败、重试、放弃都走 `L.e` 而不是 `L.i` / `L.w`：后者只在 `verbose_logs` 打开时出，默认配置里等于没有，而这几行是排查"语音为什么丢、为什么慢"的唯一入口。日志里带 `CodecException` 的 `transient` / `recoverable` / `diagnostic`（它的 `getMessage()` 是空的，只有这三项有内容）。

### 验证

- 修复前：58.8 秒 mp3（转 SILK 后 92 KB）连发三条，第二条失败，logcat 里是上面那条异常栈。
- 修复后：同一条连发 15 次（分五轮：6 / 3 / 1 / 2 / 3），没有一次失败。QQ 内核日志里每条都是 `type=4 format=1 voiceType=2`、`transfer=2`，`message.list` 读回的是 `<audio duration="59">`。
- 说明：这五轮里重试没有被触发过（失败本身是随机的，量级是十几条里一条），重试是按上面那条异常栈加的。判断有没有被触发看 logcat 里的 `silk transcode attempt N failed` / `silk transcode ok after N attempts`。
- 顺手把另外两条路也验了一遍：`<file>` 既发聊天气泡也进群文件列表（中文文件名同样过），`internal/group_file op=upload` 单传正常；`upload.create` 传 1.35 MB 用 103 毫秒。`message.create` 是同步等内核回调的，语音约 1 秒，文件慢的时候会占满 20 秒的等待窗口，超时后按"无回调"放行。
- 新增真机探针 `tests/media-live-probe.js`（`voice` / `file` / `filecn` / `groupfile` / `voicerepeat` / `timing` / `list`，用法见脚本头注释）。
- JVM 单测 20 项全过；真机 QQ 9.3.60.40970 覆盖安装后 `/healthz` 报 `version: 0.9.1`、`compat` 204/204、`kick_hook` 12。
