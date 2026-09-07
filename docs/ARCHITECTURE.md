# 架构（QQ 9.3.60.40970）

```
Main            QQ 所有进程：MapsHide + AntiDetect；仅主进程再 SatoriHub → QQClient
Cfg / L         配置；日志 tag Q.Kernel（verbose 才打 XposedBridge）
net/            手写 HTTP + 事件 WS，只绑 127.0.0.1
core/           Satori 方法分发、事件、message id / file id 注册表
satori/         元素解析与事件映射
qq/             NT 桥、段转换、Silk、GOT+seccomp
packet/         OIDB / 长消息 / 群文件
native/         libmapshide.so
```

## 双清单（Duck Detector 对抗）

`AndroidManifest.xml`（引导）带全套 Xposed meta-data 供 vector 列模块与注册；
`AndroidManifest.stealth.xml`（隐身）零 `xposed*` 键，仅保留 `assets/xposed_init`。
依据：LSPosed 守护进程解析已启用模块只看入口文件（`ConfigManager.getModuleApkPath`
+ `ConfigFileManager.loadModule`），`PACKAGE_REPLACED` 不在其广播处理之列，
meta-data 仅管理器 UI 与 `PACKAGE_ADDED` 注册路径需要。先启用引导包，再
`pm install -r` 隐身包；升级照旧（build.sh 步骤 6/7 产出并断言双包）。

**收** `IKernelMsgListener.onRecvMsg` → Satori `message-created`。**发** `message.create`：`<message forward>` 走合并转发，否则 `sendMsg`。**OIDB** 走 `onSendSSORequest`（不要 `onSendOidbRequest`，会把 0x8FC 拼成 `0x2300`）。QQ 自己做 SSO / QSec 签名。

在线：账号 + NT session + MsgService + 当前 listener，且栈顶不是 `LoginActivity`。离线动作 1500。

富媒体：`RichMediaElementGetReq(msgId, peerUid, chatType, elementId, 1, 0, "", fileModelId, 0, 1)` → `downloadRichMedia`；视频失败再 `getVideoPlayUrlV2`。群文件 0x6D8 查、0x6D6 文件、0x6D7 目录。

## JNI（`nativeinterface`，避开混淆 `api.*`）

会话：hook `IQQNTWrapperSession$CppProxy` 构造捕获。兜底：另 hook `getMsgService` / `getGroupService`（无锁身份校验短路），捕获 hook 安装前已建、构造函数漏抓的活动会话——否则会出现 QQ 在线、HTTP 端口在听、却 `isOnline()` 恒 false、发送全 1500 的隐性离线。服务：`getMsgService` / `getGroupService` / `getProfileService` / `getBuddyService` / `getRichMediaService`。

- 发：`sendMsg(msgId, Contact, elems, emptyAttrs, cb)`；`Contact(chatType, peerUid, "")`；群 peer=群号，私聊 peer=**uid**
- uin→uid：`getUidByUin`
- 听：`IKernelMsgListener`（~40 方法，Proxy 漏一个就挂）
- 历史：`getMsgs`；撤回：`recallMsg`
- 好友：`getBuddyListFromCache`（枚举拼写 `KNOMAL`）+ `getCoreInfo`
- `MsgConstant.KELEMTYPE*`：TEXT1 PIC2 FILE3 PTT4 VIDEO5 FACE6 REPLY7 GRAYTIP8 ARK10 MARKETFACE11 MULTIFORWARD16
- 图：`genFileMd5Hex` → `getRichMediaFilePathForMobileQQSend` → `copyFile` → `PicElement` → sendMsg 自动上传
- 语音：`SilkCodecWrapper.encode`（本机 VideoElement **无** fileWidth/fileHeight）
- QSec：`getSign(String, byte[])` 不动；`detectMethod` / `getXpsInfo` 可中和；`getFeKitAttach` 只计数；`trpc.o3.report` 可丢（不要动 `ecdh_access`）

QQ 9.3.60 移除了 `MsgRecord.senderRoleType` 及 `RevokeElement.senderUid`；可选兼容字段必须经
`Ref.getOrNull` 探测，不能让缺字段中断整条消息事件。升级 QQ 后需重新核验上述
JNI 类、方法签名、元素字段和 QSec 命令白名单。新版 `MsgRecord.roleType/roleId`
不是群成员身份，群消息角色从 `getAllMemberList` 缓存解析。

## 常驻（合作式前台服务）

VPN 式保活,不是反复拉起进程。服务在线时,把 QQ 主进程里一个已声明
`foregroundServiceType` 的 service 提升为**真正的前台服务**,进程即进入 FGS 优先级档
(实测 `oom_score_adj` 从 ~450 降到 ~50,后台时依然),系统低内存回收与 OEM 后台冻结
默认放过它——像 VPN/音乐应用一样一直活着。

- 宿主 service:`com.qq.background.task.service.QQDataSyncService`(主进程,声明
  `dataSync`,免运行时权限;QQ 持有 `FOREGROUND_SERVICE_DATA_SYNC`)。以自定义 action
  启动,在 before-hook 里 `startForeground` 并短路其 `onStartCommand`,QQ 自身对该
  service 的使用不受影响。`onStartCommand` 实际声明在基类 `QQBackgroundService`,安装
  hook 时逐级向上找到声明处。
- 通知即 FGS 通知(与状态通知同一条、同 id),绿色「运行中」。
- **合作、非对抗**:用户强停 / 划掉 QQ → service 随进程死 → Satori 断开,**不复活**。
  这正是与「monkey 反复拉起、跟 cgroup freezer 硬掰、改系统名单」的看门狗相反的取舍:
  开着就保活,关了就干净断开,把控制权交回用户。
- **一次性授权**:首次在线且未加 Doze 白名单时,弹一次系统「忽略电池优化」对话框
  (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`);授予后后台启动 FGS 始终放行。
- 开关:`foreground_keepalive` / `request_battery_exemption`;通知另由 `status_notification`。
- **唤醒锁开关(仿 Termux)**:常驻通知带「获取/释放唤醒锁」动作按钮。以 QQ 身份持有
  `PARTIAL_WAKE_LOCK` + 高性能 Wi-Fi 锁——FGS 保进程常驻,唤醒锁再保其不被打盹冻住网络。
  按钮 PendingIntent 发一条自寻址、NOT_EXPORTED 的广播到运行时注册的接收器,每点一下翻转
  状态并重绘通知。手动持有默认关;此外**每个 mutation 自动持锁**(`guarded()` 里 begin/end,
  自动锁带 3 分钟超时),`wifi_sustain` 则在有客户端连着时长期持 Wi-Fi 锁。
  开关:`wake_lock_control` / `wifi_sustain`。
- 局限一(进程):FGS 大幅降低被杀/冻结概率但非绝对;ColorOS 等激进省电下仍建议保留 Doze
  白名单(即上面的一次性授权)。QQ targetSdk 34,不受 Android 15+ `dataSync` 6 小时上限约束。
- **局限二(网络,应用层无解)**:厂商 ROM 的「睡眠待机优化 / 深度睡眠」会在夜间预测窗口里
  **直接切断数据通路**,不是限速。ColorOS 的实现见 `com.oplus.battery` 的
  `DeepSleepSharepref.xml`(`deep_sleep_is_disable_net_allowed`、`deepsleep_network_switch`
  = 3 表示 Wi-Fi + 移动数据一起关,故换网络也复现)与 `com.oplus.deepsleep.RestoreNetworkReceiver`。
  症状形状很特征:**纯文字正常、图片/合并转发失败**——文字一个包顺着已建立的 MSF 长连接就
  出去了,而富媒体上传在 `sendMsg` 内同步进行,要新建连接与持续吞吐,断网时第一个失败
  (`code=-1 / rich media transfer failed`)。模块侧只能兜底(`sendMedia()` 重试 + 发送期持锁),
  真正的修复是设备侧关掉该优化;整机流量若走本地代理/VPN,那个应用也要进电池优化白名单。
  次要因素:息屏 Wi-Fi 省电把到 AP 的 RTT 从 ~4ms 抬到 ~27ms,同样应用层管不了——Android 14+
  把 `WIFI_MODE_FULL_HIGH_PERF` 重映射成低延迟锁,而低延迟锁只在亮屏且持锁应用前台时激活,
  只有设备侧 `cmd wifi force-hi-perf-mode` 能强制(需要至少一把锁存在才生效)。

## 可观测性

- HTTP accept 循环自带 3s 重绑,端口丢失可自愈;Satori HTTP/WS 只绑 `127.0.0.1`,
  无对外连接。
- `GET /healthz`(本地免鉴权)直吐 `online` / `listening` / `self_id` / 在线时长,以及
  `notice`(通知投递状态)、`keepalive`(FGS 状态)与 `wakelock`(唤醒锁状态)诊断,机器可读,把「真在线」与
  「端口在听但内核离线」分开。
- QQ 通知栏常驻一条静默(`IMPORTANCE_LOW`)通知,随状态切换 运行中 / 等待登录 /
  服务异常,人可读。需 QQ 具备通知权限(Android 13+ 的 `POST_NOTIFICATIONS`,默认已授予;
  开启前台保活时它同时作为 FGS 通知)。
