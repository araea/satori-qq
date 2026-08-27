# onebot-qq

把本机 QQ (9.3.50 / NT 架构) 封装成 **OneBot 11 正向 WebSocket 实现端**，供
`/data/media/0/dev/ayjx`（Rust OneBot 机器人框架）使用。作为 **vector / LSPosed**
Xposed 模块运行在 QQ 主进程内，参考 OpenShamrock 的 hook 思路，但所有 QQNT 内核
签名均来自对本机 QQ 9.3.50 的反编译实测（非直接使用已归档的 Shamrock）。

## 架构
```
ayjx (PRoot, OneBot 客户端)  --ws://127.0.0.1:3001-->  onebot-qq (QQ 进程内)
                                                        ├─ WsServer     正向 WS + Bearer 鉴权
                                                        ├─ OneBotHub    动作分发 + 事件下发
                                                        ├─ Convert      段<->QQ元素, MsgRecord->事件
                                                        └─ QQClient     捕获 NT 会话 / 收发消息
```
- ayjx 是**客户端**，主动连到本模块的 WS **服务端**；用 `echo` 匹配响应。
- 只在 QQ 主进程 `com.tencent.mobileqq` 运行；:MSF 等子进程忽略。
- 会话获取：hook `IQQNTWrapperSession$CppProxy` 构造函数捕获实时会话，
  再 `getMsgService()/getGroupService()/...`（`nativeinterface` 名称稳定，避开混淆的 `api.*`）。

## 现状
**里程碑 1（已在设备上验证）：**
- [x] 正向 WS 服务 + Bearer/access_token 鉴权 + 心跳 meta 事件
- [x] `get_login_info` → 真实 uin + 昵称
- [x] 接收群/私聊消息 → OneBot 事件（text / at / face / image / reply 段）
- [x] 发送 `send_msg` / `send_group_msg` / `send_private_msg`（text / at / face / reply / **image**）
- [x] `delete_msg`（撤回）、`get_msg`

**里程碑 2：**
- [x] **图片发送**（QQNT rich-media：copy 到 getRichMediaFilePathForMobileQQSend 路径后 sendMsg 自动上传；file 支持 路径/file://http/base64）
- [x] `get_group_list`（100 群实测）、`get_group_member_info`、`get_group_member_list`
- [x] `set_msg_emoji_like`（贴表情回应）
- [x] uin→uid 解析（ProfileService.getUidByUin，任意好友私聊）
- **AntiDetect** 反检测（best-effort，缓解掉线，详见 docs/ANTIDETECT.md）
- 已在真实群验证：文本 + 图片发送 + 撤回全部 retcode 0
- 注意：**「私聊自己」不是有效投递目标**（图片会报 rich media transfer failed），测试请发真实群/好友

**里程碑 3（已在设备上验证）：**
- [x] OIDB/裸 SSO 封包子系统、`set_group_special_title`
- [x] 合并转发发送与 `get_forward_msg`
- [x] `upload_group_file` / `upload_private_file`、语音/视频/文件发送
- [x] 群管理动作、json/lightapp/mface/poke 段
- [x] `send_like` 协议实现；服务器当前以 319 appid 策略拒绝，非本地封包错误

**里程碑 4（主号真机验证）：**
- [x] `get_friend_list`（153 人）、`get_stranger_info`、`get_group_msg_history`
- [x] `get_version_info`、`can_send_image/record`、`clean_cache`、`set_restart`
- [x] `set_group_name`（测试群原值写回）
- [x] 任意常见音频经 Android MediaCodec 转 AMR-NB；MP3 发送+撤回 retcode 0
- [x] 接收侧补齐 record/video/file/json/mface 段

**里程碑 5（0.5.0，反检测 × 协议双主线）：**
- [x] 默认静默/中性日志，`getXpsInfo` 前置 replacement，QSec reportLog 可配置阻断
- [x] exposure audit + watchdog 状态切换快照，用于 24h/72h 反检测 A/B
- [x] `get_image/get_file` 资源注册与 QQNT 内核下载闭环（主号历史只读验证）
- [x] `get_record` 同路径实现（等待自然语音样本验证）

**韧性层（2026-08-27；主号真机已验证在线路径）：**
- [x] `get_status` 返回真实 `online/good`，心跳不再把离线硬编码成在线
- [x] WS 建连立即发送 lifecycle `connect` + 当前状态心跳；在线切换发送 enable/disable
- [x] QQ 重登录产生新 NT 会话时自动重绑消息/群监听并清群缓存
- [x] 离线动作立即返回 1500，避免请求进入内核黑洞

进程外 root watchdog 已安装并开机持久化；真实 force-stop 后 10 秒拉起 QQ，`set_restart` 同样完成闭环。
watchdog 会持久化当前状态及掉线/恢复/拉起/冷重启计数，可用
`/data/adb/onebot-qq/qq-onebot-watchdog.sh status` 和 `snapshot` 查看，为 24h/72h 稳定性观测提供基线。
主号真机已验证：模块加载、消息监听、lifecycle `connect`、即时/周期 heartbeat、`get_status`、
`get_login_info`，以及设置页强制退出→LoginActivity offline→一键登录→online 的完整往返。WS 全程未断，
离线动作按预期快速失败 1500，恢复后群消息监听继续工作。此次 QQ 复用了同一进程/会话对象，真正创建
替换 CppProxy 对象时的重绑竞态保护仍未直接触发。
notice 事件因 ayjx 当前不消费而继续搁置。项目明确保持 Android 原生 QQ 单轨，不转 Lagrange。

## 配置
可选，放到 QQ 能读到的路径（否则用默认：3001 端口、无鉴权）：
`/sdcard/Android/data/com.tencent.mobileqq/files/onebot-qq.json`（见 onebot-qq.sample.json）。
设了 `token` 后，ayjx 的 `access_token` 要一致。

## 构建 / 部署
```
bash build.sh                              # -> build/OneBotQQ.apk
pm install -r -d /data/local/tmp/OneBotQQ.apk   # (先 cp 到 /data/local/tmp)
# 在 vector 里启用模块并把 com.tencent.mobileqq 加入作用域，然后冷启动 QQ：
sh /data/adb/modules/zygisk_vector/cli modules enable com.onebot.qq
sh /data/adb/modules/zygisk_vector/cli scope add com.onebot.qq com.tencent.mobileqq/0
am force-stop com.tencent.mobileqq && monkey -p com.tencent.mobileqq 1
```
工具链：javac(JDK21)+android.jar(API35)+libs/r8.jar(D8)+aapt/zipalign/apksigner。
注意：Xposed API 桩类 (`stubs/de/robv/**`) 仅编译期用，**必须**排除出 dex（build.sh 已处理），
否则框架报 “Xposed API classes are compiled into the module's APK” 拒绝加载。

## 文档（重要）
- [`docs/HANDOFF.md`](docs/HANDOFF.md) — **交接文档**：环境/工具链/构建部署/坑清单，无上下文也能接手
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — 代码结构 + **完整 QQNT 9.3.50 内核映射表**
- [`docs/ANTIDETECT.md`](docs/ANTIDETECT.md) — 掉线/反检测：做了什么、能力边界、根治路线
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — 里程碑 3 每个待做动作的具体打法
- [`docs/ONEBOT11_SUPPORT.md`](docs/ONEBOT11_SUPPORT.md) — 已实现动作/消息段与明确边界
- [`docs/FUTURE_PLAN.md`](docs/FUTURE_PLAN.md) — 单轨初心下的多角度后续规划
- 参考：`/data/media/0/dev/QQ.hap`（QQ 鸿蒙版源码泄露，352MB）——协议/OIDB 命令字的参考金矿

## 排错
- `logcat -s OneBotQQ:*` 看模块日志
- vector 日志：`/data/adb/lspd/log/modules_*.log`（搜 com.onebot.qq）
- 端口：`cat /proc/net/tcp6 | grep 0BB9`（3001）
