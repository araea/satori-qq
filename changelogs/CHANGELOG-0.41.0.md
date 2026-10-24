# 0.41.0 — 群公告与群精华的读

0.40.0 补了群文件读写，这版把剩下两条读也打通：读群公告、读群精华。

## 读群公告（`group_bulletin_get` 重做）

- `getGroupBulletin(long, IOperateCallback)` 的回调只有 (code, msg)，拿不到正文——0.29.0 起它一直是个只回状态的占位。
- 现在走 `getGroupBulletinList(groupCode, pskey, "", GroupBulletinListReq, IOperateCallback)`：p_skey 本端自取，列表内容从 `IKernelGroupListener.onGetGroupBulletinListResult` 按群号对上（监听器本就注册着，加一条分发）。
- 返回 `bulletins[]`：`feed_id / uin / publisher / publish_time / pinned / title / text / pics`。

## 读群精华（`group_essence_list` 修正）

- `fetchGroupEssenceList(req, pskey, cb)` 的第二个参数是 qun.qq.com 的 pskey——0.29.0 起传的是空串，**这就是「内核从不回调」的根因**，不是签名变了。现在带上真 pskey，回调仍不来时退 `fetchGroupEssenceListV2`。
- 条目（`FetchEssenceMsgInfo`）只有 msgSeq / msgRandom / 发送者 / 加精人；正文由本端按 msgSeq 从本地历史补齐（`getMsgsBySeqAndCount`，每条一次，最多 20 条，补不上的留空）。

## 其他

- compat `TYPES` 重生成（91 项，新增 `GroupBulletinListReq`）。
- 0.40.0 尚未装机（等重启）的话，这版一起生效。
