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
    private static final String BATCH_GROUP_DETAIL_REQ =
            "com.tencent.qqnt.kernel.nativeinterface.BatchQueryCachedGroupDetailInfoReq";
    private static final String BATCH_GROUP_DETAIL_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IBatchQueryCachedGroupDetailInfoCallback";
    private static final String GROUP_DETAIL_REQ =
            "com.tencent.qqnt.kernel.nativeinterface.GroupDetailInfoReq";
    private static final String GROUP_DETAIL_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGroupDetailInfoCallback";
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

    // --- 0.8.9.39: kernel types used by the extended group/message/media/profile actions ---
    private static final String GROUP_LINK_REQ =
            "com.tencent.qqnt.kernel.nativeinterface.GroupLinkReq";
    private static final String JOIN_LINK_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetJoinGroupLinkCallback";
    private static final String MEMBER_CARD_REQ =
            "com.tencent.qqnt.kernel.nativeinterface.GroupMemberCardInfoReq";
    private static final String MEMBER_CARD_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetGroupMemberCardInfoCallback";
    private static final String RELATED_GROUP_REQ =
            "com.tencent.qqnt.kernel.nativeinterface.GetRelatedGroupReq";
    private static final String RELATED_GROUP_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetRelatedGroupCallback";
    private static final String SUB_GROUP_REQ =
            "com.tencent.qqnt.kernel.nativeinterface.GetSubGroupInfoReq";
    private static final String SUB_GROUP_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetSubGroupInfoCallback";
    private static final String APP_CENTER_REQ =
            "com.tencent.qqnt.kernel.nativeinterface.GetAppCenterReq";
    private static final String APP_CENTER_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetAppCenterCallback";
    private static final String ILLEGAL_MEMBER_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGroupMemberIllegalInfoCallback";
    private static final String MEMBER_CACHE_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGroupMemberCacheCallback";
    private static final String MSG_LIMIT_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGroupMsgLimitFreqCallback";
    private static final String MEMBER_MAX_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGroupMemberMaxNumCallback";
    private static final String TRANSFER_GROUP_CB =
            "com.tencent.qqnt.kernel.nativeinterface.ITransferGroupCallback";
    private static final String SIGN_IN_STATUS_REQ =
            "com.tencent.qqnt.kernel.nativeinterface.GetGroupSignInStatusReq";
    private static final String SIGN_IN_STATUS_INNER =
            "com.tencent.qqnt.kernel.nativeinterface.StSignInStatusReq";
    private static final String SIGN_IN_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetGroupSignInStatusCallback";
    private static final String MSG_OPERATE_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IMsgOperateCallback";
    private static final String MSG_SEQ_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetMsgSeqCallback";
    private static final String HIDDEN_SESSION_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IOperateHiddenSessionCallback";
    private static final String HIDDEN_SESSION_INFO =
            "com.tencent.qqnt.kernel.nativeinterface.RecentHiddenSesionInfo";
    private static final String DRAFT_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetDraftOperateCallback";
    private static final String ADD_FAV_EMOJI_REQ =
            "com.tencent.qqnt.kernel.nativeinterface.AddFavEmojiReq";
    private static final String ADD_FAV_EMOJI_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IAddFavEmojiCallback";
    private static final String EMOJI_DESC_INFO =
            "com.tencent.qqnt.kernel.nativeinterface.EmojiDescInfo";
    private static final String MODIFY_FAV_EMOJI_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IModifyFavEmojiDescCallback";
    private static final String TEMP_CHAT_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetTempChatInfoCallback";
    private static final String RECENT_FACE_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetRecentUsedFaceListCallback";
    private static final String EMOJI_LIKES_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetMsgEmojiLikesListCallback";
    private static final String MSG_ABSTRACT_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetMsgAbstractsCallback";
    private static final String BATCH_FILE_COUNT_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IBatchGroupFileCountCallback";
    private static final String RECENT_SNAPSHOT_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IKernelRecentSnapShotCallback";
    private static final String UNREAD_DETAILS_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IKernelRecentGetContactUnreadDetailsCallback";
    private static final String CONTACT_TOP_DATA =
            "com.tencent.qqnt.kernel.nativeinterface.ContactTopData";
    private static final String GROUP_ROBOT_CREATE_REQ =
            "com.tencent.qqnt.kernel.nativeinterface.GroupRobotGetCreateGroupRobotsReq";
    private static final String GROUP_ROBOT_CREATE_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetGroupRobotListForCreateCallback";
    private static final String GROUP_ROBOT_OWNED_REQ =
            "com.tencent.qqnt.kernel.nativeinterface.GroupRobotGetOwnedBotsReq";
    private static final String GROUP_ROBOT_OWNED_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetGroupMemberOwnedRobotsCallback";

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
            Compat.observe(label, "failed");
            return r;
        }
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicInteger code = new AtomicInteger(-1);
            final String[] wording = new String[]{""};
            final Object[] payload = new Object[]{null};
            Object cb = Proxy.newProxyInstance(ref.cl, new Class[]{ref.cls(cbClass)}, (p, m, args) -> {
                // Kernel callbacks come in two shapes. Most report `(int code, String msg,
                // <payload>...)`; a few carry the payload alone, e.g.
                // `IBatchQueryCachedGroupDetailInfoCallback.onResult(ArrayList<GroupDetailInfo>)`.
                // Both have to complete the wait, or the second shape silently times out.
                //
                // Only methods named `on*` count as callbacks: `Object.equals` hands the proxy a
                // single non-numeric argument too, and treating that as a payload would let a
                // bookkeeping call complete the wait with the wrong value.
                String cbName = m.getName();
                boolean isCallback = cbName != null && cbName.startsWith("on");
                if (isCallback && args != null && args.length >= 2 && args[0] instanceof Number) {
                    code.set(Ref.asInt(args[0]));
                    wording[0] = Ref.asStr(args[1]);
                    if (args.length >= 3 && args[2] != null) payload[0] = args[2];
                    latch.countDown();
                } else if (isCallback && args != null && args.length == 1 && args[0] != null
                        && !(args[0] instanceof Number)) {
                    code.set(0);
                    wording[0] = "payload";
                    payload[0] = args[0];
                    latch.countDown();
                } else {
                    L.i("ExtraSvc " + label + " cb." + cbName + " args="
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
                Compat.observe(label, "timeout");
                L.i("ExtraSvc " + label + " -> timeout");
                return r;
            }
            r.code = code.get();
            r.msg = wording[0] == null ? "" : wording[0];
            r.payload = payload[0];
            Compat.observe(label, r.ok() ? "ok" : "failed");
            L.i("ExtraSvc " + label + " -> " + r.describe()
                    + (r.payload == null ? "" : " payload=" + r.payload.getClass().getName()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            r.msg = label + " interrupted";
        } catch (Throwable t) {
            L.e(label, t);
            r.msg = String.valueOf(t);
            Compat.observe(label, "failed");
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

    /**
     * Whether a callback payload actually carries something. An empty list or map is what a kernel
     * cache answers when it has nothing — treating that as data would stop the caller from trying
     * the next entry.
     */
    private static boolean hasData(Object payload) {
        if (payload == null) return false;
        if (payload instanceof java.util.Collection) return !((java.util.Collection<?>) payload).isEmpty();
        if (payload instanceof java.util.Map) return !((java.util.Map<?, ?>) payload).isEmpty();
        if (payload instanceof String) return !((String) payload).trim().isEmpty();
        return true;
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

    // --------------------------------------------------------- group settings

    // ------------------------------------------------------------------ buddy


    // --------------------------------------------------------------- recent

    // ------------------------------------------------------------ group reads


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


    // ------------------------------------------------------------------ buddy


    // ---------------------------------------------------------------- profile


    // ----------------------------------------------------------------- message

    // -------------------------------------------------------------- rich media

    // ------------------------------------------------------- group administration

    // ------------------------------------------------------------------ message

    /** The conversation's draft. */
    public Result draft(Object contact) {
        return call(qq.getMsgService(), DRAFT_CB, "getDraft",
                (svc, cb) -> ref.call(svc, "getDraft", contact, cb));
    }

    // --------------------------------------------------------- recent contacts

    // ------------------------------------------------------------------- robot

    // ========================================================== 扩展动作注册表
    //
    // 每个动作在这里登记一次：规范名、别名、是否写入、参数说明，以及一次调用。
    // 新增动作前先核三件事（与 docs/ARCHITECTURE.md 的「QQ 升级检查」一致）：
    //   1. 类名在 QQ 的 dex 类索引里；
    //   2. 请求结构体的字段名（对单个类反编译核对，字段改名是静默失败）；
    //   3. 入口会不会回调（现场探测，只信 observed）。
    // 结构体字段的取值都记在下方注释里，取自本机 QQ 9.3.65 的反编译结果。

    /** 返回 {@link Result}（等内核回调）或 {@link org.json.JSONObject}（同步读）。 */
    public interface Fn { Object run(ExtraSvc s, org.json.JSONObject p) throws Exception; }

    /** 一个内核扩展动作。 */
    public static final class Spec {
        public final String name;
        public final String[] alias;
        public final boolean write;
        public final String params;
        public final Fn fn;
        Spec(String name, String[] alias, boolean write, String params, Fn fn) {
            this.name = name; this.alias = alias; this.write = write;
            this.params = params; this.fn = fn;
        }
    }

    private static final java.util.List<Spec> ACTIONS = new java.util.ArrayList<>();

    private static void reg(String name, String[] alias, boolean write, String params, Fn fn) {
        ACTIONS.add(new Spec(name, alias, write, params, fn));
    }

    public static java.util.List<Spec> actions() {
        return java.util.Collections.unmodifiableList(ACTIONS);
    }

    public static Spec find(String name) {
        if (name == null) return null;
        for (Spec s : ACTIONS) {
            if (s.name.equals(name)) return s;
            for (String a : s.alias) if (a.equals(name)) return s;
        }
        return null;
    }

    // ------------------------------------------------------------------ 取参

    /** 从 channel_id / guild_id+user_id 里取群号。 */
    static long groupOf(org.json.JSONObject p) {
        long g = p.optLong("guild_id", p.optLong("group_id", 0));
        if (g != 0) return g;
        String ch = p.optString("channel_id", "");
        if (!ch.isEmpty() && !com.satori.qq.satori.Codec.isPrivateChannel(ch))
            return com.satori.qq.satori.Codec.channelPeer(ch);
        return 0;
    }

    /** 会话 Contact（QQ 的 kernelpublic Contact）：群聊用群号，私聊用 UID。 */
    Object contactOf(org.json.JSONObject p) throws Exception {
        long g = groupOf(p);
        if (g != 0) return ref.neu(QQClient.CONTACT, QQClient.CT_GROUP, String.valueOf(g), "");
        String ch = p.optString("channel_id", "");
        long uin = 0;
        if (!ch.isEmpty() && com.satori.qq.satori.Codec.isPrivateChannel(ch))
            uin = com.satori.qq.satori.Codec.channelPeer(ch);
        else uin = parseUin(p.optString("user_id", ""));
        if (uin == 0) throw new IllegalArgumentException("missing channel_id");
        String uid = qq.resolveUid(uin);
        if (uid == null || uid.isEmpty()) uid = String.valueOf(uin);
        return ref.neu(QQClient.CONTACT, QQClient.CT_C2C, uid, "");
    }

    private Object neu(String cls, Object... args) { return ref.neu(cls, args); }
    private void put(Object o, String f, Object v) { if (v != null) ref.put(o, f, v); }
    private Object enumOf(String cls, String name) {
        try { return ref.getStatic(cls, name); } catch (Throwable t) { return null; }
    }

    private static java.util.ArrayList<Long> longs(org.json.JSONArray a) {
        java.util.ArrayList<Long> out = new java.util.ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optLong(i));
        return out;
    }
    private static java.util.ArrayList<String> strings(org.json.JSONArray a) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optString(i, ""));
        return out;
    }
    private static java.util.ArrayList<Integer> ints(org.json.JSONArray a) {
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optInt(i));
        return out;
    }

    /** 同步读到的东西直接包成 JSON；内核回调的读走 {@link Result}。 */
    private static org.json.JSONObject ok(org.json.JSONObject body) throws Exception {
        return body == null ? new org.json.JSONObject() : body;
    }

    private static final String[] NONE = new String[0];

    // ------------------------------------------------------ 新用到的回调接口

    private static final String INLINE_KEYBOARD_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IClickInlineKeyboardButtonCallback";
    private static final String GROUP_ESSENCE_LATEST_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetGroupLatestEssenceListCallback";
    private static final String ESSENCE_CACHED_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IQueryCachedEssenceCallback";
    private static final String JOIN_GROUP_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IJoinGroupCallback";
    private static final String JOIN_INFO_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGroupInfoForJoinCallback";
    private static final String BULLETIN_PIC_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IUploadGroupBulletinPicCallback";
    private static final String GROUP_PROFILE_CARD_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetGroupProfileCardInfoCallback";
    private static final String GROUP_ITEM_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGroupItemCallback";
    private static final String GROUP_FILE_RESULT_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGroupFileCommonResultCallback";
    private static final String DELETE_GROUP_FILE_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IDeleteGroupFileCallback";
    private static final String RENAME_GROUP_FILE_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IRenameGroupFileCallback";
    private static final String MOVE_GROUP_FILE_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IMoveGroupFileCallback";
    private static final String TRANS_GROUP_FILE_CB =
            "com.tencent.qqnt.kernel.nativeinterface.ITransGroupFileCallback";
    private static final String OCR_SEARCH_CB =
            "com.tencent.qqnt.kernel.nativeinterface.ISearchOcrDataCallback";
    private static final String OCR_GET_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetOcrDataCallback";

    static {
        final String NS = "com.tencent.qqnt.kernel.nativeinterface.";

        // ------------------------------------------------------------ 消息

        // sendShowInputStatusReq(int chatType, int eventType, String peerUid, cb)
        reg("typing", new String[]{"input_status", "set_input_status"}, true,
                "channel_id|guild_id|user_id, event_type?",
                (s, p) -> {
                    long g = groupOf(p);
                    String peer;
                    int chatType;
                    if (g != 0) { chatType = QQClient.CT_GROUP; peer = String.valueOf(g); }
                    else {
                        chatType = QQClient.CT_C2C;
                        long uin = parseUin(p.optString("user_id", ""));
                        String ch = p.optString("channel_id", "");
                        if (uin == 0 && !ch.isEmpty())
                            uin = com.satori.qq.satori.Codec.channelPeer(ch);
                        if (uin == 0) throw new IllegalArgumentException("missing channel_id");
                        String uid = s.qq.resolveUid(uin);
                        peer = uid == null || uid.isEmpty() ? String.valueOf(uin) : uid;
                    }
                    final int eventType = p.optInt("event_type", 1);
                    return s.call(s.svc("msg"), OPERATE_CB, "sendShowInputStatusReq",
                            (svc2, cb) -> s.ref.call(svc2, "sendShowInputStatusReq",
                                    chatType, eventType, peer, cb));
                });

        reg("mark_read", NONE, true, "channel_id",
                (s, p) -> s.call(s.svc("msg"), OPERATE_CB, "setMsgRead",
                        (svc2, cb) -> s.ref.call(svc2, "setMsgRead", s.contactOf(p), cb)));

        reg("mark_read_seq", new String[]{"mark_read_message"}, true, "channel_id, message_seq",
                (s, p) -> s.call(s.svc("msg"), OPERATE_CB, "setSpecificMsgReadAndReport",
                        (svc2, cb) -> s.ref.call(svc2, "setSpecificMsgReadAndReport",
                                s.contactOf(p), p.optLong("message_seq", 0), cb)));

        reg("mark_all_read", NONE, true, "",
                (s, p) -> s.call(s.svc("msg"), OPERATE_CB, "setAllC2CAndGroupMsgRead",
                        (svc2, cb) -> s.ref.call(svc2, "setAllC2CAndGroupMsgRead", cb)));

        // getMsgAbstract(Contact, long msgSeq, IGetMsgAbstractsCallback)
        reg("message_abstract", NONE, false, "channel_id, message_seq",
                (s, p) -> s.call(s.svc("msg"), MSG_ABSTRACT_CB, "getMsgAbstract",
                        (svc2, cb) -> s.ref.call(svc2, "getMsgAbstract",
                                s.contactOf(p), p.optLong("message_seq", 0), cb)));

        // clickInlineKeyboardButton(InlineKeyboardClickInfo, IClickInlineKeyboardButtonCallback)
        // InlineKeyboardClickInfo: botAppid(long) buttonId(String) callbackData(String)
        //   chatType(int) dmFlag(int) guildId(String) msgSeq(long) peerId(String)
        reg("click_inline_keyboard", NONE, true,
                "channel_id, button_id, callback_data?, bot_appid?, msg_seq?",
                (s, p) -> {
                    Object info = s.neu(NS + "InlineKeyboardClickInfo");
                    long g = groupOf(p);
                    s.put(info, "botAppid", p.optLong("bot_appid", 0));
                    s.put(info, "buttonId", p.optString("button_id", ""));
                    s.put(info, "callbackData", p.optString("callback_data", ""));
                    s.put(info, "chatType", g != 0 ? QQClient.CT_GROUP : QQClient.CT_C2C);
                    s.put(info, "guildId", g != 0 ? String.valueOf(g) : "");
                    s.put(info, "msgSeq", p.optLong("msg_seq", 0));
                    s.put(info, "peerId", g != 0 ? String.valueOf(g) : p.optString("user_id", ""));
                    return s.call(s.svc("msg"), INLINE_KEYBOARD_CB, "clickInlineKeyboardButton",
                            (svc2, cb) -> s.ref.call(svc2, "clickInlineKeyboardButton", info, cb));
                });

        // reeditRecallMsg(Contact, long msgSeq, IOperateCallback)
        reg("reedit_recall", NONE, true, "channel_id, message_seq",
                (s, p) -> s.call(s.svc("msg"), OPERATE_CB, "reeditRecallMsg",
                        (svc2, cb) -> s.ref.call(svc2, "reeditRecallMsg",
                                s.contactOf(p), p.optLong("message_seq", 0), cb)));

        // ---- 图片 OCR（IKernelSearchService）。三个入口的参数语义未从反编译确定，
        //      这里按原样透传，标为 read：它们只落本地 OCR 库，不产生出站动作。
        reg("image_ocr", NONE, false, "path, a?, b?（参数语义未核，原样透传）",
                (s, p) -> {
                    final int a = p.optInt("a", 0);
                    final int b = p.optInt("b", 0);
                    final String path = p.optString("path", p.optString("image", ""));
                    return s.call(s.svc("search"), OPERATE_CB, "doOcrOnPicMsg",
                            (svc2, cb) -> s.ref.call(svc2, "doOcrOnPicMsg", path, a, b, cb));
                });

        reg("ocr_data", NONE, false, "keyword, a?, b?（参数语义未核）",
                (s, p) -> {
                    final String kw = p.optString("keyword", "");
                    final String b = p.optString("b", "");
                    final int c = p.optInt("c", 0);
                    return s.call(s.svc("search"), OCR_SEARCH_CB, "searchOcrData",
                            (svc2, cb) -> s.ref.call(svc2, "searchOcrData", kw, b, c, cb));
                });

        reg("ocr_by_aio", NONE, false, "peer, a?（参数语义未核）",
                (s, p) -> {
                    final String peer = p.optString("peer", p.optString("channel_id", ""));
                    final int a = p.optInt("a", 0);
                    return s.call(s.svc("search"), OCR_GET_CB, "getOcrDataByAIO",
                            (svc2, cb) -> s.ref.call(svc2, "getOcrDataByAIO", peer, a, cb));
                });

        // -------------------------------------------------------------- 群

        reg("group_shut_up_list", new String[]{"group_mute_list"}, false, "guild_id",
                (s, p) -> s.call(s.svc("group"), OPERATE_CB, "getGroupShutUpMemberList",
                        (svc2, cb) -> s.ref.call(svc2, "getGroupShutUpMemberList", groupOf(p), cb)));

        // getJoinGroupLink(GroupLinkReq, IGetJoinGroupLinkCallback)
        // GroupLinkReq: groupCode(long) srcId(int) needShortUrl(boolean) additionalParam(String)
        reg("group_join_link", new String[]{"group_share_link"}, false,
                "guild_id, src_id?, short_url?",
                (s, p) -> {
                    Object req = s.neu(NS + "GroupLinkReq");
                    s.put(req, "groupCode", groupOf(p));
                    s.put(req, "srcId", p.optInt("src_id", 73));
                    s.put(req, "needShortUrl", p.optBoolean("short_url", true));
                    s.put(req, "additionalParam", p.optString("additional_param", ""));
                    return s.call(s.svc("group"), JOIN_LINK_CB, "getJoinGroupLink",
                            (svc2, cb) -> s.ref.call(svc2, "getJoinGroupLink", req, cb));
                });

        // fetchGroupEssenceList(FetchGroupEssenceListReq, String, IFetchGroupEssenceListCallback)
        // FetchGroupEssenceListReq: groupCode(long) pageStart(int) pageLimit(int)
        reg("group_essence_list", NONE, false, "guild_id, page_start?, page_limit?",
                (s, p) -> {
                    Object req = s.neu(NS + "FetchGroupEssenceListReq");
                    s.put(req, "groupCode", groupOf(p));
                    s.put(req, "pageStart", p.optInt("page_start", 0));
                    s.put(req, "pageLimit", p.optInt("page_limit", 20));
                    return s.call(s.svc("group"), GROUP_ESSENCE_CB, "fetchGroupEssenceList",
                            (svc2, cb) -> s.ref.call(svc2, "fetchGroupEssenceList", req, "", cb));
                });

        // getGroupLatestEssenceList(GetGroupLatestEssenceListReq, IGetGroupLatestEssenceListCallback)
        // GetGroupLatestEssenceListReq: groupCode(long) sign(String) pageCookie(String)
        //   msgType(int) excludeMsgType(int) signTs(long) source(int)
        reg("group_essence_latest", NONE, false, "guild_id, count?",
                (s, p) -> {
                    Object req = s.neu(NS + "GetGroupLatestEssenceListReq");
                    s.put(req, "groupCode", groupOf(p));
                    s.put(req, "msgType", p.optInt("msg_type", 0));
                    s.put(req, "excludeMsgType", p.optInt("exclude_msg_type", 0));
                    return s.call(s.svc("group"), GROUP_ESSENCE_LATEST_CB, "getGroupLatestEssenceList",
                            (svc2, cb) -> s.ref.call(svc2, "getGroupLatestEssenceList", req, cb));
                });

        // queryCachedEssenceMsg(EssenceKey, IQueryCachedEssenceCallback)
        // EssenceKey: groupCode(long) msgRandom(int) msgSeq(int)
        reg("group_essence_cached", NONE, false, "guild_id, msg_seq, msg_random",
                (s, p) -> {
                    Object key = s.neu(NS + "EssenceKey");
                    s.put(key, "groupCode", groupOf(p));
                    s.put(key, "msgSeq", p.optInt("msg_seq", 0));
                    s.put(key, "msgRandom", p.optInt("msg_random", 0));
                    return s.call(s.svc("group"), ESSENCE_CACHED_CB, "queryCachedEssenceMsg",
                            (svc2, cb) -> s.ref.call(svc2, "queryCachedEssenceMsg", key, cb));
                });

        // modifyGroupRemark(long groupCode, String remark, IOperateCallback)
        reg("group_remark", new String[]{"set_group_remark"}, true, "guild_id, remark",
                (s, p) -> s.call(s.svc("group"), OPERATE_CB, "modifyGroupRemark",
                        (svc2, cb) -> s.ref.call(svc2, "modifyGroupRemark",
                                groupOf(p), p.optString("remark", ""), cb)));

        // quitGroupV2(QuitGroupReq, IOperateCallback)
        // QuitGroupReq: groupCode(long) needDeleteLocalMsg(boolean)
        reg("group_quit", new String[]{"quit_group"}, true, "guild_id, delete_local_msg?",
                (s, p) -> {
                    Object req = s.neu(NS + "QuitGroupReq");
                    s.put(req, "groupCode", groupOf(p));
                    s.put(req, "needDeleteLocalMsg", p.optBoolean("delete_local_msg", false));
                    return s.call(s.svc("group"), OPERATE_CB, "quitGroupV2",
                            (svc2, cb) -> s.ref.call(svc2, "quitGroupV2", req, cb));
                });

        // reqToJoinGroup(ReqToGroup, IOperateCallback)
        // ReqToGroup: groupCode(long) sourceId(int) sourceSubId(int) richMsg(byte[])
        //   applyMsg(String) token(String) auth(String) noVerifyAuth(String) transInfo(byte[])
        reg("group_join", new String[]{"req_to_join_group"}, true,
                "guild_id, apply_msg?, source_id?, token?, auth?",
                (s, p) -> {
                    Object req = s.neu(NS + "ReqToGroup");
                    s.put(req, "groupCode", groupOf(p));
                    s.put(req, "sourceId", p.optInt("source_id", 0));
                    s.put(req, "sourceSubId", p.optInt("source_sub_id", 0));
                    if (p.has("apply_msg")) s.put(req, "applyMsg", p.optString("apply_msg", ""));
                    if (p.has("token")) s.put(req, "token", p.optString("token", ""));
                    if (p.has("auth")) s.put(req, "auth", p.optString("auth", ""));
                    return s.call(s.svc("group"), JOIN_GROUP_CB, "reqToJoinGroup",
                            (svc2, cb) -> s.ref.call(svc2, "reqToJoinGroup", req, cb));
                });

        // getGroupInfoForJoinGroup(long groupCode, boolean, int src, IGroupInfoForJoinCallback)
        reg("group_join_info", NONE, false, "guild_id, src?",
                (s, p) -> {
                    final int src = p.optInt("src", 0);
                    return s.call(s.svc("group"), JOIN_INFO_CB, "getGroupInfoForJoinGroup",
                            (svc2, cb) -> s.ref.call(svc2, "getGroupInfoForJoinGroup",
                                    groupOf(p), p.optBoolean("need_auth", false), src, cb));
                });

        // getJoinGroupNoVerifyFlag(long groupCode, String uid, IOperateCallback)
        reg("group_join_noverify", NONE, false, "guild_id, user_id",
                (s, p) -> {
                    final String uid = String.valueOf(parseUin(p.optString("user_id", "")));
                    return s.call(s.svc("group"), OPERATE_CB, "getJoinGroupNoVerifyFlag",
                            (svc2, cb) -> s.ref.call(svc2, "getJoinGroupNoVerifyFlag", groupOf(p), uid, cb));
                });

        // publishGroupBulletin(long groupCode, String psKey, GroupBulletinPublishReq, IOperateCallback)
        // GroupBulletinPublishReq: text(String) pinned(int) oldFeedsId(String) picInfo(GroupBulletinPicInfo)
        // psKey 由调用方给：本模块没有 WebAPI 通道，取不到 pskey 时这条多半失败。
        reg("group_bulletin_publish", new String[]{"publish_group_bulletin"}, true,
                "guild_id, text, pskey, pinned?",
                (s, p) -> {
                    Object req = s.neu(NS + "GroupBulletinPublishReq");
                    s.put(req, "text", p.optString("text", ""));
                    s.put(req, "pinned", p.optInt("pinned", 0));
                    if (p.has("old_feeds_id")) s.put(req, "oldFeedsId", p.optString("old_feeds_id", ""));
                    return s.call(s.svc("group"), OPERATE_CB, "publishGroupBulletin",
                            (svc2, cb) -> s.ref.call(svc2, "publishGroupBulletin",
                                    groupOf(p), p.optString("pskey", ""), req, cb));
                });

        // deleteGroupBulletin(long groupCode, String psKey, String feedsId, IOperateCallback)
        reg("group_bulletin_delete", new String[]{"delete_group_bulletin"}, true,
                "guild_id, feeds_id, pskey",
                (s, p) -> s.call(s.svc("group"), OPERATE_CB, "deleteGroupBulletin",
                        (svc2, cb) -> s.ref.call(svc2, "deleteGroupBulletin", groupOf(p),
                                p.optString("pskey", ""), p.optString("feeds_id", ""), cb)));

        // uploadGroupBulletinPic(long groupCode, String psKey, String path, IUploadGroupBulletinPicCallback)
        reg("group_bulletin_upload_pic", NONE, true, "guild_id, path, pskey",
                (s, p) -> s.call(s.svc("group"), BULLETIN_PIC_CB, "uploadGroupBulletinPic",
                        (svc2, cb) -> s.ref.call(svc2, "uploadGroupBulletinPic", groupOf(p),
                                p.optString("pskey", ""), p.optString("path", ""), cb)));

        // getGroupBulletin(long groupCode, IOperateCallback) —— 回调只有 (code, msg)，拿不到正文。
        reg("group_bulletin_get", new String[]{"get_group_bulletin"}, false, "guild_id",
                (s, p) -> s.call(s.svc("group"), OPERATE_CB, "getGroupBulletin",
                        (svc2, cb) -> s.ref.call(svc2, "getGroupBulletin", groupOf(p), cb)));

        reg("group_ext_list", NONE, false, "force?",
                (s, p) -> s.call(s.svc("group"), OPERATE_CB, "getGroupExtList",
                        (svc2, cb) -> s.ref.call(svc2, "getGroupExtList",
                                p.optBoolean("force", true), cb)));

        reg("group_member_level", NONE, false, "guild_id",
                (s, p) -> s.call(s.svc("group"), OPERATE_CB, "getGroupMemberLevelInfo",
                        (svc2, cb) -> s.ref.call(svc2, "getGroupMemberLevelInfo", groupOf(p), cb)));

        // getIdentityList(GetIdentityListReq, boolean, IGetIdentityListCallback)
        // GetIdentityListReq: groupCode(long) memberUin(long)
        reg("group_identity_list", NONE, false, "guild_id, user_id?",
                (s, p) -> {
                    Object req = s.neu(NS + "GetIdentityListReq");
                    s.put(req, "groupCode", groupOf(p));
                    s.put(req, "memberUin", parseUin(p.optString("user_id", "")));
                    return s.call(s.svc("group"), IDENTITY_LIST_CB, "getIdentityList",
                            (svc2, cb) -> s.ref.call(svc2, "getIdentityList", req, true, cb));
                });

        // getGroupMemberCardInfo(GroupMemberCardInfoReq, IGetGroupMemberCardInfoCallback)
        // GroupMemberCardInfoReq: groupCode(long) memberUin(long)
        reg("group_member_card", NONE, false, "guild_id, user_id",
                (s, p) -> {
                    Object req = s.neu(NS + "GroupMemberCardInfoReq");
                    s.put(req, "groupCode", groupOf(p));
                    s.put(req, "memberUin", parseUin(p.optString("user_id", "")));
                    return s.call(s.svc("group"), MEMBER_CARD_CB, "getGroupMemberCardInfo",
                            (svc2, cb) -> s.ref.call(svc2, "getGroupMemberCardInfo", req, cb));
                });

        // getGroupProfileCardInfo(GetGroupProfileCardInfoReq, IGetGroupProfileCardInfoCallback)
        // GetGroupProfileCardInfoReq: groupCode(long) fetchMode(enum GroupProfileCardFetchMode)
        //   needAuthSign(boolean) needFetchOld88d(boolean) src(int) subSrc(int) thirdFileFeatureFlag(int)
        reg("group_profile_card", NONE, false, "guild_id, src?, sub_src?",
                (s, p) -> {
                    Object req = s.neu(NS + "GetGroupProfileCardInfoReq");
                    s.put(req, "groupCode", groupOf(p));
                    s.put(req, "src", p.optInt("src", 0));
                    s.put(req, "subSrc", p.optInt("sub_src", 0));
                    Object mode = s.enumOf(NS + "GroupProfileCardFetchMode",
                            p.optString("fetch_mode", "KFROMCACHE"));
                    if (mode != null) s.put(req, "fetchMode", mode);
                    return s.call(s.svc("group"), GROUP_PROFILE_CARD_CB, "getGroupProfileCardInfo",
                            (svc2, cb) -> s.ref.call(svc2, "getGroupProfileCardInfo", req, cb));
                });

        // batchGetGroupFileCount(ArrayList<Long>, IBatchGroupFileCountCallback)
        reg("group_file_count", NONE, false, "guild_ids[]",
                (s, p) -> {
                    final java.util.ArrayList<Long> gids = p.has("guild_ids")
                            ? longs(p.optJSONArray("guild_ids"))
                            : new java.util.ArrayList<>(java.util.Collections.singletonList(groupOf(p)));
                    return s.call(s.svc("richmedia"), BATCH_FILE_COUNT_CB, "batchGetGroupFileCount",
                            (svc2, cb) -> s.ref.call(svc2, "batchGetGroupFileCount", gids, cb));
                });

        // createGroupFolder(long groupCode, String folderName, IGroupItemCallback)
        reg("group_file_folder_create", NONE, true, "guild_id, name",
                (s, p) -> s.call(s.svc("richmedia"), GROUP_ITEM_CB, "createGroupFolder",
                        (svc2, cb) -> s.ref.call(svc2, "createGroupFolder",
                                groupOf(p), p.optString("name", ""), cb)));

        // deleteGroupFolder(long groupCode, String folderId, IGroupFileCommonResultCallback)
        reg("group_file_folder_delete", NONE, true, "guild_id, folder_id",
                (s, p) -> s.call(s.svc("richmedia"), GROUP_FILE_RESULT_CB, "deleteGroupFolder",
                        (svc2, cb) -> s.ref.call(svc2, "deleteGroupFolder",
                                groupOf(p), p.optString("folder_id", ""), cb)));

        // renameGroupFolder(long groupCode, String folderId, String newName, IGroupItemCallback)
        reg("group_file_folder_rename", NONE, true, "guild_id, folder_id, name",
                (s, p) -> s.call(s.svc("richmedia"), GROUP_ITEM_CB, "renameGroupFolder",
                        (svc2, cb) -> s.ref.call(svc2, "renameGroupFolder", groupOf(p),
                                p.optString("folder_id", ""), p.optString("name", ""), cb)));

        // deleteGroupFile(long groupCode, ArrayList<Integer> busIds, ArrayList<String> files, cb)
        reg("group_file_delete", NONE, true, "guild_id, files[], bus_ids?",
                (s, p) -> {
                    final java.util.ArrayList<Integer> bus = p.has("bus_ids")
                            ? ints(p.optJSONArray("bus_ids"))
                            : new java.util.ArrayList<>(java.util.Collections.singletonList(p.optInt("bus_id", 102)));
                    final java.util.ArrayList<String> files = strings(p.optJSONArray("files"));
                    return s.call(s.svc("richmedia"), DELETE_GROUP_FILE_CB, "deleteGroupFile",
                            (svc2, cb) -> s.ref.call(svc2, "deleteGroupFile", groupOf(p), bus, files, cb));
                });

        // renameGroupFile(long groupCode, int busId, String fileId, String parentId, String newName, cb)
        reg("group_file_rename", NONE, true, "guild_id, file_id, name, parent_id?, bus_id?",
                (s, p) -> s.call(s.svc("richmedia"), RENAME_GROUP_FILE_CB, "renameGroupFile",
                        (svc2, cb) -> s.ref.call(svc2, "renameGroupFile", groupOf(p),
                                p.optInt("bus_id", 102), p.optString("file_id", ""),
                                p.optString("parent_id", ""), p.optString("name", ""), cb)));

        // moveGroupFile(long, ArrayList<Integer> busIds, ArrayList<String> files,
        //               String parentFolderId, String destFolderId, cb)
        reg("group_file_move", NONE, true, "guild_id, files[], dest_folder_id, parent_id?, bus_ids?",
                (s, p) -> {
                    final java.util.ArrayList<Integer> bus = p.has("bus_ids")
                            ? ints(p.optJSONArray("bus_ids"))
                            : new java.util.ArrayList<>(java.util.Collections.singletonList(p.optInt("bus_id", 102)));
                    final java.util.ArrayList<String> files = strings(p.optJSONArray("files"));
                    return s.call(s.svc("richmedia"), MOVE_GROUP_FILE_CB, "moveGroupFile",
                            (svc2, cb) -> s.ref.call(svc2, "moveGroupFile", groupOf(p), bus, files,
                                    p.optString("parent_id", ""), p.optString("dest_folder_id", ""), cb));
                });

        // transGroupFile(long groupCode, String fileId, ITransGroupFileCallback)
        reg("group_file_trans", NONE, true, "guild_id, file_id",
                (s, p) -> s.call(s.svc("richmedia"), TRANS_GROUP_FILE_CB, "transGroupFile",
                        (svc2, cb) -> s.ref.call(svc2, "transGroupFile",
                                groupOf(p), p.optString("file_id", ""), cb)));

        // 头衔/身份开关。内核写路径复用 QQClient 已核过的那两条。
        reg("identity_title_info", new String[]{"set_identity_title_info"}, true, "guild_id, show?",
                (s, p) -> s.opJson(s.qq.setIdentityTitleInfo(groupOf(p), p.optBoolean("show", true))));

        reg("identity_level_info", new String[]{"set_identity_level_info"}, true, "guild_id, show?",
                (s, p) -> s.opJson(s.qq.setGroupIdentityLevelInfo(groupOf(p), p.optBoolean("show", true))));

        // ------------------------------------------------------- 资料与好友

        // getUserDetailInfo(String uid, IOperateCallback) —— 回调只有 code/msg。
        reg("user_detail", NONE, false, "user_id",
                (s, p) -> {
                    final String uid = s.uidForUin(p.optString("user_id", ""));
                    return s.call(s.svc("profile"), OPERATE_CB, "getUserDetailInfo",
                            (svc2, cb) -> s.ref.call(svc2, "getUserDetailInfo", uid, cb));
                });

        // getUserDetailInfoByUin(long uin, IDetailInfoByUinCallback)
        reg("user_detail_by_uin", NONE, false, "user_id",
                (s, p) -> {
                    final long uin = parseUin(p.optString("user_id", ""));
                    return s.call(s.svc("profile"), DETAIL_BY_UIN_CB, "getUserDetailInfoByUin",
                            (svc2, cb) -> s.ref.call(svc2, "getUserDetailInfoByUin", uin, cb));
                });

        // getUserSimpleInfo(boolean, ArrayList<String> uids, IOperateCallback)
        reg("user_simple_info", NONE, false, "user_ids[]",
                (s, p) -> {
                    final java.util.ArrayList<String> uids = new java.util.ArrayList<>();
                    org.json.JSONArray a = p.optJSONArray("user_ids");
                    if (a == null && p.has("user_id")) a = new org.json.JSONArray().put(p.opt("user_id"));
                    for (int i = 0; a != null && i < a.length(); i++) uids.add(s.uidForUin(a.optString(i, "")));
                    return s.call(s.svc("profile"), OPERATE_CB, "getUserSimpleInfo",
                            (svc2, cb) -> s.ref.call(svc2, "getUserSimpleInfo", true, uids, cb));
                });

        // setLongNick(String, IOperateCallback)
        reg("long_nick", new String[]{"set_long_nick"}, true, "long_nick",
                (s, p) -> s.call(s.svc("profile"), OPERATE_CB, "setLongNick",
                        (svc2, cb) -> s.ref.call(svc2, "setLongNick", p.optString("long_nick", ""), cb)));

        // getBuddyRemark(ArrayList<String> uids) —— 同步读，回 HashMap<String,String>
        reg("friend_remark_get", NONE, false, "user_ids[]",
                (s, p) -> {
                    java.util.ArrayList<String> uids = new java.util.ArrayList<>();
                    org.json.JSONArray a = p.optJSONArray("user_ids");
                    if (a == null && p.has("user_id")) a = new org.json.JSONArray().put(p.opt("user_id"));
                    for (int i = 0; a != null && i < a.length(); i++) uids.add(s.uidForUin(a.optString(i, "")));
                    Object map = s.ref.call(s.svc("buddy"), "getBuddyRemark", uids);
                    return new org.json.JSONObject().put("ok", true)
                            .put("remark", s.mapJson(map));
                });

        // setBuddyRemark(RemarkParams, IOperateCallback)
        // RemarkParams: uid(String) remark(String) signInfo(RemarkSignExtInfo)
        reg("friend_remark_set", new String[]{"set_friend_remark"}, true, "user_id, remark",
                (s, p) -> {
                    Object req = s.neu(NS + "RemarkParams");
                    s.put(req, "uid", s.uidForUin(p.optString("user_id", "")));
                    s.put(req, "remark", p.optString("remark", ""));
                    return s.call(s.svc("buddy"), OPERATE_CB, "setBuddyRemark",
                            (svc2, cb) -> s.ref.call(svc2, "setBuddyRemark", req, cb));
                });

        reg("friend_category_add", NONE, true, "name",
                (s, p) -> s.call(s.svc("buddy"), OPERATE_CB, "addCategory",
                        (svc2, cb) -> s.ref.call(svc2, "addCategory", p.optString("name", ""), cb)));

        reg("friend_category_delete", NONE, true, "category_id",
                (s, p) -> s.call(s.svc("buddy"), OPERATE_CB, "delCategory",
                        (svc2, cb) -> s.ref.call(svc2, "delCategory", p.optInt("category_id", 0), cb)));

        reg("friend_category_rename", NONE, true, "category_id, name",
                (s, p) -> s.call(s.svc("buddy"), OPERATE_CB, "renameCategory",
                        (svc2, cb) -> s.ref.call(svc2, "renameCategory",
                                p.optInt("category_id", 0), p.optString("name", ""), cb)));

        reg("friend_category_set", NONE, true, "user_id, category_id",
                (s, p) -> {
                    final String uid = s.uidForUin(p.optString("user_id", ""));
                    final int id = p.optInt("category_id", 0);
                    return s.call(s.svc("buddy"), OPERATE_CB, "setBuddyCategory",
                            (svc2, cb) -> s.ref.call(svc2, "setBuddyCategory", uid, id, cb));
                });

        reg("friend_category_set_batch", NONE, true, "user_ids[], category_id",
                (s, p) -> {
                    final java.util.ArrayList<String> uids = new java.util.ArrayList<>();
                    org.json.JSONArray a = p.optJSONArray("user_ids");
                    for (int i = 0; a != null && i < a.length(); i++) uids.add(s.uidForUin(a.optString(i, "")));
                    final int id = p.optInt("category_id", 0);
                    return s.call(s.svc("buddy"), OPERATE_CB, "setBatchBuddyCategory",
                            (svc2, cb) -> s.ref.call(svc2, "setBatchBuddyCategory", uids, id, cb));
                });

        // reqToAddFriends(ReqToFriend, IOperateCallback)
        // ReqToFriend: buddyUin(long) buddyUid(String) phoneNumber(String) addFriendSetting(int)
        //   answer(String) remark(String) defaultCatgory(Integer) verifyInfo(String)
        //   securityVerify(Verify) sourceID(int) sourceSubID(int)
        reg("friend_add", new String[]{"add_friend"}, true,
                "user_id, remark?, verify_info?, answer?, source_id?",
                (s, p) -> {
                    Object req = s.neu(NS + "ReqToFriend");
                    final long uin = parseUin(p.optString("user_id", ""));
                    s.put(req, "buddyUin", uin);
                    s.put(req, "buddyUid", s.uidForUin(p.optString("user_id", "")));
                    s.put(req, "addFriendSetting", p.optInt("add_friend_setting", 0));
                    s.put(req, "remark", p.optString("remark", ""));
                    s.put(req, "verifyInfo", p.optString("verify_info", ""));
                    s.put(req, "answer", p.optString("answer", ""));
                    s.put(req, "sourceID", p.optInt("source_id", 3999));
                    s.put(req, "sourceSubID", p.optInt("source_sub_id", 0));
                    return s.call(s.svc("buddy"), OPERATE_CB, "reqToAddFriends",
                            (svc2, cb) -> s.ref.call(svc2, "reqToAddFriends", req, cb));
                });

        // ------------------------------------------------- 在线文件与临时会话

        // getOnlineFileMsgs(Contact, IMsgOperateCallback) / getAllOnlineFileMsgs(IMsgOperateCallback)
        reg("online_file_list", NONE, false, "channel_id?",
                (s, p) -> {
                    if (!p.has("channel_id") && !p.has("guild_id") && !p.has("user_id")) {
                        return s.call(s.svc("msg"), MSG_OPERATE_CB, "getAllOnlineFileMsgs",
                                (svc2, cb) -> s.ref.call(svc2, "getAllOnlineFileMsgs", cb));
                    }
                    return s.call(s.svc("msg"), MSG_OPERATE_CB, "getOnlineFileMsgs",
                            (svc2, cb) -> s.ref.call(svc2, "getOnlineFileMsgs", s.contactOf(p), cb));
                });

        // refuseReceiveOnlineFileMsg(Contact, long msgSeq, IOperateCallback)
        reg("online_file_refuse", NONE, true, "channel_id, message_seq",
                (s, p) -> s.call(s.svc("msg"), OPERATE_CB, "refuseReceiveOnlineFileMsg",
                        (svc2, cb) -> s.ref.call(svc2, "refuseReceiveOnlineFileMsg",
                                s.contactOf(p), p.optLong("message_seq", 0), cb)));

        // getTempChatInfo(int chatType, String uid, IGetTempChatInfoCallback)
        reg("temp_chat_info", NONE, false, "guild_id?|user_id, chat_type?",
                (s, p) -> {
                    final long g = groupOf(p);
                    final int chatType = p.optInt("chat_type", g != 0 ? QQClient.CT_GROUP : QQClient.CT_C2C);
                    final String peer = g != 0
                            ? String.valueOf(g)
                            : s.uidForUin(p.optString("user_id", ""));
                    return s.call(s.svc("msg"), TEMP_CHAT_CB, "getTempChatInfo",
                            (svc2, cb) -> s.ref.call(svc2, "getTempChatInfo", chatType, peer, cb));
                });
    }

    /** uin → uid；取不到就回原值（有些入口收的就是 uin 字符串）。 */
    private String uidForUin(String raw) {
        long uin = parseUin(raw);
        if (uin == 0) return raw == null ? "" : raw;
        try {
            String uid = qq.resolveUid(uin);
            if (uid != null && !uid.isEmpty()) return uid;
        } catch (Throwable ignore) {}
        return String.valueOf(uin);
    }

    private Object svc(String key) {
        switch (key) {
            case "msg": return qq.getMsgService();
            case "group": return qq.getGroupService();
            case "profile": return qq.getProfileService();
            case "buddy": return qq.getBuddyService();
            case "richmedia": return qq.getRichMediaService();
            case "search": return qq.getSearchService();
            case "collection": return qq.getCollectionService();
            default: return null;
        }
    }

    private static org.json.JSONObject opJson(QQClient.OpResult r) throws Exception {
        return new org.json.JSONObject().put("ok", r != null && r.ok())
                .put("code", r == null ? -1 : r.code)
                .put("msg", r == null ? "no result" : r.msg);
    }

    /** 同步返回的 Map 直接转 JSON（字段名是 uin/uid 这类普通串，不需要深度反射）。 */
    @SuppressWarnings("unchecked")
    private static org.json.JSONObject mapJson(Object map) throws Exception {
        org.json.JSONObject out = new org.json.JSONObject();
        if (map instanceof java.util.Map) {
            for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) map).entrySet())
                out.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : String.valueOf(e.getValue()));
        }
        return out;
    }

}
