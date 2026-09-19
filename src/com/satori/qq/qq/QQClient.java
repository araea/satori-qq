package com.satori.qq.qq;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import com.satori.qq.L;
import com.satori.qq.packet.PacketSvc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.charset.StandardCharsets;

/** Bridge into the Android QQ NT kernel: capture session, send, register listeners and identity. */
public final class QQClient {
    // ---- QQ 9.3.50 class names, reverified on 9.3.60 ----
    public static final String SESSION_CPP     = "com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession$CppProxy";
    public static final String MSG_LISTENER    = "com.tencent.qqnt.kernel.nativeinterface.IKernelMsgListener";
    public static final String CONTACT         = "com.tencent.qqnt.kernelpublic.nativeinterface.Contact";
    public static final String MSG_ELEMENT     = "com.tencent.qqnt.kernel.nativeinterface.MsgElement";
    public static final String TEXT_ELEMENT    = "com.tencent.qqnt.kernel.nativeinterface.TextElement";
    public static final String IOPERATE_CB     = "com.tencent.qqnt.kernel.nativeinterface.IOperateCallback";
    public static final String MOBILEQQ        = "mqq.app.MobileQQ";
    public static final String GROUP_LISTENER  = "com.tencent.qqnt.kernel.nativeinterface.IKernelGroupListener";
    public static final String BUDDY_LISTENER  = "com.tencent.qqnt.kernel.nativeinterface.IKernelBuddyListener";
    public static final String MEMBER_LIST_CB  = "com.tencent.qqnt.kernel.nativeinterface.IGroupMemberListCallback";
    public static final String MEMBER_EXT_CB   = "com.tencent.qqnt.kernel.nativeinterface.IGroupMemberExtCallback";
    public static final String OPERATE_CB      = "com.tencent.qqnt.kernel.nativeinterface.IOperateCallback";
    public static final String KICK_CB         = "com.tencent.qqnt.kernel.nativeinterface.IKickMemberOperateCallback";
    public static final String SHUTUP_INFO     = "com.tencent.qqnt.kernel.nativeinterface.GroupMemberShutUpInfo";
    public static final String MEMBER_ROLE     = "com.tencent.qqnt.kernelpublic.nativeinterface.MemberRole";
    public static final String BUDDY_REQ_TYPE  = "com.tencent.qqnt.kernel.nativeinterface.BuddyListReqType";
    public static final String MSG_OPERATE_CB  = "com.tencent.qqnt.kernel.nativeinterface.IMsgOperateCallback";
    public static final String AIO_FIRST_CB    = "com.tencent.qqnt.kernel.nativeinterface.IGetAioFirstViewLatestMsgCallback";
    public static final String RICH_MEDIA_GET_REQ = "com.tencent.qqnt.kernel.nativeinterface.RichMediaElementGetReq";
    public static final String VIDEO_URL_CB = "com.tencent.qqnt.kernel.nativeinterface.IVideoPlayUrlCallback";
    public static final String VIDEO_CODEC = "com.tencent.qqnt.kernel.nativeinterface.VideoCodecFormatType";
    public static final String RM_EX_PARAMS = "com.tencent.qqnt.kernel.nativeinterface.RMReqExParams";

    public static final int CT_C2C = 1;
    public static final int CT_GROUP = 2;

    public interface Listener {
        void onRecvMsgs(List<?> msgRecords);
        /** Client-side send callback; group self messages often finish via onMsgUpdates. */
        void onAddSendMsg(Object msgRecord);
        void onMsgUpdates(List<?> msgRecords);
        void onRecall(int type, String info, long time);
        void onBuddyReq(Object buddyReqInfo);
        void onGroupNotifies(List<?> notifies);
        void onMemberListChange(Object change);
        void onGroupListUpdate(Object updateType, List<?> groups);
    }

    public final Ref ref;
    private final PacketSvc packetSvc;
    private final ExtraSvc extraSvc;
    private volatile Object session;        // IQQNTWrapperSession
    private volatile boolean listenerRegistered;
    private volatile Object listenerSession;
    private volatile Listener listener;
    private volatile String selfUin = "";
    private volatile String selfNick = "";
    private final boolean mainProcess;
    private final java.util.concurrent.ConcurrentHashMap<String, MediaDownload> mediaDownloads =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.Map<String, Object>>
            groupMemberCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<Long> groupMemberWarmups =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final class MediaDownload {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile String path = "";
        volatile long errorCode = -1;
        volatile String errorMessage = "";
    }

    public QQClient(ClassLoader cl, boolean mainProcess) {
        this.ref = new Ref(cl);
        this.mainProcess = mainProcess;
        this.packetSvc = new PacketSvc(this);
        this.extraSvc = new ExtraSvc(this);
    }

    public void setListener(Listener l) { this.listener = l; }

    /** Install hooks that capture the live kernel session as soon as QQ creates it. */
    /**
     * 接上内核会话与回包通道。幂等。
     *
     * <p>会话不再靠钩构造器拿：{@code IKernelService.getWrapperSession()} 本来就是公开的接口
     * 方法，反射直接问就行。QQ 建会话比注入晚，所以起一个轮询线程，拿到就停。
     *
     * <p>回包通道是 native 侧的 {@code RegisterNatives}（见 {@link com.satori.qq.xp.Xp} 与
     * {@link PacketSvc}）。
     */
    public void installHooks() {
        packetSvc.installHooks();
        startSessionPoll();
    }

    private static final String KERNEL_SERVICE = "com.tencent.qqnt.kernel.api.IKernelService";
    private volatile boolean sessionPollStarted;
    private volatile String sessionProbe = "not-attempted";

    /**
     * 轮询内核会话。拿到之前 1 秒一次，拿到之后降到 10 秒一次——重登/切号时 QQ 会把会话换掉，
     * 换了要重新注册监听器。
     */
    private synchronized void startSessionPoll() {
        if (sessionPollStarted) return;
        sessionPollStarted = true;
        Thread t = new Thread(() -> {
            for (;;) {
                Object s = peekSession();
                if (s != null && s != session) onSession(s);
                // 会话在了但回包通道还没装上（QQ 的原生库可能比会话晚一步注册），继续试。
                if (session != null && !PacketSvc.ssoHookInstalled()) packetSvc.installHooks();
                try {
                    Thread.sleep(session == null ? 1000L : 10000L);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "pool-7-thread-1");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 反射问一次内核会话；任何一步拿不到都返回 null，并把卡在哪一步记进 {@link #sessionProbe}
     * （healthz 的 session 段直接看得到，不用翻 logcat）。
     */
    /**
     * 反射问一次内核会话。
     *
     * <p>不写死某一条反射路径：QQ 9.3.65 上 {@code AppRuntime.getRuntimeService(Class, String)}
     * 已经不存在了（2026-09-19 实测 NoSuchMethodError）。所以按「先从 runtime 要 session，再要
     * kernel service，再从 service 要 session」三段走，每段都先试名字，名字对不上就扫类上无参、
     * 返回类型名匹配的方法。任何一步失败都把现场记进 {@link #sessionProbe}。
     */
    private Object peekSession() {
        StringBuilder log = new StringBuilder();

        Object runtime = appRuntime();
        if (runtime == null) {
            log.append("runtime=null;");
            Object mobileqq;
            try {
                mobileqq = ref.callS(MOBILEQQ, "getMobileQQ");
            } catch (Throwable t) {
                sessionProbe = "getMobileQQ: " + t;
                return null;
            }
            if (mobileqq == null) { sessionProbe = "getMobileQQ=null"; return null; }
            try { runtime = ref.call(mobileqq, "peekAppRuntime"); }
            catch (Throwable t) { log.append("peekAppRuntime:").append(t).append(';'); }
            if (runtime == null) {
                try { runtime = ref.get(mobileqq, "mAppRuntime"); }
                catch (Throwable t) { log.append("mAppRuntime:").append(t).append(';'); }
            }
            if (runtime == null) {
                sessionProbe = log + " on " + mobileqq.getClass().getName();
                return null;
            }
        }

        // 1) runtime 自己就可能是 session 的持有者
        Object direct = scanNoArgReturning(runtime, "IQQNTWrapperSession");
        if (direct != null) { sessionProbe = "ok/runtime." + scanHit; return direct; }

        // 2) 要 kernel service：先按名字，再扫
        Object kernel = null;
        Throwable namedFailure = null;
        try {
            kernel = ref.call(runtime, "getRuntimeService", ref.cls(KERNEL_SERVICE), "");
        } catch (Throwable t) {
            namedFailure = t;
            try { kernel = ref.call(runtime, "getRuntimeService", ref.cls(KERNEL_SERVICE)); }
            catch (Throwable ignored) { }
        }
        if (kernel == null) kernel = scanNoArgReturning(runtime, "IKernelService");
        if (kernel == null) {
            sessionProbe = log + "no-kernel-service(" + runtime.getClass().getName() + ")"
                    + (namedFailure == null ? "" : " getRuntimeService:" + namedFailure)
                    + " | candidates=" + describeMethods(runtime.getClass(), "runtimeservice");
            return null;
        }

        // 3) 从 service 要 session
        try {
            Object s = ref.call(kernel, "getWrapperSession");
            if (s != null) { sessionProbe = "ok/getWrapperSession"; return s; }
        } catch (Throwable t) {
            log.append("getWrapperSession:").append(t).append(';');
        }
        Object scanned = scanNoArgReturning(kernel, "IQQNTWrapperSession");
        if (scanned != null) { sessionProbe = "ok/service." + scanHit; return scanned; }
        sessionProbe = log + "no-session on " + kernel.getClass().getName()
                + " | candidates=" + describeMethods(kernel.getClass(), "session");
        return null;
    }

    private volatile String scanHit = "";

    /** 扫一个类（含父类）上「无参 + 返回类型名以 suffix 结尾」的方法，返回第一个非 null 结果。 */
    private Object scanNoArgReturning(Object target, String suffix) {
        scanHit = "";
        StringBuilder errors = new StringBuilder();
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (!m.getReturnType().getName().endsWith(suffix)) continue;
                try {
                    m.setAccessible(true);
                    Object v = m.invoke(target);
                    if (v != null) { scanHit = m.getName(); return v; }
                } catch (Throwable t) {
                    if (errors.length() < 200) errors.append(m.getName()).append(':')
                            .append(t.getClass().getSimpleName()).append(' ');
                }
            }
        }
        scanHit = "scan-empty" + (errors.length() == 0 ? "" : "[" + errors + "]");
        return null;
    }

    /** 把一个类（含父类）上名字含 {@code filter} 的方法签名拼成一行，给 healthz 看。 */
    private static String describeMethods(Class<?> cls, String filter) {
        StringBuilder b = new StringBuilder();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (!m.getName().toLowerCase().contains(filter)) continue;
                if (b.length() > 400) { b.append("..."); return b.toString(); }
                b.append(m.getName()).append('(');
                Class<?>[] p = m.getParameterTypes();
                for (int i = 0; i < p.length; i++) {
                    if (i > 0) b.append(',');
                    b.append(p[i].getSimpleName());
                }
                b.append(")->").append(m.getReturnType().getSimpleName()).append(' ');
            }
        }
        return b.length() == 0 ? "none" : b.toString();
    }

    private volatile boolean listenerPollerStarted;

    private synchronized void onSession(Object s) {
        if (s == null) return;
        if (session != s) {
            listenerRegistered = false;
            listenerSession = null;
            groupListenerRegistered = false;
            groupListenerSession = null;
            buddyListenerRegistered = false;
            buddyListenerSession = null;
            groupInfoCache.clear();
        }
        session = s;
        L.d("Captured wrapper session: " + s.getClass().getName());
        // SSO 回包通道要等到这里才装：QQ 的原生库是建会话时才注册 native_onSendSSOReply 的，
        // 早于那一步去 RegisterNatives，换掉的是 ART 的解析存根，转交原实现会变成互相递归
        // （2026-09-19 实测：把 QQ 打满几个核）。installHooks 幂等，装上了就不会再试。
        if (mainProcess) {
            packetSvc.installHooks();
            ensureListenerAsync();
        }
    }

    /** msgService may not be ready the instant the session is created; poll until it is. */
    private synchronized void ensureListenerAsync() {
        if (listenerRegistered || listenerPollerStarted) return;
        listenerPollerStarted = true;
        Thread t = new Thread(() -> {
            try {
                while (!listenerRegistered || listenerSession != session) {
                    tryRegisterListener();
                    if (listenerRegistered && listenerSession == session) break;
                    try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                }
            } finally {
                boolean restart;
                synchronized (QQClient.this) {
                    listenerPollerStarted = false;
                    restart = session != null && (!listenerRegistered || listenerSession != session);
                }
                // Close the narrow race where a replacement session arrived after the loop
                // decided it was done but before listenerPollerStarted was cleared.
                if (restart) ensureListenerAsync();
            }
        }, "pool-6-thread-1");
        t.setDaemon(true);
        t.start();
    }

    public Object getSession() { return session; }

    /** healthz 用：会话抓到没有、监听器挂上没有、没抓到的话卡在哪一步。 */
    public String sessionDiag() {
        Object s = session;
        if (s == null) return "no-session [" + sessionProbe + "]";
        boolean listening = listenerRegistered && listenerSession == s;
        return (listening ? "ready" : "session-no-listener") + "/" + s.getClass().getSimpleName()
                + " [" + sessionProbe + "]";
    }
    public PacketSvc packets() { return packetSvc; }
    public ExtraSvc extra() { return extraSvc; }
    /**
     * Current QQ AppRuntime, or null while logged out / before account startup.
     *
     * <p>先问 {@code peekAppRuntime()}，问不到才退回 {@code mAppRuntime} 字段。实测（2026-09-16）
     * 这个字段在重登/切号期间会指着上一个对象：拿它调 {@code logout()} 会静默空转，因为
     * {@code logout()} 先看自己那份 {@code isLogin}，指错了就什么都不做。
     */
    public Object appRuntime() {
        try {
            Object app = ref.callS(MOBILEQQ, "getMobileQQ");
            if (app == null) return null;
            try {
                Object peeked = ref.call(app, "peekAppRuntime");
                if (peeked != null) return peeked;
            } catch (Throwable ignore) {}
            return ref.get(app, "mAppRuntime");
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code AppRuntime.isLogin()}；取不到运行时或调不通时回 null，调用方按「别据此下结论」处理。 */
    public Boolean appRuntimeIsLogin() {
        Object rt = appRuntime();
        if (rt == null) return null;
        try {
            java.lang.reflect.Method m = rt.getClass().getMethod("isLogin");
            m.setAccessible(true);
            Object v = m.invoke(rt);
            return v instanceof Boolean ? (Boolean) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public Object getMsgService() {
        Object s = session;
        return getMsgService(s);
    }
    private Object getMsgService(Object s) {
        return getMsgService(s, true);
    }
    private Object getMsgService(Object s, boolean logError) {
        if (s == null) return null;
        try { return ref.call(s, "getMsgService"); }
        catch (Throwable t) { if (logError) L.e("getMsgService", t); return null; }
    }
    public Object getGroupService() {
        Object s = session; if (s == null) return null;
        try { return ref.call(s, "getGroupService"); } catch (Throwable t) { return null; }
    }
    public Object getProfileService() {
        Object s = session; if (s == null) return null;
        try { return ref.call(s, "getProfileService"); } catch (Throwable t) { return null; }
    }
    public Object getBuddyService() {
        Object s = session; if (s == null) return null;
        try { return ref.call(s, "getBuddyService"); } catch (Throwable t) { return null; }
    }
    public Object getRichMediaService() {
        Object s = session; if (s == null) return null;
        try { return ref.call(s, "getRichMediaService"); } catch (Throwable t) { return null; }
    }
    public Object getRecentContactService() {
        Object s = session; if (s == null) return null;
        try { return ref.call(s, "getRecentContactService"); } catch (Throwable t) { return null; }
    }

    private synchronized void tryRegisterListener() {
        Object targetSession = session;
        if (targetSession == null) return;
        if (listenerRegistered && listenerSession == targetSession) return;
        Object msgService = getMsgService(targetSession);
        if (msgService == null) return;
        try {
            Class<?> li = ref.cls(MSG_LISTENER);
            Object proxy = Proxy.newProxyInstance(ref.cl, new Class[]{li}, new ListenerHandler());
            ref.call(msgService, "addKernelMsgListener", proxy);
            if (session != targetSession) return;
            listenerSession = targetSession;
            listenerRegistered = true;
            L.i("Registered IKernelMsgListener (receiving messages)");
            tryRegisterGroupListener();
            tryRegisterBuddyListener();
            refreshPendingRequestsAsync();
        } catch (Throwable t) {
            L.e("Failed to register msg listener", t);
        }
    }

    private final class ListenerHandler implements InvocationHandler {
        @Override public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
            String name = m.getName();
            try {
                if ("onRichMediaDownloadComplete".equals(name) && args != null && args.length > 0) {
                    onRichMediaDownloadComplete(args[0]);
                    return def(m);
                }
                Listener l = listener;
                if (l == null) return def(m);
                switch (name) {
                    case "onRecvMsg":
                    case "onRecvActiveMsg":
                    case "onMsgInfoListAdd":
                    case "onRecvOnlineFileMsg":
                        if (args != null && args.length >= 1 && args[0] instanceof List)
                            l.onRecvMsgs((List<?>) args[0]);
                        break;
                    case "onMsgInfoListUpdate":
                        if (args != null && args.length >= 1 && args[0] instanceof List)
                            l.onMsgUpdates((List<?>) args[0]);
                        break;
                    case "onAddSendMsg":
                        if (args != null && args.length >= 1 && args[0] != null)
                            l.onAddSendMsg(args[0]);
                        break;
                    case "onMsgRecall":
                        if (args != null && args.length >= 3)
                            l.onRecall(Ref.asInt(args[0]), Ref.asStr(args[1]), Ref.asLong(args[2]));
                        break;
                    default: break;
                }
            } catch (Throwable t) {
                L.e("listener." + name, t);
            }
            return def(m);
        }
        private Object def(java.lang.reflect.Method m) {
            Class<?> r = m.getReturnType();
            if (!r.isPrimitive()) return null;
            if (r == boolean.class) return false;
            if (r == void.class) return null;
            if (r == long.class) return 0L;
            if (r == int.class) return 0;
            if (r == double.class) return 0d;
            if (r == float.class) return 0f;
            if (r == short.class) return (short) 0;
            if (r == byte.class) return (byte) 0;
            if (r == char.class) return (char) 0;
            return null;
        }
    }

    private static String mediaDownloadKey(long msgId, long elementId) {
        return msgId + ":" + elementId;
    }

    private void onRichMediaDownloadComplete(Object info) {
        if (info == null) return;
        try {
            long msgId = Ref.asLong(ref.get(info, "msgId"));
            long elementId = Ref.asLong(ref.get(info, "msgElementId"));
            MediaDownload pending = mediaDownloads.get(mediaDownloadKey(msgId, elementId));
            if (pending == null) return;
            pending.path = Ref.asStr(ref.get(info, "filePath"));
            pending.errorCode = Ref.asLong(ref.get(info, "fileErrCode"));
            pending.errorMessage = Ref.asStr(ref.get(info, "fileErrMsg"));
            pending.latch.countDown();
        } catch (Throwable t) {
            L.e("rich media completion", t);
        }
    }

    // ---------- identity ----------
    public String selfUin() {
        String current = currentUin();
        return current.isEmpty() ? selfUin : current;
    }

    /** Strict current account probe. Unlike selfUin(), this never falls back to a cached account. */
    private String currentUin() {
        try {
            Object rt = appRuntime();
            if (rt != null) {
                String u = tryStr(rt, "getCurrentUin");
                if (u == null || u.isEmpty()) u = tryStr(rt, "getAccount");
                if (u == null) return "";
                u = u.trim();
                if (u.isEmpty() || "0".equals(u)) return "";
                if (!u.equals(selfUin)) { selfUin = u; selfNick = ""; groupInfoCache.clear(); }
                String nk = tryStr(rt, "getCurrentNickname");
                if (nk != null && !nk.isEmpty()) selfNick = nk;
                return u;
            }
        } catch (Throwable t) { L.e("currentUin", t); }
        return "";
    }
    public String selfNick() { currentUin(); return selfNick; }

    /** Installed host QQ version, read dynamically so get_version_info survives client upgrades. */
    public String qqVersion() {
        try {
            Object app = ref.callS(MOBILEQQ, "getMobileQQ");
            if (app instanceof Context) {
                android.content.pm.PackageInfo info = ((Context) app).getPackageManager()
                        .getPackageInfo("com.tencent.mobileqq", 0);
                if (info != null && info.versionName != null) return info.versionName;
            }
        } catch (Throwable t) {
            L.e("qqVersion", t);
        }
        return "";
    }

    /** QQ's own Application as a Context, for posting notifications under QQ's identity. */
    public android.content.Context appContext() {
        try {
            Object app = ref.callS(MOBILEQQ, "getMobileQQ");
            if (app instanceof Context) return (Context) app;
        } catch (Throwable t) { L.e("appContext", t); }
        return null;
    }

    /** True only when the current account, NT session, message service and receive listener are ready. */
    public boolean isOnline() {
        Object s = session;
        if (s == null || !listenerRegistered || listenerSession != s) return false;
        if (getMsgService(s, false) == null) return false;
        if (loginActivityOnTop()) return false;
        if (currentUin().isEmpty()) return false;
        // AppRuntime 已经登出时，内核 session 对象短期还是活的、getCurrentUin() 也还回得出号，
        // 但收发已经不动了（2026-09-16 实测：`logout()` 之后群消息立刻停止入库，而这条判据
        // 当时还回 true）。不把这一条算进来的话，/healthz 会一直报 online=true、上游连接也没断，
        // 看守三条判据一条都不会触发，只能等人发现。
        // 取不到运行时或调不通 isLogin 时不下结论（fail-open）：宁可晚重启一次，也不要因为
        // 一次反射失败把 QQ 反复重启。
        Boolean loggedIn = appRuntimeIsLogin();
        return loggedIn == null || loggedIn;
    }

    /** Own-task inspection needs no cross-app task permission and catches stale sessions on LoginActivity. */
    private boolean loginActivityOnTop() {
        try {
            Object app = ref.callS(MOBILEQQ, "getMobileQQ");
            if (!(app instanceof Context)) return false;
            ActivityManager am = (ActivityManager) ((Context) app).getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            for (ActivityManager.AppTask task : am.getAppTasks()) {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                ComponentName top = info == null ? null : info.topActivity;
                if (top != null && "com.tencent.mobileqq.activity.LoginActivity".equals(top.getClassName()))
                    return true;
            }
        } catch (Throwable ignore) {}
        return false;
    }
    private String tryStr(Object o, String m) { try { return Ref.asStr(ref.call(o, m)); } catch (Throwable t) { return null; } }

    // ---------- VAS bubble / font on outbound send ----------
    private static final String MSG_ATTR_INFO = "com.tencent.qqnt.kernel.nativeinterface.MsgAttributeInfo";
    private static final String VAS_MSG_ELEMENT = "com.tencent.qqnt.kernel.nativeinterface.VASMsgElement";
    private static final String VAS_MSG_BUBBLE = "com.tencent.qqnt.kernel.nativeinterface.VASMsgBubble";
    private static final String VAS_MSG_FONT = "com.tencent.qqnt.kernel.nativeinterface.VASMsgFont";

    private static final class VasStyle {
        int bubbleId, bubbleDiyTextId, subBubbleId, fontId, magicFontType;
        boolean any() { return bubbleId > 0 || bubbleDiyTextId > 0 || subBubbleId > 0 || fontId != 0; }
    }

    /** Read the logged-in account's current AIO bubble + font selection. */
    public VasStyle currentVasStyle() {
        VasStyle s = new VasStyle();
        String uin = selfUin;
        if (uin == null || uin.isEmpty()) return s;
        try {
            Object vasApi = qrouteApi("com.tencent.mobileqq.vas.api.IVasAioData");
            if (vasApi != null) {
                Object data = ref.call(vasApi, "getAioVasMsgData", uin);
                if (data != null) {
                    s.bubbleId = Ref.asInt(ref.call(data, "getBubbleId"));
                    s.bubbleDiyTextId = Ref.asInt(ref.call(data, "getBubbleDiyTextId"));
                    s.subBubbleId = Ref.asInt(ref.call(data, "getSubBubbleId"));
                    s.fontId = Ref.asInt(ref.call(data, "getFontId"));
                    s.magicFontType = Ref.asInt(ref.call(data, "getMagicFontType"));
                    if (s.any()) return s;
                }
            }
        } catch (Throwable t) {
            L.e("currentVasStyle aio", t);
        }
        try {
            Object handler = svipHandler();
            if (handler == null) return s;
            s.bubbleId = Ref.asInt(ref.call(handler, "getSelfBubbleId"));
            s.bubbleDiyTextId = Ref.asInt(ref.call(handler, "getSelfBubbleDiyTextId"));
            s.subBubbleId = Ref.asInt(ref.call(handler, "getSubBubbleId"));
            Object fontInfo = ref.call(handler, "getSelfFontInfo");
            if (fontInfo != null) {
                long vipFont = ref.getLong(fontInfo, "H");
                int fontType = Ref.asInt(ref.get(fontInfo, "I"));
                int magicFont = Ref.asInt(ref.get(fontInfo, "J"));
                s.magicFontType = Ref.asInt(ref.get(fontInfo, "K"));
                s.fontId = packNtFontId(vipFont, fontType, magicFont);
            } else {
                s.fontId = Ref.asInt(ref.call(handler, "getSelfFontId"));
            }
        } catch (Throwable t) {
            L.e("currentVasStyle svip", t);
        }
        return s;
    }

    private Object qrouteApi(String apiClass) {
        try {
            return ref.callS("com.tencent.mobileqq.qroute.QRoute", "api", ref.cls(apiClass));
        } catch (Throwable t) {
            return null;
        }
    }

    private Object svipHandler() {
        try {
            Object runtime = appRuntime();
            if (runtime == null) return null;
            Object proxy = qrouteApi("com.tencent.mobileqq.vas.svip.api.ISVIPHandlerProxy");
            String name = proxy == null ? "com.tencent.mobileqq.app.SVIPHandler"
                    : Ref.asStr(ref.call(proxy, "getImplClassName"));
            if (name == null || name.isEmpty()) name = "com.tencent.mobileqq.app.SVIPHandler";
            return ref.call(runtime, "getBusinessHandler", name);
        } catch (Throwable t) {
            L.e("svipHandler", t);
            return null;
        }
    }

    /** Same packing QQ NT uses when translating profile font fields into msg attrs. */
    private static int packNtFontId(long vipFont, int fontType, int magicFont) {
        long lo = vipFont & 255L;
        long hi = (vipFont >> 8) & 255L;
        return (int) (((lo << 8) + hi) + ((long) fontType << 16) + ((long) magicFont << 24));
    }

    private static Integer intBox(int v) { return v > 0 ? v : null; }

    private Object buildVasMsgAttr(VasStyle style) {
        if (style == null || !style.any()) return null;
        try {
            Object bubble = ref.neu(VAS_MSG_BUBBLE);
            ref.set(bubble, "bubbleId", intBox(style.bubbleId));
            ref.set(bubble, "bubbleDiyTextId", intBox(style.bubbleDiyTextId));
            ref.set(bubble, "subBubbleId", intBox(style.subBubbleId));
            if (style.bubbleId > 0) ref.set(bubble, "canConvertToText", 1);

            Object font = ref.neu(VAS_MSG_FONT);
            if (style.fontId != 0) ref.set(font, "fontId", style.fontId);
            if (style.magicFontType > 0) ref.set(font, "magicFontType", style.magicFontType);

            Object vas = ref.neu(VAS_MSG_ELEMENT);
            ref.set(vas, "bubbleInfo", bubble);
            ref.set(vas, "vasFont", font);

            Object info = ref.neu(MSG_ATTR_INFO);
            ref.set(info, "attrType", 0);
            ref.set(info, "attrId", 0L);
            ref.set(info, "vasMsgInfo", vas);
            return info;
        } catch (Throwable t) {
            L.e("buildVasMsgAttr", t);
            return null;
        }
    }

    /** Fallback: clone msgAttrs[0] from a recent manual send in this chat. */
    private Object findRecentSelfVasAttr(int chatType, String peerUid) {
        long self = parseLong(selfUin);
        if (self == 0) return null;
        MsgListResult list = getMsgs(chatType, peerUid, 0, 40);
        if (list.records == null) return null;
        for (Object rec : list.records) {
            if (ref.getLong(rec, "senderUin") != self) continue;
            Object cloned = cloneVasAttrFromRecord(rec);
            if (cloned != null) return cloned;
        }
        return null;
    }

    private Object cloneVasAttrFromRecord(Object rec) {
        try {
            Object mapObj = ref.get(rec, "msgAttrs");
            if (!(mapObj instanceof java.util.Map)) return null;
            Object src = ((java.util.Map<?, ?>) mapObj).get(0);
            if (src == null) return null;
            Object vas = ref.get(src, "vasMsgInfo");
            if (vas == null) return null;
            Object bubble = ref.get(vas, "bubbleInfo");
            Object font = ref.get(vas, "vasFont");
            Integer bid = bubble == null ? null : (Integer) ref.get(bubble, "bubbleId");
            Integer fid = font == null ? null : (Integer) ref.get(font, "fontId");
            int bubbleId = bid == null ? 0 : bid;
            int fontId = fid == null ? 0 : fid;
            if (bubbleId <= 0 && fontId == 0) return null;

            Object dstVas = ref.neu(VAS_MSG_ELEMENT);
            ref.set(dstVas, "bubbleInfo", cloneVasBubble(bubble));
            ref.set(dstVas, "vasFont", cloneVasFont(font));

            Object dst = ref.neu(MSG_ATTR_INFO);
            int attrType = Ref.asInt(ref.get(src, "attrType"));
            ref.set(dst, "attrType", attrType);
            ref.set(dst, "attrId", ref.getLong(src, "attrId"));
            ref.set(dst, "vasMsgInfo", dstVas);
            return dst;
        } catch (Throwable t) {
            L.e("cloneVasAttrFromRecord", t);
            return null;
        }
    }

    private Object cloneVasBubble(Object src) throws Exception {
        Object dst = ref.neu(VAS_MSG_BUBBLE);
        if (src == null) return dst;
        copyIntegerField(src, dst, "bubbleId");
        copyIntegerField(src, dst, "bubbleDiyTextId");
        copyIntegerField(src, dst, "subBubbleId");
        copyIntegerField(src, dst, "canConvertToText");
        return dst;
    }

    private Object cloneVasFont(Object src) throws Exception {
        Object dst = ref.neu(VAS_MSG_FONT);
        if (src == null) return dst;
        copyIntegerField(src, dst, "fontId");
        copyIntegerField(src, dst, "magicFontType");
        copyIntegerField(src, dst, "diyFontCfgUpdateTime");
        copyIntegerField(src, dst, "diyFontImageId");
        Object sub = ref.get(src, "subFontId");
        if (sub instanceof Long) ref.set(dst, "subFontId", sub);
        else if (sub instanceof Integer) ref.set(dst, "subFontId", ((Integer) sub).longValue());
        return dst;
    }

    private void copyIntegerField(Object src, Object dst, String field) {
        Object v = ref.get(src, field);
        if (v != null) ref.set(dst, field, v);
    }

    /** QQ AIO stores bubble/font on msgAttrs key 0 via IVasAIOSendDataUtilApi (msgType 4 = text). */
    private HashMap<Integer, Object> buildSendMsgAttrs(int chatType, String peerUid) {
        HashMap<Integer, Object> attrs = new HashMap<>();
        try {
            Object contact = ref.neu(CONTACT, chatType, peerUid, "");
            Object vasSend = qrouteApi("com.tencent.qqnt.kernel.api.IVasAIOSendDataUtilApi");
            if (vasSend != null) {
                ref.call(vasSend, "detailVasMsgDataAttrs", attrs, contact, 4);
                if (!attrs.isEmpty()) return attrs;
            }
        } catch (Throwable t) {
            L.e("buildSendMsgAttrs vasApi", t);
        }
        Object vasAttr = findRecentSelfVasAttr(chatType, peerUid);
        if (vasAttr == null) vasAttr = buildVasMsgAttr(currentVasStyle());
        if (vasAttr != null) attrs.put(0, vasAttr);
        return attrs;
    }

    private static long parseLong(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    // ---------- sending ----------
    public static final class SendResult { public int code = -1; public String msg = ""; public long msgId; }

    /** Called after QQ allocates the message id and before it can emit any send callback. */
    public interface SendTracker {
        void onGenerated(long msgId);
    }

    /** Send a pre-built element list to a group (chatType=2) or c2c (chatType=1). */
    public SendResult sendMsg(int chatType, String peerUid, ArrayList<?> elements) {
        return sendMsg(chatType, peerUid, elements, null);
    }

    /**
     * Send a message while exposing its id before entering the QQ kernel. This makes echo
     * suppression exact: unrelated messages typed in the QQ UI are never classified by a
     * broad "a bot send is in progress" time window.
     */
    public SendResult sendMsg(int chatType, String peerUid, ArrayList<?> elements,
                              SendTracker tracker) {
        SendResult r = new SendResult();
        Object msgService = getMsgService();
        if (msgService == null) { r.msg = "kernel session not ready"; return r; }
        try {
            long msgId = Ref.asLong(ref.call(msgService, "generateMsgUniqueId", chatType, System.currentTimeMillis()));
            r.msgId = msgId;
            if (tracker != null && msgId != 0) tracker.onGenerated(msgId);
            Object contact = ref.neu(CONTACT, chatType, peerUid, "");
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicInteger code = new AtomicInteger(-1);
            final String[] wording = new String[]{""};
            Object cb = Proxy.newProxyInstance(ref.cl, new Class[]{ref.cls(IOPERATE_CB)}, (proxy, m, args) -> {
                if (m.getName().equals("onResult") && args != null && args.length >= 1) {
                    code.set(Ref.asInt(args[0]));
                    if (args.length >= 2) wording[0] = Ref.asStr(args[1]);
                    latch.countDown();
                }
                return null;
            });
            try {
                String pre = describeElements(elements);
                L.e("sendMsg pre " + pre.replace("\n", " | "), null);
                // 落盘只在 verbose 下做，且写进 app 私有目录：原来无条件写外部
                // Android/data 下那个路径，那个目录一旦不可写就每次发消息抛一次
                // FileNotFoundException（logcat 里连着整串栈），既是噪声也是暴露面。
                if (L.verbose()) {
                    try (java.io.FileWriter w = new java.io.FileWriter(LAST_SEND_DUMP, false)) {
                        w.write("PRE\n" + pre);
                    }
                }
            } catch (Throwable t0) {
                L.e("sendMsg pre dump", t0);
            }
            HashMap<Integer, Object> attrs = buildSendMsgAttrs(chatType, peerUid);
            ref.call(msgService, "sendMsg", msgId, contact, elements, attrs, cb);
            if (latch.await(20, TimeUnit.SECONDS)) {
                r.code = code.get();
                r.msg = wording[0];
            } else {
                r.code = 0; // assume sent if no callback (fire-and-forget fallback)
                r.msg = "no callback (assumed sent)";
            }
        } catch (Throwable t) {
            L.e("sendMsg", t);
            r.msg = String.valueOf(t);
        }
        return r;
    }

    private static final String LAST_SEND_DUMP =
            "/data/data/com.tencent.mobileqq/files/satori-last-send.txt";

    /** Write kernel-stored element types/pic fields after send (no peer identifiers). */
    public void dumpSent(int chatType, String peerUid, long msgId) {
        StringBuilder sb = new StringBuilder();
        sb.append("msgId=").append(msgId).append(" chatType=").append(chatType).append('\n');
        Object rec = null;
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                try { Thread.sleep(250); } catch (InterruptedException ignore) {}
            }
            MsgListResult got = getMsgsByMsgId(chatType, peerUid, msgId);
            int n = got.records == null ? -1 : got.records.size();
            sb.append("try").append(i).append(" code=").append(got.code).append(" n=").append(n);
            if (got.msg != null && !got.msg.isEmpty()) sb.append(" ").append(got.msg);
            sb.append('\n');
            if (got.records != null && !got.records.isEmpty()) {
                rec = got.records.get(0);
                break;
            }
        }
        if (rec != null) {
            try { Media.repairSentPtt(ref, rec); } catch (Throwable t) { L.e("repairSentPtt", t); }
            sb.append(describeRecord(rec));
        } else sb.append("no record\n");
        L.e("dumpSent " + sb.toString().replace("\n", " | "), null);
        // The logcat line above is the diagnostic; the file was a second copy of it. Writing it
        // costs a read+rewrite per send and leaves a file named after the module in Android/data,
        // so it is verbose-only now.
        if (!L.verbose()) return;
        try {
            String pre = "";
            java.io.File dump = new java.io.File(LAST_SEND_DUMP);
            if (dump.isFile()) {
                byte[] raw = java.nio.file.Files.readAllBytes(dump.toPath());
                pre = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            }
            try (java.io.FileWriter w = new java.io.FileWriter(dump, false)) {
                w.write(pre);
                if (!pre.endsWith("\n")) w.write("\n");
                w.write("POST\n");
                w.write(sb.toString());
            }
        } catch (Throwable t) {
            L.e("dumpSent write", t);
        }
    }

    public String describeElements(java.util.List<?> list) {
        StringBuilder sb = new StringBuilder();
        if (list == null) return "elements=null\n";
        sb.append("elements=").append(list.size()).append('\n');
        for (int i = 0; i < list.size(); i++) {
            Object e = list.get(i);
            if (e == null) { sb.append(i).append(": null\n"); continue; }
            int et = Ref.asInt(ref.get(e, "elementType"));
            sb.append(i).append(": type=").append(et);
            sb.append(" class=").append(e.getClass().getName());
            if (et == 2) {
                Object pic = ref.get(e, "picElement");
                if (pic == null) { sb.append(" pic=null\n"); continue; }
                String src = Ref.asStr(ref.get(pic, "sourcePath"));
                java.io.File srcF = src == null || src.isEmpty() ? null : new java.io.File(src);
                sb.append(" ").append(Ref.asInt(ref.get(pic, "picWidth"))).append('x')
                        .append(Ref.asInt(ref.get(pic, "picHeight")));
                sb.append(" size=").append(ref.get(pic, "fileSize"));
                sb.append(" picType=").append(ref.get(pic, "picType"));
                sb.append(" inApp=").append(ref.get(pic, "isInApplicationDataPath"));
                sb.append(" name=").append(ref.get(pic, "fileName"));
                sb.append(" srcExists=").append(srcF != null && srcF.isFile());
                sb.append(" srcLen=").append(srcF == null ? -1 : srcF.length());
                sb.append(" thumb=").append(ref.get(pic, "thumbPath"));
            } else if (et == 4) {
                appendPtt(sb, ref.get(e, "pttElement"));
            } else if (et == 1) {
                Object te = ref.get(e, "textElement");
                String c = Ref.asStr(ref.get(te, "content"));
                sb.append(" textLen=").append(c.length());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public String describeRecord(Object rec) {

        if (rec == null) return "null\n";
        StringBuilder sb = new StringBuilder();
        sb.append("msgType=").append(ref.get(rec, "msgType"));
        sb.append(" sendStatus=").append(ref.get(rec, "sendStatus"));
        sb.append(" sendType=").append(ref.get(rec, "sendType")).append('\n');
        Object els = ref.get(rec, "elements");
        if (!(els instanceof List)) {
            sb.append("elements=").append(els == null ? "null" : els.getClass().getName()).append('\n');
            return sb.toString();
        }
        List<?> list = (List<?>) els;
        sb.append("elements=").append(list.size()).append('\n');
        for (int i = 0; i < list.size(); i++) {
            Object e = list.get(i);
            if (e == null) { sb.append(i).append(": null\n"); continue; }
            int et = Ref.asInt(ref.get(e, "elementType"));
            sb.append(i).append(": type=").append(et);
            if (et == 2) {
                Object pic = ref.get(e, "picElement");
                if (pic == null) { sb.append(" pic=null\n"); continue; }
                String src = Ref.asStr(ref.get(pic, "sourcePath"));
                java.io.File srcF = src == null || src.isEmpty() ? null : new java.io.File(src);
                sb.append(" ").append(Ref.asInt(ref.get(pic, "picWidth"))).append('x')
                        .append(Ref.asInt(ref.get(pic, "picHeight")));
                sb.append(" size=").append(ref.get(pic, "fileSize"));
                sb.append(" picType=").append(ref.get(pic, "picType"));
                sb.append(" inApp=").append(ref.get(pic, "isInApplicationDataPath"));
                sb.append(" name=").append(ref.get(pic, "fileName"));
                sb.append(" srcExists=").append(srcF != null && srcF.isFile());
                sb.append(" srcLen=").append(srcF == null ? -1 : srcF.length());
                sb.append(" thumb=").append(ref.get(pic, "thumbPath"));
                sb.append(" uuidEmpty=").append(Ref.asStr(ref.get(pic, "fileUuid")).isEmpty());
            } else if (et == 4) {
                appendPtt(sb, ref.get(e, "pttElement"));
            } else if (et == 1) {
                Object te = ref.get(e, "textElement");
                String c = Ref.asStr(ref.get(te, "content"));
                sb.append(" textLen=").append(c.length());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private void appendPtt(StringBuilder sb, Object ptt) {
        if (ptt == null) { sb.append(" ptt=null"); return; }
        String path = Ref.asStr(ref.get(ptt, "filePath"));
        java.io.File local = path.isEmpty() ? null : new java.io.File(path);
        Object waves = ref.get(ptt, "waveAmplitudes");
        sb.append(" duration=").append(ref.get(ptt, "duration"));
        sb.append(" format=").append(ref.get(ptt, "formatType"));
        sb.append(" voiceType=").append(ref.get(ptt, "voiceType"));
        sb.append(" size=").append(ref.get(ptt, "fileSize"));
        sb.append(" path=").append(local == null ? "" : local.getName());
        sb.append(" pathExists=").append(local != null && local.isFile());
        sb.append(" pathLen=").append(local == null ? -1 : local.length());
        sb.append(" transfer=").append(ref.get(ptt, "transferStatus"));
        sb.append(" progress=").append(ref.get(ptt, "progress"));
        sb.append(" play=").append(ref.get(ptt, "playState"));
        sb.append(" invalid=").append(ref.get(ptt, "invalidState"));
        sb.append(" uuidEmpty=").append(Ref.asStr(ref.get(ptt, "fileUuid")).isEmpty());
        sb.append(" waves=").append(waves instanceof java.util.List ? ((java.util.List<?>) waves).size() : -1);
    }

    /**
     * Ask the kernel to fetch inner merge-forward records for a just-sent card so the QQ client
     * viewer can open it via getMultiMsg instead of showing 消息加载失败.
     */
    public void prefetchForward(int chatType, String peerUid, long msgId) {
        MsgListResult result = getMultiMsg(chatType, peerUid, msgId);
        if (!result.ok()) L.e("getMultiMsg " + result.describe(), null);
    }

    /** Resolve a native merge-forward card by its parent message id. */
    public MsgListResult getMultiMsg(int chatType, String peerUid, long msgId) {
        MsgListResult result = new MsgListResult();
        if (msgId == 0 || peerUid == null || peerUid.isEmpty()) {
            result.msg = "missing contact or msgId";
            return result;
        }
        Object msgService = getMsgService();
        if (msgService == null) { result.msg = "kernel session not ready"; return result; }
        try {
            Object contact = ref.neu(CONTACT, chatType, peerUid, "");
            try {
                ref.call(msgService, "fetchLongMsg", contact, msgId);
            } catch (Throwable t) {
                L.e("fetchLongMsg", t);
            }
            final CountDownLatch latch = new CountDownLatch(1);
            final int[] code = new int[]{-1};
            final String[] wording = new String[]{""};
            final Object[] records = new Object[1];
            Object cb = Proxy.newProxyInstance(ref.cl,
                    new Class[]{ref.cls("com.tencent.qqnt.kernel.nativeinterface.IGetMultiMsgCallback")},
                    (proxy, m, args) -> {
                        if ("onResult".equals(m.getName()) && args != null && args.length >= 1) {
                            code[0] = Ref.asInt(args[0]);
                            if (args.length >= 2) wording[0] = Ref.asStr(args[1]);
                            if (args.length >= 3) records[0] = args[2];
                            latch.countDown();
                        }
                        return null;
                    });
            ref.call(msgService, "getMultiMsg", contact, msgId, msgId, cb);
            if (latch.await(8, TimeUnit.SECONDS)) {
                result.code = code[0];
                result.msg = wording[0] == null ? "" : wording[0];
                if (records[0] instanceof List) result.records = (List<?>) records[0];
            } else {
                result.timedOut = true;
                result.msg = "getMultiMsg timeout";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.timedOut = true;
            result.msg = "getMultiMsg interrupted";
        } catch (Throwable t) {
            L.e("getMultiMsg", t);
            result.msg = String.valueOf(t);
        }
        return result;
    }

    /** Native merge-forward of already-sent messages. src and dest are usually the same contact. */
    public SendResult multiForward(int chatType, String peerUid, ArrayList<Long> msgIds,
                                   ArrayList<String> names) {
        return multiForward(chatType, peerUid, chatType, peerUid, msgIds, names);
    }

    /**
     * Native merge-forward where the source messages live in a different chat than the
     * destination (e.g. scaffolding messages parked in the bot's own self-chat, forwarded
     * into a group). Mirrors the manual QQ flow: select messages in chat A, forward to chat B.
     */
    public SendResult multiForward(int srcChatType, String srcPeerUid, int dstChatType,
                                   String dstPeerUid, ArrayList<Long> msgIds,
                                   ArrayList<String> names) {
        SendResult r = new SendResult();
        Object msgService = getMsgService();
        if (msgService == null) { r.msg = "kernel session not ready"; return r; }
        if (msgIds == null || msgIds.isEmpty()) { r.msg = "empty msg ids"; return r; }
        try {
            ArrayList<Object> infos = new ArrayList<>();
            String infoCls = "com.tencent.qqnt.kernel.nativeinterface.MultiMsgInfo";
            for (int i = 0; i < msgIds.size(); i++) {
                long id = msgIds.get(i);
                String name = (names != null && i < names.size() && names.get(i) != null)
                        ? names.get(i) : "";
                infos.add(ref.neu(infoCls, id, name));
            }
            Object src = ref.neu(CONTACT, srcChatType, srcPeerUid, "");
            Object dst = ref.neu(CONTACT, dstChatType, dstPeerUid, "");
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicInteger code = new AtomicInteger(-1);
            final String[] wording = new String[]{""};
            Object cb = Proxy.newProxyInstance(ref.cl, new Class[]{ref.cls(IOPERATE_CB)},
                    (proxy, m, args) -> {
                        if (m.getName().equals("onResult") && args != null && args.length >= 1) {
                            code.set(Ref.asInt(args[0]));
                            if (args.length >= 2) wording[0] = Ref.asStr(args[1]);
                            latch.countDown();
                        }
                        return null;
                    });
            ref.call(msgService, "multiForwardMsg", infos, src, dst, cb);
            if (latch.await(20, TimeUnit.SECONDS)) {
                r.code = code.get();
                r.msg = wording[0];
            } else {
                r.code = -1;
                r.msg = "multiForwardMsg timeout";
            }
        } catch (Throwable t) {
            L.e("multiForwardMsg", t);
            r.msg = String.valueOf(t);
        }
        return r;
    }

    /** Query local/roamed message history around a message sequence; blocks up to 15 seconds. */
    public static final class MsgListResult {
        public int code = -1;
        public String msg = "";
        public boolean timedOut;
        public List<?> records = java.util.Collections.emptyList();
        public String trace = "";
        public final java.util.Map<String, String> texts = new java.util.HashMap<>();
        public boolean ok() { return !timedOut && code == 0; }
        public String describe() {
            if (timedOut) return msg == null || msg.isEmpty() ? "timeout" : msg;
            if (msg == null || msg.isEmpty()) return "code=" + code;
            return "code=" + code + " " + msg;
        }
    }

    public static final class Anchor {
        public long msgId;
        public long msgSeq;
        public long msgTime;
    }

    private static String describeList(MsgListResult r) {
        if (r == null) return "null";
        int n = r.records == null ? -1 : r.records.size();
        return r.describe() + " n=" + n;
    }

    private static boolean hasRecords(MsgListResult r) {
        return r != null && r.records != null && !r.records.isEmpty();
    }

    private boolean hasElements(MsgListResult r) {
        if (!hasRecords(r)) return false;
        for (Object rec : r.records) {
            if (recordHasElements(rec)) return true;
        }
        return false;
    }

    private boolean recordHasElements(Object rec) {
        if (rec == null) return false;
        try {
            Object els = ref.get(rec, "elements");
            if (!(els instanceof List)) return false;
            for (Object e : (List<?>) els) {
                if (e == null) continue;
                int et = Ref.asInt(ref.get(e, "elementType"));
                if (et == 1) {
                    Object t = ref.get(e, "textElement");
                    String c = t == null ? "" : Ref.asStr(ref.get(t, "content"));
                    if (c != null && !c.isEmpty()) return true;
                    continue;
                }
                if (et != 0) return true;
            }
            return false;
        } catch (Throwable ignore) {
            return false;
        }
    }

    private void fillAnchor(Anchor a, MsgListResult r) {
        if (a.msgId != 0 || !hasRecords(r)) return;
        Object rec = r.records.get(r.records.size() - 1);
        try {
            a.msgId = Ref.asLong(ref.get(rec, "msgId"));
            a.msgSeq = Ref.asLong(ref.get(rec, "msgSeq"));
            a.msgTime = Ref.asLong(ref.get(rec, "msgTime"));
        } catch (Throwable ignore) {}
    }

    private MsgListResult hydrate(int chatType, String peerUid, MsgListResult src) {
        if (!hasRecords(src)) return src;
        java.util.ArrayList<Object> full = new java.util.ArrayList<>();
        boolean changed = false;
        for (Object rec : src.records) {
            if (recordHasElements(rec)) {
                full.add(rec);
                continue;
            }
            long id = 0;
            try { id = Ref.asLong(ref.get(rec, "msgId")); } catch (Throwable ignore) {}
            if (id == 0) {
                full.add(rec);
                continue;
            }
            MsgListResult got = getMsgsByMsgId(chatType, peerUid, id);
            if (hasRecords(got)) {
                full.add(got.records.get(0));
                changed = true;
            } else {
                full.add(rec);
            }
        }
        if (changed) src.records = full;
        return src;
    }

    @SuppressWarnings("unchecked")
    private List<?> takeRecords(Object holder) {
        if (!(holder instanceof List)) return java.util.Collections.emptyList();
        List<?> raw = (List<?>) holder;
        java.util.ArrayList<Object> out = new java.util.ArrayList<>(raw.size());
        for (Object o : raw) {
            if (o == null) continue;
            out.add(com.satori.qq.qq.Convert.unwrapRecord(o));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public MsgListResult getMsgs(int chatType, String peerUid, long messageSeq, int count) {
        return getMsgs(chatType, peerUid, messageSeq, count, true);
    }

    /** queryOrder=true walks toward older messages; false walks toward newer messages. */
    public MsgListResult getMsgs(int chatType, String peerUid, long messageSeq, int count,
                                 boolean queryOrder) {
        MsgListResult r = new MsgListResult();
        Object msgService = getMsgService();
        if (msgService == null) {
            r.msg = "kernel session not ready";
            return r;
        }
        count = Math.max(1, Math.min(100, count));
        try {
            Object contact = ref.neu(CONTACT, chatType, peerUid, "");
            final Object[] holder = new Object[1];
            final int[] code = new int[]{-1};
            final String[] wording = new String[]{""};
            CountDownLatch latch = new CountDownLatch(1);
            Object cb = Proxy.newProxyInstance(ref.cl, new Class[]{ref.cls(MSG_OPERATE_CB)}, (p, m, a) -> {
                if ("onResult".equals(m.getName()) && a != null && a.length >= 1) {
                    code[0] = Ref.asInt(a[0]);
                    if (a.length >= 2) wording[0] = Ref.asStr(a[1]);
                    if (a.length >= 3) holder[0] = a[2];
                    latch.countDown();
                }
                return null;
            });
            if (messageSeq > 0) {
                ref.call(msgService, "getMsgsBySeqAndCount", contact, messageSeq, count,
                        queryOrder, true, cb);
            } else {
                ref.call(msgService, "getMsgs", contact, 0L, count, queryOrder, cb);
            }
            if (!latch.await(15, TimeUnit.SECONDS)) {
                r.timedOut = true;
                r.msg = "getMsgs timeout";
                return r;
            }
            r.code = code[0];
            r.msg = wording[0] == null ? "" : wording[0];
            r.records = takeRecords(holder[0]);
        } catch (Throwable t) {
            L.e("getMsgs history", t);
            r.msg = String.valueOf(t);
        }
        return r;
    }

    /** NapCat getMsgHistory: include own messages; msgId=0 means latest. */
    public MsgListResult getMsgsIncludeSelf(int chatType, String peerUid, long msgId, int count,
                                            boolean queryOrder) {
        MsgListResult r = new MsgListResult();
        Object msgService = getMsgService();
        if (msgService == null) {
            r.msg = "kernel session not ready";
            return r;
        }
        count = Math.max(1, Math.min(100, count));
        try {
            Object contact = ref.neu(CONTACT, chatType, peerUid, "");
            final Object[] holder = new Object[1];
            final int[] code = new int[]{-1};
            final String[] wording = new String[]{""};
            CountDownLatch latch = new CountDownLatch(1);
            Object cb = Proxy.newProxyInstance(ref.cl, new Class[]{ref.cls(MSG_OPERATE_CB)}, (p, m, a) -> {
                if ("onResult".equals(m.getName()) && a != null && a.length >= 1) {
                    code[0] = Ref.asInt(a[0]);
                    if (a.length >= 2) wording[0] = Ref.asStr(a[1]);
                    if (a.length >= 3) holder[0] = a[2];
                    latch.countDown();
                }
                return null;
            });
            ref.call(msgService, "getMsgsIncludeSelf", contact, msgId, count, queryOrder, cb);
            if (!latch.await(15, TimeUnit.SECONDS)) {
                r.timedOut = true;
                r.msg = "getMsgsIncludeSelf timeout";
                return r;
            }
            r.code = code[0];
            r.msg = wording[0] == null ? "" : wording[0];
            r.records = takeRecords(holder[0]);
        } catch (Throwable t) {
            L.e("getMsgsIncludeSelf", t);
            r.msg = String.valueOf(t);
        }
        return r;
    }

    public MsgListResult getAioFirstViewLatestMsgs(int chatType, String peerUid, int count) {
        MsgListResult r = new MsgListResult();
        Object msgService = getMsgService();
        if (msgService == null) {
            r.msg = "kernel session not ready";
            return r;
        }
        count = Math.max(1, Math.min(100, count));
        try {
            Object contact = ref.neu(CONTACT, chatType, peerUid, "");
            final Object[] holder = new Object[1];
            final int[] code = new int[]{-1};
            final String[] wording = new String[]{""};
            CountDownLatch latch = new CountDownLatch(1);
            Object cb = Proxy.newProxyInstance(ref.cl, new Class[]{ref.cls(AIO_FIRST_CB)}, (p, m, a) -> {
                if ("onResult".equals(m.getName()) && a != null && a.length >= 1) {
                    code[0] = Ref.asInt(a[0]);
                    if (a.length >= 2) wording[0] = Ref.asStr(a[1]);
                    if (a.length >= 3) holder[0] = a[2];
                    latch.countDown();
                }
                return null;
            });
            ref.call(msgService, "getAioFirstViewLatestMsgs", contact, count, cb);
            if (!latch.await(15, TimeUnit.SECONDS)) {
                r.timedOut = true;
                r.msg = "getAioFirstViewLatestMsgs timeout";
                return r;
            }
            r.code = code[0];
            r.msg = wording[0] == null ? "" : wording[0];
            r.records = takeRecords(holder[0]);
        } catch (Throwable t) {
            L.e("getAioFirstViewLatestMsgs", t);
            r.msg = String.valueOf(t);
        }
        return r;
    }

    /** Local NT DB latest messages; does not depend on the in-memory AIO window. */
    public MsgListResult getLatestDbMsgs(int chatType, String peerUid, int count) {
        MsgListResult r = new MsgListResult();
        Object msgService = getMsgService();
        if (msgService == null) {
            r.msg = "kernel session not ready";
            return r;
        }
        count = Math.max(1, Math.min(100, count));
        try {
            Object contact = ref.neu(CONTACT, chatType, peerUid, "");
            final Object[] holder = new Object[1];
            final int[] code = new int[]{-1};
            final String[] wording = new String[]{""};
            CountDownLatch latch = new CountDownLatch(1);
            Object cb = Proxy.newProxyInstance(ref.cl, new Class[]{ref.cls(MSG_OPERATE_CB)}, (p, m, a) -> {
                if ("onResult".equals(m.getName()) && a != null && a.length >= 1) {
                    code[0] = Ref.asInt(a[0]);
                    if (a.length >= 2) wording[0] = Ref.asStr(a[1]);
                    if (a.length >= 3) holder[0] = a[2];
                    latch.countDown();
                }
                return null;
            });
            ref.call(msgService, "getLatestDbMsgs", contact, count, cb);
            if (!latch.await(15, TimeUnit.SECONDS)) {
                r.timedOut = true;
                r.msg = "getLatestDbMsgs timeout";
                return r;
            }
            r.code = code[0];
            r.msg = wording[0] == null ? "" : wording[0];
            r.records = takeRecords(holder[0]);
        } catch (Throwable t) {
            L.e("getLatestDbMsgs", t);
            r.msg = String.valueOf(t);
        }
        return r;
    }

    public MsgListResult getLastMessageList(int chatType, String peerUid) {
        MsgListResult r = new MsgListResult();
        Object msgService = getMsgService();
        if (msgService == null) {
            r.msg = "kernel session not ready";
            return r;
        }
        try {
            java.util.ArrayList<Object> contacts = new java.util.ArrayList<>();
            contacts.add(ref.neu(CONTACT, chatType, peerUid, ""));
            final Object[] holder = new Object[1];
            final int[] code = new int[]{-1};
            final String[] wording = new String[]{""};
            CountDownLatch latch = new CountDownLatch(1);
            Object cb = Proxy.newProxyInstance(ref.cl, new Class[]{ref.cls(MSG_OPERATE_CB)}, (p, m, a) -> {
                if ("onResult".equals(m.getName()) && a != null && a.length >= 1) {
                    code[0] = Ref.asInt(a[0]);
                    if (a.length >= 2) wording[0] = Ref.asStr(a[1]);
                    if (a.length >= 3) holder[0] = a[2];
                    latch.countDown();
                }
                return null;
            });
            ref.call(msgService, "getLastMessageList", contacts, cb);
            if (!latch.await(15, TimeUnit.SECONDS)) {
                r.timedOut = true;
                r.msg = "getLastMessageList timeout";
                return r;
            }
            r.code = code[0];
            r.msg = wording[0] == null ? "" : wording[0];
            r.records = takeRecords(holder[0]);
        } catch (Throwable t) {
            L.e("getLastMessageList", t);
            r.msg = String.valueOf(t);
        }
        return r;
    }

    public Anchor recentAnchor(int chatType, String peerUid) {
        Anchor a = new Anchor();
        try {
            Object svc = getRecentContactService();
            if (svc == null) return a;
            Object info;
            try { info = ref.call(svc, "getRecentContactListSyncLimit", 400); }
            catch (Throwable ignore) { info = ref.call(svc, "getRecentContactListSync"); }
            if (info == null) return a;
            Object list = ref.get(info, "changedList");
            if (!(list instanceof List)) return a;
            long wantUin = 0;
            try { wantUin = Long.parseLong(peerUid); } catch (Exception ignore) {}
            for (Object c : (List<?>) list) {
                if (c == null) continue;
                if (Ref.asInt(ref.get(c, "chatType")) != chatType) continue;
                String uid = Ref.asStr(ref.get(c, "peerUid"));
                long uin = Ref.asLong(ref.get(c, "peerUin"));
                boolean match = (peerUid != null && peerUid.equals(uid))
                        || (wantUin != 0 && uin == wantUin)
                        || (wantUin != 0 && String.valueOf(wantUin).equals(uid));
                if (!match) continue;
                a.msgId = Ref.asLong(ref.get(c, "msgId"));
                a.msgSeq = Ref.asLong(ref.get(c, "msgSeq"));
                a.msgTime = Ref.asLong(ref.get(c, "msgTime"));
                return a;
            }
        } catch (Throwable t) {
            L.e("recentAnchor", t);
        }
        return a;
    }

    /** Latest / paged history. Prefers the local NT DB over the in-memory AIO window. */
    public MsgListResult getHistory(int chatType, String peerUid, long messageSeq, int count,
                                    boolean queryOrder) {
        StringBuilder trace = new StringBuilder("hist3 peer=").append(peerUid)
                .append(" seq=").append(messageSeq);
        if (messageSeq > 0) {
            MsgListResult bySeq = hydrate(chatType, peerUid,
                    getMsgs(chatType, peerUid, messageSeq, count, queryOrder));
            trace.append(" bySeq=").append(describeList(bySeq)).append(hasElements(bySeq) ? "+el" : "");
            if (hasElements(bySeq)) return finishHistory(bySeq, trace);
        }
        MsgListResult db = getLatestDbMsgs(chatType, peerUid, count);
        trace.append(" db=").append(describeList(db)).append(hasElements(db) ? "+el" : "");
        if (hasElements(db)) return finishHistory(hydrate(chatType, peerUid, db), trace);
        MsgListResult include = hydrate(chatType, peerUid,
                getMsgsIncludeSelf(chatType, peerUid, 0, count, queryOrder));
        trace.append(" inc0=").append(describeList(include)).append(hasElements(include) ? "+el" : "");
        if (hasElements(include)) return finishHistory(include, trace);
        MsgListResult aio = hydrate(chatType, peerUid, getAioFirstViewLatestMsgs(chatType, peerUid, count));
        trace.append(" aio=").append(describeList(aio)).append(hasElements(aio) ? "+el" : "");
        if (hasElements(aio)) return finishHistory(aio, trace);
        MsgListResult last = getLastMessageList(chatType, peerUid);
        trace.append(" last=").append(describeList(last));
        Anchor a = recentAnchor(chatType, peerUid);
        fillAnchor(a, db);
        fillAnchor(a, last);
        fillAnchor(a, include);
        trace.append(" anchor=").append(a.msgId).append('/').append(a.msgSeq);
        if (a.msgId != 0) {
            MsgListResult fromId = hydrate(chatType, peerUid,
                    getMsgsIncludeSelf(chatType, peerUid, a.msgId, count, queryOrder));
            trace.append(" incId=").append(describeList(fromId)).append(hasElements(fromId) ? "+el" : "");
            if (hasElements(fromId)) return finishHistory(fromId, trace);
        }
        if (a.msgSeq != 0) {
            MsgListResult bySeqA = hydrate(chatType, peerUid,
                    getMsgs(chatType, peerUid, a.msgSeq, count, queryOrder));
            trace.append(" bySeqA=").append(describeList(bySeqA)).append(hasElements(bySeqA) ? "+el" : "");
            if (hasElements(bySeqA)) return finishHistory(bySeqA, trace);
        }
        MsgListResult queried = hydrate(chatType, peerUid,
                queryMsgs(chatType, peerUid, a.msgId, a.msgTime,
                        a.msgSeq != 0 ? a.msgSeq : messageSeq, count, queryOrder));
        trace.append(" query=").append(describeList(queried)).append(hasElements(queried) ? "+el" : "");
        if (hasElements(queried)) return finishHistory(queried, trace);
        if (hasRecords(db)) return finishHistory(hydrate(chatType, peerUid, db), trace);
        if (hasRecords(last)) return finishHistory(hydrate(chatType, peerUid, last), trace);
        MsgListResult fallback = hydrate(chatType, peerUid, messageSeq > 0
                ? getMsgs(chatType, peerUid, messageSeq, count, queryOrder)
                : getMsgs(chatType, peerUid, 0, count, queryOrder));
        trace.append(" get0=").append(describeList(fallback));
        return finishHistory(fallback, trace);
    }

    /** Plain text from a MsgRecord, using the same field reads as history sampling. */
    public String peekRecordText(Object rec) {
        if (rec == null) return "";
        try {
            Object els = ref.get(rec, "elements");
            if (!(els instanceof List)) return "";
            StringBuilder sb = new StringBuilder();
            for (Object e : (List<?>) els) {
                if (e == null) continue;
                if (Ref.asInt(ref.get(e, "elementType")) != 1) continue;
                Object t = ref.get(e, "textElement");
                String c = t == null ? "" : Ref.asStr(ref.get(t, "content"));
                if (c != null && !c.isEmpty()) sb.append(c);
            }
            return sb.toString();
        } catch (Throwable ignore) {
            return "";
        }
    }

    private String sampleRecord(MsgListResult r) {
        if (!hasRecords(r)) return "none";
        Object rec = r.records.get(0);
        try {
            Object els = ref.get(rec, "elements");
            int n = els instanceof List ? ((List<?>) els).size() : -1;
            int et = -1;
            int textLen = -1;
            String textHead = "";
            if (els instanceof List && n > 0) {
                Object e0 = ((List<?>) els).get(0);
                et = e0 == null ? -2 : Ref.asInt(ref.get(e0, "elementType"));
                if (et == 1 && e0 != null) {
                    Object t = ref.get(e0, "textElement");
                    String c = t == null ? "" : Ref.asStr(ref.get(t, "content"));
                    textLen = c.length();
                    textHead = c.length() <= 24 ? c : c.substring(0, 24);
                    textHead = textHead.replace('\n', ' ').replace('\r', ' ');
                }
            }
            return rec.getClass().getSimpleName()
                    + " ct=" + ref.get(rec, "chatType")
                    + " mt=" + ref.get(rec, "msgType")
                    + " sub=" + ref.get(rec, "subMsgType")
                    + " time=" + ref.get(rec, "msgTime")
                    + " seq=" + ref.get(rec, "msgSeq")
                    + " id=" + ref.get(rec, "msgId")
                    + " suin=" + ref.get(rec, "senderUin")
                    + " peer=" + ref.get(rec, "peerUid")
                    + " puin=" + ref.get(rec, "peerUin")
                    + " els=" + n + " et0=" + et + " textLen=" + textLen
                    + " text=\"" + textHead + "\""
                    + " recall=" + ref.get(rec, "recallTime");
        } catch (Throwable t) {
            return "err:" + t;
        }
    }

    private MsgListResult finishHistory(MsgListResult r, StringBuilder trace) {
        if (r == null) r = new MsgListResult();
        if (r.records != null) {
            for (Object rec : r.records) {
                if (rec == null) continue;
                try {
                    long id = Ref.asLong(ref.get(rec, "msgId"));
                    String text = peekRecordText(rec);
                    if (id != 0 && text != null && !text.isEmpty())
                        r.texts.put(String.valueOf(id), text);
                } catch (Throwable ignore) {}
            }
        }
        trace.append(" sample=").append(sampleRecord(r));
        if (!r.texts.isEmpty()) trace.append(" texts=").append(r.texts.size());
        r.trace = trace.toString();
        L.e(r.trace, null);
        // Same reasoning as dumpSent: the trace belongs in logcat, not in a file in Android/data.
        if (L.verbose()) try {
            java.io.File f = new java.io.File(
                    "/data/data/com.tencent.mobileqq/files/satori-history.txt");
            try (java.io.FileWriter w = new java.io.FileWriter(f, false)) { w.write(r.trace); }
        } catch (Throwable ignore) {}
        return r;
    }

    /** Local NT DB query; works when the in-memory AIO list is still empty after a cold start. */
    public MsgListResult queryMsgs(int chatType, String peerUid, long messageSeq, int count,
                                   boolean queryOrder) {
        return queryMsgs(chatType, peerUid, 0L, 0L, messageSeq, count, queryOrder);
    }

    public MsgListResult queryMsgs(int chatType, String peerUid, long msgId, long msgTime,
                                   long messageSeq, int count, boolean queryOrder) {
        MsgListResult r = new MsgListResult();
        Object msgService = getMsgService();
        if (msgService == null) {
            r.msg = "kernel session not ready";
            return r;
        }
        count = Math.max(1, Math.min(100, count));
        try {
            Object chat = ref.neu("com.tencent.qqnt.kernel.nativeinterface.ChatInfo",
                    chatType, peerUid == null ? "" : peerUid);
            Object params = ref.neu("com.tencent.qqnt.kernel.nativeinterface.QueryMsgsParams");
            ref.set(params, "chatInfo", chat);
            ref.set(params, "pageLimit", count);
            ref.set(params, "isReverseOrder", queryOrder);
            ref.set(params, "isIncludeCurrent", Boolean.TRUE);
            ref.set(params, "filterMsgFromTime", 0L);
            ref.set(params, "filterMsgToTime", System.currentTimeMillis() / 1000);
            ref.set(params, "filterMsgType", new java.util.ArrayList<>());
            ref.set(params, "filterSendersUid", new java.util.ArrayList<String>());
            final Object[] holder = new Object[1];
            final int[] code = new int[]{-1};
            final String[] wording = new String[]{""};
            CountDownLatch latch = new CountDownLatch(1);
            Object cb = Proxy.newProxyInstance(ref.cl, new Class[]{ref.cls(MSG_OPERATE_CB)}, (p, m, a) -> {
                if ("onResult".equals(m.getName()) && a != null && a.length >= 1) {
                    code[0] = Ref.asInt(a[0]);
                    if (a.length >= 2) wording[0] = Ref.asStr(a[1]);
                    if (a.length >= 3) holder[0] = a[2];
                    latch.countDown();
                }
                return null;
            });
            try {
                ref.call(msgService, "queryMsgsWithFilterEx", msgId, msgTime, messageSeq, params, cb);
            } catch (Throwable first) {
                ref.call(msgService, "queryMsgsWithFilter", msgId, msgTime, params, cb);
            }
            if (!latch.await(15, TimeUnit.SECONDS)) {
                r.timedOut = true;
                r.msg = "queryMsgs timeout";
                return r;
            }
            r.code = code[0];
            r.msg = wording[0] == null ? "" : wording[0];
            r.records = takeRecords(holder[0]);
        } catch (Throwable t) {
            L.e("queryMsgs", t);
            r.msg = String.valueOf(t);
        }
        return r;
    }

    /** Retry getMsgsByMsgId; just-sent records are often not indexed on the first tick. */
    public Object fetchRecord(int chatType, String peerUid, long msgId) {
        if (msgId == 0) return null;
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                try { Thread.sleep(250); } catch (InterruptedException ignore) {}
            }
            MsgListResult got = getMsgsByMsgId(chatType, peerUid, msgId);
            if (got.ok() && got.records != null && !got.records.isEmpty())
                return got.records.get(0);
        }
        MsgListResult hist = getMsgsIncludeSelf(chatType, peerUid, 0, 20, true);
        if (hist.ok() && hist.records != null) {
            for (Object rec : hist.records) {
                if (Ref.asLong(ref.get(rec, "msgId")) == msgId) return rec;
            }
        }
        return null;
    }

    public MsgListResult getMsgsByMsgId(int chatType, String peerUid, long msgId) {
        MsgListResult r = new MsgListResult();
        Object msgService = getMsgService();
        if (msgService == null) {
            r.msg = "kernel session not ready";
            return r;
        }
        if (msgId == 0) {
            r.msg = "missing msgId";
            return r;
        }
        try {
            Object contact = ref.neu(CONTACT, chatType, peerUid, "");
            java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
            ids.add(msgId);
            final Object[] holder = new Object[1];
            final int[] code = new int[]{-1};
            final String[] wording = new String[]{""};
            CountDownLatch latch = new CountDownLatch(1);
            Object cb = Proxy.newProxyInstance(ref.cl, new Class[]{ref.cls(MSG_OPERATE_CB)}, (p, m, a) -> {
                if ("onResult".equals(m.getName()) && a != null && a.length >= 1) {
                    code[0] = Ref.asInt(a[0]);
                    if (a.length >= 2) wording[0] = Ref.asStr(a[1]);
                    if (a.length >= 3) holder[0] = a[2];
                    latch.countDown();
                }
                return null;
            });
            ref.call(msgService, "getMsgsByMsgId", contact, ids, cb);
            if (!latch.await(15, TimeUnit.SECONDS)) {
                r.timedOut = true;
                r.msg = "getMsgsByMsgId timeout";
                return r;
            }
            r.code = code[0];
            r.msg = wording[0] == null ? "" : wording[0];
            r.records = takeRecords(holder[0]);
        } catch (Throwable t) {
            L.e("getMsgsByMsgId", t);
            r.msg = String.valueOf(t);
        }
        return r;
    }

    /** Ask QQ NT to materialize a received rich-media element and return its callback path. */
    public String downloadRichMedia(int chatType, String peerUid, long msgId, long elementId) {
        return downloadRichMedia(chatType, peerUid, msgId, elementId, 0L);
    }

    public String downloadRichMedia(int chatType, String peerUid, long msgId, long elementId,
                                    long fileModelId) {
        Object msgService = getMsgService();
        if (msgService == null || chatType == 0 || peerUid == null || peerUid.isEmpty()
                || msgId == 0) return "";
        String key = mediaDownloadKey(msgId, elementId);
        MediaDownload pending = new MediaDownload();
        mediaDownloads.put(key, pending);
        try {
            Class<?>[] types = new Class[]{long.class, String.class, int.class, long.class,
                    int.class, int.class, String.class, long.class, int.class, int.class};
            Object req = ref.neuTyped(RICH_MEDIA_GET_REQ, types,
                    new Object[]{msgId, peerUid, chatType, elementId,
                            1, 0, "", fileModelId, 0, 1});
            ref.call(msgService, "downloadRichMedia", req);
            if (!pending.latch.await(30, TimeUnit.SECONDS)) return "";
            if (pending.errorCode != 0) {
                L.e("downloadRichMedia code=" + pending.errorCode + " " + pending.errorMessage, null);
                return "";
            }
            if (pending.path == null || pending.path.isEmpty()) {
                L.e("downloadRichMedia empty path elem=" + elementId, null);
                return "";
            }
            java.io.File result = new java.io.File(pending.path);
            return result.isFile() && result.length() > 0 ? result.getAbsolutePath() : "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Throwable t) {
            L.e("downloadRichMedia", t);
            return "";
        } finally {
            mediaDownloads.remove(key, pending);
        }
    }

    /**
     * Video original file is often missing from {@code downloadRichMedia}; NapCat uses
     * {@code getVideoPlayUrlV2} (codec H264, downSourceType=1, triggerType=1).
     */
    public String getVideoPlayUrl(int chatType, String peerUid, long msgId, long elementId) {
        Object rms = getRichMediaService();
        if (rms == null || chatType == 0 || peerUid == null || peerUid.isEmpty() || msgId == 0)
            return "";
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final String[] url = {""};
            final int[] code = {-1};
            Object cb = Proxy.newProxyInstance(ref.cl, new Class[]{ref.cls(VIDEO_URL_CB)}, (p, m, args) -> {
                if ("onResult".equals(m.getName()) && args != null && args.length >= 1) {
                    code[0] = Ref.asInt(args[0]);
                    if (args.length >= 3 && args[2] != null) url[0] = firstVideoUrl(args[2]);
                    latch.countDown();
                }
                return defOf(m.getReturnType());
            });
            Object contact = ref.neu(CONTACT, chatType, peerUid, "");
            Object codec = ref.getStatic(VIDEO_CODEC, "KCODECFORMATH264");
            Object ex = ref.neu(RM_EX_PARAMS, 1, 1);
            ref.call(rms, "getVideoPlayUrlV2", contact, msgId, elementId, codec, ex, cb);
            if (!latch.await(20, TimeUnit.SECONDS)) return "";
            if (code[0] != 0) {
                L.e("getVideoPlayUrlV2 code=" + code[0], null);
                return "";
            }
            return url[0] == null ? "" : url[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Throwable t) {
            L.e("getVideoPlayUrl", t);
            return "";
        }
    }

    private String firstVideoUrl(Object result) {
        try {
            for (String field : new String[]{"domainUrl", "v4IpUrl", "v6IpUrl"}) {
                Object list = ref.get(result, field);
                if (!(list instanceof List)) continue;
                for (Object info : (List<?>) list) {
                    if (info == null) continue;
                    String u = Ref.asStr(ref.get(info, "url"));
                    if (u != null && !u.isEmpty()) return u;
                }
            }
        } catch (Throwable ignore) {}
        return "";
    }


    // ---------- group queries ----------
    private final java.util.Map<Long, Object> groupInfoCache = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean groupListenerRegistered;
    private volatile Object groupListenerSession;
    private volatile boolean buddyListenerRegistered;
    private volatile Object buddyListenerSession;

    private synchronized void tryRegisterGroupListener() {
        Object targetSession = session;
        if (targetSession == null) return;
        if (groupListenerRegistered && groupListenerSession == targetSession) return;
        Object gs = getGroupService();
        if (gs == null) return;
        try {
            Class<?> li = ref.cls(GROUP_LISTENER);
            Object proxy = Proxy.newProxyInstance(ref.cl, new Class[]{li}, (p, m, args) -> {
                try {
                    String name = m.getName();
                    if ("onGroupListUpdate".equals(name) && args != null && args.length >= 2
                            && args[1] instanceof List) {
                        String update = args[0] == null ? "" : String.valueOf(args[0]).toUpperCase();
                        for (Object gi : (List<?>) args[1]) {
                            long code = Ref.asLong(ref.get(gi, "groupCode"));
                            if (code == 0) continue;
                            if (update.contains("DELETE") || update.contains("REMOVE")
                                    || update.contains("DEL") || update.contains("QUIT")
                                    || update.contains("EXIT"))
                                groupInfoCache.remove(code);
                            else groupInfoCache.put(code, gi);
                        }
                    }
                    Listener l = listener;
                    if (l != null && args != null) {
                        if ("onMemberListChange".equals(name) && args.length >= 1 && args[0] != null)
                            l.onMemberListChange(args[0]);
                        if ("onGroupListUpdate".equals(name) && args.length >= 2
                                && args[1] instanceof List)
                            l.onGroupListUpdate(args[0], (List<?>) args[1]);
                        List<?> notifies = extractGroupNotifies(name, args);
                        if (notifies != null && !notifies.isEmpty()) l.onGroupNotifies(notifies);
                    }
                } catch (Throwable t) {
                    L.e("groupListener." + m.getName(), t);
                }
                return defOf(m.getReturnType());
            });
            ref.call(gs, "addKernelGroupListener", proxy);
            if (session != targetSession) return;
            groupListenerSession = targetSession;
            groupListenerRegistered = true;
            L.i("Registered IKernelGroupListener (group list + notifies)");
        } catch (Throwable t) {
            L.e("register group listener", t);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<?> extractGroupNotifies(String name, Object[] args) {
        Object list = null;
        if ("onGroupNotifiesUpdated".equals(name) && args.length >= 2) list = args[1];
        else if ("onGroupNotifiesUpdatedV2".equals(name) && args.length >= 3) list = args[2];
        else if ("onGroupSingleScreenNotifies".equals(name) && args.length >= 3) list = args[2];
        else if ("onGroupSingleScreenNotifiesV2".equals(name) && args.length >= 6) list = args[5];
        return list instanceof List ? (List<?>) list : null;
    }

    private synchronized void tryRegisterBuddyListener() {
        Object targetSession = session;
        if (targetSession == null) return;
        if (buddyListenerRegistered && buddyListenerSession == targetSession) return;
        Object buddy = getBuddyService();
        if (buddy == null) return;
        try {
            Class<?> li = ref.cls(BUDDY_LISTENER);
            Object proxy = Proxy.newProxyInstance(ref.cl, new Class[]{li}, (p, m, args) -> {
                try {
                    if ("onBuddyReqChange".equals(m.getName()) && args != null && args.length >= 1
                            && args[0] != null) {
                        Listener l = listener;
                        if (l != null) l.onBuddyReq(args[0]);
                    }
                } catch (Throwable t) {
                    L.e("buddyListener." + m.getName(), t);
                }
                return defOf(m.getReturnType());
            });
            ref.call(buddy, "addKernelBuddyListener", proxy);
            if (session != targetSession) return;
            buddyListenerSession = targetSession;
            buddyListenerRegistered = true;
            L.i("Registered IKernelBuddyListener (friend requests)");
        } catch (Throwable t) {
            L.e("register buddy listener", t);
        }
    }

    /** Pull existing buddy/group requests so set_*_add_request works without a live push. */
    public void refreshPendingRequestsAsync() {
        Thread t = new Thread(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
            refreshBuddyReqs();
            refreshGroupNotifies();
        }, "pool-6-thread-3");
        t.setDaemon(true);
        t.start();
    }

    public boolean refreshBuddyReqs() {
        Object buddy = getBuddyService();
        if (buddy == null) return false;
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            Object cb = Proxy.newProxyInstance(ref.cl, new Class[]{ref.cls(OPERATE_CB)}, (p, m, args) -> {
                if ("onResult".equals(m.getName())) latch.countDown();
                return defOf(m.getReturnType());
            });
            ref.call(buddy, "getBuddyReq", cb);
            return latch.await(8, TimeUnit.SECONDS);
        } catch (Throwable t) {
            L.e("getBuddyReq", t);
            return false;
        }
    }

    public boolean refreshGroupNotifies() {
        Object gs = getGroupService();
        if (gs == null) return false;
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            Object cb = Proxy.newProxyInstance(ref.cl, new Class[]{ref.cls(OPERATE_CB)}, (p, m, args) -> {
                if ("onResult".equals(m.getName())) latch.countDown();
                return defOf(m.getReturnType());
            });
            ref.call(gs, "getSingleScreenNotifies", false, 0L, 50, cb);
            return latch.await(8, TimeUnit.SECONDS);
        } catch (Throwable t) {
            L.e("getSingleScreenNotifies", t);
            return false;
        }
    }

    private static Object defOf(Class<?> r) {
        if (!r.isPrimitive() || r == void.class) return null;
        if (r == boolean.class) return false;
        if (r == long.class) return 0L; if (r == int.class) return 0;
        if (r == double.class) return 0d; if (r == float.class) return 0f;
        if (r == short.class) return (short) 0; if (r == byte.class) return (byte) 0;
        if (r == char.class) return (char) 0;
        return null;
    }

    /** All members of a group: HashMap<uid, MemberInfo>. Blocks up to 15s. Null on failure. */
    public java.util.Map<String, Object> getAllMembers(long groupCode) {
        return getAllMembers(groupCode, false);
    }

    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> getAllMembers(long groupCode, boolean force) {
        Object gs = getGroupService();
        if (gs == null) return null;
        try {
            final Object[] holder = new Object[1];
            final CountDownLatch latch = new CountDownLatch(1);
            Object cb = Proxy.newProxyInstance(ref.cl, new Class[]{ref.cls(MEMBER_LIST_CB)}, (p, m, args) -> {
                if ("onResult".equals(m.getName()) && args != null && args.length >= 3 && args[2] != null) {
                    try { holder[0] = ref.get(args[2], "infos"); } catch (Throwable ignore) {}
                    latch.countDown();
                }
                return null;
            });
            ref.call(gs, "getAllMemberList", groupCode, force, cb);
            latch.await(15, TimeUnit.SECONDS);
            java.util.Map<String, Object> result = (java.util.Map<String, Object>) holder[0];
            if (result != null) groupMemberCache.put(groupCode, result);
            return result;
        } catch (Throwable t) {
            L.e("getAllMembers " + groupCode, t);
            return null;
        }
    }

    /** Resolve a message sender's role from an already fetched QQ member map, without blocking. */
    public String cachedGroupMemberRole(long groupCode, String uid, long uin) {
        java.util.Map<String, Object> members = groupMemberCache.get(groupCode);
        if (members == null) return "";
        Object info = uid == null || uid.isEmpty() ? null : members.get(uid);
        if (info == null && uin != 0) {
            for (Object candidate : members.values()) {
                if (candidate != null && ref.getLong(candidate, "uin") == uin) {
                    info = candidate;
                    break;
                }
            }
        }
        if (info == null) return "";
        Object role = ref.getOrNull(info, "role");
        String name = "";
        try {
            if (role instanceof Enum) name = ((Enum<?>) role).name();
            else if (role != null) name = String.valueOf(ref.call(role, "name"));
        } catch (Throwable ignore) {}
        name = name.toUpperCase(java.util.Locale.ROOT);
        if (name.contains("OWNER")) return "owner";
        if (name.contains("ADMIN")) return "admin";
        if (name.contains("MEMBER")) return "member";
        return "";
    }

    /** Prime one group's member map off the kernel callback thread. */
    public void warmGroupMembersAsync(long groupCode) {
        if (groupCode == 0 || groupMemberCache.containsKey(groupCode)
                || !groupMemberWarmups.add(groupCode)) return;
        Thread t = new Thread(() -> {
            try { getAllMembers(groupCode); }
            finally { groupMemberWarmups.remove(groupCode); }
        }, "pool-4-thread-2");
        t.setDaemon(true);
        t.start();
    }

    /** History conversion may wait once so its role metadata is correct on the first page. */
    public void ensureGroupMembers(long groupCode) {
        if (groupCode != 0 && !groupMemberCache.containsKey(groupCode)) getAllMembers(groupCode);
    }

    /** Cached group simple-infos; triggers a refresh and waits briefly if the cache is empty. */
    public java.util.Collection<Object> getGroupList() {
        if (groupInfoCache.isEmpty()) {
            Object gs = getGroupService();
            if (gs != null) {
                try {
                    Object cb = Proxy.newProxyInstance(ref.cl, new Class[]{ref.cls(OPERATE_CB)}, (p, m, a) -> null);
                    ref.call(gs, "getGroupList", false, cb);
                    for (int i = 0; i < 30 && groupInfoCache.isEmpty(); i++) Thread.sleep(100);
                } catch (Throwable t) { L.e("getGroupList", t); }
            }
        }
        return groupInfoCache.values();
    }

    // ---------- group management (wait on kernel IOperateCallback / IKickMemberOperateCallback) ----------
    public static final class OpResult {
        public int code = -1;
        public String msg = "";
        public boolean timedOut;
        public boolean ok() { return !timedOut && code == 0; }
        public String describe() {
            if (timedOut) return msg == null || msg.isEmpty() ? "timeout" : msg;
            if (msg == null || msg.isEmpty()) return "code=" + code;
            return "code=" + code + " " + msg;
        }
    }

    private interface GroupCall {
        void run(Object gs, Object cb) throws Exception;
    }

    private OpResult awaitGroup(String cbClass, String label, GroupCall call) {
        OpResult r = new OpResult();
        Object gs = getGroupService();
        if (gs == null) {
            r.msg = "group service not ready";
            return r;
        }
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicInteger code = new java.util.concurrent.atomic.AtomicInteger(-1);
            final String[] wording = new String[]{""};
            Object cb = Proxy.newProxyInstance(ref.cl, new Class[]{ref.cls(cbClass)}, (p, m, args) -> {
                if ("onResult".equals(m.getName()) && args != null && args.length >= 1) {
                    int c = Ref.asInt(args[0]);
                    if (args.length >= 2) wording[0] = Ref.asStr(args[1]);
                    if (args.length >= 3 && args[2] instanceof List) {
                        for (Object kr : (List<?>) args[2]) {
                            try {
                                int krCode = Ref.asInt(ref.get(kr, "result"));
                                if (krCode != 0) {
                                    c = krCode;
                                    if (wording[0] == null || wording[0].isEmpty())
                                        wording[0] = "kick result " + krCode;
                                }
                            } catch (Throwable ignore) {}
                        }
                    }
                    code.set(c);
                    latch.countDown();
                }
                return defOf(m.getReturnType());
            });
            call.run(gs, cb);
            if (!latch.await(15, TimeUnit.SECONDS)) {
                r.timedOut = true;
                r.msg = label + " timeout";
                return r;
            }
            r.code = code.get();
            r.msg = wording[0] == null ? "" : wording[0];
        } catch (Throwable t) {
            L.e(label, t);
            r.msg = String.valueOf(t);
        }
        return r;
    }

    public OpResult kickMember(long groupCode, String uid, boolean rejectAddReq) {
        return awaitGroup(KICK_CB, "kickMember", (gs, cb) -> {
            java.util.ArrayList<String> uids = new java.util.ArrayList<>();
            uids.add(uid);
            ref.call(gs, "kickMember", groupCode, uids, rejectAddReq, "", cb);
        });
    }
    public OpResult inviteToGroup(long groupCode, String uid) {
        return awaitGroup(OPERATE_CB, "inviteToGroup", (gs, cb) -> {
            java.util.ArrayList<String> uids = new java.util.ArrayList<>();
            uids.add(uid);
            ref.call(gs, "inviteToGroup", groupCode, uids, cb);
        });
    }
    public OpResult banMember(long groupCode, String uid, int seconds) {
        return awaitGroup(OPERATE_CB, "setMemberShutUp", (gs, cb) -> {
            Object info = ref.neu(SHUTUP_INFO);
            ref.set(info, "uid", uid);
            ref.set(info, "timeStamp", seconds);
            java.util.ArrayList<Object> list = new java.util.ArrayList<>();
            list.add(info);
            ref.call(gs, "setMemberShutUp", groupCode, list, cb);
        });
    }
    public OpResult wholeBan(long groupCode, boolean enable) {
        return awaitGroup(OPERATE_CB, "setGroupShutUp",
                (gs, cb) -> ref.call(gs, "setGroupShutUp", groupCode, enable, cb));
    }
    public OpResult setCard(long groupCode, String uid, String card) {
        return awaitGroup(OPERATE_CB, "modifyMemberCardName",
                (gs, cb) -> ref.call(gs, "modifyMemberCardName", groupCode, uid, card == null ? "" : card, cb));
    }
    public OpResult setAdmin(long groupCode, String uid, boolean enable) {
        return awaitGroup(OPERATE_CB, "modifyMemberRole", (gs, cb) -> {
            Object role = ref.getStatic(MEMBER_ROLE, enable ? "ADMIN" : "MEMBER");
            ref.call(gs, "modifyMemberRole", groupCode, uid, role, cb);
        });
    }
    public OpResult setGroupName(long groupCode, String name) {
        final String wanted = name == null ? "" : name;
        final boolean normalMember = isNormalGroupMember(groupCode);
        OpResult primary = awaitGroup(OPERATE_CB, "modifyGroupName",
                (gs, cb) -> ref.call(gs, "modifyGroupName", groupCode, wanted, normalMember, cb));
        if (!primary.ok()) return primary;
        if (refreshAndVerifyGroupName(groupCode, wanted)) return primary;

        // QQ 9.3.55 can acknowledge modifyGroupName after only updating the conversation-side
        // TroopInfo cache. The group detail page then still sees an empty groupName. Persist the
        // same field through the filtered V2 detail API, so unrelated group settings are untouched.
        OpResult detail = awaitGroup(OPERATE_CB, "modifyGroupDetailInfoV2(groupName)", (gs, cb) -> {
            Object req = ref.neu("com.tencent.qqnt.kernel.nativeinterface.GroupModifyInfoReq");
            ref.put(req, "groupCode", groupCode);
            Object filter = ref.get(req, "filter");
            Object info = ref.get(req, "modifyInfo");
            if (filter == null || info == null)
                throw new IllegalStateException("GroupModifyInfoReq fields unavailable");
            ref.put(filter, "groupName", 1);
            ref.put(info, "groupName", wanted);
            ref.call(gs, "modifyGroupDetailInfoV2", req, 0, cb);
        });
        if (!detail.ok()) return detail;
        if (refreshAndVerifyGroupName(groupCode, wanted)) return detail;

        OpResult failed = new OpResult();
        failed.msg = "group name write was acknowledged but read-back did not match";
        return failed;
    }

    /** Matches QQ 9.3.55 TroopOperationRepo: the boolean is isNormalMember. */
    private boolean isNormalGroupMember(long groupCode) {
        Object info = groupInfo(groupCode);
        if (info == null) return true;
        try {
            Object role = ref.get(info, "memberRole");
            String roleName;
            if (role instanceof Enum) roleName = ((Enum<?>) role).name();
            else roleName = role == null ? "" : String.valueOf(ref.call(role, "name"));
            roleName = roleName.toUpperCase(java.util.Locale.ROOT);
            return !(roleName.contains("OWNER") || roleName.contains("ADMIN"));
        } catch (Throwable t) {
            L.e("read group memberRole " + groupCode, t);
            return true;
        }
    }

    private boolean refreshAndVerifyGroupName(long groupCode, String wanted) {
        refreshGroupList();
        Object info = groupInfoCache.get(groupCode);
        if (info == null) return false;
        return wanted.equals(Ref.asStr(ref.get(info, "groupName")));
    }

    /** NT IKernelGroupService.setHeader(groupCode, localPath). Owner/admin only. */
    public OpResult setGroupHeader(long groupCode, String path) {
        return awaitGroup(OPERATE_CB, "setHeader",
                (gs, cb) -> ref.call(gs, "setHeader", groupCode, path == null ? "" : path, cb));
    }

    /**
     * groupFlagExt3 bit 0x2000000 = 群标识 / 群荣誉 AIO.
     * The bit SET means the honor badge switch is OFF. This is NOT 成员群头衔.
     */
    public static final int HONOR_AIO_FLAG = 33554432;

    /** Result of getMemberExtInfo: group-level title display flags. */
    public static final class MemberExtFlags {
        public int code = -1;
        public String msg = "";
        public int userShowFlag;
        public int userShowFlagNew;
        public int sysShowFlag;
        public final java.util.List<int[]> levelIds = new ArrayList<>();
        public final java.util.List<String> levelNames = new ArrayList<>();
        public final java.util.List<int[]> levelIdsNew = new ArrayList<>();
        public final java.util.List<String> levelNamesNew = new ArrayList<>();
        public boolean ok() { return code == 0; }
        /** AIO 专属/成员群头衔: cGroupRankUserFlag == 1. */
        public boolean titleOpen() { return userShowFlag == 1; }
    }

    private void copyLevelNames(Object result, String field,
                                java.util.List<int[]> ids, java.util.List<String> names) {
        try {
            Object list = ref.get(result, field);
            if (!(list instanceof java.util.Collection)) return;
            for (Object item : (java.util.Collection<?>) list) {
                ids.add(new int[]{Ref.asInt(ref.get(item, "level"))});
                names.add(Ref.asStr(ref.get(item, "strName")));
            }
        } catch (Throwable ignore) {}
    }

    /** Runtime methods of a QQ type whose name mentions title/rank/show/level/switch. */
            public java.util.List<String> dumpServiceMethods(String className) {
        java.util.List<String> out = new ArrayList<>();
        try {
            Class<?> c = ref.clsOrNull(className);
            if (c == null) {
                out.add("missing:" + className);
                return out;
            }
            for (java.lang.reflect.Method m : c.getDeclaredMethods())
                out.add(m.getName() + java.util.Arrays.toString(m.getParameterTypes()));
        } catch (Throwable t) {
            out.add("error:" + t);
        }
        return out;
    }

    public OpResult setHonorAioSwitch(long groupCode, boolean open) {
        return awaitGroup(OPERATE_CB, "modifyGroupDetailInfo", (gs, cb) -> {
            Object info = ref.neu("com.tencent.qqnt.kernel.nativeinterface.GroupModifyInfo");
            ref.put(info, "groupFlagExt3", open ? 0 : HONOR_AIO_FLAG);
            ref.put(info, "groupFlagExt3Mask", HONOR_AIO_FLAG);
            ref.call(gs, "modifyGroupDetailInfo", groupCode, info, cb);
        });
    }


    public boolean callHonorAioService(long groupCode, boolean open) {
        try {
            Object runtime = appRuntime();
            if (runtime == null) return false;
            Class<?> svc = ref.cls("com.tencent.mobileqq.troop.honor.api.ITroopHonorService");
            Object honor = ref.call(runtime, "getRuntimeService", svc, "");
            if (honor == null) return false;
            ref.call(honor, "updateTroopHonorAIOSwitch", String.valueOf(groupCode), open);
            return true;
        } catch (Throwable t) {
            L.e("callHonorAioService", t);
            return false;
        }
    }

    public int groupFlagExt3(long groupCode) {
        Object gi = groupInfo(groupCode);
        return gi == null ? 0 : Ref.asInt(ref.get(gi, "groupFlagExt3"));
    }


    /**
     * Force-read group title-display flags via IKernelGroupService.getMemberExtInfo.
     * userShowFlag=1 means 成员群头衔 / 专属头衔 is on (cGroupRankUserFlag).
     * userShowFlagNew=0 means 等级头衔 is on (inverted).
     */
    public MemberExtFlags getMemberExtInfo(long groupCode) {
        MemberExtFlags out = new MemberExtFlags();
        Object gs = getGroupService();
        if (gs == null) {
            out.msg = "group service not ready";
            return out;
        }
        try {
            Object req = ref.neu("com.tencent.qqnt.kernel.nativeinterface.GroupMemberExtReq");
            Object titleType = ref.getStatic(
                    "com.tencent.qqnt.kernel.nativeinterface.MemberExtSourceType", "TITLETYPE");
            ref.put(req, "sourceType", ref.call(titleType, "ordinal"));
            ref.put(req, "groupCode", groupCode);
            ref.set(req, "beginUin", "0");
            ref.set(req, "dataTime", "0");
            ArrayList<Long> uins = new ArrayList<>();
            try { uins.add(Long.parseLong(selfUin())); } catch (Exception ignore) { uins.add(0L); }
            ref.set(req, "uinList", uins);
            Object filter = ref.neu("com.tencent.qqnt.kernel.nativeinterface.MemberExtInfoFilter");
            ref.put(filter, "userShowFlag", 1);
            ref.put(filter, "userShowFlagNew", 1);
            ref.put(filter, "sysShowFlag", 1);
            ref.put(filter, "levelName", 1);
            ref.put(filter, "levelNameNew", 1);
            ref.put(filter, "dataTime", 1);
            ref.put(filter, "specialTitle", 1);
            ref.set(req, "memberExtFilter", filter);
            final CountDownLatch latch = new CountDownLatch(1);
            final Object[] holder = new Object[1];
            final int[] code = new int[]{-1};
            final String[] msg = new String[]{""};
            Object cb = Proxy.newProxyInstance(ref.cl, new Class[]{ref.cls(MEMBER_EXT_CB)}, (p, m, args) -> {
                if ("onResult".equals(m.getName()) && args != null && args.length >= 2) {
                    code[0] = Ref.asInt(args[0]);
                    msg[0] = Ref.asStr(args[1]);
                    if (args.length >= 3) holder[0] = args[2];
                    latch.countDown();
                }
                return null;
            });
            ref.call(gs, "getMemberExtInfo", req, cb);
            if (!latch.await(15, TimeUnit.SECONDS)) {
                out.msg = "getMemberExtInfo timeout";
                return out;
            }
            out.code = code[0];
            out.msg = msg[0] == null ? "" : msg[0];
            if (holder[0] != null) {
                out.userShowFlag = Ref.asInt(ref.get(holder[0], "userShowFlag"));
                out.userShowFlagNew = Ref.asInt(ref.get(holder[0], "userShowFlagNew"));
                out.sysShowFlag = Ref.asInt(ref.get(holder[0], "sysShowFlag"));
                copyLevelNames(holder[0], "msgLevelName", out.levelIds, out.levelNames);
                copyLevelNames(holder[0], "msgLevelNameNew", out.levelIdsNew, out.levelNamesNew);
            }
        } catch (Throwable t) {
            L.e("getMemberExtInfo " + groupCode, t);
            out.msg = String.valueOf(t);
        }
        return out;
    }


    /** Local DB write so AIO reads cGroupRankUserFlag without waiting for a push. */
    public java.util.List<String> dumpClassFields(String className) {
        java.util.List<String> out = new ArrayList<>();
        try {
            Class<?> c = ref.clsOrNull(className);
            if (c == null) {
                out.add("missing");
                return out;
            }
            for (java.lang.reflect.Field f : c.getDeclaredFields())
                out.add(f.getName() + ":" + f.getType().getSimpleName());
        } catch (Throwable t) {
            out.add("error:" + t);
        }
        return out;
    }

    public OpResult setIdentityTitleInfo(long groupCode, boolean show) {
        String reqName = "com.tencent.qqnt.kernel.nativeinterface.SetIdentityTitleInfoReq";
        if (ref.clsOrNull(reqName) == null) {
            OpResult missing = new OpResult();
            missing.msg = "SetIdentityTitleInfoReq missing";
            return missing;
        }
        return awaitGroup(OPERATE_CB, "setIdentityTitleInfo", (gs, cb) -> {
            Object req = ref.neu(reqName);
            int flag = show ? 1 : 0;
            for (java.lang.reflect.Field f : req.getClass().getDeclaredFields()) {
                String n = f.getName();
                Class<?> t = f.getType();
                try {
                    if (n.toLowerCase().contains("group") && (t == long.class || t == Long.class))
                        ref.put(req, n, groupCode);
                    else if ((n.toLowerCase().contains("show") || n.toLowerCase().contains("flag")
                            || n.toLowerCase().contains("switch") || n.toLowerCase().contains("title")
                            || n.toLowerCase().contains("rank") || n.toLowerCase().contains("open"))
                            && (t == int.class || t == Integer.class || t == byte.class || t == Byte.class))
                        ref.put(req, n, flag);
                    else if (n.equals("groupCode") || n.equals("group_code"))
                        ref.put(req, n, groupCode);
                } catch (Throwable ignore) {}
            }
            ref.call(gs, "setIdentityTitleInfo", req, cb);
        });
    }

    public OpResult setGroupIdentityLevelInfo(long groupCode, boolean show) {
        OpResult r = new OpResult();
        String reqName = "com.tencent.qqnt.kernel.nativeinterface.GIMSetGroupLevelInfoReq";
        String cbName = "com.tencent.qqnt.kernel.nativeinterface.ISetGroupIdentityLevelInfoCallback";
        if (ref.clsOrNull(reqName) == null) {
            r.msg = "GIMSetGroupLevelInfoReq missing";
            return r;
        }
        if (ref.clsOrNull(cbName) == null) cbName = OPERATE_CB;
        Object gs = getGroupService();
        if (gs == null) {
            r.msg = "group service not ready";
            return r;
        }
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final int[] code = new int[]{-1};
            final String[] wording = new String[]{""};
            Object cb = Proxy.newProxyInstance(ref.cl, new Class[]{ref.cls(cbName)}, (p, m, args) -> {
                if (args != null && args.length >= 1) {
                    code[0] = Ref.asInt(args[0]);
                    if (args.length >= 2) wording[0] = Ref.asStr(args[1]);
                    if (args.length >= 3 && args[2] != null) {
                        wording[0] = wording[0] + " rsp=" + args[2];
                        try {
                            for (java.lang.reflect.Field f : args[2].getClass().getDeclaredFields()) {
                                f.setAccessible(true);
                                wording[0] = wording[0] + " " + f.getName() + "=" + f.get(args[2]);
                            }
                        } catch (Throwable ignore) {}
                    }
                    latch.countDown();
                }
                return defOf(m.getReturnType());
            });
            Object req = ref.neu(reqName);
            ref.put(req, "groupCode", groupCode);
            ref.put(req, "levelFlag", show ? 1 : 0);
            ref.put(req, "levelNewFlag", show ? 0 : 1);
            ref.put(req, "useNewLevel", 1);
            ref.call(gs, "setGroupIdentityLevelInfo", req, cb);
            if (!latch.await(15, TimeUnit.SECONDS)) {
                r.timedOut = true;
                r.msg = "setGroupIdentityLevelInfo timeout";
                return r;
            }
            r.code = code[0];
            r.msg = wording[0] == null ? "" : wording[0];
        } catch (Throwable t) {
            L.e("setGroupIdentityLevelInfo", t);
            r.msg = String.valueOf(t);
        }
        return r;
    }

    public MemberExtFlags getGroupMemberLevelInfo(long groupCode) {
        MemberExtFlags out = new MemberExtFlags();
        Object gs = getGroupService();
        if (gs == null) {
            out.msg = "group service not ready";
            return out;
        }
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final int[] code = new int[]{-1};
            final String[] msg = new String[]{""};
            Object cb = Proxy.newProxyInstance(ref.cl, new Class[]{ref.cls(OPERATE_CB)}, (p, m, args) -> {
                if (args != null && args.length >= 1) {
                    Object a0 = args[0];
                    if (a0 instanceof Boolean) code[0] = ((Boolean) a0) ? 0 : 1;
                    else code[0] = Ref.asInt(a0);
                    if (args.length >= 2) msg[0] = Ref.asStr(args[1]);
                    latch.countDown();
                }
                return null;
            });
            ref.call(gs, "getGroupMemberLevelInfo", groupCode, cb);
            if (!latch.await(15, TimeUnit.SECONDS)) {
                out.msg = "getGroupMemberLevelInfo timeout";
                return out;
            }
            out.code = code[0];
            out.msg = msg[0];
        } catch (Throwable t) {
            L.e("getGroupMemberLevelInfo", t);
            out.msg = String.valueOf(t);
        }
        return out;
    }

    /** TroopInfo.extDBInfo title flags as AIO/群管理 actually read them. */
    public int[] troopExtRankFlags(long groupCode) {
        int[] out = new int[]{-1, -1};
        try {
            Object runtime = appRuntime();
            if (runtime == null) return out;
            Class<?> svc = ref.cls("com.tencent.mobileqq.troop.api.ITroopInfoService");
            Object infoSvc = ref.call(runtime, "getRuntimeService", svc, "");
            if (infoSvc == null) return out;
            Object troop = ref.call(infoSvc, "findTroopInfo", String.valueOf(groupCode));
            if (troop == null) return out;
            Object ext = ref.get(troop, "extDBInfo");
            if (ext == null) return out;
            out[0] = Ref.asInt(ref.get(ext, "cGroupRankUserFlag"));
            out[1] = Ref.asInt(ref.get(ext, "cNewGroupRankUserFlag"));
        } catch (Throwable t) {
            L.e("troopExtRankFlags", t);
        }
        return out;
    }

    public boolean updateLocalRankSwitch(long groupCode, boolean show) {
        try {
            Class<?> apiCls = ref.cls("com.tencent.qqnt.troop.ITroopExtInfoDBApi");
            Object api = ref.callS("com.tencent.mobileqq.qroute.QRoute", "api", apiCls);
            if (api == null) return false;
            Byte rank = show ? (byte) 1 : (byte) 0;
            ref.call(api, "updateTroopLevelSwitch", String.valueOf(groupCode), rank, null);
            return true;
        } catch (Throwable t) {
            L.e("updateLocalRankSwitch", t);
            return false;
        }
    }

    public void refreshGroupList() {
        Object gs = getGroupService();
        if (gs == null) return;
        try {
            Object cb = Proxy.newProxyInstance(ref.cl, new Class[]{ref.cls(OPERATE_CB)}, (p, m, a) -> null);
            ref.call(gs, "getGroupList", true, cb);
            for (int i = 0; i < 20; i++) Thread.sleep(100);
        } catch (Throwable t) {
            L.e("refreshGroupList", t);
        }
    }

    public OpResult setGroupEssence(long groupCode, long msgSeq, long msgRandom, boolean add) {
        String digestReq = "com.tencent.qqnt.kernel.nativeinterface.DigestReq";
        String digestCb = "com.tencent.qqnt.kernel.nativeinterface.IDigestCallback";
        if (ref.clsOrNull(digestReq) == null)
            digestReq = "com.tencent.qqnt.kernelpublic.nativeinterface.DigestReq";
        if (ref.clsOrNull(digestCb) == null)
            digestCb = "com.tencent.qqnt.kernel.nativeinterface.IOperateCallback";
        String cbClass = digestCb;
        String reqName = digestReq;
        return awaitGroup(cbClass, add ? "addGroupEssence" : "removeGroupEssence", (gs, cb) -> {
            Object req = ref.neu(reqName);
            try { ref.put(req, "groupCode", String.valueOf(groupCode)); }
            catch (Throwable ignore) { ref.put(req, "groupCode", groupCode); }
            try { ref.put(req, "msgSeq", (int) msgSeq); }
            catch (Throwable ignore) { ref.put(req, "msgSeq", msgSeq); }
            try { ref.put(req, "msgRandom", (int) msgRandom); }
            catch (Throwable ignore) { ref.put(req, "msgRandom", msgRandom); }
            ref.call(gs, add ? "addGroupEssence" : "removeGroupEssence", req, cb);
        });
    }

    /** Cached GroupSimpleInfo for one group, or null. */
    public Object groupInfo(long groupCode) {
        if (groupInfoCache.isEmpty()) getGroupList();
        return groupInfoCache.get(groupCode);
    }

    private static final String GROUP_INFO_SOURCE =
            "com.tencent.qqnt.kernel.nativeinterface.GroupInfoSource";

    /** 内核 simple-info 里名字为空的群，这里存向内核要回来的真名。 */
    private final java.util.concurrent.ConcurrentHashMap<Long, String> groupNameDetail =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<Long> groupNameFetching =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 群名。
     *
     * <p>内核的 {@code GroupSimpleInfo.groupName} 对某些群是空的——实测测试群
     * 280183116 就是，{@code guild.list} 与 {@code guild.get} 一起读回来都是空。这时依次
     * 退到 {@code remarkName} 和一次群详情查询。详情是异步的，第一次调用通常还拿不到，
     * 拿回来之后进缓存，之后每次都有。
     */
    public String groupName(long groupCode) {
        if (groupCode == 0) return "";
        String name = infoName(groupInfoCache.get(groupCode));
        if (!name.isEmpty()) return name;
        String cached = groupNameDetail.get(groupCode);
        if (cached != null) return cached;
        // 只在确实认得的群上去问详情：未知 id 问一次也是白问。
        if (groupInfoCache.containsKey(groupCode) && groupNameFetching.add(groupCode)) {
            fetchGroupDetailName(groupCode);
        }
        return "";
    }

    /**
     * 同 {@link #groupName}，但会给异步的详情查询最多 {@code timeoutMs} 的等待。
     *
     * <p>单个群的显式查询（{@code guild.get} / {@code channel.get}）用它：空名字看着像
     * 实现端漏了字段，等一小会儿比让调用方自己重试友好。列表接口用异步版本，不该为了
     * 一个群把整页拖住。
     */
    public String groupNameWait(long groupCode, long timeoutMs) {
        String name = groupName(groupCode);
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        while (name.isEmpty() && System.currentTimeMillis() < deadline) {
            String cached = groupNameDetail.get(groupCode);
            if (cached != null) name = cached;
            else if (!groupNameFetching.contains(groupCode)) break;   // 没在查了，等也没用
            else try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        return name.isEmpty() ? groupName(groupCode) : name;
    }

    private String infoName(Object gi) {
        if (gi == null) return "";
        try {
            String name = Ref.asStr(ref.get(gi, "groupName"));
            if (name != null && !name.isEmpty()) return name;
            String remark = Ref.asStr(ref.get(gi, "remarkName"));
            return remark == null ? "" : remark;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 向内核要一次群详情（异步），只要名字。失败就丢掉这次尝试，下次调用会再试。 */
    private void fetchGroupDetailName(long groupCode) {
        Thread t = new Thread(() -> {
            try {
                Object gs = getGroupService();
                if (gs == null) return;
                final CountDownLatch latch = new CountDownLatch(1);
                final String[] found = new String[]{""};
                Object cb = Proxy.newProxyInstance(ref.cl, new Class[]{ref.cls(OPERATE_CB)}, (p, m, args) -> {
                    if ("onResult".equals(m.getName()) && args != null && args.length >= 3
                            && args[2] instanceof List) {
                        for (Object item : (List<?>) args[2]) {
                            String name = Ref.asStr(ref.get(item, "groupName"));
                            if (name != null && !name.isEmpty()) { found[0] = name; break; }
                        }
                        latch.countDown();
                    }
                    return defOf(m.getReturnType());
                });
                ref.call(gs, "getGroupDetailInfo", groupCode, groupInfoSource(), cb);
                if (latch.await(15, TimeUnit.SECONDS) && !found[0].isEmpty()) {
                    groupNameDetail.put(groupCode, found[0]);
                    L.i("group detail name " + groupCode + " = " + found[0]);
                }
            } catch (Throwable e) {
                L.e("fetchGroupDetailName " + groupCode, e);
            } finally {
                groupNameFetching.remove(groupCode);
            }
        }, "pool-4-thread-3");
        t.setDaemon(true);
        t.start();
    }

    /** {@code GroupInfoSource} 里挑一个常量：优先 KAIO，取不到就用第一个非 null 的。 */
    private Object groupInfoSource() {
        try {
            Object[] constants = ref.cls(GROUP_INFO_SOURCE).getEnumConstants();
            if (constants != null) {
                for (Object o : constants) if (o != null && "KAIO".equals(String.valueOf(o))) return o;
                for (Object o : constants) if (o != null) return o;
            }
        } catch (Throwable t) {
            L.e("groupInfoSource", t);
        }
        return null;
    }

    /** Resolve a uin to its QQNT uid via the profile service (synchronous). Empty on failure. */
    @SuppressWarnings("unchecked")
    public String resolveUid(long uin) {
        try {
            Object profile = getProfileService();
            if (profile == null) return "";
            ArrayList<Long> uins = new ArrayList<>();
            uins.add(uin);
            Object res = ref.call(profile, "getUidByUin", "", uins);
            if (res instanceof java.util.Map) {
                Object uid = ((java.util.Map<Object, Object>) res).get(uin);
                if (uid == null) uid = ((java.util.Map<Object, Object>) res).get(Long.valueOf(uin));
                return uid == null ? "" : String.valueOf(uid);
            }
        } catch (Throwable t) {
            L.e("resolveUid " + uin, t);
        }
        return "";
    }

    /** Resolve a QQNT uid to uin via the profile service (synchronous). 0 on failure. */
    @SuppressWarnings("unchecked")
    public long resolveUin(String uid) {
        if (uid == null || uid.isEmpty()) return 0;
        try {
            Object profile = getProfileService();
            if (profile == null) return 0;
            ArrayList<String> uids = new ArrayList<>();
            uids.add(uid);
            Object res = ref.call(profile, "getUinByUid", "", uids);
            if (res instanceof java.util.Map) {
                Object uin = ((java.util.Map<Object, Object>) res).get(uid);
                return uin == null ? 0 : Ref.asLong(uin);
            }
        } catch (Throwable t) {
            L.e("resolveUin", t);
        }
        return 0;
    }

    /** Approve or refuse a friend request. flag/reqTime is BuddyReq.reqTime. */
    public OpResult approvalFriendRequest(String friendUid, boolean accept, String refuseMsg, long reqTime) {
        OpResult r = new OpResult();
        Object buddy = getBuddyService();
        if (buddy == null) {
            r.msg = "buddy service not ready";
            return r;
        }
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicInteger code = new java.util.concurrent.atomic.AtomicInteger(-1);
            final String[] wording = new String[]{""};
            Object cb = Proxy.newProxyInstance(ref.cl, new Class[]{ref.cls(OPERATE_CB)}, (p, m, args) -> {
                if ("onResult".equals(m.getName()) && args != null && args.length >= 1) {
                    code.set(Ref.asInt(args[0]));
                    if (args.length >= 2) wording[0] = Ref.asStr(args[1]);
                    latch.countDown();
                }
                return defOf(m.getReturnType());
            });
            Object req = ref.neu("com.tencent.qqnt.kernel.nativeinterface.ApprovalBuddyRequest",
                    friendUid == null ? "" : friendUid, accept,
                    refuseMsg == null ? "" : refuseMsg, reqTime);
            ref.call(buddy, "approvalFriendRequest", req, cb);
            if (!latch.await(15, TimeUnit.SECONDS)) {
                r.timedOut = true;
                r.msg = "approvalFriendRequest timeout";
                return r;
            }
            r.code = code.get();
            r.msg = wording[0] == null ? "" : wording[0];
        } catch (Throwable t) {
            L.e("approvalFriendRequest", t);
            r.msg = String.valueOf(t);
        }
        return r;
    }

    /** Delete one buddy without additionally blocking the account. */
    public OpResult deleteFriend(long uin) {
        OpResult r = new OpResult();
        Object buddy = getBuddyService();
        if (buddy == null) { r.msg = "buddy service not ready"; return r; }
        String uid = resolveUid(uin);
        if (uid == null || uid.isEmpty()) { r.msg = "cannot resolve friend uid"; return r; }
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicInteger code = new java.util.concurrent.atomic.AtomicInteger(-1);
            final String[] wording = new String[]{""};
            Object cb = Proxy.newProxyInstance(ref.cl, new Class[]{ref.cls(OPERATE_CB)}, (p, m, args) -> {
                if ("onResult".equals(m.getName()) && args != null && args.length >= 1) {
                    code.set(Ref.asInt(args[0]));
                    if (args.length >= 2) wording[0] = Ref.asStr(args[1]);
                    latch.countDown();
                }
                return defOf(m.getReturnType());
            });
            Object info = ref.neu("com.tencent.qqnt.kernel.nativeinterface.DelBuddyInfo",
                    uid, false, false);
            ref.call(buddy, "delBuddy", info, cb);
            if (!latch.await(15, TimeUnit.SECONDS)) {
                r.timedOut = true;
                r.msg = "delBuddy timeout";
                return r;
            }
            r.code = code.get();
            r.msg = wording[0] == null ? "" : wording[0];
        } catch (Throwable t) {
            L.e("delBuddy", t);
            r.msg = String.valueOf(t);
        }
        return r;
    }

    /**
     * Approve or refuse a group join/invite notify.
     * typeEnum is the original GroupNotifyMsgType instance from the kernel notify.
     */
    public OpResult operateGroupNotify(long seq, long groupCode, Object typeEnum, boolean agree, String reason) {
        return awaitGroup(OPERATE_CB, "operateSysNotify", (gs, cb) -> {
            Object target = ref.neu("com.tencent.qqnt.kernel.nativeinterface.GroupNotifyTargetMsg");
            ref.set(target, "seq", seq);
            if (typeEnum != null) ref.set(target, "type", typeEnum);
            ref.set(target, "groupCode", groupCode);
            ref.set(target, "postscript", reason == null || reason.isEmpty() ? " " : reason);
            Object op = ref.neu("com.tencent.qqnt.kernel.nativeinterface.GroupNotifyOperateMsg");
            String opName = agree ? "KAGREE" : "KREFUSE";
            ref.set(op, "operateType",
                    ref.getStatic("com.tencent.qqnt.kernel.nativeinterface.GroupNotifyOperateType", opName));
            ref.set(op, "targetMsg", target);
            ref.call(gs, "operateSysNotify", false, op, cb);
        });
    }

    /** Ordered map uid -> CoreInfo for the current account's complete buddy list. */
    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> getFriendCoreInfos() {
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        Object buddy = getBuddyService();
        Object profile = getProfileService();
        if (buddy == null || profile == null) return out;
        try {
            Object reqType = ref.getStatic(BUDDY_REQ_TYPE, "KNOMAL"); // spelling is QQ 9.3.50's enum
            List<?> categories = (List<?>) ref.call(buddy, "getBuddyListFromCache", "", reqType);
            if (categories == null || categories.isEmpty()) {
                CountDownLatch latch = new CountDownLatch(1);
                Object cb = Proxy.newProxyInstance(ref.cl, new Class[]{ref.cls(OPERATE_CB)}, (p, m, a) -> {
                    if ("onResult".equals(m.getName())) latch.countDown();
                    return null;
                });
                ref.call(buddy, "getBuddyList", true, cb);
                latch.await(10, TimeUnit.SECONDS);
                categories = (List<?>) ref.call(buddy, "getBuddyListFromCache", "", reqType);
            }
            java.util.LinkedHashSet<String> uidSet = new java.util.LinkedHashSet<>();
            if (categories != null) {
                for (Object category : categories) {
                    Object raw = ref.get(category, "buddyUids");
                    if (!(raw instanceof List)) continue;
                    for (Object uid : (List<?>) raw) {
                        String value = Ref.asStr(uid);
                        if (!value.isEmpty()) uidSet.add(value);
                    }
                }
            }
            ArrayList<String> all = new ArrayList<>(uidSet);
            for (int from = 0; from < all.size(); from += 200) {
                ArrayList<String> chunk = new ArrayList<>(all.subList(from, Math.min(from + 200, all.size())));
                Object rawMap = ref.call(profile, "getCoreInfo", "", chunk);
                if (!(rawMap instanceof java.util.Map)) continue;
                java.util.Map<Object, Object> map = (java.util.Map<Object, Object>) rawMap;
                for (String uid : chunk) {
                    Object info = map.get(uid);
                    if (info != null) out.put(uid, info);
                }
            }
        } catch (Throwable t) {
            L.e("getFriendCoreInfos", t);
        }
        return out;
    }

    /** CoreInfo for any resolvable uin (friend or stranger), or null when QQ has no cached profile. */
    @SuppressWarnings("unchecked")
    public Object getCoreInfo(long uin) {
        try {
            String uid = resolveUid(uin);
            Object profile = getProfileService();
            if (uid.isEmpty() || profile == null) return null;
            ArrayList<String> uids = new ArrayList<>();
            uids.add(uid);
            Object raw = ref.call(profile, "getCoreInfo", "", uids);
            if (!(raw instanceof java.util.Map)) return null;
            return ((java.util.Map<Object, Object>) raw).get(uid);
        } catch (Throwable t) {
            L.e("getCoreInfo " + uin, t);
            return null;
        }
    }

}
