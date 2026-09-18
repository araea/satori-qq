package com.satori.qq.xp;

import java.lang.reflect.Member;

/**
 * 本进程的 hook 运行时：宿主（QQ）的 classloader + 到 native 引擎（LSPlant）的入口。
 *
 * <p>以前的版本这里持的是 libxposed 框架给的 {@code XposedModule}。改走 Zygisk 之后框架不在场
 * （QQ 会把 LSPosed 模块判成环境异常，实测连零钩子的也失败），引擎由模块自带的 .so 提供。
 * native 侧只认两件事：被钩成员的反射对象、以及一个持有回调方法的普通 Java 对象。
 *
 * <p>状态是每进程一份：内嵌在 .so 里的 dex 由 native 在注入时各自加载。
 */
public final class Xp {

    private static volatile ClassLoader hostLoader;
    private static volatile String processName;
    private static volatile boolean ready;

    private Xp() {}

    /** 由 {@code Boot} 在拿到 QQ 的 Application 之后调用一次。 */
    public static void attach(ClassLoader host, String process) {
        hostLoader = host;
        processName = process;
        ready = true;
    }

    /** 框架不在场时（模块自己的 UI 进程）为 false。 */
    public static boolean attached() {
        return ready;
    }

    /** 宿主 classloader：查 QQ 的混淆类时用它。 */
    public static ClassLoader host() {
        return hostLoader;
    }

    public static String process() {
        return processName;
    }

    // 注意：这里没有 System.loadLibrary。模块的 .so 是 Zygisk Next 从模块目录直接加载的，
    // 不在应用的库搜索路径里；native 侧在建好这个类之后自己 RegisterNatives。

    /**
     * 把 {@code target} 换成 {@code hooker} 的 {@code dispatch(Object[])}，返回备份成员
     * （反射调用它等于调用原实现）。失败返回 null。
     */
    static native Member nativeHook(Member target, Object hooker);

    /** 撤销一次 hook；{@code target} 必须是当初传进去的那个成员。 */
    static native boolean nativeUnhook(Member target);
}
