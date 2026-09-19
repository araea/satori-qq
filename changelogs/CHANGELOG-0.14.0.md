## 0.14.0 · 知弦

这一版处理人脸核身（身份认证用的「刷脸」）提示
`你的设备环境异常，无法使用人脸识别能力。请更换成安全设备重试。` 这条链路。

### 先说清楚这条链路的形状

核过之后有三件事是确定的，写在这里，免得下次又从头查：

- **那句话不是客户端的字符串**：41 个 dex 与 `resources.arsc`（UTF-8、UTF-16 都试过）里搜不到
  `设备环境异常` / `安全设备`。它出现在 `beacon_db_...:MSF` 的 `errorMsg` 字段里，
  也就是**服务端 SSO 响应带回的错误串**，客户端只负责显示
- **人脸走的是慧眼 SDK + TuringFace**：`IdentificationHuiyanSDKInitHelper` 起
  `HuiYanAuth`（慧眼 1.0.9.32），慧眼内部用 `com.tencent.turingcam.TuringFaceDefender`
  （TuringFace 2.3.0，上报到 `sdk.faceid.qq.com/api/turing_new`）采设备风险。
  下载下来的两个活体库 `libYTLiveness.so` / `libYTCommonLiveness.so` 里没有任何环境判定
- **上报通道与别的检测不是一条路**：慧眼事件 →
  `IQSecChannel.feEnvReport` → `MainProcess2Fe.k("face_detect"|"camera_detect")` →
  `O3BusinessHandler.P2` → MSF `cmd_sec_dispatch_event`。它**不走** `ChannelProxy.sendMessage`，
  所以按命令名的丢包名单一条都拦不到它；而 `cmd_sec_dispatch_event` 同时承载
  `FaceQueryAppConf`、`FaceGetRecognitionResult` 这些必须放行的请求，也不能整条丢

### 这一版挡在哪

- **慧眼采集的那份设备数据不再出站**：`QSecChannelImpl.feEnvReport` /
  `feCameraActionReport` 直接 no-op（QQ 这里传的回调是 null，不会挂住调用方），
  `MainProcess2Fe.k(..., "face_detect"|"camera_detect", ...)` 丢弃并计数。
  开关是新的 `block_face_report`（默认 `true`）
- **TuringFace 的设备信息不再返回 null**：`TuringFaceDefender.getDeviceInfo` 与
  `TuringSdkImp.b()` 的结果保证是 JSONObject——null 会被慧眼当成「采集失败」写进上报
- **TuringFace 的初始化错误串不再上报**：`TuringSdkImp.a()` 返回空串，
  `turing init error code: N` 这类本机故障不再被当成环境证据
- **turingcam 的进程表扫描改成「留真名、只滤敏感条目」**：以前把 `oqKCa.a(int)` 一律置成
  null，等于**整张进程表清空**。空进程表正是虚拟机/沙箱的长相，把这份数据交给服务端比读到
  真进程名更可疑；而真进程名里本来也不会出现 root 管理器的字样（本机没装）
- `/healthz` 多一个 `face` 段：`dropped` / `events`（按事件名）/ `hooks`
  （`face_report`、`turing_face`、`turing_process`）。`enabled=true` 而 `hooks.*=0`
  表示这一版没挂上钩子，要按 0 处理

### 这一版修不了什么

判定在服务端，客户端能做的只是「别把脏数据送上去」。2026-09-18 01:35 那次现场里，
慧眼 SDK 其实**跑完了整套活体流程**，最后停在 `errorcode:1007 活体检测没通过，请重试`
（`cloud-huiyan` 日志），说明服务端当时是肯处理这台设备的——所以那条
`设备环境异常` 更可能来自更早的 `FaceQueryAppConf` / `face_usable` 响应，而不是慧眼上报的结果。

`block_face_report` 单独做成开关就是为了 A/B：服务端如果依赖这批数据做判定，
关掉反而更脏；如果判据来自服务端已经存下的历史，开关怎么调都一样。两种情形只有试出来才知道。

升级提示：沿用包名与签名，直接覆盖升级；装完本端会重启一次 QQ 进程（旧 dex 不会热更）。
这版只加检测面的钩子与一个观测段，不影响登录态、配对逻辑与消息收发。
