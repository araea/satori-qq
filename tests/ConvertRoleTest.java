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
        System.out.println("ConvertRoleTest OK");
    }

    private static void eq(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + " got " + actual);
        }
    }
}
