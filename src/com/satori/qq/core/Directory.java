package com.satori.qq.core;

import com.satori.qq.qq.Convert;
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
        long groupId = ob.optLong("group_id");
        String name = groupNames.get(groupId);
        if (name == null || name.isEmpty()) {
            // 部署以来见过的群名：内核会话列表（内存）优先，再退到落盘的那份。两条都不阻塞。
            name = qq.groupName(groupId);
            if (name == null || name.isEmpty()) name = qq.knownGroupName(groupId);
            if (name != null && !name.isEmpty()) groupNames.put(groupId, name);
        }
        if (name == null || name.isEmpty()) return;
        try {
            ob.put("group_name", name);
        } catch (Exception ignore) {
            // 补不上就不补
        }
    }

    /**
     * 成员变动事件里补上当事人的名字：内核的灰条只给 uid / uin，名字得自己找。
     *
     * <p>只读已经取到的成员表，绝不等内核——入群欢迎这类「当事人一到就要说一句」的场景
     * 不该为了一个昵称多等几秒；表里没有时退一步问资料服务（本地调用），仍没有就留空，
     * 同时让成员表在后台补一份，下一次（含同一个人的其他事件）就有名字了。</p>
     */
    void attachMemberName(JSONObject ob) {
        if (ob == null || !ob.has("group_id")) return;
        String type = ob.optString("notice_type", "");
        if (!("group_increase".equals(type) || "group_decrease".equals(type) || "group_ban".equals(type))) return;
        long groupId = ob.optLong("group_id");
        long uin = ob.optLong("user_id");
        if (groupId == 0 || uin == 0) return;
        // 入群灰条自己带着昵称：用它，别再查成员表与资料服务。
        if (!ob.optString("member_name", "").isEmpty()) return;
        Object member = qq.cachedGroupMember(groupId, null, uin);
        if (member != null) {
            String uid = Ref.asStr(qq.ref.getOrNull(member, "uid"));
            // 入群欢迎紧接着就要 @ 这个人：uid 在这里学到，发消息时不必再问一次资料服务。
            if (!uid.isEmpty()) store.learnUid(uin, uid);
        }
        String name = member == null ? "" : firstNonEmpty(
                Ref.asStr(qq.ref.getOrNull(member, "cardName")),
                Ref.asStr(qq.ref.getOrNull(member, "nick")));
        if (name.isEmpty()) {
            // 资料服务是本地缓存/库读，不是网络请求；新入群的人常常只在这里查得到昵称。
            Object core = qq.getCoreInfo(uin);
            if (core != null) name = Ref.asStr(qq.ref.getOrNull(core, "nick"));
        }
        if (name.isEmpty()) {
            qq.warmGroupMembersAsync(groupId);
            return;
        }
        try {
            ob.put("member_name", name);
        } catch (Exception ignore) {
            // 补不上就不补
        }
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) return a;
        return b == null ? "" : b;
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

    /**
     * 群里这个 uid 的号码：缓存 → 资料服务 → 成员表缓存；都没有且 {@code refresh} 时在后台向服务器
     * 重拉一份成员表（同一个群同时只拉一份，不在调用线程上等），下一次再查就有了。刚进群的人
     * 常常要重拉之后才查得到。
     */
    long memberUin(long groupId, String uid, boolean refresh) {
        long uin = uinOf(uid);
        if (uin != 0 || groupId == 0) return uin;
        Object member = qq.cachedGroupMember(groupId, uid, 0);
        if (member == null) {
            if (refresh) qq.refreshGroupMembersAsync(groupId, true);
            return 0;
        }
        uin = Ref.asLong(qq.ref.get(member, "uin"));
        if (uin != 0) store.learnUid(uin, uid);
        return uin;
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

    /**
     * 单人查询最多等内核这么久。成员表缓存里没有这个人才向服务器重拉，而且不按整份名单的
     * 15 秒耐心等：`guild.member.get` 常被放在「要先问出名字才能说话」的路上。
     */
    private static final long MEMBER_WAIT_MS = 4000;

    JSONObject groupMemberInfo(long groupId, long userId) throws Exception {
        if (groupId == 0 || userId == 0) throw ApiError.badRequest("missing group_id/user_id");
        // 先看已经取到的成员表：单查一个人不该把整份名单向服务器重拉一遍（内核那条路
        // 最长会挂 15 秒）。新入群的人常常还不在表里，那时才刷新，等得也有限。
        Object known = qq.cachedGroupMember(groupId, null, userId);
        if (known != null) {
            // 成员表拉了很久了：这次先用它，后台换一份新的（身份、名片都可能变过）。
            if (qq.groupMembersStale(groupId)) qq.refreshGroupMembersAsync(groupId, true);
            return memberJson(groupId, known);
        }
        Map<String, Object> members = qq.getAllMembers(groupId, true, MEMBER_WAIT_MS);
        if (members == null) {
            // 问的是账号自己而成员表一时拉不下来：身份在群资料里就有，先答身份，名字留空。
            // 调用方多半正要判断「罚不罚得动人」，这时报错只会让它当自己不是管理员。
            String own = String.valueOf(userId).equals(qq.selfUin()) ? qq.ownRole(groupId) : "";
            if (!own.isEmpty()) return selfOnly(groupId, userId, own);
            throw ApiError.failed("cannot fetch group members");
        }
        for (Object member : members.values()) {
            if (Ref.asLong(qq.ref.get(member, "uin")) == userId) return memberJson(groupId, member);
        }
        throw ApiError.notFound("member " + userId + " not found in group " + groupId);
    }

    private JSONArray groupMemberList(long groupId, boolean refresh) throws Exception {
        if (groupId == 0) throw ApiError.badRequest("missing group_id");
        Map<String, Object> members = qq.getAllMembers(groupId, refresh);
        if (members == null) throw ApiError.failed("cannot fetch group members");
        JSONArray arr = new JSONArray();
        for (Object member : members.values()) arr.put(memberJson(groupId, member));
        return arr;
    }

    private static JSONObject selfOnly(long groupId, long userId, String role) throws Exception {
        return new JSONObject()
                .put("group_id", groupId)
                .put("user_id", userId)
                .put("nickname", "")
                .put("card", "")
                .put("role", role);
    }

    private JSONObject memberJson(long groupId, Object member) throws Exception {
        long uin = Ref.asLong(qq.ref.get(member, "uin"));
        String role = roleName(qq.ref.get(member, "role"));
        // 账号自己：成员表里那一行可能是被设成管理员之前拉的，群资料里的身份才是最新的。
        if (String.valueOf(uin).equals(qq.selfUin())) {
            String own = qq.ownRole(groupId);
            if (!own.isEmpty()) role = own;
        }
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

    private static String roleName(Object roleEnum) {
        String role = Convert.mapRole(roleEnum);
        // 读不出角色就按普通成员算（成员资源总得带一个身份）
        return role.isEmpty() ? "member" : role;
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
        return guildOf(groupInfo(guildId(p)));
    }

    JSONArray guilds() throws Exception {
        JSONArray src = groupList();
        JSONArray data = new JSONArray();
        for (int i = 0; i < src.length(); i++) {
            JSONObject g = src.optJSONObject(i);
            if (g != null) data.put(guildOf(g));
        }
        return data;
    }

    /**
     * 群资源，外加人数（扩展字段 {@code member_count} / {@code max_member_count}）。人数就在内核的
     * 群列表缓存里，不多问一次；客户端连上时翻一遍 {@code guild.list} 就能记下每个群的名字与人数，
     * 欢迎词里的「第 N 位成员」不必再为每个新人问一次 {@code guild.get}。读不出（0）就不带。
     */
    private static JSONObject guildOf(JSONObject g) throws Exception {
        JSONObject guild = Codec.guild(g.optLong("group_id"), g.optString("group_name"));
        int members = g.optInt("member_count");
        int max = g.optInt("max_member_count");
        if (members > 0) guild.put("member_count", members);
        if (max > 0) guild.put("max_member_count", max);
        return guild;
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

    /**
     * 带 {@code refresh: true} 时先向服务器重拉一遍名单（只在第一页，翻页沿用这份），
     * 否则用 QQ 本地缓存——缓存里的「最后发言」可能停在上次打开成员列表的时候。
     */
    JSONArray members(JSONObject p) throws Exception {
        boolean refresh = p.optBoolean("refresh", false) && p.optString("next", "").isEmpty();
        JSONArray src = groupMemberList(guildId(p), refresh);
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
        // 扩展字段：QQ 服务器记的最后发言时刻（毫秒，0 = 进群后没说过话）与群等级。
        // 机器人离线期间的发言它也记着，清理潜水不必只靠自己收到的消息。
        out.put("last_sent_at", Math.max(0, m.optLong("last_sent_time", 0)) * 1000);
        long level = Ids.parse(m.optString("level", "0"));
        if (level > 0) out.put("level", level);
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
