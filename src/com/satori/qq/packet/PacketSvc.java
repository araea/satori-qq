package com.satori.qq.packet;

import android.os.Looper;

import com.satori.qq.L;
import com.satori.qq.qq.QQClient;
import com.satori.qq.qq.Ref;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.satori.qq.xp.Xp;

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
 * <p>Replies are correlated by the NT requestId. 0.23.0 起回包通道完全走 JNI 层：native 用
 * {@code RegisterNatives} 把 {@code IQQNTWrapperSession$CppProxy.native_onSendSSOReply} 换成
 * 自己的实现，再回调 {@link #onNativeSsoReply}。属于本模块的 requestId 在这里被消费掉，
 * native 不会把它们喂进 QQ 的原生会话（那些 id 是模块自己分配的，QQ 那边找不到）；
 * 其余回包原样转交原实现，普通 QQNT 收发不受影响。</p>
 */
public final class PacketSvc {
    private static final String KERNEL_SERVICE = "com.tencent.qqnt.kernel.api.IKernelService";
    private static final String SEND_PARAM =
            "com.tencent.qqnt.kernel.nativeinterface.SendRequestParam";

    private static final long DEFAULT_TIMEOUT_MS = 30_000L;
    private static final AtomicLong NEXT_REQUEST =
            new AtomicLong((System.currentTimeMillis() << 16) ^ System.nanoTime());

    /**
     * 模块自己发的 SSO 请求回来的失败记录。
     *
     * <p>为什么单独记这个：被服务端踢下线有两种性质完全不同的成因。一种是设备环境被检测出来，
     * 另一种是**接口层把会话打废**——QQ 客户端在若干错误码上会认定登录票据失效，然后走
     * 「刷新票据失败 → 踢回登录页」那条链：
     *
     * <ul>
     *   <li>{@code login.ntlogin.ao.f(int, String)}：错误码 140022014 / 140022015 / 140022016
     *       或 {@code refreshMethodNeedKick}；</li>
     *   <li>{@code MainService$MyErrorHandler.onUserTokenExpired}：{@code ssoErrorCode} 为
     *       -10135 或 10136。</li>
     * </ul>
     *
     * 模块自己造的 SSO 请求（OIDB 与裸 trpc）如果撞上这一组码，那「是接口调用把会话打废的、
     * 不是检测面处理不够」这个判断就成立；如果踢线发生时这里一条都没有，方向就回到服务端主动下发的
     * 强制下线。所以这里把失败逐条落盘（{@code qk_sso.log}，app 私有目录 0600），时间戳可以直接
     * 和 {@code qk_kick.log} 对。记录只是观测，不改任何行为。
     */
    private static final AtomicLong SSO_FAILURES = new AtomicLong();
    private static final AtomicLong SSO_SESSION_ERRORS = new AtomicLong();
    private static final int SSO_LOG_MAX = 12;
    private static final java.util.ArrayDeque<String> SSO_LOG = new java.util.ArrayDeque<>();

    public static long ssoFailures() { return SSO_FAILURES.get(); }
    public static long ssoSessionErrors() { return SSO_SESSION_ERRORS.get(); }

    /** 最近几次失败的原文，新的在前。 */
    public static String[] ssoLog() {
        synchronized (SSO_LOG) {
            return SSO_LOG.toArray(new String[0]);
        }
    }

    /** QQ 认「票据失效」的那一组错误码。 */
    public static boolean sessionFamilyCode(int ssoRet, int trpcRet) {
        int[] codes = {-10135, -10136, 10136, 140022014, 140022015, 140022016};
        for (int c : codes) {
            if (ssoRet == c || trpcRet == c) return true;
        }
        return false;
    }

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
    /** 回包回调是 static（native 直接调），所以待处理表和反射句柄都放静态。 */
    private static final ConcurrentHashMap<Long, Pending> pending = new ConcurrentHashMap<>();
    private static volatile Ref sref;
    private static volatile boolean ssoHookInstalled;

    public PacketSvc(QQClient qq) {
        this.qq = qq;
        this.ref = qq.ref;
        sref = qq.ref;
    }

    /**
     * 装 SSO 回包通道。幂等。
     *
     * <p>native 那边取不到 QQ 的原函数指针时（客户端换实现 / ART 用了 opaque jni id）会返回
     * false：裸 SSO 相关的功能报不可用，其余照常。
     */
    public synchronized void installHooks() {
        if (ssoHookInstalled) return;
        sref = ref;
        try {
            ssoHookInstalled = Xp.nativeInstallSsoHook(PacketSvc.class.getClassLoader());
        } catch (Throwable t) {
            L.e("PacketSvc hook install failed", t);
            return;
        }
        if (ssoHookInstalled) L.i("PacketSvc: replaced native_onSendSSOReply");
        else L.e("PacketSvc: native SSO hook unavailable; raw SSO features disabled", null);
    }

    /**
     * native 侧收到 SSO 回包时回调这里（它替换了 QQ 的 {@code native_onSendSSOReply}）。
     *
     * @return true 表示本模块已经消费掉这条回包，native 不要再转给 QQ 的原生会话——这些
     *         requestId 是模块自己分配的，喂进去 QQ 也找不到对应请求。
     */
    public static boolean onNativeSsoReply(long requestId, String ssoCmd, int resultCode,
                                           String errorMsg, Object info) {
        Pending wait = pending.remove(requestId);
        if (wait == null) return false;  // QQ 自己的请求：原样放行
        try {
            wait.result = decodeReply(requestId, wait.command, wait.subCommand, wait.oidb,
                    resultCode, errorMsg, info);
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
        return true;
    }

    public boolean isReady() {
        return ssoHookInstalled && qq.getSession() != null && qq.appRuntime() != null;
    }

    /** healthz 用：native 那边到底有没有把回包通道换掉。 */
    public static boolean ssoHookInstalled() {
        return ssoHookInstalled;
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
        Result result = dispatchInner(serviceCmd, packet, command, subCommand, oidb, timeoutMs);
        if (!result.ok()) recordSsoFailure(serviceCmd, result);
        return result;
    }

    /**
     * 记一次失败。只观测：日志走 {@code L.e}（logcat 里可见），并追加到 app 私有目录的
     * {@code qk_sso.log}（0600，超 64KB 只留尾部 32KB），因为踢线原文也是落盘的，两边要对时间。
     */
    private static void recordSsoFailure(String serviceCmd, Result r) {
        SSO_FAILURES.incrementAndGet();
        boolean session = sessionFamilyCode(r.ssoRetCode, r.trpcRetCode);
        if (session) SSO_SESSION_ERRORS.incrementAndGet();
        if (!session && r.timedOut) return; // 超时不落盘，只计数：网络慢时会是噪声
        String line = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date(System.currentTimeMillis()))
                + (session ? " SESSION-ERROR " : " failed ")
                + serviceCmd + " " + r.describe();
        line = line.replace('\n', ' ').replace('\r', ' ');
        if (line.length() > 300) line = line.substring(0, 300);
        synchronized (SSO_LOG) {
            SSO_LOG.addFirst(line);
            while (SSO_LOG.size() > SSO_LOG_MAX) SSO_LOG.removeLast();
        }
        L.e("PacketSvc: " + line, null);
        appendPrivate("/data/data/com.tencent.mobileqq/files", "qk_sso.log", line);
    }

    /** 追加一行到 app 私有目录，超 64KB 只留尾部 32KB。 */
    private static void appendPrivate(String dir, String name, String line) {
        try {
            java.io.File d = new java.io.File(dir);
            if (!d.isDirectory()) return;
            java.io.File f = new java.io.File(d, name);
            if (f.length() > 65536L) {
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw");
                byte[] tail = new byte[(int) Math.min(32768L, f.length())];
                raf.seek(f.length() - tail.length);
                raf.readFully(tail);
                raf.setLength(0);
                raf.write(tail);
                raf.close();
            }
            java.io.FileOutputStream out = new java.io.FileOutputStream(f, true);
            out.write((line + "\n").getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignore) {}
    }

    private Result dispatchInner(String serviceCmd, byte[] packet, int command, int subCommand,
                                 boolean oidb, long timeoutMs) {
        Result failure = new Result();
        failure.command = command;
        failure.subCommand = subCommand;
        if (!ssoHookInstalled) {
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
            // Private factory used by QQNT during session init; reflective call returns the exact
            // adapter wired to this KernelServiceImpl.
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

    private static Result decodeReply(long requestId, int command, int subCommand, boolean oidb,
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
        Object packet = sref.get(info, "pbBuffer");
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

    private static int intField(Object o, String field, int fallback) {
        try { return Ref.asInt(sref.get(o, field)); } catch (Throwable t) { return fallback; }
    }

    private static String strField(Object o, String field) {
        try { return Ref.asStr(sref.get(o, field)); } catch (Throwable t) { return ""; }
    }

    private static String appendError(String a, String b) {
        if (b == null || b.isEmpty()) return a == null ? "" : a;
        if (a == null || a.isEmpty()) return b;
        return a + "; " + b;
    }
}
