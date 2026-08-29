# onebot-qq 接手

**现场（PID/scope/watchdog/下一步/硬约束）以仓库外这份为准，先读完再动手：**  
`/storage/emulated/0/Dev/onebot-qq-接手提示词.md`

目标：本机 Android QQNT → OneBot 11 正向 WS，给 ayjx 用。坚持原生 QQ 单轨。
**协议面已冻结**（ayjx 所用动作/段已覆盖），当前主线只做反检测 / 防掉线 / 防设备异常；协议只修 ayjx 回归。
细节以仓库文档为准，不要把旧会话结论或已完成清单再抄进对话。

## 读这些

1. 仓库外现场提示词。
2. `HANDOFF.md`（构建/部署）、`STACK.md`（换机/升版本）、`ARCHITECTURE.md`、`ANTIDETECT.md`（掉线证据）、`ONEBOT11_SUPPORT.md`（支持矩阵）、`FUTURE_PLAN.md`（反检测主线；`ROADMAP.md` 是冻结后的历史打法）。
3. 只读检查：Git、QQ PID、3001、vector scope、watchdog、`ws-health.js`。
4. 审计工作区已有改动，先构建再续写。

## 底线

- 全栈默认全开；单一变量 A/B 已废止。能登录就不卸注入。
- 协议冻结：不要再排 request live / 新动作 / 新段，除非 ayjx 回归。seccomp 胶水已确认；观察踢号窗口，不要为 log 冷启。
- 不改写 `getSign` / `getFeKitAttach` 返回；不全局 hook ART/libc。
- token、私聊数据、`tests/protocol-full-test.js` 不进 Git/日志/对话；未要求不要 commit。
- 测试群首选 `280183116`（作者已授权全部群聊）。闪退或验证升级时先停、存证；scope 回滚：  
  `su -c 'sh /data/adb/modules/zygisk_vector/cli scope rm com.onebot.qq com.tencent.mobileqq/0'`

构建必须在 `/data/media/0/Dev/onebot-qq`（不要在 `/sdcard` 视图里编）。
