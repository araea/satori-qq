# 接手提示词 —— 新会话直接粘贴这一整篇（终极完整版）

你是 Claude Code，接手一个**进行中**的项目。作者可能中断一阵，这份是给"零记忆的未来的你"看的。
**动手前按顺序做**：(1) 完整读 `/data/media/0/dev/onebot-qq/docs/` 下 5 篇文档（下面列）；
(2) 跑 §8"确认运行态"看现状；(3) 读完 + §10 战略读完再问作者要继续哪块，**别急着改代码**。

---

## 0. 项目一句话
把本机 **QQ 9.3.50(NT 架构, Android)** 封装成 **OneBot 11 正向 WebSocket 实现端**，作为 **vector(LSPosed 兼容)** 的 **Java Xposed 模块**跑在 QQ 主进程里，给 `/data/media/0/dev/ayjx`(Rust OneBot bot) 用。QQNT 类名/签名都是反编译本机 QQ 9.3.50 实测的。

## 1. 位置 / 备份 / 文档
- 项目：`/data/media/0/dev/onebot-qq`（= `/sdcard/Dev/onebot-qq`；只有 root 能进 /data/media/0）
- GitHub 私有备份：`github.com/araea/onebot-qq`（gh 已登录 `araea`，git push 直接可用；本会话已推到 `2bb4b09`）
- 持久记忆：`qqnt-9350-onebot-nt-kernel.md`（本项目浓缩笔记，通常自动加载）
- **必读文档**（docs/，共 5 篇）：`HANDOFF.md`(环境/工具链/坑) `ARCHITECTURE.md`(代码结构+QQNT内核映射表) `ANTIDETECT.md`(反检测) `ROADMAP.md`(待办打法) `RESUME_PROMPT.md`(本文)
- **参考资料**（reference/）：`PACKETS.md`(OIDB 命令号+结构,抄自 NapCat/LagrangeGo) `qqhap-proto/`(QQ.hap 抽的 protobuf) `README.md`
- ayjx 框架：`/data/media/0/dev/ayjx`；QQ.hap(鸿蒙QQ泄露,协议参考)：`/data/media/0/dev/QQ.hap`

## 2. 战略全局：三条路（作者在权衡，务必懂；细节见 §10）
作者要"**纯手机、不用 PC**"。达成这个目标有三条路，本项目只是其一：
| 方案 | 平台 | 掉线问题 | 难点 | 轻重 |
|---|---|---|---|---|
| **本项目 Xposed 模块** | 安卓原生 QQ | ❌有(**范式固有,别再死磕**) | 反检测天花板在框架/native层 | 最轻 |
| NapCat + Linux QQ(Termux) | 桌面 QQ(Electron) | ✅无 | Chromium-under-PRoot 难搞+重 | 最重 |
| **Lagrange.OneBot(Termux)** | 独立协议客户端(.NET) | ✅无 | 配签名服务器 | 轻 |
- **诚实定位**：论功能/成熟/稳定，**NapCat 全面碾压本项目**。本项目唯一真实优势=**能在安卓手机上跑不用 PC**(NapCat/LLOneBot 只能桌面)。有常开 PC 的话桌面 NapCat 远比这稳。**沟通别把本项目吹过头。**
- **掉线是范式固有代价的新认知（本会话确立），已重写 §6 + §10，动手前务必读。**

## 3. 环境 & 构建部署（关键，别踩坑）
- 目标机是 **rooted ColorOS 手机**，root 框架 **KernelSU 系 + Zygisk Next + vector**（JingMatrix 的 Xposed 框架，module id `zygisk_vector`，LSPosed-API 兼容）。
- **⚠️ 权限现实（本会话实测，务必先确认）**：会话 shell 可能是**普通 app UID**（如 `u0_a499`，非 root PRoot）。若直接跑 `pm`/`am`/vector cli/进 `/data/media/0` 报 `Permission denied`，**全部包 `su -c '...'`**（KernelSU `su` 可用）。且：
  - `am`/`monkey` 用 **`/system/bin` 绝对路径**（Termux 自带 `am` shim 无 `force-stop`）。
  - build 在 `su -c` 下且要 `export HOME=/data/data/com.termux/files/home` + `export PATH=/data/data/com.termux/files/usr/bin:/system/bin:$PATH`。
  - `git`/`gh` 在普通 shell 的 `/sdcard/Dev/onebot-qq` 视图直接可用（无需 su）。
  - （若你的会话本就在 root PRoot 里、直接能跑，就忽略 `su -c`。）
- **构建**：`cd /data/media/0/dev/onebot-qq && bash build.sh` → `build/OneBotQQ.apk`（javac + libs/r8.jar 的 d8 + aapt，无需 Android SDK；还会用 termux clang 编 native libmapshide.so）。`libs/r8.jar` 丢了按 build.sh 顶部注释重下。
- **安装**：`cp build/OneBotQQ.apk /data/local/tmp/ && pm install -r -d /data/local/tmp/OneBotQQ.apk`（pm 读不了 /sdcard，必须先 cp 到 /data/local/tmp）。
- **启/停注入**（实时，免重启 daemon）：`sh /data/adb/modules/zygisk_vector/cli scope add|rm com.onebot.qq com.tencent.mobileqq/0`。手动改 `modules_config.db` **不生效**（要 cli 或重启）。
- **改代码要冷启动 QQ 才加载**：`logcat -c; /system/bin/am force-stop com.tencent.mobileqq; sleep 1; /system/bin/monkey -p com.tencent.mobileqq -c android.intent.category.LAUNCHER 1`，等 ~14s 注入。
- **Xposed 桩类 `stubs/de/robv/**` 必须排除出 dex**（build.sh 已做），否则 vector 拒载。
- 反编译取字段：`jadx --single-class com.tencent.qqnt.kernel.nativeinterface.<类名> -d out <QQ base.apk>`（单类也要加载整包，~2min，后台跑）。base.apk 路径 `pm path com.tencent.mobileqq`。**别信 NapCat TS 的字段类型**（见 §4 富媒体坑）。

## 4. 当前进度（截至 2026-08-27，本会话大幅推进）

**基础（早已实现 + 真机验证）**
正向 WS + Bearer 鉴权 + 心跳；`get_login_info`；收/发消息段 text/at/face/reply/**image**/**json(ark卡片)**/lightapp/mface/poke；`delete_msg`(撤回)、`get_msg`；`get_group_list`、`get_group_member_info`/`_list`、`get_group_info`；`set_group_kick`/`ban`/`whole_ban`/`card`/`admin`/`leave`；`set_msg_emoji_like`；uin→uid 解析；**AntiDetect**(Java hook)。

**封包子系统（`packet/`，真机打通）**
- `Pb.java`：零依赖 protobuf 编解码 + OIDB 外壳辅助。
- `PacketSvc.java`：走 QQNT 自带 `IDependsAdapter.onSendSSORequest` 发包、hook `CppProxy.onSendSSOReply` 按 requestId 收包（QQ 自己做 SSO framing + QSec 签名，**不手工 QSign**）。两条 API：
  - `sendOidb(cmd,sub,body[,isReserved])` —— 套 OIDB 外层，serviceCmd = `OidbSvcTrpcTcp.0x{HEX}_{sub}`。
  - `sendSso(serviceCmd,body)` —— **裸 trpc**（不套 OIDB），合并转发/取回用。
- `set_group_special_title`(0x8FC_2)：测试群原值写回 retcode 0。

**本会话新增（全部真机验证）**
- **合并转发发送** `send_group_forward_msg`/`send_private_forward_msg`/`send_forward_msg`：`packet/LongMsg.java` 拼 im_msg_body 假节点 → gzip → `sendSso(SsoSendLongMsg)` 拿 resId → 组 `com.tencent.multimsg` LightApp 卡片 → 复用现有 json/ark 发送。响应带 `res_id`。v1 文本节点。
- **合并转发取回** `get_forward_msg`：`sendSso(SsoRecvLongMsg)` → gunzip → 解节点。往返验证：昵称/文本/时间正确。**注**：伪造节点名字可控，但非本人 uin 被服务器盖占位值（自身真 uin 精确往返 = 编解码正确，是服务器反伪造）。
- **富媒体发送** 语音 `record`(silk/amr)、文件 `upload_group_file`/`upload_private_file`+`file` 段、视频 `video`：见 `qq/Media.java`。全部 retcode 0。
- **`send_like`**(0x7E5_104)：协议实现正确，但被服务器 `oidb=319 rule type not match appid` 策略拦——**非代码问题**，所有 source 都 319，是腾讯 appid 级封禁叠加主号风控。换干净号/政策放开即可用。

**富媒体发送的关键事实（`Media.java`，别踩坑）**
- 全部走**内核 auto-upload**：`getRichMediaFilePathForMobileQQSend(RichMediaFilePathInfo(...))` 拿目标路径 → `copyFile` → 建对应 Element → `sendMsg`，**QQ 自己 highway 上传，不用手写 highway**。
- `RichMediaFilePathInfo(elementType, subType, md5, fileName, downloadType, thumbSize, null, "", true)`，**首参 = elementType**：PIC=2 / FILE=3 / PTT=4 / VIDEO=5（`MsgConstant.KELEMTYPE*`）。
- **反编译实测：PttElement/FileElement/VideoElement 的 `fileSize` 都是 `long`**（NapCat TS 写的 string 是错的）；**VideoElement 无 `fileWidth`/`fileHeight` 字段**（TS 有，9.3.50 无，照写会炸）。
- 语音**不转码**：输入须已是 silk/amr。QQ 语音就是 silk，头是 `\x02#!SILK_V3`；时长靠数 silk 20ms 帧。
- 视频：`MediaMetadataRetriever` 提封面(getFrameAtTime→JPEG)/时长/分辨率；双路径 视频(5,2,dl1) + 缩略图(5,1,dl2)。

**未做 / 别做**
- **别做**：notice 事件（撤回/戳一戳/进退群/禁言通知）——**ayjx 不消费**（它的 recall 是 /撤回命令，不是通知）。
- **按需**：`get_stranger_info`/`get_friend_list`(BuddyService)、silk 转码（要让任意音频能发语音，需 native silk 编码器；现状要求输入已是 silk/amr）。
- **战略级**（见 §10）：韧性/重登层、换小号、C 轨 Lagrange——等作者定。

## 5. 封包子系统怎么继续
1. `PacketSvc` 已打通，不要退回 `onSendOidbRequest`：它会把 0x8FC 错拼成 `0x2300`，真机返回 236 `cmd not found`。正确路线 `onSendSSORequest` + `onSendSSOReply`。
2. 命令号/body 抄 **LagrangeDev/LagrangeGo**（`client/packets/pb/service/oidb/*.proto` + `client/packets/oidb/*.go`）或 NapCat；body 与安卓一致，只发送方式不同。见 `reference/PACKETS.md`。
3. 传输层复用：OIDB 类走 `sendOidb`，裸 trpc 服务（如 SsoSend/RecvLongMsg）走 `sendSso`。不要手工 QSign（QQ 会签，9.3.50 的 `QSec.getSign` 是 `(String,byte[])`）。
4. **appid 策略坑**：真机真 appid 反被服务器策略卡（send_like 319）。发新封包前先判断该命令是否**社交/资料/宠物类**（易被 319）；群管/消息类多数不卡。
5. **每步真机发包测试有风控风险**：测试固定发测试群 `675983807`（作者群主），消息型动作立即 `delete_msg` 撤回；非消息型尽量原值写回。

## 6. 反检测现状（读 ANTIDETECT.md 全文）—— 认知已重构
- **掉线不是本模块的锅，是"注入真机 QQ"这个范式的固有代价。** 作者实测：即便本模块不注入，只要有**别的 vector 模块**在全局注入，QQ 照样掉线。libfekit(QSec) 检测的是"本进程被 Xposed/zygisk 注入"这个**事实**，不是"onebot-qq 的指纹"。
- 因此：① **别再写/加 Java anti-detect**（`detectMethod`/`getXpsInfo` 已到头，再投入白费）；② **别再碰 maps_hide**（已证死路：linker 命名空间隔离 + libfekit 走裸 syscall）；③ **把掉线当既定事实，设计"围绕它"的架构**（韧性/重登/换小号），详见 §10。
- 仍成立的红线：**别 hook** `getSign/getSignEntry/getEstInfo/doSomething/energy`（登录+签名要用，动了直接登不上）。

## 7. ⚠️ 安全铁律
- 作者**主号**曾被风控判高危（一度连人脸都扫不了）。作者**明确接受主号风险**，但**任何可能触发掉线的操作前先说清楚**。
- **换小号**：任何注入实验优先用小号，主号别再拿来试注入（§10.5）。
- **一键回滚**（掉线/卡死立刻执行）：`su -c 'sh /data/adb/modules/zygisk_vector/cli scope rm com.onebot.qq com.tencent.mobileqq/0'` → QQ 回干净无注入。
- **别搞全局 native hook**（喂 ART 假 maps → QQ 卡死闪屏）。发送测试只发测试群 `675983807` 且立即撤回；别发陌生群；「私聊自己」不是有效投递目标（图片报 rich media transfer failed）。

## 8. 确认运行态（先跑这些；普通 app UID 记得包 `su -c`）
```bash
su -c '
export PATH=/system/bin:$PATH
cat /proc/net/tcp6|awk "{print \$2}"|grep -qi ":0BB9" && echo "注入中(3001在听)" || echo "未注入"
sh /data/adb/modules/zygisk_vector/cli scope ls com.onebot.qq          # QQ 是否在作用域
dumpsys activity activities|grep -i topResumedActivity|grep -i mobileqq  # Login=掉线,其它=登着
logcat -d -s OneBotQQ:* MapsHide:*|tail                                  # Registered/listening/AntiDetect
'
```
连通测试(Node，普通 shell 即可)：连 `ws://127.0.0.1:3001`，带 header `Authorization: Bearer onebot-qq-token`（token 在 `/sdcard/Android/data/com.tencent.mobileqq/files/onebot-qq.json`），发 `{"action":"get_login_info","echo":"x"}`；返回**真实昵称**=会话活。（服务端是手写 RFC6455，Node 无第三方库时自己拼帧即可。）

## 9. 下一步（等作者定）
里程碑 3 主线**已基本清空**：封包子系统 ✅、合并转发发送+取回 ✅、语音/文件/视频发送 ✅、群管动作 ✅；`send_like` 协议 ✅（被 319 拦）。
**后续别再靠直觉堆功能——先读 §10 全局战略。** 真正该问作者的是那几个决策点（§10.8），不是"下一个 API 做啥"。
若一定要继续 A 轨补功能（均非 appid 策略卡）：韧性/重登层（§10.5，最有价值）、silk 转码让任意音频能发语音、`get_stranger_info`/`get_friend_list` 等按需小动作。
**先读 5 篇文档 + reference/PACKETS.md + §10 + 确认运行态，再问作者。合作愉快 🤝**

## 10. 全局战略与后续架构方向（动手前必读）

> 触发这次重写的关键认知（作者亲述）：**掉线不是本模块的锅**。只要手机上有**任何** vector/Xposed 模块在全局注入，libfekit 就检测到"进程被注入"这个事实并踢下线——本模块注不注入、指纹干不干净都一样。这把过去"死磕反检测"的方向证伪了。

### 10.1 一个必须先接受的事实
- **掉线 = "注入真机 QQ" 这个范式的固有税**，不是可修的 bug。天花板在 **vector 框架 + libfekit native**，不在本模块的 Java 层。
- 直接推论：❌ 别再写 Java anti-detect；❌ 别再碰 maps_hide（已证死路）；✅ 把掉线当**已知常量**，设计"围绕它"的架构（韧性/重登/换小号）。
- 一句话：**这场仗（消灭掉线）在本模块层面打不赢，别打了。**

### 10.2 三条路的重新定位（带一个反直觉洞察）
| 路 | 本质 | 掉线 | 功能天花板 | 真实定位 |
|---|---|---|---|---|
| **A=本项目** 注入真机 QQ | Xposed 模块 | ❌固有,无解 | 真客户端功能，但**真 appid 反被服务器策略卡** | 纯手机 + 真客户端 + 最大控制 + **实验/好玩** |
| **B=NapCat+Linux QQ** | 桌面 QQ(Electron) | ✅无 | 最成熟最全 | 有常开 PC 时的最优；手机上太重 |
| **C=Lagrange.OneBot** | 独立协议端(.NET) | ✅无(不注入) | 靠社区补协议 | **纯手机 + 稳定生产**的务实赢家；代价=签名服务器 |

- **反直觉洞察（appid 悖论）**：本项目是"真身份"，却因真 appid 被服务器策略拦（`send_like` 全 source 都 319）。而 **Lagrange 用自己模拟的 appid，反而可能在白名单里、点赞照发**。→ "注入真 QQ = 功能最全" 这个直觉是**错的**：真身份既扛掉线税、又扛 appid 策略税。A 的价值在"真客户端体验 + 纯手机 + 可玩"，不在"功能最全"。

### 10.3 先回答：这个项目到底为谁、为什么（目标决定架构）
- **(a) 给 ayjx 一个能天天用的稳定手机 OneBot** → 选 **C（Lagrange）**，或 A + 小号 + 重登韧性。别拿 A 背"稳定生产"。
- **(b) 探索/学习 Android QQ 内核、追求真客户端保真度** → 留 **A**，它是唯一有"真 QQ"的路。
- **建议双轨并行**：**A = 实验/保真轨**（用小号常驻，掉了无所谓）；**C = 生产/稳定轨**（真给 ayjx 供 OneBot11）。**别让 A 去干 C 的活**。

### 10.4 架构解耦：让已有投入不白费
- **干净、可复用资产**（与"QQ 怎么绑"无关）：`net/WsServer`、`core/OneBotHub`、`core/MsgStore`、`qq/Convert`——一整套 OneBot11 协议面。
- **脆弱、随 QQ 更新碎**：`qq/QQClient`（内核 hook）、`packet/PacketSvc`（封包）。
- 建议把 OneBot 协议面做成**传输无关**（`OneBotHub` 只依赖一个 `Backend` 接口：发消息/收事件/查询）。这样 A 轨内核实现是一个 backend，将来 Lagrange 协议核也能架同一套协议面，不用重写。

### 10.5 韧性层：围绕掉线设计（A 轨最有价值的下一步）
- **掉线检测**：轮询登录 activity（`Login`=掉线）/ `session==null` / 3001 是否在听 → 判 bot online/offline。
- **对 ayjx 暴露生命周期**：OneBot `meta_event`（lifecycle + heartbeat.status.online），让 ayjx 知道 bot 掉了、别把请求打进黑洞。
- **自动恢复**：掉线后不崩，尝试 `monkey` 拉起 QQ / 引导扫码重登，恢复后自动重注册监听 + 重连事件流。
- **换小号**：主号已判高危，注入实验一律用小号（已升格进 §7）。

### 10.6 功能优先级：按"是否被 appid 策略卡"看
- ✅ **已做、不卡策略**：群管 OIDB（0x8FC_2 头衔、kick/ban/card/admin）、**合并转发发送+取回**、**语音/文件/视频发送**——本会话已全部真机验证。
- ⛔ **被策略卡、非代码能解**（搁置）：`send_like`(319)、宠物类 OIDB。等换干净号/政策放开。
- 🎯 **若继续 A 轨补功能**：韧性层（§10.5，最值）＞ silk 转码（任意音频发语音）＞ `get_stranger_info`/`get_friend_list` 等按需小动作。

### 10.7 维护性 / 长期视角
- **A 是与 QQ 版本更新赛跑的跑步机**：QQ 一升级，类名/签名/内核方法/字段可能碎（本会话已见 NapCat TS 字段类型与 9.3.50 实测不符）。需版本锁（记 base.apk 指纹）+ 反编译重生成流程（jadx `--single-class`）。这是 A 的**结构性成本**。
- **C 也是跑步机，但有社区维护**，单人 bus factor 更低 → 长期看 C 更省心，这也是把 C 定为生产轨的原因。
- 封包/内核映射文档（ARCHITECTURE + PACKETS + 本文）是真资产，别让它随会话丢。

### 10.8 留给作者的决策点（该问的是这些，不是"下个 API")
1. 要不要**真开 C 轨**（Termux 起 Lagrange.OneBot 做 ayjx 稳定通道），A 保留为纯手机保真实验轨？
2. A 是否**换小号常驻**？（主号继续试注入不划算。）
3. 要不要做 **OneBot 协议面传输无关解耦**（§10.4，为 A/C 共用铺路）？
4. A 轨若继续，先做**韧性/重登层**（§10.5）还是补具体功能？
