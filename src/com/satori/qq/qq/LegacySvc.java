package com.satori.qq.qq;

import android.os.Bundle;

import com.satori.qq.L;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Dispatcher for QQ's legacy (pre-NT) WUP services, which are still what the Android
 * client uses for a handful of "social" actions the NT kernel never absorbed.
 *
 * <p>The profile-card like is the reason this class exists. The desktop client likes a
 * card through {@code OidbSvcTrpcTcp.0x7E5_104}, and that is what this module used to
 * send. Android QQ has no {@code NodeIKernelProfileLikeService} at all, and the server
 * gates 0x7E5_104 per appid: an Android client's request is routed to the like service
 * and then refused with {@code oidb=319 "[oidb] rule type not match appid"} for every
 * source value. The mobile client likes a card through the legacy
 * {@code VisitorSvc.ReqFavorite} WUP call instead, so a request built the PC way can
 * never succeed here no matter how the body is shaped.</p>
 *
 * <p>We therefore reproduce exactly what the client itself does — see
 * {@code com.tencent.mobileqq.app.ch#g} (encoder), {@code CardHandler#d4} (friend) and
 * {@code NearbyCardHandler#M2} (stranger): build a {@code ToServiceMsg} for
 * "mobileqq.service" with the same {@code extraData} keys and hand it to
 * {@link QQClient#appRuntime AppInterface#sendToService}. QQ's own
 * {@code MobileQQServiceBase} then encodes the {@code QQService.ReqFavorite} JCE struct,
 * signs it and supplies the mobile appid, so nothing is hand-rolled on the wire.</p>
 *
 * <p>Results are read back through {@code MobileQQServiceBase.dispatchToHandler}, whose
 * decoded payload the LBS coder has already turned into a {@code QQService.RespFavorite}
 * and stashed under the {@code "result"} attribute. Reply success is
 * {@code stHeader.iReplyCode == 0}, matching how the client's own
 * {@code com.tencent.mobileqq.app.ch#a} decides.</p>
 */
public final class LegacySvc {
    /** Legacy WUP service + function the mobile client uses to like a profile card. */
    public static final String CMD_FAVORITE = "VisitorSvc.ReqFavorite";

    /**
     * {@code ReqFavorite.emSource} the client passes when liking a card from the vote /
     * profile flow. {@code VoteHelper.j()} uses 66 for friends and for strangers alike;
     * {@code ZPlanProfileLikeManager} uses 70 for the ZPlan dress-up entry.
     */
    public static final int SOURCE_CARD = 66;
    public static final int SOURCE_ZPLAN = 70;

    private static final String TO_SERVICE_MSG = "com.tencent.qphone.base.remote.ToServiceMsg";
    private static final String SERVICE_BASE = "com.tencent.mobileqq.service.MobileQQServiceBase";

    /** The client's own coder gives up after 10s; stay just past that. */
    private static final long REPLY_TIMEOUT_MS = 12_000L;

    /** Outcome of one legacy call: transport-level and service-level result. */
    public static final class Outcome {
        public boolean success;
        public int transportCode = -1;
        /** {@code RespFavorite.stHeader.iReplyCode}; 0 means the server accepted it. */
        public int replyCode = -1;
        public String replyMessage = "";
        public boolean timedOut;

        public String describe() {
            if (timedOut) return "timeout";
            return "transport=" + transportCode + ", reply=" + replyCode
                    + (replyMessage.isEmpty() ? "" : ", message=" + replyMessage);
        }
    }

    private static final class Pending {
        final long targetUin;
        final CountDownLatch latch = new CountDownLatch(1);
        volatile int transportCode = -1;
        volatile int replyCode = -1;
        volatile String replyMessage = "";

        Pending(long targetUin) { this.targetUin = targetUin; }
    }

    private final QQClient qq;
    private final Ref ref;
    private volatile boolean hookInstalled;
    private final AtomicReference<Pending> pending = new AtomicReference<>();
    private volatile String lastOutcome = "";

    public LegacySvc(QQClient qq) {
        this.qq = qq;
        this.ref = qq.ref;
    }

    /** Install the reply observer. Safe to call more than once. */
    public synchronized void installHooks() {
        if (hookInstalled) return;
        try {
            Class<?> base = ref.clsOrNull(SERVICE_BASE);
            if (base == null) {
                L.w("LegacySvc: " + SERVICE_BASE + " missing; replies will time out");
                return;
            }
            Method dispatch = null;
            for (Method m : base.getDeclaredMethods()) {
                if (!"dispatchToHandler".equals(m.getName())) continue;
                if (m.getParameterTypes().length == 3) { dispatch = m; break; }
            }
            if (dispatch == null) throw new NoSuchMethodException("dispatchToHandler");

            dispatch.setAccessible(true);
            XposedBridge.hookMethod(dispatch, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args == null || p.args.length < 3) return;
                    onReply(p.args[1]);
                }
            });
            hookInstalled = true;
            L.i("LegacySvc: hooked MobileQQServiceBase.dispatchToHandler");
        } catch (Throwable t) {
            L.e("LegacySvc hook install failed", t);
        }
    }

    public boolean isReady() {
        return hookInstalled && qq.appRuntime() != null;
    }

    /** Diagnostic string for the most recent call, for healthz/logs. */
    public String lastOutcome() { return lastOutcome; }

    /**
     * Like {@code targetUin}'s profile card {@code count} times through the client's own
     * legacy path and wait for the server's reply.
     *
     * @throws IllegalStateException when QQ is not ready, a like is already in flight, or
     *                               the request cannot be dispatched.
     */
    public Outcome likeProfile(long targetUin, int source, int count) {
        Outcome out = new Outcome();
        if (targetUin == 0) throw new IllegalArgumentException("missing user_id");
        if (count < 1) count = 1;

        Object runtime = qq.appRuntime();
        if (runtime == null) throw new IllegalStateException("QQ AppRuntime not ready");
        long self = parseUin(qq.selfUin());
        if (self == 0) throw new IllegalStateException("current account unknown");

        Pending wait = new Pending(targetUin);
        if (!pending.compareAndSet(null, wait)) {
            throw new IllegalStateException("another like is already in flight");
        }
        try {
            Object msg = ref.neu(TO_SERVICE_MSG, "mobileqq.service", String.valueOf(self),
                    CMD_FAVORITE);
            Object extra = ref.get(msg, "extraData");
            if (!(extra instanceof Bundle)) {
                throw new IllegalStateException("ToServiceMsg.extraData unavailable");
            }
            Bundle data = (Bundle) extra;
            // Keys read back by com.tencent.mobileqq.app.ch#g when it encodes the struct.
            data.putLong("selfUin", self);
            data.putLong("targetUin", targetUin);
            data.putInt("favoriteSource", source);
            data.putInt("iCount", count);
            data.putInt("from", 1);

            ref.call(runtime, "sendToService", msg);

            if (!wait.latch.await(REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                out.timedOut = true;
                lastOutcome = out.describe();
                return out;
            }
            out.transportCode = wait.transportCode;
            out.replyCode = wait.replyCode;
            out.replyMessage = wait.replyMessage;
            out.success = wait.transportCode == 1000 && wait.replyCode == 0;
            lastOutcome = out.describe();
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.replyMessage = "interrupted";
            lastOutcome = out.describe();
            return out;
        } finally {
            pending.compareAndSet(wait, null);
        }
    }

    /**
     * Consume one reply. Runs on whichever thread MSF dispatched on.
     *
     * <p>Correlation is by target uin ({@code RespFavorite.lMID}) rather than arrival
     * order, so a like the user triggers from the UI at the same moment cannot be
     * mistaken for ours — and vice versa.</p>
     */
    private void onReply(Object fromMsg) {
        Pending wait = pending.get();
        if (wait == null || fromMsg == null) return;
        try {
            if (!CMD_FAVORITE.equals(Ref.asStr(ref.call(fromMsg, "getServiceCmd")))) return;

            Object result = null;
            Object attrs = ref.call(fromMsg, "getAttributes");
            if (attrs instanceof java.util.Map) result = ((java.util.Map<?, ?>) attrs).get("result");
            if (result == null) return;
            if (Ref.asLong(ref.get(result, "lMID")) != wait.targetUin) return;

            wait.transportCode = Ref.asInt(ref.call(fromMsg, "getResultCode"));
            Object head = ref.get(result, "stHeader");
            if (head != null) {
                wait.replyCode = Ref.asInt(ref.get(head, "iReplyCode"));
                wait.replyMessage = Ref.asStr(ref.get(head, "strResult"));
            }
            wait.latch.countDown();
        } catch (Throwable t) {
            L.e("LegacySvc.onReply", t);
        }
    }

    private static long parseUin(String uin) {
        if (uin == null) return 0;
        try { return Long.parseLong(uin.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
