package com.onebot.qq.qq;

import de.robv.android.xposed.XposedHelpers;

/** Thin reflection facade bound to QQ's ClassLoader (all QQ classes are obfuscated / off-classpath). */
public final class Ref {
    public final ClassLoader cl;
    public Ref(ClassLoader cl) { this.cl = cl; }

    public Class<?> cls(String name) { return XposedHelpers.findClass(name, cl); }
    public Class<?> clsOrNull(String name) { return XposedHelpers.findClassIfExists(name, cl); }

    public Object neu(String clsName, Object... args) { return XposedHelpers.newInstance(cls(clsName), args); }
    public Object neu(Class<?> c, Object... args) { return XposedHelpers.newInstance(c, args); }
    public Object neuTyped(String clsName, Class<?>[] types, Object[] args) { return XposedHelpers.newInstance(cls(clsName), types, args); }

    public Object call(Object o, String m, Object... args) { return XposedHelpers.callMethod(o, m, args); }
    public Object callS(String clsName, String m, Object... args) { return XposedHelpers.callStaticMethod(cls(clsName), m, args); }
    public Object callS(Class<?> c, String m, Object... args) { return XposedHelpers.callStaticMethod(c, m, args); }

    public Object get(Object o, String f) { return XposedHelpers.getObjectField(o, f); }
    public void set(Object o, String f, Object v) { XposedHelpers.setObjectField(o, f, v); }
    public Object getStatic(String clsName, String f) { return XposedHelpers.getStaticObjectField(cls(clsName), f); }

    /** A no-op proxy of the given callback interface (for fire-and-forget kernel calls). */
    public Object nullCb(String cbClass) {
        return java.lang.reflect.Proxy.newProxyInstance(cl, new Class[]{cls(cbClass)}, (p, m, a) -> null);
    }

    public static long asLong(Object o) { return o == null ? 0 : ((Number) o).longValue(); }
    public static int asInt(Object o) { return o == null ? 0 : ((Number) o).intValue(); }
    public static String asStr(Object o) { return o == null ? "" : String.valueOf(o); }
}
