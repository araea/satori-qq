# OIDB 与封包参考

命令号与结构抄自 NapCat、Lagrange。body protobuf 与安卓一致，发送方式不同。

## OIDB 外壳 OidbSvcTrpcTcpBase

`1=command(uint32), 2=subCommand(uint32), 3=errorCode(uint32), 4=body(bytes), 5=errorMsg(string), 12=isReserved(uint32)`

- serviceCmd 字符串为 `OidbSvcTrpcTcp.0x{CMD大写HEX}_{sub}`，如 `OidbSvcTrpcTcp.0x8FC_2`
- 大多现代命令 isReserved=1，body 用 uid
- 本项目已实现于 `packet/Pb.java`：`Pb.oidb(cmd,sub,body,isReserved)`、`Pb.oidbCmd(cmd,sub)`，回包用 `oidbBody` / `oidbResult` / `oidbErrMsg`。离线测过 0x8FC_2 正确

## 已抄到的命令

### set_group_special_title（cmd=0x8FC sub=2）

```text
{1:groupUin(uint32), 3:body{1:targetUid(str), 5:specialTitle(str), 6:expiredTime(int32,-1=永久), 7:uinName(str), 8:targetName(str)}}
```

### 戳一戳 poke（cmd=0xED3 sub=1）

body `{uin:target, ext:0, groupUin或friendUin:peer}`。群传 groupUin，私聊传 friendUin，字段号对照 NapCat `proto/oidb/Oidb.0xED3.ts`。

### send_like（点赞资料卡）

安卓 QQ 9.3.55 没有 ProfileLikeService 内核服务，也不能照搬桌面端的 `OidbSvcTrpcTcp.0x7E5_104`：服务端把该 rule 绑定在桌面 appid 上，手机 appid 发过去一律回 `oidb=319 "[oidb] rule type not match appid"`（换 source 值无效，也不是包格式问题；命令号错会回 236 `cmd not found`）。手机客户端走的是老式 WUP：`VisitorSvc.ReqFavorite`，已实现于 `qq/LegacySvc.java`，真机 QQ 9.3.55 回包成功。

发送：构造 `ToServiceMsg("mobileqq.service", selfUin, "VisitorSvc.ReqFavorite")`，`extraData` 填 `selfUin`(long)、`targetUin`(long)、`favoriteSource`(int)、`iCount`(int)、`from`(int)，交给 `AppInterface#sendToService`。QQ 的 `MobileQQServiceBase` 会用 `com.tencent.mobileqq.app.ch#g` 编码 `QQService.ReqFavorite`（JCE，非 protobuf）并签名，无需手工拼包。`ReqFavorite` 字段号：

```text
0 stHeader: ReqHead{lUIN(long), shVersion(short=1), iSeq(int), bReqType(byte=1), bTriggered(byte=0), vCookies(bytes|可选)}
1 lMID             目标 uin (long)
2 cOpType          0
3 emSource         来源；资料卡点赞=66，ZPlan 换装=70（客户端 VoteHelper / ZPlanProfileLikeManager）
4 iCount           次数
5 iHasZplanAvatar  0
```

接收：hook `MobileQQServiceBase.dispatchToHandler`，回包已被 LBS coder 解成 `QQService.RespFavorite` 放在 `FromServiceMsg` 的 `"result"` 属性里，按 `lMID` 关联目标。判据同客户端 `com.tencent.mobileqq.app.ch#a`：`stHeader.iReplyCode == 0` 为成功（`FromServiceMsg.getResultCode()` 业务层为 1000）。实测错误码：`10003` 对方权限设置不允许点赞，`54` 禁止给自己点赞。`iKoiLikeCount`（field 4）可读点赞后计数。

### 合并转发上传（trpc.group.long_msg_interface.MsgService.SsoSendLongMsg，非 OIDB）

流程见 NapCat `message/UploadForwardMsg.ts`：

1. buildFakeMsg：把每个伪造节点拼成 im_msg_body 的 MsgRecord protobuf（参考 `reference/qqhap-proto/im_msg_body.proto`）
2. 包成 `LongMsgResult{action:[{actionCommand:'MultiMsg', actionData:{msgBody}}]}`
3. gzip
4. `SendLongMsgReq{info:{type: 群?3:1, uid:{uid: 群?groupUin:selfUid}, groupUin, payload}, settings:{field1:4,field2:1,field3:7,field4:0}}`
5. 发出后回 resId，再发一个引用 resId 的 ark / multiforward 消息元素

`get_forward_msg` 反过来，见 NapCat `message/DownloadForwardMsg.ts`。

### 群文件查询（cmd=0x6D8）

- sub=1：body field2=`GetFileListReq{1:group,2:appId=7,3:folderId,5:count,9:sortBy=1,12:fieldFlag=0xFFFFFF,13:startIndex,17:sortOrder=2,18:showOnlineDoc=0}`；响应 field2，item field5，type=1 为文件（field3）、type=2 为目录（field2），isEnd=4、nextIndex=13
- sub=2：body field3=`GetFileCountReq{1:group,2:appId=7,3:busSelector=6}`；响应 field3 的 `4=fileCount,6=limitCount,7=isFull`
- sub=3：body field4=`GetSpaceReq{1:group,2:appId=7}`；响应 field4 的 `4=totalSpace,5=usedSpace`

### 群文件 URL（cmd=0x6D6 sub=2）

body field3=`DownloadReq{1:group,2:appId=7,3:busId(默认102),4:fileId}`；响应 field3 的 `1=retCode,5=downloadDns,6=downloadUrl(bytes),13=httpsDns`，URL 为 DNS 加 token hex 的 `/ftn_handler/.../?fname=`。以上均已走 Android PacketSvc 真机回包。

### 群文件写（cmd=0x6D6 / 0x6D7）

- `0x6D6_3` 删除文件：body field4=`{1:group,2:appId=7,3:busId,5:fileId}`
- `0x6D6_4` 重命名文件：body field5=`{1:group,2:appId=7,3:busId,4:fileId,5:parent,6:newName}`
- `0x6D6_5` 移动文件：body field6=`{1:group,2:appId=7,3:busId,4:fileId,5:parent,6:dest}`
- `0x6D7_0` 创建目录：body field1=`{1:group,2:appId=7,3:parent,4:name}`；响应 field1.4=FolderInfo
- `0x6D7_1` 删除目录：body field2=`{1:group,2:appId=7,3:folderId}`
- `0x6D7_2` 重命名目录：body field3=`{1:group,2:appId=7,3:folderId,4:newName}`

字段抄自 Lagrange 的 `Oidb_0x6D6` / `Oidb_0x6D7` 与 NapCat `Oidb.0x6D6.ts`；appId 与已验证查询保持 7。

## 安卓发送链路（QQ 9.3.50 已实现并真机回包）

1. `PacketSvc` 反射取 `IKernelService.getIDependsAdapter()`，调用 `onSendSSORequest`，传精确的 `OidbSvcTrpcTcp.0x{CMD大写HEX}_{sub}` 与 `Pb.oidb(...)`
2. QQ 的 `KernelServlet` / MSF 继续负责 SSO framing、账号元数据与 QSec 签名，无需手工 QSign
3. hook `IQQNTWrapperSession$CppProxy.onSendSSOReply`，按自分配 requestId 关联回包，只消费模块自己的请求
4. 不要改用 `onSendOidbRequest`：它在本机把 0x8FC 的数值 2300 拼成字符串 `0x2300`，实测得到 236 `cmd not found`。改为显式 SSO serviceCmd 后，在内部群主测试群 `675983807` 以原值写回空头衔，真机返回成功（status=ok, retcode=0）
