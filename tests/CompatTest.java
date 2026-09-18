package com.satori.qq.qq;

import java.util.ArrayList;
import java.util.List;

/**
 * Compat 的匹配逻辑。真机上的类来自 QQ 的 ClassLoader，这里用等价的本地形状验证判定口径：
 * 方法按名字+参数个数匹配（含继承与接口），字段按名字匹配（含父类），回调只认 `on*`。
 */
public final class CompatTest {
    interface Callback {
        void onResult(int code, String msg);
    }

    interface PayloadCallback {
        void onResult(List<String> rows);
    }

    interface ExtraArgsCallback {
        void onResult(int code, String msg, List<String> rows, boolean done);
    }

    interface NotACallback {
        void handle(int code, String msg);
    }

    static class Base {
        public long groupCode;
        void baseOnly(int a, int b) {}
    }

    static class Derived extends Base {
        void open(int a, int b, int c) {}
        void open(int a) {}
    }

    public static void main(String[] args) {
        check(Compat.hasMethod(Derived.class, "open", 3), "3 参方法");
        check(Compat.hasMethod(Derived.class, "open", 1), "重载按个数区分");
        check(!Compat.hasMethod(Derived.class, "open", 2), "没有 2 参重载");
        check(Compat.hasMethod(Derived.class, "baseOnly", 2), "父类方法也算");
        // -1 参数个数那条走 hasMethodNamed：只要名字在就算命中（重载多、形状不固定的入口）。
        check(Compat.hasMethodNamed(Derived.class, "open"), "只按名字命中重载");
        check(Compat.hasMethodNamed(Derived.class, "baseOnly"), "只按名字命中父类方法");
        check(!Compat.hasMethodNamed(Derived.class, "renameMe"), "名字不存在则不命中");

        check(Compat.hasField(Derived.class, "groupCode"), "父类字段也算");
        check(!Compat.hasField(Derived.class, "missingField"), "不存在的字段");

        check(Compat.hasCallback(Callback.class, 2, true), "(int, String) 形状");
        check(!Compat.hasCallback(Callback.class, 3, true), "只有 2 个参数，不到 3");
        check(Compat.hasCallback(PayloadCallback.class, 1, false), "纯载荷形状");
        check(!Compat.hasCallback(PayloadCallback.class, 2, false), "纯载荷不是 2 参");
        // 内核回调常多带参数：只要求「至少」时应当命中。
        check(Compat.hasCallback(ExtraArgsCallback.class, 2, true), "4 参回调满足 >=2");
        check(!Compat.hasCallback(ExtraArgsCallback.class, 2, false), "精确 2 参不命中 4 参回调");
        check(!Compat.hasCallback(NotACallback.class, 2, true), "非 on* 不算回调");

        // 观测表：同 label 累加，三种结果分开计。
        Compat.observe("unit-test-label", "ok");
        Compat.observe("unit-test-label", "timeout");
        Compat.observe("unit-test-label", "failed");
        Compat.observe("unit-test-label", "ok");
        try {
            boolean seen = false;
            org.json.JSONArray rows = Compat.observed().getJSONArray("calls");
            for (int i = 0; i < rows.length(); i++) {
                org.json.JSONObject row = rows.getJSONObject(i);
                if (!"unit-test-label".equals(row.optString("label"))) continue;
                seen = true;
                eq(row.optLong("ok"), 2L, "ok 计数");
                eq(row.optLong("timeout"), 1L, "timeout 计数");
                eq(row.optLong("failed"), 1L, "failed 计数");
            }
            check(seen, "观测表里有刚写进去的 label");
        } catch (Exception e) {
            throw new AssertionError("observed(): " + e);
        }

        // 空 label 也不该抛。
        Compat.observe("", "ok");
        check(true, "空 label 不抛");
        System.out.println("CompatTest OK");
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }

    private static void eq(long actual, long wanted, String label) {
        if (actual != wanted) throw new AssertionError(label + ": " + actual + " != " + wanted);
    }

    private CompatTest() {}
}
