package com.satori.qq.xp;

/**
 * 本进程的运行环境：宿主（QQ）的 classloader，以及到 native 侧那几个 JNI 入口。
 *
 * <p>0.23.0 起模块不再带 ART hook 引擎，这里也就没有「hook 运行时」这回事了：native 只暴露
 * 一个能力——把 QQNT 的 {@code native_onSendSSOReply} 换成自己的实现（纯 {@code RegisterNatives}，
 * 不改 ArtMethod）。
 *
 * <p>状态是每进程一份：内嵌在 .so 里的 dex 由 native 在注入时各自加载，native 侧在建好
 * 这个类之后自己 {@code RegisterNatives}。
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
     * 把 QQNT 的 {@code IQQNTWrapperSession$CppProxy.native_onSendSSOReply} 换成模块自己的实现，
     * 之后每条 SSO 回包都会先交给 {@code PacketSvc.onNativeSsoReply}。幂等。
     *
     * @return 装上返回 true；QQ 那边接口变了/取不到原函数指针时返回 false（裸 SSO 相关的功能
     *         —— 合并转发、部分群管理 —— 会报不可用，其余照常）。
     */
    public static native boolean nativeInstallSsoHook(ClassLoader loader);
}
