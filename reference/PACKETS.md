# OIDB / 封包子系统参考（命令号 + 结构，抄自 NapCat/Lagrange，非 abc 反汇编）

> 建议参考现代维护库：**NapCatQQ**(TS, github.com/NapNeko/NapCatQQ, `packages/napcat-core/packet/`)、
> **Lagrange.Core**(C#, `Lagrange.Core/Internal/Packets/Service/Oidb_0xXXXX.cs`)。Shamrock 太老别用。
> 它们的 body protobuf 与安卓**完全一样**（同一服务器协议），只有**发送方式**不同(桌面 vs 安卓 MSF)。
> **命令号不用啃 QQ.hap 的 abc**——NapCat/Lagrange 源码里明写。

## OIDB 外壳 `OidbSvcTrpcTcpBase`（字段号确认）
`1=command(uint32), 2=subCommand(uint32), 3=errorCode(uint32), 4=body(bytes), 5=errorMsg(string), 12=isReserved(uint32)`
- serviceCmd 字符串 = `OidbSvcTrpcTcp.0x{CMD大写HEX}_{sub}`，如 `OidbSvcTrpcTcp.0x8FC_2`。
- 大多现代命令 isReserved=1（body 用 uid）。
- 本项目已实现：`packet/Pb.java` 的 `Pb.oidb(cmd,sub,body,isReserved)` + `Pb.oidbCmd(cmd,sub)` + 回包 `oidbBody/oidbResult/oidbErrMsg`。离线测过 0x8FC_2 正确。

## 已抄到的命令（body 字段号）
### set_group_special_title  cmd=0x8FC sub=2
`{1:groupUin(uint32), 3:body{1:targetUid(str), 5:specialTitle(str), 6:expiredTime(int32,-1=永久), 7:uinName(str), 8:targetName(str)}}`
### 戳一戳 poke  cmd=0xED3 sub=1
body `{uin:target, ext:0, groupUin或friendUin:peer}`（群传 groupUin，私聊传 friendUin；字段号去 NapCat proto/oidb/Oidb.0xED3.ts 核对）
### send_like（点赞资料卡）
安卓 QQ 9.3.50 **无 ProfileLikeService 内核服务**，必须走封包。命令号见 NapCat `action/user/SendLike.ts` / Lagrange。
### 合并转发上传  cmd=`trpc.group.long_msg_interface.MsgService.SsoSendLongMsg`（非OIDB，直接 trpc 服务）
流程(NapCat message/UploadForwardMsg.ts)：
1. buildFakeMsg：把每个伪造节点拼成 im_msg_body 的 MsgRecord protobuf（参考 reference/qqhap-proto/im_msg_body.proto）
2. 包成 `LongMsgResult{action:[{actionCommand:'MultiMsg', actionData:{msgBody}}]}`
3. gzip
4. `SendLongMsgReq{info:{type: 群?3:1, uid:{uid: 群?groupUin:selfUid}, groupUin, payload}, settings:{field1:4,field2:1,field3:7,field4:0}}`
5. 发出→回 resId→再发一个引用 resId 的 ark/multiforward 消息元素。
   get_forward_msg 反过来：NapCat message/DownloadForwardMsg.ts。

## 还差的发送链路（PacketSvc，风险在这）
1. 从主进程发：`com.tencent.mobileqq.msf.sdk.o` 里 `this.q.sendToServiceMsg(ToServiceMsg)`(o 混淆,动态定位)；
   `ToServiceMsg(appId, uin, serviceCmd)` + setWupBuffer(oidb pkg bytes)。或学 NapCat/Shamrock 注入 :MSF。
2. **签名**：trpc 包要 `QSec.getInstance().getSign(cmd, body, seq)`(AntiDetect 没碰 getSign,安全)。
3. 回包：MSFServlet 或 hook FromServiceMsg，按 seq/hash 关联(见 Shamrock PacketReceiver)。
每步都要真机发包测试（有风控风险，测试发到用户指定测试群/自身）。
