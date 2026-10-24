# 0.40.0 — 账号级资料动作与群文件读写

协议实现端补上了 QQ 客户端自己日常会做、此前一直没暴露的一批写操作，全部走纯 JNI 层，不新增封包通道。

## 账号级

- `internal/nick`：改 QQ 昵称（内核 `IKernelProfileService.setNickName`）。
- `internal/avatar`：改 QQ 头像（内核 `setHeader`，入参同群公告 `image`：本地路径、`file:`、`data:`、`http(s):`、`internal:`，本端先落盘）。
- `internal/long_nick` 增加别名 `signature`：个性签名。
- `internal/feed_publish`（别名 `publish_feed`）：发 QQ 空间说说。凭据走空间域 `p_skey`（内核 `getPskey` 自取）+ `g_tk`，请求为空间 H5 的 `emotion_cgi_publish_v6`，只支持文本；失败回执带 CGI 原始 `raw`。
- `internal/pskey`：直接取域名 → p_skey 表（读动作），调用方自己拼网页请求时用。

## 群文件

- `internal/group_file_list`：按 `folder_id` 翻页列出群文件与文件夹。内核 `getGroupFileList` 的结果从 `onGroupFileInfoUpdate` 回来，本端按群号对上并替调用方等到回包，翻页到 `isEnd`。
- `internal/group_file_get` / `group_file_search`：单文件详情、按名搜索。
- `internal/group_file_download`：`onlyDownloadFile` 落盘，完成通知按 `file_id` 对上；等不到完成通知时以 `getGroupFileInfo` 的本地路径兜底。成功回执带 `url`（`internal:` 资源，字节经 `GET /v1/internal/red/{selfId}/_tmp/{id}` 取走）。
- `internal/group_file_upload`：`onlyUploadFile` 上传，完成通知按文件名尾段对上，等不到标 `assumed`。

## 其他

- `internal/card` 不带 `user_id` 即改自己的群名片（群昵称），0.39 及以前的行为不变。
- `capabilities.removed` 里 `qzone.publish` 与 `group_file` 撤下：分别由 `feed_publish` 与 `group_file_*` 家族取代。
- compat 自检表补 `IGroupFileInfoCallback` / `ISearchGroupFileCallback`；`TYPES` 重新生成（90 项）。
