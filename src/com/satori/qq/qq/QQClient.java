package com.satori.qq.qq;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import com.satori.qq.L;
import com.satori.qq.packet.PacketSvc;
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

/** Bridge into the Android QQ NT kernel: capture session, send, register listeners and identity. */
public final class QQClient {
    // ---- QQ 9.3.50 class names, reverified on 9.3.55 ----
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
    public static final String OPERATE_CB      = "com.tencent.qqnt.kernel.nativeinterface.IOperateCallback";
    public static final String KICK_CB         = "com.tencent.qqnt.kernel.nativeinterface.IKickMemberOperateCallback";
    public static final String SHUTUP_INFO     = "com.tencent.qqnt.kernel.nativeinterface.GroupMemberShutUpInfo";
    public static final String MEMBER_ROLE     = "com.tencent.qqnt.kernelpublic.nativeinterface.MemberRole";
    public static final String BUDDY_REQ_TYPE  = "com.tencent.qqnt.kernel.nativeinterface.BuddyListReqType";
    public static final String MSG_OPERATE_CB  = "com.tencent.qqnt.kernel.nativeinterface.IMsgOperateCallback";
    public static final String RICH_MEDIA_GET_REQ = "com.tencent.qqnt.kernel.nativeinterface.RichMediaElementGetReq";
    public static final String VIDEO_URL_CB = "com.tencent.qqnt.kernel.nativeinterface.IVideoPlayUrlCallback";
    public static final String VIDEO_CODEC = "com.tencent.qqnt.kernel.nativeinterface.VideoCodecFormatType";
    public static final String RM_EX_PARAMS = "com.tencent.qqnt.kernel.nativeinterface.RMReqExParams";

    public static final int CT_C2C = 1;
    public static final int CT_GROUP = 2;

    public interface Listener {
        void onRecvMsgs(List<?> msgRecords);
        void onMsgUpdates(List<?> msgRecords);
        void onRecall(int type, String info, long time);
        void onBuddyReq(Object buddyReqInfo);
        void onGroupNotifies(List<?> notifies);
        void onMemberListChange(Object change);
        void onGroupListUpdate(Object updateType, List<?> groups);
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
            buddyListenerRegistered = false;
            buddyListenerSession = null;
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
    public Object getRichMediaService() {
        Object s = session; if (s == null) return null;
        try { return ref.call(s, "getRichMediaService"); } catch (Throwable t) { return null; }
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
                        if (args != null && args.length >= 1 && args[0] != null) {
                            java.util.ArrayList<Object> one = new java.util.ArrayList<>();
                            one.add(args[0]);
                            l.onRecvMsgs(one);
                        }
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
            try {
                String pre = describeElements(elements);
                L.e("sendMsg pre " + pre.replace("\n", " | "), null);
                try (java.io.FileWriter w = new java.io.FileWriter(LAST_SEND_DUMP, false)) {
                    w.write("PRE\n" + pre);
                }
            } catch (Throwable t0) {
                L.e("sendMsg pre dump", t0);
            }
            HashMap<Integer, Object> attrs = new HashMap<>();
            try {
                Object info = ref.neu("com.tencent.qqnt.kernel.nativeinterface.MsgAttributeInfo");
                ref.set(info, "attrType", 0);
                ref.set(info, "attrId", 0L);
                attrs.put(0, info);
            } catch (Throwable t) {
                L.e("msgAttributeInfo", t);
            }
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
            "/storage/emulated/0/Android/data/com.tencent.mobileqq/files/satori-last-send.txt";

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
        if (rec != null) sb.append(describeRecord(rec));
        else sb.append("no record\n");
        L.e("dumpSent " + sb.toString().replace("\n", " | "), null);
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
            } else if (et == 1) {
                Object te = ref.get(e, "textElement");
                String c = Ref.asStr(ref.get(te, "content"));
                sb.append(" textLen=").append(c.length());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Ask the kernel to fetch inner merge-forward records for a just-sent card so the QQ client
     * viewer can open it via getMultiMsg instead of showing 消息加载失败.
     */
    public void prefetchForward(int chatType, String peerUid, long msgId) {
        if (msgId == 0 || peerUid == null || peerUid.isEmpty()) return;
        Object msgService = getMsgService();
        if (msgService == null) return;
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
            Object cb = Proxy.newProxyInstance(ref.cl,
                    new Class[]{ref.cls("com.tencent.qqnt.kernel.nativeinterface.IGetMultiMsgCallback")},
                    (proxy, m, args) -> {
                        if ("onResult".equals(m.getName()) && args != null && args.length >= 1) {
                            code[0] = Ref.asInt(args[0]);
                            if (args.length >= 2) wording[0] = Ref.asStr(args[1]);
                            latch.countDown();
                        }
                        return null;
                    });
            ref.call(msgService, "getMultiMsg", contact, msgId, msgId, cb);
            if (latch.await(8, TimeUnit.SECONDS)) {
                if (code[0] != 0) L.e("getMultiMsg code=" + code[0] + " " + wording[0], null);
            }
        } catch (Throwable t) {
            L.e("prefetchForward", t);
        }
    }

    /** Native merge-forward of already-sent messages. src and dest are usually the same contact. */
    public SendResult multiForward(int chatType, String peerUid, ArrayList<Long> msgIds,
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
            Object contact = ref.neu(CONTACT, chatType, peerUid, "");
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
            ref.call(msgService, "multiForwardMsg", infos, contact, contact, cb);
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
        public boolean ok() { return !timedOut && code == 0; }
        public String describe() {
            if (timedOut) return msg == null || msg.isEmpty() ? "timeout" : msg;
            if (msg == null || msg.isEmpty()) return "code=" + code;
            return "code=" + code + " " + msg;
        }
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
            if (holder[0] instanceof List) r.records = (List<?>) holder[0];
        } catch (Throwable t) {
            L.e("getMsgs history", t);
            r.msg = String.valueOf(t);
        }
        return r;
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
            if (holder[0] instanceof List) r.records = (List<?>) holder[0];
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

    /** peerUid for a group is the group code string; for c2c it's the target's uid. */
    public String groupPeer(long groupCode) { return String.valueOf(groupCode); }

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
    public OpResult quitGroup(long groupCode) {
        return awaitGroup(OPERATE_CB, "quitGroup",
                (gs, cb) -> ref.call(gs, "quitGroup", groupCode, cb));
    }
    public OpResult setGroupName(long groupCode, String name) {
        return awaitGroup(OPERATE_CB, "modifyGroupName",
                (gs, cb) -> ref.call(gs, "modifyGroupName", groupCode, name == null ? "" : name, false, cb));
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
