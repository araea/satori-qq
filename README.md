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

## 现状（已在设备上验证）
**里程碑 1：**
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
- 已在真实群验证：文本 + 图片发送 + 撤回全部 retcode 0
- 注意：**「私聊自己」不是有效投递目标**（图片会报 rich media transfer failed），测试请发真实群/好友

## 待办（里程碑 3，需要 OIDB 原始封包子系统 / hook MSF 发包层）
- `send_like`、`set_group_special_title`、`get_forward_msg`
- `upload_group_file` / `upload_private_file`、语音/视频/文件**发送**
- notice 事件：群撤回、戳一戳、进退群、禁言

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

## 排错
- `logcat -s OneBotQQ:*` 看模块日志
- vector 日志：`/data/adb/lspd/log/modules_*.log`（搜 com.onebot.qq）
- 端口：`cat /proc/net/tcp6 | grep 0BB9`（3001）
