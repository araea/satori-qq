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
    private static final String GROUP_INFO_SOURCE =
            "com.tencent.qqnt.kernel.nativeinterface.GroupInfoSource";
    private static final String GROUP_BULLETIN_LIST_REQ =
            "com.tencent.qqnt.kernel.nativeinterface.GroupBulletinListReq";
    private static final String GROUP_ESSENCE_REQ =
            "com.tencent.qqnt.kernel.nativeinterface.FetchGroupEssenceListReq";
    private static final String GROUP_ESSENCE_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IFetchGroupEssenceListCallback";
    private static final String GROUP_MEDAL_REQ =
            "com.tencent.qqnt.kernel.nativeinterface.GetGroupMedalListReq";
    private static final String GROUP_MEDAL_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetGroupMedalListCallback";
    private static final String GROUP_AVATAR_WALL_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGroupAvatarWallCallback";
    private static final String IDENTITY_LIST_REQ =
            "com.tencent.qqnt.kernel.nativeinterface.GetIdentityListReq";
    private static final String IDENTITY_LIST_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetIdentityListCallback";
    private static final String GROUP_MEMBER_COMMON_REQ =
            "com.tencent.qqnt.kernel.nativeinterface.GroupMemberCommonReq";
    private static final String GROUP_MEMBER_COMMON_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGroupMemberCommonCallback";
    private static final String SPECIAL_CARE_SETTING =
            "com.tencent.qqnt.kernel.nativeinterface.SpecialCareSetting";
    private static final String BUDDY_LIST_REQ_TYPE =
            "com.tencent.qqnt.kernel.nativeinterface.BuddyListReqType";
    private static final String DETAIL_BY_UIN_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IDetailInfoByUinCallback";
    private static final String FAV_EMOJI_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IFetchFavEmojiListCallback";
    private static final String AUTO_REPLY_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetAutoReplyTextListCallback";
    private static final String RECENT_EMOJI_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetRecentUseEmojiListCallback";

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
                // Kernel callbacks are not all named `onResult` (e.g. IFetchFavEmojiListCallback
                // uses `onFetchFavEmojiListCallback`), but they all report `(int code, String msg,
                // <payload>...)`. Match on that shape so no callback is missed and silently
                // times out.
                if (args != null && args.length >= 2 && args[0] instanceof Number) {
                    code.set(Ref.asInt(args[0]));
                    wording[0] = Ref.asStr(args[1]);
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

    // ------------------------------------------------------------ group reads

    /** Full group profile: capacity, level, owner, flags. `source` picks the big-data entry. */
    public Result groupDetail(long groupCode, String source) {
        return call(qq.getGroupService(), OPERATE_CB, "getGroupDetailInfo",
                (svc, cb) -> ref.call(svc, "getGroupDetailInfo", groupCode,
                        groupInfoSource(source), cb));
    }

    /** The same profile through the "all info" entry, which carries a few extra fields. */
    public Result groupAllInfo(long groupCode) {
        return call(qq.getGroupService(), OPERATE_CB, "getGroupAllInfo",
                (svc, cb) -> ref.call(svc, "getGroupAllInfo", groupCode,
                        groupInfoSource(null), cb));
    }

    /**
     * The enum constant is passed straight to native code, so a null here silently returns
     * nothing. Probe the spellings this client knows instead of trusting one.
     */
    private Object groupInfoSource(String name) {
        String[] candidates = name == null || name.trim().isEmpty()
                ? new String[]{"KDATACARD", "KAIO", "KUNSPECIFIED"}
                : new String[]{name.trim(), "KDATACARD", "KUNSPECIFIED"};
        for (String candidate : candidates) {
            try {
                Object value = ref.getStatic(GROUP_INFO_SOURCE, candidate);
                if (value != null) return value;
            } catch (Throwable ignore) {}
        }
        return null;
    }

    /** The group's pinned bulletin (群公告). */
    public Result groupBulletin(long groupCode) {
        return call(qq.getGroupService(), OPERATE_CB, "getGroupBulletin",
                (svc, cb) -> ref.call(svc, "getGroupBulletin", groupCode, cb));
    }

    /** Paginated bulletin list. The two strings are the group-uin and reader-uid selectors. */
    public Result groupBulletinList(long groupCode, int start, int count) {
        return call(qq.getGroupService(), OPERATE_CB, "getGroupBulletinList", (svc, cb) -> {
            Object req = ref.neu(GROUP_BULLETIN_LIST_REQ);
            ref.put(req, "startIndex", Math.max(0, start));
            ref.put(req, "num", count <= 0 ? 20 : count);
            ref.put(req, "needPublisherInfo", 1);
            ref.put(req, "needInstructionsForJoinGroup", 0);
            ref.call(svc, "getGroupBulletinList", groupCode, String.valueOf(groupCode),
                    qq.selfUin(), req, cb);
        });
    }

    /** Group statistics: message volume, active members. */
    public Result groupStatistic(long groupCode) {
        return call(qq.getGroupService(), OPERATE_CB, "getGroupStatisticInfo",
                (svc, cb) -> ref.call(svc, "getGroupStatisticInfo", groupCode, cb));
    }

    /** The group's member-level scale, used to interpret per-member level numbers. */
    public Result groupMemberLevel(long groupCode) {
        return call(qq.getGroupService(), OPERATE_CB, "getGroupMemberLevelInfo",
                (svc, cb) -> ref.call(svc, "getGroupMemberLevelInfo", groupCode, cb));
    }

    /** Wall of member-contributed group avatars. */
    public Result groupAvatarWall(long groupCode) {
        return call(qq.getGroupService(), GROUP_AVATAR_WALL_CB, "getGroupAvatarWall",
                (svc, cb) -> ref.call(svc, "getGroupAvatarWall", groupCode, cb));
    }

    /** Group medals (群勋章). */
    public Result groupMedalList(long groupCode) {
        return call(qq.getGroupService(), GROUP_MEDAL_CB, "getGroupMedalList", (svc, cb) -> {
            Object req = ref.neu(GROUP_MEDAL_REQ);
            ref.put(req, "groupCode", groupCode);
            ref.call(svc, "getGroupMedalList", req, cb);
        });
    }

    /**
     * Essence (精华) messages, paged by index. The V2 entry takes no cookie and is the one this
     * build answers on; the older three-argument form is kept as a fallback, mirroring how
     * `getGroupShutUpMemberList` had to be replaced by `queryGroupMuteMemberList`.
     */
    public Result groupEssence(long groupCode, int start, int limit) {
        Object req = essenceReq(groupCode, start, limit);
        Result v2 = call(qq.getGroupService(), GROUP_ESSENCE_CB, "fetchGroupEssenceListV2",
                (svc, cb) -> ref.call(svc, "fetchGroupEssenceListV2", req, cb));
        if (!v2.timedOut) return v2;
        return call(qq.getGroupService(), GROUP_ESSENCE_CB, "fetchGroupEssenceList",
                (svc, cb) -> ref.call(svc, "fetchGroupEssenceList", req, "", cb));
    }

    private Object essenceReq(long groupCode, int start, int limit) {
        Object req = ref.neu(GROUP_ESSENCE_REQ);
        ref.put(req, "groupCode", groupCode);
        ref.put(req, "pageStart", Math.max(0, start));
        ref.put(req, "pageLimit", limit <= 0 ? 20 : limit);
        return req;
    }

    /** One member's group identity: level, titles, interaction and app tags. */
    public Result memberIdentity(long groupCode, long memberUin) {
        return call(qq.getGroupService(), IDENTITY_LIST_CB, "getIdentityList", (svc, cb) -> {
            Object req = ref.neu(IDENTITY_LIST_REQ);
            ref.put(req, "groupCode", groupCode);
            ref.put(req, "memberUin", memberUin);
            ref.call(svc, "getIdentityList", req, false, cb);
        });
    }

    /** Extra per-member fields the ordinary member cache does not carry. */
    public Result memberCommon(long groupCode, java.util.List<Long> uins, String startUin) {
        return call(qq.getGroupService(), GROUP_MEMBER_COMMON_CB, "getMemberCommonInfo", (svc, cb) -> {
            Object req = ref.neu(GROUP_MEMBER_COMMON_REQ);
            ref.put(req, "groupCode", groupCode);
            ref.put(req, "sourceType", 0);
            ref.put(req, "startUin", startUin == null ? "" : startUin);
            ArrayList<Long> list = new ArrayList<>();
            if (uins != null) list.addAll(uins);
            ref.put(req, "uinList", list);
            ref.call(svc, "getMemberCommonInfo", req, cb);
        });
    }

    /** Read the group's message-notify mask (the write side is {@link #setGroupMsgMask}). */
    public Result groupMsgMask() {
        return call(qq.getGroupService(), OPERATE_CB, "getGroupMsgMask",
                (svc, cb) -> ref.call(svc, "getGroupMsgMask", cb));
    }

    // ------------------------------------------------------------------ buddy

    /**
     * Friend categories (好友分组) with their membership, straight from the local cache.
     * `KNOMAL` is the user's own category table; `KLETTER` is the fixed A-Z view QQ shows in the
     * contact list and carries none of the categories the write side creates.
     */
    @SuppressWarnings("unchecked")
    public java.util.List<Object> buddyCategories() {
        java.util.List<Object> out = new java.util.ArrayList<>();
        try {
            Object buddy = qq.getBuddyService();
            if (buddy == null) return out;
            Object value = ref.call(buddy, "getBuddyListFromCache", "", buddyListReqType("KNOMAL"));
            if (value instanceof java.util.List) out.addAll((java.util.List<Object>) value);
        } catch (Throwable t) {
            L.e("buddyCategories", t);
        }
        return out;
    }

    private Object buddyListReqType(String name) {
        for (String candidate : new String[]{name, "KNOMAL", "KLETTER"}) {
            if (candidate == null) continue;
            try {
                Object value = ref.getStatic(BUDDY_LIST_REQ_TYPE, candidate);
                if (value != null) return value;
            } catch (Throwable ignore) {}
        }
        return null;
    }

    /** Refresh the friend-category table from the server. */
    public Result pullBuddyCategories() {
        return call(qq.getBuddyService(), OPERATE_CB, "pullCategory",
                (svc, cb) -> ref.call(svc, "pullCategory", cb));
    }

    /** Create a friend category (好友分组). */
    public Result addBuddyCategory(String name) {
        return call(qq.getBuddyService(), OPERATE_CB, "addCategory",
                (svc, cb) -> ref.call(svc, "addCategory", name == null ? "" : name, cb));
    }

    public Result deleteBuddyCategory(int categoryId) {
        return call(qq.getBuddyService(), OPERATE_CB, "delCategory",
                (svc, cb) -> ref.call(svc, "delCategory", categoryId, cb));
    }

    public Result renameBuddyCategory(int categoryId, String name) {
        return call(qq.getBuddyService(), OPERATE_CB, "renameCategory",
                (svc, cb) -> ref.call(svc, "renameCategory", categoryId, name == null ? "" : name, cb));
    }

    /** Move one friend into a category. */
    public Result setBuddyCategory(long uin, int categoryId) {
        String uid = qq.resolveUid(uin);
        if (uid == null || uid.isEmpty()) return missingUid();
        return call(qq.getBuddyService(), OPERATE_CB, "setBuddyCategory",
                (svc, cb) -> ref.call(svc, "setBuddyCategory", uid, categoryId, cb));
    }

    /** Special care (特别关心) and its ring/zone sub-switches for one friend. */
    public Result setSpecialCare(long uin, boolean on, boolean ring, boolean zone) {
        String uid = qq.resolveUid(uin);
        if (uid == null || uid.isEmpty()) return missingUid();
        return call(qq.getBuddyService(), OPERATE_CB, "SetSpecialCare", (svc, cb) -> {
            Object setting = ref.neu(SPECIAL_CARE_SETTING);
            ref.put(setting, "isOn", on);
            ref.put(setting, "isRingOn", ring);
            ref.put(setting, "isZoneOn", zone);
            ref.call(svc, "SetSpecialCare", uid, setting, cb);
        });
    }

    /** The account's "who may add me" settings (加好友设置). */
    public Result addMeSetting() {
        return call(qq.getBuddyService(), OPERATE_CB, "getAddMeSetting",
                (svc, cb) -> ref.call(svc, "getAddMeSetting", cb));
    }

    /** Update one add-me setting type with the kernel's string key/value map. */
    public Result setAddMeSetting(int type, java.util.HashMap<String, String> values) {
        java.util.HashMap<String, String> payload =
                values == null ? new java.util.HashMap<>() : values;
        return call(qq.getBuddyService(), OPERATE_CB, "modifyAddMeSetting",
                (svc, cb) -> ref.call(svc, "modifyAddMeSetting", type, payload, cb));
    }

    /** Unanswered stranger (疑问) friend requests. */
    public Result doubtBuddyReq(String cookie, int count) {
        return call(qq.getBuddyService(), OPERATE_CB, "getDoubtBuddyReq",
                (svc, cb) -> ref.call(svc, "getDoubtBuddyReq",
                        cookie == null ? "" : cookie, count <= 0 ? 20 : count, "", cb));
    }

    /** Accept or reject a stranger friend request. */
    public Result approveDoubtBuddyReq(String reqId, long seq, boolean approve) {
        return call(qq.getBuddyService(), OPERATE_CB, "approvalDoubtBuddyReq",
                (svc, cb) -> ref.call(svc, "approvalDoubtBuddyReq",
                        reqId == null ? "" : reqId, Long.valueOf(seq), approve ? "1" : "2", cb));
    }

    /** Unread friend-request count. */
    public Result buddyReqUnread() {
        return call(qq.getBuddyService(), OPERATE_CB, "getBuddyReqUnreadCnt",
                (svc, cb) -> ref.call(svc, "getBuddyReqUnreadCnt", cb));
    }

    /** Nicknames for the given friends, keyed by uid (synchronous cache read). */
    @SuppressWarnings("unchecked")
    public java.util.Map<String, String> buddyNick(java.util.List<String> uids) {
        try {
            Object buddy = qq.getBuddyService();
            if (buddy == null) return java.util.Collections.emptyMap();
            Object value = ref.call(buddy, "getBuddyNick", new ArrayList<>(uids));
            if (value instanceof java.util.Map) return (java.util.Map<String, String>) value;
        } catch (Throwable t) {
            L.e("buddyNick", t);
        }
        return java.util.Collections.emptyMap();
    }

    // ---------------------------------------------------------------- profile

    /** Rich profile for one account (level, vip, medals, signatures). */
    public Result userDetail(long uin) {
        return call(qq.getProfileService(), DETAIL_BY_UIN_CB, "getUserDetailInfoByUin",
                (svc, cb) -> ref.call(svc, "getUserDetailInfoByUin", uin, cb));
    }

    /** VAS rows (会员等级、铭牌、字体) read straight from the cache. */
    public java.util.Map<String, Object> vasInfo(java.util.List<String> uids) {
        return profileMap("getVasInfo", uids);
    }

    /** Online-status rows: device, battery, network, custom status. */
    public java.util.Map<String, Object> statusInfo(java.util.List<String> uids) {
        return profileMap("getStatusInfo", uids);
    }

    /** Intimacy (亲密关系) rows. */
    public java.util.Map<String, Object> intimate(java.util.List<String> uids) {
        return profileMap("getIntimate", uids);
    }

    /** Relation flags: block, top, disturb, special care, qq-show, ... */
    public java.util.Map<String, Object> relationFlag(java.util.List<String> uids) {
        return profileMap("getRelationFlag", uids);
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> profileMap(String method, java.util.List<String> uids) {
        try {
            Object profile = qq.getProfileService();
            if (profile == null) return java.util.Collections.emptyMap();
            Object value = ref.call(profile, method, "", new ArrayList<>(uids));
            if (value instanceof java.util.Map) return (java.util.Map<String, Object>) value;
        } catch (Throwable t) {
            L.e(method, t);
        }
        return java.util.Collections.emptyMap();
    }

    /** Set the account's own birthday (1-12 month, 1-31 day; 0 clears). */
    public Result setBirthday(int year, int month, int day) {
        return call(qq.getProfileService(), OPERATE_CB, "setBirthday",
                (svc, cb) -> ref.call(svc, "setBirthday", year, month, day, cb));
    }

    // ----------------------------------------------------------------- message

    /**
     * The account's emoji tray. QQ's phone build only answers `getRecentUseEmojiList`; both
     * `fetchFavEmojiList` and `queryFavEmojiByDesc` accept the call and never call back, so
     * calling them first would only add two 15-second stalls to every request.
     */
    public Result emojiTray() {
        return call(qq.getMsgService(), RECENT_EMOJI_CB, "getRecentUseEmojiList",
                (svc, cb) -> ref.call(svc, "getRecentUseEmojiList", cb));
    }

    /** The account's auto-reply texts (自动回复). */
    public Result autoReplyList() {
        return call(qq.getMsgService(), AUTO_REPLY_CB, "getAutoReplyTextList",
                (svc, cb) -> ref.call(svc, "getAutoReplyTextList", cb));
    }

    /** Mark one conversation as read. The caller supplies the kernel Contact. */
    public Result markRead(Object contact) {
        return call(qq.getMsgService(), OPERATE_CB, "setMsgRead",
                (svc, cb) -> ref.call(svc, "setMsgRead", contact, cb));
    }

    /** Unread counters for the given conversations. */
    public Result unreadCount(java.util.List<Object> contacts) {
        ArrayList<Object> payload = new ArrayList<>();
        if (contacts != null) payload.addAll(contacts);
        return call(qq.getMsgService(), OPERATE_CB, "getContactUnreadCnt",
                (svc, cb) -> ref.call(svc, "getContactUnreadCnt", payload, cb));
    }

    /**
     * Speech-to-text for one voice message. The kernel wants the message's own PttElement, so
     * the caller loads the record and hands the element over.
     */
    public Result voiceToText(long msgId, Object contact, Object element) {
        return call(qq.getMsgService(), OPERATE_CB, "translatePtt2Text",
                (svc, cb) -> ref.call(svc, "translatePtt2Text", msgId, contact, element, cb));
    }

    // -------------------------------------------------------------- rich media

}
