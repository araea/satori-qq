# onebot-qq 接手提示词（当前版）

你正在接手一个进行中的项目。不要凭旧记忆改方向，也不要一上来改代码。

## 接手顺序

1. 完整阅读 `docs/HANDOFF.md`、`docs/ARCHITECTURE.md`、`docs/ANTIDETECT.md`、
   `docs/ROADMAP.md`、`docs/ONEBOT11_SUPPORT.md`、`docs/FUTURE_PLAN.md` 和
   `reference/PACKETS.md`。
2. 运行本文“只读运行态核验”，再看 `git status --short --branch` 与最近提交。
3. 保留工作区中已有改动；先构建和审计，再补缺口。
4. 若作者只说“继续”，按 `FUTURE_PLAN.md` 的顺序推进安全、可验证、不会撞主号风控的工作。

## 不变初心与最终决定

项目只做一件事：把本机 Android QQNT 9.3.50 封装成供 ayjx 使用的 OneBot 11 正向 WebSocket
实现端，作为 vector/LSPosed 兼容的 Java Xposed 模块运行在 QQ 主进程。

- 坚持纯手机、Android 原生 QQ 单轨；作者已明确不转 Lagrange，也不以桌面 QQ 替代。
- 使用主号的风险由作者明确接受，但任何可能触发掉线、验证或风控的操作前仍须说明。
- 注入导致的掉线是 vector/zygisk + libfekit native 层的范式成本，不是继续堆 Java hook 能解决的 bug。
- 不再碰 maps_hide、ART/全局 maps 欺骗或 QSec 签名 JNI；只保留低风险的
  `detectMethod/getXpsInfo` 中和。
- notice/request 不因规范存在就盲目实现；ayjx 当前不消费，因此保持不做。

## 项目与环境

- 仓库：`/data/media/0/Dev/onebot-qq`，普通 shell 视图为 `/storage/emulated/0/Dev/onebot-qq`。
- ayjx：`/data/media/0/Dev/ayjx`。
- 私有备份：`github.com/araea/onebot-qq`；先核对状态再提交/推送。
- 设备：rooted ColorOS、KernelSU 系、Zygisk Next、vector，模块包名 `com.onebot.qq`。
- 当前会话常为普通 app UID；Android/root 命令用 `su -c`，`am`/`monkey` 用 `/system/bin` 绝对路径。
- `/sdcard` 是 FUSE，不适合构建；构建必须走 `/data/media/0/Dev/onebot-qq`。

构建：

```bash
su -c '
export HOME=/data/data/com.termux/files/home
export PATH=/data/data/com.termux/files/usr/bin:/system/bin:$PATH
cd /data/media/0/Dev/onebot-qq && bash build.sh
'
```

安装 APK 会冷启 QQ、可能触发账号风险；除非本轮确需部署验证，不要仅为“看看”而安装或重启。

## 当前真实进度

以下均已实现；标为真机验证的事实以 `HANDOFF.md` 和 `ONEBOT11_SUPPORT.md` 为准：

- 正向 WS、Bearer 鉴权、真实 lifecycle/heartbeat/get_status、离线动作 1500。
- 收发 text/at/face/reply/image/record/video/file/json/mface/poke 等消息段。
- 合并转发发送/取回，群管、好友/陌生人、群历史及常用 OneBot 11 查询动作。
- OIDB/裸 SSO 传输层；QQ 自己完成 SSO framing/QSec 签名，绝不手工 QSign。
- 常见音频经 Android MediaCodec 解码、8k mono 重采样、AMR-NB 编码；MP3 发送/撤回已验证。
- 同进程退出/重登录自动恢复 listener；新 session 对象的竞态保护已写入但尚待自然触发验证。
- root watchdog 已开机持久化，可拉起被杀的 QQ，且 LoginActivity 不重启循环、冷启有 5 分钟退避。
- watchdog 将状态写入 `/data/adb/onebot-qq/watchdog.status`，累计计数写入
  `watchdog.counters`；支持 `status` 和 `snapshot` 子命令。
- `send_like` 协议正确，但服务器以 appid 规则 319 拒绝；不要重复撞风控。

## 只读运行态核验

```bash
su -c '
export PATH=/system/bin:$PATH
cat /proc/net/tcp6 | awk "{print \$2}" | grep -qi :0BB9 \
  && echo "3001 listening" || echo "3001 not listening"
sh /data/adb/modules/zygisk_vector/cli scope ls com.onebot.qq
/system/bin/dumpsys activity activities | grep -i topResumedActivity | grep -i mobileqq
pidof com.tencent.mobileqq
sh /data/adb/onebot-qq/qq-onebot-watchdog.sh status
sh /data/adb/onebot-qq/qq-onebot-watchdog.sh snapshot
'
```

连通测试只调用 `get_status` 或 `get_login_info`，不要发消息。连接
`ws://127.0.0.1:3001`，Authorization token 取 QQ 配置文件但不要打印到日志或回复中。

## 安全边界

- 新发包先做离线 protobuf 往返；真机测试固定群 `675983807`，消息立即撤回，管理动作原值写回。
- 不向陌生人发消息，不导出 cookies/csrf/credentials，不 hook
  `getSign/getSignEntry/getEstInfo/doSomething/energy`。
- `onSendOidbRequest` 会把 0x8FC 错拼成 0x2300；继续使用 `onSendSSORequest` + `onSendSSOReply`。
- 私聊自己不是有效富媒体投递目标。
- 出现掉线/卡死且需立即止损时：
  `su -c 'sh /data/adb/modules/zygisk_vector/cli scope rm com.onebot.qq com.tencent.mobileqq/0'`。

## 下一里程碑

按 `FUTURE_PLAN.md`：

1. 用 watchdog 已落盘的状态/计数完成 24h、72h 稳定性基线。
2. 群文件列表/URL 与 `get_image`/`get_record` 资源闭环。
3. 转发节点支持 image/at/reply。
4. QQ base.apk 版本指纹与适配探针。
5. ayjx 对 offline、WS 断线和 retcode 1500 给出明确降级提示。

保持诚实：本项目的价值是纯手机上的真实 Android QQ 控制与探索，不把它吹成比 NapCat 更成熟，
也不因困难改变作者已经确定的技术对象。
