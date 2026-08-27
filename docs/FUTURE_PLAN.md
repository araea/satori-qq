# 后续规划：反检测 × OneBot 11 双主线

## 总目标

坚持纯手机与 Android 原生 QQ，把 QQNT 9.3.50 做成可长期运行、协议能力完整的 OneBot 11 实现端。
反检测、防掉线、恢复韧性和协议完善不再互相排斥，按两条主线并行建设。

## 主线一：反检测与防掉线

### 已完成基线（0.5.0）

- 日志默认静默，运行时标签由 `OneBotQQ` 收敛为中性 `Q.Kernel`；verbose/Xposed 日志仅配置开启。
- 线程名无 onebot/xposed/vector/zygisk/mapshide；WS 仅绑定 127.0.0.1。
- `QSec.detectMethod` 前置返回 false；`getXpsInfo` 改为 replacement，原生 `T.ad` 采集器不再先执行。
- `QSec.reportLog` 默认阻断，`execTasks` 保留为独立实验开关；签名、Est、Dandelion 路径不受影响。
- `/data/adb/onebot-qq/qq-onebot-exposure-audit.sh` 可生成可比较快照；watchdog 状态切换自动留档。
- 0.5.0 真机基线：onebot/xposed/lspd/mapshide maps=0，可疑线程=0，旧日志指纹=0；
  剩余 vector=4、zygisk=6、fekit=3。

### 下一步

1. 对 `reportLog` 阻断做 24h/72h A/B，比较掉线、恢复、登录与消息能力。
2. 单独评估 `block_qsec_tasks`，先短窗口再长期；不与其它高风险变量同时改变。
3. 跟进 vector 新版本、加载路径和框架级隐藏能力，记录升级前后 maps 差异。
4. 设计下一代 native 实验：跨 linker namespace 精确定位 libfekit，先观测真实读取点，再决定
   GOT/inline/syscall 处理；保持限定检测库、配置开关和旧 APK 回滚。
5. 扩展 exposure snapshot：QSec 调用计数、掉线前后时间线、maps 基址/库清单差异与崩溃墓碑关联。

## 主线二：OneBot 11 完整实现

### 已完成基线（0.5.0）

- 新增资源注册表：消息段 file_id 与 chatType/peerUid/msgId/elementId/fileModelId 关联。
- 新增 `get_image/get_record/get_file`；顺序为本地缓存 → QQNT `downloadRichMedia` → URL 兜底。
- 按 NapCat 当前源码与本机/QQ.hap 核对：普通消息下载使用 fileModelId=0、downSourceType=0、
  triggerType=1，并通过 `onRichMediaDownloadComplete` 返回真实路径。
- 真机只读验证：`get_image`、`get_file` 均 retcode 0 且返回真实本地文件；最近历史无 record/video 样本。

### 下一步

1. 等自然样本或准备本地样本验证 `get_record` 与 video→`get_file`；为 `get_record.out_format` 增加转码。
2. 接入 `IKernelRichMediaService.getGroupFileList`、群文件空间、文件 URL 与目录操作。
3. 完善历史游标与私聊历史；避免只返回空数组而不区分超时/内核错误。
4. 合并转发节点从文本扩展到 image/at/reply/file，并解决转发资源 file_id 上下文。
5. 基于 IKernelMsgListener/GroupListener 补 recall、poke、群成员变化、禁言等 notice；再补 request。
6. 把群管理 fire-and-forget 改成真实 callback 结果，统一 OneBot retcode/wording。

## 参考资料策略

- 优先查仍在维护的 NapCatQQ、Lagrange 等新库，直接复用 action 行为、protobuf、命令号与踩坑结论。
- `/storage/emulated/0/Dev/QQ.hap` 用于检索腾讯自身的 ArkTS 绑定、libkernel 字符串、proto 与业务结构。
- 社区桌面类型与鸿蒙 API 都必须由 Android QQ 9.3.50 的反编译类/构造器/真机回调最终校正。
- 已确认事实随代码写入 `ARCHITECTURE.md`、`ANTIDETECT.md`、`ONEBOT11_SUPPORT.md`，避免再次从零猜。

## 工作节奏

- 每轮尽量同时落一项反检测增量和一项协议增量。
- 先离线构建/单测，再真机只读验证；需要发送时使用作者控制的目标并清理测试消息。
- 反检测变量一次只改一个并留快照；协议项明确区分“已实现”“构建通过”“真机验证”“外部限制”。
- 海阔天空，但结论必须可复现、可回滚、有数据。

## 测试授权与目标选择

- 作者已明确授权使用所有群聊进行测试，接手者可自行判断，无需逐项确认。
- 主号为群主的群 `280183116` 是写操作与权限型测试的首选，包括群文件、群管理、OIDB 与回调结果。
- 其它群可用于只读查询、历史/资源样本和普通消息兼容性；若目标群权限或样本更合适，也可用于写测试。
- 选择原则是结果覆盖度、权限匹配和影响最小化，不再把测试固定死在旧群 `675983807`。

## 下一次对话的已确认计划

1. 反检测：以当前 reportLog-block 为基线，只启用 `block_qsec_tasks`，完成短窗口单变量 A/B；
   立即验证登录、WS、消息、资源下载、错误日志和 exposure snapshot，稳定后进入长期观察。
2. OneBot 11：参考 NapCat 当前 `IKernelRichMediaService` 与 QQ.hap，实现群文件系统信息、根目录列表、
   子目录列表和文件 URL；使用 `280183116` 做权限完整的真机验证。
3. 必要时上传并清理一个小型测试文件，验证列表→URL→下载闭环；不把仅查询成功当成整组完成。
4. 本轮之后优先级：转发 image/at/reply/file → notice/request → 群管理真实 callback。
