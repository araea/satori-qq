## 0.19.0 · 知弦

### 移除全部过检测与反检测逻辑

这一版删掉模块里所有为「避开 QQ 检测」而存在的代码，只留协议功能核心。Satori 方法表、
`/v1/internal` 动作与事件形状都不变。

删掉的部分：

- **Java 层过检测整套**（`qq/AntiDetect`，3536 行）：Root / Xposed / 调试器 / 包名 / 堆栈
  检测的拦截，QSec、Turing、Beacon、O3 上报与命令过滤，包管理器与 `File.exists` 的伪装，
  设备标识（IMEI / AndroidID / Serial）替换，`/proc` 文本与属性读取的改写。
- **Native 层整套**（`qq/MapsHide` 与 `native/mapshide.c`，1969 行）：检测库 GOT 修补、
  `/proc/self/maps` 与 `cmdline` 过滤、seccomp 系统调用过滤、memfd 加载。
  APK 里不再有 `.so`，`build.sh` 不再调 clang。
- **踢线拦截与登出守卫**：服务端强制下线不再被拦在本地。被踢时由 QQ 自己处理。
- **设备侧看守脚本**：`scripts/qq-revive.sh` 与 `scripts/98-qq-revive.sh`。看守的存在前提是
  模块拦下踢线后留下的「内核报在线、上游已断」僵尸态；不再拦踢线就没有这个状态。
  另外删掉只服务于过检测 A/B 的 `scripts/qq-satori-exposure-audit.sh` 与
  `scripts/qq-satori-fekit-inventory.sh`。

配置与输出面随之收窄：

- 配置项删掉 `anti_detect`、`maps_hide`、`block_qsec_tasks`、`block_qsec_reports`、
  `observe_fekit_attach`、`block_o3_report`、`block_turing_risk`、`block_face_report`、
  `block_server_kick`、`clean_offline_on_kick`、`fake_imei`、`fake_android_id`、`fake_serial`。
  文件里留着这些键不影响启动，只是不再被读取。
- `/healthz` 删掉 `blocked_kicks`、`kick_hook`、`last_kick*`、`kick_log`、
  `auto_login_kept`、`clean_offline`、`token_expired`、`allowed_logout`、`logout_guard`、
  `login_state`、`face`；`internal/status` 删掉 `fekit_attach`、`env_report`、`face`。
  `sso`（模块自己 SSO 请求的失败计数）保留。
- `internal/compat` 删掉 `subsystems` 一张表（只被过检测钩子用），其余四张内核接口表不变。
  总数从 249 项变为 204 项。
- 知弦管理页的诊断信息去掉「离线拦截」与「最近离线」两行。

保留不变：协议方法与事件、`/v1/internal` 动作、消息与媒体链路、OIDB 与合并转发封包、
前台服务保活、唤醒锁与 Wi-Fi 保持、内核接口自检（`internal/compat`）、状态通知。

升级提示：沿用包名与签名，可直接覆盖升级。升级后 QQ 的其他进程（`:MSF` 等）不再被注入，
只有主进程加载模块。设备上若装过看守脚本，可从 `/data/adb/service.d/98-qq-revive.sh` 与
`/data/adb/satori-qq/` 里手动删掉。

取舍说明：模块不再介入设备的检测面，QQ 的风控与踢线行为完全由客户端与服务端决定。
