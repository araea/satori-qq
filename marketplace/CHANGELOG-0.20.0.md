## 0.20.0 · 知弦

### 恢复过检测与反检测整套逻辑

这一版把 0.19.0 删掉的全部过检测代码原样收回（回滚 `137945a`），并补齐设备层缺的那一块。
Satori 方法表、`/v1/internal` 动作与事件形状不变。

恢复的部分：

- **Java 层**（`qq/AntiDetect`）：QSec 检测入口按返回类型置安全值（`detectMethod` 恒假、
  `getXpsInfo` 回空、`execTasks` / `reportLog` 回 0），`SocketStatus.checkSocket` 命中框架名
  返回 0，ChannelProxy / ChannelManager / MsfCore 按命令白名单丢弃环境上报并回空成功。
  Root、Xposed、调试器、模拟器、包管理、堆栈、Pandora、Turing、MSF 遥测与强制下线逐项覆盖，
  人脸核身链路（TuringFace / turingcam / 慧眼上报）单独覆盖，SoMonitor / NativeMonitor 的
  三个安装点与 Beacon 出口一起拦。
- **Native 层**（`qq/MapsHide` 与 `native/mapshide.c`）：15 个检测库与监控库的 GOT 修补、
  `/proc` 路径与行过滤（含 `fdinfo`、`numa_maps`、目录项前缀）、无路径可执行映射过滤、
  模块自身以 memfd 载入、seccomp 拦住模块文本段里的裸 svc。
- **踢线拦截与登出守卫**：服务端强制下线在本地被拦下，避免「内核报在线、上游已断」的僵尸态。
- **设备侧看守**：`scripts/qq-revive.sh` 与 `scripts/98-qq-revive.sh`，以及过检测审计脚本
  `qq-satori-exposure-audit.sh`、`qq-satori-fekit-inventory.sh`。
- **配置开关**：`anti_detect`、`maps_hide`、`block_qsec_tasks`、`block_qsec_reports`、
  `observe_fekit_attach`、`block_o3_report`、`block_turing_risk`、`block_face_report`、
  `block_server_kick`、`clean_offline_on_kick`、`fake_imei`、`fake_android_id`、`fake_serial`
  全部回到配置文件与文档里（`satori-qq.sample.json`）。

输出面：

- `/healthz` 恢复 `blocked_kicks`、`kick_hook`、`last_kick*`、`kick_log`、`auto_login_kept`、
  `clean_offline`、`token_expired`、`allowed_logout`、`logout_guard`、`login_state`、`face`；
  `internal/status` 恢复 `fekit_attach`、`env_report`、`face`；`internal/compat` 恢复
  `subsystems` 表，总数 204 → 249 项。
- 知弦管理页恢复「离线拦截」与「最近离线」两行。

配套（模块外，设备层）：

- `ro.boot.flash.locked` 与 `ro.boot.verifiedbootstate` 由 root 方案那一侧的属性模块改，
  本模块不碰。本机需要它们分别是 `1` 与 `green`，否则 Duck Detector 之类的读取者会把
  System Properties 与 Bootloader 两张卡片判红。设备上没装这类模块时先补上。

边界（这一版没有改变）：

- **人脸核身的「设备环境异常」是服务端 413，客户端改不了**。根因是本机解锁 bootloader 后
  厂商撤销了认证密钥供给（`generateKey` 报 `-74 ATTESTATION_KEYS_NOT_PROVISIONED`），
  TuringFace 要的硬件认证链出不来，服务端也不认解锁态的自述。换设备或刷回锁定官方系统才行。
- 服务端风控按历史行为、设备指纹变化与网络环境打分，客户端拦不住。
- `libfekit` 的 ArtMethod 完整性校验、进程内内存关键字扫描、多后端交叉校验不在覆盖范围。

升级提示：沿用包名与签名，可直接覆盖升级。升级后 QQ 的其他进程（`:MSF` 等）会重新被注入。
若设备上此前删过看守脚本，需要把 `scripts/98-qq-revive.sh` 放回 `/data/adb/service.d/`。
