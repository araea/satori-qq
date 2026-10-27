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
            "group_statistic", "voice_to_text",
            "get_resource", "session_top", "group_msg_mask", "offline");

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

    /**
     * 会落到别人头上的动作：踢、禁言、删别人的文件、发公告、开全员通知之类。客户端据此要
     * 额外的授权，不许拿「普通写动作」的额度把这类事做掉。
     */
    private static final List<String> MANAGE = List.of(
            "essence", "special_title", "invite", "group_file_delete", "group_file_rename",
            "group_file_move", "group_file_folder_delete", "group_file_folder_rename",
            "group_bulletin_publish", "group_bulletin_delete", "group_quit",
            "group_join_noverify", "online_file_refuse", "group_file_trans");

    /**
     * 标准方法的参数说明。只写常用的那几个：客户端真正需要读的是下面
     * {@code internal} 动作的说明，标准方法照 Satori 规范走。
     */
    private static final Map<String, String> STANDARD_PARAMS = Map.ofEntries(
            Map.entry("message.create", "channel_id, content"),
            Map.entry("message.get", "channel_id, message_id"),
            Map.entry("message.list", "channel_id, next?, limit?, order?"),
            Map.entry("message.delete", "channel_id, message_id"),
            Map.entry("channel.get", "channel_id"),
            Map.entry("channel.list", "guild_id, next?"),
            Map.entry("channel.mute", "channel_id, duration（毫秒；0 解除）"),
            Map.entry("channel.update", "channel_id, name?"),
            Map.entry("guild.get", "guild_id"),
            Map.entry("guild.list", "next?"),
            Map.entry("guild.member.get", "guild_id, user_id"),
            Map.entry("guild.member.list", "guild_id, next?"),
            Map.entry("guild.member.kick", "guild_id, user_id, permanent?"),
            Map.entry("guild.member.mute", "guild_id, user_id, duration（毫秒）"),
            Map.entry("guild.member.role.set", "guild_id, user_id, role_id"),
            Map.entry("guild.member.role.unset", "guild_id, user_id, role_id"),
            Map.entry("guild.member.role.list", "guild_id, user_id"),
            Map.entry("guild.role.list", "guild_id, next?"),
            Map.entry("reaction.create", "channel_id, message_id, emoji_id"),
            Map.entry("reaction.delete", "channel_id, message_id, emoji_id, user_id?"),
            Map.entry("reaction.list", "channel_id, message_id, emoji_id?, next?"),
            Map.entry("user.get", "user_id"),
            Map.entry("friend.list", "next?"),
            Map.entry("friend.delete", "user_id"),
            Map.entry("friend.approve", "message_id, approve"),
            Map.entry("guild.approve", "message_id, approve, comment?"),
            Map.entry("guild.member.approve", "message_id, approve, comment?"),
            Map.entry("upload.create", "type, filename?（multipart 上传）"),
            Map.entry("user.channel.create", "user_id, guild_id?"),
            Map.entry("login.get", "无参数"));

    /**
     * 机器可读的能力目录：一条一行，名字、读写/管理分类、参数说明、别名与「会不会改动
     * QQ 那边」都在里面。
     *
     * <p>标准方法与内部动作混在一张表里，是因为调用方要回答同一个问题：「这棵树上有哪些
     * 果子能摘、摘哪个要打招呼」。从前这些只散在文档与 {@code params} 字符串里，智能体
     * 只能靠人一条条写进工具 Schema——QQ 端多接一个动作，客户端就得等一次发版。有了这份
     * 目录，客户端把名字与参数说明原样交给模型就能用（acumen 1.37 起就是这么做的）。
     */
    private static JSONArray catalog() throws Exception {
        JSONArray out = new JSONArray();
        for (String method : Identity.STANDARD_METHODS) {
            boolean mutating = OutboundGuard.isMutation(method);
            out.put(catalogEntry(method, mutating ? (MANAGE.contains(method) ? "manage" : "write") : "read",
                    STANDARD_PARAMS.getOrDefault(method, ""), mutating, new JSONArray(), "标准 Satori 方法"));
        }
        // 核心内部动作：与本类 actions 那份名单同一来源，避免两处口径打架。
        for (String name : CORE_ACTIONS) {
            String kind = CORE_READ.contains(name) ? "read" : (MANAGE.contains(name) ? "manage" : "write");
            out.put(catalogEntry(name, kind, paramsHint(name), !"read".equals(kind),
                    new JSONArray(), noteOf(name)));
        }
        for (ExtraSvc.Spec spec : ExtraSvc.actions()) {
            String kind = !spec.write ? "read" : (MANAGE.contains(spec.name) ? "manage" : "write");
            out.put(catalogEntry(spec.name, kind, spec.params, spec.write,
                    new JSONArray(List.of(spec.alias)), "QQ 内核扩展动作"));
        }
        return out;
    }

    private static String paramsHint(String name) {
        return switch (name) {
            case "poke" -> "channel_id|guild_id?, user_id (private:uin may supply target)";
            case "invite" -> "guild_id, user_id";
            case "reaction_clear" -> "channel_id, message_id, emoji_id?; scope=self";
            case "reaction_summary" -> "channel_id, message_id; cached counts and self flags";
            case "card" -> "guild_id, user_id?, card";
            case "special_title" -> "guild_id, user_id, title";
            case "title_display" -> "guild_id, show?";
            case "title_wear" -> "guild_id, title_id?|role?（owner/admin/level/off）|name?，title?（≤6字，群主专属），strategy?（auto/identity/special），verify?，settle_ms?";
            case "honor_display" -> "guild_id, show?";
            case "sign" -> "guild_id";
            case "essence" -> "guild_id, message_id, remove?";
            case "dice", "rps" -> "channel_id|guild_id";
            case "get_forward" -> "id 或 native:<父消息 ID>，可选 channel_id";
            case "chat_screenshot" -> "channel_id, start_message_id, end_message_id（含端点，最多 40 条）";
            case "message_search" -> "keyword；可选 channel_id|guild_id、user_id、before/after（unix 秒）、limit（≤50）；搜本进程见过的最近四千条，按时间倒序";
            case "message_context" -> "message_id；可选 before/after（各 ≤40）；取同频道前后文";
            case "message_forward" -> "message_id 或 message_ids[]（同一来源）, target 或 targets[]（群号 / private:QQ号，≤10）, channel_id?（来源，消息不在缓存时用）";
            case "voice_to_text" -> "message_id, channel_id?, wait?（秒，默认 12）；返回 {text}";
            case "request_list" -> "refresh?（默认 true）；返回 {data:[未处理的 friend-request / guild-member-request / guild-request 事件]}";
            default -> "";
        };
    }

    private static String noteOf(String name) {
        return switch (name) {
            case "reaction_clear" -> "撤掉自己在某条消息上的表态";
            case "card" -> "改群名片；带 user_id 时是管理动作";
            case "special_title" -> "设置群专属头衔（需群主或管理员）";
            case "essence" -> "加/撤群精华（需群主或管理员）";
            case "get_forward" -> "展开合并转发";
            case "chat_screenshot" -> "把一段群聊截成一张图";
            case "message_search" -> "在本进程见过的消息里按关键词检索";
            case "message_context" -> "取某条消息同频道的前后几条";
            case "message_forward" -> "把已有消息原样转发到别的群或私聊（媒体不重传）";
            case "voice_to_text" -> "QQ 自带的语音转文字";
            case "request_list" -> "还没处理的好友申请、入群申请与群邀请";
            case "capabilities" -> "这份目录本身";
            case "compat", "selftest" -> "内核兼容性自检";
            case "restart" -> "重启 QQ 进程";
            case "clean_cache" -> "清理模块的临时文件";
            case "status", "version" -> "模块运行状况与版本";
            default -> "QQ 专有扩展动作";
        };
    }

    private static JSONObject catalogEntry(String name, String kind, String params, boolean mutating,
            JSONArray aliases, String note) throws Exception {
        return new JSONObject()
                .put("name", name)
                .put("kind", kind)
                .put("params", params)
                .put("mutating", mutating)
                .put("aliases", aliases)
                .put("note", note);
    }

    /** 这份实现端认不认这个名字（标准方法、核心内部动作或内核扩展）。 */
    static boolean declares(String name) {
        return Identity.serves(name) || CORE_ACTIONS.contains(name) || ExtraSvc.find(name) != null;
    }

    /** 只读的内部动作（与 {@link #CORE_ACTIONS} 同一份名单的子集）。 */
    private static final List<String> CORE_READ = List.of(
            "get_forward", "reaction_summary", "title_display", "chat_screenshot",
            "message_search", "message_context", "voice_to_text", "request_list",
            "status", "version", "capabilities", "compat");

    /** 核心内部动作的完整名单；目录、`actions` 与 {@link #declares} 共用。 */
    private static final List<String> CORE_ACTIONS = List.of(
            "poke", "invite", "reaction_clear", "reaction_summary",
            "card", "special_title", "title_display", "honor_display", "sign", "essence",
            "dice", "rps", "get_forward", "chat_screenshot", "message_search", "message_context",
            "message_forward", "voice_to_text", "request_list",
            "capabilities", "compat", "status", "version", "clean_cache", "restart");

    static JSONObject build() throws Exception {
        JSONArray actions = new JSONArray(CORE_ACTIONS);
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
                .put("message_search", "keyword；可选 channel_id|guild_id、user_id、before/after（unix 秒）、limit（≤50）；搜本进程见过的最近四千条，按时间倒序")
                .put("message_context", "message_id；可选 before/after（各 ≤40）；取同频道前后文")
                .put("message_forward", "message_id 或 message_ids[]（同一来源）, target 或 targets[]（群号 / private:QQ号，≤10）, channel_id?（来源，消息不在缓存时用）")
                .put("voice_to_text", "message_id, channel_id?, wait?（秒，默认 12）；返回 {text}")
                .put("request_list", "refresh?（默认 true）；返回 {data:[未处理的 friend-request / guild-member-request / guild-request 事件]}")
                .put("compat", "force?");
        JSONArray reads = new JSONArray(CORE_READ);
        JSONArray writes = new JSONArray(CORE_ACTIONS.stream().filter(n -> !CORE_READ.contains(n)).toList());
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
                // 机器可读的目录：一条能力一行。客户端照它把所有动作摆给模型，
                // 不必为每一条手写工具 Schema（见 docs/SATORI_SUPPORT.md）。
                .put("catalog", catalog())
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
