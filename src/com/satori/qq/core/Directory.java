package com.satori.qq.core;

import com.satori.qq.qq.QQClient;
import com.satori.qq.qq.Ref;
import com.satori.qq.satori.Codec;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** 「谁是谁」：群、成员、好友、资料的查询，以及 uin↔uid 的解析。读到的名字顺手记进缓存。 */
final class Directory {
    private final QQClient qq;
    private final MsgStore store;
    private final Map<Long, String> groupNames = new ConcurrentHashMap<>();

    Directory(QQClient qq, MsgStore store) {
        this.qq = qq;
        this.store = store;
    }

    void reset() { groupNames.clear(); }

    void rememberGroup(long groupId, String name) {
        if (groupId != 0 && name != null && !name.isEmpty()) groupNames.put(groupId, name);
    }

    /** 事件与历史条目里补上群名（内核没带时用缓存里见过的）。 */
    void attachGroupName(JSONObject ob) {
        if (ob == null || !ob.has("group_id") || !ob.optString("group_name", "").isEmpty()) return;
        String name = groupNames.get(ob.optLong("group_id"));
        if (name == null || name.isEmpty()) return;
        try {
            ob.put("group_name", name);
        } catch (Exception ignore) {
            // 补不上就不补
        }
    }

    /** 点名一个群的请求参数：{@code guild_id}，退而求其次 {@code channel_id}。 */
    static long guildId(JSONObject p) {
        long g = Ids.parse(p.optString("guild_id", ""));
        if (g == 0) g = Ids.parse(p.optString("channel_id", ""));
        if (g == 0) throw ApiError.badRequest("missing guild_id");
        return g;
    }

    // ------------------------------------------------------------------ uin ↔ uid

    /** uin 对应的 uid：缓存 → 资料服务；解析不出来返回空串。 */
    String uidOf(long uin) {
        String uid = store.uidOf(uin);
        if (uid == null || uid.isEmpty()) {
            uid = qq.resolveUid(uin);
            if (uid != null && !uid.isEmpty()) store.learnUid(uin, uid);
        }
        return uid == null ? "" : uid;
    }

    /** Resolve a uin to its uid for a group action: cache -> profile service -> group member list. */
    String uidFor(long groupId, long uin) throws Exception {
        if (uin == 0) throw ApiError.badRequest("missing user_id");
        String uid = uidOf(uin);
        if (!uid.isEmpty()) return uid;
        if (groupId != 0) {
            Map<String, Object> members = qq.getAllMembers(groupId);
            if (members != null) {
                for (Object member : members.values()) {
                    if (Ref.asLong(qq.ref.get(member, "uin")) == uin) {
                        String found = Ref.asStr(qq.ref.get(member, "uid"));
                        store.learnUid(uin, found);
                        return found;
                    }
                }
            }
        }
        throw ApiError.notFound("cannot resolve uid for user " + uin);
    }

    long uinOf(String uid) {
        if (uid == null || uid.isEmpty()) return 0;
        long uin = store.uinOf(uid);
        if (uin != 0) return uin;
        uin = qq.resolveUin(uid);
        if (uin != 0) store.learnUid(uin, uid);
        return uin;
    }

    // ------------------------------------------------------------------ kernel readers

    private JSONArray groupList() {
        JSONArray arr = new JSONArray();
        for (Object info : qq.getGroupList()) {
            try {
                long gid = Ref.asLong(qq.ref.get(info, "groupCode"));
                String name = qq.groupName(gid);
                qq.rememberGroupName(gid, name);
                rememberGroup(gid, name);
                arr.put(new JSONObject()
                        .put("group_id", gid)
                        .put("group_name", name)
                        .put("member_count", Ref.asInt(qq.ref.get(info, "memberCount")))
                        .put("max_member_count", Ref.asInt(qq.ref.get(info, "maxMember"))));
            } catch (Throwable ignore) {
                // 单个群读不出来不拖累整张表
            }
        }
        return arr;
    }

    private JSONArray friendList() throws Exception {
        JSONArray arr = new JSONArray();
        for (Map.Entry<String, Object> entry : qq.getFriendCoreInfos().entrySet()) {
            Object info = entry.getValue();
            long uin = Ref.asLong(qq.ref.get(info, "uin"));
            if (uin == 0) continue;
            String uid = Ref.asStr(qq.ref.get(info, "uid"));
            store.learnUid(uin, uid.isEmpty() ? entry.getKey() : uid);
            arr.put(new JSONObject()
                    .put("user_id", uin)
                    .put("nickname", Ref.asStr(qq.ref.get(info, "nick")))
                    .put("remark", Ref.asStr(qq.ref.get(info, "remark"))));
        }
        return arr;
    }

    private JSONObject strangerInfo(long userId) throws Exception {
        if (userId == 0) throw ApiError.badRequest("missing user_id");
        Object info = qq.getCoreInfo(userId);
        if (info == null) throw ApiError.notFound("profile not found for user " + userId);
        String uid = Ref.asStr(qq.ref.get(info, "uid"));
        if (!uid.isEmpty()) store.learnUid(userId, uid);
        return new JSONObject()
                .put("user_id", userId)
                .put("nickname", Ref.asStr(qq.ref.get(info, "nick")))
                .put("sex", "unknown")
                .put("age", 0)
                .put("qid", "")
                .put("level", 0)
                .put("login_days", 0);
    }

    JSONObject groupMemberInfo(long groupId, long userId) throws Exception {
        if (groupId == 0 || userId == 0) throw ApiError.badRequest("missing group_id/user_id");
        Map<String, Object> members = qq.getAllMembers(groupId, true);
        if (members == null) throw ApiError.failed("cannot fetch group members");
        for (Object member : members.values()) {
            if (Ref.asLong(qq.ref.get(member, "uin")) == userId) return memberJson(groupId, member);
        }
        throw ApiError.notFound("member " + userId + " not found in group " + groupId);
    }

    private JSONArray groupMemberList(long groupId) throws Exception {
        if (groupId == 0) throw ApiError.badRequest("missing group_id");
        Map<String, Object> members = qq.getAllMembers(groupId);
        if (members == null) throw ApiError.failed("cannot fetch group members");
        JSONArray arr = new JSONArray();
        for (Object member : members.values()) arr.put(memberJson(groupId, member));
        return arr;
    }

    private JSONObject memberJson(long groupId, Object member) throws Exception {
        long uin = Ref.asLong(qq.ref.get(member, "uin"));
        String role = roleName(qq.ref.get(member, "role"));
        store.learnUid(uin, Ref.asStr(qq.ref.get(member, "uid")));
        store.learnRole(groupId, uin, role);
        return new JSONObject()
                .put("group_id", groupId)
                .put("user_id", uin)
                .put("nickname", Ref.asStr(qq.ref.get(member, "nick")))
                .put("card", Ref.asStr(qq.ref.get(member, "cardName")))
                .put("sex", "unknown")
                .put("age", 0)
                .put("area", "")
                .put("join_time", Ref.asInt(qq.ref.get(member, "joinTime")))
                .put("last_sent_time", Ref.asInt(qq.ref.get(member, "lastSpeakTime")))
                .put("level", String.valueOf(Ref.asInt(qq.ref.get(member, "memberLevel"))))
                .put("role", role)
                .put("unfriendly", false)
                .put("title", Ref.asStr(qq.ref.get(member, "memberSpecialTitle")))
                .put("title_expire_time", Ref.asLong(qq.ref.get(member, "specialTitleExpireTime")))
                .put("card_changeable", true);
    }

    private String roleName(Object roleEnum) {
        try {
            String name = String.valueOf(qq.ref.call(roleEnum, "name")).toUpperCase();
            if (name.contains("OWNER")) return "owner";
            if (name.contains("ADMIN")) return "admin";
        } catch (Throwable ignore) {
            // 读不出角色就按普通成员算
        }
        return "member";
    }

    JSONObject groupInfo(long groupId) throws Exception {
        Object info = qq.groupInfo(groupId);
        if (info == null) throw ApiError.notFound("group not found: " + groupId);
        String name = qq.groupName(groupId);
        qq.rememberGroupName(groupId, name);
        int flag3 = Ref.asInt(qq.ref.get(info, "groupFlagExt3"));
        return new JSONObject()
                .put("group_id", Ref.asLong(qq.ref.get(info, "groupCode")))
                .put("group_name", name)
                .put("member_count", Ref.asInt(qq.ref.get(info, "memberCount")))
                .put("max_member_count", Ref.asInt(qq.ref.get(info, "maxMember")))
                .put("group_flag_ext3", flag3)
                .put("honor_open", (flag3 & QQClient.HONOR_AIO_FLAG) == 0);
    }

    // ------------------------------------------------------------------ Satori shapes

    JSONObject guild(JSONObject p) throws Exception {
        JSONObject g = groupInfo(guildId(p));
        return Codec.guild(g.optLong("group_id"), g.optString("group_name"));
    }

    JSONArray guilds() throws Exception {
        JSONArray src = groupList();
        JSONArray data = new JSONArray();
        for (int i = 0; i < src.length(); i++) {
            JSONObject g = src.optJSONObject(i);
            if (g != null) data.put(Codec.guild(g.optLong("group_id"), g.optString("group_name")));
        }
        return data;
    }

    JSONObject channel(JSONObject p) throws Exception {
        String channelId = p.optString("channel_id", p.optString("guild_id", ""));
        if (Codec.isPrivateChannel(channelId)) {
            return Codec.channel(QQClient.CT_C2C, Codec.channelPeer(channelId), "");
        }
        long gid = Codec.channelPeer(channelId);
        return Codec.channel(QQClient.CT_GROUP, gid, groupInfo(gid).optString("group_name"))
                .put("parent_id", String.valueOf(gid));
    }

    JSONArray channels(JSONObject p) throws Exception {
        long gid = guildId(p);
        return new JSONArray().put(Codec.channel(QQClient.CT_GROUP, gid, groupInfo(gid).optString("group_name")));
    }

    /**
     * QQ's rank model is the built-in owner/admin/member trio, and that is all the kernel can
     * grant ({@code guild.member.role.set} only acts on `admin`). The richer per-member identity
     * data — level, titles, tags — is a separate concept and lives in `internal/member_identity`.
     */
    static JSONArray roles(long groupId) throws Exception {
        if (groupId == 0) throw ApiError.badRequest("missing guild_id");
        JSONArray roles = new JSONArray();
        for (String role : new String[]{"owner", "admin", "member"}) {
            roles.put(new JSONObject().put("id", role).put("name", role));
        }
        return roles;
    }

    JSONObject member(JSONObject p) throws Exception {
        return memberShape(groupMemberInfo(guildId(p), Ids.parse(p.optString("user_id", ""))));
    }

    JSONArray members(JSONObject p) throws Exception {
        JSONArray src = groupMemberList(guildId(p));
        JSONArray data = new JSONArray();
        for (int i = 0; i < src.length(); i++) {
            JSONObject m = src.optJSONObject(i);
            if (m != null) data.put(memberShape(m));
        }
        return data;
    }

    JSONArray memberRoles(JSONObject p) throws Exception {
        long groupId = guildId(p), userId = Ids.parse(p.optString("user_id", ""));
        if (userId == 0) throw ApiError.badRequest("missing user_id");
        JSONArray roles = memberShape(groupMemberInfo(groupId, userId)).optJSONArray("roles");
        return roles == null ? new JSONArray() : roles;
    }

    private static JSONObject memberShape(JSONObject m) throws Exception {
        String card = m.optString("card", "");
        String title = m.optString("title", "");
        String role = m.optString("role", "member");
        JSONObject out = new JSONObject()
                .put("user", Codec.user(m.optLong("user_id"), m.optString("nickname"), card))
                .put("roles", new JSONArray().put(new JSONObject().put("id", role).put("name", role)));
        if (!card.isEmpty()) out.put("nick", card).put("name", card);
        if (!title.isEmpty()) out.put("title", title).put("special_title", title);
        long titleExpire = m.optLong("title_expire_time", 0);
        if (titleExpire != 0) out.put("title_expire_time", titleExpire);
        long joined = m.optLong("join_time", 0);
        if (joined > 0) out.put("joined_at", joined * 1000);
        return out;
    }

    JSONObject user(JSONObject p) throws Exception {
        JSONObject s = strangerInfo(Ids.parse(p.optString("user_id", "")));
        return Codec.user(s.optLong("user_id"), s.optString("nickname"), "");
    }

    JSONArray friends() throws Exception {
        JSONArray src = friendList();
        JSONArray data = new JSONArray();
        for (int i = 0; i < src.length(); i++) {
            JSONObject f = src.optJSONObject(i);
            if (f == null) continue;
            JSONObject user = Codec.user(f.optLong("user_id"), f.optString("nickname"), f.optString("remark"));
            data.put(new JSONObject().put("user", user).put("nick", f.optString("remark")));
        }
        return data;
    }
}
