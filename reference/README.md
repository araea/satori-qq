# 参考资料

`qqhap-proto/` 包含从 QQ.hap 提取的协议定义，供实现 OIDB 与消息封包时查阅：

- `oidb.proto`：OIDBSSOPkg 外层封包
- `im_msg_body.proto`：IM 消息体
- `nt_msg_common.proto`、`nt_push.proto`：NT 消息与推送
- `oidb.d.ts`：OIDB TypeScript 定义

OIDB 命令号可从 `ets/modules.abc`、NapCat、Lagrange 或抓包结果中查找。
