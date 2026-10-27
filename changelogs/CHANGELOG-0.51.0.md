# 知弦 0.51.0：群文件、群精华、群公告读得出来了

## 现象

- `group_file_list` 对任何群都回空列表（`all_file_count: -1`），像是群里没有文件
- `group_essence_list`、`group_bulletin_get` 一律报 `pskey unavailable: code=-1 no valid local guest ticket`

## 原因

- **p_skey 一直取错了地方**：0.41 起退到登录服务的 `guestA2ToPskey`，它只给访客模式发票，正常登录的账号永远拿不到。依赖它的群精华、群公告（读与发）、发说说全部失效
- 群文件列表的内核请求漏填了 `sortType`（枚举字段，null 交给了 native），内核不回包；回包又按「第一条目的群号」分发，空文件夹、出错的回包没有条目，永远送不到——调用方等满 15 秒拿回空列表
- 精华读的是一个不存在的网页接口；公告接口的群号参数名写错（`group_code`，应为 `qid`），条目字段（`feeds` / `inst`）也认错了

## 改法

- p_skey 依次取：票据管理器缓存 `TicketManager.getPskey(uin, domain)` → QQ 网页业务自己用的 `IPskeyManager.getPskey(String[], cb)`（回调是混淆接口，按方法签名取类型、按参数形状认成败）→ 访客票。网页凭据另带 `TicketManager.getSkey`，bkn 先按 skey 算、被拒再按 p_skey 算
- 群精华改走 `qun.qq.com/cgi-bin/group_digest/digest_list`，按页翻（`page`、`page_limit`）；群公告改走带 `qid` 的 `announce/get_t_list`，置顶的在前
- 两者回包规整成固定字段（谁发的、什么时候、谁加的精、正文、图片），公告正文解开 HTML 转义；失败带 `error` 与原回包 `raw`
- 群文件列表先发 `OidbSvcTrpcTcp.0x6D8_1`（一问一答，不靠监听器），回不来或解出零条再走内核；内核请求补上 `sortType`、`sortOrder`，回包按请求号收进信箱再取。条目统一成内核 `GroupFileInfo` / `GroupFolderInfo` 的字段名，外加 `type` 与回执里的 `source`
- `internal/compat` 的 pskey 一项改为检查 `IPskeyManager.getPskey`

## 验证

真机验收要重启手机让新 .so 生效后做：`internal/call` 依次调 `pskey`（domains=["qun.qq.com"]）、`group_essence_list`、`group_bulletin_get`、`group_file_list`（`guild_id` 取一个有文件的群）。
