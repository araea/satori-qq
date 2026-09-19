## 0.21.0 · 知弦

### 清空旧过检测，改为「让宿主看不到痕迹」

0.20.0 是最后一次把旧的反检测逻辑收回来。这一版把它整套删掉，换成一套从头写的、只做一件事的
实现：**让本模块在 QQ 进程里不可枚举**。

删掉的部分（不再保留任何开关）：

- `qq/AntiDetect`（3536 行）与 `qq/MapsHide` + `native/mapshide.c`（2113 行）：QSec/Turing/Beacon/O3
  上报过滤、踢线拦截与登出守卫、设备标识替换、root/Xposed/调试器/模拟器探测对抗、`/proc` 文本改写、
  GOT 修补、seccomp、memfd 加载，全部移除。
- 人脸链路（慧眼 SDK + TuringFace + turingcam）的三个钩子一并移除：这一版**不碰认证与人脸链路的
  任何一环**。
- `Cfg` 去掉 13 个开关；`docs/ANTIDETECT.md` 与两个过检测审计脚本删除。
- 不再加载任何 native 库，`build.sh` 不再编译 `.so`，APK 里只有 `classes.dex` 与
  `META-INF/xposed/*`。
- 不再向 QQ 的数据目录写任何文件（旧实现留了 29 个 `qk_*` 文件）。

新实现：

- `qq/EnvShield`：只挂 `PackageManager` 的五个查询口（`getPackageInfo`、`getApplicationInfo`、
  `getInstalledPackages`、`getInstalledApplications`、`getPackagesForUid`），把本模块从结果里摘掉，
  并抹掉结果里的 `xposed*` 元数据键。不改任何检测 API 的返回值，不拦任何上报。
- `/healthz` 新增 `shield` 段：在宿主进程内按 QQ 检测库的思路读一遍
  `/proc/self/{maps,smaps,mounts,mountinfo,cmdline,status,environ}`，统计黑名单字面量
  （`lsposed`/`zygisk`/`magisk`/`libriru`/`me.bmax.apatch` 等）的命中数。它只读、不落盘，
  用来判断宿主在自己进程里还能不能看到痕迹。

配套（设备层，不在本模块内）：

- Zygisk Next 的 `znctl memory-type anonymous`，让框架自己的库不再以文件映射形式出现在宿主
  `/proc/self/maps` 里。模块改动生效需要重启 zygote（整机重启）。

输出面变化：`/healthz` 去掉 `blocked_kicks`、`kick_hook`、`last_kick*`、`kick_log`、
`auto_login_kept`、`clean_offline`、`token_expired`、`allowed_logout`、`logout_guard`、
`login_state`、`face`；`internal/status` 去掉 `fekit_attach`、`env_report`、`face`；
`internal/compat` 去掉只在过检测里用到的 `subsystems` 表，249 → 204 项。Satori 方法表、
`/v1/internal` 的其余动作与事件形状不变。

升级提示：沿用包名与签名，可直接覆盖升级。旧配置文件里的 `anti_detect`、`maps_hide`、
`block_*`、`fake_*` 等键已不再被读取，留着无害，也可以删掉。

边界：这一版只负责「客户端不留下自己的痕迹」。踢线不再被拦截（服务端强下线由 QQ 自己处理），
人脸核身链路不做任何干预。
