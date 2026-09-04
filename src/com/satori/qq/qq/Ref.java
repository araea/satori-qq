package com.satori.qq.qq;

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
    public Object callTyped(Object o, String m, Class<?>[] types, Object... args) {
        return XposedHelpers.callMethod(o, m, types, args);
    }
    public Object callS(String clsName, String m, Object... args) { return XposedHelpers.callStaticMethod(cls(clsName), m, args); }
    public Object callS(Class<?> c, String m, Object... args) { return XposedHelpers.callStaticMethod(c, m, args); }

    public Object get(Object o, String f) { return XposedHelpers.getObjectField(o, f); }
    /**
     * Read an optional field without asking XposedHelpers to resolve a field that may not exist.
     * QQ occasionally removes nativeinterface fields between releases; those compatibility probes
     * must fall through instead of aborting the whole message conversion.
     */
    public Object getOrNull(Object o, String f) {
        if (o == null || f == null || f.isEmpty()) return null;
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field field = c.getDeclaredField(f);
                field.setAccessible(true);
                return field.get(o);
            } catch (NoSuchFieldException ignore) {
                // Keep walking: QQ wrappers sometimes inherit fields from a generated base class.
            } catch (Throwable ignore) {
                return null;
            }
        }
        return null;
    }
    public long getLong(Object o, String f) {
        try {
            Object v = XposedHelpers.getObjectField(o, f);
            if (v instanceof Number) return ((Number) v).longValue();
            if (v != null) {
                try { return Long.parseLong(String.valueOf(v).trim()); } catch (Exception ignore) {}
            }
        } catch (Throwable ignore) {}
        try { return XposedHelpers.getLongField(o, f); } catch (Throwable ignore) {}
        return 0;
    }
    public void set(Object o, String f, Object v) { XposedHelpers.setObjectField(o, f, v); }
    /** Public field write that accepts boxed numbers for primitive ints/longs. */
    public void put(Object o, String f, Object v) {
        try {
            java.lang.reflect.Field field = XposedHelpers.findField(o.getClass(), f);
            field.setAccessible(true);
            Class<?> t = field.getType();
            if (t == int.class) field.setInt(o, v == null ? 0 : ((Number) v).intValue());
            else if (t == long.class) field.setLong(o, v == null ? 0L : ((Number) v).longValue());
            else if (t == boolean.class) field.setBoolean(o, v instanceof Boolean ? (Boolean) v : false);
            else field.set(o, v);
        } catch (Throwable e) {
            set(o, f, v);
        }
    }
    public Object getStatic(String clsName, String f) { return XposedHelpers.getStaticObjectField(cls(clsName), f); }

    /** A no-op proxy of the given callback interface (for fire-and-forget kernel calls). */
    public Object nullCb(String cbClass) {
        return java.lang.reflect.Proxy.newProxyInstance(cl, new Class[]{cls(cbClass)}, (p, m, a) -> null);
    }

    public static long asLong(Object o) { return o == null ? 0 : ((Number) o).longValue(); }
    public static int asInt(Object o) { return o == null ? 0 : ((Number) o).intValue(); }
    public static String asStr(Object o) { return o == null ? "" : String.valueOf(o); }
    public static boolean asBool(Object o) {
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number) return ((Number) o).intValue() != 0;
        return false;
    }
}
