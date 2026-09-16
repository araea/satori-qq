## 0.13.2 · 知弦

这一版**只加观测点，不改任何行为**。

### 为什么要加

前面几版把「被踢之后为什么会变成要重新登录」这条链查了一遍，还剩最后一段没定案：`qk_guard.log` 里 71 行 `kept-login-state updateSimpleAccountNotCreate(uin, false->true)`（QQ 想把账号从已登录列表里摘掉、被模块顶回去）有两个可能的调用点 —— `MyErrorHandler.onUserTokenExpired` 的 `expired` 支，和 `UidServiceImpl.logoutWhenReqUidFail`。两者都能解释日志的形状，只看参数分不出来，所以**不能**据此决定要不要拦 `expired` 那条路（它现在是唯一没拦的、会把人带回登录页的踢线路径）。

### 加了什么

- 钩 `MyErrorHandler.onUserTokenExpired`，**只读不拦**：记 `ssoErr=`（`attr_sso_error_code`）、`branch=`（`ssoErr` 属于 {-10135,10136} 是 `kicked` 支，其余是 `expired` 支）、`svcCmd`、`uin`、服务端文案与布尔参数。落在 `qk_guard.log` 的 `token-expired` 行，计数进 `/healthz` 的 `token_expired`
- 出口钩放行 `expired` / `gray` 时记一行 `allowed-logout`（仍不拦）。以前这条完全静默，「是谁把人送回登录页的」在台账里看不见。计数进 `/healthz` 的 `allowed_logout`
- `login_state` 的 `kept-login-state` 行多一个 `by=`（哪个入口钩在调）+ `frames=`（栈兜底）。0.13.2 的 `by=` 只做栈扫描，实测取到的是框架自己的混淆帧（`g.a`），0.13.3 改成入口打标记后才准，读法见 `docs/ANTIDETECT.md`

这三项都只写 `qk_guard.log`，不写 `qk_kick.log` —— 后者是看守「立刻重启 QQ」的判据，观测项混进去会变成重启风暴。

### 下一次事件怎么读

```text
qk_kick.log    2026-09-16T16:33:19 msf-kick-entry entry=... kickType=0 ...   ← 服务端踢线
qk_guard.log   2026-09-16T16:34:37 token-expired ssoErr=... branch=expired  ← 谁在把号往回带
qk_guard.log   2026-09-16T16:34:37 kept-login-state ... by=token-expired frames=... ← 谁摘的账号
qk_guard.log   2026-09-16T16:34:37 allowed-logout reason=expired ...         ← 哪条路把人送去登录页
```

升级提示：沿用包名与签名，直接覆盖升级，装完重启一次 QQ。

### 装上之后第一次踢线就给出了答案

升级到 0.13.2 之后本机立刻碰上了一次真踢线（19:39:26），带新字段的第一条记录长这样：

```text
msf-kick-entry entry=onKickedAndClearToken kickType=0 sigKick=0 sameDevice=0
seqno=3721231761 title=下线通知 msg=你的账号当前登录已失效，请重新登录。
uin=3373167460 args=false svcCmd=StatSvc.ReqMSFOffline cmd=unknown up=637s
```

三件事定下来了：走的是 `onKickedAndClearToken`（会摘账号的那一支）；服务端下发的命令名是 `StatSvc.ReqMSFOffline`；这次没有 `token-expired` 行，说明它没经过 `onUserTokenExpired`。另外 19:42:49 有个进程在内存里没有踢线记录的情况下把账号标记顶了回去（`kept-login-state ... (last kick  -)`），那是 0.13.1 的跨重启窗口在真机上第一次生效。
