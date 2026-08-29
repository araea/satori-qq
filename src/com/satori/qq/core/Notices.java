package com.satori.qq.core;

import org.json.JSONArray;
import org.json.JSONObject;

/** Notice JSON builders. No QQ kernel types — Convert/Hub fill uin from uid. */
public final class Notices {
    private Notices() {}

    public static JSONObject recall(long self, long time, boolean group, long peer,
                                    long userId, long operatorId, int messageId) throws Exception {
        JSONObject n = base(self, time);
        n.put("notice_type", group ? "group_recall" : "friend_recall");
        n.put("user_id", userId);
        n.put("operator_id", operatorId == 0 ? userId : operatorId);
        n.put("message_id", messageId);
        if (group) n.put("group_id", peer);
        return n;
    }

    public static JSONObject pokeFromJson(String json, long self, long time,
                                          boolean group, long peer) throws Exception {
        if (json == null || json.isEmpty()) return null;
        JSONObject root = new JSONObject(json);
        JSONArray items = root.optJSONArray("items");
        if (items == null) return null;
        java.util.ArrayList<String> uids = new java.util.ArrayList<>();
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
        n.put("user_id", 0);
        n.put("target_id", 0);
        n.put("sender_uid", uids.get(0));
        n.put("target_uid", uids.get(1));
        if (group) n.put("group_id", peer);
        else n.put("user_id", peer);
        return n;
    }

    /** Android QQNT 9.3.50 poke gray tip is XML (subType 12), not JSON busiId 1061. */
    public static JSONObject pokeFromXml(String xml, long self, long time,
                                         boolean group, long peer) throws Exception {
        if (xml == null || xml.isEmpty()) return null;
        String lower = xml.toLowerCase();
        if (!lower.contains("nudgeaction") && !lower.contains("nudge")) return null;
        java.util.ArrayList<String> uids = xmlUids(xml);
        if (uids.size() < 2) return null;
        JSONObject n = base(self, time);
        n.put("notice_type", "notify");
        n.put("sub_type", "poke");
        n.put("user_id", 0);
        n.put("target_id", 0);
        n.put("sender_uid", uids.get(0));
        n.put("target_uid", uids.get(1));
        if (group) n.put("group_id", peer);
        else n.put("user_id", peer);
        return n;
    }

    /** Android QQNT ban gray tip is also XML subType 12 (「将…禁言N秒」). */
    public static JSONObject banFromXml(String xml, long self, long time, long groupId) throws Exception {
        if (xml == null || xml.isEmpty()) return null;
        String lower = xml.toLowerCase();
        if (lower.contains("nudgeaction") || lower.contains("nudge")) return null;
        boolean lift = xml.contains("解除禁言") || xml.contains("取消禁言");
        if (!lift && !xml.contains("禁言")) return null;
        java.util.ArrayList<String> uids = xmlUids(xml);
        long duration = lift ? 0 : parseBanDuration(xml);
        JSONObject n = groupBan(self, time, groupId, 0, 0, duration);
        if (uids.size() >= 2) {
            n.put("admin_uid", uids.get(0));
            n.put("member_uid", uids.get(1));
        } else if (uids.size() == 1) {
            n.put("member_uid", uids.get(0));
        }
        return n;
    }

    static java.util.ArrayList<String> xmlUids(String xml) {
        java.util.ArrayList<String> uids = new java.util.ArrayList<>();
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

    static long parseBanDuration(String xml) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)\\s*(秒|分钟|小时|天)").matcher(xml);
        if (!m.find()) return 1;
        long n = Long.parseLong(m.group(1));
        String unit = m.group(2);
        if ("分钟".equals(unit)) return n * 60;
        if ("小时".equals(unit)) return n * 3600;
        if ("天".equals(unit)) return n * 86400;
        return n;
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
        java.util.ArrayList<String> uids = xmlUids(xml);
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

    static JSONObject base(long self, long time) throws Exception {
        JSONObject n = new JSONObject();
        n.put("time", time);
        n.put("self_id", self);
        n.put("post_type", "notice");
        return n;
    }
}
