## 0.10.0

这一版不改发送行为，改的是**「被踢下线到底是为什么」这件事能不能查**，以及补几处反检测面的小口子。

### 为什么做这个

先是把「语音条、文件能不能发」验了一遍（都行，见 0.9.1），期间撞上两次服务端强制下线：`msf-kick-entry`，`kickType=0`、`sigKick=0`、`sameDevice=0`，文案是「你的账号当前登录已失效，请重新登录」。拦住了、账号没被摘、自动登录保住了、看守重启 QQ 后自己登回来——但**看不出这是风控打击，还是同一账号在别处登录把本机顶了**。前者值得继续做过检测，后者做过检测一点用没有，先把这件事分开才有意义。

### 改了什么

**踢线台账补字段。** 之前的行只有入口、reason、`kickType`、`sigKick`、标题正文：

- MSF 那条路（`RequestMSFForceOffline` 八个字段）补 `seqno=`、`sigLen=`（安全强踢的签名段长度，用来分清"说是安全强踢但签名段是空的"）。
- NT 内核那条路（`KickedInfo`）补 `appId=`、`instanceId=`、`securityKickedType=`。**`appId` 是判「谁把我顶了」的唯一字段**（PC / 手机 / 平板各有 appId），MSF 包里没有它。
- 每行补 `up=<秒>`：这次登录活了多久。同一个数反复出现说明是会话/票据寿命到了，长短不一才像按行为打分。
- 把 `KickedType` 的声明顺序从 APK 里核出来（`KKICKBYMULTIINST, KKICKBYMOBILE, KKICKBYPASSWORDCHANGE, KKCIKBYLOWVERSION`），名字按它取；**名字后面继续带 `?`**——「服务端那个字节就是枚举序号」仍是推定：没有任何 Java 类读这个字段，native 里也搜不到这几个枚举名的字符串，核不到映射代码；而且 `KickedInfo` 的默认构造就是 `values()[0]`，服务端没填时同样取到 0。

**反检测面补五条。** `libfekit.so` 自带一份 root 管理器名单，模块的黑名单里原来覆盖了 magisk / kernelsu / apatch / supersu / superuser，缺 `com.kingroot.kinguser`、`com.kingo.root`、`com.shuame.rootgenius`、`com.smedialink.oneclickroot`、`com.zhiqupk.root.global` 这五个一键 root 应用，现在补上（本机一个都没装，补的是名单完整性）。

### 顺带核到的设备侧现状

写进 `docs/ANTIDETECT.md` 的「设备侧现状」表，结论是**客户端这边看不到能解释「被设备异常整下线」的脏东西**：

- `ro.boot.verifiedbootstate=green`、`ro.boot.flash.locked=1`（设备层模块已经改好，文档里原来写的 `orange` 已过时）
- 没有任何 root 管理器应用；没有 `/sys/module/kernelsu`、`/sys/module/apatch`
- 没配假设备标识（配置文件不存在，全用默认值），QQ 自己的 `files/imei` 从 2026-09-07 起没变
- 时钟与 baidu / tencent / deepseek 回包的 `Date` 差 1 秒内（SSO 签名对时间敏感）
- `libMSFKernel.so` 里没有 root/hook 关键字名单，只有遥测与它自己的 `.MSF*` 文件；它的 inotify 盯的是自己那些文件

所以这一版没有再加过检测代码——**先让下一次踢线的证据能落地**。文档里也写明：排障时别把 force-stop 当免费手段，每强停一次 QQ 都要重新握手、重新上报设备、重新登录一次。

### 验证

JVM 单测 20 项全过；真机 QQ 9.3.60.40970 覆盖安装后 `/healthz` 报 `version: 0.10.0`、`compat` 204/204、`kick_hook` 12、`logout_guard.hooks` 5、`login_state.hooks` 2。踢线字段要等下一次真实踢线才能看到实例（`qk_kick.log` 的新行会带 `up=`）。
