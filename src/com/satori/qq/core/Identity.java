package com.satori.qq.core;

import com.satori.qq.qq.QQClient;
import com.satori.qq.satori.Codec;
import com.satori.qq.satori.Protocol;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** 本实现端当前登录的 QQ 号，以及它在 Satori 里的两种面貌：完整的 login 与事件里带的精简 login。 */
final class Identity {
    static final String PLATFORM = "red";
    static final String ADAPTER = "satori-qq";

    /** `login.sn` 只用来区分一条连接上的多个登录；一个账号，一个编号，和 satori-wx 一致。 */
    private static final int LOGIN_SN = 1;

    /**
     * 本实现端提供的标准 Satori 方法。{@code login.features} 与 {@code internal/capabilities} 的
     * {@code standard_methods} 都从这一份来，客户端按它决定能调什么。
     */
    private static final List<String> STANDARD_METHODS = List.of(
            "login.get", "message.create", "message.get", "message.delete", "message.list",
            "channel.get", "channel.list", "channel.update", "channel.mute", "user.channel.create",
            "guild.get", "guild.list", "guild.member.get", "guild.member.list", "guild.member.kick",
            "guild.member.mute", "guild.member.role.set", "guild.member.role.unset",
            "guild.member.role.list", "guild.role.list",
            "reaction.create", "reaction.delete", "reaction.list",
            "user.get", "friend.list", "friend.delete", "friend.approve",
            "guild.approve", "guild.member.approve", "upload.create");

    private final QQClient qq;

    Identity(QQClient qq) { this.qq = qq; }

    long selfUin() {
        try {
            return Long.parseLong(qq.selfUin());
        } catch (Throwable t) {
            return 0;
        }
    }

    static boolean serves(String method) { return STANDARD_METHODS.contains(method); }

    static JSONArray standardMethods() { return new JSONArray(STANDARD_METHODS); }

    JSONObject loginFull() throws Exception {
        JSONArray features = new JSONArray().put("guild.plain");
        STANDARD_METHODS.forEach(features::put);
        return new JSONObject()
                .put("sn", LOGIN_SN)
                .put("adapter", ADAPTER)
                .put("platform", PLATFORM)
                .put("status", qq.isOnline() ? 1 : 0)
                .put("hidden", false)
                .put("self_id", String.valueOf(selfUin()))
                .put("user", Codec.user(selfUin(), qq.selfNick(), ""))
                .put("features", features);
    }

    JSONObject loginSlim() throws Exception {
        return Protocol.eventLogin(LOGIN_SN, PLATFORM, Codec.user(selfUin(), qq.selfNick(), ""));
    }

    /**
     * Where received media points. An {@code internal:} link names the login and the resource, and
     * carries no address: it survives a changed host or port, and a client resolves it through
     * {@code /v1/proxy/…} (or hands it back unchanged in a reply, where we resolve it from disk
     * without a round trip to ourselves). A URL built from this server's own bind address — what
     * this used to return — breaks as soon as the client is not on the same host.
     */
    String assetBase() { return "internal:" + PLATFORM + "/" + selfUin() + "/_tmp/"; }

    /**
     * Whether a {@code Satori-User-ID} names a login we do not serve.
     *
     * <p>An absent selector is not a foreign one. Neither is {@code 0}: that is the placeholder this
     * hub used to advertise in READY before QQ's account was known, and a client that connected
     * then keeps echoing it for the life of its connection. Treating it as foreign rejects every
     * request from that client, and since nothing about the selector ever changes on its own, the
     * client stays mute until it reconnects. Anything else really is another login.
     */
    static boolean isForeignLogin(String userId, long selfUin) {
        if (userId == null || userId.isEmpty() || "0".equals(userId)) return false;
        return selfUin != 0 && !String.valueOf(selfUin).equals(userId);
    }
}
