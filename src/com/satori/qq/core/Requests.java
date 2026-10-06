package com.satori.qq.core;

import com.satori.qq.qq.QQClient;
import com.satori.qq.qq.Ref;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

/** 好友申请与入群申请：从内核通知生成事件，记住还没处理的，再按 flag 同意或拒绝。 */
final class Requests {
    private record Friend(String uid, long time, JSONObject event, boolean decided) {}

    private record Group(long seq, long groupCode, Object type, JSONObject event, boolean decided) {}

    private final QQClient qq;
    private final Identity identity;
    private final Directory directory;
    private final Map<String, Friend> friends = new ConcurrentHashMap<>();
    private final Map<String, Group> groups = new ConcurrentHashMap<>();
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    Requests(QQClient qq, Identity identity, Directory directory) {
        this.qq = qq;
        this.identity = identity;
        this.directory = directory;
    }

    void reset() {
        friends.clear();
        groups.clear();
        seen.clear();
    }

    /** 新连上的客户端要补收的、还没处理的申请事件。 */
    Collection<JSONObject> pending() {
        Collection<JSONObject> out = new ArrayList<>();
        for (Friend f : friends.values()) if (f.event() != null && !f.decided()) out.add(f.event());
        for (Group g : groups.values()) if (g.event() != null && !g.decided()) out.add(g.event());
        return out;
    }

    private boolean firstSighting(String key) {
        boolean first = seen.add(key);
        if (seen.size() > 8000) seen.clear();
        return first;
    }

    /** 内核的好友申请记录 → 事件；已处理的、自己发起的、见过的返回 null。 */
    JSONObject friendEvent(Object req) throws Exception {
        if (req == null) return null;
        boolean initiator = Ref.asBool(qq.ref.get(req, "isInitiator"));
        boolean decided = Ref.asBool(qq.ref.get(req, "isDecide"));
        int reqType = Ref.asInt(qq.ref.get(req, "reqType"));
        if (initiator) return null;
        if (decided && reqType != 13) return null; // 13 = KMEINITIATORWAITPEERCONFIRM
        String uid = Ref.asStr(qq.ref.get(req, "friendUid"));
        long reqTime = Ref.asLong(qq.ref.get(req, "reqTime"));
        if (uid.isEmpty() || reqTime == 0) return null;
        String flag = String.valueOf(reqTime);
        long time = reqTime > 1_000_000_000_000L ? reqTime / 1000 : reqTime;
        JSONObject ev = Notices.friendRequest(identity.selfUin(), time, directory.uinOf(uid),
                Ref.asStr(qq.ref.get(req, "extWords")), flag);
        friends.put(flag, new Friend(uid, reqTime, ev, decided));
        return firstSighting("f:" + flag) ? ev : null;
    }

    /** 内核的入群申请通知 → 事件；不是「需要管理员处理」的、已处理的、见过的返回 null。 */
    JSONObject groupEvent(Object notify) throws Exception {
        if (notify == null) return null;
        Object status = qq.ref.get(notify, "status");
        boolean unhandled = status == null || Ref.enumName(status).contains("KUNHANDLE");
        Object type = qq.ref.get(notify, "type");
        String typeName = Ref.enumName(type);
        String sub;
        boolean invited = false;
        if (typeName.contains("REQUESTJOINNEEDADMINISTRATORPASS") || typeName.contains("INVITEDNEEDADMINISTRATORPASS")) {
            sub = "add";
        } else if (typeName.contains("INVITEDBYMEMBER")) {
            sub = "invite";
            invited = true;
        } else {
            return null;
        }
        long seq = Ref.asLong(qq.ref.get(notify, "seq"));
        Object group = qq.ref.get(notify, "group");
        long groupCode = group == null ? 0 : Ref.asLong(qq.ref.get(group, "groupCode"));
        if (seq == 0 || groupCode == 0) return null;
        Object user = qq.ref.get(notify, invited ? "user2" : "user1");
        String uid = user == null ? "" : Ref.asStr(qq.ref.get(user, "uid"));
        String flag = String.valueOf(seq);
        long actionTime = Ref.asLong(qq.ref.get(notify, "actionTime"));
        long time = actionTime > 1_000_000_000_000L ? actionTime / 1000
                : actionTime > 0 ? actionTime : System.currentTimeMillis() / 1000;
        JSONObject ev = Notices.groupRequest(identity.selfUin(), time, groupCode, directory.uinOf(uid), sub,
                Ref.asStr(qq.ref.get(notify, "postscript")), flag);
        groups.put(flag, new Group(seq, groupCode, type, ev, !unhandled));
        if (!unhandled) return null;
        return firstSighting("g:" + flag) ? ev : null;
    }

    void approveFriend(String flag, boolean approve, String remark) {
        if (flag == null || flag.isEmpty()) throw ApiError.badRequest("missing flag");
        Friend req = friends.get(flag);
        if (req == null) {
            qq.refreshBuddyReqs();
            req = friends.get(flag);
        }
        String uid = req != null ? req.uid() : "";
        if (uid.isEmpty()) throw ApiError.notFound("unknown friend request flag");
        long reqTime = req.time();
        ApiError.require(qq.approvalFriendRequest(uid, approve, approve ? "" : remark, reqTime));
        friends.remove(flag);
    }

    void approveGroup(String flag, boolean approve, String reason) {
        if (flag == null || flag.isEmpty()) throw ApiError.badRequest("missing flag");
        Group req = groups.get(flag);
        if (req == null) {
            qq.refreshGroupNotifies();
            req = groups.get(flag);
        }
        if (req == null) throw ApiError.notFound("unknown group request flag");
        ApiError.require(qq.operateGroupNotify(req.seq(), req.groupCode(), req.type(), approve, reason));
        groups.remove(flag);
    }
}
