# 反检测 (掉线绕过) 说明 — 请务必读完再判断预期

## 2026-08-28：双主线重新开放后的 0.5.0 进展

旧实验说明保留用于避免重复踩坑，但“不再继续研究”不再是项目结论。当前策略是：低风险指纹持续收敛、
Java/QSec 入口做单变量 A/B、native/framework 路线用证据逐层推进，同时保留 watchdog 恢复。

- 默认静默日志，tag 改为中性 `Q.Kernel`；XposedBridge verbose 日志仅显式开启。
- `getXpsInfo` 从 after-hook 改为 replacement：原实现中的 `T.ad(...)` 不再先执行，避免采集副作用。
- `QSec.reportLog` 默认返回 0；`execTasks` 由 `block_qsec_tasks` 独立控制，默认关闭。
- `getSign/getSignEntry/getEstInfo/Dandelion` 仍保持原样，避免把登录签名变量混进第一轮 A/B。
- 新增 root `qq-onebot-exposure-audit.sh`，watchdog 状态切换自动记录 maps、线程与日志指纹。
- reportLog 阻断启用后的即时真机结果：登录、WS、图片内核下载、文件获取均正常；
  maps `vector=4, zygisk=6, fekit=3, onebot/xposed/lspd/mapshide=0`，可疑线程与旧日志指纹为 0。

下一步是 24h/72h 数据、`execTasks` 独立短窗口实验，以及跨 namespace 精确观测 libfekit 的读取路径。

## 症状
QQ 被注入后，报"设备环境不安全"，隔一阵把账号踢下线，要重新登录 + 验证。很烦。

## 根因（诚实版）
QQ NT 的安全核心是 **native 的 `libfekit.so`**（腾讯 QSec/fekit SDK）。Java 层的
`QSec.getSign / doSomething / getEstInfo`、`Dandelion.energy`、`QsecEst.d` **全是 JNI**，
真正的环境扫描（读 `/proc/self/maps` 找注入的 .so、查 hook 框架、反调试）在 native 里做，
把"设备风险"信号塞进登录/心跳签名上报给服务器，**服务器**据此触发踢下线+验证。

**结论：纯 Java 的 Xposed 模块无法阻止 native 扫描。** 这是能力边界，别被任何"一键绕过"忽悠。

## 本模块做了什么（best-effort，`qq/AntiDetect.java`，配置 `anti_detect` 默认开）
中和 QQ **Java 层**的检测缝隙（这些能安全 hook，且不影响登录）：
- `QSec.detectMethod(cls, method)` → 恒 `false`：QQ 用它反射探测 hook 框架类（如
  `de.robv.android.xposed.XposedBridge.log` 是否存在），返回 false 让它探不到。
- `QSec.getXpsInfo()` → 返回空 `byte[]`：`Xps`=Xposed，这是 Xposed 信息采集器，清空它。

**绝不 hook** `getSign/getSignEntry/getEstInfo/doSomething/energy`——那是登录签名，动了直接登不上。

已在设备验证这两个 hook 成功安装（logcat：`AntiDetect: neutralised QSec.detectMethod / getXpsInfo`）。
**能减少 Java 层暴露面，但不保证消灭 native 触发的掉线。** 请观察一段时间看频率是否下降。

## 想要更彻底？三条路（按性价比）
1. **框架级隐藏（最有效，推荐先试）**：把 vector 注入的 .so 从 QQ 进程的 `/proc/self/maps` 里藏掉，
   native 扫描就找不到。这属于 **vector 框架**能力，不是本模块能写的。查 vector 是否有 hide/隐身：
   `sh /data/adb/modules/zygisk_vector/cli config get <key>` / 看 vector manager。
   另外确保**只有本模块**把 QQ 加了作用域（别让别的模块也注入 QQ，否则多一份暴露）。
2. **native maps 过滤 hook（治本但重）**：给本模块配一个 zygisk 伴生 native .so，
   inline-hook libc 的 `open/openat/read`（或 `fopen`），把读 `/proc/self/maps` 的结果里
   含 `vector/xposed/zygisk/lspd/riru` 的行滤掉，喂给 libfekit 一份"干净"的 maps。
   需要写 ARM64 native + Dobby/inline-hook，版本敏感，工程量大。参考现成"maps hider"实现思路。
3. **降低自身指纹（锦上添花）**：本模块的线程名叫 `onebot-*`、开了 3001 端口、建了实现 QQ 接口的
   动态 Proxy——这些不是主要检测项（主检测项是 Xposed 框架本身），但洁癖的话可改随机线程名、
   端口只绑 127.0.0.1。

## 设备侧现状（背景，来自本机既有配置）
这台已做了很多 root 隐藏：tricky_store keystore 证明、HMA、prop 伪装、zygisk denylist。
但那些针对的是**其它风控 App**；QQ 的掉线是 **Xposed 注入**被 QSec native 抓到，和上面那套是两码事。
KernelSU 的 umount-default / denylist 对"被注入的 QQ"无效（要注入就不能 denylist QQ）。

---

## Route 2 已实现：native maps 隐藏 (`maps_hide`, 默认关闭)
`native/mapshide.c` → 编译成 `lib/arm64-v8a/libmapshide.so`，由 `qq/MapsHide.java` 在 QQ 进程里
`System.load` + `install()`。原理：用 `dl_iterate_phdr` 遍历所有已加载 ELF，patch 它们 GOT 里的
`open`/`openat`，重定向到我们的实现——读 `/proc/self/maps` 时返回一份**过滤掉**
`vector/zygisk/xposed/lspd/riru/magisk/mapshide/onebot/data/adb` 行的 memfd。
构建：`build.sh` 里 termux clang `--target=aarch64-linux-android24` 直接编 bionic .so。

**⚠️ 诚实警告**：libfekit 是专业 native 反篡改，**很可能用裸系统调用 (svc #openat) 直接读 maps，
绕过 libc**——那样 GOT hook 拦不到，`maps_hide` 就无效。这是 best-effort，不保证。
真要拦裸 syscall，得做 seccomp/inline-hook syscall entry，工程量再上一个量级。

### 测试步骤（**先确保主号已在干净 QQ 上登录稳定**）
1. 写配置 `/sdcard/Android/data/com.tencent.mobileqq/files/onebot-qq.json`：`{"maps_hide": true, "token": "..."}`
2. 把 QQ 加回作用域：`sh /data/adb/modules/zygisk_vector/cli scope add com.onebot.qq com.tencent.mobileqq/0`
3. 冷启动 QQ：`am force-stop com.tencent.mobileqq; monkey -p com.tencent.mobileqq 1`
4. 看日志：`logcat -s OneBotQQ:* MapsHide:*`，确认 `MapsHide: loaded ... patched N GOT slots`（N>0）。
5. 观察 QQ 是否还掉线：
   - **不掉了** → GOT 级过滤够用，成了。
   - **还掉** → 基本可确认 libfekit 走裸 syscall；关掉 `maps_hide` + `scope rm` 停手，
     下一步只能上 syscall/inline hook（大工程，见上）。
> 出事随时一键停：`sh .../cli scope rm com.onebot.qq com.tencent.mobileqq/0` 让 QQ 立刻回到干净无注入。

### 2026-08-27 实测结论（重要，别再走这条弯路）
maps_hide 外科版（只 patch libfekit）在真机测了：**patched 0 slots，无效**。两个原因：
1. **linker 命名空间隔离**：我们的 `libmapshide.so` 经 `System.load` 加载在**模块 classloader 的
   命名空间**里，`dl_iterate_phdr` 只枚举本命名空间，**看不到 QQ app 命名空间里的 libfekit.so**
   （虽然 libfekit 确实在同进程、/proc/pid/maps 里能看到）。要 patch 它得改成：读真实
   `/proc/self/maps` 定位 libfekit 基址 → 直接解析其 ELF 内存 → mprotect+patch GOT（跨 ns 同地址空间可行）。
2. **就算 patch 到了，libfekit 是专业反篡改，极可能用裸 svc syscall 读 maps**，根本不走 libc GOT。
好消息：**外科版不会再卡死 QQ**（只碰检测库，ART 读真 maps）。坏消息：**GOT 路线基本走死**。
真要继续：只剩(a) 直接内存 patch libfekit + 赌它走 libc；(b) seccomp/inline-hook 拦裸 syscall（大工程）；
(c) **换思路：别藏，改成压制"踢下线"动作**——但"设备异常要人脸"是**服务器**签发票据控制的，
本地压制大概率让会话变成死号（能显示登录但发不出消息）。

### 当前可用状态（2026-08-27）
注入 + `anti_detect`(Java hook detectMethod/getXpsInfo) + maps_hide 关。实测 QQ **保持登录数分钟、
会话活(get_login_info 返回真实昵称)、未被踢**。是否长期稳定需观察数小时。**诚实底线：libfekit 的
native 检测无法保证绕过**；现实选择是"接受偶尔重登"或"换小号"。

## 历史阶段的一句话结论
当时 Java 层只做了 detectMethod/getXpsInfo，GOT maps_hide 也未奏效。该结论仅说明旧方案失败；当前继续
从 replacement 副作用、遥测入口、框架加载暴露和更精确的 native 观测推进，详见本文开头 0.5.0 更新。

## 2026-08-27 最终收敛审计

- vector 7 个启用模块中，QQ scope **只有 `com.onebot.qq`**，没有其它模块叠加注入。
- QQ maps 计数：`vector=4`、`zygisk=6`，`xposed/lspd/riru/onebot/mapshide=0`；vector 2.2 CLI/Manager
  没有 hide 配置。这 10 条 native 暴露是当前天花板。
- 安全收敛已做：WS 只绑 `127.0.0.1`；工作线程名不含 onebot/xposed/vector；maps_hide 保持关闭；
  仅保留 `detectMethod/getXpsInfo` 两个低风险 Java hook。
- 禁止项不变：不碰 QSec 签名 JNI、不做 ART/全局 maps 欺骗、不因“反检测”引入更高崩溃风险。
- 防掉线采用“降低表面指纹 + 量化观察 + 快速恢复”：真实 lifecycle/heartbeat、同进程重登、
  root watchdog、`set_restart` 均已主号真机验证。
