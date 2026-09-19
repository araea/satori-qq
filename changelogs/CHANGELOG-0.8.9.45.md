## 0.8.9.45

0.8.9.44 拦住了踢线通知，但**账号还是会被登出**。这一版补上真正的登出那条线。

### 0.8.9.44 漏了什么

17:29 的一次真踢线上实测到的：`blocked_kicks=1`、`auto_login_kept=2`、自动登录的值事后仍是"开"，但 QQ 弹回登录页，要短信验证才登得回来。

漏的是 UID 那条线：

```text
UidServiceImpl.logoutWhenReqUidFail()
  setAutoLogin(false);        // 被自动登录守卫顶回去了，所以 auto_login_kept 涨了
  logout(true);                // 真登出，之前没人拦
  updateSimpleAccountNotCreate(uin, false);   // 把账号从已登录列表里摘掉 ← 要害
  refreAccountList();
  reportClearLoginData(uin, "2011");
```

`updateSimpleAccountNotCreate` 那一步是关键：账号从已登录列表里没了，下次启动没有可自动登录的对象，于是停在登录页要短信验证。之前记的 uid-fail 只拦了它**后面**的 `kickToLoginPage()`——名字看着像终点，其实登出已经在前一步发生了。

### 怎么拦

这条不能按 reason 过滤：`logout(true)` 走 `QQAppInterface.logout(boolean)`（override，不经过 `AppRuntime.logout(reason, ...)`），而 `AppRuntime.logout(boolean)` 底下把 reason 写死成 `user`。所以改成**按时间窗口**：拦下踢线后的 5 分钟内，四个登出入口一律停掉。

| 入口 | 拦法 |
| --- | --- |
| `UidServiceImpl.logoutWhenReqUidFail()` | 窗口内整体 no-op（连带摘账号、报清数据一起停） |
| `QQAppInterface.logout(boolean)` | 窗口内 no-op（reason 分不出来） |
| `AppRuntime.logout(LogoutReason, boolean)` | 窗口内且 reason 是 `kicked`/`secKicked`/`forceLogout`/`suspend` |
| `AppRuntime.ntTriggerLogout(LogoutReason)` | 同上 |

窗口之外一律不动，用户自己退出、切号、票据自然过期都不受影响。`/healthz` 新增 `logout_guard`：`hooks` 是守卫 hook 数（正常 4，为 0 说明这一版没拦住）、`blocked` 是被拦下的登出次数、`log` 是最近 12 条原文。被拦下的登出落盘到 `qk_guard.log`，**故意不并进 `qk_kick.log`**——那份是看守「立刻重启」的判据，混进去会让看守反复重启 QQ。

### 一并修掉的看守 bug

同一次踢线暴露出看守记了 `restart: 服务端踢线被拦下`，QQ 主进程的 pid 却一个都没变。原因是脚本自己把 `$PREFIX/bin` 放在 PATH 最前面，而 Termux 的 `am` 是转发给 Termux:API 的脚本，以 root 跑只会打印帮助并退 1，连 `force-stop` 子命令都没有；输出被重定向丢掉，失败看起来像成功。现在系统工具优先、`am`/`monkey` 钉绝对路径、重启后核对进程是否真换了（没杀掉就 `kill -9` 兜底并记一行）。这条属于仓库脚本，0.8.9.44 之后单独提交（f139fb7），不进 APK。

### 验证

真机 QQ 9.3.60.40970：`/healthz` 报 `kick_hook: 7`、`logout_guard.hooks: 4`、`hardening: 60`，`internal/compat` 204/204，主进程 GOT 65 槽、`loop_ok=1`、`leak_*` 全 0。内核探测 39/39、读动作 28/28、写动作 11/11 无回归，JVM 单测 19 项（新增登出守卫窗口边界）。

版本号 0.8.9.45（versionCode 80），沿用同一签名密钥，支持从 0.8.9.44 直接覆盖升级。
