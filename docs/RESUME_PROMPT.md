# 接手提示词 —— 新会话直接粘贴这一整篇（终极完整版）

你是 Claude Code，接手一个**进行中**的项目。作者可能中断一阵，这份是给"零记忆的未来的你"看的。
**动手前按顺序做**：(1) 完整读 `/data/media/0/dev/onebot-qq/docs/` 下 6 篇文档（下面列）；
(2) 跑"确认运行态"命令看现状；(3) 读完+确认完再问作者要继续哪块，**别急着改代码**。

---

## 0. 项目一句话
把本机 **QQ 9.3.50(NT 架构, Android)** 封装成 **OneBot 11 正向 WebSocket 实现端**，作为 **vector(LSPosed 兼容)** 的 **Java Xposed 模块**跑在 QQ 主进程里，给 `/data/media/0/dev/ayjx`(Rust OneBot bot) 用。QQNT 类名/签名都是反编译本机 QQ 9.3.50 实测的。

## 1. 位置 / 备份 / 文档
- 项目：`/data/media/0/dev/onebot-qq`（= `/sdcard/Dev/onebot-qq`；只有 root 能进 /data/media/0）
- GitHub 私有备份：`github.com/araea/onebot-qq`（gh 已登录 `araea`，git push 直接可用）
- 持久记忆：`qqnt-9350-onebot-nt-kernel.md`（本项目浓缩笔记，通常自动加载）
- **必读文档**（docs/）：`HANDOFF.md`(环境/工具链/坑) `ARCHITECTURE.md`(代码结构+QQNT内核映射表) `ANTIDETECT.md`(反检测) `ROADMAP.md`(待办打法) `RESUME_PROMPT.md`(本文)
- **参考资料**（reference/）：`PACKETS.md`(OIDB 命令号+结构,抄自 NapCat) `qqhap-proto/`(QQ.hap 抽的 protobuf) `README.md`
- ayjx 框架：`/data/media/0/dev/ayjx`；QQ.hap(鸿蒙QQ泄露,协议参考)：`/data/media/0/dev/QQ.hap`

## 2. 战略全局：三条路（作者在权衡，务必懂）
作者要"**纯手机、不用 PC**"。达成这个目标有三条路，本项目只是其一：
| 方案 | 平台 | 掉线问题 | 难点 | 轻重 |
|---|---|---|---|---|
| **本项目 Xposed 模块** | 安卓原生 QQ | ❌有(在死磕) | 反检测天花板 | 最轻 |
| NapCat + Linux QQ(Termux) | 桌面 QQ(Electron) | ✅无 | Chromium-under-PRoot 难搞+重 | 最重 |
| **Lagrange.OneBot(Termux)** | 独立协议客户端(.NET) | ✅无 | 配签名服务器 | 轻 |
- **诚实定位**：论功能/成熟/稳定，**NapCat 全面碾压本项目**。本项目唯一真实优势=**能在安卓手机上跑不用 PC**(NapCat/LLOneBot 只能桌面)。有常开 PC 的话桌面 NapCat 远比这稳。**沟通别把本项目吹过头。**
- 若作者想换省心方案：**Lagrange.OneBot 在 Termux PRoot 里跑**最优(纯 .NET,不需要 QQ 客户端/Electron,扫码登录,不注入→不掉线,给 ayjx 出 OneBot11;代价=签名服务器)。本项目作为"试安卓原生能到什么程度"的实验有价值,作者也玩得开心。

## 3. 环境 & 构建部署（关键，别踩坑）
- 跑在 **rooted 手机的 Termux PRoot(Ubuntu)** 里。Android 二进制**直接能用**：`/system/bin/{pm,am,monkey,logcat,dumpsys,ps}`、`/data/adb/modules/zygisk_vector/cli`。`su`/`adb` 不行。
- 构建：`cd /data/media/0/dev/onebot-qq && bash build.sh` → `build/OneBotQQ.apk`（javac+r8+aapt,无需 Android SDK;还会用 termux clang 编 native libmapshide.so）。`libs/r8.jar` 丢了按 build.sh 顶部注释重下。
- 安装：`cp build/OneBotQQ.apk /data/local/tmp/ && pm install -r -d /data/local/tmp/OneBotQQ.apk`（pm 读不了 /sdcard,必须先 cp 到 /data/local/tmp）。
- 启/停注入（实时,免重启 daemon）：`sh /data/adb/modules/zygisk_vector/cli scope add|rm com.onebot.qq com.tencent.mobileqq/0`。手动改 modules_config.db **不生效**（要 cli 或重启）。
- 改代码要冷启动 QQ 才加载：`logcat -c; am force-stop com.tencent.mobileqq; sleep 1; monkey -p com.tencent.mobileqq 1`。
- **Xposed 桩类 `stubs/de/robv/**` 必须排除出 dex**（build.sh 已做）,否则 vector 拒载。
- 反编译产物在临时目录 `qqdex/full/sources/`（会话结束就没了,用 jadx 重生成:`jadx -d full --no-res --no-debug-info classes.dex classes2.dex`,QQ base.apk 从 `pm path com.tencent.mobileqq` 拿）。

## 4. 当前进度
**已实现 + 真机验证**：正向 WS + Bearer 鉴权 + 心跳；`get_login_info`；收发消息段 text/at/face/reply/**image**/**json(ark卡片)**/mface/poke；`delete_msg`(撤回)、`get_msg`；`get_group_list`、`get_group_member_info`/`_list`、`get_group_info`；`set_group_kick`/`ban`/`whole_ban`/`card`/`admin`/`leave`；`set_msg_emoji_like`；uin→uid 解析；**AntiDetect**(Java hook)。
**封包子系统（已真机打通）**：`packet/Pb.java` 数据层 + `packet/PacketSvc.java` 发送/回包层已实现。发送走 QQNT 自带 `IDependsAdapter.onSendSSORequest`，显式传十六进制 serviceCmd 与 OIDB 外层；回包 hook `CppProxy.onSendSSOReply` 按 requestId 关联，QQ 自己完成 SSO/QSec 签名。`set_group_special_title` 的 0x8FC_2 已接入；在内部测试群 `675983807`（账号为 owner）把本人空头衔原值写回，真机返回 `status=ok, retcode=0`，成功分支已验证。
**已新增(2026-08-27,真机验证)**：**合并转发** `send_group_forward_msg`/`send_private_forward_msg`(走 `packet/LongMsg.java` + `PacketSvc.sendSso` 发 `SsoSendLongMsg` 拿 resId → multimsg 卡片,测试群发+撤回 retcode 0,v1 文本节点)；`send_like`(0x7E5_104,协议对但被服务器 319 appid 策略拦,非代码问题)。
**未做**：语音/视频/文件发送(silk+highway 上传)、upload_*、get_forward_msg(下载/取回方向)。
**别做**：notice 事件（ayjx 不消费,它的 recall 是 /撤回命令）。

## 5. 封包子系统怎么继续
1. `PacketSvc` 已打通，不要退回 `onSendOidbRequest`：它会把 0x8FC 错拼成 `0x2300`，真机返回 236 `cmd not found`。
2. 正确路线是 `onSendSSORequest(Pb.oidbCmd, Pb.oidb, ...)` + `onSendSSOReply`；0x8FC_2 已在群主测试群返回 retcode 0。
3. 后续所有群内受控测试固定使用 `675983807`；非消息型动作尽量原值写回，消息型动作立即撤回。
4. 现在可直接复用 `sendOidb` 实现 `send_like`/SsoSendLongMsg。不要手工 QSign：QQ 会签名，且 9.3.50 的 `QSec.getSign` 实际是 `(String,byte[])`。
**每步要真机发包测试(有风控风险)**,测试发到作者指定测试群 `675983807` 并立即 delete_msg 撤回。

## 6. 反检测现状（读 ANTIDETECT.md 全文）
- QQ 被注入触发 native `libfekit.so`(QSec)检测→服务器踢下线要人脸。
- **Java 层 AntiDetect**(hook `QSec.detectMethod`→false、`getXpsInfo`→空)已生效;**疑似只靠它就能稳**(已连续挺过多次注入冷启动没掉线,需长期观察)。**别 hook** `getSign/doSomething/energy`(登录+签名要用)。
- **maps_hide(native GOT) 是死路**:linker 命名空间隔离(我们.so 看不到 libfekit)+libfekit 走裸 syscall。默认关,别再走。

## 7. ⚠️ 安全铁律
- 作者**主号**曾被风控判高危(一度连人脸都扫不了)。作者**明确接受主号风险**,但**任何可能触发掉线的操作前先说清楚**。
- **一键回滚**(掉线/卡死立刻执行)：`sh /data/adb/modules/zygisk_vector/cli scope rm com.onebot.qq com.tencent.mobileqq/0` → QQ 回干净无注入。
- **别搞全局 native hook**(喂 ART 假 maps → QQ 卡死闪屏)。发送测试只发测试群 `675983807` 且立即撤回;别发陌生群;「私聊自己」不是有效投递目标(图片报 rich media transfer failed)。

## 8. 确认运行态（先跑这些）
```bash
export PATH=/system/bin:$PATH
cat /proc/net/tcp6|awk '{print $2}'|grep -qi ':0BB9' && echo 注入中(3001在听) || echo 未注入
sh /data/adb/modules/zygisk_vector/cli scope ls com.onebot.qq            # QQ 是否在作用域
dumpsys activity activities|grep topResumedActivity|grep mobileqq         # Login=掉线,其它=登着
logcat -d -s OneBotQQ:* MapsHide:*|tail                                    # Registered/Captured/AntiDetect
```
连通测试(Node)：连 `ws://127.0.0.1:3001`,带 header `Authorization: Bearer onebot-qq-token`(token 在 `/sdcard/Android/data/com.tencent.mobileqq/files/onebot-qq.json`),发 `{"action":"get_login_info","echo":"x"}`;返回**真实昵称**=会话活。

## 9. 下一步（等作者定）
候选：① 基于已通 PacketSvc 实现 send_like/SsoSendLongMsg ② 继续补协议 body ③ 帮作者在 Termux 里试 Lagrange.OneBot ④ 继续观察注入后的账号稳定性。
**先读 6 篇文档 + reference/PACKETS.md + 确认运行态,再问作者。合作愉快 🤝**
