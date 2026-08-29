05 · 电线
=========

## SSO 与 OIDB

**为什么。** 有些动作内核 Java API 不够用或行为不对（头衔、群文件、合并转发上传）。QQ 自己有一条已经签过名的发包通道。借用它，就不必在模块里伪造登录签名。

**怎么用到。** `PacketSvc` 调 `onSendSSORequest`，自己分配 `requestId`，在 `onSendSSOReply` 里只收自己的包。不要用 `onSendOidbRequest`：本机实现会把命令号拼错。群文件、长消息的命令字抄自仍在维护的开源实现，再用本机回包校正。

**去学。** 「命令号 + 子命令 + body」这一层就够。OIDB 是腾讯业务包的一种外壳。先看 `packet/Pb.java` 怎么写 varint，再看一个具体动作（例如 0x8FC）的 body 是怎么填的。

## protobuf

**为什么。** 电线的 body 不是 JSON。要用尽量小的编码器把字段写成 protobuf。

**怎么用到。** 零依赖的 `Pb.java`。字段号来自社区与 QQ.hap 的对照，最后以真机回包为准。

**去学。** protobuf 的 wire 类型：varint、length-delimited、嵌套 message。会用手写一个嵌套结构，就能读懂 `LongMsg` 和群文件。不必上完整 codegen。

## QSec 签名

**为什么。** 每条真正离开手机的业务包，QQ 都会用 QSec 签字。签字里可以夹带环境信息。模块若去改 `getSign` 的返回值，服务器会认为这台设备在撒谎，登录先坏。

**怎么用到。** 签名路径保持原样。Java 上只中和明确的探测函数。环境扫描的主体在 `libfekit.so` 里，那是下一篇的事。

**去学。** 只要建立因果：本地 hook 可见 ≠ 服务器会信。不要去「研究怎么伪造签名」。那不是这个仓库的方向，也不是安全的练习题。
