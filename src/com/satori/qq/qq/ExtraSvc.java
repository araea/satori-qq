package com.satori.qq.qq;

import com.satori.qq.L;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thin wrappers over the QQNT kernel services that the Satori core does not need but the
 * module exposes as QQ-only extensions: profile self-service, group settings, buddy remarks
 * and session flags, and the recent-contact snapshot.
 *
 * <p>Every kernel call here is asynchronous and reports back through a three-argument
 * {@code onResult(int code, String msg, Object payload)}. {@link #call} performs that wait
 * and keeps the payload, so callers can either surface it verbatim or pick fields out of it.
 * The services themselves are obtained from the already-captured session in {@link QQClient};
 * this class never hooks anything.</p>
 */
public final class ExtraSvc {
    private static final String OPERATE_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IOperateCallback";
    private static final String HONOR_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGroupMemberHonorCallback";
    private static final String MUTE_LIST_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IQueryGroupMuteMemberListCallback";
    private static final String RECENT_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IKernelRecentGetContactCallback";
    private static final String GROUP_MEMBER_HONOR_REQ =
            "com.tencent.qqnt.kernel.nativeinterface.GroupMemberHonorReq";
    private static final String GROUP_MSG_MASK =
            "com.tencent.qqnt.kernel.nativeinterface.GroupMsgMask";
    private static final String REMARK_PARAMS =
            "com.tencent.qqnt.kernel.nativeinterface.RemarkParams";
    private static final String REQ_TO_FRIEND =
            "com.tencent.qqnt.kernel.nativeinterface.ReqToFriend";

    private static final long TIMEOUT_MS = 15_000L;

    /** Outcome of one kernel call: transport-level code plus the callback's payload. */
    public static final class Result {
        public int code = -1;
        public String msg = "";
        public boolean timedOut;
        public Object payload;

        public boolean ok() { return !timedOut && code == 0; }

        public String describe() {
            if (timedOut) return msg == null || msg.isEmpty() ? "timeout" : msg;
            if (msg == null || msg.isEmpty()) return "code=" + code;
            return "code=" + code + " " + msg;
        }
    }

    /** One kernel invocation: hand the service and a callback proxy to {@code run}. */
    public interface Call {
        void run(Object svc, Object cb) throws Exception;
    }

    private final QQClient qq;
    private final Ref ref;

    public ExtraSvc(QQClient qq) {
        this.qq = qq;
        this.ref = qq.ref;
    }

    /**
     * Invoke {@code call} and wait for {@code onResult}. The payload (third argument) is kept
     * even when the code is non-zero, so diagnostics can show what QQ returned.
     */
    public Result call(Object svc, String cbClass, String label, Call call) {
        Result r = new Result();
        if (svc == null) {
            r.msg = label + ": service not ready";
            return r;
        }
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicInteger code = new AtomicInteger(-1);
            final String[] wording = new String[]{""};
            final Object[] payload = new Object[]{null};
            Object cb = Proxy.newProxyInstance(ref.cl, new Class[]{ref.cls(cbClass)}, (p, m, args) -> {
                if ("onResult".equals(m.getName()) && args != null && args.length >= 1) {
                    code.set(Ref.asInt(args[0]));
                    if (args.length >= 2) wording[0] = Ref.asStr(args[1]);
                    if (args.length >= 3 && args[2] != null) payload[0] = args[2];
                    latch.countDown();
                } else {
                    L.i("ExtraSvc " + label + " cb." + m.getName() + " args="
                            + (args == null ? 0 : args.length));
                }
                return defOf(m.getReturnType());
            });
            L.i("ExtraSvc invoke " + label + " on " + svc.getClass().getName());
            // QQ's kernel services are main-thread affine: invoking them from an HTTP worker
            // thread returns immediately but the callback never fires. Post the invocation to the
            // main looper (which returns at once) and keep waiting here on the worker thread.
            android.os.Looper main = android.os.Looper.getMainLooper();
            if (main != null && android.os.Looper.myLooper() != main) {
                new android.os.Handler(main).post(() -> {
                    try {
                        call.run(svc, cb);
                        L.i("ExtraSvc dispatched " + label);
                    } catch (Throwable t) {
                        L.e("ExtraSvc " + label + " dispatch", t);
                        latch.countDown();
                    }
                });
            } else {
                call.run(svc, cb);
                L.i("ExtraSvc dispatched " + label);
            }
            if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                r.timedOut = true;
                r.msg = label + " timeout";
                L.i("ExtraSvc " + label + " -> timeout");
                return r;
            }
            r.code = code.get();
            r.msg = wording[0] == null ? "" : wording[0];
            r.payload = payload[0];
            L.i("ExtraSvc " + label + " -> " + r.describe()
                    + (r.payload == null ? "" : " payload=" + r.payload.getClass().getName()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            r.msg = label + " interrupted";
        } catch (Throwable t) {
            L.e(label, t);
            r.msg = String.valueOf(t);
        }
        return r;
    }

    private static Object defOf(Class<?> r) {
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

    private String selfUid() {
        try {
            return qq.resolveUid(parseUin(qq.selfUin()));
        } catch (Throwable t) {
            return "";
        }
    }

    private static long parseUin(String uin) {
        if (uin == null) return 0;
        try { return Long.parseLong(uin.trim()); } catch (NumberFormatException e) { return 0; }
    }

    // ---------------------------------------------------------------- profile

    /** Write the account's personal signature (个性签名). */
    public Result setSignature(String signature) {
        return call(qq.getProfileService(), OPERATE_CB, "setLongNick",
                (svc, cb) -> ref.call(svc, "setLongNick", signature == null ? "" : signature, cb));
    }

    /** Write the account's nickname. */
    public Result setNickname(String nickname) {
        return call(qq.getProfileService(), OPERATE_CB, "setNickName",
                (svc, cb) -> ref.call(svc, "setNickName", nickname == null ? "" : nickname, cb));
    }

    /** Upload a local image as the account avatar; {@code path} must be an absolute file path. */
    public Result setAvatar(String path) {
        return call(qq.getProfileService(), OPERATE_CB, "setHeader",
                (svc, cb) -> ref.call(svc, "setHeader", path == null ? "" : path, cb));
    }

    /** Read the account's own online status through the profile service. */
    public Result getSelfStatus() {
        return call(qq.getProfileService(), OPERATE_CB, "getSelfStatus",
                (svc, cb) -> ref.call(svc, "getSelfStatus", cb));
    }

    /** Cached profile (nick, remark, signature, level, ...) for the current account. */
    public Object selfCoreInfo() {
        try {
            String uid = selfUid();
            Object profile = qq.getProfileService();
            if (uid.isEmpty() || profile == null) return null;
            ArrayList<String> uids = new ArrayList<>();
            uids.add(uid);
            // CoreAndBase carries the personal signature; plain CoreInfo does not.
            try {
                Object full = ref.call(profile, "getCoreAndBaseInfo", "", uids);
                if (full instanceof java.util.Map) {
                    Object v = ((java.util.Map<?, ?>) full).get(uid);
                    if (v != null) return v;
                }
            } catch (Throwable ignore) {}
            Object map = ref.call(profile, "getCoreInfo", "", uids);
            if (map instanceof java.util.Map) return ((java.util.Map<?, ?>) map).get(uid);
        } catch (Throwable t) {
            L.e("selfCoreInfo", t);
        }
        return null;
    }

    // --------------------------------------------------------- group settings

    /** Set the group message mask: notify / assistant / shield / receive. */
    public Result setGroupMsgMask(long groupCode, String mask) {
        return call(qq.getGroupService(), OPERATE_CB, "setGroupMsgMask", (svc, cb) -> {
            Object value = ref.getStatic(GROUP_MSG_MASK, mask);
            if (value == null) throw new IllegalArgumentException("unknown mask " + mask);
            ref.call(svc, "setGroupMsgMask", groupCode, value, cb);
        });
    }

    /** List the members currently muted in a group, with their remaining time. */
    public Result getGroupShutUpList(long groupCode) {
        return call(qq.getGroupService(), MUTE_LIST_CB, "queryGroupMuteMemberList",
                (svc, cb) -> ref.call(svc, "queryGroupMuteMemberList", groupCode, cb));
    }

    /** Read a group's honor (群荣誉) roster. */
    public Result getGroupHonor(long groupCode) {
        return call(qq.getGroupService(), HONOR_CB, "getGroupHonorList", (svc, cb) -> {
            Object req = ref.neu(GROUP_MEMBER_HONOR_REQ);
            ArrayList<Long> groups = new ArrayList<>();
            groups.add(groupCode);
            ref.put(req, "groupCode", groups);
            ref.call(svc, "getGroupHonorList", req, cb);
        });
    }

    // ------------------------------------------------------------------ buddy

    /** Send a friend request to {@code uin}. */
    public Result addFriend(long uin, String verify, String remark, int sourceId) {
        final String uid = qq.resolveUid(uin);
        return call(qq.getBuddyService(), OPERATE_CB, "reqToAddFriends", (svc, cb) -> {
            Object req = ref.neu(REQ_TO_FRIEND);
            ref.put(req, "buddyUin", uin);
            ref.put(req, "buddyUid", uid == null ? "" : uid);
            ref.put(req, "verifyInfo", verify == null ? "" : verify);
            ref.put(req, "answer", "");
            ref.put(req, "remark", remark == null ? "" : remark);
            ref.put(req, "sourceID", sourceId);
            ref.put(req, "sourceSubID", 0);
            // QQ's native side dereferences securityVerify, so hand it an empty struct
            // rather than null; the server only looks inside when it wants a challenge.
            try {
                ref.put(req, "securityVerify", ref.neu(
                        "com.tencent.qqnt.kernel.nativeinterface.Verify"));
            } catch (Throwable ignore) {}
            ref.call(svc, "reqToAddFriends", req, cb);
        });
    }

    /** Set a buddy's remark (好友备注); empty clears it. */
    public Result setBuddyRemark(long uin, String remark) {
        final String uid = qq.resolveUid(uin);
        if (uid == null || uid.isEmpty()) {
            Result r = new Result();
            r.msg = "cannot resolve friend uid";
            return r;
        }
        return call(qq.getBuddyService(), OPERATE_CB, "setBuddyRemark", (svc, cb) -> {
            Object req = ref.neu(REMARK_PARAMS);
            ref.put(req, "uid", uid);
            ref.put(req, "remark", remark == null ? "" : remark);
            ref.call(svc, "setBuddyRemark", req, cb);
        });
    }

    /** Cached remark for a buddy, empty when none is set. */
    @SuppressWarnings("unchecked")
    public String getBuddyRemark(long uin) {
        try {
            String uid = qq.resolveUid(uin);
            Object buddy = qq.getBuddyService();
            if (uid.isEmpty() || buddy == null) return "";
            ArrayList<String> uids = new ArrayList<>();
            uids.add(uid);
            Object map = ref.call(buddy, "getBuddyRemark", uids);
            if (map instanceof java.util.Map) {
                Object v = ((java.util.Map<Object, Object>) map).get(uid);
                return v == null ? "" : String.valueOf(v);
            }
        } catch (Throwable t) {
            L.e("getBuddyRemark", t);
        }
        return "";
    }

    /** Pin or unpin a buddy's conversation. */
    public Result setBuddyTop(long uin, boolean top) {
        final String uid = qq.resolveUid(uin);
        if (uid == null || uid.isEmpty()) return missingUid();
        return call(qq.getBuddyService(), OPERATE_CB, "setTop",
                (svc, cb) -> ref.call(svc, "setTop", uid, top, cb));
    }

    /** Mute or unmute message notifications for one buddy. */
    public Result setBuddyMsgNotify(long uin, boolean notify) {
        final String uid = qq.resolveUid(uin);
        if (uid == null || uid.isEmpty()) return missingUid();
        return call(qq.getBuddyService(), OPERATE_CB, "setMsgNotify",
                (svc, cb) -> ref.call(svc, "setMsgNotify", uid, notify, cb));
    }

    /** Block or unblock a buddy. */
    public Result setBuddyBlock(long uin, boolean block) {
        final String uid = qq.resolveUid(uin);
        if (uid == null || uid.isEmpty()) return missingUid();
        return call(qq.getBuddyService(), OPERATE_CB, "setBlock",
                (svc, cb) -> ref.call(svc, "setBlock", uid, block, cb));
    }

    /** Synchronous relationship flags for one account. */
    @SuppressWarnings("unchecked")
    public boolean isBuddy(long uin) {
        try {
            String uid = qq.resolveUid(uin);
            Object buddy = qq.getBuddyService();
            if (uid.isEmpty() || buddy == null) return false;
            Object v = ref.call(buddy, "isBuddy", uid);
            return Ref.asBool(v);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Synchronous block flag for one account. */
    public boolean isBlocked(long uin) {
        try {
            String uid = qq.resolveUid(uin);
            Object buddy = qq.getBuddyService();
            if (uid.isEmpty() || buddy == null) return false;
            return Ref.asBool(ref.call(buddy, "isBlocked", uid));
        } catch (Throwable t) {
            return false;
        }
    }

    private static Result missingUid() {
        Result r = new Result();
        r.msg = "cannot resolve uid";
        return r;
    }

    // --------------------------------------------------------------- recent

    /**
     * Recent-contact snapshot. QQ's synchronous and list variants answer
     * {@code "msg service is nullptr"} / no payload on this build, so use the callback that
     * delivers the array directly.
     */
    public Result recentContacts() {
        return call(qq.getRecentContactService(), RECENT_CB, "getRecentContactInfos",
                (svc, cb) -> ref.call(svc, "getRecentContactInfos", cb));
    }
}
