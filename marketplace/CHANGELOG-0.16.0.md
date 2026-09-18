## 0.16.0 · 知弦

### 迁移到现代 Xposed API

- 模块从旧版 `de.robv.android.xposed` 接口迁移到 libxposed API 102（`io.github.libxposed:api:102.0.0`），不再使用 `XposedBridge`、`XposedHelpers` 与 `XC_MethodHook`。
- 入口类改为继承 `XposedModule`。全模块 70 余处钩子由一层兼容层驱动，before/after 语义与旧接口一致。
- 模块注册改走 APK 内的 `META-INF/xposed/`：入口、属性与作用域分别写 `java_init.list`、`module.prop`、`scope.list`。清单里的 `xposed*` 元数据已移除，描述改用 `android:description`。
- 作用域固定为 QQ（`staticScope=true`），框架里不需要再手工添加。
- 钩子统一用 passthrough 模式。旧接口下 before 钩子注入的异常（隐藏包名、隐藏 exec 路径等）在默认的 protective 模式下会被框架吞掉，隐藏会静默失效。

升级提示：沿用包名与签名，可直接覆盖升级，已有配置不变。需要支持 libxposed API 102 的框架，LSPosed 2.x 起可用。

验证：真机 QQ 9.3.65 上接口面自检 240/240，`kick_hook` 12、`logout_guard` 5、`login_state` 2、face 3/3/1，native 补丁 fekit 37 / turingxq 21 / loop_ok 1，框架日志无钩子异常。
