package com.satori.qq.core;

/**
 * Pure backoff helpers shared by the protocol layer.
 *
 * <p>Kept free of Android and QQ types so the JVM tests can pin the exact schedule. The only
 * current caller is the rich-media retry path, but the shape is deliberately generic: exponential
 * growth with a hard cap and a deadline check, which is what every reconnect path needs.
 */
public final class RetryPolicy {
    private RetryPolicy() {}

    /**
     * Exponential backoff for retry {@code attempt} (1-based): {@code base, 2*base, 4*base …},
     * clamped to {@code capMs}. Attempt 0 and a non-positive base are no delay.
     */
    public static long backoffMs(long baseMs, int attempt, long capMs) {
        if (attempt <= 0 || baseMs <= 0) return 0L;
        long cap = capMs <= 0 ? baseMs : capMs;
        long value = baseMs;
        for (int i = 1; i < attempt && value < cap; i++) {
            value <<= 1;
            if (value <= 0) return cap; // overflow
        }
        return Math.min(value, cap);
    }

    /** Whether a delay of {@code backoffMs} would spend the remaining budget before the deadline. */
    public static boolean budgetSpent(long nowMs, long backoffMs, long deadlineMs) {
        return nowMs + backoffMs >= deadlineMs;
    }
}
