## 0.8.9.39

补齐 Satori 官方客户端的登录域内路由，再按 QQ 9.3.60.40970 的内核接口扩一批动作，并收紧过检测的几处暴露面。

### 协议

- 新增官方 internal 路由：`POST /v1/internal/{platform}/{selfId}/_api/{name}` 与 `GET /v1/internal/{platform}/{selfId}/_tmp/{id}`。前者是 Koishi `adapter-satori` 的 `bot.internal.*` 走法，参数按 `JsonForm` 编码，带 `Satori-Pagination: true` 时回 `{data, …}`；后者是 `upload.create` 返回的 `internal:` 资源被客户端回落到的地址。此前这两条路径分别回 405 与 404，`upload.create` 的返回值取不回来
- `login.get` 与 `READY` 的 login 补 `self_id` 与 `hidden`；每个事件顶层补 `self_id` 与 `platform`（`Event` 类型里与 `login` 并列的扁平字段）
- 表态事件保持 `reaction-added` / `reaction-removed`，与 `@satorijs/core` 声明的事件表一致（协议类型里写的是 `reaction-deleted`，Discord、Kook 适配器用的是后者，QQ 适配器用前者，两头都发会让一次操作派发两个会话）

### QQ 能力扩展（新增 23 个 `internal/*` 动作）

- 群：`group_join_link`（加群/分享链接）、`group_member_card`、`group_related`（关联群与子群）、`group_apps`、`group_illegal`、`group_capacity`、`group_msg_limit`、`group_notify`、`group_signin_status`、`group_check_member`、`group_transfer` 与 `group_destroy`（都要 `confirm=true`）
- 消息：`message_by_id`、`recall_history`、`first_unread`、`msg_abstract`、`reaction.likes`、`draft`、`temp_chat`、`recent_faces`、`fav_emoji_write`
- 会话：`hidden_session`、`session_top`、`recent_snapshot`、`unread_details`
- 媒体与机器人：`media_dir`、`batch_file_count`、`robot_list`、`robot_owned`
- `internal/capabilities` 的 `params` 同步补齐

核过但**没有**提供动作的内核入口，逐条记在 `docs/SATORI_SUPPORT.md` 的「内核可用性」：`getOnLineDev` 接受调用但不回调；`getNextMemberList` / `getPrevMemberList` 要先建 member list 场景，实测各游标都回空页；`enumProvinceOptions` 一类的地区枚举在内核 `prepareRegionConfig` 之前恒为空表，而该入口不回调（15 秒超时）。不回调的入口一律不进模块，否则调用方白等 15 秒。

### 过检测

- 字符串搜索按符号集合接管：libfekit 同时 import `strstr`、`strcasestr`、`memmem`，此前只接管了 `strstr`，而且只在 needle 恰好等于黑名单里的某个词时才返回未找到。现在三个入口共用同一条规则（needle 含黑名单任一条即返回未找到），判定集与路径过滤完全一致。主进程补丁数 63 → 65，MSF 40 → 42
- `/proc/<pid>/mem`、`/proc/<pid>/pagemap`、`/proc/kcore` 直接返回 `ENOENT`。按偏移读的文件做不了行过滤，而「在自己进程里扫 dalvik 堆找关键字」正好走这条路
- 模块自己的 memfd 不再叫 `jit-cache`：ART 已经用了这个名字，同进程出现第二个不同 inode 的 `/memfd:jit-cache` 是「加载器藏在 memfd 里」的判据之一。改名 `dalvik-jit-code-cache`，实测 ART 的 `jit-cache` 恢复为唯一一个
- 模块自己在盘上的残留收了三处：`qk_env_*.json` 自检从外部 `Android/data` 迁到应用私有目录（并清理旧位置）、看守日志与 pid 从 `/data/local/tmp` 迁到 `/data/adb/satori-qq/`、逐次发送的调试落盘改为 `verbose_logs` 才写。配置文件 `satori-qq.json` 仍在外部目录，那是给人编辑用的必要暴露
- 对照 Duck Detector 源码逐项核过一遍，能挡与挡不住的都写进 `docs/ANTIDETECT.md`。新增记录 Turing 的 POSIX ERE 黑名单（进程名、线程名、路径），这些正则不走 libc 字符串函数，模块改线程名或目录名时要照它核一遍

### 看守

- 僵尸会话看守加宽限期：force-stop 后拉起 QQ 到 MSF 重新连上要 5 分钟左右，原来的 3 轮（3 分钟）判定会在这段时间里把 QQ 再杀一次，于是每 3 分钟重启一轮、永远等不到连接。现在主进程未满 `QQ_REVIVE_GRACE`（默认 300 秒）不做僵尸判定，`STALE_LIMIT` 默认 3 → 5
- 日志与 pid 落到 `/data/adb/satori-qq/`，权限 0600

### 验证

- JVM 单测 18 项通过（`ProtocolTest` 补 `self_id` 用例），`tests/mapshide-filter-test.c` 通过（补 token 扫描与 `/proc/*/mem` 用例）
- 新增 `tests/internal-kernel-probe.js`：官方 internal 路由端到端（含 `upload.create` 取回资源、分页头、异号 404）与 23 个新动作，在测试群 280183116 上 39/39 通过
- 既有巡检无回归：`ws-kernel-extras.js` 28/28、`ws-kernel-writes.js` 11/11
- 真机 `/healthz`：主进程与 MSF 的 GOT 补丁 65 / 42，maps、tcp、environ 泄漏 0，`loop_ok=1`，`kick_hook=3`

版本号 0.8.9.39（versionCode 74），沿用同一签名密钥，支持从 0.8.9.38 直接覆盖升级。
