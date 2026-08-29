04 · 桥
=======

## Hook

**为什么。** QQ 不会给你一份稳定 SDK。要在它内部的方法上接线：会话构造出来时抓住对象，探测 hook 的方法改成返回 false。

**怎么用到。** `XposedBridge.hookMethod`、`XC_MethodReplacement`。签名路径（`getSign`）不碰——那是登录用的笔，改了就写不出合法字。`getFeKitAttach` 只在 after 里数次数，不改返回值。

**去学。** before / after / replace 三种插入各做什么、对原方法有没有副作用。用玩具方法练，不要一上来 hook 支付或登录。

## 反射与 ClassLoader

**为什么。** 模块的类和 QQ 的类不在同一套默认可见性里。必须用 QQ 的 `ClassLoader` 去找 `IQQNTWrapperSession`。字段名、方法名很多是 JNI 留下的稳定字符串，也有一批每次混淆都变的 `api.*`。

**怎么用到。** `Ref.java` 是门面。一律走 `nativeinterface` 这种 JNI 名。`api.*` 禁止当入口。

**去学。** `Class.forName`、`getDeclaredMethod`、`setAccessible`。搞清楚「我的 ClassLoader」和「宿主的 ClassLoader」。动态 `Proxy` 实现一个接口（本仓库用它冒充 `IKernelMsgListener`）：漏一个抽象方法，整座桥会在运行时塌。

## 反编译

**为什么。** 没有 javadoc。版本一变，构造器参数、字段类型、回调签名都可能变。不打开 APK 核对，就是在赌。

**怎么用到。** jadx 打开 QQ 的 `classes.dex`。对照 `docs/ARCHITECTURE.md`。桌面 QQ、NapCat、鸿蒙包只能当线索，最终以当前手机这份 jadx 为准。

**去学。** jadx 的搜索、跳转到定义、看方法签名。练这个题：在一份陌生 APK 里找到「发送文本」相关的类，写出它的参数类型。不必会修加固、不必会脱壳；遇到加固再另开一门课。

换 QQ 版本时，把这一篇当检查单再走一遍。步骤在 `docs/STACK.md` 的「QQ 升版本」。
