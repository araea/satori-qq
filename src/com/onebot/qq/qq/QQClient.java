package com.onebot.qq.qq;

import com.onebot.qq.L;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bridge into QQ 9.3.50 NT kernel: capture session, send, register receive-listener, self identity. */
public final class QQClient {
    // ---- QQ 9.3.50 class names (verified via decompile) ----
    public static final String SESSION_CPP     = "com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession$CppProxy";
    public static final String MSG_LISTENER    = "com.tencent.qqnt.kernel.nativeinterface.IKernelMsgListener";
    public static final String CONTACT         = "com.tencent.qqnt.kernelpublic.nativeinterface.Contact";
    public static final String MSG_ELEMENT     = "com.tencent.qqnt.kernel.nativeinterface.MsgElement";
    public static final String TEXT_ELEMENT    = "com.tencent.qqnt.kernel.nativeinterface.TextElement";
    public static final String IOPERATE_CB     = "com.tencent.qqnt.kernel.nativeinterface.IOperateCallback";
    public static final String MOBILEQQ        = "mqq.app.MobileQQ";
    public static final String GROUP_LISTENER  = "com.tencent.qqnt.kernel.nativeinterface.IKernelGroupListener";
    public static final String MEMBER_LIST_CB  = "com.tencent.qqnt.kernel.nativeinterface.IGroupMemberListCallback";
    public static final String OPERATE_CB      = "com.tencent.qqnt.kernel.nativeinterface.IOperateCallback";

    public static final int CT_C2C = 1;
    public static final int CT_GROUP = 2;

    public interface Listener {
        void onRecvMsgs(List<?> msgRecords);
        void onRecall(int type, String info, long time);
    }

    public final Ref ref;
    private volatile Object session;        // IQQNTWrapperSession
    private volatile boolean listenerRegistered;
    private volatile Listener listener;
    private volatile String selfUin = "";
    private volatile String selfNick = "";
    private final boolean mainProcess;

    public QQClient(ClassLoader cl, boolean mainProcess) {
        this.ref = new Ref(cl);
        this.mainProcess = mainProcess;
    }

    public void setListener(Listener l) { this.listener = l; }

    /** Install hooks that capture the live kernel session as soon as QQ creates it. */
    public void installHooks() {
        try {
            Class<?> sc = ref.cls(SESSION_CPP);
            XposedBridge.hookAllConstructors(sc, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    onSession(p.thisObject);
                }
            });
            L.i("Hooked session ctor: " + SESSION_CPP);
        } catch (Throwable t) {
            L.e("Failed to hook session ctor", t);
        }
    }

    private volatile boolean listenerPollerStarted;

    private void onSession(Object s) {
        if (s == null) return;
        session = s;
        L.d("Captured wrapper session: " + s.getClass().getName());
        if (mainProcess) ensureListenerAsync();
    }

    /** msgService may not be ready the instant the session is created; poll until it is. */
    private synchronized void ensureListenerAsync() {
        if (listenerRegistered || listenerPollerStarted) return;
        listenerPollerStarted = true;
        Thread t = new Thread(() -> {
            for (int i = 0; i < 120 && !listenerRegistered; i++) {
                tryRegisterListener();
                if (listenerRegistered) break;
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }, "onebot-listener-register");
        t.setDaemon(true);
        t.start();
    }

    public Object getSession() { return session; }

    public Object getMsgService() {
        Object s = session;
        if (s == null) return null;
        try { return ref.call(s, "getMsgService"); } catch (Throwable t) { L.e("getMsgService", t); return null; }
    }
    public Object getGroupService() {
        Object s = session; if (s == null) return null;
        try { return ref.call(s, "getGroupService"); } catch (Throwable t) { return null; }
    }
    public Object getProfileService() {
        Object s = session; if (s == null) return null;
        try { return ref.call(s, "getProfileService"); } catch (Throwable t) { return null; }
    }

    private synchronized void tryRegisterListener() {
        if (listenerRegistered) return;
        Object msgService = getMsgService();
        if (msgService == null) return;
        try {
            Class<?> li = ref.cls(MSG_LISTENER);
            Object proxy = Proxy.newProxyInstance(ref.cl, new Class[]{li}, new ListenerHandler());
            ref.call(msgService, "addKernelMsgListener", proxy);
            listenerRegistered = true;
            L.i("Registered IKernelMsgListener (receiving messages)");
            tryRegisterGroupListener();
        } catch (Throwable t) {
            L.e("Failed to register msg listener", t);
        }
    }

    private final class ListenerHandler implements InvocationHandler {
        @Override public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
            String name = m.getName();
            try {
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

    // ---------- identity ----------
    public String selfUin() {
        if (!selfUin.isEmpty()) return selfUin;
        try {
            Object app = ref.callS(MOBILEQQ, "getMobileQQ");
            Object rt = null;
            try { rt = ref.get(app, "mAppRuntime"); } catch (Throwable ignore) {}
            if (rt != null) {
                String u = tryStr(rt, "getCurrentUin");
                if (u == null || u.isEmpty()) u = tryStr(rt, "getAccount");
                if (u != null && !u.isEmpty()) { selfUin = u; }
                if (selfNick.isEmpty()) {
                    String nk = tryStr(rt, "getCurrentNickname");
                    if (nk != null && !nk.isEmpty()) selfNick = nk;
                }
            }
        } catch (Throwable t) { L.e("selfUin", t); }
        return selfUin;
    }
    public String selfNick() { return selfNick; }
    public void learnSelf(String uin, String nick) {
        if (uin != null && !uin.isEmpty()) selfUin = uin;
        if (nick != null && !nick.isEmpty()) selfNick = nick;
    }
    private String tryStr(Object o, String m) { try { return Ref.asStr(ref.call(o, m)); } catch (Throwable t) { return null; } }

    // ---------- sending ----------
    public static final class SendResult { public int code = -1; public String msg = ""; public long msgId; }

    /** Send a pre-built element list to a group (chatType=2) or c2c (chatType=1). */
    public SendResult sendMsg(int chatType, String peerUid, ArrayList<?> elements) {
        SendResult r = new SendResult();
        Object msgService = getMsgService();
        if (msgService == null) { r.msg = "kernel session not ready"; return r; }
        try {
            long msgId = Ref.asLong(ref.call(msgService, "generateMsgUniqueId", chatType, System.currentTimeMillis()));
            r.msgId = msgId;
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
            ref.call(msgService, "sendMsg", msgId, contact, elements, new HashMap<Integer, Object>(), cb);
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

    /** peerUid for a group is the group code string; for c2c it's the target's uid. */
    public String groupPeer(long groupCode) { return String.valueOf(groupCode); }

    // ---------- group queries ----------
    private final java.util.Map<Long, Object> groupInfoCache = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean groupListenerRegistered;

    private synchronized void tryRegisterGroupListener() {
        if (groupListenerRegistered) return;
        Object gs = getGroupService();
        if (gs == null) return;
        try {
            Class<?> li = ref.cls(GROUP_LISTENER);
            Object proxy = Proxy.newProxyInstance(ref.cl, new Class[]{li}, (p, m, args) -> {
                try {
                    if ("onGroupListUpdate".equals(m.getName()) && args != null && args.length >= 2
                            && args[1] instanceof List) {
                        for (Object gi : (List<?>) args[1]) {
                            long code = Ref.asLong(ref.get(gi, "groupCode"));
                            if (code != 0) groupInfoCache.put(code, gi);
                        }
                    }
                } catch (Throwable ignore) {}
                return defOf(m.getReturnType());
            });
            ref.call(gs, "addKernelGroupListener", proxy);
            groupListenerRegistered = true;
            L.i("Registered IKernelGroupListener (group list cache)");
        } catch (Throwable t) {
            L.e("register group listener", t);
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
    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> getAllMembers(long groupCode) {
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
            ref.call(gs, "getAllMemberList", groupCode, false, cb);
            latch.await(15, TimeUnit.SECONDS);
            return (java.util.Map<String, Object>) holder[0];
        } catch (Throwable t) {
            L.e("getAllMembers " + groupCode, t);
            return null;
        }
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
}
