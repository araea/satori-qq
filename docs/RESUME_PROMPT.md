# 接手提示词（新会话直接粘贴这一整篇）

你是 Claude Code，接手一个**进行中**的项目。**动手前先做两件事**：
(1) 完整读 `/data/media/0/dev/onebot-qq/docs/` 下的 `HANDOFF.md`、`ARCHITECTURE.md`、`ANTIDETECT.md`、`ROADMAP.md` 四篇；
(2) 用下面"确认运行态"的命令看现在 QQ/模块是什么状态。读完 + 确认完，再问我要继续哪块，别急着改。

## 项目一句话
把本机 **QQ 9.3.50（NT 架构）** 封装成 **OneBot 11 正向 WebSocket 实现端**，作为 **vector（LSPosed 兼容）** 的 **Java Xposed 模块**跑在 QQ 主进程里，给 `/data/media/0/dev/ayjx`（Rust 写的 OneBot 机器人）用。参考过 OpenShamrock 的 hook 思路，但所有 QQNT 类名/签名都是反编译本机 QQ 9.3.50 实测的。

## 位置 / 备份
- 项目：`/data/media/0/dev/onebot-qq`（= `/sdcard/Dev/onebot-qq`；只有 root 能进 /data/media/0）
- GitHub 私有备份：`github.com/araea/onebot-qq`（gh 已登录 `araea`，`git push` 可直接用）
- 持久记忆里有 `qqnt-9350-onebot-nt-kernel.md`（本项目浓缩笔记，通常自动加载）
- 协议参考：`reference/qqhap-proto/`（从 `/data/media/0/dev/QQ.hap` 鸿蒙QQ泄露包抽的 OIDB/消息体 proto）

## 环境（关键，别踩坑）
- 跑在 **rooted 手机的 Termux PRoot(Ubuntu)** 里。Android 二进制**直接能用**：`/system/bin/{pm,am,monkey,logcat,dumpsys,ps}`、`/data/adb/modules/zygisk_vector/cli`。`su`/`adb` 不行。
- **构建**：`cd /data/media/0/dev/onebot-qq && bash build.sh` → `build/OneBotQQ.apk`（javac+r8+aapt，无需 Android SDK）。`libs/r8.jar` 若丢了按 build.sh 顶部注释重下。
- **安装**：`cp build/OneBotQQ.apk /data/local/tmp/ && pm install -r -d /data/local/tmp/OneBotQQ.apk`（pm 读不了 /sdcard，必须先 cp 到 /data/local/tmp）。
- **启用/停用注入（实时，免重启 daemon）**：`sh /data/adb/modules/zygisk_vector/cli scope add|rm com.onebot.qq com.tencent.mobileqq/0`。**手动改 modules_config.db 不生效**（要 cli 或重启）。
- **改完代码要冷启动 QQ 才加载**：`logcat -c; am force-stop com.tencent.mobileqq; sleep 1; monkey -p com.tencent.mobileqq 1`。
- **Xposed 桩类 `stubs/de/robv/**` 必须排除出 dex**（build.sh 已做），否则 vector 拒载。

## 当前进度
**已实现 + 真机验证**：正向 WS + Bearer 鉴权 + 心跳；`get_login_info`；收发消息段 text/at/face/reply/**image**/**json(ark卡片)**/mface/poke；`delete_msg`(撤回)、`get_msg`；`get_group_list`、`get_group_member_info`/`_list`、`get_group_info`；`set_group_kick`/`ban`/`whole_ban`/`card`/`admin`/`leave`；`set_msg_emoji_like`；uin→uid 解析；AntiDetect(Java hook)。
**未做（见 ROADMAP.md 打法）**：合并转发(multiForwardMsgElement)、语音/视频/文件**发送**(要 silk 编码+上传)、`send_like`/`set_group_special_title`(要 **OIDB 原始封包子系统**——QQ.hap 的 proto 帮拼包体，命令号要另挖)、`upload_*`。
**ayjx 不消费 notice 事件**（它的 recall 是 /撤回命令），别在 notice 上花时间。

## 反检测现状（读 ANTIDETECT.md 全文）
- QQ 被注入会触发 native `libfekit.so`(QSec) 检测 → 服务器踢下线要人脸验证。
- **Java 层 AntiDetect**（hook `QSec.detectMethod`→false、`getXpsInfo`→空）已生效；**疑似只靠它就能稳住**（用户已连续挺过多次注入冷启动没掉线，需长期观察确认）。
- **maps_hide（native GOT hook）是死路**：linker 命名空间隔离(我们的.so看不到libfekit)+libfekit 走裸 syscall。默认关，别再走这条弯路。

## ⚠️ 安全铁律
- 用户**主号**曾被风控判高危（一度连人脸都扫不了）。用户明确**接受主号风险**，但**任何可能触发掉线的操作前先跟用户说清楚**。
- **一键回滚**（QQ 掉线/卡死立刻执行）：`sh /data/adb/modules/zygisk_vector/cli scope rm com.onebot.qq com.tencent.mobileqq/0` → QQ 回到干净无注入。
- **别搞全局 native hook**（会喂 ART 假 maps → QQ 卡死在闪屏）。**别 hook** QQ 的 `getSign/doSomething/energy`（登录要用）。
- **发送测试**只发用户指定的测试群（如 `253119763`）且**立即 `delete_msg` 撤回**；别发陌生群/私聊；「私聊自己」不是有效投递目标（图片会报 rich media transfer failed）。

## 确认运行态（先跑这些）
```bash
export PATH=/system/bin:$PATH
cat /proc/net/tcp6 | awk '{print $2}' | grep -qi ':0BB9' && echo "注入中(3001在听)" || echo "未注入"
sh /data/adb/modules/zygisk_vector/cli scope ls com.onebot.qq      # QQ 是否在作用域
dumpsys activity activities | grep topResumedActivity | grep mobileqq   # Login=掉线, 其它=登着
logcat -d -s OneBotQQ:* | tail                                     # Registered/Captured/AntiDetect
```
连通测试（Node）：连 `ws://127.0.0.1:3001`，带 header `Authorization: Bearer <token>`（token 见 `/sdcard/Android/data/com.tencent.mobileqq/files/onebot-qq.json`，当前 `onebot-qq-token`），发 `{"action":"get_login_info","echo":"x"}`；返回**真实昵称**=会话活。反编译 QQ 的产物在构建会话临时目录 `qqdex/full/sources/`（丢了用 jadx 重生成，QQ base.apk 从 `pm path com.tencent.mobileqq` 拿）。

## 下一步（等用户定）
候选：合并转发 / OIDB 封包子系统 / 语音发送 / 继续观察反检测稳定性。**先读那四篇文档 + 确认运行态，再问用户。**
