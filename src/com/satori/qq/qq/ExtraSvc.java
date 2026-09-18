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

    // ------------------------------------------------------------------ profile

    // --------------------------------------------------------- recent contacts

    // ------------------------------------------------------------------- robot

}
