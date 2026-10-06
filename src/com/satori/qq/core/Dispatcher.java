package com.satori.qq.core;

import com.satori.qq.qq.Compat;
import com.satori.qq.qq.ExtraSvc;
import com.satori.qq.qq.Media;
import com.satori.qq.qq.QQClient;
import com.satori.qq.satori.Codec;
import org.json.JSONObject;

/**
 * 方法名 → 实现。标准 Satori 方法走 {@link #call}，{@code /v1/internal/…} 走 {@link #internal}。
 * 写动作都在 {@link OutboundGate} 里执行，读动作直接跑。
 */
final class Dispatcher {
    /** Page size used when a caller passes `next` without `limit`. */
    private static final int PAGE_DEFAULT = 200;

    /** 没有返回值的写动作。 */
    private interface Action {
        void run() throws Exception;
    }

    private final QQClient qq;
    private final Identity identity;
    private final OutboundGate gate;
    private final Directory directory;
    private final Messages messages;
    private final History history;
    private final Reactions reactions;
    private final GroupOps groupOps;
    private final Requests requests;
    private final Forwards forwards;
    private final Monitor monitor;

    Dispatcher(QQClient qq, Identity identity, OutboundGate gate, Directory directory, Messages messages,
               History history, Reactions reactions, GroupOps groupOps, Requests requests, Forwards forwards,
               Monitor monitor) {
        this.qq = qq;
        this.identity = identity;
        this.gate = gate;
        this.directory = directory;
        this.messages = messages;
        this.history = history;
        this.reactions = reactions;
        this.groupOps = groupOps;
        this.requests = requests;
        this.forwards = forwards;
        this.monitor = monitor;
    }

    private static JSONObject done() { return new JSONObject(); }

    private Object write(String action, Action work) throws Exception {
        return gate.guarded(action, () -> {
            work.run();
            return done();
        });
    }

    // ------------------------------------------------------------------ 标准方法

    Object call(String method, JSONObject params) throws Exception {
        JSONObject p = params == null ? new JSONObject() : params;
        if ("login.get".equals(method)) return identity.loginFull();
        if ("reaction.clear".equals(method)) {
            throw ApiError.unsupported("reaction.clear (use internal/reaction_clear for own reactions)");
        }
        // 没有的方法与内核在不在线无关：先判 404，再判 503，客户端才分得清「没这个」和「现在不行」。
        if (!Identity.serves(method)) throw ApiError.unsupported(method);
        if (!qq.isOnline()) throw ApiError.kernelOffline();
        return switch (method) {
            case "message.create" -> messages.create(p);
            case "message.get" -> history.message(p);
            case "message.list" -> history.list(p);
            case "message.delete" -> {
                messages.delete(p);
                yield done();
            }
            case "channel.get" -> directory.channel(p);
            case "channel.list" -> Json.page(directory.channels(p), p, PAGE_DEFAULT);
            case "channel.update" -> {
                groupOps.updateChannel(p);
                yield done();
            }
            case "channel.mute" -> {
                groupOps.muteChannel(p);
                yield done();
            }
            case "user.channel.create" -> {
                long uid = Ids.parse(p.optString("user_id", ""));
                if (uid == 0) throw ApiError.badRequest("missing user_id");
                yield Codec.channel(QQClient.CT_C2C, uid, "");
            }
            case "guild.get" -> directory.guild(p);
            case "guild.list" -> Json.page(directory.guilds(), p, PAGE_DEFAULT);
            case "guild.member.get" -> directory.member(p);
            case "guild.member.list" -> Json.page(directory.members(p), p, PAGE_DEFAULT);
            case "guild.member.kick" -> {
                groupOps.kick(p);
                yield done();
            }
            case "guild.member.mute" -> {
                groupOps.mute(p);
                yield done();
            }
            case "guild.member.role.set", "guild.member.role.unset" -> {
                groupOps.setRole(p, method.endsWith(".set"), method);
                yield done();
            }
            case "guild.member.role.list" -> Json.page(directory.memberRoles(p), p, PAGE_DEFAULT);
            case "guild.role.list" -> Json.page(Directory.roles(Directory.guildId(p)), p, PAGE_DEFAULT);
            case "reaction.create" -> {
                reactions.add(p);
                yield done();
            }
            case "reaction.delete" -> {
                reactions.remove(p);
                yield done();
            }
            case "reaction.list" -> reactions.list(p);
            case "user.get" -> directory.user(p);
            case "friend.list" -> Json.page(directory.friends(), p, PAGE_DEFAULT);
            case "friend.delete" -> {
                groupOps.deleteFriend(p);
                yield done();
            }
            case "friend.approve" -> {
                requests.approveFriend(p.optString("message_id", ""), p.optBoolean("approve", true),
                        p.optString("comment", ""));
                yield done();
            }
            case "guild.approve", "guild.member.approve" -> {
                requests.approveGroup(p.optString("message_id", ""), p.optBoolean("approve", true),
                        p.optString("comment", ""));
                yield done();
            }
            default -> throw ApiError.unsupported(method);
        };
    }

    // ------------------------------------------------------------------ internal

    /**
     * 只留这一份白名单。QQ 的批量查询与本地会话状态那批内核接口在 0.17.0 整批撤掉：
     * 它们要按号去问 QQ 要资料，跟真人客户端的行为对不上，是这批接口里最显眼的一类。
     * 保留的是「会真的产生一次出站动作」的那几个（戳、赞、头衔、名片、打卡、精华、
     * 邀请、骰子、猜拳），加上合并转发元素唯一的那条解析路径（get_forward），
     * 以及模块自己的运维口。其余一律 404。
     */
    Object internal(String rawName, JSONObject params) throws Exception {
        JSONObject p = params == null ? new JSONObject() : params;
        String name = normalize(rawName);
        return switch (name) {
            case "reaction_summary" -> reactions.summary(p);
            case "reaction_clear" -> gate.guarded("internal.reaction_clear", () -> reactions.clearOwn(p));
            case "poke" -> write("internal.poke", () -> groupOps.poke(p));
            case "special_title", "set_group_special_title" -> write("internal.special_title", () -> groupOps.specialTitle(p));
            case "title_display" -> gate.guarded("internal.title_display", () -> groupOps.titleDisplay(p));
            case "honor_display" -> gate.guarded("internal.honor_display", () -> groupOps.honorDisplay(p));
            case "card", "set_card" -> write("internal.card", () -> groupOps.card(p));
            case "sign", "clock_in", "group_sign" -> gate.guarded("internal.sign", () -> groupOps.sign(p));
            case "essence", "set_essence" -> write("internal.essence", () -> groupOps.essence(p));
            case "invite" -> write("internal.invite", () -> groupOps.invite(p));
            case "dice" -> gate.guarded("internal.dice", () -> groupOps.specialFace(p, Codec.DICE_FACE, "dice"));
            case "rps", "rock_paper_scissors" ->
                    gate.guarded("internal.rps", () -> groupOps.specialFace(p, Codec.RPS_FACE, "rps"));
            case "chat_screenshot" -> history.screenshot(p);
            // 合并转发在入站只有 `<message forward id="…"/>` 一个元素，没有正文，
            // 也没有第二条能取回它的路，所以这条解析保留。
            case "get_forward" -> forwards.get(p);
            case "capabilities", "help" -> Capabilities.build();
            case "compat", "selftest" -> new JSONObject()
                    .put("static", monitor.compat(p.optBoolean("force", false)))
                    .put("observed", Compat.observed());
            case "restart" -> {
                monitor.restart(Math.max(500, p.optInt("delay", 0)));
                yield done();
            }
            case "clean_cache" -> new JSONObject().put("deleted", Media.cleanTemp());
            case "status" -> monitor.status();
            case "version" -> monitor.version();
            default -> extension(name, p);
        };
    }

    /**
     * 扩展动作来自 {@link ExtraSvc} 的注册表（0.29.0 起）。写动作走 {@code guarded}，
     * 与其余出站动作共用限频与熔断；读动作直接执行。找不到才是「移除过」或「从来没有」。
     */
    private Object extension(String name, JSONObject p) throws Exception {
        ExtraSvc.Spec spec = ExtraSvc.find(name);
        if (spec != null) {
            return spec.write ? gate.guarded("internal." + spec.name, () -> run(spec, p)) : run(spec, p);
        }
        String since = Capabilities.removedSince(name);
        if (since != null) throw ApiError.removed("internal/" + name, since, Capabilities.removedWhy(name));
        throw ApiError.unsupported("internal/" + name);
    }

    /**
     * 内核回调的读把 code/msg 与 payload 一起回给客户端；同步读直接回自己的 JSON。
     * 写动作只在成功时返回，失败一律抛 {@link ApiError}，交给调用方看文案。
     */
    private Object run(ExtraSvc.Spec spec, JSONObject p) throws Exception {
        Object r = spec.fn.run(qq.extra(), p);
        if (r instanceof ExtraSvc.Result kr) {
            JSONObject o = new JSONObject().put("ok", kr.ok()).put("code", kr.code).put("msg", kr.msg);
            if (kr.timedOut) o.put("timeout", true);
            if (kr.payload != null) o.put("data", Json.reflect(kr.payload));
            if (spec.write && !kr.ok()) throw ApiError.failed(spec.name + ": " + kr.describe());
            return o;
        }
        if (r instanceof JSONObject o) {
            if (spec.write && !o.optBoolean("ok", true)) {
                throw ApiError.failed(spec.name + ": " + o.optString("msg", "failed"));
            }
            return o;
        }
        return new JSONObject().put("ok", true).put("result", Json.reflect(r));
    }

    /**
     * 接受两种写法：直接的 {@code internal/name}，以及官方适配器那种带登录的代理路径
     * {@code red/<uin>/_api/name}；JavaScript 习惯 camelCase，直连的 HTTP 调用多用 snake_case，
     * 统一成 snake_case。
     */
    private String normalize(String raw) {
        String name = raw == null ? "" : raw;
        String platformPrefix = Identity.PLATFORM + "/";
        if (name.startsWith(platformPrefix)) {
            String expected = platformPrefix + identity.selfUin() + "/";
            if (!name.startsWith(expected)) throw ApiError.notFound("internal login not found");
            name = name.substring(expected.length());
        }
        if (name.startsWith("_api/")) name = name.substring(5);
        if (name.startsWith("/")) name = name.substring(1);
        return snakeCase(name.trim());
    }

    static String snakeCase(String name) {
        StringBuilder out = new StringBuilder(name.length() + 8);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '-') c = '_';
            if (Character.isUpperCase(c) && out.length() > 0) {
                char last = out.charAt(out.length() - 1);
                if (last != '_' && last != '.') out.append('_');
            }
            out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }
}
