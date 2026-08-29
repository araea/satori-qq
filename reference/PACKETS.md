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

### 群文件查询  cmd=0x6D8

- sub=1：body field2=`GetFileListReq{1:group,2:appId=7,3:folderId,5:count,9:sortBy=1,
  12:fieldFlag=0xFFFFFF,13:startIndex,17:sortOrder=2,18:showOnlineDoc=0}`；响应 field2，item field5，
  type=1/file(field3)、type=2/folder(field2)，isEnd=4、nextIndex=13。
- sub=2：body field3=`GetFileCountReq{1:group,2:appId=7,3:busSelector=6}`；响应 field3 的
  `4=fileCount,6=limitCount,7=isFull`。
- sub=3：body field4=`GetSpaceReq{1:group,2:appId=7}`；响应 field4 的 `4=totalSpace,5=usedSpace`。

### 群文件 URL  cmd=0x6D6 sub=2

body field3=`DownloadReq{1:group,2:appId=7,3:busId(默认102),4:fileId}`；响应 field3 的
`1=retCode,5=downloadDns,6=downloadUrl(bytes),13=httpsDns`，URL 为 DNS + token hex 的
`/ftn_handler/.../?fname=`。以上均已走 Android PacketSvc 真机回包。

### 群文件写  cmd=0x6D6 / 0x6D7

- `0x6D6_3` 删除文件：body field4=`{1:group,2:appId=7,3:busId,5:fileId}`。
- `0x6D6_4` 重命名文件：body field5=`{1:group,2:appId=7,3:busId,4:fileId,5:parent,6:newName}`。
- `0x6D6_5` 移动文件：body field6=`{1:group,2:appId=7,3:busId,4:fileId,5:parent,6:dest}`。
- `0x6D7_0` 创建目录：body field1=`{1:group,2:appId=7,3:parent,4:name}`；响应 field1.4=FolderInfo。
- `0x6D7_1` 删除目录：body field2=`{1:group,2:appId=7,3:folderId}`。
- `0x6D7_2` 重命名目录：body field3=`{1:group,2:appId=7,3:folderId,4:newName}`。
  字段抄自 Lagrange `Oidb_0x6D6/0x6D7` 与 NapCat `Oidb.0x6D6.ts`；appId 与已验证查询保持 7。

## 安卓发送链路（QQ 9.3.50 已实现/真机回包）
1. `PacketSvc` 反射取 `IKernelService.getIDependsAdapter()`，调用 `onSendSSORequest`，传精确的
   `OidbSvcTrpcTcp.0x{CMD大写HEX}_{sub}` 与 `Pb.oidb(...)`。
2. QQ 的 `KernelServlet`/MSF 继续负责 SSO framing、账号元数据和 QSec 签名；无需手工 QSign。
3. hook `IQQNTWrapperSession$CppProxy.onSendSSOReply`，按自分配 requestId 关联回包，只消费模块自己的请求。
4. 不要改用 `onSendOidbRequest`：它在本机把 0x8FC 的数值 2300 拼成字符串 `0x2300`，已实测得到
   236 `cmd not found`。改为显式 SSO serviceCmd 后，在内部群主测试群 `675983807` 原值写回空头衔，
   真机返回成功（status=ok, retcode=0）。
