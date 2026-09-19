package com.satori.qq;

import com.satori.qq.xp.Reflect;

/** Reflect 的实参类型推断：Class 实参必须按 Class 类型去匹配形参，不能当成类型提示。 */
public final class ReflectTest {

    private static final class Target {
        public String pick(Class<?> type, String tag) { return type.getName() + ":" + tag; }
        public String one(Class<?> type) { return type.getName(); }
        public int boxed(int v) { return v; }
    }

    public static void main(String[] args) {
        Target t = new Target();
        String got = (String) Reflect.callMethod(t, "pick", String.class, "x");
        check("java.lang.String:x".equals(got), "Class 实参按 Class 形参匹配: " + got);
        String one = (String) Reflect.callMethod(t, "one", Integer.class);
        check("java.lang.Integer".equals(one), "单参重载: " + one);
        Integer boxed = (Integer) Reflect.callMethod(t, "boxed", 7);
        check(boxed == 7, "装箱值按原始类型匹配: " + boxed);
        System.out.println("   ok   ReflectTest");
    }

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }
}
