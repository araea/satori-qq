# 后续规划：坚持安卓原生 QQ 的单轨路线

## 不变初心

项目只做一件事：把这台手机上的 Android QQNT 9.3.50 变成可供 ayjx 使用的 OneBot 11 实现端。
不转 Lagrange，不以桌面 QQ 替代，不因稳定性困难改变技术对象；主号测试风险已由作者明确接受。

## 多角度判断

### 1. 稳定性与反检测

- 当前 QQ 作用域只有 `com.onebot.qq`，不存在其它 Xposed 模块叠加注入。
- 实测进程 maps 仍暴露 4 条 vector、6 条 zygisk；vector 2.2 没有 hide 开关。这是当前最大剩余风险。
- 已安全收敛：WS 只绑 `127.0.0.1`、线程名不含 onebot/xposed/vector、maps_hide 关闭、Java 仅中和
  `QSec.detectMethod/getXpsInfo`，绝不碰签名 JNI。
- 下一步不是再写 Java 欺骗，而是连续 24h/72h 记录掉线时间、登录 Activity、QSec/系统日志和网络状态，
  建立可比较的基线；vector 若未来提供官方隐藏，再做 A/B 测试。

### 2. 韧性

- 进程内：真实 online/offline 心跳、生命周期、离线 1500、同进程退出/一键登录恢复均已验证。
- 进程外：`/data/adb/onebot-qq/qq-onebot-watchdog.sh` 已开机持久化；force-stop 后 10 秒拉起，
  `set_restart` 也可闭环恢复。
- watchdog 已持久化 `watchdog.status`/`watchdog.counters`，记录 online/login/qq_down/port_missing、
  掉线与恢复次数、拉起与冷重启次数；LoginActivity 永不重启循环。下一步用这些数据完成 24h/72h 基线，
  而不是高频重启。

### 3. OneBot 11 协议价值

优先顺序按 ayjx 实际收益排序：

1. 群文件查询/下载、图片/语音取回等闭环型 API。
2. 更完整的消息历史游标、转发节点富媒体内容。
3. 群荣誉、精华消息等只读查询。
4. request/notice 仅在 ayjx 新增消费者后实现。
5. 服务器明确 319 的社交 OIDB 不重复撞风控。

### 4. 主号安全

- 发送测试固定群 `675983807`，消息立即撤回；管理动作原值写回。
- 不向陌生人发送、不导出 cookies、不 hook QSec 签名入口。
- 每个新 OIDB 先离线 protobuf 往返，再做一次最小真机请求；错误达到风控/验证级别立即停并移除作用域。

### 5. 维护性

- 锁定 QQ 9.3.50；记录 base.apk SHA-256、版本号和反编译类清单。
- 新版本适配先跑“会话/服务/字段签名探针”，再编译，不在主号上边猜边试。
- `ONEBOT11_SUPPORT.md` 是协议事实表；每项只有真机通过后才能标 ✅。

### 6. 性能与资源

- 好友资料每 200 uid 分块；历史最多 100 条；WS 64MB 帧上限。
- 音频转码使用流式临时 PCM 文件，避免把整段音频放进 QQ Java 堆；临时文件由 `clean_cache` 清理。
- 后续监控线程数、FD、Java heap 和 MediaCodec 失败率，防止“功能变多”反过来影响 QQ 稳定。

## 下一里程碑

1. 用 watchdog 已落盘的状态与计数完成 24h/72h 稳定性观测。
2. 群文件列表/URL 与 `get_image`/`get_record` 资源闭环。
3. 转发节点支持图片、at、reply。
4. QQ 版本指纹与适配探针。
5. ayjx 端为 online=false、WS 断线、retcode 1500 增加明确降级提示。
