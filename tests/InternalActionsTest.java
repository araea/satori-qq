package com.satori.qq.core;

import org.json.JSONObject;

import java.time.LocalDate;

/** Offline checks for the deterministic parts of QQ-only internal actions. */
public final class InternalActionsTest {
    public static void main(String[] args) throws Exception {
        LocalDate leapJoin = LocalDate.of(2020, 2, 29);
        eq("2024-02-29", SatoriHub.anniversaryInYear(leapJoin, 2024).toString(),
                "leap-year anniversary");
        eq("2025-02-28", SatoriHub.anniversaryInYear(leapJoin, 2025).toString(),
                "non-leap anniversary");
        eq(1_700_000_000_000L, SatoriHub.normalizeEpochMs(1_700_000_000L),
                "seconds to milliseconds");
        eq(1_700_000_000_123L, SatoriHub.normalizeEpochMs(1_700_000_000_123L),
                "milliseconds unchanged");
        JSONObject message = new JSONObject().put("user", new JSONObject().put("id", "12345"));
        eq(12345L, SatoriHub.messageUserId(message), "nested Satori user id");
        eq(0L, SatoriHub.messageUserId(new JSONObject()), "missing user id");
        System.out.println("InternalActionsTest OK");
    }

    private static void eq(long expected, long actual, String label) {
        if (expected != actual) throw new AssertionError(label + ": " + expected + " != " + actual);
    }

    private static void eq(String expected, String actual, String label) {
        if (!expected.equals(actual))
            throw new AssertionError(label + ": " + expected + " != " + actual);
    }
}
