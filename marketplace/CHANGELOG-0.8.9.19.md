## 0.8.9.19

- 适配 QQ 9.3.60.40970：兼容 `MsgRecord.senderRoleType` 与 `RevokeElement.senderUid` 被移除，缺失的可选字段不再导致入站消息或撤回事件丢失；改从群成员缓存解析群主/管理员角色。
- 新增可选 JNI 字段读取回归测试，并在 QQ 9.3.60 真机复核 HTTP、WebSocket、登录态、消息历史与事件转换。
