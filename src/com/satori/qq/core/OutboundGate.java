package com.satori.qq.core;

import com.satori.qq.Cfg;
import com.satori.qq.qq.QQClient;
import com.satori.qq.qq.WakeLockCtl;
import org.json.JSONObject;

/**
 * 所有写动作的必经之路：等内核稳定、过限频与熔断、占着唤醒锁把活干完，再把结果记回熔断器。
 * 读动作不经过这里，媒体上传期间状态与查询照常响应。
 */
final class OutboundGate {
    /** 一次要受保护执行的写动作。 */
    interface Work {
        Object run() throws Exception;
    }

    private final Cfg cfg;
    private final QQClient qq;
    private final Identity identity;
    private final OutboundGuard guard;
    /** 本线程正在代表哪个账号发送：发送途中账号换了就中止，别把消息发到另一个号上。 */
    private final ThreadLocal<Long> account = new ThreadLocal<>();
    private volatile long onlineSinceMs;
    private volatile WakeLockCtl wakeLock;

    OutboundGate(Cfg cfg, QQClient qq, Identity identity) {
        this.cfg = cfg;
        this.qq = qq;
        this.identity = identity;
        this.guard = new OutboundGuard(cfg.outboundMinIntervalMs, cfg.outboundQueueTimeoutMs,
                cfg.outboundMaxQueued, cfg.outboundMaxPerMinute, cfg.outboundFailureThreshold,
                cfg.outboundCircuitOpenMs);
    }

    long onlineSinceMs() { return onlineSinceMs; }

    void onlineSince(long epochMs) { onlineSinceMs = epochMs; }

    WakeLockCtl wakeLock() { return wakeLock; }

    void wakeLock(WakeLockCtl lock) { wakeLock = lock; }

    JSONObject stats() { return guard.stats(); }

    /** 发送途中（转换、下载、重试退避都发生在出队之后）确认账号还是入队时的那个。 */
    void checkAccountUnchanged() {
        Long expected = account.get();
        if (expected != null && expected != identity.selfUin()) {
            throw ApiError.notFound("login changed before send");
        }
    }

    private void ensureReady() {
        if (!qq.isOnline()) throw ApiError.kernelOffline();
        long since = onlineSinceMs;
        long remaining = cfg.onlineStabilizeMs - (System.currentTimeMillis() - since);
        if (since <= 0 || remaining > 0) {
            long seconds = Math.max(1, (remaining + 999) / 1000);
            throw ApiError.stabilizing((int) Math.min(seconds, 3600));
        }
    }

    Object guarded(String method, Work work) throws Exception {
        long self = identity.selfUin();
        OutboundGuard.Lease lease = null;
        boolean ok = false;
        boolean kernelNeutral = false;
        WakeLockCtl held = wakeLock;
        // QQ's kernel uploads media inline while sendMsg runs; on a locked screen a parked CPU
        // and Wi-Fi radio make that transfer fail while plain text still rides the live socket.
        if (held != null) held.begin();
        try {
            ensureReady();
            try {
                lease = guard.acquire(method);
            } catch (OutboundGuard.BusyException busy) {
                throw ApiError.busy(busy.rateLimited, busy.slug, busy.getMessage(), busy.retryAfterSeconds);
            }
            ensureReady();
            if (self == 0 || self != identity.selfUin()) throw ApiError.notFound("login changed while waiting");
            account.set(self);
            Object data = work.run();
            ok = true;
            return data;
        } catch (Throwable t) {
            kernelNeutral = ApiError.isKernelNeutral(t);
            throw t;
        } finally {
            if (lease != null) {
                if (!ok && kernelNeutral) lease.failTransport();
                else lease.complete(ok);
            }
            account.remove();
            if (held != null) held.end();
        }
    }
}
