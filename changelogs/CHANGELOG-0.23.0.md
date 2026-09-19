## 0.23.0 · 知弦

### 进程内不再有任何 hook 引擎

0.22.0 把注入从 LSPosed 换成 Zygisk 之后，模块仍然自带 ART hook 引擎（LSPlant + Dobby）。
那一版功能完全正常，但 QQ 的人脸验证仍被判失败——而「只注入、什么都不做」的探针能过，说明被判的
正是 hook 引擎自己留下的痕迹。

这一版把引擎整个去掉，全部能力改走 JNI 层，**一条 ArtMethod 都不改写**：

- 引导：独立线程轮询 `ActivityThread.currentApplication()` 取宿主 classloader（原来是钩
  `Instrumentation.callApplicationOnCreate`）
- 内核会话：反射 `IKernelService.getWrapperSession()`（原来是钩会话的构造器与 getter）
- 裸 SSO 回包：`RegisterNatives` 换掉 `native_onSendSSOReply`（唯一一处，且只换入口，不改方法体）

`.so` 从 1.68 MB 降到约 440 KB，`NEEDED` 只剩 `liblog/libdl/libm/libc`，不再静态链接任何第三方库；
Zygisk API 升到 v4。真机人脸验证**通过**。

### 相应地删掉的东西

模块不再挂钩子引擎，也就不再保留那些必须靠通用 Java hook 才能成立的部分：PM 自隐
（`EnvShield`）、前台服务保活（`Keepalive`）、名片点赞（`LegacySvc`），以及整套 Xposed 形态的
类型（`XposedBridge` / `XC_MethodHook` / `HookEntry`）。反射工具改名 `Reflect`。

### 顺带修掉的问题

- **`Reflect` 把 `Class` 实参当成「形参类型提示」**：`getRuntimeService(IKernelService.class, "")`
  因此永远匹配不到方法，内核会话抓不到、所有走它的功能（内核服务、群头衔等）一直是坏的。已按
  「Class 实参就是 Class 类型」修正并加了回归测试。
- **管理页设置推送不到 QQ 进程**：`ContentResolver` 在 QQ 侧解析不到模块的 provider（Android 11+
  包可见性过滤），加 `android:forceQueryable` 解决。
- 引导时机：`currentApplication()` 在 `handleBindApplication` 中途就非空，此时 ContentProvider
  还没装，管理页设置会读到 `provider-unavailable`。现在等主线程把手柄跑完再继续。

### 验收

`/healthz` 报 `online: true`、`compat 204/204`、`config_status: applied`；群消息、私聊、走 SSO 回包的
群操作均实测可用；QQ 进程 CPU 静置约 0.7%，无异常。
