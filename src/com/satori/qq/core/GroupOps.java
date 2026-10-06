package com.satori.qq.core;

import com.satori.qq.L;
import com.satori.qq.packet.PacketSvc;
import com.satori.qq.packet.Pb;
import com.satori.qq.qq.QQClient;
import com.satori.qq.satori.Codec;
import com.satori.qq.satori.Protocol;
import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** 群管类的写动作：踢人、禁言、设管理、改群、头衔、签到、精华、戳一戳、骰子猜拳。 */
final class GroupOps {
    /**
     * All-member mute has no duration in QQ's kernel call, only a boolean, so the module owns the
     * timer. An `enable: true` request without an explicit duration therefore means "until turned
     * off"; 30 days is QQ's own ceiling for a timed mute and doubles as "no deadline" here.
     */
    private static final long DEFAULT_CHANNEL_MUTE_MS = 30L * 24 * 3600 * 1000;

    /**
     * 超级表情的 `FaceElement.faceType`：3 = 动画贴纸（NapCat 的 `FaceType.AniSticke`）。
     * 骰子与猜拳只有用这个值、并带上贴纸身份，QQ 才会画成整条放大的动画。
     */
    static final int SPECIAL_FACE_TYPE = 3;

    private final QQClient qq;
    private final Identity identity;
    private final Directory directory;
    private final Lookup lookup;
    private final Resources resources;
    private final Sender sender;
    private final Events events;
    private final OutboundGate gate;
    /** 整群禁言的到期时间：同一个群后来的请求覆盖前一个，旧定时器醒来时发现不是自己的就退出。 */
    private final Map<Long, Long> muteDeadlines = new ConcurrentHashMap<>();

    GroupOps(QQClient qq, Identity identity, Directory directory, Lookup lookup, Resources resources,
             Sender sender, Events events, OutboundGate gate) {
        this.qq = qq;
        this.identity = identity;
        this.directory = directory;
        this.lookup = lookup;
        this.resources = resources;
        this.sender = sender;
        this.events = events;
        this.gate = gate;
    }

    private static long group(JSONObject p) { return p.optLong("guild_id", p.optLong("group_id", 0)); }

    private static long user(JSONObject p) { return Ids.parse(p.optString("user_id", "")); }

    private static long requireGroup(JSONObject p) {
        long g = group(p);
        if (g == 0) throw ApiError.badRequest("missing guild_id");
        return g;
    }

    private static long requireUser(JSONObject p) {
        long u = user(p);
        if (u == 0) throw ApiError.badRequest("missing user_id");
        return u;
    }

    // ------------------------------------------------------------------ 标准方法

    void kick(JSONObject p) throws Exception {
        long g = Directory.guildId(p);
        ApiError.require(qq.kickMember(g, directory.uidFor(g, user(p)), p.optBoolean("permanent", false)));
    }

    void mute(JSONObject p) throws Exception {
        long g = Directory.guildId(p);
        long durationMs = p.optLong("duration", 0);
        long seconds = durationMs <= 0 ? 0 : durationMs / 1000 + (durationMs % 1000 == 0 ? 0 : 1);
        ApiError.require(qq.banMember(g, directory.uidFor(g, user(p)), (int) Math.min(Integer.MAX_VALUE, seconds)));
    }

    /** {@code guild.member.role.set/unset}：QQ 只有「管理员」这一个能授予的角色。 */
    void setRole(JSONObject p, boolean grant, String method) throws Exception {
        long g = Directory.guildId(p);
        if (!"admin".equals(p.optString("role_id", ""))) throw ApiError.unsupported(method);
        ApiError.require(qq.setAdmin(g, directory.uidFor(g, user(p)), grant));
    }

    void deleteFriend(JSONObject p) {
        ApiError.require(qq.deleteFriend(requireUser(p)));
    }

    void updateChannel(JSONObject p) throws Exception {
        String channelId = p.optString("channel_id", "");
        if (Codec.isPrivateChannel(channelId)) throw ApiError.badRequest("private channel name/avatar cannot be updated");
        long gid = Codec.channelPeer(channelId);
        JSONObject data = p.optJSONObject("data");
        if (data == null) data = new JSONObject();
        String name = data.optString("name", "").trim();
        String avatar = data.optString("avatar", "").trim();
        if (gid == 0) throw ApiError.badRequest("missing channel_id");
        if (name.isEmpty() && avatar.isEmpty()) throw ApiError.badRequest("missing channel name or avatar");
        // 改名走 NapCat 的那一条路径（QQClient.setGroupName），不做二次写入、也不在这里回读校验：
        // 内核缓存滞后是常态，回读不匹配不代表改名没生效，据此再写一次才是以前出事的地方。
        if (!name.isEmpty()) ApiError.require(qq.setGroupName(gid, name));
        if (!avatar.isEmpty()) {
            File file = resources.resolveImage(avatar);
            ApiError.require(qq.setGroupHeader(gid, file.getAbsolutePath()));
        }
        if (name.isEmpty()) {
            try {
                name = directory.groupInfo(gid).optString("group_name");
            } catch (Exception ignore) {
                name = "";
            }
        }
        // 名字只用于事件（改了头像也得把当前名字带上），本实现端不写群名。
        events.guildChanged("updated", gid, name);
    }

    /**
     * Milliseconds for a `channel.mute` request. The published spec sends `duration`; the
     * protocol table shipped by @satorijs/protocol sends `enable`. A bare `enable: true` means
     * "until turned off", which QQ's boolean kernel call expresses as its 30-day ceiling.
     */
    static long muteDurationMs(JSONObject p) {
        if (p == null) return DEFAULT_CHANNEL_MUTE_MS;
        if (p.has("duration")) return Math.max(0, p.optLong("duration", 0));
        if (p.has("enable")) return p.optBoolean("enable", true) ? DEFAULT_CHANNEL_MUTE_MS : 0;
        return DEFAULT_CHANNEL_MUTE_MS;
    }

    void muteChannel(JSONObject p) {
        long guildId = Directory.guildId(p);
        long durationMs = muteDurationMs(p);
        ApiError.require(qq.wholeBan(guildId, durationMs > 0));
        if (durationMs <= 0) {
            muteDeadlines.remove(guildId);
            return;
        }
        long now = System.currentTimeMillis();
        long deadline = durationMs > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + durationMs;
        muteDeadlines.put(guildId, deadline);
        Workers.start(Workers.CHANNEL_UNMUTE, () -> {
            try {
                for (long remaining = deadline - System.currentTimeMillis(); remaining > 0;
                        remaining = deadline - System.currentTimeMillis()) {
                    Thread.sleep(Math.min(remaining, 60_000L));
                }
                Long current = muteDeadlines.get(guildId);
                if (current == null || current != deadline) return;
                if (muteDeadlines.remove(guildId, current)) {
                    gate.guarded("channel.mute", () -> {
                        ApiError.require(qq.wholeBan(guildId, false));
                        return new JSONObject();
                    });
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                L.e("scheduled channel unmute " + guildId, t);
            }
        });
    }

    // ------------------------------------------------------------------ internal 动作

    void invite(JSONObject p) throws Exception {
        long g = group(p), u = user(p);
        if (g == 0 || u == 0) throw ApiError.badRequest("missing guild_id/user_id");
        ApiError.require(qq.inviteToGroup(g, directory.uidFor(g, u)));
    }

    void card(JSONObject p) throws Exception {
        long g = requireGroup(p);
        long u = user(p);
        if (u == 0) u = identity.selfUin();
        ApiError.require(qq.setCard(g, directory.uidFor(g, u), p.optString("card", "")));
    }

    /** OidbSvcTrpcTcp.0x8FC_2: set (or clear) one member's special title. */
    void specialTitle(JSONObject p) throws Exception {
        long g = requireGroup(p), u = requireUser(p);
        String targetUid = directory.uidFor(g, u);
        String title = p.optString("title", p.optString("special_title", ""));
        // D8FCReqBody: 1=groupCode, 2=showFlag, 3=MemberInfo{1=uid, 5=title, 6=expire(-1=永久), 7=uinName}.
        byte[] member = Pb.w().string(1, targetUid).string(5, title).varint(6, -1L).string(7, title).toByteArray();
        byte[] body = Pb.w().varint(1, g).varint(2, 1).message(3, member).toByteArray();
        PacketSvc.Result result = qq.packets().sendOidb(0x8FC, 2, body);
        if (!result.ok()) throw ApiError.failed("set special title failed: " + result.describe());
        qq.getAllMembers(g, true);
    }

    /** 带 show/enable 才写；不带就只读当前开关状态，不动设置。 */
    JSONObject titleDisplay(JSONObject p) throws Exception {
        long g = requireGroup(p);
        if (!p.has("show") && !p.has("enable")) return readTitleDisplay(g);
        return writeTitleDisplay(g, p.optBoolean("show", p.optBoolean("enable", true)));
    }

    /**
     * 群管理「成员群头衔」= userShowFlag / cGroupRankUserFlag (1=开).
     * Not 群标识: that is groupFlagExt3 0x2000000 / isTroopHonorOpen.
     *
     * <p>只走反射内核服务通道（`setIdentityTitleInfo` / `setGroupIdentityLevelInfo`），
     * 外加本地 DB 补写（`updateLocalRankSwitch`）让 AIO 立刻读到，不发原始 OIDB 封包——
     * 群头衔的显示开关没有像 `0x8FC_2`（设头衔本身）那样核验过的封包形状，瞎发存在把
     * 群设置写坏的风险，JNI 反射通道错了至多是回调不触发。写完读回
     * `getMemberExtInfo`/`troopExtRankFlags` 校验，因为写调用本身的回调码不代表真的生效。
     */
    private JSONObject writeTitleDisplay(long groupId, boolean show) throws Exception {
        QQClient.MemberExtFlags before = qq.getMemberExtInfo(groupId);
        QQClient.OpResult id = qq.setIdentityTitleInfo(groupId, show);
        QQClient.OpResult level = qq.setGroupIdentityLevelInfo(groupId, show);
        boolean localUpdated = qq.updateLocalRankSwitch(groupId, show);
        try {
            Thread.sleep(400);
        } catch (InterruptedException ignore) {
            // 少等一会儿而已
        }
        QQClient.MemberExtFlags after = qq.getMemberExtInfo(groupId);
        int[] local = qq.troopExtRankFlags(groupId);
        return new JSONObject()
                .put("guild_id", String.valueOf(groupId))
                .put("wanted", show)
                .put("title_open", local[0] == 1 || after.titleOpen())
                .put("before_open", before.titleOpen())
                .put("user_show_flag", after.userShowFlag)
                .put("local_rank_flag", local[0])
                .put("set_identity_title_info", id.describe())
                .put("set_group_identity_level_info", level.describe())
                .put("local_rank_switch_updated", localUpdated);
    }

    /** 只读当前「展示成员群头衔」开关状态，不写；同样只走反射内核服务通道。 */
    private JSONObject readTitleDisplay(long groupId) throws Exception {
        QQClient.MemberExtFlags ext = qq.getMemberExtInfo(groupId);
        int[] local = qq.troopExtRankFlags(groupId);
        return new JSONObject()
                .put("guild_id", String.valueOf(groupId))
                .put("title_open", local[0] == 1 || ext.titleOpen())
                .put("user_show_flag", ext.userShowFlag)
                .put("local_rank_flag", local[0]);
    }

    /** 群聊资料中的「群荣誉/群标识」开关; distinct from member title display. */
    JSONObject honorDisplay(JSONObject p) throws Exception {
        long groupId = group(p);
        boolean show = p.optBoolean("show", p.optBoolean("enable", true));
        if (groupId == 0) throw ApiError.badRequest("missing group_id");
        int before = qq.groupFlagExt3(groupId);
        ApiError.require(qq.setHonorAioSwitch(groupId, show));
        boolean localUpdated = qq.callHonorAioService(groupId, show);
        qq.refreshGroupList();
        int after = qq.groupFlagExt3(groupId);
        return new JSONObject()
                .put("guild_id", String.valueOf(groupId))
                .put("wanted", show)
                .put("honor_open", (after & QQClient.HONOR_AIO_FLAG) == 0)
                .put("group_flag_ext3", after)
                .put("before_group_flag_ext3", before)
                .put("local_service_updated", localUpdated);
    }

    JSONObject sign(JSONObject p) throws Exception {
        long groupId = group(p);
        if (groupId == 0) throw ApiError.badRequest("missing group_id");
        String version = qq.qqVersion();
        if (version == null || version.isEmpty()) version = "9.3.60";
        byte[] inner = Pb.w()
                .string(1, String.valueOf(identity.selfUin()))
                .string(2, String.valueOf(groupId))
                .string(3, version)
                .toByteArray();
        PacketSvc.Result result = qq.packets().sendOidb(0xEB7, 1, Pb.w().message(2, inner).toByteArray(), false);
        if (!result.ok()) throw ApiError.failed("group sign failed: " + result.describe());
        return new JSONObject().put("ok", true);
    }

    void essence(JSONObject p) throws Exception {
        boolean add = !"remove".equals(p.optString("op", "add")) && !p.optBoolean("remove", false);
        long g = group(p);
        if (g == 0) g = Codec.channelPeer(p.optString("channel_id", ""));
        MsgStore.Rec rec = lookup.require(p.optString("message_id", ""), p);
        if (g == 0) g = rec.peerUin;
        long seq = rec.msgSeq;
        long random = 0;
        if (rec.msgRecord != null) {
            random = qq.ref.getLong(rec.msgRecord, "msgRandom");
            if (seq == 0) seq = qq.ref.getLong(rec.msgRecord, "msgSeq");
        }
        if (seq == 0 || random == 0) throw ApiError.badRequest("message missing seq/random");
        ApiError.require(qq.setGroupEssence(g, seq, random, add));
    }

    /** go-cqhttp send_poke: 0xED3_1. Group uses groupUin+target; friend uses friendUin=target. */
    void poke(JSONObject p) throws Exception {
        long[] target;
        try {
            target = Protocol.pokeTarget(p);
        } catch (IllegalArgumentException e) {
            throw ApiError.badRequest(e.getMessage());
        }
        long groupId = target[0], userId = target[1];
        if (userId == 0) throw ApiError.badRequest("missing user_id");
        Pb.Writer body = Pb.w().varint(1, userId).varint(6, 0);
        if (groupId != 0) body.varint(2, groupId);
        else body.varint(5, userId);
        PacketSvc.Result result = qq.packets().sendOidb(0xED3, 1, body.toByteArray());
        if (!result.ok()) throw ApiError.failed("send_poke failed: " + result.describe());
    }

    /** QQ's built-in random dice/RPS faces (358/359), sent to a group or direct channel. */
    JSONObject specialFace(JSONObject p, int faceId, String kind) throws Exception {
        String channelId = p.optString("channel_id", "");
        long groupId = group(p);
        long userId = user(p);
        if (!channelId.isEmpty()) {
            if (Codec.isPrivateChannel(channelId)) userId = Codec.channelPeer(channelId);
            else groupId = Codec.channelPeer(channelId);
        }
        // 骰子与猜拳是「超级表情」：FaceElement.faceType=3（动画贴纸）再加贴纸身份，
        // 否则 QQ 只画一个 16px 的小脸。取值照抄 NapCat 的 dice / rps 段（见 addFace 注释）。
        boolean dice = faceId == Codec.DICE_FACE;
        JSONArray message = new JSONArray().put(new JSONObject()
                .put("type", "face")
                .put("data", new JSONObject()
                        .put("id", String.valueOf(faceId))
                        .put("face_type", SPECIAL_FACE_TYPE)
                        .put("face_text", dice ? "[骰子]" : "[包剪锤]")
                        .put("pack_id", "1")
                        .put("sticker_id", dice ? "33" : "34")
                        .put("sticker_type", 2)
                        .put("source_type", 1)));
        String content = "<emoji id=\"" + faceId + "\"/>";
        JSONObject sent;
        if (groupId != 0) sent = sender.toGroup(groupId, message, content);
        else if (userId != 0) sent = sender.toUser(userId, message, content);
        else throw ApiError.badRequest("missing channel_id, guild_id, or user_id");
        return sent.put("kind", kind).put("face_id", faceId).put("face_type", SPECIAL_FACE_TYPE);
    }
}
