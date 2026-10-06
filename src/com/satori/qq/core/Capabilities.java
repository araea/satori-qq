package com.satori.qq.core;

import com.satori.qq.net.HttpServer;
import com.satori.qq.qq.ExtraSvc;
import com.satori.qq.satori.Codec;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** {@code internal/capabilities}：这个实现端能做什么、不能做什么、曾经能做什么，一份给客户端协商用的目录。 */
final class Capabilities {
    /**
     * 0.17.0 收掉的 QQ 内核查询与本地会话状态接口。
     *
     * <p>其中 `group_remark` `group_shut_up_list` `user_detail` `mark_read` 已经在 0.29.0
     * 以扩展动作的形式回来（`ExtraSvc` 的注册表），所以从这份名单里去掉——名单只列
     * **当前**仍然没有的动作，两者不能同时成立。
     */
    private static final List<String> REMOVED_0_17 = List.of(
            "group_overview", "group_extra", "member_info", "group_member_search", "recent_contacts",
            "contact_search", "friend_relation", "profile_self", "group_honor",
            "group_active", "group_anniversary", "group_detail",
            "group_statistic", "voice_to_text", "message_context", "message_search",
            "group_file", "get_resource", "session_top", "group_msg_mask",
            "qzone.publish", "offline");

    /** 0.23.0 收掉的：这些在旧实现里靠通用 Java hook 才成立。 */
    private static final Map<String, String> REMOVED_0_23 = Map.of(
            "like", "资料卡点赞走 QQ 的 WUP/Handler 通道，本实现端只走 JNI 层");

    private static final String REMOVED_0_17_WHY = "QQ 内核查询接口不再向客户端暴露";

    private Capabilities() {}

    /** 动作是不是「移除过」，是的话返回移除时的版本；从没有过返回 null。 */
    static String removedSince(String name) {
        if (REMOVED_0_23.containsKey(name)) return "0.23.0";
        if (REMOVED_0_17.contains(name)) return "0.17.0";
        return null;
    }

    static String removedWhy(String name) {
        return REMOVED_0_23.getOrDefault(name, REMOVED_0_17_WHY);
    }

    /** `removed` 段的构造：key 是动作名，value 是「哪个版本、为什么」。 */
    private static JSONObject removed() throws Exception {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, String> e : REMOVED_0_23.entrySet()) o.put(e.getKey(), "0.23.0 起移除：" + e.getValue());
        for (String name : REMOVED_0_17) o.put(name, "0.17.0 起移除：" + REMOVED_0_17_WHY);
        return o;
    }

    private static JSONArray array(String... names) { return new JSONArray(List.of(names)); }

    static JSONObject build() throws Exception {
        JSONArray actions = array("poke", "invite", "reaction_clear", "reaction_summary",
                "card", "special_title", "title_display", "honor_display", "sign", "essence",
                "dice", "rps", "get_forward", "chat_screenshot",
                "capabilities", "compat", "status", "version", "clean_cache", "restart");
        JSONObject params = new JSONObject()
                .put("poke", "channel_id|guild_id?, user_id (private:uin may supply target)")
                .put("reaction_clear", "channel_id, message_id, emoji_id?; scope=self")
                .put("reaction_summary", "channel_id, message_id; cached counts and self flags")
                .put("invite", "guild_id, user_id")
                .put("card", "guild_id, user_id?, card")
                .put("special_title", "guild_id, user_id, title；别名 set_group_special_title")
                .put("title_display", "guild_id, show?（省略 show/enable 则只读当前开关状态，不写）")
                .put("honor_display", "guild_id, show?")
                .put("sign", "guild_id")
                .put("essence", "guild_id, message_id, remove?")
                .put("dice", "channel_id|guild_id")
                .put("rps", "channel_id|guild_id")
                .put("get_forward", "id 或 native:<父消息 ID>，可选 channel_id")
                .put("chat_screenshot", "channel_id, start_message_id, end_message_id（含端点，最多 40 条；返回 PNG internal: 资源）")
                .put("compat", "force?");
        JSONArray reads = array("get_forward", "reaction_summary", "title_display", "chat_screenshot",
                "status", "version", "capabilities", "compat");
        JSONArray writes = array("poke", "invite", "card", "reaction_clear", "special_title", "title_display",
                "honor_display", "sign", "essence", "dice", "rps", "clean_cache", "restart");
        // 0.29.0 起的扩展动作来自 ExtraSvc 的注册表，目录由它生成，不手抄第二份。
        for (ExtraSvc.Spec spec : ExtraSvc.actions()) {
            actions.put(spec.name);
            params.put(spec.name, spec.params);
            (spec.write ? writes : reads).put(spec.name);
            for (String alias : spec.alias) params.put(alias, "别名，等价于 " + spec.name);
        }
        return new JSONObject()
                // 与 satori-wx 共用的口径：adapter / version / platform / standard_methods / unsupported /
                // event_types / message_elements / limits。acumen 按它协商，不靠适配器名字猜。
                .put("adapter", Identity.ADAPTER)
                .put("version", SatoriHub.APP_VERSION)
                .put("platform", Identity.PLATFORM)
                .put("standard_methods", Identity.standardMethods())
                .put("unsupported", array("reaction.clear", "message.update", "channel.create", "channel.delete",
                        "guild.role.create", "guild.role.update", "guild.role.delete"))
                .put("event_types", array("message-created", "message-deleted",
                        "guild-added", "guild-updated", "guild-removed",
                        "channel-added", "channel-updated", "channel-removed",
                        "guild-member-added", "guild-member-updated", "guild-member-removed",
                        "reaction-added", "reaction-removed",
                        "friend-request", "guild-member-request", "guild-request",
                        "internal", "login-updated"))
                .put("message_elements", array("text", "at", "sharp", "quote", "emoji", "a", "br", "p",
                        "img", "audio", "video", "file", "message"))
                .put("limits", new JSONObject().put("upload_bytes", HttpServer.MAX_UPLOAD_BYTES))
                .put("actions", actions)
                .put("special_faces", new JSONObject()
                        .put("dice", Codec.DICE_FACE).put("rps", Codec.RPS_FACE)
                        .put("face_type", GroupOps.SPECIAL_FACE_TYPE))
                .put("params", params)
                .put("read_actions", reads)
                .put("write_actions", writes)
                // 曾经有过、现在没有的动作。客户端按这个把能力标成不可用，不必逐轮试。
                .put("removed", removed());
    }
}
