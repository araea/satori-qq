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
        System.out.println("StatusNoticeTest OK");
    }

    private static void eq(String want, String got) {
        if (!want.equals(got)) throw new AssertionError("want <" + want + "> got <" + got + ">");
    }
}
