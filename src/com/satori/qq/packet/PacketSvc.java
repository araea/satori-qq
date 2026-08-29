package com.satori.qq.packet;

import android.os.Looper;

import com.satori.qq.L;
import com.satori.qq.qq.QQClient;
import com.satori.qq.qq.Ref;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Raw QQNT packet transport for QQ 9.3.50.
 *
 * <p>This deliberately reuses QQNT's own IDependsAdapter -> KernelSendObserver -> KernelServlet
 * -> MSF path. We enter through onSendSSORequest with an explicit OidbSvcTrpcTcp command string:
 * QQ 9.3.50's onSendOidbRequest incorrectly concatenates the numeric command in decimal (for
 * example 0x8FC becomes "0x2300"), which the server rejects as cmd-not-found. The normal SSO path
 * still supplies framing, account metadata and QSec signing. Calling QSec.getSign ourselves is
 * therefore unnecessary and fragile (the actual API is getSign(String, byte[]), not the
 * three-argument signature that older community notes sometimes show).</p>
 *
 * <p>Replies are correlated by the NT requestId at
 * IQQNTWrapperSession.CppProxy.onSendSSOReply(...). Replies belonging to this class are consumed
 * before they reach the native session, because their requestIds were allocated here rather than
 * by the native kernel.  All ordinary QQNT replies pass through untouched.</p>
 */
public final class PacketSvc {
    private static final String SESSION_CPP =
            "com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession$CppProxy";
    private static final String KERNEL_SERVICE = "com.tencent.qqnt.kernel.api.IKernelService";
    private static final String SEND_PARAM =
            "com.tencent.qqnt.kernel.nativeinterface.SendRequestParam";

    private static final long DEFAULT_TIMEOUT_MS = 30_000L;
    private static final AtomicLong NEXT_REQUEST =
            new AtomicLong((System.currentTimeMillis() << 16) ^ System.nanoTime());

    public static final class Result {
        public long requestId;
        public int command;
        public int subCommand;
        public int ssoRetCode = -1;
        public int trpcRetCode = -1;
        public int trpcFuncCode = -1;
        public int oidbRetCode = -1;
        public String error = "";
        /** Full OIDBSSOPkg reply produced by QQ's KernelServlet. */
        public byte[] packet = new byte[0];
        /** OIDB field 4, or an empty array if the reply did not contain one. */
        public byte[] body = new byte[0];
        public boolean timedOut;

        public boolean ok() {
            return !timedOut && ssoRetCode == 0 && trpcRetCode == 0
                    && trpcFuncCode == 0 && oidbRetCode == 0;
        }

        public String describe() {
            if (timedOut) return "timeout";
            return "sso=" + ssoRetCode + ", trpc=" + trpcRetCode + "/" + trpcFuncCode
                    + ", oidb=" + oidbRetCode + (error.isEmpty() ? "" : ", error=" + error);
        }
    }

    private static final class Pending {
        final int command;
        final int subCommand;
        final boolean oidb;
        final CountDownLatch latch = new CountDownLatch(1);
        volatile Result result;

        Pending(int command, int subCommand, boolean oidb) {
            this.command = command;
            this.subCommand = subCommand;
            this.oidb = oidb;
        }
    }

    private final QQClient qq;
    private final Ref ref;
    private final ConcurrentHashMap<Long, Pending> pending = new ConcurrentHashMap<>();
    private volatile boolean hookInstalled;

    public PacketSvc(QQClient qq) {
        this.qq = qq;
        this.ref = qq.ref;
    }

    /** Install the requestId reply hook. Safe to call more than once. */
    public synchronized void installHooks() {
        if (hookInstalled) return;
        try {
            Class<?> sessionClass = ref.cls(SESSION_CPP);
            Method reply = null;
            for (Method m : sessionClass.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("onSendSSOReply") && p.length == 5
                        && p[0] == long.class && p[1] == String.class && p[2] == int.class) {
                    reply = m;
                    break;
                }
            }
            if (reply == null) throw new NoSuchMethodException("CppProxy.onSendSSOReply(long,...)");
            reply.setAccessible(true);
            XposedBridge.hookMethod(reply, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args == null || p.args.length < 5) return;
                    long requestId = Ref.asLong(p.args[0]);
                    Pending wait = pending.remove(requestId);
                    if (wait == null) return; // QQ's own request: never interfere.
                    try {
                        // QQ 9.3.50 signature is
                        // (requestId, ssoCmd, resultCode, errorMsg, MsfRspInfo). Retain the numeric
                        // command/sub-command in Pending for callers.
                        wait.result = decodeReply(requestId, wait.command,
                                wait.subCommand, wait.oidb, Ref.asInt(p.args[2]),
                                Ref.asStr(p.args[3]), p.args[4]);
                    } catch (Throwable t) {
                        Result r = new Result();
                        r.requestId = requestId;
                        r.command = wait.command;
                        r.subCommand = wait.subCommand;
                        r.error = "reply decode failed: " + t;
                        wait.result = r;
                    } finally {
                        wait.latch.countDown();
                    }
                    // This requestId was allocated outside the native kernel. Do not feed the
                    // matching reply into its native pending-request table.
                    p.setResult(null);
                }
            });
            hookInstalled = true;
            L.i("PacketSvc: hooked QQNT onSendSSOReply");
        } catch (Throwable t) {
            L.e("PacketSvc hook install failed", t);
        }
    }

    public boolean isReady() {
        return hookInstalled && qq.getSession() != null && qq.appRuntime() != null;
    }

    public Result sendOidb(int command, int subCommand, byte[] body) {
        return sendOidb(command, subCommand, body, true, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Send with an explicit OIDB envelope isReserved (field 12). Most modern commands that carry a
     * uid in the body use isReserved=1; a few (for example 0x7E5_104 friend-like) use 0. Match the
     * reference implementation for the specific command rather than assuming.
     */
    public Result sendOidb(int command, int subCommand, byte[] body, boolean isReserved) {
        return sendOidb(command, subCommand, body, isReserved, DEFAULT_TIMEOUT_MS);
    }

    public Result sendOidb(int command, int subCommand, byte[] body, long timeoutMs) {
        return sendOidb(command, subCommand, body, true, timeoutMs);
    }

    /**
     * Send a raw OIDB body. Do not pass Pb.oidb(...) here; this method adds the envelope.
     */
    public Result sendOidb(int command, int subCommand, byte[] body, boolean isReserved,
                           long timeoutMs) {
        if (body == null) body = new byte[0];
        String serviceCmd = Pb.oidbCmd(command, subCommand);
        byte[] packet = Pb.oidb(command, subCommand, body, isReserved);
        return dispatch(serviceCmd, packet, command, subCommand, true, timeoutMs);
    }

    /**
     * Send a raw trpc SSO request whose body is NOT wrapped in an OIDB envelope (for example
     * trpc.group.long_msg_interface.MsgService.SsoSendLongMsg). The reply's pbBuffer is returned
     * verbatim in Result.body / Result.packet; there is no OIDB error code to parse, so ok() rests
     * on the SSO/trpc transport codes only.
     */
    public Result sendSso(String serviceCmd, byte[] body) {
        return sendSso(serviceCmd, body, DEFAULT_TIMEOUT_MS);
    }

    public Result sendSso(String serviceCmd, byte[] body, long timeoutMs) {
        if (body == null) body = new byte[0];
        return dispatch(serviceCmd, body, 0, 0, false, timeoutMs);
    }

    private Result dispatch(String serviceCmd, byte[] packet, int command, int subCommand,
                            boolean oidb, long timeoutMs) {
        Result failure = new Result();
        failure.command = command;
        failure.subCommand = subCommand;
        if (!hookInstalled) {
            failure.error = "PacketSvc reply hook is not installed";
            return failure;
        }
        if (qq.getSession() == null) {
            failure.error = "kernel session not ready";
            return failure;
        }
        Object runtime = qq.appRuntime();
        if (runtime == null) {
            failure.error = "QQ AppRuntime not ready";
            return failure;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            failure.error = "refusing to block QQ main thread";
            return failure;
        }

        long requestId = NEXT_REQUEST.incrementAndGet();
        failure.requestId = requestId;
        Pending wait = new Pending(command, subCommand, oidb);
        pending.put(requestId, wait);
        try {
            Object kernel = ref.call(runtime, "getRuntimeService", ref.cls(KERNEL_SERVICE), "");
            if (kernel == null) throw new IllegalStateException("IKernelService unavailable");
            // Private factory used by QQNT during session init. XposedHelpers.callMethod can invoke
            // it reflectively and returns the exact adapter wired to this KernelServiceImpl.
            Object adapter = ref.call(kernel, "getIDependsAdapter");
            if (adapter == null) throw new IllegalStateException("IDependsAdapter unavailable");

            Object param = ref.neu(SEND_PARAM);
            int timeout = (int) Math.max(1_000L, Math.min(timeoutMs, Integer.MAX_VALUE));
            ref.set(param, "sendTimeout", timeout);
            ref.set(param, "sendTimeoutOnSlowNet", timeout);
            ref.set(param, "resendNum", 0);
            ref.set(param, "sendOptions", 1); // fail fast when QQ reports no network
            ref.set(param, "reqTargetAccountType", 0);
            ref.set(param, "account", qq.selfUin());
            ref.set(param, "accountType", 0);

            // Generic SSO entry: KernelServlet/MSF still apply QQ's framing and QSec signing.
            ref.call(adapter, "onSendSSORequest", requestId, serviceCmd, packet,
                    param, "", new HashMap<String, byte[]>(), 0);

            if (!wait.latch.await(timeoutMs + 2_000L, TimeUnit.MILLISECONDS)) {
                pending.remove(requestId, wait);
                failure.timedOut = true;
                failure.error = serviceCmd + " reply timeout";
                return failure;
            }
            Result result = wait.result;
            if (result == null) {
                failure.error = serviceCmd + " reply missing";
                return failure;
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.remove(requestId, wait);
            failure.error = "interrupted while waiting for " + serviceCmd + " reply";
            return failure;
        } catch (Throwable t) {
            pending.remove(requestId, wait);
            L.e("PacketSvc send " + serviceCmd, t);
            failure.error = String.valueOf(t);
            return failure;
        }
    }

    private Result decodeReply(long requestId, int command, int subCommand, boolean oidb,
                               int callbackResult, String callbackError, Object info) {
        Result r = new Result();
        r.requestId = requestId;
        r.command = command;
        r.subCommand = subCommand;
        r.error = callbackError == null ? "" : callbackError;
        if (info == null) {
            r.error = appendError(r.error, "MsfRspInfo is null");
            return r;
        }
        r.ssoRetCode = intField(info, "ssoRetCode", callbackResult);
        r.trpcRetCode = intField(info, "trpcRetCode", -1);
        r.trpcFuncCode = intField(info, "trpcFuncCode", -1);
        String msfError = strField(info, "errorMsg");
        r.error = appendError(r.error, msfError);
        Object packet = ref.get(info, "pbBuffer");
        if (packet instanceof byte[]) r.packet = (byte[]) packet;
        if (!oidb) {
            // Raw trpc reply: hand the body back untouched, no OIDB envelope to parse.
            r.body = r.packet;
            r.oidbRetCode = 0;
            return r;
        }
        if (r.packet.length > 0) {
            try {
                Pb.Reader envelope = new Pb.Reader(r.packet);
                r.oidbRetCode = (int) envelope.num(3);
                byte[] b = envelope.bytes(4);
                if (b != null) r.body = b;
                r.error = appendError(r.error, envelope.str(5));
            } catch (Throwable t) {
                r.error = appendError(r.error, "invalid OIDB reply: " + t);
            }
        } else {
            r.oidbRetCode = 0;
        }
        return r;
    }

    private int intField(Object o, String field, int fallback) {
        try { return Ref.asInt(ref.get(o, field)); } catch (Throwable t) { return fallback; }
    }

    private String strField(Object o, String field) {
        try { return Ref.asStr(ref.get(o, field)); } catch (Throwable t) { return ""; }
    }

    private static String appendError(String a, String b) {
        if (b == null || b.isEmpty()) return a == null ? "" : a;
        if (a == null || a.isEmpty()) return b;
        return a + "; " + b;
    }
}
