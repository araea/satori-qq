package com.satori.qq.xp;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Holds the framework interface the module was handed at load time.
 *
 * <p>The module is built against the modern libxposed API 102: hooks are installed through the
 * {@link XposedModule} the framework instantiates, not through a static legacy bridge. Everything
 * in {@link XposedBridge} and {@link XposedHelpers} is a thin facade on top of it so the rest of
 * the module keeps its old call shape.
 *
 * <p>State is per process (the module classloader is not shared across processes).
 */
public final class Xp {
    private static volatile XposedInterface api;

    private Xp() {}

    /** Called once from {@code Main.onModuleLoaded}. */
    public static void attach(XposedModule module) {
        api = module;
    }

    /** True once the framework has attached; false in the module's own UI process. */
    public static boolean attached() {
        return api != null;
    }

    static XposedInterface api() {
        XposedInterface a = api;
        if (a == null) throw new IllegalStateException("xposed framework not attached yet");
        return a;
    }
}
