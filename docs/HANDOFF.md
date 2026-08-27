# onebot-qq — 交接文档 (HANDOFF)

> 目标读者：**下一个没有任何上下文记忆的开发者/AI**。读完这一篇 + `ARCHITECTURE.md` +
> `ANTIDETECT.md` + `ROADMAP.md`，你就能无缝接手。所有"为什么"都写在这里。

## 这是什么
把本机 **QQ 9.3.50（NT 架构，Android）** 封装成 **OneBot 11 正向 WebSocket 实现端**，
作为 **vector / LSPosed** 的 Xposed 模块运行在 QQ 主进程内，给
`/data/media/0/dev/ayjx`（Rust 写的 OneBot 机器人框架）使用。

参考了 OpenShamrock 的 *hook 思路*，但**所有 QQNT 内核类名/方法签名都是我亲自反编译本机这台
QQ 9.3.50 得到的实测值**（OpenShamrock 已于 2024-08 归档且从 1.1.0 起弃用 OneBot 11 转 Kritor，
不能直接用）。

## 运行环境（关键，先读）
- 这台是 **rooted ColorOS 手机**，Claude Code 跑在 **Termux PRoot-Distro (Ubuntu)** 里。
- PRoot 用 `--bind=/apex --bind=/system --bind=/data` 启动，所以 **Android bionic 二进制能在 PRoot 里直接跑**：
  `/system/bin/{pm,am,monkey,logcat,dumpsys,sh,getprop}`、`/data/adb/ksud`、vector 的 cli 都能用。
  只有 `su` 不行（loader 依赖不解析），`adb` 没装。
- Root 框架：**KernelSU 系** + **Zygisk Next** + **vector**（JingMatrix 的 Xposed 框架，
  module id `zygisk_vector`，LSPosed-API 兼容，配置库在 `/data/adb/lspd/config/modules_config.db`）。
- **文件系统坑**：`/sdcard`(`/storage/emulated/0`) 是 FUSE，无符号链接/不可执行/大小写不敏感，
  **不能在里面构建**。真实后端是 `/data/media/0`（f2fs，完整 POSIX）。本项目放
  `/data/media/0/dev/onebot-qq`（= `/sdcard/Dev/onebot-qq`）。只有 root 能进 `/data/media/0`。
  两个视图**不一致**：通过 `/data/media/0` 写的文件，`/sdcard` 视图可能读到旧内容——别交叉读。

## 工具链（无 Android SDK 也能出 APK）
`build.sh` 已封装。手动等价：
- `javac` (Termux JDK 21) 编 `src/` + `stubs/`，`-classpath android.jar`（**不要**用 `-bootclasspath android.jar`，
  否则 lambda 编不了，缺 `LambdaMetafactory`）。
- `d8`：用 `libs/r8.jar`（Google Maven 的 r8，含 `com.android.tools.r8.D8`）：
  `java -cp libs/r8.jar com.android.tools.r8.D8 --release --min-api 26 --lib android.jar ...`
- **必须把 `stubs/de/robv/**`（Xposed API 桩类）从 dex 里排除**（build.sh 用 `grep -v /de/robv/` 过滤 d8 输入）。
  否则 vector 报 `Xposed API classes are compiled into the module's APK` 拒绝加载。
- `aapt package -M AndroidManifest.xml -I /system/framework/framework-res.apk -A assets` → `aapt add classes.dex`
  → `zipalign` → `apksigner`（keystore 自动生成在 `build/`，已 gitignore）。
- 依赖文件位置：
  - `android.jar` (API 35)：`/data/data/com.termux/files/home/android/platform/android-35/android.jar`
  - `aapt/aapt2/zipalign`：`/data/data/com.termux/files/home/android/android-sdk-tools/build-tools/`
  - `r8.jar`：本仓库 `libs/r8.jar`（gitignore，**克隆后要重新下**：见 build.sh 顶部注释，从
    `https://maven.google.com/com/android/tools/r8/8.9.35/r8-8.9.35.jar` 下）。

## 一键构建 + 部署 + 验证
```bash
cd /data/media/0/dev/onebot-qq && bash build.sh          # -> build/OneBotQQ.apk
cp build/OneBotQQ.apk /data/local/tmp/OneBotQQ.apk       # pm install 读不了 /sdcard
pm install -r -d /data/local/tmp/OneBotQQ.apk
# 启用模块 + 把 QQ 加入作用域（免重启，直接推给运行中的 vectord）：
sh /data/adb/modules/zygisk_vector/cli modules enable com.onebot.qq
sh /data/adb/modules/zygisk_vector/cli scope add com.onebot.qq com.tencent.mobileqq/0
# 冷启动 QQ 让模块加载：
logcat -c; am force-stop com.tencent.mobileqq; sleep 1; monkey -p com.tencent.mobileqq 1
sleep 12; logcat -d | grep OneBotQQ            # 看 "listening" / "Registered" / "AntiDetect"
cat /proc/net/tcp6 | grep 0BB9                 # 3001 端口在听
```
> vector 的 cli **能直接改运行中的 daemon 缓存，不用重启**；但**手动改
> `modules_config.db` 是不生效的**（vectord 只在开机重建 ConfigCache），要么用 cli，要么重启。

## ayjx 那边（消费端）
- ayjx 是 **OneBot 11 正向 WS 客户端**：它主动连 `ws://127.0.0.1:3001`，发 `Authorization: Bearer <token>`，
  用 `echo` 字段匹配响应。PRoot 与 QQ 共享网络命名空间，`127.0.0.1` 直通。
- 配置 `/data/media/0/dev/ayjx/config.toml`：onebot bot `enabled=true`, `url=ws://127.0.0.1:3001`,
  `access_token` **必须非空**（ayjx 空 token 会跳过连接！）。模块端 token 为空=不鉴权，会接受任意 client；
  要真鉴权就在模块配置里设一样的 token。
- 跑：`cd /data/media/0/dev/ayjx && ./target/debug/ayjx`（首次 `cargo build`，~5 分钟）。

## ayjx 实际会调的 OneBot 动作（实现优先级看这个）
`get_login_info`、`send_msg`/`send_group_msg`/`send_private_msg`、`get_msg`、`delete_msg`、
`get_forward_msg`、`get_group_member_info`、`get_group_list`、`send_like`、
`set_group_special_title`、`set_msg_emoji_like`、`upload_group_file`/`upload_private_file`。

## 现在完成到哪了（截至 2026-08-27）
**已实现 + 验证：**
- 正向 WS + Bearer 鉴权 + 心跳；`get_login_info`（真实 uin+昵称）
- 收群/私聊消息→事件（text/at/face/image/reply）
- 发送 text/at/face/reply/**image**（真实群实测：发+撤回 retcode 0；图片自动上传）
- `delete_msg`(撤回)、`get_msg`
- `get_group_list`(100 群)、`get_group_member_info`/`get_group_member_list`
- `set_msg_emoji_like`
- uin→uid 解析（`IKernelProfileService.getUidByUin`，任意好友私聊）
- **AntiDetect**（best-effort 反检测，见 ANTIDETECT.md）

**已实现，发包链路已真机验证：**
- `packet/PacketSvc.java`：用 QQNT 自带的 `IDependsAdapter.onSendSSORequest` → `KernelServlet` → MSF，
  显式传正确的十六进制 serviceCmd/OIDB 外层；在 `IQQNTWrapperSession$CppProxy.onSendSSOReply` 按
  `requestId` 收包。QQ 继续完成 SSO framing 和 QSec 签名，不再手工调用 QSign。
- `set_group_special_title`：0x8FC_2 body 已接入 OneBot 动作。错误路由曾返回 236 `cmd not found`；
  修复后在旧群（账号为 member）返回业务码 1013，在内部测试群 `675983807`（账号为 owner）把本人空头衔
  原值写回，真机返回 OneBot `status=ok, retcode=0`，成功分支已验证且没有可见改动。

**新增（2026-08-27，真机验证）：**
- **合并转发** `send_group_forward_msg`/`send_private_forward_msg`/`send_forward_msg`：走
  `packet/LongMsg.java` 拼 im_msg_body 假节点 → gzip → `PacketSvc.sendSso("trpc.group.long_msg_interface.MsgService.SsoSendLongMsg", ...)`
  拿 resId → 组 `com.tencent.multimsg` LightApp 卡片 → 复用现有 json/ark 发送。测试群 `675983807`
  发+撤回 retcode 0。v1 支持**文本节点**（图片/at 节点内容需各自补 Elem 编码）。
- **语音/文件发送**（真机 retcode 0）：`record`(silk/amr)、`upload_group_file`/`upload_private_file` + `file` 段。走图片同款 richmedia auto-upload（`RichMediaFilePathInfo` 首参=elementType，PIC2/FILE3/PTT4/VIDEO5），QQ sendMsg 自动上传。语音不转码，输入须已是 silk/amr。
- `get_forward_msg`（`SsoRecvLongMsg`）：真机往返验证——上传转发拿 resId → 下载解回节点(昵称/文本/时间)。伪造节点非本人 uin 被服务器盖占位值(自身真 uin 精确往返)。
- `send_like`（0x7E5_104）：协议实现正确，但本机/本号被服务器 `oidb=319 rule type not match appid`
  策略拦截（非代码问题；换干净号或政策放开即可用）。

**未做（里程碑 3，见 ROADMAP.md）：** 
**视频**发送(需缩略图+分辨率+时长)、notice 事件（撤回/戳一戳/进退群/禁言）。
封包传输层和 0x8FC_2 成功分支均已打通；后续封包测试固定使用内部测试群 `675983807`。

## 重要坑清单
- Xposed 桩类不能进 dex（见上）。
- `File.createTempFile` 前缀必须 ≥3 字符。
- **「私聊自己」不是有效投递目标**：文本 send 返回 retcode 0 但不真投递，图片直接 `rich media transfer failed`。
  测发送要发**真实群/好友**。
- QQ 被注入会触发反篡改→掉线/重登验证（见 ANTIDETECT.md）。用户已接受主账号风险。
- `api.*` 服务类是**混淆**的（`getMsgService`→`api.ac`）；一律走 `nativeinterface`/`kernelpublic.nativeinterface`
  的稳定 JNI 名 + `IQQNTWrapperSession` 拿服务。
- 反编译产物在构建会话的 scratchpad `qqdex/full/sources/`（临时，丢了就用 jadx 重生成：
  `jadx -d full --no-res --no-debug-info classes.dex classes2.dex`，QQ base.apk 从
  `pm path com.tencent.mobileqq` 拿）。

## 参考资料
- **`/data/media/0/dev/QQ.hap`**（352MB）：QQ 鸿蒙版源码泄露包。ArkTS + native，可作为后续 API/协议
  的**参考**（尤其协议字段、OIDB 命令字）。注意它是 HarmonyOS 包，Java 层不通用，但业务逻辑/协议可借鉴。
  解包：`unzip QQ.hap` 看 `ets/`(ArkTS 字节码) 与 `libs/`(native)。
- OpenShamrock（已归档）：hook 思路参考，`Evanfeng709/OpenShamrock` 是可用镜像。
- 本仓库 `ARCHITECTURE.md`：完整 QQNT 内核类/方法映射表（收发消息、群、富媒体）。
