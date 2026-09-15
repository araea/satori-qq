## 0.8.9.42

给「QQ 升级后要重新核一遍」这件事做了自动化。以前升级 QQ 之后要挨个动作打一遍，现在先跑一次自检就知道断在哪。

### 新增 `internal/compat`

用反射核对模块依赖的内核接口面，不发任何内核请求，几毫秒出结果：

- `types`：模块引用到的 128 个内核类（接口、结构体、枚举）。这张表由源码里的类名字面量生成，不是手抄的，重新生成的办法写在 `qq/Compat` 注释里
- `services`：会话上 7 个服务入口与它们必须实现的接口
- `structs`：模块会写入的 24 个结构体的字段名。字段改名是静默失败——`Ref.put` 找不到字段就退化成老路，值写不进去，服务端只回参数错误
- `callbacks`：45 个回调接口有没有 `on*` 方法。参数个数按「至少」算，因为内核回调常多带参数（`IBatchGroupFileCountCallback.onResult` 有 4 个）
- `observed`：`ExtraSvc.call` 记录的每次内核调用结果（成功/超时/其它失败），按 label 分列。哪个入口开始不回调看这里

在 9.3.60.40970 上静态自检 204/204 通过，作为干净基线；升级后 `missing` 会逐条指出断点。`force=true` 强制重算，`/healthz` 里的 `compat` 是摘要（按 QQ 版本缓存 10 分钟）。

### 检测库按库计数

`qk_env_maps_*.json` 与 `internal/status` 的 `env_report.maps` 增加 `libs`，逐库给出这一轮补了多少个 GOT 槽：本地实测 `{"fekit":37,"turingxq":21,"turingmfa":0,"msfbootV2":5,"qsec":0,"ckguard":2,"wtecdh":0}`，合计等于 `patched`。QQ 换库名或去掉某个库时，对应项会直接变 0，不用再逐槽核对。

### 修复：`voice_to_text` 真的能转出文字

这个动作从 0.8.9.38 起只回状态码，拿不到文字。两层原因：

- 记录里的元素是 `MsgElement` 外壳，真正的 `PttElement` 挂在它的一个字段上。此前按 `elementType == 4` 命中外壳就当成语音元素往下读，`duration` 一律是 0、`canConvert2Text` 一律是 false，看上去像「这段语音本来就转不了」
- 转写结果不在回调里，而且听写是异步的：`translatePtt2Text` 用 `IOperateCallback`（只有码与一句短语），这一次调用只是把任务交出去，文字稍后写回元素自己（`PttElement.text`）

现在外壳与内芯分开拿：调内核要外壳，读 `text` / `duration` / `canConvert2Text` 要内芯，内芯按**字段类型名**找而不是按字段名找——`Ref.getOrNull` 在这个字段上一路回 null（同一个对象 `toJson` 却读得到），类型名在 QQ 换代时也比字段名稳。交任务之后盯着元素看最多 3 秒（10 × 300ms）再返回，别让第一次调用的人收到空字符串。主入口一直没结果时，加 `ai: true` 会让 `translatePtt2TextAiVoice` 再试一次（它会翻成人话，所以不默认启用）。没转出字时返回 `can_convert` / `translate_status` / `duration`，把「内核还没转」和「这段本来就转不了」分开。

测试群 46360522 的 8 条真实语音全部转出文字；第一条 15 秒是冷启动听写，其余 100–240 毫秒。

### 其它

- `internal/capabilities` 登记 `compat`
- `internal/compat` 与升级检查清单写进 `README.md`、`docs/ARCHITECTURE.md`、`docs/SATORI_SUPPORT.md`
- 新增 `tests/CompatTest`（方法/字段/回调的判定口径，含「至少 N 个参数」与观测表计数），JVM 单测 18 → 19 项；`tests/mapshide-filter-test.c` 补按库归因的用例

版本号 0.8.9.42（versionCode 77），沿用同一签名密钥，支持从 0.8.9.41 直接覆盖升级。

### 验证

真机 QQ 9.3.60.40970：`internal/compat` 静态 204/204、`observed` 47 个 label；`internal-kernel-probe.js` 39/39、`ws-kernel-extras.js` 28/28 无回归。
