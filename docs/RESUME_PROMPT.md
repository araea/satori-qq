# onebot-qq 接手提示词：反检测 × OneBot 11 双主线

你正在接手一个持续演进的手机端 QQNT OneBot 项目。先掌握事实，再大胆推进；不要被旧会话的结论、
临时取舍或“以前没做”束缚，也不要重复已经证伪的实验。

## 初心

把本机 Android QQNT 9.3.50 变成完整、可长期运行、可供 ayjx 及其它 OneBot 11 客户端使用的
正向 WebSocket 实现端。项目坚持纯手机与 Android 原生 QQ，充分利用真实客户端内核，同时正面解决
注入暴露、掉线、恢复和协议完整度问题。

后续工作围绕两条同等重要的主线展开：

1. **反检测与防掉线**：持续减少 Java、native、框架、进程、线程、文件、网络和行为层暴露；研究
   vector/zygisk/libfekit 的真实检测面，允许探索框架级隐藏、跨 linker namespace 定位、外科式 native
   处理和更好的恢复策略。所有结论以真机证据和 A/B 数据为准，不把“难”写成“永远不可能”。
2. **OneBot 11 完整实现**：继续补动作、消息段、notice/request、资源获取、文件管理、历史游标、
   合并转发富媒体和协议兼容性。ayjx 是优先消费者，但不再把“ayjx 暂时没用”当成永久不实现的理由。

## 接手顺序

1. 完整读 `HANDOFF.md`、`ARCHITECTURE.md`、`ANTIDETECT.md`、`ROADMAP.md`、
   `ONEBOT11_SUPPORT.md`、`FUTURE_PLAN.md` 与 `reference/PACKETS.md`。
2. 运行只读状态检查，查看 Git 状态、最近提交、QQ PID、3001、vector scope、watchdog snapshot 和日志。
3. 审计工作区已有改动，先构建再续写；不要覆盖来源不明的现有成果。
4. 若作者只说“继续”，自行选择两条主线上收益最高、可验证的一组任务推进，不必等待逐项指定。

## 当前基线

- 仓库：`/data/media/0/Dev/onebot-qq`；普通 shell 视图：`/storage/emulated/0/Dev/onebot-qq`。
- 环境：rooted ColorOS、KernelSU 系、Zygisk Next、vector；模块 `com.onebot.qq` 注入 QQ 主进程。
- 正向 WS、Bearer、真实 lifecycle/heartbeat/get_status、离线 1500、同进程重登录重绑已完成。
- root watchdog 可拉起死亡进程，并持久化 online/login/qq_down/port_missing 与掉线/恢复计数。
- 消息收发、群管、好友/陌生人、历史、合并转发、图片/语音/视频/文件、OIDB/裸 SSO 已有完整基础。
- 常见音频可经 MediaCodec 转 AMR-NB；`send_like` 当前受服务器 appid 319 限制。
- 0.5.0 默认静默日志；`QSec.detectMethod/getXpsInfo` 前置替换，`reportLog` 默认阻断，`execTasks` 独立开关关闭。
- exposure audit 已安装并由 watchdog 状态切换触发；当前 onebot/xposed/lspd maps、可疑线程与旧日志均为 0，
  剩余 vector=4、zygisk=6、fekit=3。
- `get_image/get_file` 已通过 QQNT `downloadRichMedia` 回调返回真实本地文件；`get_record` 已实现待样本。
- 已知 native 暴露仍包括 vector/zygisk maps；旧 GOT maps_hide 因 linker namespace 与裸 syscall 风险未奏效，
  这是一条实验结果，不是禁止继续研究其它 native/框架方案。

## 反检测推进方法

- 每次先建立暴露清单：`/proc/<pid>/maps`、线程名、已加载 dex/so、模块路径、类探测、端口、日志、
  QSec 调用与掉线时间线。
- 分层推进：低风险元数据/Java 指纹 → 模块加载与路径暴露 → vector/zygisk 框架隐藏 →
  libfekit 定向研究 → 行为与恢复。
- native 改动优先外科式、限定 QQ/限定检测库、带开关；避免无边界地影响 ART 或全进程 libc。
- 每个方案记录“启用前/启用后”maps、崩溃、登录、心跳、消息能力与 24h/72h 掉线次数。
- 韧性层不是认输：检测降低与掉线后自动恢复可以同时建设。

## OneBot 11 推进方法

- 以 OneBot 11 语义、NapCat/Lagrange/OpenShamrock 行为和 ayjx 实际需求交叉校验，不只追求 action 名存在。
- NapCatQQ、Lagrange 等仍在维护的新库是首选效率工具：直接参考其 action 行为、protobuf 与字段，
  再用本机 Android QQ 9.3.50 反编译结果校正平台差异，不重复从零猜协议。
- `/storage/emulated/0/Dev/QQ.hap` 是鸿蒙 QQ 本地源码/协议参考；Java/ArkTS API 不硬搬，但富媒体下载、
  群文件、事件源、OIDB/trpc 命令和业务数据结构应充分检索复用。
- 优先做闭环：消息事件中的资源标识必须能被 `get_image/get_record/get_file` 取回；上传后也能查询和下载。
- 查询动作尽量只读、分页/游标明确；变更动作返回真实回调结果，不长期停留在 fire-and-forget。
- 合并转发从文本节点扩到 image/at/reply/file 等真实段；notice/request 在内核事件源明确后逐步补齐。
- `ONEBOT11_SUPPORT.md` 持续维护“实现 / 真机验证 / 外部限制”三种事实，不能把编译通过写成真机通过。

## 必要边界

海阔天空不等于不可回滚。保留少数工程底线：

- 任何高风险注入/native 实验先保留当前 APK、脚本和 scope 回滚路径，并明确观察窗口。
- 不把账号凭据、token、cookies 或私有消息写入日志、Git 或回复。
- 新消息/管理动作优先使用作者控制的测试群，避免把调试流量扩散给无关用户。
- 出现持续闪退、登录验证升级或消息能力异常时，先停实验并保存证据；scope 回滚：
  `su -c 'sh /data/adb/modules/zygisk_vector/cli scope rm com.onebot.qq com.tencent.mobileqq/0'`。

## 构建与只读核验

```bash
su -c '
export HOME=/data/data/com.termux/files/home
export PATH=/data/data/com.termux/files/usr/bin:/system/bin:$PATH
cd /data/media/0/Dev/onebot-qq && bash build.sh
'
```

```bash
su -c '
export PATH=/system/bin:$PATH
pidof com.tencent.mobileqq
cat /proc/net/tcp6 | awk "{print \$2}" | grep -i :0BB9
sh /data/adb/modules/zygisk_vector/cli scope ls com.onebot.qq
sh /data/adb/onebot-qq/qq-onebot-watchdog.sh status
sh /data/adb/onebot-qq/qq-onebot-watchdog.sh snapshot
/system/bin/logcat -d -s Q.Kernel:* | tail -n 80
sh /data/adb/onebot-qq/qq-onebot-exposure-audit.sh latest
'
```

## 当前优先队列

### 反检测

1. 用已安装 exposure audit 对 reportLog 阻断做 24h/72h A/B。
2. 单独短窗口评估 `block_qsec_tasks`，不与其它高风险变量同时改变。
3. 核对 vector 新版本/配置/加载路径隐藏，并设计跨 namespace 的 libfekit 精确观测实验。
4. 保持所有能力配置化、可回滚，并关联 watchdog 掉线时间线。

### OneBot 11

1. 用自然语音/视频样本完成 `get_record`/video 下载验证，并补 out_format。
2. 群文件列表、文件 URL/下载能力与更完整的历史游标。
3. 合并转发 image/at/reply/file 节点。
4. notice/request、精华/荣誉等扩展动作与真实回调结果。
5. ayjx 对 offline、断线、1500 和恢复事件的明确降级体验。

保持初心，也保持想象力：用证据淘汰路线，而不是用旧结论封死路线；把真正能在手机上长期运行的
Android QQ OneBot 11 做深、做全、做稳。
