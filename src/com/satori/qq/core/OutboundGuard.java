package com.satori.qq.core;

import org.json.JSONObject;

import java.util.Collections;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Serializes state-changing QQ operations and spaces them apart.
 *
 * <p>The HTTP server has one worker thread per client, so two local clients can otherwise
 * enter QQ's kernel concurrently.  This guard deliberately applies only to writes; status and
 * lookup methods remain responsive while a media upload is in progress.</p>
 */
public final class OutboundGuard {
    private static final Set<String> MUTATIONS;
    static {
        HashSet<String> actions = new HashSet<>();
        Collections.addAll(actions,
                "message.create", "message.delete",
                "reaction.create", "reaction.delete",
                "guild.member.kick", "guild.member.mute",
                "guild.member.role.set", "guild.member.role.unset",
                "channel.mute", "channel.update",
                "friend.approve", "friend.delete",
                "guild.approve", "guild.member.approve",
                "internal.poke", "internal.like", "internal.special_title", "internal.title_display",
                "internal.honor_display", "internal.card", "internal.sign", "internal.essence", "internal.group_remark",
                "internal.group_file", "internal.group_leave",
                "internal.dice", "internal.rps",
                "internal.qzone.publish", "internal.qzone.delete", "internal.qzone.clear",
                "internal.invite");
        MUTATIONS = Collections.unmodifiableSet(actions);
    }

    private final Semaphore lane = new Semaphore(1, true);
    private final long minIntervalMs;
    private final long queueTimeoutMs;
    private final int maxQueued;
    private final int maxPerMinute;
    private final int failureThreshold;
    private final long circuitOpenMs;
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicLong admitted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private volatile long lastFinishedAtMs;
    private volatile String lastAction = "";
    private final Object stateLock = new Object();
    private final Deque<Long> admittedAtMs = new ArrayDeque<>();
    private long succeeded;
    private long failed;
    private long rateRejected;
    private long circuitRejected;
    private long circuitOpened;
    private int consecutiveFailures;
    private long circuitOpenUntilMs;
    private boolean halfOpen;

    public OutboundGuard(long minIntervalMs, long queueTimeoutMs, int maxQueued,
            int maxPerMinute, int failureThreshold, long circuitOpenMs) {
        this.minIntervalMs = Math.max(0, minIntervalMs);
        this.queueTimeoutMs = Math.max(1, queueTimeoutMs);
        this.maxQueued = Math.max(1, maxQueued);
        this.maxPerMinute = Math.max(1, maxPerMinute);
        this.failureThreshold = Math.max(1, failureThreshold);
        this.circuitOpenMs = Math.max(1, circuitOpenMs);
    }

    public static boolean isMutation(String action) {
        return MUTATIONS.contains(action);
    }

    public Lease acquire(String action) throws BusyException, InterruptedException {
        int depth = queued.incrementAndGet();
        if (depth > maxQueued) {
            queued.decrementAndGet();
            rejected.incrementAndGet();
            throw new BusyException("outbound queue full");
        }

        boolean acquired = false;
        try {
            acquired = lane.tryAcquire(queueTimeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            queued.decrementAndGet();
        }
        if (!acquired) {
            rejected.incrementAndGet();
            throw new BusyException("outbound queue timeout");
        }

        try {
            checkBudgetAndCircuit();
            long remaining = minIntervalMs - (System.currentTimeMillis() - lastFinishedAtMs);
            if (remaining > 0) Thread.sleep(remaining);
            admitted.incrementAndGet();
            lastAction = action == null ? "" : action;
            synchronized (stateLock) {
                long now = System.currentTimeMillis();
                purgeBudget(now);
                admittedAtMs.addLast(now);
            }
            return new Lease(this);
        } catch (BusyException e) {
            lane.release();
            throw e;
        } catch (InterruptedException e) {
            lane.release();
            throw e;
        } catch (Throwable t) {
            lane.release();
            throw t;
        }
    }

    public JSONObject stats() {
        try {
            JSONObject out = new JSONObject()
                    .put("min_interval_ms", minIntervalMs)
                    .put("queue_timeout_ms", queueTimeoutMs)
                    .put("max_queued", maxQueued)
                    .put("max_per_minute", maxPerMinute)
                    .put("failure_threshold", failureThreshold)
                    .put("circuit_open_ms", circuitOpenMs)
                    .put("queued", queued.get())
                    .put("admitted", admitted.get())
                    .put("rejected", rejected.get())
                    .put("last_action", lastAction)
                    .put("last_finished_epoch_ms", lastFinishedAtMs);
            synchronized (stateLock) {
                long now = System.currentTimeMillis();
                purgeBudget(now);
                out.put("used_last_minute", admittedAtMs.size())
                        .put("succeeded", succeeded)
                        .put("failed", failed)
                        .put("rate_rejected", rateRejected)
                        .put("circuit_rejected", circuitRejected)
                        .put("circuit_opened", circuitOpened)
                        .put("consecutive_failures", consecutiveFailures)
                        .put("circuit_state", circuitState(now))
                        .put("circuit_open_until_epoch_ms", circuitOpenUntilMs);
            }
            return out;
        } catch (Throwable ignored) {
            return new JSONObject();
        }
    }

    private void checkBudgetAndCircuit() throws BusyException {
        synchronized (stateLock) {
            long now = System.currentTimeMillis();
            purgeBudget(now);
            if (circuitOpenUntilMs > now) {
                circuitRejected++;
                rejected.incrementAndGet();
                throw new BusyException("outbound circuit open; retry after "
                        + Math.max(1, (circuitOpenUntilMs - now + 999) / 1000) + "s");
            }
            if (circuitOpenUntilMs > 0) halfOpen = true;
            if (admittedAtMs.size() >= maxPerMinute) {
                long retryMs = 60000 - (now - admittedAtMs.peekFirst());
                rateRejected++;
                rejected.incrementAndGet();
                throw new BusyException("outbound rate budget exhausted; retry after "
                        + Math.max(1, (retryMs + 999) / 1000) + "s");
            }
        }
    }

    private void purgeBudget(long now) {
        while (!admittedAtMs.isEmpty() && now - admittedAtMs.peekFirst() >= 60000) {
            admittedAtMs.removeFirst();
        }
    }

    private String circuitState(long now) {
        if (circuitOpenUntilMs > now) return "open";
        if (halfOpen || circuitOpenUntilMs > 0) return "half_open";
        return "closed";
    }

    private void release(boolean success) {
        long now = System.currentTimeMillis();
        synchronized (stateLock) {
            if (success) {
                succeeded++;
                consecutiveFailures = 0;
                circuitOpenUntilMs = 0;
                halfOpen = false;
            } else {
                failed++;
                consecutiveFailures++;
                if (halfOpen || consecutiveFailures >= failureThreshold) {
                    circuitOpenUntilMs = now + circuitOpenMs;
                    halfOpen = false;
                    circuitOpened++;
                }
            }
        }
        lastFinishedAtMs = now;
        lane.release();
    }

    public static final class Lease implements AutoCloseable {
        private OutboundGuard owner;
        private Lease(OutboundGuard owner) { this.owner = owner; }
        public void complete(boolean success) {
            OutboundGuard current = owner;
            if (current == null) return;
            owner = null;
            current.release(success);
        }
        @Override public void close() {
            complete(true);
        }
    }

    public static final class BusyException extends Exception {
        BusyException(String message) { super(message); }
    }
}
