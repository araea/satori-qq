## 0.18.0 · 知弦

### 过检测面扩建：SO 加载监控、Java 侧 root 检测、/proc 文本读法

这一版只动过检测相关代码，协议与已开放的动作不变。

- **新增覆盖 QQ 的 SO 加载 / native hook 监控（SoMonitor）**。9.3.65 的启动步骤
  `startup.step.OpenThreadCreateHook` 会调 `NativeMonitorConfigHelper.setupSoLoadHook()`，
  native 侧挂 `Runtime.nativeLoad` 的 ArtMethod hook，把每次 SO 加载的路径、md5、长度、
  是否合法与调用栈经 Beacon 的 `native_monitor_so_load` / `native_monitor_native_hook` 事件
  上报。模块的 `.so` 是 `System.load("/proc/self/fd/<n>")`，一旦这条钩子装上就会被判
  `name_illegal`。现在拦的是**安装点**（`NativeMonitorConfigHelper.setupSoLoadHook`、
  `NativeMemoryMonitor.setupSoLoadHook`、`NativeMemoryMonitor.setNativeHookMonitor`），
  不装钩子就没有数据；另外在 `QQBeaconReport.report` 上兜底过滤 `native_monitor*` 与
  `soMonitorCollectorReport*` 事件。
  QQ 自己的内存、dex、线程监控（`setupFileHook` / `setupOpenDexFileHook` / `initJniHook` /
  `initThreadHook`）刻意不停，避免无谓的行为改变。
- **Java 侧 root 检测补两处**：`com.tencent.camerasdk.avreport.DeviceInfo.isDeviceRooted()`
  此前没覆盖；`com.tencent.gathererga.core.UserInfoImpl` 这个类名在 APK 里根本不存在
  （真实类是 `com.tencent.gathererga.core.internal.provider.impl.UserInfoImpl`，且
  `isRooted` 返回的不是 boolean），那条钩子一直空转，已按实测形状改掉。
- **`/proc` 文本过滤补齐读法**。此前只接 `BufferedReader.readLine`，现在同样处理
  `RandomAccessFile.readLine`、`Files.readAllLines`、`Files.readString`，四种读法共用同一套
  行判据（`TracerPid` / `NoNewPrivs` 归零，命中黑名单的 maps 行清空）。
- **native 补丁的检测库从 8 个扩到 15 个**。新增 `libnative-memory-library-lib`、
  `librmonitor_base`、`librmonitor_memory`、`libmatrix-hookcommon`、`libmatrix-traffic`、
  `libshadowhook`、`libbugly_shadowhook`、`libthreadsuspend`、`liblogcathook`、
  `libunusedcodecheck`——它们都在进程内读 `/proc/self/maps`。崩溃与符号化那几家
  （`libBugly_Native`、`libwechatbacktrace`、`libwechatcrash`）不补：它们读 maps 与
  `/proc/self/mem` 是为了出崩溃报告，挡住会让报告本身坏掉。
- **黑名单补 `susfs`、`lspatch`、`dobby`**（路径、maps 行、目录项、环境变量、进程名、
  属性六处同一张表）。这三个本机都没装，补的是「以后装上任意一种也被命中」。
- **静态自检加 9 条**（SoMonitor 四家、Java root 检测四家、wlogin 的文件探测口）。
  `internal/compat` 与 `/healthz` 的 `compat` 从 240 项变 249 项。
- **`/healthz` 的 `env_report` 多一段 `native_monitor`**：`hooks` 是拦下来的安装点数，
  `beacon_dropped` 是被丢掉的事件数，`last` 是最近一条事件名。

升级提示：沿用包名与签名，可直接覆盖升级。装完模块会重启一次 QQ 进程，其余不需要手工动作。

边界没变：硬件认证（Key Attestation）与 TEE 相关的判定在本机拿不到，服务端风控也不在
客户端能改的范围里，这两条不受本版影响。
