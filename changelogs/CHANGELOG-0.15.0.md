## 0.15.0 · 知弦

这一版是**适配 QQ 9.3.65（versionCode 16240，2026-09-18 04:21 自动更新）**，结论是接口面没变、
本端不用改；顺带把「升级 QQ 时该看哪里」这件事变成了自检项。

### 9.3.65 的核对结果

- **Java 相关包逐类相同**：两版 dex 里 `mobileqq/{qsec,dt,channel,kick,identification,msf,qqsec}`、
  `tencent/{turingcam,turingfd,tfd,could,qmethod,soter}` 与 `mqq/app/MainService` 的类描述符
  都是 **3404 条，无增无减**——没有新检测入口，也没有改名
- **native 只有 libfekit 变了**：其余检测库 md5 全同。新 libfekit 的导入符号 232 条逐条一致
  （GOT 槽没少）、命令表 431 条一致、字符串里没有新增检测关键字，所以 native 侧不用动
- **真机装完所有钩子照常绑**：`hardening=73`、`kick_hook=12`、`logout_guard=5`、`login_state=2`、
  `face.hooks=3/3/1`；native 自检 `patched=63 / fekit=37 / turingxq=21 / loop_ok=1`

### 新增：自检多了一张「非内核面」表

`Compat` 原来只查 `com.tencent.qqnt.kernel*` 的类型、服务、结构体字段与回调形状。但升级 QQ 时
真正静默坏掉的往往是另一半——踢线入口、检测面（QSec / ChannelProxy / dt 的 O3 派发 / MSF 收发）
与人脸核身链路（慧眼 + TuringFace）。它们坏掉的表现只是「计数从 N 掉到 N-1」，或者某个入口
再也不拦到东西。

现在这 36 个入口也进了静态自检：

- 条目形如 `{类全名, 方法名, 参数个数, 标签}`，方法名为空串表示只查类在不在，
  参数个数 `-1` 表示只查名字（重载多、形状不固定的入口）
- `/healthz` 的 `compat` 里多一个 `subsystems` 分组（`ok` / `total`），坏的条目会带着标签
  出现在 `missing` 里——下次 QQ 升级跑一次就知道哪一条要修，不用数计数猜
- 自检总数 204 → **240**

升级提示：沿用包名与签名，直接覆盖升级；装完本端会重启一次 QQ 进程（旧 dex 不会热更）。
这一版不改发送、配对与过检测的任何行为，只加了自检项与文档。
