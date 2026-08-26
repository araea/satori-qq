# 参考资料

## qqhap-proto/ — 从 QQ.hap（鸿蒙版 QQ 泄露包，/data/media/0/dev/QQ.hap）抽出的 protobuf 定义
做 OIDB 原始封包（send_like / set_group_special_title 等里程碑-3 功能）时的协议参考。
- `oidb.proto` — OIDBSSOPkg 外层封包结构（uint32_command 主命令号 / service_type 子命令 / bytes_bodybuffer 包体）
- `im_msg_body.proto` — 完整 IM 消息体（所有富媒体 element 的 protobuf 布局）
- `nt_msg_common.proto` / `nt_push.proto` — NT 消息公共 + 推送定义
- `oidb.d.ts` — OIDB 的 TypeScript 定义（可读）

**注意**：具体 OIDB **命令号**（如 send_like 用哪个 0xXXXX）在 QQ.hap 的 `ets/modules.abc`
（ArkTS 字节码）里，普通 strings 抽不出，需要 abc 反汇编器。命令号可从：abc 反汇编 / 社区文档 / 抓包 获取。
