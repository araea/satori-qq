package com.satori.qq.core;

/** Pure formatting logic for the resident status notification. No QQ/Android runtime touched. */
public final class StatusNoticeTest {
    public static void main(String[] args) {
        eq("0 秒", StatusNotice.humanUptime(0));
        eq("0 秒", StatusNotice.humanUptime(-5));
        eq("45 秒", StatusNotice.humanUptime(45_000L));
        eq("1 分", StatusNotice.humanUptime(60_000L));
        eq("2 小时 3 分", StatusNotice.humanUptime((2 * 3600 + 3 * 60) * 1000L));
        eq("1 天 1 小时", StatusNotice.humanUptime((25 * 3600) * 1000L));

        // 内容变了就发；内容没变但条目还在就不发（否则每秒 notify 一次）。
        yes("changed", StatusNotice.shouldPost(true, true, true, true, 10_000L, 3_000L));
        no("unchanged alive", StatusNotice.shouldPost(false, true, true, true, 10_000L, 3_000L));
        // QQ 回到前台清掉自家通知：内容没变，但条目没了，必须补发。
        yes("unchanged missing", StatusNotice.shouldPost(false, true, true, false, 10_000L, 3_000L));
        // 还没发过任何一条时不补发，等第一次 changed。
        no("nothing cached", StatusNotice.shouldPost(false, true, false, false, 10_000L, 3_000L));
        // 通知被禁用时不重发，等重新启用后由 missing 分支自愈。
        no("disabled", StatusNotice.shouldPost(false, false, true, false, 10_000L, 3_000L));
        // 同一条内容的重发有下限，避免系统反复吞掉时打成死循环。
        no("throttled", StatusNotice.shouldPost(false, true, true, false, 500L, 3_000L));
        yes("gap reached", StatusNotice.shouldPost(false, true, true, false, 3_000L, 3_000L));

        System.out.println("StatusNoticeTest OK");
    }

    private static void yes(String what, boolean got) {
        if (!got) throw new AssertionError("expected post for " + what);
    }

    private static void no(String what, boolean got) {
        if (got) throw new AssertionError("expected no post for " + what);
    }

    private static void eq(String want, String got) {
        if (!want.equals(got)) throw new AssertionError("want <" + want + "> got <" + got + ">");
    }
}
