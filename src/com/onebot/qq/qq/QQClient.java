package com.onebot.qq.qq;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import com.onebot.qq.L;
import com.onebot.qq.packet.PacketSvc;
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
    public static final String KICK_CB         = "com.tencent.qqnt.kernel.nativeinterface.IKickMemberOperateCallback";
    public static final String SHUTUP_INFO     = "com.tencent.qqnt.kernel.nativeinterface.GroupMemberShutUpInfo";
    public static final String MEMBER_ROLE     = "com.tencent.qqnt.kernelpublic.nativeinterface.MemberRole";
    public static final String BUDDY_REQ_TYPE  = "com.tencent.qqnt.kernel.nativeinterface.BuddyListReqType";
    public static final String MSG_OPERATE_CB  = "com.tencent.qqnt.kernel.nativeinterface.IMsgOperateCallback";
    public static final String RICH_MEDIA_GET_REQ = "com.tencent.qqnt.kernel.nativeinterface.RichMediaElementGetReq";

    public static final int CT_C2C = 1;
    public static final int CT_GROUP = 2;

    public interface Listener {
        void onRecvMsgs(List<?> msgRecords);
        void onRecall(int type, String info, long time);
    }

    public final Ref ref;
    private final PacketSvc packetSvc;
    private volatile Object session;        // IQQNTWrapperSession
    private volatile boolean listenerRegistered;
    private volatile Object listenerSession;
    private volatile Listener listener;
    private volatile String selfUin = "";
    private volatile String selfNick = "";
    private final boolean mainProcess;
    private final java.util.concurrent.ConcurrentHashMap<String, MediaDownload> mediaDownloads =
            new java.util.concurrent.ConcurrentHashMap<>();

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
    }

    public void setListener(Listener l) { this.listener = l; }

    /** Install hooks that capture the live kernel session as soon as QQ creates it. */
    public void installHooks() {
        packetSvc.installHooks();
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

    private synchronized void onSession(Object s) {
        if (s == null) return;
        if (session != s) {
            listenerRegistered = false;
            listenerSession = null;
            groupListenerRegistered = false;
            groupListenerSession = null;
            groupInfoCache.clear();
        }
        session = s;
        L.d("Captured wrapper session: " + s.getClass().getName());
        if (mainProcess) ensureListenerAsync();
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
    public PacketSvc packets() { return packetSvc; }

    /** Current QQ AppRuntime, or null while logged out / before account startup. */
    public Object appRuntime() {
        try {
            Object app = ref.callS(MOBILEQQ, "getMobileQQ");
            return app == null ? null : ref.get(app, "mAppRuntime");
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

    /** True only when the current account, NT session, message service and receive listener are ready. */
    public boolean isOnline() {
        Object s = session;
        if (s == null || !listenerRegistered || listenerSession != s) return false;
        if (getMsgService(s, false) == null) return false;
        if (loginActivityOnTop()) return false;
        return !currentUin().isEmpty();
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

    /** Query local/roamed message history around a message sequence; blocks up to 15 seconds. */
    @SuppressWarnings("unchecked")
    public List<?> getMsgs(int chatType, String peerUid, long messageSeq, int count) {
        Object msgService = getMsgService();
        if (msgService == null) return java.util.Collections.emptyList();
        count = Math.max(1, Math.min(100, count));
        try {
            Object contact = ref.neu(CONTACT, chatType, peerUid, "");
            final Object[] holder = new Object[1];
            final int[] code = new int[]{-1};
            CountDownLatch latch = new CountDownLatch(1);
            Object cb = Proxy.newProxyInstance(ref.cl, new Class[]{ref.cls(MSG_OPERATE_CB)}, (p, m, a) -> {
                if ("onResult".equals(m.getName()) && a != null && a.length >= 3) {
                    code[0] = Ref.asInt(a[0]);
                    holder[0] = a[2];
                    latch.countDown();
                }
                return null;
            });
            if (messageSeq > 0) {
                ref.call(msgService, "getMsgsBySeqAndCount", contact, messageSeq, count, true, false, cb);
            } else {
                ref.call(msgService, "getMsgs", contact, 0L, count, true, cb);
            }
            latch.await(15, TimeUnit.SECONDS);
            if (code[0] != 0 || !(holder[0] instanceof List)) return java.util.Collections.emptyList();
            return (List<?>) holder[0];
        } catch (Throwable t) {
            L.e("getMsgs history", t);
            return java.util.Collections.emptyList();
        }
    }

    /** Ask QQ NT to materialize a received rich-media element and return its callback path. */
    public String downloadRichMedia(int chatType, String peerUid, long msgId, long elementId) {
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
                            1, 0, "", 0L, 0, 1});
            ref.call(msgService, "downloadRichMedia", req);
            if (!pending.latch.await(30, TimeUnit.SECONDS)) return "";
            if (pending.errorCode != 0) {
                L.w("downloadRichMedia code=" + pending.errorCode + " " + pending.errorMessage);
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

    /** peerUid for a group is the group code string; for c2c it's the target's uid. */
    public String groupPeer(long groupCode) { return String.valueOf(groupCode); }

    // ---------- group queries ----------
    private final java.util.Map<Long, Object> groupInfoCache = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean groupListenerRegistered;
    private volatile Object groupListenerSession;

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
            if (session != targetSession) return;
            groupListenerSession = targetSession;
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

    // ---------- group management (fire-and-forget via IOperateCallback) ----------
    public boolean kickMember(long groupCode, String uid, boolean rejectAddReq) {
        Object gs = getGroupService(); if (gs == null) return false;
        java.util.ArrayList<String> uids = new java.util.ArrayList<>(); uids.add(uid);
        ref.call(gs, "kickMember", groupCode, uids, rejectAddReq, "", ref.nullCb(KICK_CB));
        return true;
    }
    public boolean banMember(long groupCode, String uid, int seconds) {
        Object gs = getGroupService(); if (gs == null) return false;
        Object info = ref.neu(SHUTUP_INFO);
        ref.set(info, "uid", uid);
        ref.set(info, "timeStamp", seconds);
        java.util.ArrayList<Object> list = new java.util.ArrayList<>(); list.add(info);
        ref.call(gs, "setMemberShutUp", groupCode, list, ref.nullCb(OPERATE_CB));
        return true;
    }
    public boolean wholeBan(long groupCode, boolean enable) {
        Object gs = getGroupService(); if (gs == null) return false;
        ref.call(gs, "setGroupShutUp", groupCode, enable, ref.nullCb(OPERATE_CB));
        return true;
    }
    public boolean setCard(long groupCode, String uid, String card) {
        Object gs = getGroupService(); if (gs == null) return false;
        ref.call(gs, "modifyMemberCardName", groupCode, uid, card == null ? "" : card, ref.nullCb(OPERATE_CB));
        return true;
    }
    public boolean setAdmin(long groupCode, String uid, boolean enable) {
        Object gs = getGroupService(); if (gs == null) return false;
        Object role = ref.getStatic(MEMBER_ROLE, enable ? "ADMIN" : "MEMBER");
        ref.call(gs, "modifyMemberRole", groupCode, uid, role, ref.nullCb(OPERATE_CB));
        return true;
    }
    public boolean quitGroup(long groupCode) {
        Object gs = getGroupService(); if (gs == null) return false;
        ref.call(gs, "quitGroup", groupCode, ref.nullCb(OPERATE_CB));
        return true;
    }
    public boolean setGroupName(long groupCode, String name) {
        Object gs = getGroupService(); if (gs == null) return false;
        ref.call(gs, "modifyGroupName", groupCode, name == null ? "" : name, false, ref.nullCb(OPERATE_CB));
        return true;
    }

    /** Cached GroupSimpleInfo for one group, or null. */
    public Object groupInfo(long groupCode) {
        if (groupInfoCache.isEmpty()) getGroupList();
        return groupInfoCache.get(groupCode);
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
