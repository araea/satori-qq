# 过检测

记录 QQ 在本机自己做了哪些检测、模块挡在哪里、哪些挡不住。结论按真机 QQ 9.3.55 与 9.3.60.40970（versionCode 16070）的二进制核实。

## 检测从哪来

QQ 的检测分三条路。

- `libfekit.so`：QSec 的宿主。读 `/proc/self/maps`、`/proc/self/smaps`、`/proc/self/mountinfo`、`/proc/self/cmdline`，并逐个读 `/proc/<pid>/cmdline` 找别的进程。字符串里带 root 管理器黑名单，以及 `cat /proc/mounts | grep magisk`。
- `libturingxq.so` 与 `libturingmfa.so`：Turing 风控。读 `/sys/fs/selinux/enforce`，枚举 `/data/local/bin`、`/data/local/xbin`、`/sbin/`、`/system/xbin/`、`/system/sd/xbin/`，看 `/system/bin/ddexe`、`debuggerd`、`debuggerd64` 与 `/system/etc/install-recovery.sh` 是否存在。
- `libmsfbootV2.so`：启动期上报。读 `/data/data/com.tencent.mobileqq/files/imei`，并用 `dl_iterate_phdr` 看模块列表。

`libMSFKernel.so` 读 `/proc/loadavg`、`/proc/stat`、`/proc/self/stat` 与 `/sys/.../scaling_cur_freq`，这些是遥测，不是环境判定，模块不碰它。16070 起它还用 `inotify_init1`/`inotify_add_watch` 盯文件变化，模块没接管。

Java 侧在 `com.tencent.mobileqq.qsec.qsecurity` 下。`QSec` 的 `detectMethod`、`getXpsInfo`、`execTasks`、`reportLog` 是检测入口；`getSign`、`getLiteSign`、`getSignEntry` 是签名链路，不能动；`getFeKitAttach` 是登录附件，只记录不改。`SocketStatus.checkSocket(name)` 连一个抽象本地 socket，连上或权限被拒返回 1，连不上返回 0，用于探 LSPosed、Zygisk、Shamiko 这类守护进程。`ProcessCheck` 读 `/proc/self/cmdline` 取自己的进程名。16070 的 `qsecurity` 包与 16030 逐类相同，没有新增检测入口。

上报命令里，`trpc.o3.report.*`、`trpc.o3.mobile_security.*`、`trpc.gc_indust.device_report.*`、`trpc.ilive_cdn.report.*`、`OidbSvc.0xd79`、`OidbSvcTrpcTcp.0x9c00_*`/`0x9c01_*`/`0x9c02_*`/`0x9c0c_*`/`0x9cdf_*` 是环境上报，模块丢弃。`trpc.o3.ecdh_access.*` 是登录密钥交换，保留。`wtlogin.device_lock` 是账号设备锁服务，不是环境上报，模块不动它。

### 16070 新增的 QSec_Channel 上报器

16030 → 16070 只有 `libfekit.so` 与 `libMSFKernel.so` 变了，turing 系、`libQSec.so`、`libmsfbootV2.so` 的 md5 完全一致。fekit 的新 import 只有 `inet_addr`、`recv`、`send`、`setsockopt`（`send` 已在 GOT 拦截范围内），检测路径与黑名单关键字一条没加。真正的变化在上报：

- 二进制里新增 `channel/src/reporter/{async,delay,high_reliability,super}_reporter.cpp`、`super_reporter_cache.h`，以及 `HighReliabilityReporter loadFromDisk/saveToDisk/report`、`QSec_Channel report retry!,%d`、`QSec_Channel report error! retry 3 times!`、`QSec_Channel cached size:%d`、`sendRequestPB:cmd:%s,datalen:%d`。
- 命令表从 312 条变成 317 条，新增的 5 条是 `OidbSvcTrpcTcp.0x9c00_0`、`0x9c01_0`、`0x9c02_0`、`0x9c0c_0`、`0x9cdf_1`。前四条全 APK 只出现在 libfekit，`0x9cdf_1` 与 libMSFKernel 共用。
- 这套上报器会把报不通的内容落盘、之后重发，重试 3 次。只按旧的 `trpc.o3.report.*` 名单丢包挡不住它，这是 16070 之后「又被检测设备风险」的直接嫌疑。模块把这 5 条一起丢，并向服务端回空成功，避免它继续重试。

`0x9cdf_1` 是这 5 条里唯一与 libMSFKernel 共用的，命令表里挨着 `wtlogin.log_report`，所以它的影响面比另外四条大。真要回滚，配置里把 `block_o3_report` 置 false 即可整体关掉这套丢包逻辑。

## 模块怎么挡

Java 层在 `qq/AntiDetect`，装在每个 QQ 进程：

- QSec 检测入口按返回类型置安全值：`detectMethod` 恒假，`getXpsInfo` 返回空，`execTasks` 与 `reportLog` 返回 0
- `SocketStatus.checkSocket` 命中框架名字时返回 0，其余名字原样放行
- ChannelProxy、ChannelManager、MsfCore 的收发口按命令白名单丢环境上报，并向服务端回空成功，避免上报器重试
- Root、Xposed、调试器、模拟器、包管理、堆栈、Pandora、Turing、MSF 遥测与强制下线处理逐项覆盖
- 设备标识只在配置了假值时改写，多路径保持同一值

### 踢下线的入口

服务端踢线在客户端有不止一个处理入口，它们互不经过对方。少挡一个，被踢时界面照样退回登录页：

| 入口 | 触发方 | 拦下的方法 | 计数标签 |
| --- | --- | --- | --- |
| `com.tencent.mobileqq.kick.NTKickProcessor` | 内核 `IKickApi` 收到的踢线 | `a(AppRuntime, KickedInfo)`、`b(AppRuntime, KickedInfo, LogoutReason)` | `nt-kick` |
| `com.tencent.mobileqq.login.ntlogin.ao` | `NTLoginTicketManager` 刷新登录票据失败，错误码 140022014/140022015/140022016 或 `refreshMethodNeedKick` | `f(int, String)` | `ticket-refresh` |
| `com.tencent.mobileqq.login.api.impl.UidServiceImpl` | 取不到 UID | `kickToLoginPage()` | `uid-fail` |
| `mqq.app.MainService$MyErrorHandler` | MSF 把强踢当错误事件抛上来 | `onKicked` / `onKickedAndClearToken` / `onKickedInternal` / `onCloneError`（**处理器入口**） | `msf-kick-entry` |
| `mqq.app.MainService$MyErrorHandler` | 同上，最后一步的界面出口 | `popupNotification(...)`（6 参与 8 参两个重载）、`popupNotificationEx(...)` | `msf-kick` |
| `mqq.app.MainService$MyErrorHandler` | 账号进风控灰名单 | `onGrayError(...)`（**只当软事件，见下**） | `soft-kick` |

`NTKickProcessor` 那条：`a` 是接口 `IKickApi.b` 的实现，`b` 是它调用的私有方法。只拦 `b` 的话，`a` 里在它之前做的几件事照旧执行——`kick.a` 线程（清登录数据）、`updateSimpleAccount(uin,false)`、`reportClearLoginData(uin,"2004")`、`setSortAccountList`，本地账号列表当场被标成已下线。两个都拦。

**MSF 那条要拦处理器入口，不是拦 `popupNotification`。** `popupNotification` / `popupNotificationEx` 是 `MyErrorHandler` 一堆回调（`onKicked`、`onKickedAndClearToken`、`onUserTokenExpired`、`onServerSuspended`、`onCloneError`、`onGrayError`）共同的最后一环，里面做的是 `appRuntime.logout(reason, true)` 再拿 `LoginActivity` 发 `ACTION_KICK_TO_LOGIN`。但**毁本地登录态的写在它前面**，只拦出口等于只挡住了界面动作。0.8.9.46 起 `onKicked` / `onKickedAndClearToken` / `onKickedInternal` / `onCloneError` 四个入口整体 no-op，出口那两个继续挂着，负责按 reason 过滤 `onUserTokenExpired` 与 `onServerSuspended`。

`onGrayError` 单独处理：它兼管 `wt_GetStViaSMSVerifyLogin` 与 `wt_loginAuth` 的响应，整条拦掉会把「被踢之后靠短信验证登回来」这一步封死；而它又可能反复发生，当成硬踢线会让看守每来一次就 force-stop QQ 一次。所以只做两件事——放行（除上述登录命令外），并把善后窗口打开、记一行 `soft-kick` 到 `qk_guard.log`（不算 `blocked_kicks`、不写 `qk_kick.log`）。

不是所有 `LogoutReason` 都该拦。拦的是 `kicked`、`secKicked`、`forceLogout`、`suspend`；放行 `user`（用户自己退出）、`switchAccount`（切号）、`expired`（票据自然过期，QQ 自己会重登）、`tips`、`gray`、`restartProcess`——拦这些才是真出问题。判定在 `AntiDetect.kickReasonBlocked`，有单测。

`/healthz` 的 `kick_hook` 是踢线入口的 hook 数之和（0.8.9.46 起正常为 **12**：nt-kick 2 + ticket-refresh 1 + uid-fail 1 + msf 入口 4 + msf 出口 3 + gray 软事件 1），`last_kick_source` 记下最近一次是哪个入口拦下的，`last_kick` 记参数，`kick_log` 是最近 12 次的原文。另有一个独立的登出守卫，hook 数在 `logout_guard.hooks`（0.8.9.46 起正常为 **5**），以及保住盘上登录态的守卫，在 `login_state.hooks`（正常为 **2**）。

`last_kick` 现在带这些字段：`kickType=`（`RequestMSFForceOffline.bKickType`，名字按 `KickedType` 的声明顺序取，见下）与 `sigKick=`（1 表示带 `vecSigKickData` 的安全强踢，reason 取 `secKicked`；0 是普通强踢），另有 `seqno=` / `sigLen=` / `sameDevice=`。内核那条路（`nt-kick`）参数是 `KickedInfo`，字段比 MSF 包多，单独记 `appId=` / `instanceId=` / `securityKickedType=`。只记 reason 与服务端文案的话，现场分不出「在别处登录被顶」和「风控打击」。

### `kickType` 那个字节能读到什么程度（2026-09-15 核）

16070 的 `classes.dex` 里 `com.tencent.qqnt.kernel.nativeinterface.KickedType` 的声明顺序是：

```text
KKICKBYMULTIINST(0), KKICKBYMOBILE(1), KKICKBYPASSWORDCHANGE(2), KKCIKBYLOWVERSION(3)
```

`KickedInfo` 的字段是 `appId, instanceId, kickedType, sameDevice, securityKickedType, tipsDesc, tipsTitle`——**`appId` 是判「谁把我顶了」的唯一字段**（PC / 手机 / 平板各有自己的 appId），MSF 那个包里没有它。

仍然不确定的两件事，别当成结论用：

- 「服务端那个字节就是枚举序号」是推定。没有任何 Java 类读这个字段（映射在 native），而 native 里连这几个枚举名的字符串都没有（`libMSFKernel.so`、`libkernel.so` 等逐个 `strings` 过，0 命中），所以核不到映射代码。日志里名字后面一直带 `?`。
- `0` 有歧义：`KickedInfo` 的默认构造就是 `KickedType.values()[0]`，服务端没填时同样取到 0。

## 设备侧现状（2026-09-15 实测，判断「环境到底脏不脏」用这几条）

| 项 | 本机 | 说明 |
| --- | --- | --- |
| `ro.boot.verifiedbootstate` | `green` | 设备层已处理（KSU 侧模块改的），与 `ro.boot.flash.locked=1` 一致 |
| `ro.boot.flash.locked` | `1` | 同上 |
| `ro.boot.hardware` / `ro.bootloader` | `qcom` / `unknown` | libfekit 只读这两个，没有异常 |
| `ro.debuggable` / `ro.kernel.qemu` | 空 / 空 | libfekit 读；空是正常 |
| `ro.build.tags` / `ro.build.type` | `release-keys` / `user` | 正常 |
| `ro.vivo.oem.name` | 空 | libfekit 读它（跨厂商 ROM 检查）；空是正常 |
| root 管理器应用 | 一个都没装 | libfekit 自带名单：magisk / kernelsu / apatch / kingroot / kingo / shuame / smedialink / zhiqupk / cleanmaster 系，`pm list packages` 逐个对过 |
| `/sys/module/kernelsu`、`/sys/module/apatch` | 不存在 | 本机 KSU 编进内核（GKI），没有模块目录 |
| `files/imei`（libmsfbootV2 启动读它） | `4538aeb7a1c7d631`，2026-09-07 起没变 | 设备指纹稳定，模块也没设 `fake_imei`（配置文件不存在，全用默认值） |
| 时钟偏差 | 与 baidu/tencent/deepseek 回包的 `Date` 差 1 秒内 | SSO 签名对时间敏感，偏差大的设备会「登录已失效」 |
| libfekit 读的 `/sys` 路径 | `/sys/devices/soc0/serial_number`（应用读是 EACCES）、`cpu0/cpufreq/{cpuinfo_max,cpuinfo_min,scaling_cur}_freq` | 前者普通应用拿不到（QQ 也拿不到），后者是遥测 |
| libturingxq / libturingmfa 读的 `/sys` | `/sys/fs/selinux/enforce`、`cpu%d/cpu_capacity` | 本机 enforcing（正常） |
| libMSFKernel 的关键字 | 只有 `loadavg` / `meminfo` / `stat` 与它自己的 `.MSF*` 数据文件 | 没有 root/hook 关键字名单；inotify 盯的是它自己那些文件，不是检测面 |

结论：**客户端这边看不到能解释「被设备异常整下线」的脏东西**。所以再堆过检测的收益有限，先把「下一次踢线到底是什么性质」的证据抓全（见上），别反过来先改策略。

踢线原文本该从 `Packet.decodePacket(buf, "RequestMSFForceOffline", new RequestMSFForceOffline())` 解——那就是 `MainService` 自己解这个包用的入口。别的回调带的是另一种包，硬解会得到垃圾字段，所以解完要校验（标题或正文至少一个非空，或 uin 非 0），过不了就只记 `cmd=` 与 `uin=`。

### 踢线之后：本机毁的是**盘上**的登录态（0.8.9.46）

这是 0.8.9.44/45 两次都没修对的地方，写清楚下次别再绕。0.8.9.44 认为拦下 `popupNotification` 就够了，0.8.9.45 认为漏的是 `UidServiceImpl.logoutWhenReqUidFail`——**两个判断都错**，实际漏的那条就在 `onKickedInternal` 里，而且早于被拦下的出口：

```text
MainService$MyErrorHandler.onKickedInternal(ToServiceMsg, FromServiceMsg, isTokenExpired, isSameDevice)
  ├─ isTokenExpired == false（onKicked 进来）
  │    mApplication.setAutoLogin(false);                  // mmkv 落盘，60 秒守卫来不及
  │    popupNotification(..., forceLogout, ...)            // ← 0.8.9.44 拦的是这里
  └─ isTokenExpired == true（onKickedAndClearToken 进来）
       MsfSdkUtils.updateSimpleAccount(uin, false);        // files/user/u_<uin>_t → _f  ← 要害
       mApplication.setSortAccountList(...);               // 已登录列表当场少一个号
       popupNotification(..., LogoutReason.kicked, ...)    // ← 0.8.9.44 拦的是这里
```

2026-09-15 18:33 那次走的就是下面这一支：`qk_kick.log` 记的是 `reason=kicked`、`bSigKick != 1`，对应 `onKickedInternal` 的 `isTokenExpired == true` 分支，也就是 `onKickedAndClearToken` 进来的。证据链是齐的——出口被拦下了（`blocked_kicks=1`），但**账号标记已经改名、自动登录已经关掉**，所以重启多少次都停在登录页。而 0.8.9.45 挂的 `logout_guard` 一条都没命中（`qk_guard.log` 里空），正说明登出不是那个时机发生的。

两个落盘位置：

| 状态 | 在哪 | 谁写它 |
| --- | --- | --- |
| 账号是否在已登录列表 | `/data/data/com.tencent.mobileqq/files/user/u_<uin>_t`（`_f` = 已登出） | `MsfSdkUtils.updateSimpleAccount*(uin, boolean)` |
| 下次是否自动登录 | `common_mmkv_configurations` 的 `mqq_account_auto_login_<uin>`（2 = 自动，1 = 手动） | `mqq.app.AutoLoginUtil.setAutoLogin(uin, boolean)` |

所以 0.8.9.46 三层一起上：

1. **拦入口**：`onKicked` / `onKickedAndClearToken` / `onKickedInternal` / `onCloneError` 整体 no-op，上面那些写操作一次都不会发生。
2. **顶回去**：善后期内 `MsfSdkUtils.updateSimpleAccount` / `updateSimpleAccountNotCreate` 的 `false` 顶成 `true`（改名仍然发生，但改成 `_t`，账号留在列表里），`AutoLoginUtil.setAutoLogin(uin, false)` 顶成 `true`。
3. **修回来**：离线时把 `u_<uin>_f` 改回 `u_<uin>_t`、把自动登录开关写回 2，各记一行 `self-heal`。要修哪个号只认踢线原文里的 `uin=`（内存里没有就读 `qk_kick.log` 末行）——**不要**拿「`user/` 里唯一那个 `_f`」当兜底，本机就有一个用户 2026-09-11 自己注销掉的 `u_1665757132_f`，按这个猜会把早就登出的号重新标成已登录。

### 踢线之后：不许本机自己登出

拦下踢线入口并不覆盖所有登出路径，所以另有一层登出守卫。`AppRuntime.logout(boolean)` 底下会把 reason 写死成 `user`，`QQAppInterface.logout(boolean)` 是它的 override 且带 kickPC 的注释——这两个入口既服务「用户点退出登录」（主线程），也服务「踢线路径顺手登出」（`UidServiceImpl` 在工作线程上调的就是它），reason 分不出来，**按调用线程分**：主线程那次当用户点的，放行并记下「用户主动退出」；工作线程那次窗口内 no-op。

| 入口 | 拦法 |
| --- | --- |
| `UidServiceImpl.logoutWhenReqUidFail()` | 5 分钟窗口内整体 no-op（连带摘账号、报清数据一起停） |
| `QQAppInterface.logout(boolean)` / `AppRuntime.logout(boolean)` | 窗口内且**非主线程**才拦；主线程那次视为用户主动退出 |
| `AppRuntime.logout(LogoutReason, boolean)` | 窗口内**且** reason 是 `kicked`/`secKicked`/`forceLogout`/`suspend`；reason 是 `user`/`switchAccount` 时只记「用户主动退出」 |
| `AppRuntime.ntTriggerLogout(LogoutReason)` | 同上 |

只有 `user` 与 `switchAccount` 算「用户主动退出」（`AntiDetect.userInitiatedLogout`）。`expired` / `gray` / `tips` / `restartProcess` 是 QQ 自己的生命周期，把它们当成用户意图会让善后期在一件跟用户无关的事上失效。

被拦下的登出记在 `/healthz` 的 `logout_guard`（`hooks` / `blocked` / `log`），落盘到 app 私有目录的 `qk_guard.log`。**故意不写进 `qk_kick.log`**：那份是看守「踢线 = 会话已作废，立刻重启」的判据，混进去会让看守反复重启 QQ。

善后期（保账号、保自动登录、自愈）是 15 分钟且跨重启成立，比 5 分钟的登出守卫长：判据除了内存里的 `lastKickMs`，还看 `qk_kick.log` 的**修改时间**（带 5 秒缓存）。看守恰好在踢线后 force-stop QQ，重启之后内存计数归零，只靠内存的话善后期在新进程里等于不存在。

一个已知副作用：善后期内（只是被拦下踢线后的 15 分钟）用户按退出登录，QQ 的账号标记可能已经被顶成 `_t`，于是下次启动会自己登回来，用户得再退一次（那时已在窗口之外）。窗口很短，且比「被踢之后退不出来 / 登不回去」轻，所以不做额外处理。

### 踢线之后：自动登录会被关掉

这一条比拦踢线本身更要紧。QQ 在踢线路径上顺手做 `appRuntime.setAutoLogin(false)`：

```text
QQAppInterface.setAutoLogin(false)
  -> mqq.app.AutoLoginUtil.setAutoLogin(uin, false)
     -> common_mmkv_configurations["mqq_account_auto_login_<uin>"] = 1   // 2 才是自动
```

这是**落盘**的。所以踢线之后哪怕把 QQ 拉起来，它停在登录页、不会自己登回来，外部看守重启多少次都一样。`NTKickProcessor.a`（`KKICKBYMULTIINST` 分支）与 `MainService$MyErrorHandler.onKickedInternal` 里都有这一句。

注意这句在 `onKickedInternal` 里位于 `popupNotification` **之前**（`isTokenExpired == false` 那一支的第一句）。0.8.9.44/45 的守卫挂在 `popupNotification` 上、按「拦下踢线后 60 秒」计时，等于永远晚一步：那句执行时 `lastKickMs` 还是 0。现在改成善后期判据（`inKickAftermath`，15 分钟且跨重启），命中数在 `/healthz` 的 `auto_login_kept`。

`qk_kick.log` 里逐条记着时间、入口、reason、`kickType`、`sigKick`、标题与正文（app 私有目录，0600，只 root 可读）。落盘的理由和 `blocked_kicks` 会随进程重启归零有关：`scripts/qq-revive.sh` 按这个文件的行数增长判断"刚刚又被踢了"，跨重启仍然成立；`online=false` 连着几轮也重启（那是已经退出登录，只能靠登回来）。

### 踢线成因怎么分：环境检测还是接口把会话打废

被踢下线有两种性质完全不同的成因，处置方向相反：

- **环境检测**：设备风险被打分，服务端主动下发强制下线。
- **接口把会话打废**：某个请求回来的错误码让客户端认定登录票据失效，于是走「刷新票据失败 → 踢回登录页」。QQ 只在少数几个码上这么做：`login.ntlogin.ao.f(int, String)` 的 140022014/140022015/140022016，以及 `MainService$MyErrorHandler.onUserTokenExpired` 的 `ssoErrorCode` 为 -10135 或 10136。

两者用三个字段分开：

| 看哪里 | 环境检测 | 接口打废会话 |
| --- | --- | --- |
| `last_kick_source` | `msf-kick-entry` / `nt-kick` / `msf-kick` | `ticket-refresh`（`f(int,String)` 被拦下） |
| `last_kick` 的 `sigKick` | 0（普通强踢）或 1（带签名数据的安全强踢） | — |
| `sso.session_errors` | 保持 0 | 涨 |

`sso.session_errors` 是模块**自己**发的 SSO 请求（`PacketSvc` 的 OIDB 与裸 trpc）撞上那组码的次数，判定在 `PacketSvc.sessionFamilyCode`，有单测钉住这张表（多认一个码会把业务失败误报成会话失效，少认一个码就分不出来了）。原文落盘 `qk_sso.log`（app 私有目录 0600，超 64KB 只留尾部 32KB），时间戳可以直接和 `qk_kick.log` 对。超时只计数不落盘——网络慢时那是噪声。这条只是观测，不改任何行为。

真机实测（2026-09-15，0.8.9.46）：`sso.failures` 与 `sso.session_errors` 全程为 0，模块自己的调用没撞上会话类错误码。

Native 层在 `native/mapshide.c`，只对检测库改 GOT，不动其它库：

- 命中库：libfekit、libturingxq、libturingmfa、ckguard、wtecdh、libQSec、dandelion、libmsfbootV2
- 接管这些库 import 的 `open`、`openat`、`fopen`、`stat`、`access`、`readlink`、`getdents64`、`readdir`、`syscall`、`__system_property_get` 等符号，换成自己的包装
- 包命中的 `/proc` 路径做行过滤：maps、smaps、smaps_rollup、mountinfo、mounts、status、environ、cmdline、tcp/tcp6，0.8.9.44 起加上 `fdinfo`（`name:\t<路径>` 行）与 `numa_maps`（`file=<路径>` 行）；路径命中黑名单直接返回 `ENOENT`
- 目录列举的**条目名**也过同一张表（`dent_name_blocked`，`getdents64` 与 `readdir` 共用一个判定）。0.8.9.46 起多一条前缀规则：`qk_` 开头的条目一律不列出——模块自己在 `files/` 下的 `qk_env_*.json`、`qk_kick.log`、`qk_guard.log`、`qk_sso.log` 属于这一类。检测库不需要读 `/proc` 也能在**自己进程内** `getFilesDir().listFiles()` 看见它们，而 `qk_env_maps_main.json` 的内容直接写着模块打了多少个 GOT、拦了哪些库、`loop_ok` 是多少。这个前缀是模块独占的：41 个 dex 里一个 `qk_` 字符串都没有（`libfekit.so` 里 grep 得到 4 处，是指令字节的假命中，附近是 `5280..`、`b97f0100` 这类 AArch64 编码，不是字符串表内容）
- 无路径的可执行映射一律从行过滤里去掉（0.8.9.44 起 `rwxp` 也算，此前只滤 `r-xp`）。真机上核对过主进程那两条无路径 `rwxp`：一条 4KB（与 `:MSF` 同地址）、一条约 1.9MB（只有主进程有），读出来的字节都是 aarch64 蹦床（`ldr x17,#8; br x17`、`ldr x0,#12; br x16` 这类），也就是 inline hook 的落地页。带 `[anon:...]` / `[anon_shmem:...]` 名字的映射不受影响
- 无路径的 RX 映射改名成 `dalvik-jit-code-cache`，模块自己的 `.so` 从 memfd 以同名载入；0.8.9.44 起 `BLOCK` 表里多一条 `/memfd:dalvik-jit-code-cache`，把模块那三行映射（r-xp/r--p/rw-p，同一个 inode）从 maps/smaps 里一并去掉
- `readlink`/`readlinkat` 除输入路径外还看**返回值**：`/proc/self/fd/<n>`、`/proc/self/map_files/<range>` 这类入口本身没有可拦的关键字，泄漏全在目标路径上
- `dl_iterate_phdr` 的过滤除按名字，还按加载基址跳过模块自己——libmapshide 是从 memfd 载的，`dlpi_name` 就是当初 `dlopen` 的参数 `/proc/self/fd/<n>`，名字过滤认不出来
- 用 seccomp 对模块自身文本段里的裸 svc 做 TRAP 兜底，覆盖 openat、openat2、faccessat、faccessat2、newfstatat、statx、readlinkat、getdents64
- 装完后自己再读一遍 maps、tcp、environ，把 `leak_*` 与 `loop_ok` 落盘自证

memfd 名字为什么这么取：ART 自己的两份 JIT 缓存是 `/memfd:jit-cache` 与 `/memfd:jit-zygote-cache`，`dalvik-jit-code-cache` 只作为它们的 `[anon_shmem:...]` 名字出现，带 `/memfd:` 前缀的从来没有。所以模块用 `dalvik-jit-code-cache` 当 memfd 名（早先用 `jit-cache` 会与 ART 撞成同进程两个同名不同 inode，那正是"加载器藏在 memfd"的判据），再用 `/memfd:dalvik-jit-code-cache` 这条 `BLOCK` 把它的三行映射从检测库的视野里去掉，ART 自己的行不受影响。

0.8.9.39 起 token 扫描按「符号集合」而不是单个符号接管。libfekit 同时 import `strstr`、`strcasestr`、`memmem`，此前只接管了 `strstr`，而且只在 needle 恰好等于黑名单里的某个词时才返回 NULL：`strstr(maps_line, "com.topjohnwu.magisk-1.2")` 这类更长的 needle 会照常命中。现在三个入口共用一条规则——**needle 里只要出现 `BLOCK` 表中任一条（子串、大小写不敏感），就返回未找到**，判定集与 `path_denied` 完全一致。实测主进程补丁数 63 → 65，MSF 40 → 42。

检测库自己扫字符串的入口不止这三个。libturingxq 还 `regcomp`/`regexec` 一批 POSIX ERE，用在进程名、线程名与路径上：

```text
^/system/xbin/ku[.]sud$   ^daemonsu:   ^kworker/[1-9][0-9]*:[0-9]+$
^kr_worker/…  ^km_worker/…  ^tworker/…  ^tu_worker/…  ^tq_worker/…
^permmgrd$   ^360sguard$   ^/data/data/[[:alnum:]_.-]+/   ^/system/bin/[.]   ^[.]
```

这些正则不走 libc 字符串函数，模块的 `strstr` 家族接管不到。目前靠「别撞上」：模块自己起的线程名是 `pool-N-thread-M`（JVM 默认风格），路径不带 `sator-`/`magisk`/`kworker` 之类字样，所以没有一条能匹配。**改动线程名、临时文件名或模块目录名时要照这份清单核一遍**。

线程名是外部可见的，`/proc/<pid>/task/*/comm` 直接读得到。0.8.9.39 里自检到两处带模块名的线程（`satori-self-send`、`satori-channel-unmute`），0.8.9.40 改成 `pool-8-thread-N` 与 `pool-9-thread-1`；QQ 自己也有若干 `pool-N-thread-M`，这种名字不显眼。**新起线程时不要用模块名**，要能过 `scripts/qq-satori-exposure-audit.sh` 的 `suspicious_thread_names`（它匹配 satori/xposed/vector/zygisk/mapshide）。

logcat 是接受的暴露：Java 侧 `L.e` 与 native 的 `Q.Maps` 用 `Q.` 前缀的标签，消息里会提到 patch 计数与 seccomp。QQ 自己进程写下的日志，自己的进程（以及 root）读得到，拦不掉，而排障要用它；详细数字另外落盘到只 root 可读的 `qk_env_maps_*.json`。同理，常驻通知的渠道名与标题带「Satori」是给人看的，属于必要暴露。

清单分两份：`AndroidManifest.xml` 带 Xposed 元数据用于注册，`AndroidManifest.stealth.xml` 不带，启用后覆盖安装 stealth 版本。按安装包元数据里有没有 `xposed*` 键判定模块的工具，看到的是 stealth 版本。Duck Detector 的 LSPosed 卡片就是按这条查的：同一台机器上它扫出了便签模块与「雹」的 `xposedmodule` 元数据，而已覆盖安装 stealth 变体的本模块不在那张清单里。

真机实测（16070，0.8.9.39）：主进程 libfekit 的 GOT 逐槽核对全部指向 libmapshide 的包装（`dlsym`/`open`/`fopen`/`getenv`/`readdir`/`freopen`/`strcasestr`/`memmem` 等），主进程 65 个 slot、MSF 42 个，maps、tcp、environ 泄漏 0，`loop_ok=1`。

### 模块自己在盘上留下的东西

过检测不只是拦读取，模块自己写下的文件同样是暴露面。0.8.9.39 收了三处：

| 东西 | 原来在哪 | 现在在哪 | 为什么 |
| --- | --- | --- | --- |
| `qk_env_*.json` 自检 | `/storage/emulated/0/Android/data/com.tencent.mobileqq/files` | `/data/data/com.tencent.mobileqq/files`（0600） | 外部存储的 `Android/data` 有绕过存储沙箱的枚举手法，而这份文件的字段名直接写着模块做了什么。升级后首次写盘会把旧位置的同名文件删掉 |
| 看守日志与 pid | `/data/local/tmp`（0771，libfekit 二进制里带着这个路径字符串） | `/data/adb/satori-qq/`（0600，普通应用进不去） | 文件名与内容能反推模块 |
| `satori-last-send.txt`、`satori-history.txt` | 每次发消息、每次查历史都重写 | 只在 `verbose_logs=true` 时写（0.8.9.46 起并移到 `/data/data/com.tencent.mobileqq/files/`） | 逐次 I/O 与残留；诊断信息在 logcat 的 `Q.Kernel` 里仍然有 |
| `qk_kick.log` 踢线记录（0.8.9.44 新增，0.10.0 补字段） | — | `/data/data/com.tencent.mobileqq/files/qk_kick.log`（0600，超过 64KB 只留尾部 32KB） | 服务端踢线原文（入口、reason、`kickType`、`sigKick`、`seqno`、`sigLen`、`sameDevice`、标题、正文、`up=<秒>`）对排障有用，但要把"刚被踢过"这件事留给重启后的看守看，所以落盘而不是只在内存里。`up=` 是这次登录活了多久，用来分「周期性（票据/会话寿命）」与「事件驱动（行为打分）」 |
| `qk_guard.log` 善后动作（0.8.9.45/46） | — | 同上目录（0600，同口径截断） | 被拦下的登出、被顶回去的摘账号、自愈修回来的东西。**不与 `qk_kick.log` 合并**：那份是看守的重启判据 |
| `qk_sso.log` 模块自己 SSO 请求的失败（0.8.9.46 新增） | — | 同上目录（0600，同口径截断） | 用来分开「环境检测」与「接口把会话打废」两种踢线成因，要与 `qk_kick.log` 对时间 |

这四个 `qk_*` 名字对检测库不可见：`getdents64` / `readdir` 的条目过滤按 `qk_` 前缀去掉（见上「模块怎么挡」）。`satori-last-send.txt` / `satori-history.txt` 走的是通用黑名单（名字里有 `satori`），`verbose_logs` 关着时本来也不会生成。

配置文件 `satori-qq.json` 仍留在外部目录：它要能被人直接编辑，这是必要暴露；换到只有 root 能改的路径，维护成本高过收益。

## 与公开检测器逐项对照

拿 [Duck Detector](https://github.com/eltavine/Duck-Detector-Refactoring) 的源码逐项过了一遍，只挑**在目标进程内部成立**的检查（也就是 QQ 自己可以照抄的那部分），看模块挡不挡得住。

模块挡得住的（都是「靠读文本、认名字」这一类）：

| 检测方做的事 | 模块的对策 |
| --- | --- |
| `maps`/`heap` 里找 lsposed、libxposed、xposedbridge、lsplant、lspatch 等关键字 | 模块的 `.so` 不带自己的路径，Java 侧类名不进 maps；`BLOCK` 表覆盖这些词，检测库读到的 maps 行被丢掉 |
| 认模块自己的路径或库名（`libmapshide.so`、`/data/adb/...`、模块包名） | GOT 接管 `open`/`read`/`readlink`/`getdents64`/`dl_iterate_phdr`，命中即 `ENOENT` 或从列表里去掉 |
| `TracerPid`、`/proc/*/task/*/comm` 里的 zygisk/magisk/riru | 模块线程名是 `pool-N-thread-M`；`status` 里的 `Seccomp_filters`/`NoNewPrivs` 被改写 |
| 环境变量 `TMP_PATH` 带 `/data/adb` + zygisk | `scrub_environ` 把命中黑名单的变量从本进程环境里删掉 |
| 内核关键字（`kallsyms`/`modules`/`uname` 里的 ksu_/apatch/magisk） | 本机内核没这些字样；即便有，命中 `BLOCK` 的行也会被过滤 |
| root 属性残留、`/data/local/tmp` 里的临时提权产物 | `prop_denied` 直接吞掉这些读；看守日志已迁出 `/data/local/tmp` |
| 属性四源交叉（reflection / `getprop` 子进程 / `System.getProperty` / native `__system_property_get`） | 只在配置了假值时改写，且 Java 与 native 两条路径给同一个值 |
| 可执行映射里出现第二个 `jit-cache` memfd（加载器藏在 memfd 的判据） | 模块的 memfd 改名为 `dalvik-jit-code-cache`，ART 自己的 `jit-cache` 仍是唯一一个 |
| 用 `dladdr` 反查函数地址属于哪个模块 | `my_dladdr` 把模块自己的名字换成 `[anon:dalvik-jit-code-cache]` |
| 数 `/proc/<pid>/mem` 里的 dalvik 堆窗口找关键字 | 0.8.9.39 起 `/proc/*/mem`、`/proc/*/pagemap`、`/proc/kcore` 直接 `ENOENT`（偏移读没法行过滤） |
| 枚举 `/proc/self/fd`、`/proc/self/map_files` 逐个 `readlink`，看有没有从 memfd 或模块 APK 载进来的东西 | `readlink`/`readlinkat` 的返回值命中 `BLOCK` 就回 `ENOENT`；`fdinfo`（`name:` 行）与 `numa_maps`（`file=` 行）这两条等价的文本路走同一张表 |
| 读 `/proc/self/maps` 数 `/memfd:` 映射，或把 `dl_iterate_phdr` 与 maps 的条目对上 | 模块那三行带 `/memfd:dalvik-jit-code-cache` 的映射被 `BLOCK` 滤掉；`dl_iterate_phdr` 按加载基址跳过模块自己（它的 `dlpi_name` 是 `/proc/self/fd/<n>`，名字里没有关键字） |
| 在**自己进程内**列举 app 私有目录（`getFilesDir().listFiles()`、`readdir(/data/data/<pkg>/files)`）找模块留下的文件 | `getdents64` / `readdir` 的条目名走同一张表：`qk_` 前缀（模块自检、踢线记录、善后记录、SSO 失败记录）与含 `satori` 的名字都不列出 |

挡不住的，以及为什么：

| 检测方做的事 | 为什么挡不住 |
| --- | --- |
| 自己装 seccomp TRAP 探钩子、`setresuid` 的 SIGKILL 语义、`/dev/pts` 的 uid 与 xattr、KSU supercall 的 ioctl 返回值 | 都是内核侧语义的结果，不是文本；模块只能保证自己不去伪造 |
| 用裸 syscall 或多后端（libc / syscall / asm）交叉读同一个东西 | 裸 svc 不过 GOT，多后端一对比就露出被改写的那一条 |
| 直接扫描自己进程的堆或栈内存找 Xposed 类名 | Xposed 模块必须引用 `de.robv.android.xposed.**`，类名一定在堆里；读内存不是读文件，过滤器管不到 |
| TEE / attestation / bootloader 属性 | 见下面「挡不住的部分」，属设备层 |
| 服务端按行为与设备指纹打分 | 客户端拦得再干净也不影响这一层 |

最后一条里的「堆关键字扫描」值得单独说：本模块在 QQ 进程里是 Xposed 模块，`XposedHelpers`、`XposedBridge` 这些类名天然存在于堆中，任何在**自己进程内**扫内存的检测都能找到。这不是实现缺陷，是这种模块形态的下限。

## 诊断口径

自检文件在 `/data/data/com.tencent.mobileqq/files/qk_env_*.json`（0.8.9.39 之前写在外部 `Android/data`，已迁走；root 才能读，模块自己读自己的）。

`envProcessKey` 把除 `:MSF` 之外的进程都记成 `main`。`:qzone`、`:gameservice` 这类子进程与主进程共用同一份 `qk_env_*.json`，后启动的会覆盖先写的，`/healthz` 里的 `maps.patched` 不一定来自主进程。

对齐办法：`qk_env_maps_main.json` 从 0.8.9.37 起也带 `pid` 字段，`/healthz` 与 `/v1/internal/status` 读到 pid 不等于自己时会补一个 `owner` 字段说明这份数据是别的进程写的（例如 `pid 23905 (not this process)`）。没有 `owner` 才是本进程的真实数字。`qk_env_main.json` 一直有 `pid`，可与 `ps -A | grep mobileqq` 里的主进程比对。

实测过一次误判：文件里是 `patched: 21`、`dlsym: 0`、`loop_ok: 0`，看着像 native 层退化，实际是 `:qzone` 写的；主进程的 libfekit GOT 用 `/proc/<pid>/mem` 逐槽读出来是补全的。核实本进程的真实状态别只信这个文件。

外部的 `scripts/qq-satori-exposure-audit.sh` 取主进程 pid 的 `/proc/<pid>/maps`，不受这份文件影响。

**排障时别把 force-stop 当免费手段**：每强停再拉起一次，QQ 都要重新握手、重新上报设备信息、重新做一次登录（`qk_guard.log` 里能看到 `auto_login_kept`），而「服务端认为同一账号出现了第二个登录实例」正是 `KKICKBYMULTIINST` 那类强下线的语义。装完新版本重启一次是必要的，除此之外的反复重启没有收益。看守只在两条判据上重启 QQ：`qk_kick.log` 增行，或连续多轮离线。

## 挡不住的部分

这些不在模块里做。列出来是为了知道边界。

- Bootloader 解锁。本机 `ro.boot.verifiedbootstate` 已经是 `green`、`ro.boot.flash.locked` 是 `1`——这两个值由 root 方案那一侧的模块改（设备层，不在本模块里）。QQ 自己的库只读 `ro.bootloader` 与 `ro.boot.hardware`，不读这两个值；本机这两个是 `unknown` / `qcom`，正常。Duck Detector 之类会读。要在设备层处理，用带 bootloader 伪造的 root 方案。
- SELinux。Turing 读 `/sys/fs/selinux/enforce`。模块的 native 过滤只覆盖 `/proc`，不覆盖 `/sys`。本机是 enforcing，正常。查看：`getprop ro.boot.selinux; cat /sys/fs/selinux/enforce`。
- 内核与挂载命名空间。Magisk、KernelSU、APatch 的挂载点由内核层暴露。模块只在检测库进程内过滤 maps 与 mountinfo，检测方换一条模块没接管的通道，或直接读内核，就绕开了。对比：在进程内和进程外各读一次 `/proc/self/mountinfo`。
- ArtMethod 完整性。`libfekit.so` 带 `parse_libart.cpp` 与整套 `art::CheckJNI` 符号，可以对比运行时方法入口与磁盘上的 `libart.so`。Xposed 与 LSPlant 的 ArtMethod 改写不在本模块覆盖范围。核实：`strings -a libfekit.so | grep -E 'CheckJNI|parse_libart'`。
- 服务端风控。客户端拦得再干净，腾讯仍按历史行为、设备指纹变化与网络环境打分。2026-09-15 那次排查把客户端能看的都看了一遍（见上「设备侧现状」），没有异常项，所以这两次踢线（`kickType=0` + `sameDevice=0` + 普通强踢）更可能是服务端侧的判断，而不是本机暴露了什么。
- 进程内内存关键字扫描。检测方读自己进程的堆/栈（`memchr` 扫一段内存），模块引用的 Xposed 类名就在里面；这条路不经过文件，GOT 与 `/proc` 过滤都用不上。`/proc/*/mem` 那条读法已经堵掉，直接扫内存堵不掉。
- 多后端交叉校验。同一个事实用 libc、裸 syscall、汇编三种方式各读一次再比对，模块只改得了其中 libc 那条。libfekit 现在只用 libc，一旦它照着这个思路改，`/proc` 文本过滤的收益会明显下降。

## 复现审计

反编译检测库：

```sh
LIB=$(su -c "ls -d /data/app/~~*/com.tencent.mobileqq-*/lib/arm64")
su -c "cat $LIB/libfekit.so" > /tmp/libfekit.so
llvm-nm -D /tmp/libfekit.so | grep ' U '
strings -a /tmp/libfekit.so | grep -aE '^/(proc|sys)|magisk|lsposed|zygisk|superuser'
```

反编译 Java 检测类：

```sh
~/tools/jadx/bin/jadx --single-class com.tencent.mobileqq.qsec.qsecurity.QSec \
  -d ~/tmpqq/qsecout ~/tmpqq/dex/classes2.dex
~/tools/jadx/bin/jadx --single-class com.tencent.mobileqq.qsec.qsecurity.utils.SocketStatus \
  -d ~/tmpqq/qsecout ~/tmpqq/dex/classes3.dex
```

采集暴露面快照并对比：

```sh
su -c "sh scripts/qq-satori-exposure-audit.sh snapshot post-<版本>"
su -c "sh scripts/qq-satori-exposure-audit.sh latest"
su -c "sh scripts/qq-satori-exposure-audit.sh diff 旧快照 新快照"
```

离线检查：

```sh
./test.sh
```

`test.sh` 跑 JVM 单测后，用 clang 编译 `tests/mapshide-filter-test.c` 并执行，校验黑名单、`/proc` 路径分类、属性改写与风险载荷匹配。该测试用 aarch64 的 `svc` 指令，只能在设备上跑。
