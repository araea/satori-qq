## 0.8.9.41

修 0.8.9.38 起一批「回成功但没数据」的群查询动作。

本机 QQNT 的 `IOperateCallback.onResult` 只有 `(int, String)` 两个参数，没有载荷，因此用这类回调做的「读」只能拿到状态码。0.8.9.38 把十个这样的入口做成了动作，客户端调用时看到 `code=0 success` 却拿不到任何字段。

- 群详情改走 `batchQueryCachedGroupDetailInfo`（回调是 `onResult(ArrayList<GroupDetailInfo>)`，载荷单独一个参数），拿回完整结构：群容量、等级、群主、`cmdUinMsgMask`、`activeMemberNum`、扩展标志等。取不到缓存时回退 `getGroupDetailInfoByFilter`
- `ExtraSvc.call` 现在认两种回调形状：`(int code, String msg, <payload>)` 与「只有载荷」的单参数回调。只把 `on*` 开头的方法当回调，避免 `Object.equals` 被当成载荷
- 群消息提醒方式（`group_msg_mask` 的读）与群统计（`group_statistic`）改从上述缓存详情里取值，不再返回空
- 其余确实没有载荷可取的读取动作（群公告、群成员等级、好友申请数、加好友设置、陌生申请）在响应里带 `payload: false`，把「内核只回状态码」这件事说清楚，而不是返回一个看起来像空结果的响应

其余与 0.8.9.40 相同。版本号 0.8.9.41（versionCode 76），沿用同一签名密钥，支持从 0.8.9.40 直接覆盖升级。

### 验证

- 测试群实测：`group_detail` 回完整结构（`cmdUinPrivilege=OWNER`、`activeMemberNum=3`），`group_msg_mask` 回 `NOTIFY`，`group_statistic` 回活跃数与成员数；无数据的动作回 `payload: false`
- `tests/internal-kernel-probe.js` 39/39、`ws-kernel-extras.js` 28/28、`ws-kernel-writes.js` 11/11，JVM 单测 18 项
