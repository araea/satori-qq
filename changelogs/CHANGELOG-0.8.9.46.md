## 0.8.9.46

**结论先行：0.8.9.44 与 0.8.9.45 都拦错了地方，所以前两次踢线之后账号照样被登出、要短信验证才登得回来。** 这一版拦真正的入口，并且把已经被写坏的落盘状态修回来。

### 之前错在哪

QQ 处理服务端踢线时，`MainService$MyErrorHandler` 的每个回调最后都会落进 `popupNotification` / `popupNotificationEx`，那里做 `appRuntime.logout(reason, true)` 再跳登录页。0.8.9.44 拦的是这个出口，0.8.9.45 认为漏的是 `UidServiceImpl.logoutWhenReqUidFail`。

实际漏的那条在 `onKickedInternal` 里，**早于出口**：

```text
MainService$MyErrorHandler.onKickedInternal(ToServiceMsg, FromServiceMsg, isTokenExpired, isSameDevice)
  ├─ isTokenExpired == false（onKicked 进来）
  │    mApplication.setAutoLogin(false);                // mmkv 落盘
  │    popupNotification(..., forceLogout, ...)          // ← 0.8.9.44 拦的是这里
  └─ isTokenExpired == true（onKickedAndClearToken 进来）
       MsfSdkUtils.updateSimpleAccount(uin, false);      // files/user/u_<uin>_t → _f  ← 要害
       mApplication.setSortAccountList(...);
       popupNotification(..., LogoutReason.kicked, ...)  // ← 0.8.9.44 拦的是这里
```

2026-09-15 18:33 那次走的是下面一支：`qk_kick.log` 记的是 `reason=kicked`、`bSigKick != 1`，即 `isTokenExpired == true`，也就是 `onKickedAndClearToken` 进来的。出口确实被拦下了（`blocked_kicks` 涨、`popupNotification` 没跑），但账号标记已经改名、自动登录已经关掉——**这两样是落盘的**，重启多少次都停在登录页。0.8.9.45 挂的登出守卫一条都没命中（`qk_guard.log` 是空的），正说明登出不是那个时机发生的。

### 这一版怎么修

三层一起上：

1. **拦处理器入口**。`onKicked` / `onKickedAndClearToken` / `onKickedInternal` / `onCloneError` 整体 no-op，上面那些写操作一次都不会发生。出口那两个钩子继续留着，负责按 reason 过滤 `onUserTokenExpired` 与 `onServerSuspended`。

2. **把写坏的顶回去**。15 分钟善后期内，`MsfSdkUtils.updateSimpleAccount` / `updateSimpleAccountNotCreate` 的 `false` 顶成 `true`（改名仍然发生，但改成 `_t`，账号留在已登录列表里），`AutoLoginUtil.setAutoLogin(uin, false)` 顶成 `true`。

3. **离线时修回来**。把 `u_<uin>_f` 改回 `_t`、把自动登录开关写回 2。要修哪个号只认踢线原文里的 `uin=`（内存没有就读 `qk_kick.log` 末行），不按「`user/` 里唯一那个 `_f`」猜——本机就有一个用户自己注销掉的 `u_1665757132_f`，按这个猜会把早就登出的号重新标成已登录。

善后期跨进程重启成立：判据除了内存计数还看 `qk_kick.log` 的修改时间。看守恰好在踢线后 force-stop QQ，只靠内存的话善后期在新进程里等于不存在。用户自己按的退出登录（reason `user`/`switchAccount`）会让善后期立即停手。

### 一并改的几处

- **`onGrayError` 只当软事件**。它兼管 `wt_GetStViaSMSVerifyLogin` 与 `wt_loginAuth` 的响应，整条拦掉会把「靠短信验证登回来」这一步封死；而它又可能反复发生，当成硬踢线会让看守每来一次就 force-stop QQ 一次。现在放行（除上述登录命令外），只把善后窗口打开、记一行 `soft-kick` 到 `qk_guard.log`。
- **登出入口按调用线程区分**。`logout(boolean)` 的 reason 在底下被写死成 `user`，它既服务用户点退出登录（主线程），也服务踢线路径顺手登出（工作线程）。主线程那次放行并记下「用户主动退出」，工作线程那次窗口内拦掉。只有 `user` / `switchAccount` 算用户主动退出。
- **踢线原文多两个字段**：`kickType=`（`RequestMSFForceOffline.bKickType`，对应 `KKICKBYMULTIINST` / `KKICKBYMOBILE` / `KKICKBYPASSWORDCHANGE` / `KKCIKBYLOWVERSION`）与 `sigKick=`（1 = 带签名数据的安全强踢，reason 取 `secKicked`；0 = 普通强踢）。此前只记 reason 与服务端文案，现场分不出「在别处登录被顶」和「风控打击」。
- **新增 SSO 失败观测**（`/healthz` 的 `sso`，落盘 `qk_sso.log`）。模块自己发的 SSO 请求如果撞上 QQ 认「票据失效」的那组错误码（140022014/140022015/140022016、-10135/10136），说明踢线是接口调用把会话打废的，不是环境检测。`session_errors` 保持 0 就排除这条。
- **模块自己的文件从目录列举里去掉**。检测库在自己进程内 `getFilesDir().listFiles()` 就能看见 `qk_env_*.json`、`qk_kick.log` 等，而 `qk_env_maps_main.json` 的内容直接写着模块打了多少 GOT。`getdents64` / `readdir` 的条目名现在按 `qk_` 前缀过滤（这个前缀在 41 个 dex 里一个字符串都没有，不会误伤 QQ 自己的文件）。
- **修掉每次发消息都抛的一次 `FileNotFoundException`**。`satori-last-send.txt` 的写入此前无条件走外部 `Android/data` 路径，那个目录一旦不可写，logcat 里每次发消息就是一串栈。现在只在 `verbose_logs` 下写，并且移到 app 私有目录。

### 验证

真机 QQ 9.3.60.40970，0.8.9.46 覆盖安装后：`/healthz` 报 `kick_hook: 12`、`logout_guard.hooks: 5`、`login_state.hooks: 2`、`sso: {failures: 0, session_errors: 0}`，`internal/compat` 204/204，主进程 GOT 65 槽、`loop_ok=1`、`leak_*` 全 0。内核探测 39/39、读动作 28/28、写动作 11/11 无回归，JVM 单测 20 项（新增善后期窗口边界与 SSO 会话码判定），native 过滤单测新增 6 项。

版本号 0.8.9.46（versionCode 81），沿用同一签名密钥，支持从 0.8.9.45 直接覆盖升级。

**没有实测到的部分**：到发版为止没有触发新的真踢线，所以「拦入口之后账号标记与自动登录确实没被改坏」这条只有单元测试与代码路径支撑，没有真机踢线现场。下一次真踢线看两件事：`qk_guard.log` 里有没有 `self-heal` 行（说明账号标记确实坏过并被修回来）、`sso.session_errors` 是否仍为 0（说明踢线不是模块自己的请求打废的会话）。
