package com.satori.qq.core;

/** Pins the exponential schedule the protocol layer retries on. Pure, no Android runtime. */
public final class RetryPolicyTest {
    public static void main(String[] args) {
        eq(0L, RetryPolicy.backoffMs(4000L, 0, 45000L));
        eq(0L, RetryPolicy.backoffMs(0L, 3, 45000L));
        eq(4000L, RetryPolicy.backoffMs(4000L, 1, 45000L));
        eq(8000L, RetryPolicy.backoffMs(4000L, 2, 45000L));
        eq(16000L, RetryPolicy.backoffMs(4000L, 3, 45000L));
        eq(32000L, RetryPolicy.backoffMs(4000L, 4, 45000L));
        // Cap wins once the doubling passes it.
        eq(45000L, RetryPolicy.backoffMs(4000L, 5, 45000L));
        eq(45000L, RetryPolicy.backoffMs(4000L, 30, 45000L));

        yes("spends budget", RetryPolicy.budgetSpent(1000L, 5000L, 6000L));
        no("fits budget", RetryPolicy.budgetSpent(1000L, 4000L, 6000L));

        System.out.println("RetryPolicyTest OK");
    }

    private static void eq(long want, long got) {
        if (want != got) throw new AssertionError("want " + want + " got " + got);
    }

    private static void yes(String what, boolean got) {
        if (!got) throw new AssertionError("expected true: " + what);
    }

    private static void no(String what, boolean got) {
        if (got) throw new AssertionError("expected false: " + what);
    }
}
