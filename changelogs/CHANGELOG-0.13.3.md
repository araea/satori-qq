## 0.13.3 · 知弦

上一版（0.13.2）只加观测点，发出去之后做了三路对抗核验，**核验在已上线的代码里找出一处错的归因字段**，这版修掉它，外带两处口径问题。仍然只有观测，不碰 QQ 行为。

### `by=` 之前是错的（这版的主要改动）

0.13.2 里 `kept-login-state` 行的 `by=` 只做栈扫描，想着「取第一帧外部调用者」。实际跑不到 QQ 的调用点：Xposed 旧式钩子里，回调上方先经过框架自己的分派帧，而 Vector 的 dex 里那一层是**单段混淆类**（`g.a` 这种），过滤表里没有它，也没有 `org.matrix.vector.*`。于是每一次命中都写出同一个与调用点无关的混淆帧 —— 长得像答案，其实是常量。比不写更坏，因为「账号是谁摘掉的」正是决定下一步要不要拦 `expired` 那条路的依据。

改成**入口打标记**：

- `onUserTokenExpired` 的钩子在 `before` 写 `token-expired`，`logoutWhenReqUidFail` 的钩子写 `uid-fail`，`after` 清除（两条路径都是同线程内联调 `updateSimpleAccount*`，线程标记足够）
- `by=` 读这个标记，取不到写 `by=-`；另加 `frames=` 做栈兜底（跳过单段类名与 `org.matrix.vector.*`，最多三帧），留给还没点名的第三方调用点

### 另外两处

- `allowed-logout` 会把一次 `gray` 记两行：6 参 `popupNotification` 是无条件转发给 8 参的纯转发器，两个重载都挂了钩。现在只在 8 参那个重载上计数
- `count=0` 有两种意思（没发生过 / 钩子没挂上），分不出来。`/healthz` 的 `token_expired` 与 `allowed_logout` 各加一个 `hooks` 字段，钩子挂载失败也从 `L.w`（verbose 关着时静默）改成 `L.e`

### 顺带

- `kept-login-state` 行尾部的时间锚点不再只写内存那一份：内存被重启清掉时用 `qk_kick.log` 的 mtime，写成 `(kick marker <N>s ago)`。之前重启之后每一行都是 `(last kick  -)`，而重启之后正是最需要看出「这行在不在善后期里」的时候

升级提示：沿用包名与签名，直接覆盖升级，装完重启一次 QQ。
