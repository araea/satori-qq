package com.satori.qq.qq;

/** Legacy numeric and enum sender-role normalization. */
public final class ConvertRoleTest {
    private enum LegacyRole { MEMBER, ADMIN, OWNER }

    public static void main(String[] args) {
        eq("", Convert.mapRole(0), "unspecified");
        eq("", Convert.mapRole(1), "stranger");
        eq("member", Convert.mapRole(2), "member ordinal");
        eq("admin", Convert.mapRole(3), "admin ordinal");
        eq("owner", Convert.mapRole(4L), "owner ordinal");
        eq("member", Convert.mapRole(LegacyRole.MEMBER), "legacy member enum");
        eq("admin", Convert.mapRole(LegacyRole.ADMIN), "legacy admin enum");
        eq("owner", Convert.mapRole(LegacyRole.OWNER), "legacy owner enum");
        eq("[红包]", Convert.walletLabel("恭喜发财红包", "", ""), "wallet red packet");
        eq("[红包]", Convert.walletLabel(null, "", "群红包"), "wallet receiver title");
        eq("[QQ钱包消息]", Convert.walletLabel("", "转账", ""), "transfer is not red packet");
        eq("[QQ钱包消息]", Convert.walletLabel(null, null, null), "empty wallet");
        System.out.println("ConvertRoleTest OK");
    }

    private static void eq(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + " got " + actual);
        }
    }
}
