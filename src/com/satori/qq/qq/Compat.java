package com.satori.qq.qq;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * QQ 升级后的接口面自检。
 *
 * <p>升级 QQ 时模块最先坏的地方是内核接口：类改了名、方法换了签名、结构体删了字段。这些都不
 * 会报错，只会让某个动作静默超时或回空。逐个动作打一遍要几十分钟，所以这里把模块依赖的
 * 内核类型、服务入口、结构体字段与回调形状写成表，用反射一次性核对，几毫秒出结果。
 *
 * <p>`TYPES` 由源码里的类名字面量生成，不是手抄的。重新生成：
 *
 * <pre>
 * grep -rhoE 'com\.tencent\.qqnt\.(kernel|kernelpublic)\.nativeinterface\.[A-Za-z0-9_]+' src/ \
 *   | sort -u | sed 's/.*nativeinterface\.//'
 * </pre>
 *
 * <p>表里没有的运行时事实（方法参数个数、回调是否真的会回调）靠
 * {@link #observed()} 记录：`ExtraSvc.call` 每次调用都会记一行结果，跑一遍巡检脚本就能看到
 * 哪些入口开始不回调了。
 */
public final class Compat {
    private Compat() {}

    private static final String NS = "com.tencent.qqnt.kernel.nativeinterface.";

    /**
     * 模块引用到的内核类型（接口、结构体、枚举）。只列**必须存在**的：
     * `QQClient` 给 `DigestReq` 留的 `kernelpublic` 兜底名在 9.3.60 的 dex 里并不存在，
     * 那是条备用路径，列进来会让自检长期报一项假缺失。
     */
    private static final String[] TYPES = {
            "com.tencent.qqnt.kernel.nativeinterface.AddFavEmojiReq", "com.tencent.qqnt.kernel.nativeinterface.ApprovalBuddyRequest",
            "com.tencent.qqnt.kernel.nativeinterface.ArkElement", "com.tencent.qqnt.kernel.nativeinterface.BatchQueryCachedGroupDetailInfoReq",
            "com.tencent.qqnt.kernel.nativeinterface.BuddyListReqType", "com.tencent.qqnt.kernel.nativeinterface.ChatInfo",
            "com.tencent.qqnt.kernel.nativeinterface.ContactTopData", "com.tencent.qqnt.kernel.nativeinterface.DelBuddyInfo",
            "com.tencent.qqnt.kernel.nativeinterface.DigestReq", "com.tencent.qqnt.kernel.nativeinterface.EmojiDescInfo",
            "com.tencent.qqnt.kernel.nativeinterface.FaceElement", "com.tencent.qqnt.kernel.nativeinterface.FetchGroupEssenceListReq",
            "com.tencent.qqnt.kernel.nativeinterface.FileElement", "com.tencent.qqnt.kernel.nativeinterface.GIMSetGroupLevelInfoReq",
            "com.tencent.qqnt.kernel.nativeinterface.GIMSetGroupLevelInfoRsp", "com.tencent.qqnt.kernel.nativeinterface.GetAppCenterReq",
            "com.tencent.qqnt.kernel.nativeinterface.GetGroupMedalListReq", "com.tencent.qqnt.kernel.nativeinterface.GetGroupSignInStatusReq",
            "com.tencent.qqnt.kernel.nativeinterface.GetIdentityListReq", "com.tencent.qqnt.kernel.nativeinterface.GetRelatedGroupReq",
            "com.tencent.qqnt.kernel.nativeinterface.GetSubGroupInfoReq", "com.tencent.qqnt.kernel.nativeinterface.GroupBulletinListReq",
            "com.tencent.qqnt.kernel.nativeinterface.GroupDetailInfoReq", "com.tencent.qqnt.kernel.nativeinterface.GroupInfoSource",
            "com.tencent.qqnt.kernel.nativeinterface.GroupLinkReq", "com.tencent.qqnt.kernel.nativeinterface.GroupMemberCardInfoReq",
            "com.tencent.qqnt.kernel.nativeinterface.GroupMemberCommonReq", "com.tencent.qqnt.kernel.nativeinterface.GroupMemberExtReq",
            "com.tencent.qqnt.kernel.nativeinterface.GroupMemberHonorReq", "com.tencent.qqnt.kernel.nativeinterface.GroupMemberShutUpInfo",
            "com.tencent.qqnt.kernel.nativeinterface.GroupModifyInfo", "com.tencent.qqnt.kernel.nativeinterface.GroupModifyInfoReq",
            "com.tencent.qqnt.kernel.nativeinterface.GroupMsgMask", "com.tencent.qqnt.kernel.nativeinterface.GroupNotifyOperateMsg",
            "com.tencent.qqnt.kernel.nativeinterface.GroupNotifyOperateType", "com.tencent.qqnt.kernel.nativeinterface.GroupNotifyTargetMsg",
            "com.tencent.qqnt.kernel.nativeinterface.GroupRobotGetCreateGroupRobotsReq", "com.tencent.qqnt.kernel.nativeinterface.GroupRobotGetOwnedBotsReq",
            "com.tencent.qqnt.kernel.nativeinterface.IAddFavEmojiCallback", "com.tencent.qqnt.kernel.nativeinterface.IBatchGroupFileCountCallback",
            "com.tencent.qqnt.kernel.nativeinterface.IBatchQueryCachedGroupDetailInfoCallback", "com.tencent.qqnt.kernel.nativeinterface.IClientKeyCallback",
            "com.tencent.qqnt.kernel.nativeinterface.IDetailInfoByUinCallback", "com.tencent.qqnt.kernel.nativeinterface.IDigestCallback",
            "com.tencent.qqnt.kernel.nativeinterface.IFetchFavEmojiListCallback", "com.tencent.qqnt.kernel.nativeinterface.IFetchGroupEssenceListCallback",
            "com.tencent.qqnt.kernel.nativeinterface.IGetAioFirstViewLatestMsgCallback", "com.tencent.qqnt.kernel.nativeinterface.IGetAppCenterCallback",
            "com.tencent.qqnt.kernel.nativeinterface.IGetAutoReplyTextListCallback", "com.tencent.qqnt.kernel.nativeinterface.IGetDraftOperateCallback",
            "com.tencent.qqnt.kernel.nativeinterface.IGetGroupMedalListCallback", "com.tencent.qqnt.kernel.nativeinterface.IGetGroupMemberCardInfoCallback",
            "com.tencent.qqnt.kernel.nativeinterface.IGetGroupMemberOwnedRobotsCallback", "com.tencent.qqnt.kernel.nativeinterface.IGetGroupRobotListForCreateCallback",
            "com.tencent.qqnt.kernel.nativeinterface.IGetGroupSignInStatusCallback", "com.tencent.qqnt.kernel.nativeinterface.IGetIdentityListCallback",
            "com.tencent.qqnt.kernel.nativeinterface.IGetJoinGroupLinkCallback", "com.tencent.qqnt.kernel.nativeinterface.IGetMsgAbstractsCallback",
            "com.tencent.qqnt.kernel.nativeinterface.IGetMsgEmojiLikesListCallback", "com.tencent.qqnt.kernel.nativeinterface.IGetMsgSeqCallback",
            "com.tencent.qqnt.kernel.nativeinterface.IGetMultiMsgCallback", "com.tencent.qqnt.kernel.nativeinterface.IGetPskeyCallback",
            "com.tencent.qqnt.kernel.nativeinterface.IGetRecentUseEmojiListCallback", "com.tencent.qqnt.kernel.nativeinterface.IGetRecentUsedFaceListCallback",
            "com.tencent.qqnt.kernel.nativeinterface.IGetRelatedGroupCallback", "com.tencent.qqnt.kernel.nativeinterface.IGetSubGroupInfoCallback",
            "com.tencent.qqnt.kernel.nativeinterface.IGetTempChatInfoCallback", "com.tencent.qqnt.kernel.nativeinterface.IGroupAvatarWallCallback",
            "com.tencent.qqnt.kernel.nativeinterface.IGroupDetailInfoCallback", "com.tencent.qqnt.kernel.nativeinterface.IGroupMemberCacheCallback",
            "com.tencent.qqnt.kernel.nativeinterface.IGroupMemberCommonCallback", "com.tencent.qqnt.kernel.nativeinterface.IGroupMemberExtCallback",
            "com.tencent.qqnt.kernel.nativeinterface.IGroupMemberHonorCallback", "com.tencent.qqnt.kernel.nativeinterface.IGroupMemberIllegalInfoCallback",
            "com.tencent.qqnt.kernel.nativeinterface.IGroupMemberListCallback", "com.tencent.qqnt.kernel.nativeinterface.IGroupMemberMaxNumCallback",
            "com.tencent.qqnt.kernel.nativeinterface.IGroupMsgLimitFreqCallback", "com.tencent.qqnt.kernel.nativeinterface.IKernelBuddyListener",
            "com.tencent.qqnt.kernel.nativeinterface.IKernelGroupListener", "com.tencent.qqnt.kernel.nativeinterface.IKernelMsgListener",
            "com.tencent.qqnt.kernel.nativeinterface.IKernelRecentGetContactCallback", "com.tencent.qqnt.kernel.nativeinterface.IKernelRecentGetContactUnreadDetailsCallback",
            "com.tencent.qqnt.kernel.nativeinterface.IKernelRecentSnapShotCallback", "com.tencent.qqnt.kernel.nativeinterface.IKickMemberOperateCallback",
            "com.tencent.qqnt.kernel.nativeinterface.IModifyFavEmojiDescCallback", "com.tencent.qqnt.kernel.nativeinterface.IMsgOperateCallback",
            "com.tencent.qqnt.kernel.nativeinterface.IOperateCallback", "com.tencent.qqnt.kernel.nativeinterface.IOperateHiddenSessionCallback",
            "com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession", "com.tencent.qqnt.kernel.nativeinterface.IQueryGroupMuteMemberListCallback",
            "com.tencent.qqnt.kernel.nativeinterface.ISetGroupIdentityLevelInfoCallback", "com.tencent.qqnt.kernel.nativeinterface.ISetMsgEmojiLikesCallback",
            "com.tencent.qqnt.kernel.nativeinterface.ITransferGroupCallback", "com.tencent.qqnt.kernel.nativeinterface.IVideoPlayUrlCallback",
            "com.tencent.qqnt.kernel.nativeinterface.LinkInfo", "com.tencent.qqnt.kernel.nativeinterface.MarketFaceElement",
            "com.tencent.qqnt.kernel.nativeinterface.MemberExtInfoFilter", "com.tencent.qqnt.kernel.nativeinterface.MemberExtSourceType",
            "com.tencent.qqnt.kernel.nativeinterface.MsgAttributeInfo", "com.tencent.qqnt.kernel.nativeinterface.MsgElement",
            "com.tencent.qqnt.kernel.nativeinterface.MultiForwardMsgElement", "com.tencent.qqnt.kernel.nativeinterface.MultiMsgInfo",
            "com.tencent.qqnt.kernel.nativeinterface.PicElement", "com.tencent.qqnt.kernel.nativeinterface.PttElement",
            "com.tencent.qqnt.kernel.nativeinterface.QQNTWrapperUtil", "com.tencent.qqnt.kernel.nativeinterface.QueryMsgsParams",
            "com.tencent.qqnt.kernel.nativeinterface.RMReqExParams", "com.tencent.qqnt.kernel.nativeinterface.RecentHiddenSesionInfo",
            "com.tencent.qqnt.kernel.nativeinterface.RemarkParams", "com.tencent.qqnt.kernel.nativeinterface.ReplyElement",
            "com.tencent.qqnt.kernel.nativeinterface.ReqToFriend", "com.tencent.qqnt.kernel.nativeinterface.RichMediaElementGetReq",
            "com.tencent.qqnt.kernel.nativeinterface.RichMediaFilePathInfo", "com.tencent.qqnt.kernel.nativeinterface.SendRequestParam",
            "com.tencent.qqnt.kernel.nativeinterface.SetGroupMemberExtInfoReq", "com.tencent.qqnt.kernel.nativeinterface.SetIdentityTitleInfoReq",
            "com.tencent.qqnt.kernel.nativeinterface.SpecialCareSetting", "com.tencent.qqnt.kernel.nativeinterface.StSignInStatusReq",
            "com.tencent.qqnt.kernel.nativeinterface.StructLongMsgElement", "com.tencent.qqnt.kernel.nativeinterface.TextElement",
            "com.tencent.qqnt.kernel.nativeinterface.VASMsgBubble", "com.tencent.qqnt.kernel.nativeinterface.VASMsgElement",
            "com.tencent.qqnt.kernel.nativeinterface.VASMsgFont", "com.tencent.qqnt.kernel.nativeinterface.Verify",
            "com.tencent.qqnt.kernel.nativeinterface.VideoCodecFormatType", "com.tencent.qqnt.kernel.nativeinterface.VideoElement",
            "com.tencent.qqnt.kernelpublic.nativeinterface.Contact",
            "com.tencent.qqnt.kernelpublic.nativeinterface.MemberRole",
    };

    /** 会话上的服务入口 → 该服务必须实现的接口。 */
    private static final String[][] SERVICES = {
            {"getMsgService", "IKernelMsgService"},
            {"getGroupService", "IKernelGroupService"},
            {"getProfileService", "IKernelProfileService"},
            {"getBuddyService", "IKernelBuddyService"},
            {"getRichMediaService", "IKernelRichMediaService"},
            {"getRecentContactService", "IKernelRecentContactService"},
            {"getRobotService", "IKernelRobotService"},
    };

    /**
     * 模块会写入的字段名。结构体的字段改名是静默失败：`Ref.put` 找不到字段就没法写入，
     * 服务端只回参数错误。
     */
    private static final String[][] STRUCT_FIELDS = {
            {"GroupDetailInfoReq", "groupCode", "appid"},
            {"BatchQueryCachedGroupDetailInfoReq", "groupCodes"},
            {"GroupMemberCardInfoReq", "groupCode", "memberUin"},
            {"GroupLinkReq", "groupCode", "srcId", "needShortUrl", "additionalParam"},
            {"GetRelatedGroupReq", "fromGroupCode", "onlyNumber", "source"},
            {"GetSubGroupInfoReq", "groupCode"},
            {"GetAppCenterReq", "groupId", "page", "num", "from", "mode", "keyword"},
            {"GetGroupSignInStatusReq", "signInStatusReq"},
            {"StSignInStatusReq", "groupId", "uin", "scene"},
            {"GroupMemberInfoListId", "uid", "index"},
            {"GroupMemberCommonReq", "groupCode", "sourceType", "startUin", "uinList"},
            {"GetIdentityListReq", "groupCode", "memberUin"},
            {"GroupBulletinListReq", "startIndex", "num", "needPublisherInfo"},
            {"FetchGroupEssenceListReq", "groupCode", "pageStart", "pageLimit"},
            {"GetGroupMedalListReq", "groupCode"},
            {"GroupMemberHonorReq", "groupCode"},
            {"RemarkParams", "uid", "remark"},
            {"ReqToFriend", "buddyUin", "buddyUid", "verifyInfo", "answer", "remark", "sourceID"},
            {"SpecialCareSetting", "isOn", "isRingOn", "isZoneOn"},
            {"AddFavEmojiReq", "emojiPath", "fileName", "fileSize", "md5", "isMarkFace"},
            {"EmojiDescInfo", "emojiId", "md5", "resId", "desc"},
            {"RecentHiddenSesionInfo", "chatType", "peerUid", "peerUin", "isHidden"},
            {"ContactTopData", "chatType", "uid"},
            {"GroupRobotGetOwnedBotsReq", "groupCode", "members"},
    };

    /** 回调接口 → 回调方法的参数个数（`2+` 是 `(code, msg, …)` 形状，`1` 是纯载荷）。 */
    private static final String[][] CALLBACKS = {
            {"IOperateCallback", "2"},
            {"IGroupMemberHonorCallback", "2+"},
            {"IQueryGroupMuteMemberListCallback", "2+"},
            {"IGroupAvatarWallCallback", "2+"},
            {"IGetGroupMedalListCallback", "2+"},
            {"IFetchGroupEssenceListCallback", "2+"},
            {"IGetIdentityListCallback", "2+"},
            {"IGroupMemberCommonCallback", "2+"},
            {"IGroupMemberExtCallback", "2+"},
            {"IDetailInfoByUinCallback", "2+"},
            {"IFetchFavEmojiListCallback", "2+"},
            {"IGetAutoReplyTextListCallback", "2+"},
            {"IGetRecentUseEmojiListCallback", "2+"},
            {"IGetRecentUsedFaceListCallback", "2+"},
            {"IGetMsgEmojiLikesListCallback", "2+"},
            {"IMsgOperateCallback", "2+"},
            {"IGetMsgSeqCallback", "2+"},
            {"IGetMsgAbstractsCallback", "2+"},
            {"IGetAioFirstViewLatestMsgCallback", "2+"},
            {"IGetMultiMsgCallback", "2+"},
            {"IOperateHiddenSessionCallback", "2+"},
            {"IGetDraftOperateCallback", "2+"},
            {"IAddFavEmojiCallback", "2+"},
            {"IModifyFavEmojiDescCallback", "2+"},
            {"IGetTempChatInfoCallback", "2+"},
            {"IGetJoinGroupLinkCallback", "2+"},
            {"IGetGroupMemberCardInfoCallback", "2+"},
            {"IGetRelatedGroupCallback", "2+"},
            {"IGetSubGroupInfoCallback", "2+"},
            {"IGetAppCenterCallback", "2+"},
            {"IGetGroupSignInStatusCallback", "2+"},
            {"ITransferGroupCallback", "2+"},
            {"IGroupMemberIllegalInfoCallback", "2+"},
            {"IGroupMemberCacheCallback", "2+"},
            {"IGroupMemberMaxNumCallback", "2+"},
            {"IGroupMsgLimitFreqCallback", "2+"},
            {"IGroupMemberListCallback", "2+"},
            {"IBatchGroupFileCountCallback", "2+"},
            {"IKernelRecentGetContactCallback", "2+"},
            {"IKernelRecentSnapShotCallback", "1,2+"},
            {"IKernelRecentGetContactUnreadDetailsCallback", "2+"},
            {"IBatchQueryCachedGroupDetailInfoCallback", "1"},
            {"IGroupDetailInfoCallback", "2+"},
            {"IGetGroupRobotListForCreateCallback", "2+"},
            {"IGetGroupMemberOwnedRobotsCallback", "2+"},
    };


    /** 静态自检：类在不在、字段在不在、回调形状对不对。不发任何内核请求。 */
    public static JSONObject audit(Ref ref, String qqVersion) throws Exception {        JSONObject out = new JSONObject();
        JSONArray missing = new JSONArray();
        int ok = 0;

        for (String name : TYPES) {
            if (ref.clsOrNull(name) != null) { ok++; continue; }
            missing.put(new JSONObject().put("kind", "type").put("name", name));
        }
        JSONObject types = new JSONObject().put("ok", ok).put("total", TYPES.length);

        ok = 0;
        Class<?> session = ref.clsOrNull(NS + "IQQNTWrapperSession");
        for (String[] service : SERVICES) {
            String getter = service[0];
            boolean has = session != null && hasMethod(session, getter, 0)
                    && ref.clsOrNull(NS + service[1]) != null;
            if (has) { ok++; continue; }
            missing.put(new JSONObject().put("kind", "service").put("name", getter)
                    .put("needs", NS + service[1]));
        }
        JSONObject services = new JSONObject().put("ok", ok).put("total", SERVICES.length);

        int lifecycleOk = 0;
        for (String method : new String[]{"switchToFront", "switchToBackGround"}) {
            if (session != null && hasMethod(session, method, 0)) lifecycleOk++;
            else missing.put(new JSONObject().put("kind", "session").put("name", method));
        }
        Class<?> foreground = ref.clsOrNull("mqq.app.Foreground");
        if (foreground != null && hasMethod(foreground, "isCurrentProcessForeground", 0)) lifecycleOk++;
        else missing.put(new JSONObject().put("kind", "lifecycle").put("name", "isCurrentProcessForeground"));

        ok = 0;
        for (String[] entry : STRUCT_FIELDS) {
            Class<?> cls = ref.clsOrNull(NS + entry[0]);
            if (cls == null) {
                missing.put(new JSONObject().put("kind", "struct").put("name", entry[0])
                        .put("detail", "class missing"));
                continue;
            }
            boolean complete = true;
            for (int i = 1; i < entry.length; i++) {
                if (!hasField(cls, entry[i])) {
                    missing.put(new JSONObject().put("kind", "field")
                            .put("name", entry[0] + "." + entry[i]));
                    complete = false;
                }
            }
            if (complete) ok++;
        }
        JSONObject structs = new JSONObject().put("ok", ok).put("total", STRUCT_FIELDS.length);

        ok = 0;
        for (String[] entry : CALLBACKS) {
            Class<?> cls = ref.clsOrNull(NS + entry[0]);
            String[] arities = entry[1].split(",");
            boolean complete = cls != null;
            if (complete) {
                // 列表里的参数个数是「或」：同一个回调在不同版本可能多带一个载荷参数。
                boolean any = false;
                for (String arity : arities) {
                    String a = arity.trim();
                    boolean atLeast = a.endsWith("+");
                    int argc = Integer.parseInt(atLeast ? a.substring(0, a.length() - 1) : a);
                    if (hasCallback(cls, argc, atLeast)) { any = true; break; }
                }
                if (!any) {
                    complete = false;
                    missing.put(new JSONObject().put("kind", "callback")
                            .put("name", entry[0]).put("detail", "no on*(…" + entry[1] + ")"));
                }
            } else {
                missing.put(new JSONObject().put("kind", "callback").put("name", entry[0])
                        .put("detail", "class missing"));
            }
            if (complete) ok++;
        }
        JSONObject callbacks = new JSONObject().put("ok", ok).put("total", CALLBACKS.length);


        int total = TYPES.length + SERVICES.length + STRUCT_FIELDS.length + CALLBACKS.length + 3;
        out.put("qq_version", qqVersion == null ? "" : qqVersion);
        out.put("checked_epoch_ms", System.currentTimeMillis());
        out.put("ok", missing.length() == 0);
        out.put("passed", types.optInt("ok") + services.optInt("ok") + structs.optInt("ok")
                + callbacks.optInt("ok") + lifecycleOk);
        out.put("total", total);
        out.put("types", types);
        out.put("services", services);
        out.put("structs", structs);
        out.put("callbacks", callbacks);
        out.put("lifecycle", new JSONObject().put("ok", lifecycleOk).put("total", 3));
        out.put("missing", missing);
        return out;
    }

    /** 只按名字查方法（-1 参数个数那条用）。 */
    static boolean hasMethodNamed(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)) return true;
            }
            for (Class<?> itf : c.getInterfaces()) {
                for (java.lang.reflect.Method m : itf.getDeclaredMethods()) {
                    if (m.getName().equals(name)) return true;
                }
            }
        }
        return false;
    }

    /** 方法存在性：按名字与参数个数匹配，不比对具体类型（QQ 用 long/ArrayList 等基本形状）。 */
    static boolean hasMethod(Class<?> cls, String name, int argc) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == argc) return true;
            }
            for (Class<?> itf : c.getInterfaces()) {
                for (java.lang.reflect.Method m : itf.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterTypes().length == argc) return true;
                }
            }
        }
        return false;
    }

    static boolean hasField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                c.getDeclaredField(name);
                return true;
            } catch (NoSuchFieldException ignore) {
                // 继续往父类找
            }
        }
        return false;
    }

    /**
     * 回调接口里有没有一个 on* 方法。`atLeast` 为真表示参数个数 >= argc——内核回调常带额外参数
     * （例如 `IBatchGroupFileCountCallback.onResult(int, String, ArrayList, ArrayList)` 是 4 个），
     * 模块只依赖前两个（code、msg）与紧跟的那个载荷。
     */
    static boolean hasCallback(Class<?> cls, int argc, boolean atLeast) {
        for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
            if (!m.getName().startsWith("on")) continue;
            int n = m.getParameterTypes().length;
            if (atLeast ? n >= argc : n == argc) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ 运行时观测

    private static final java.util.concurrent.ConcurrentHashMap<String, long[]> OBSERVED =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * `ExtraSvc.call` 每次调用记一行：成功 / 超时 / 其它失败。QQ 升级后哪个入口开始不回调，
     * 跑一遍巡检脚本再看这里就知道，比对着一堆 15 秒超时猜要快。
     */
    public static void observe(String label, String outcome) {
        long[] row = OBSERVED.computeIfAbsent(label, k -> new long[3]);
        synchronized (row) {
            if ("ok".equals(outcome)) row[0]++;
            else if ("timeout".equals(outcome)) row[1]++;
            else row[2]++;
        }
    }

    /** 已观测到的内核调用结果。 */
    public static JSONObject observed() throws Exception {
        JSONObject out = new JSONObject();
        JSONArray rows = new JSONArray();
        for (java.util.Map.Entry<String, long[]> e : new java.util.TreeMap<>(OBSERVED).entrySet()) {
            long[] v = e.getValue();
            rows.put(new JSONObject().put("label", e.getKey())
                    .put("ok", v[0]).put("timeout", v[1]).put("failed", v[2]));
        }
        out.put("calls", rows);
        out.put("count", rows.length());
        return out;
    }
}
