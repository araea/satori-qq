package com.satori.qq.core;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Notice JSON builders. No QQ kernel types — Convert/Hub fill uin from uid. */
public final class Notices {
    /** 灰条里读不出禁言时长。0 是「解除」，所以未知另用负数，不能混。 */
    public static final long UNKNOWN_DURATION = -1;

    /** 最近一条读不出时长的禁言灰条（已去掉账号与昵称），进 healthz 的 inbound 诊断。 */
    private static volatile String unreadBanTip = "";

    /** 最近一条禁言灰条（同样去掉账号与昵称）：核对 QQ 实际的句式、两个人的先后用。 */
    private static volatile String lastBanTip = "";

    private Notices() {}

    public static String unreadBanTip() { return unreadBanTip; }

    public static String lastBanTip() { return lastBanTip; }

    public static JSONObject recall(long self, long time, boolean group, long peer,
                                    long userId, long operatorId, int messageId) throws Exception {
        return recall(self, time, group, peer, userId, operatorId, messageId, 0);
    }

    public static JSONObject recall(long self, long time, boolean group, long peer,
                                    long userId, long operatorId, int storeId, long qqMsgId)
            throws Exception {
        JSONObject n = base(self, time);
        n.put("notice_type", group ? "group_recall" : "friend_recall");
        n.put("user_id", userId);
        n.put("operator_id", operatorId == 0 ? userId : operatorId);
        if (qqMsgId != 0) {
            n.put("message_id", String.valueOf(qqMsgId));
            n.put("qq_msg_id", qqMsgId);
            if (storeId != 0) n.put("store_id", storeId);
        } else {
            n.put("message_id", storeId);
        }
        if (group) n.put("group_id", peer);
        return n;
    }

    public static JSONObject pokeFromJson(String json, long self, long time,
                                          boolean group, long peer) throws Exception {
        if (json == null || json.isEmpty()) return null;
        JSONObject root = new JSONObject(json);
        JSONArray items = root.optJSONArray("items");
        if (items == null) return null;
        ArrayList<String> uids = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String uid = it.optString("uid", "");
            if (!uid.isEmpty()) uids.add(uid);
        }
        if (uids.size() < 2) return null;
        JSONObject n = base(self, time);
        n.put("notice_type", "notify");
        n.put("sub_type", "poke");
        n.put("sender_uid", uids.get(0));
        n.put("target_uid", uids.get(1));
        n.put("user_id", numericId(uids.get(0)));
        n.put("target_id", numericId(uids.get(1)));
        if (group) n.put("group_id", peer);
        else if (n.optLong("user_id") == 0) n.put("user_id", peer);
        return n;
    }

    /** Android QQNT 9.3.50 poke gray tip is XML (subType 12), not JSON busiId 1061. */
    public static JSONObject pokeFromXml(String xml, long self, long time,
                                         boolean group, long peer) throws Exception {
        if (xml == null || xml.isEmpty()) return null;
        String lower = xml.toLowerCase();
        if (!lower.contains("nudgeaction") && !lower.contains("nudge")) return null;
        ArrayList<String> uids = xmlUids(xml);
        if (uids.size() < 2) return null;
        JSONObject n = base(self, time);
        n.put("notice_type", "notify");
        n.put("sub_type", "poke");
        n.put("sender_uid", uids.get(0));
        n.put("target_uid", uids.get(1));
        n.put("user_id", numericId(uids.get(0)));
        n.put("target_id", numericId(uids.get(1)));
        if (group) n.put("group_id", peer);
        else if (n.optLong("user_id") == 0) n.put("user_id", peer);
        return n;
    }

    /** Android QQNT ban gray tip is also XML subType 12 (「将…禁言N秒」). */
    public static JSONObject banFromXml(String xml, long self, long time, long groupId) throws Exception {
        if (xml == null || xml.isEmpty()) return null;
        String lower = xml.toLowerCase();
        if (lower.contains("nudgeaction") || lower.contains("nudge")) return null;
        boolean lift = xml.contains("解除禁言") || xml.contains("取消禁言");
        if (!lift && !xml.contains("禁言")) return null;
        long duration = lift ? 0 : parseBanDuration(xml);
        lastBanTip = redact(xml);
        if (duration == UNKNOWN_DURATION) unreadBanTip = lastBanTip;
        JSONObject n = groupBan(self, time, groupId, 0, 0, duration);
        String[] people = banPeople(xml);
        if (!people[0].isEmpty()) n.put("member_uid", people[0]);
        if (!people[1].isEmpty()) n.put("admin_uid", people[1]);
        return n;
    }

    /**
     * 禁言灰条里的两个人：{被禁言的成员, 动手的管理员}，没有的是空串。
     * QQ 写作「成员 被管理员 管理员 禁言 10分钟」——**被禁言的人在前**（真机 2026-10-12 实测，
     * 此前按「管理员 将 成员 禁言」反着认，榜上列的全是管理员）。以防别的版本换了句式，
     * 两个人之间的文字里有「被」就是成员在前，有「将」「把」或「了」（解除了…的禁言）就是管理员在前，都没有按成员在前。
     */
    public static String[] banPeople(String xml) {
        Matcher m = Pattern.compile("uin=\"([^\"]*)\"|txt=\"([^\"]*)\"").matcher(xml);
        ArrayList<String> uids = new ArrayList<>();
        StringBuilder between = new StringBuilder();
        while (m.find()) {
            String uid = m.group(1);
            if (uid != null) {
                if (!uid.trim().isEmpty()) uids.add(uid.trim());
            } else if (uids.size() == 1) {
                between.append(m.group(2));
            }
        }
        if (uids.isEmpty()) return new String[]{"", ""};
        if (uids.size() == 1) return new String[]{uids.get(0), ""};
        String gap = between.toString();
        boolean adminFirst = !gap.contains("被")
                && (gap.contains("将") || gap.contains("把") || gap.contains("了"));
        return adminFirst
                ? new String[]{uids.get(1), uids.get(0)}
                : new String[]{uids.get(0), uids.get(1)};
    }

    static ArrayList<String> xmlUids(String xml) {
        ArrayList<String> uids = new ArrayList<>();
        int from = 0;
        while (true) {
            int i = xml.indexOf("uin=\"", from);
            if (i < 0) break;
            int start = i + 5;
            int end = xml.indexOf('"', start);
            if (end < 0) break;
            String uid = xml.substring(start, end).trim();
            if (!uid.isEmpty()) uids.add(uid);
            from = end + 1;
        }
        return uids;
    }

    private static final Pattern TXT = Pattern.compile("txt=\"([^\"]*)\"");
    private static final Pattern DURATION_PART =
            Pattern.compile("(\\d+)\\s*(秒钟|秒|分钟|分|小时|时|天)");

    /**
     * 灰条里的禁言时长，秒；读不出来是 {@link #UNKNOWN_DURATION}，不再编一个 1 秒。
     * 先把所有 {@code txt="…"} 按序接成一句话（数字和单位被拆在相邻节点里也读得到），
     * 只看「禁言」之后的部分，免得把昵称里的「3天」当时长；「1天2小时」这样的复合写法累加。
     */
    public static long parseBanDuration(String xml) {
        Matcher parts = TXT.matcher(xml);
        StringBuilder sentence = new StringBuilder();
        while (parts.find()) sentence.append(parts.group(1));
        String text = sentence.toString();
        int at = text.indexOf("禁言");
        Matcher m = DURATION_PART.matcher(at >= 0 ? text.substring(at) : text);
        long total = 0;
        boolean found = false;
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            String unit = m.group(2);
            if (unit.startsWith("分")) n *= 60;
            else if (unit.startsWith("小时") || unit.equals("时")) n *= 3600;
            else if (unit.equals("天")) n *= 86400;
            total += n;
            found = true;
        }
        return found ? total : UNKNOWN_DURATION;
    }

    /** 诊断用的灰条原文：去掉账号与昵称，只留结构与文字。 */
    static String redact(String xml) {
        String s = xml.replaceAll("(uin|nm)=\"[^\"]*\"", "$1=\"*\"");
        return s.length() > 400 ? s.substring(0, 400) : s;
    }

    /** Join/leave/kick gray tips on Android QQNT are XML subType 12. */
    public static JSONObject memberChangeFromXml(String xml, long self, long time, long groupId)
            throws Exception {
        if (xml == null || xml.isEmpty()) return null;
        String lower = xml.toLowerCase();
        if (lower.contains("nudge") || xml.contains("禁言")) return null;
        boolean kick = xml.contains("移出") || xml.contains("踢出") || xml.contains("移除");
        boolean leave = xml.contains("退出了") || xml.contains("退出群");
        boolean join = xml.contains("加入") || xml.contains("入群");
        if (!kick && !leave && !join) return null;
        ArrayList<String> uids = xmlUids(xml);
        JSONObject n;
        if (join) {
            n = groupIncrease(self, time, groupId, 0, 0, xml.contains("邀请") ? "invite" : "approve");
        } else {
            n = groupDecrease(self, time, groupId, 0, 0, kick || !leave);
        }
        if (uids.size() >= 2) {
            n.put("admin_uid", uids.get(0));
            n.put("member_uid", uids.get(1));
        } else if (uids.size() == 1) {
            n.put("member_uid", uids.get(0));
        }
        return n;
    }

    public static JSONObject groupIncrease(long self, long time, long groupId, long userId, long operatorId)
            throws Exception {
        return groupIncrease(self, time, groupId, userId, operatorId, "approve");
    }

    public static JSONObject groupIncrease(long self, long time, long groupId, long userId, long operatorId,
                                           String subType) throws Exception {
        JSONObject n = base(self, time);
        n.put("notice_type", "group_increase");
        n.put("sub_type", subType == null || subType.isEmpty() ? "approve" : subType);
        n.put("group_id", groupId);
        n.put("user_id", userId);
        n.put("operator_id", operatorId == 0 ? userId : operatorId);
        return n;
    }

    public static JSONObject groupDecrease(long self, long time, long groupId, long userId, long operatorId,
                                           boolean kick) throws Exception {
        JSONObject n = base(self, time);
        n.put("notice_type", "group_decrease");
        n.put("sub_type", kick ? "kick" : "leave");
        n.put("group_id", groupId);
        n.put("user_id", userId);
        n.put("operator_id", operatorId == 0 ? userId : operatorId);
        return n;
    }

    public static JSONObject groupBan(long self, long time, long groupId, long userId, long operatorId,
                                      long duration) throws Exception {
        JSONObject n = base(self, time);
        n.put("notice_type", "group_ban");
        n.put("sub_type", duration == 0 ? "lift_ban" : "ban");
        n.put("group_id", groupId);
        n.put("user_id", userId);
        n.put("operator_id", operatorId == 0 ? userId : operatorId);
        n.put("duration", duration);
        return n;
    }

    public static JSONObject friendRequest(long self, long time, long userId, String comment, String flag)
            throws Exception {
        JSONObject n = new JSONObject();
        n.put("time", time);
        n.put("self_id", self);
        n.put("post_type", "request");
        n.put("request_type", "friend");
        n.put("user_id", userId);
        n.put("comment", comment == null ? "" : comment);
        n.put("flag", flag == null ? "" : flag);
        return n;
    }

    public static JSONObject groupRequest(long self, long time, long groupId, long userId,
                                          String subType, String comment, String flag) throws Exception {
        JSONObject n = new JSONObject();
        n.put("time", time);
        n.put("self_id", self);
        n.put("post_type", "request");
        n.put("request_type", "group");
        n.put("sub_type", subType == null || subType.isEmpty() ? "add" : subType);
        n.put("group_id", groupId);
        n.put("user_id", userId);
        n.put("comment", comment == null ? "" : comment);
        n.put("flag", flag == null ? "" : flag);
        return n;
    }

    static long numericId(String raw) {
        if (raw == null || raw.isEmpty()) return 0;
        try { return Long.parseLong(raw.trim()); } catch (Exception e) { return 0; }
    }

    static JSONObject base(long self, long time) throws Exception {
        JSONObject n = new JSONObject();
        n.put("time", time);
        n.put("self_id", self);
        n.put("post_type", "notice");
        return n;
    }
}
