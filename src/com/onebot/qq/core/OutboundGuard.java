package com.onebot.qq.core;

import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Serializes state-changing QQ operations and spaces them apart.
 *
 * <p>The WebSocket server has one reader thread per client, so two local clients can otherwise
 * enter QQ's kernel concurrently.  This guard deliberately applies only to writes; status and
 * lookup actions remain responsive while a media upload is in progress.</p>
 */
public final class OutboundGuard {
    private static final Set<String> MUTATIONS;
    static {
        HashSet<String> actions = new HashSet<>();
        Collections.addAll(actions,
                "send_msg", "send_group_msg", "send_private_msg",
                "send_group_forward_msg", "send_private_forward_msg", "send_forward_msg",
                "delete_msg", "set_msg_emoji_like", "send_like", "send_poke",
                "set_group_kick", "invite_group", "set_group_ban", "set_group_whole_ban",
                "set_group_card", "set_group_admin", "set_group_leave", "set_group_name",
                "set_group_special_title",
                "upload_group_file", "upload_private_file",
                "create_group_file_folder", "delete_group_folder", "delete_group_file_folder",
                "rename_group_folder", "delete_group_file", "move_group_file", "rename_group_file",
                "set_friend_add_request", "set_group_add_request");
        MUTATIONS = Collections.unmodifiableSet(actions);
    }

    private final Semaphore lane = new Semaphore(1, true);
    private final long minIntervalMs;
    private final long queueTimeoutMs;
    private final int maxQueued;
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicLong admitted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private volatile long lastFinishedAtMs;
    private volatile String lastAction = "";

    public OutboundGuard(long minIntervalMs, long queueTimeoutMs, int maxQueued) {
        this.minIntervalMs = Math.max(0, minIntervalMs);
        this.queueTimeoutMs = Math.max(1, queueTimeoutMs);
        this.maxQueued = Math.max(1, maxQueued);
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
            long remaining = minIntervalMs - (System.currentTimeMillis() - lastFinishedAtMs);
            if (remaining > 0) Thread.sleep(remaining);
            admitted.incrementAndGet();
            lastAction = action == null ? "" : action;
            return new Lease(this);
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
            return new JSONObject()
                    .put("min_interval_ms", minIntervalMs)
                    .put("queue_timeout_ms", queueTimeoutMs)
                    .put("max_queued", maxQueued)
                    .put("queued", queued.get())
                    .put("admitted", admitted.get())
                    .put("rejected", rejected.get())
                    .put("last_action", lastAction)
                    .put("last_finished_epoch_ms", lastFinishedAtMs);
        } catch (Throwable ignored) {
            return new JSONObject();
        }
    }

    private void release() {
        lastFinishedAtMs = System.currentTimeMillis();
        lane.release();
    }

    public static final class Lease implements AutoCloseable {
        private OutboundGuard owner;
        private Lease(OutboundGuard owner) { this.owner = owner; }
        @Override public void close() {
            OutboundGuard current = owner;
            if (current == null) return;
            owner = null;
            current.release();
        }
    }

    public static final class BusyException extends Exception {
        BusyException(String message) { super(message); }
    }
}
