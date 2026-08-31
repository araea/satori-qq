# 参考资料

## qqhap-proto/

从 `QQ.hap` 抽出的 protobuf 定义，做 OIDB 原始封包时参考。

| 文件 | 内容 |
| --- | --- |
| `oidb.proto` | OIDBSSOPkg 外层封包 |
| `im_msg_body.proto` | IM 消息体 |
| `nt_msg_common.proto` / `nt_push.proto` | NT 消息与推送 |
| `oidb.d.ts` | OIDB TypeScript 定义 |

OIDB 命令号在 `ets/modules.abc` 里，需 abc 反汇编器。也可从 NapCat / Lagrange / 抓包获取。
