# 知弦 0.52.0：转发、语音转文字、群荣誉、WebHook，以及 QQ 升级后的自检

## 新能力

- **原生转发** `internal/message_forward`：把已有消息原样转到别的群或私聊（可多个目标），走 QQ「转发」菜单那条内核调用，图片、文件、语音不重新上传
- **语音转文字** `internal/voice_to_text`：借 QQ 自带的「转文字」听写一条语音，结果写回那条消息，再问直接读缓存
- **待处理申请** `internal/request_list`：还没处理的好友申请、入群申请与群邀请，形状与推送事件相同，客户端重启后能补上错过的
- **群荣誉榜** `group_honor`：龙王、群聊之火、群聊炽焰、冒尖小春笋、快乐源泉。群荣誉页已改成前端渲染，改读它背后的 `qunapp/honor_*` 接口
- **名片卡** `contact_card` 与消息元素 `<satori-qq:contact type="group|user" id="…"/>`：发「推荐群 / 推荐好友」卡片
- **收藏表情** `fav_emoji_list`
- 以上都在 `internal/capabilities` 的目录里，acumen 这类按目录办事的客户端不用改代码就能用

## 协议对齐

- 实现了元信息 API 的 `meta/webhook.create` / `webhook.delete`：事件原样 POST 到登记的地址，带 `Satori-Opcode` 与 `Authorization: Bearer`，与官方服务端同形；登记写盘，QQ 重启后接着推

## 修正

- 网页接口的 bkn 先按 p_skey 前 10 个字符算（qun.qq.com 前端自己的写法），整串算出来的会被荣誉接口判 `csrf error`；群精华、群公告同样受益
- 群文件列表：去掉按 UTF-8 硬解出来的二进制 `md5` / `sha`；上传时间从内核误放的 `uploadedSize` 归位到 `uploadTime`；`limit` 真的按条数截断；走了内核路径时说明 0x6D8_1 为什么没用上（`oidb_error`）

## QQ 升级适配

- 模块记住上次运行的 QQ 版本，升级后第一次自检就在日志里写清「从哪版到哪版、接口面过了多少、缺什么」，`/healthz` 在升级后 7 天内带 `qq_upgrade`
- 反射找不到方法时，错误里直接写着新版同名方法的真实签名，也汇总在 `internal/compat` 的 `observed.signature_mismatches`
- `scripts/qq-upgrade.sh`：一条命令存新安装包、与上一版做 dex-diff、强制自检、清旧安装包；`scripts/dex-sig.py` 按类名或方法名查安装包里的签名

## 验证

JVM 单元测试 36 项与只读协议探针 157 项通过（探针跑的是线上 0.51.0）；群荣誉与 bkn 口径用真账号的网页凭据实测过。新动作的内核调用（forwardMsg、translatePtt2Text、名片卡、收藏表情）签名按 9.3.80 安装包核对，要重启手机让新 .so 生效后才能在真机上跑。
