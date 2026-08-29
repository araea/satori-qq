# 后续规划：反检测主线（OneBot 协议面已冻结）

## 总目标

坚持纯手机与 Android 原生 QQ，把 QQNT 9.3.55 做成可长期运行的 OneBot 11 实现端，给 ayjx 用。
**2026-08-29 起协议面冻结**：对照 ayjx 源码，所用动作/段均已覆盖；默认不再推进新 OneBot 功能，
只修 ayjx 回归。当前唯一主线是反检测 / 防掉线 / 防设备异常。
换机 / 换 root / QQ 升版本的可复现步骤见 `STACK.md`。

## 主线一：反检测与防掉线

### 已完成基线（0.5.0）

- 日志默认静默，运行时标签由 `OneBotQQ` 收敛为中性 `Q.Kernel`；verbose/Xposed 日志仅配置开启。
- 线程名无 onebot/xposed/vector/zygisk/mapshide；WS 仅绑定 127.0.0.1。
- `QSec.detectMethod` 前置返回 false；`getXpsInfo` 改为 replacement，原生 `T.ad` 采集器不再先执行。
- `QSec.reportLog` 默认阻断，`execTasks` 保留为独立实验开关；签名、Est、Dandelion 路径不受影响。
- `/data/adb/onebot-qq/qq-onebot-exposure-audit.sh` 可生成可比较快照；watchdog 状态切换自动留档。
- 0.5.0 真机基线：onebot/xposed/lspd/mapshide maps=0，可疑线程=0，旧日志指纹=0；
  剩余 vector=4、zygisk=6、fekit=3。
- 2026-08-28 12:47 已捕获真实 `KICK_TO_LOGIN` + `ACCOUNT_KICKED`。随后把 Zygisk Next 切到官方
  anonymous memory mode，精确 maps 口径降为 vector=0、zygisk=0、fekit=3，登录与 OneBot health 正常。
  旧 vector=4 中有 1 条是系统通用 `InternalMmapVector` 假阳性。

### 下一步

1. 历史：anonymous / reportLog / execTasks / maps_hide v2 / 单开 v3 / **v4 到 21:39 第九次踢号** 都没挡住踢号。这些是证据，不是「以后别开」。
2. **19:06 起全栈默认全开**。20:48 起 maps_hide 为 **v4**（VMA 命名 + memfd；真实 maps 差量为 0）。seccomp 胶水已在 PID 18120 确认（主进程 filters=2/NNP=1 vs MSF 1/0）；BPF 单测通过。本 PID native 随协议包上来，**不能**把存活写成防踢有效。
3. ColorOS **Hans** 会周期性冻 QQ；sticky unfreeze 无效。watchdog 每轮 `cmd activity unfreeze` QQ+MSF，并把 `com.tencent.mobileqq` 写入 `/data/oplus/os/bpm/bpm.xml` persist（原先只有 Termux 等）。21:21 的 33 min `qq_down` 无踢号/LMK；21:33 是 Coolapk 前台时 `installPackageLI` force-stop。端口 up/WS 无响应仍不计 kick。
4. 当前：观察 PID 18120 对照注入基线 16–31 min，**不要为看 log 冷启**。踢号则存证。syscall-entry inline 仍有证据再动。不要 hook 签名 JNI / 改 getFeKitAttach 返回。

## 已冻结：OneBot 11（只修 ayjx 回归）

ayjx 实际调用面见 `ONEBOT11_SUPPORT.md`。插件会调的动作与段均已真机；`send_msg` 的 `node`
数组改道现有 `sendForward`（ayjx 不用 `send_*_forward_msg`）。未排期项（request live 事件、
`get_record.out_format`、历史游标）不挡 bot，不要再当下一轮任务。

### 已完成基线（0.5.0）

- 新增资源注册表：消息段 file_id 与 chatType/peerUid/msgId/elementId/fileModelId 关联。
- 新增 `get_image/get_record/get_file`；顺序为本地缓存 → QQNT `downloadRichMedia` → URL 兜底。
- 按 NapCat 当前源码与本机/QQ.hap 核对：普通消息下载使用 fileModelId=0、downSourceType=0、
  triggerType=1，并通过 `onRichMediaDownloadComplete` 返回真实路径。
- 真机只读验证：`get_image`、`get_file` 均 retcode 0 且返回真实本地文件。video/`get_file` 与私聊真实 `file_id` 已在 2026-08-29 补齐。
- 群文件四项查询已完成：0x6D8 文件数/真实上限/空间/分页列表，0x6D6_2 URL；空根目录、已有文件
  HTTPS 与自然子目录均真机通过，未制造写样本。

### 冻结后不再排期

下列只作已完成记录，不是下一轮任务：`get_record.out_format` 在树里；video/`get_file` 与私聊真实
`file_id` 已真机；群文件读写闭环；`get_friend_msg_history` 已真机；合并转发点开已确认；notice 已真机；
request 动作 1400/1404 已真机，live 事件仍待申请人样本（ayjx 不消费 notice/request）。

## 参考资料策略

- 优先查仍在维护的 NapCatQQ、Lagrange 等新库，直接复用 action 行为、protobuf、命令号与踩坑结论。
- `/storage/emulated/0/Dev/QQ.hap` 用于检索腾讯自身的 ArkTS 绑定、libkernel 字符串、proto 与业务结构。
- 社区桌面类型与鸿蒙 API 都必须由当前 Android QQ 9.3.55 的反编译类/构造器/真机回调最终校正。
- 已确认事实随代码写入 `ARCHITECTURE.md`、`ANTIDETECT.md`、`ONEBOT11_SUPPORT.md`，避免再次从零猜。

## 工作节奏

- 每轮只落反检测 / 防掉线增量。协议改动仅限 ayjx 回归。
- 先离线构建/单测，再真机验证；需要发送时用 `280183116`。测试消息保留，不撤回；群文件和禁言等持久状态仍要清理/恢复。
- 全栈默认全开并留快照。seccomp 胶水已确认；不要为了再看一眼 log 冷启。协议改动仍不要和反检测冷启混在一起。
- 结论必须可复现、可回滚、有数据。即时健康 ≠ 防踢有效。

## 测试授权与目标选择

- 作者已明确授权使用所有群聊进行测试，接手者可自行判断，无需逐项确认。
- 主号为群主的群 `280183116` 是写操作与权限型测试的首选，包括群文件、群管理、OIDB 与回调结果。
- 其它群可用于只读查询、历史/资源样本和普通消息兼容性；若目标群权限或样本更合适，也可用于写测试。
- 选择原则是结果覆盖度、权限匹配和影响最小化，不再把测试固定死在旧群 `675983807`。

## 下一次对话的已确认计划

现场细节以 `/storage/emulated/0/Dev/onebot-qq-接手提示词.md` 的「此刻」为准。

1. **唯一主线：反检测。** seccomp 胶水已在 PID 18120 确认，正在对照 16–31 min 基线观察。不要为 log 冷启。不要 hook `getSign` / 改 `getFeKitAttach` 返回。
2. 踢号则存证、能登录就不卸 scope；须卸时先卸注入再密码/PIN 重登，再全栈打回。Hans unfreeze 已在 watchdog。
3. 协议只修 ayjx 回归。request live 事件不挡 bot，不必等申请人。
