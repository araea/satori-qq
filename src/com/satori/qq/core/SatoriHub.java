package com.satori.qq.core;

import com.satori.qq.Cfg;
import com.satori.qq.L;
import com.satori.qq.net.HttpServer;
import com.satori.qq.net.WsConn;
import com.satori.qq.packet.LongMsg;
import com.satori.qq.packet.GroupFiles;
import com.satori.qq.packet.PacketSvc;
import com.satori.qq.packet.Pb;
import com.satori.qq.qq.Convert;
import com.satori.qq.qq.AntiDetect;
import com.satori.qq.qq.Media;
import com.satori.qq.qq.QQClient;
import com.satori.qq.qq.QzoneSvc;
import com.satori.qq.qq.Ref;
import com.satori.qq.satori.Codec;
import com.satori.qq.satori.Elements;
import com.satori.qq.satori.Multipart;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** Satori v1 hub: HTTP RPC in + WebSocket events out. QQ kernel ops stay below this layer. */
public final class SatoriHub implements HttpServer.Handler, QQClient.Listener {
    public static final String APP_NAME = "satori-qq";
    public static final String APP_VERSION = "0.8.9";
    public static final String PLATFORM = "red";
    public static final String ADAPTER = "satori-qq";

    private static final int OP_EVENT = 0, OP_PING = 1, OP_PONG = 2, OP_IDENTIFY = 3, OP_READY = 4, OP_META = 5;

    private final Cfg cfg;
    private final QQClient qq;
    private final QzoneSvc qzone;
    private final MsgStore store;
    private final Convert conv;
    private final OutboundGuard outboundGuard;
    private HttpServer server;
    private volatile long onlineSinceMs;
    private final Set<WsConn> identified = ConcurrentHashMap.newKeySet();
    private final AtomicLong eventSn = new AtomicLong();
    private final CopyOnWriteArrayList<JSONObject> recentEvents = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Long, Long> channelMuteDeadlines = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, java.util.Map<String, Long>> reactionCounts =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> recentGroupEvents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> groupNames = new ConcurrentHashMap<>();
    private final java.util.Map<String, String> lastListTexts = new java.util.HashMap<>();
    private static final class HistRow {
        final JSONObject item;
        final long msgId;
        final long time;
        final long seq;
        final String text;
        HistRow(JSONObject item, long msgId, long time, long seq, String text) {
            this.item = item;
            this.msgId = msgId;
            this.time = time;
            this.seq = seq;
            this.text = text == null ? "" : text;
        }
    }
    private final java.util.ArrayList<HistRow> lastHistRows = new java.util.ArrayList<>();
    private static final int EVENT_BUFFER = 200;

    public SatoriHub(Cfg cfg, QQClient qq, MsgStore store) {
        this.cfg = cfg; this.qq = qq; this.store = store;
        this.qzone = new QzoneSvc(qq);
        this.conv = new Convert(qq, store);
        this.outboundGuard = new OutboundGuard(cfg.outboundMinIntervalMs,
                cfg.outboundQueueTimeoutMs, cfg.outboundMaxQueued,
                cfg.outboundMaxPerMinute, cfg.outboundFailureThreshold,
                cfg.outboundCircuitOpenMs);
    }

    public void start() {
        server = new HttpServer(cfg, this);
        server.start();
        qq.setListener(this);
        startStatusMonitor();
    }

    private long selfUin() { try { return Long.parseLong(qq.selfUin()); } catch (Throwable t) { return 0; } }

    private String assetBase() {
        return "http://" + cfg.host + ":" + cfg.port + "/v1/assets/";
    }

    private JSONObject loginFull() throws Exception {
        boolean online = qq.isOnline();
        JSONObject login = new JSONObject();
        login.put("sn", 0);
        login.put("adapter", ADAPTER);
        login.put("platform", PLATFORM);
        login.put("status", online ? 1 : 0);
        login.put("user", Codec.user(selfUin(), qq.selfNick(), ""));
        JSONArray features = new JSONArray()
                .put("guild.plain")
                .put("login.get")
                .put("message.create")
                .put("message.get")
                .put("message.delete")
                .put("message.list")
                .put("channel.get")
                .put("channel.list")
                .put("channel.update")
                .put("channel.mute")
                .put("user.channel.create")
                .put("guild.get")
                .put("guild.list")
                .put("guild.member.get")
                .put("guild.member.list")
                .put("guild.member.kick")
                .put("guild.member.mute")
                .put("guild.member.role.set")
                .put("guild.member.role.unset")
                .put("guild.role.list")
                .put("reaction.create")
                .put("reaction.delete")
                .put("reaction.list")
                .put("user.get")
                .put("friend.list")
                .put("friend.delete")
                .put("friend.approve")
                .put("guild.approve")
                .put("guild.member.approve")
                .put("upload.create");
        login.put("features", features);
        return login;
    }

    private JSONObject loginSlim() throws Exception {
        return new JSONObject()
                .put("sn", 0)
                .put("adapter", ADAPTER)
                .put("platform", PLATFORM)
                .put("status", qq.isOnline() ? 1 : 0)
                .put("user", Codec.user(selfUin(), qq.selfNick(), ""));
    }

    // ============ HTTP API + WS events ============
    @Override public HttpServer.HttpResult onHttp(HttpServer.HttpReq req) {
        try {
            String path = req.path == null ? "/" : req.path;
            if ("GET".equals(req.method) && path.startsWith("/v1/assets/")) {
                // Local-only bind; Koishi/puppeteer <img> cannot send Bearer.
                return serveAsset(path.substring("/v1/assets/".length()));
            }
            if ("GET".equals(req.method) && path.startsWith("/v1/proxy/")) {
                return serveProxy(path.substring("/v1/proxy/".length()));
            }
            if (!httpAuth(req)) return HttpServer.HttpResult.text(401, "unauthorized");
            if ("GET".equals(req.method) && "/".equals(path)) {
                return HttpServer.HttpResult.json(200, new JSONObject()
                        .put("name", APP_NAME).put("version", APP_VERSION)
                        .put("protocol", "v1").toString());
            }
            if (!"POST".equals(req.method)) {
                if (path.startsWith("/v1/")) return HttpServer.HttpResult.text(405, "Please use POST");
                return HttpServer.HttpResult.text(404, "not found");
            }
            if (!path.startsWith("/v1/")) return HttpServer.HttpResult.text(404, "not found");
            String method = path.substring("/v1/".length());
            if ("meta".equals(method)) return jsonResult(meta());
            validateLoginHeaders(req, method);
            if ("upload.create".equals(method)) return jsonResult(uploadCreate(req));
            JSONObject body = parseBody(req);
            if (method.startsWith("internal/")) {
                return jsonResult(dispatchInternal(method.substring("internal/".length()), body));
            }
            if (OutboundGuard.isMutation(method)) return jsonResult(guarded(method, () -> dispatch(method, body)));
            return jsonResult(dispatch(method, body));
        } catch (NotImplemented ni) {
            return HttpServer.HttpResult.text(404, ni.getMessage());
        } catch (ApiError e) {
            int http = e.code == 1400 ? 400 : e.code == 1404 ? 404 : 500;
            return HttpServer.HttpResult.json(http, errorJson(e.getMessage()));
        } catch (IllegalStateException e) {
            return HttpServer.HttpResult.json(500, errorJson(e.getMessage()));
        } catch (Throwable t) {
            L.e("http " + req.method + " " + req.path, t);
            return HttpServer.HttpResult.json(500, errorJson(String.valueOf(t)));
        }
    }

    @Override public void onWsOpen(WsConn conn) {
        Thread t = new Thread(() -> {
            try { Thread.sleep(10_000); } catch (InterruptedException e) { return; }
            if (!identified.contains(conn)) conn.close();
        }, "pool-5-thread-3");
        t.setDaemon(true);
        t.start();
    }

    @Override public void onWsText(WsConn conn, String text) {
        JSONObject msg;
        try { msg = new JSONObject(text); } catch (Throwable t) { return; }
        int op = msg.optInt("op", -1);
        JSONObject body = msg.optJSONObject("body");
        if (body == null) body = new JSONObject();
        try {
            if (op == OP_IDENTIFY) {
                if (cfg.token != null && !cfg.token.isEmpty()
                        && !cfg.token.equals(body.optString("token", ""))) {
                    conn.close();
                    return;
                }
                identified.add(conn);
                conn.send(opJson(OP_READY, new JSONObject()
                        .put("logins", new JSONArray().put(loginFull()))
                        .put("proxy_urls", new JSONArray())).toString());
                long after = body.optLong("sn", 0);
                replayEvents(conn, after);
                replayPendingRequests(conn);
                return;
            }
            if (!identified.contains(conn)) return;
            if (op == OP_PING) {
                conn.send(opJson(OP_PONG, new JSONObject()).toString());
            }
        } catch (Throwable t) {
            L.e("ws opcode " + op, t);
        }
    }

    @Override public void onWsClose(WsConn conn) {
        identified.remove(conn);
    }

    private boolean httpAuth(HttpServer.HttpReq req) {
        if (cfg.token == null || cfg.token.isEmpty()) return true;
        String auth = req.header("authorization");
        if (auth.regionMatches(true, 0, "Bearer ", 0, 7) && cfg.token.equals(auth.substring(7).trim()))
            return true;
        String q = req.query;
        if (q != null) {
            for (String part : q.split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0 && "access_token".equals(part.substring(0, eq))
                        && cfg.token.equals(part.substring(eq + 1))) return true;
            }
        }
        return false;
    }

    private JSONObject parseBody(HttpServer.HttpReq req) {
        String t = req.bodyText();
        if (t == null || t.trim().isEmpty()) return new JSONObject();
        try { return new JSONObject(t); }
        catch (Exception e) { throw new ApiError(1400, "malformed JSON request body"); }
    }

    private HttpServer.HttpResult jsonResult(Object data) {
        if (data == null) return HttpServer.HttpResult.json(200, "null");
        return HttpServer.HttpResult.json(200, data.toString());
    }

    private static String errorJson(String msg) {
        try { return new JSONObject().put("message", msg == null ? "" : msg).toString(); }
        catch (Exception e) { return "{\"message\":\"error\"}"; }
    }

    private static JSONObject opJson(int op, JSONObject body) throws Exception {
        return new JSONObject().put("op", op).put("body", body == null ? JSONObject.NULL : body);
    }

    private HttpServer.HttpResult serveAsset(String id) {
        if (id == null || id.isEmpty()) return HttpServer.HttpResult.text(400, "missing id");
        int q = id.indexOf('?');
        if (q >= 0) id = id.substring(0, q);
        try {
            id = java.net.URLDecoder.decode(id, "UTF-8");
        } catch (Exception ignore) {}
        try {
            // This unauthenticated browser-facing route only serves opaque ids issued by us.
            // Direct paths remain accepted by internal/get_resource, never by HTTP GET.
            if (store.getResource(id) == null) return HttpServer.HttpResult.text(404, "not found");
            JSONObject p = new JSONObject().put("file", id);
            JSONObject res = getResource(p, null);
            String file = res.optString("file", "");
            java.io.File local = file.isEmpty() ? null : new java.io.File(file);
            if (local == null || !local.isFile()) return HttpServer.HttpResult.text(404, "not found");
            byte[] data = java.nio.file.Files.readAllBytes(local.toPath());
            String type = sniffMime(data, local.getName(), res.optString("resource_type", ""));
            java.util.Map<String, String> extra = new java.util.LinkedHashMap<>();
            extra.put("Access-Control-Allow-Origin", "*");
            extra.put("Cache-Control", "private, max-age=3600");
            return new HttpServer.HttpResult(200, type, data, extra);
        } catch (ApiError e) {
            return HttpServer.HttpResult.text(e.code == 1404 ? 404 : 400, e.getMessage());
        } catch (Throwable t) {
            return HttpServer.HttpResult.text(500, String.valueOf(t));
        }
    }

    private HttpServer.HttpResult serveProxy(String encoded) {
        if (encoded == null || encoded.isEmpty()) return HttpServer.HttpResult.text(400, "missing url");
        String url;
        try { url = java.net.URLDecoder.decode(encoded, "UTF-8"); }
        catch (Exception e) { return HttpServer.HttpResult.text(400, "invalid url"); }
        if (!url.startsWith("internal:")) return HttpServer.HttpResult.text(403, "proxy url not allowed");
        String prefix = "internal:" + PLATFORM + "/" + selfUin() + "/_tmp/";
        if (!url.startsWith(prefix)) return HttpServer.HttpResult.text(404, "login or resource not found");
        String id = url.substring(prefix.length());
        if (id.isEmpty() || id.indexOf('/') >= 0 || id.indexOf('\\') >= 0)
            return HttpServer.HttpResult.text(400, "invalid internal resource");
        return serveAsset(id);
    }

    private JSONObject meta() throws Exception {
        return new JSONObject()
                .put("logins", new JSONArray().put(loginFull()))
                .put("proxy_urls", new JSONArray());
    }

    /** A single-account implementation tolerates omitted selectors for compatibility, but never
     * routes a request explicitly addressed to another platform or login. */
    private void validateLoginHeaders(HttpServer.HttpReq req, String method) {
        if (method.startsWith("meta")) return;
        String platform = req.header("satori-platform");
        String userId = req.header("satori-user-id");
        if (!platform.isEmpty() && !PLATFORM.equals(platform))
            throw new ApiError(1404, "unknown Satori-Platform: " + platform);
        long self = selfUin();
        if (!userId.isEmpty() && self != 0 && !String.valueOf(self).equals(userId))
            throw new ApiError(1404, "unknown Satori-User-ID: " + userId);
    }

    private JSONObject uploadCreate(HttpServer.HttpReq req) throws Exception {
        String type = req.header("content-type");
        java.util.List<Multipart.Part> parts;
        try { parts = Multipart.parse(req.body, type); }
        catch (IllegalArgumentException e) { throw new ApiError(1400, e.getMessage()); }
        if (parts.isEmpty()) throw new ApiError(1400, "upload contains no files");
        JSONObject out = new JSONObject();
        java.util.HashSet<String> names = new java.util.HashSet<>();
        for (Multipart.Part part : parts) {
            if (part.name == null || part.name.isEmpty()) throw new ApiError(1400, "upload part missing name");
            if (!names.add(part.name)) throw new ApiError(1400, "duplicate upload name: " + part.name);
            if (part.contentType == null || part.contentType.isEmpty())
                throw new ApiError(1400, "upload part missing Content-Type: " + part.name);
            java.io.File file = Media.storeUpload(part.data, part.filename, part.contentType);
            String kind = resourceKind(part.contentType);
            String id = store.putResource(kind, "", file.getAbsolutePath(), "",
                    part.filename, file.length());
            out.put(part.name, "internal:" + PLATFORM + "/" + selfUin() + "/_tmp/" + id);
        }
        return out;
    }

    private static String resourceKind(String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
        if (type.startsWith("image/")) return "image";
        if (type.startsWith("audio/")) return "record";
        if (type.startsWith("video/")) return "video";
        return "file";
    }

    private static String sniffMime(byte[] data, String name, String kind) {
        String ext = Media.guessExt(data);
        if (".png".equals(ext)) return "image/png";
        if (".jpg".equals(ext)) return "image/jpeg";
        if (".gif".equals(ext)) return "image/gif";
        if (".webp".equals(ext)) return "image/webp";
        if (".mp4".equals(ext)) return "video/mp4";
        if (".wav".equals(ext)) return "audio/wav";
        if (".mp3".equals(ext)) return "audio/mpeg";
        return guessType(name, kind);
    }

    private static String guessType(String name, String kind) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg") || "image".equals(kind)) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".mp4") || "video".equals(kind)) return "video/mp4";
        if ("record".equals(kind) || n.endsWith(".silk") || n.endsWith(".amr")) return "audio/silk";
        return "application/octet-stream";
    }

    private static final class ApiError extends RuntimeException {
        final int code; ApiError(int code, String msg) { super(msg); this.code = code; }
    }
    private static final class NotImplemented extends RuntimeException {
        NotImplemented(String method) { super("API not found: " + method); }
    }

    private void ensureOutboundReady() {
        if (!qq.isOnline()) throw new ApiError(1500, "QQ kernel offline or not ready");
        long since = onlineSinceMs;
        long remaining = cfg.onlineStabilizeMs - (System.currentTimeMillis() - since);
        if (since <= 0 || remaining > 0) {
            long seconds = Math.max(1, (remaining + 999) / 1000);
            throw new ApiError(1500, "QQ session stabilizing; retry after " + seconds + "s");
        }
    }

    private interface Work { Object run() throws Exception; }

    private Object guarded(String method, Work work) throws Exception {
        OutboundGuard.Lease lease = null;
        boolean ok = false;
        try {
            ensureOutboundReady();
            try {
                lease = outboundGuard.acquire(method);
            } catch (OutboundGuard.BusyException busy) {
                throw new ApiError(1500, busy.getMessage());
            }
            ensureOutboundReady();
            Object data = work.run();
            ok = true;
            return data;
        } finally {
            if (lease != null) lease.complete(ok);
        }
    }

    private Object dispatch(String method, JSONObject p) throws Exception {
        if (p == null) p = new JSONObject();
        if ("login.get".equals(method)) return loginFull();
        if (!qq.isOnline()) throw new ApiError(1500, "QQ kernel offline or not ready");
        switch (method) {
            case "message.create": return messageCreate(p);
            case "message.get": return satoriGetMsg(p);
            case "message.list": return satoriMsgList(p);
            case "message.delete": {
                String rawId = p.optString("message_id", "");
                MsgStore.Rec rec = requireMessage(rawId, p);
                validateMessageChannel(p, rec.id);
                long pub = parseLongQuiet(rawId);
                if (rec.msgId == 0 && pub != 0 && String.valueOf(pub).length() >= 16)
                    rec.msgId = pub;
                recall(rec.id);
                return new JSONObject();
            }
            case "channel.get": return satoriChannelGet(p);
            case "channel.list": return satoriChannelList(p);
            case "channel.update": return satoriChannelUpdate(p);
            case "channel.mute": {
                long gid = guildIdOf(p);
                if (p.has("duration")) muteChannel(gid, Math.max(0, p.optLong("duration", 0)));
                else requireOp(qq.wholeBan(gid, p.optBoolean("enable", true)));
                return new JSONObject();
            }
            case "user.channel.create": {
                long uid = parseId(p.optString("user_id", ""));
                if (uid == 0) throw new ApiError(1400, "missing user_id");
                return Codec.channel(QQClient.CT_C2C, uid, "");
            }
            case "guild.get": return satoriGuildGet(p);
            case "guild.list": return listWrap(satoriGuildList());
            case "guild.member.get": return satoriMember(p);
            case "guild.member.list": return listWrap(satoriMemberList(p));
            case "guild.member.kick": {
                long g = guildIdOf(p), u = parseId(p.optString("user_id", ""));
                requireOp(qq.kickMember(g, uidFor(g, u), p.optBoolean("permanent", false)));
                return new JSONObject();
            }
            case "guild.member.mute": {
                long g = guildIdOf(p), u = parseId(p.optString("user_id", ""));
                long durationMs = p.optLong("duration", 0);
                long rounded = durationMs <= 0 ? 0
                        : durationMs / 1000 + (durationMs % 1000 == 0 ? 0 : 1);
                int seconds = (int) Math.min(Integer.MAX_VALUE, rounded);
                requireOp(qq.banMember(g, uidFor(g, u), seconds));
                return new JSONObject();
            }
            case "guild.member.role.set":
            case "guild.member.role.unset": {
                long g = guildIdOf(p), u = parseId(p.optString("user_id", ""));
                String role = p.optString("role_id", "");
                if (!"admin".equals(role)) throw new NotImplemented(method);
                requireOp(qq.setAdmin(g, uidFor(g, u), method.endsWith(".set")));
                return new JSONObject();
            }
            case "guild.role.list":
                guildIdOf(p); // validate that the request identifies a guild
                return listWrap(new JSONArray()
                        .put(new JSONObject().put("id", "owner").put("name", "owner"))
                        .put(new JSONObject().put("id", "admin").put("name", "admin"))
                        .put(new JSONObject().put("id", "member").put("name", "member")));
            case "reaction.create": {
                String emojiRaw = p.optString("emoji_id", "");
                int messageId = requireMessage(p.optString("message_id", ""), p).id;
                validateMessageChannel(p, messageId);
                setEmojiLike(messageId, parseEmoji(emojiRaw), emojiRaw, true);
                return new JSONObject();
            }
            case "reaction.delete": {
                String target = p.optString("user_id", "");
                if (!target.isEmpty() && !String.valueOf(selfUin()).equals(target))
                    throw new ApiError(1400, "QQ can only remove the current login's reaction");
                String emojiRaw = p.optString("emoji_id", "");
                int messageId = requireMessage(p.optString("message_id", ""), p).id;
                validateMessageChannel(p, messageId);
                setEmojiLike(messageId, parseEmoji(emojiRaw), emojiRaw, false);
                return new JSONObject();
            }
            case "reaction.list": {
                String emojiRaw = p.optString("emoji_id", "");
                int messageId = requireMessage(p.optString("message_id", ""), p).id;
                validateMessageChannel(p, messageId);
                return reactionList(messageId, parseEmoji(emojiRaw), emojiRaw,
                        p.optString("next", ""));
            }
            case "user.get": return satoriUser(p);
            case "friend.list": return listWrap(satoriFriendList());
            case "friend.delete": {
                long friend = parseId(p.optString("user_id", ""));
                if (friend == 0) throw new ApiError(1400, "missing user_id");
                requireOp(qq.deleteFriend(friend));
                return new JSONObject();
            }
            case "friend.approve":
                setFriendAddRequest(p.optString("message_id", ""),
                        p.optBoolean("approve", true), p.optString("comment", ""));
                return new JSONObject();
            case "guild.approve":
            case "guild.member.approve":
                setGroupAddRequest(p.optString("message_id", ""),
                        p.optBoolean("approve", true), p.optString("comment", ""));
                return new JSONObject();
            case "upload.create":
                throw new NotImplemented("upload.create");
            default:
                throw new NotImplemented(method);
        }
    }

    private Object dispatchInternal(String name, JSONObject p) throws Exception {
        final JSONObject params = p == null ? new JSONObject() : p;
        if (name.startsWith("_api/")) name = name.substring(5);
        if (name.startsWith("/")) name = name.substring(1);
        switch (name) {
            case "poke":
                return guarded("internal.poke", () -> {
                    sendPoke(params.optLong("guild_id", params.optLong("group_id", 0)),
                            parseId(params.optString("user_id", "")));
                    return new JSONObject();
                });
            case "like":
                return guarded("internal.like", () -> {
                    sendLike(parseId(params.optString("user_id", "")), params.optInt("times", 1));
                    return new JSONObject();
                });
            case "special-title":
            case "special_title":
                return guarded("internal.special_title", () -> {
                    long g = params.optLong("guild_id", params.optLong("group_id", 0));
                    long u = parseId(params.optString("user_id", ""));
                    if (params.has("show") && u == 0)
                        return setGroupTitleDisplay(g, params.optBoolean("show", true));
                    if (params.has("show")) setGroupTitleDisplay(g, params.optBoolean("show", true));
                    setGroupSpecialTitle(g, uidFor(g, u),
                            params.optString("title", params.optString("special_title", "")));
                    return new JSONObject();
                });
            case "title-display":
            case "title_display":
                return guarded("internal.title_display", () -> {
                    long g = params.optLong("guild_id", params.optLong("group_id", 0));
                    return setGroupTitleDisplay(g, params.optBoolean("show", params.optBoolean("enable", true)));
                });
            case "card":
            case "set-card":
            case "set_card":
                return guarded("internal.card", () -> {
                    long g = params.optLong("guild_id", params.optLong("group_id", 0));
                    long u = parseId(params.optString("user_id", ""));
                    if (g == 0) throw new ApiError(1400, "missing guild_id");
                    if (u == 0) u = selfUin();
                    requireOp(qq.setCard(g, uidFor(g, u), params.optString("card", "")));
                    return new JSONObject();
                });
            case "sign":
            case "clock-in":
            case "clock_in":
            case "group_sign":
                return guarded("internal.sign", () -> groupSign(params.optLong("guild_id", params.optLong("group_id", 0))));
            case "essence":
            case "set-essence":
            case "set_essence":
                return guarded("internal.essence", () -> {
                    boolean add = !"remove".equals(params.optString("op", "add"))
                            && !params.optBoolean("remove", false);
                    setGroupEssence(params, add);
                    return new JSONObject();
                });
            case "group-remark":
            case "group_remark":
                return guarded("internal.group_remark", () -> {
                    long g = params.optLong("guild_id", params.optLong("group_id", 0));
                    if (g == 0) throw new ApiError(1400, "missing guild_id");
                    requireOp(qq.setGroupRemark(g, params.optString("remark", params.optString("name", ""))));
                    return new JSONObject();
                });
            case "group-extra":
            case "group_extra":
                return groupExtra(params.optLong("guild_id", params.optLong("group_id", 0)));
            case "invite":
                return guarded("internal.invite", () -> {
                    long g = params.optLong("guild_id", params.optLong("group_id", 0));
                    long u = parseId(params.optString("user_id", ""));
                    if (g == 0 || u == 0) throw new ApiError(1400, "missing guild_id/user_id");
                    requireOp(qq.inviteToGroup(g, uidFor(g, u)));
                    return new JSONObject();
                });
            case "restart":
                scheduleRestart(Math.max(500, params.optInt("delay", 0)));
                return new JSONObject();
            case "clean-cache":
            case "clean_cache":
                return new JSONObject().put("deleted", Media.cleanTemp());
            case "status":
                return status(qq.isOnline());
            case "version":
                return versionInfo();
            case "get-resource":
            case "get_resource": {
                String kind = params.optString("type", "");
                return getResource(params, kind.isEmpty() ? null : kind);
            }
            case "group-file":
            case "group_file":
                return dispatchGroupFile(params);
            case "get-forward":
            case "get_forward":
                return satoriGetForward(params.optString("id", params.optString("message_id", "")));
            case "qzone.create":
            case "qzone.publish":
                return qzonePublish(params);
            case "qzone.delete":
                return qzoneDelete(params);
            case "qzone.list":
                return qzoneList(params);
            case "qzone.auth":
                return qzoneAuthDebug();
            case "qzone.delete-all":
            case "qzone.delete_all":
            case "qzone.clear":
                return qzone.deleteAll();
            default:
                throw new NotImplemented("internal/" + name);
        }
    }

    private Object dispatchGroupFile(JSONObject p) throws Exception {
        final JSONObject params = p == null ? new JSONObject() : p;
        String op = params.optString("op", params.optString("action", "list"));
        switch (op) {
            case "info": return getGroupFileSystemInfo(params.optLong("guild_id", params.optLong("group_id", 0)));
            case "list": return getGroupFiles(params.optLong("guild_id", params.optLong("group_id", 0)),
                    firstNonEmpty(params.optString("folder_id", ""), "/"));
            case "url": return getGroupFileUrl(params.optLong("guild_id", params.optLong("group_id", 0)),
                    params.optString("file_id", ""), params.optInt("busid", params.optInt("bus_id", 0)));
            case "upload": return guarded("internal.group_file", () -> {
                String spec = params.optString("file", params.optString("url", ""));
                if (spec.startsWith("internal:")) params.put("file", resolveInternalSpec(spec));
                String ch = params.optString("channel_id", "");
                if (params.optLong("user_id", 0) != 0 || Codec.isPrivateChannel(ch)) {
                    if (!params.has("user_id") && Codec.isPrivateChannel(ch))
                        params.put("user_id", Codec.channelPeer(ch));
                    return uploadPrivateFile(params);
                }
                if (!params.has("group_id")) {
                    long g = params.optLong("guild_id", Codec.channelPeer(ch));
                    if (g != 0) params.put("group_id", g);
                }
                return uploadGroupFile(params);
            });
            default: throw new NotImplemented("internal/group_file " + op);
        }
    }

    private JSONArray messageCreate(JSONObject p) throws Exception {
        String channelId = p.optString("channel_id", "");
        if (channelId.isEmpty()) throw new ApiError(1400, "missing channel_id");
        String content = p.optString("content", "");
        boolean group = !Codec.isPrivateChannel(channelId);
        long peer = Codec.channelPeer(channelId);
        if (peer == 0) throw new ApiError(1400, "invalid channel_id");
        java.util.List<java.util.List<Elements.El>> batches = splitMessages(Elements.parse(content));
        JSONArray result = new JSONArray();
        for (java.util.List<Elements.El> batch : batches) {
            String sentContent = Elements.stringify(batch);
            resolveInternalResources(batch);
            JSONArray segs = segmentsForBatch(batch);
            if (segs.length() == 0) continue;
            JSONObject sent;
            if (looksLikeForward(segs)) {
                sent = group ? sendForward(peer, 0, segs) : sendForward(0, peer, segs);
            } else {
                sent = group ? sendGroup(peer, segs, sentContent) : sendPrivate(peer, segs, sentContent);
            }
            JSONObject msg = new JSONObject();
            String mid = Codec.publicMessageId(sent);
            msg.put("id", mid.isEmpty() ? String.valueOf(sent.opt("message_id")) : mid);
            msg.put("content", sentContent);
            msg.put("channel", Codec.channel(group ? QQClient.CT_GROUP : QQClient.CT_C2C, peer, ""));
            if (group) msg.put("guild", Codec.guild(peer, ""));
            msg.put("user", Codec.user(selfUin(), qq.selfNick(), ""));
            msg.put("created_at", System.currentTimeMillis());
            result.put(msg);
        }
        return result;
    }

    private java.util.List<java.util.List<Elements.El>> splitMessages(java.util.List<Elements.El> roots) {
        java.util.ArrayList<java.util.List<Elements.El>> out = new java.util.ArrayList<>();
        java.util.ArrayList<Elements.El> current = new java.util.ArrayList<>();
        for (Elements.El el : roots) {
            if (!"message".equals(el.type)) {
                current.add(el);
                continue;
            }
            if (!current.isEmpty()) {
                out.add(current);
                current = new java.util.ArrayList<>();
            }
            if (el.attrs.optBoolean("forward", false) || hasChildMessage(el)) {
                java.util.ArrayList<Elements.El> one = new java.util.ArrayList<>();
                one.add(el);
                out.add(one);
            } else if (!el.children.isEmpty()) {
                java.util.ArrayList<Elements.El> body = new java.util.ArrayList<>();
                for (Elements.El child : el.children)
                    if (!"author".equals(child.type)) body.add(child);
                if (!body.isEmpty()) out.add(body);
            }
            // An empty <message/> is only a separator.
        }
        if (!current.isEmpty()) out.add(current);
        return out;
    }

    private static boolean hasChildMessage(Elements.El el) {
        for (Elements.El child : el.children) if ("message".equals(child.type)) return true;
        return false;
    }

    private JSONArray segmentsForBatch(java.util.List<Elements.El> batch) throws Exception {
        if (batch.size() == 1) {
            Elements.El el = batch.get(0);
            if ("message".equals(el.type) && el.attrs.optBoolean("forward", false)
                    && el.children.isEmpty() && !el.attr("id").isEmpty()) {
                JSONObject source = getMsg(requireMessage(el.attr("id")).id);
                JSONArray segments = source.optJSONArray("message");
                return segments == null ? new JSONArray() : segments;
            }
        }
        return Codec.toSegments(batch);
    }

    private void resolveInternalResources(java.util.List<Elements.El> elements) throws Exception {
        for (Elements.El el : elements) {
            String src = el.attr("src");
            if (src.startsWith("internal:")) el.attrs.put("src", resolveInternalSpec(src));
            if (!el.children.isEmpty()) resolveInternalResources(el.children);
        }
    }

    private String resolveInternalSpec(String src) throws Exception {
        String prefix = "internal:" + PLATFORM + "/" + selfUin() + "/_tmp/";
        if (!src.startsWith(prefix)) throw new ApiError(1404, "internal resource login not found");
        String id = src.substring(prefix.length());
        JSONObject found = getResource(new JSONObject().put("file", id), null);
        String file = found.optString("file", "");
        if (file.isEmpty()) throw new ApiError(1404, "internal resource not found");
        return file;
    }

    private JSONObject satoriGetMsg(JSONObject p) throws Exception {
        MsgStore.Rec rec = requireMessage(p.optString("message_id", ""), p);
        validateMessageChannel(p, rec.id);
        JSONObject ob = getMsg(rec.id);
        JSONObject ev = Codec.toSatoriEvent(ob.put("post_type", "message")
                .put("self_id", selfUin()), loginSlim(), 0, assetBase());
        JSONObject msg = ev == null ? null : ev.optJSONObject("message");
        if (msg == null) return new JSONObject();
        if (msg.optString("content", "").isEmpty()) {
            String text = snapshotText(msg.optString("id", ""), rec);
            if (!text.isEmpty()) msg.put("content", text);
        }
        if (rec.msgTime > 0) msg.put("created_at", rec.msgTime * 1000L);
        return msg;
    }

    private JSONObject satoriMsgList(JSONObject p) throws Exception {
        String channelId = p.optString("channel_id", "");
        if (channelId.isEmpty() || Codec.channelPeer(channelId) == 0)
            throw new ApiError(1400, "missing or invalid channel_id");
        int limit = Math.max(1, Math.min(100, p.optInt("limit", 20)));
        long next = parseLongQuiet(p.optString("next", "0"));
        String direction = p.optString("direction", "before").toLowerCase(java.util.Locale.ROOT);
        String order = p.optString("order", "asc").toLowerCase(java.util.Locale.ROOT);
        if (!("before".equals(direction) || "after".equals(direction) || "around".equals(direction)))
            throw new ApiError(1400, "invalid direction");
        if (!("asc".equals(order) || "desc".equals(order))) throw new ApiError(1400, "invalid order");
        if (next == 0 && !"before".equals(direction))
            throw new ApiError(1400, "direction requires next cursor");
        boolean group = !Codec.isPrivateChannel(channelId);
        long peer = Codec.channelPeer(channelId);
        lastListTexts.clear();
        lastHistRows.clear();
        if ("around".equals(direction)) {
            int olderCount = Math.max(1, limit / 2);
            int newerCount = Math.max(1, limit - olderCount);
            getMsgHistory(group, peer, next, olderCount, true, "before");
            getMsgHistory(group, peer, next, newerCount, false, "after");
        } else {
            getMsgHistory(group, peer, next, limit, "before".equals(direction), direction);
        }
        java.util.ArrayList<HistRow> sorted = new java.util.ArrayList<>(lastHistRows);
        java.util.Collections.sort(sorted, (a, b) -> Long.compare(a.seq, b.seq));
        if (sorted.size() > limit) {
            int from = "after".equals(direction) ? 0 : sorted.size() - limit;
            sorted = new java.util.ArrayList<>(sorted.subList(from, from + limit));
        }
        if ("desc".equals(order)) java.util.Collections.reverse(sorted);
        JSONArray data = new JSONArray();
        long minSeq = Long.MAX_VALUE, maxSeq = 0;
        for (HistRow row : sorted) {
                JSONObject item = row.item;
                item.put("post_type", "message");
                item.put("self_id", selfUin());
                if (group) {
                    item.put("group_id", peer);
                    attachGroupName(item);
                    if (item.optLong("user_id", 0) == 0) {
                        JSONObject sender = item.optJSONObject("sender");
                        if (sender != null) item.put("user_id", sender.optLong("user_id", 0));
                    }
                } else if (item.optLong("user_id", 0) == 0) {
                    item.put("user_id", peer);
                }
                JSONObject ev = Codec.toSatoriEvent(item, loginSlim(), 0, assetBase());
                JSONObject src = ev == null ? null : ev.optJSONObject("message");
                if (src == null) continue;
                JSONObject msg = copyJson(src);
                if (msg.optString("content", "").isEmpty() && !row.text.isEmpty())
                    msg.put("content", row.text);
                if (msg.optString("content", "").isEmpty()) {
                    String t = snapshotText(String.valueOf(row.msgId), store.getByMsgId(row.msgId));
                    if (!t.isEmpty()) msg.put("content", t);
                }
                if (row.time > 0) msg.put("created_at", row.time * 1000L);
                data.put(msg);
                if (row.seq > 0) { minSeq = Math.min(minSeq, row.seq); maxSeq = Math.max(maxSeq, row.seq); }
        }
        JSONObject out = new JSONObject().put("data", data);
        if (maxSeq > 0) {
            if ("around".equals(direction)) {
                out.put("prev", String.valueOf(minSeq));
                out.put("next", String.valueOf(maxSeq));
            } else {
                String cursor = String.valueOf("before".equals(direction) ? minSeq : maxSeq);
                out.put("prev", cursor).put("next", cursor);
            }
        }
        return out;
    }

    private JSONObject getMsgHistory(boolean group, long peer, long cursor, int count,
                                     boolean queryOrder, String direction) throws Exception {
        return group
                ? getGroupMsgHistory(peer, cursor, count, queryOrder, direction)
                : getFriendMsgHistory(peer, cursor, count, queryOrder, direction);
    }

    private static JSONArray mergeMessages(JSONArray first, JSONArray second, int limit) {
        JSONArray out = new JSONArray();
        java.util.HashSet<Long> seenSeq = new java.util.HashSet<>();
        JSONArray[] sources = new JSONArray[]{first, second};
        for (JSONArray source : sources) {
            if (source == null) continue;
            for (int i = 0; i < source.length() && out.length() < limit; i++) {
                JSONObject item = source.optJSONObject(i);
                if (item == null) continue;
                long seq = item.optLong("message_seq", 0);
                if (seq != 0 && !seenSeq.add(seq)) continue;
                out.put(item);
            }
        }
        return out;
    }

    private JSONObject satoriChannelGet(JSONObject p) throws Exception {
        String channelId = p.optString("channel_id", p.optString("guild_id", ""));
        if (Codec.isPrivateChannel(channelId))
            return Codec.channel(QQClient.CT_C2C, Codec.channelPeer(channelId), "");
        long gid = Codec.channelPeer(channelId);
        JSONObject g = groupInfoJson(gid);
        JSONObject ch = Codec.channel(QQClient.CT_GROUP, gid, g.optString("group_name"));
        ch.put("parent_id", String.valueOf(gid));
        return ch;
    }

    private JSONObject satoriChannelList(JSONObject p) throws Exception {
        long gid = guildIdOf(p);
        JSONObject g = groupInfoJson(gid);
        JSONArray data = new JSONArray().put(Codec.channel(QQClient.CT_GROUP, gid, g.optString("group_name")));
        return listWrap(data);
    }

    private JSONObject satoriChannelUpdate(JSONObject p) throws Exception {
        String channelId = p.optString("channel_id", "");
        if (Codec.isPrivateChannel(channelId))
            throw new ApiError(1400, "private channel name/avatar cannot be updated");
        long gid = Codec.channelPeer(channelId);
        JSONObject data = p.optJSONObject("data");
        if (data == null) data = new JSONObject();
        String name = data.optString("name", "").trim();
        String avatar = data.optString("avatar", "").trim();
        if (gid == 0) throw new ApiError(1400, "missing channel_id");
        if (name.isEmpty() && avatar.isEmpty())
            throw new ApiError(1400, "missing channel name or avatar");
        if (!name.isEmpty()) requireOp(qq.setGroupName(gid, name));
        if (!avatar.isEmpty()) {
            java.io.File file = resolveAvatarFile(avatar);
            requireOp(qq.setGroupHeader(gid, file.getAbsolutePath()));
        }
        if (name.isEmpty()) {
            try { name = groupInfoJson(gid).optString("group_name"); }
            catch (Exception ignore) { name = ""; }
        }
        emitGuildChannelChange("updated", gid, name);
        return new JSONObject();
    }

    /** Local path / file: / data: / http(s) / base64 / internal: upload spec. */
    private java.io.File resolveAvatarFile(String spec) throws Exception {
        if (spec == null) spec = "";
        spec = spec.trim();
        if (spec.isEmpty()) throw new ApiError(1400, "missing avatar");
        if (spec.startsWith("internal:")) spec = resolveInternalSpec(spec);
        java.io.File local = Media.resolve(spec, spec);
        if (local == null || !local.isFile())
            throw new ApiError(1400, "cannot resolve avatar");
        return local;
    }

    private void muteChannel(long guildId, long durationMs) {
        requireOp(qq.wholeBan(guildId, durationMs > 0));
        if (durationMs <= 0) {
            channelMuteDeadlines.remove(guildId);
            return;
        }
        long now = System.currentTimeMillis();
        long deadline = durationMs > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + durationMs;
        channelMuteDeadlines.put(guildId, deadline);
        Thread timer = new Thread(() -> {
            try {
                while (true) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    Thread.sleep(Math.min(remaining, 60_000L));
                }
                Long current = channelMuteDeadlines.get(guildId);
                if (current == null || current.longValue() != deadline) return;
                if (channelMuteDeadlines.remove(guildId, current)) {
                    guarded("channel.mute", () -> {
                        requireOp(qq.wholeBan(guildId, false));
                        return new JSONObject();
                    });
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                L.e("scheduled channel unmute " + guildId, t);
            }
        }, "satori-channel-unmute");
        timer.setDaemon(true);
        timer.start();
    }

    private JSONObject satoriGuildGet(JSONObject p) throws Exception {
        JSONObject g = groupInfoJson(guildIdOf(p));
        return Codec.guild(g.optLong("group_id"), g.optString("group_name"));
    }

    private JSONArray satoriGuildList() throws Exception {
        JSONArray src = getGroupList();
        JSONArray data = new JSONArray();
        for (int i = 0; i < src.length(); i++) {
            JSONObject g = src.optJSONObject(i);
            if (g == null) continue;
            data.put(Codec.guild(g.optLong("group_id"), g.optString("group_name")));
        }
        return data;
    }

    private JSONObject satoriMember(JSONObject p) throws Exception {
        long g = guildIdOf(p), u = parseId(p.optString("user_id", ""));
        return memberToSatori(getGroupMemberInfo(g, u));
    }

    private JSONArray satoriMemberList(JSONObject p) throws Exception {
        JSONArray src = getGroupMemberList(guildIdOf(p));
        JSONArray data = new JSONArray();
        for (int i = 0; i < src.length(); i++) {
            JSONObject m = src.optJSONObject(i);
            if (m != null) data.put(memberToSatori(m));
        }
        return data;
    }

    private JSONObject memberToSatori(JSONObject m) throws Exception {
        JSONObject user = Codec.user(m.optLong("user_id"), m.optString("nickname"), m.optString("card"));
        JSONObject out = new JSONObject();
        out.put("user", user);
        String card = m.optString("card", "");
        if (!card.isEmpty()) out.put("nick", card);
        String title = m.optString("title", "");
        if (!title.isEmpty()) {
            out.put("title", title);
            out.put("special_title", title);
        }
        long titleExpire = m.optLong("title_expire_time", 0);
        if (titleExpire != 0) out.put("title_expire_time", titleExpire);
        if (!card.isEmpty()) out.put("name", card);
        String role = m.optString("role", "member");
        JSONArray roles = new JSONArray().put(new JSONObject().put("id", role).put("name", role));
        out.put("roles", roles);
        long joined = m.optLong("join_time", 0);
        if (joined > 0) out.put("joined_at", joined * 1000);
        return out;
    }

    private JSONObject satoriUser(JSONObject p) throws Exception {
        JSONObject s = getStrangerInfo(parseId(p.optString("user_id", "")));
        return Codec.user(s.optLong("user_id"), s.optString("nickname"), "");
    }

    private JSONArray satoriFriendList() throws Exception {
        JSONArray src = getFriendList();
        JSONArray data = new JSONArray();
        for (int i = 0; i < src.length(); i++) {
            JSONObject f = src.optJSONObject(i);
            if (f == null) continue;
            JSONObject user = Codec.user(f.optLong("user_id"), f.optString("nickname"), f.optString("remark"));
            data.put(new JSONObject().put("user", user).put("nick", f.optString("remark")));
        }
        return data;
    }

    private JSONObject satoriGetForward(String id) throws Exception {
        if (id != null && id.startsWith("native:")) return satoriGetNativeForward(id);
        JSONObject ob = getForwardMsg(id);
        JSONArray messages = ob.optJSONArray("messages");
        JSONArray data = new JSONArray();
        if (messages != null) {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject node = messages.optJSONObject(i);
                JSONObject d = node == null ? null : node.optJSONObject("data");
                if (d == null) continue;
                Elements.El msg = Elements.empty("message");
                Elements.El author = Elements.empty("author");
                author.attrs.put("id", String.valueOf(d.opt("user_id")));
                author.attrs.put("name", d.optString("nickname", ""));
                msg.children.add(author);
                String content = Codec.fromSegments(d.optJSONArray("content"), assetBase());
                for (Elements.El el : Elements.parse(content)) msg.children.add(el);
                JSONObject m = new JSONObject()
                        .put("id", "")
                        .put("content", msg.toString(false))
                        .put("user", Codec.user(d.optLong("user_id"), d.optString("nickname"), ""));
                data.put(m);
            }
        }
        return new JSONObject().put("data", data);
    }

    private JSONObject satoriGetNativeForward(String id) throws Exception {
        long kernelMsgId = parseLongQuiet(id.substring("native:".length()));
        if (kernelMsgId == 0) throw new ApiError(1400, "invalid native forward id");
        MsgStore.Rec parent = store.getByMsgId(kernelMsgId);
        if (parent == null) throw new ApiError(1404, "native forward is no longer cached");
        String peer = parent.peerUid;
        if ((peer == null || peer.isEmpty()) && parent.chatType == QQClient.CT_GROUP)
            peer = String.valueOf(parent.peerUin);
        QQClient.MsgListResult result = qq.getMultiMsg(parent.chatType, peer, kernelMsgId);
        if (!result.ok()) throw new ApiError(1500, "get native forward failed: " + result.describe());
        if (result.records == null || result.records.isEmpty())
            throw new ApiError(1404, "native forward is empty");
        JSONArray data = new JSONArray();
        for (Object record : result.records) {
            JSONObject ob = conv.recordToEvent(record, 0);
            if (ob == null) continue;
            JSONObject event = Codec.toSatoriEvent(ob.put("post_type", "message")
                    .put("self_id", selfUin()), loginSlim(), 0, assetBase());
            if (event != null && event.optJSONObject("message") != null)
                data.put(event.optJSONObject("message"));
        }
        if (data.length() == 0) throw new ApiError(1404, "native forward has no readable messages");
        return new JSONObject().put("data", data);
    }

    private JSONObject listWrap(JSONArray data) throws Exception {
        return new JSONObject().put("data", data == null ? new JSONArray() : data);
    }

    private long guildIdOf(JSONObject p) {
        long g = parseId(p.optString("guild_id", ""));
        if (g == 0) g = parseId(p.optString("channel_id", ""));
        if (g == 0) throw new ApiError(1400, "missing guild_id");
        return g;
    }

    private MsgStore.Rec requireMessage(String raw) {
        return requireMessage(raw, null);
    }

    private MsgStore.Rec requireMessage(String raw, JSONObject p) {
        MsgStore.Rec rec = store.resolve(raw);
        if (rec == null) rec = fetchMessage(raw, p);
        if (rec == null) {
            boolean missing = raw == null || raw.trim().isEmpty() || parseLongQuiet(raw) == 0;
            throw new ApiError(missing ? 1400 : 1404,
                    missing ? "missing message_id" : "message not found: " + raw);
        }
        return rec;
    }

    private MsgStore.Rec fetchMessage(String raw, JSONObject p) {
        if (p == null) return null;
        long msgId = parseLongQuiet(raw);
        String channelId = p.optString("channel_id", "");
        if (msgId == 0 || channelId.isEmpty()) return null;
        boolean group = !Codec.isPrivateChannel(channelId);
        long peer = Codec.channelPeer(channelId);
        if (peer == 0) return null;
        try {
            String peerUid = group ? String.valueOf(peer) : uidFor(0, peer);
            Object rec = qq.fetchRecord(group ? QQClient.CT_GROUP : QQClient.CT_C2C, peerUid, msgId);
            if (rec == null) return null;
            conv.recordToEvent(rec, 0);
            return store.getByMsgId(msgId);
        } catch (Exception e) {
            return null;
        }
    }

    private void validateMessageChannel(JSONObject p, int messageId) {
        String channelId = p.optString("channel_id", "");
        if (channelId.isEmpty()) return; // accepted for older local callers
        MsgStore.Rec rec = store.get(messageId);
        if (rec == null) return;
        boolean privateChannel = Codec.isPrivateChannel(channelId);
        long peer = Codec.channelPeer(channelId);
        boolean matchesType = privateChannel ? rec.chatType == QQClient.CT_C2C
                : rec.chatType == QQClient.CT_GROUP;
        if (peer == 0 || !matchesType || (rec.peerUin != 0 && rec.peerUin != peer))
            throw new ApiError(1404, "message not found in channel: " + channelId);
    }

    private static long parseId(String raw) { return parseLongQuiet(raw); }

    private static long parseEmoji(String raw) {
        if (raw == null || raw.isEmpty()) throw new ApiError(1400, "missing emoji_id");
        try { return Long.parseLong(raw.trim()); } catch (Exception e) {
            return raw.codePointAt(0);
        }
    }

    private static long parseLongQuiet(String s) {
        try { return s == null || s.isEmpty() ? 0 : Long.parseLong(s.trim()); }
        catch (Exception e) { return 0; }
    }

    /** Resolve an opaque file id learned from an incoming segment to a local path and/or source URL. */
    private JSONObject getResource(JSONObject p, String expectedType) throws Exception {
        String id = p.optString("file", p.optString("file_id", p.optString("id", ""))).trim();
        if (id.isEmpty()) throw new ApiError(1400, "missing file/file_id");
        boolean registered = true;
        MsgStore.Resource resource = store.getResource(id);
        if (resource == null) {
            registered = false;
            // Also accept a direct local path or URL for compatibility with clients that retain segment data.
            resource = new MsgStore.Resource();
            resource.id = id;
            resource.type = expectedType == null ? "file" : expectedType;
            if (id.startsWith("http://") || id.startsWith("https://")) resource.url = id;
            else resource.path = id.startsWith("file://") ? id.substring(7) : id;
        }
        if (expectedType != null && resource.type != null && !expectedType.equals(resource.type))
            throw new ApiError(1400, "resource type is " + resource.type + ", expected " + expectedType);

        // Prefer an existing local file, then QQ's authenticated kernel downloader. Old qpic URLs
        // frequently expire or stall, so direct HTTP is deliberately the final fallback.
        java.io.File local = com.satori.qq.qq.Media.resolve(resource.path, "");
        if (local == null && resource.msgId != 0 && qq.isOnline()) {
            String downloaded = qq.downloadRichMedia(
                    resource.chatType, resource.peerUid, resource.msgId, resource.elementId,
                    resource.fileModelId);
            if (!downloaded.isEmpty()) {
                local = new java.io.File(downloaded);
                resource.path = downloaded;
                resource.size = local.length();
            }
        }
        if (local == null && "video".equals(resource.type) && resource.msgId != 0 && qq.isOnline()) {
            String play = qq.getVideoPlayUrl(
                    resource.chatType, resource.peerUid, resource.msgId, resource.elementId);
            if (!play.isEmpty()) {
                resource.url = play;
                local = com.satori.qq.qq.Media.resolve("", play);
                if (local != null) resource.size = local.length();
            }
        }
        if (local == null && resource.url != null && !resource.url.isEmpty())
            local = com.satori.qq.qq.Media.resolve("", resource.url);
        if (local == null && (resource.url == null || resource.url.isEmpty())) {
            String why = !registered ? "unregistered"
                    : resource.msgId == 0 ? "no download context" : "download failed";
            throw new ApiError(1404, "resource unavailable (" + why + ")");
        }

        JSONObject out = new JSONObject()
                .put("resource_id", resource.id)
                .put("resource_type", resource.type == null ? "file" : resource.type)
                .put("file_name", resource.name == null ? "" : resource.name)
                .put("file_size", local != null ? local.length() : resource.size);
        if (local != null && "record".equals(expectedType)) {
            String format = p.optString("out_format", p.optString("outFormat", "")).trim();
            if (!format.isEmpty()) {
                java.io.File converted = Media.convertRecord(qq.ref, local, format);
                if (converted == null)
                    throw new ApiError(1400, "unsupported or failed out_format: " + format);
                local = converted;
                String produced = converted.getName();
                int dot = produced.lastIndexOf('.');
                out.put("out_format", dot >= 0 ? produced.substring(dot + 1) : format);
                out.put("file_size", local.length());
            }
        }
        if (local != null) out.put("file", local.getAbsolutePath());
        else out.put("file", resource.url);
        if (resource.url != null && !resource.url.isEmpty()) out.put("url", resource.url);
        return out;
    }

    /**
     * Merge-forward: Android QQNT only opens cards created by kernel {@code multiForwardMsg}
     * from real local messages. Fake SsoSendLongMsg + type-16/13/ark is fallback only.
     * Exactly one of groupId/userId is non-zero.
     */
    private JSONObject sendForward(long groupId, long userId, Object messages) throws Exception {
        if (groupId == 0 && userId == 0) throw new ApiError(1400, "missing group_id/user_id");
        List<LongMsg.Node> nodes = parseForwardNodes(messages, groupId != 0);
        if (nodes.isEmpty()) throw new ApiError(1400, "empty forward messages");
        JSONObject nativeSent = sendForwardNative(groupId, userId, messages);
        if (nativeSent != null) return nativeSent;
        String selfUid = "";
        if (groupId == 0) {
            selfUid = qq.resolveUid(selfUin());
            if (selfUid == null || selfUid.isEmpty()) throw new ApiError(1500, "cannot resolve self uid");
        } else {
            String maybe = qq.resolveUid(selfUin());
            if (maybe != null) selfUid = maybe;
        }
        String fileName = java.util.UUID.randomUUID().toString();
        byte[] req = LongMsg.buildUploadReq(groupId, selfUid, nodes, fileName);
        PacketSvc.Result r = qq.packets().sendSso(LongMsg.CMD, req);
        if (!r.ok()) throw new ApiError(1500, "forward upload failed: " + r.describe());
        String resId = LongMsg.parseResId(r.body);
        if (resId == null || resId.isEmpty()) throw new ApiError(1500, "forward upload: no resId in reply");
        LongMsg.Card card = LongMsg.buildCard(resId, nodes, groupId != 0, fileName);
        JSONObject sent = sendForwardElements(groupId, userId,
                conv.toMultiForward(card.json, resId, card.fileName), "type16");
        if (sent == null) {
            sent = sendForwardElements(groupId, userId,
                    conv.toStructLongMsg(card.xml, resId), "type13");
        }
        if (sent == null) sent = sendForwardArk(groupId, userId, card.json);
        return sent.put("res_id", resId).put("forward_id", resId).put("filename", fileName);
    }

    /**
     * Android QQNT opens merge-forward via kernel getMultiMsg(msgId). That only works for cards
     * created by {@code multiForwardMsg} from real local messages, not fake SsoSendLongMsg ark/16.
     */
    private JSONObject sendForwardNative(long groupId, long userId, Object messages)
            throws Exception {
        int chatType = groupId != 0 ? QQClient.CT_GROUP : QQClient.CT_C2C;
        String peer;
        if (groupId != 0) {
            peer = String.valueOf(groupId);
        } else {
            peer = store.uidOf(userId);
            if (peer == null || peer.isEmpty()) peer = qq.resolveUid(userId);
            if (peer != null && !peer.isEmpty()) store.learnUid(userId, peer);
            if (peer == null || peer.isEmpty()) return null;
        }
        java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        JSONArray arr = messages instanceof JSONArray ? (JSONArray) messages : new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject seg = arr.optJSONObject(i);
            JSONObject d = seg == null ? null : seg.optJSONObject("data");
            if (d == null) continue;
            Object content = d.opt("content");
            if (content == null) content = "";
            java.util.ArrayList<Object> els = conv.toElements(content, chatType);
            if (els == null || els.isEmpty()) continue;
            QQClient.SendResult sr = sendTracked(chatType, peer, els);
            if (sr.code != 0 || sr.msgId == 0) {
                L.e("native forward inner send failed code=" + sr.code + " " + sr.msg, null);
                return null;
            }
            ids.add(sr.msgId);
            String name = d.optString("nickname", d.optString("name", ""));
            names.add(name);
            afterSend(sr, chatType, groupId != 0 ? groupId : userId,
                    groupId != 0 ? String.valueOf(groupId) : peer);
        }
        if (ids.isEmpty()) return null;
        QQClient.SendResult fw = qq.multiForward(chatType, peer, ids, names);
        if (fw.code != 0) {
            L.e("multiForwardMsg failed code=" + fw.code + " " + fw.msg, null);
            return null;
        }
        JSONObject found = null;
        for (int attempt = 0; attempt < 10 && found == null; attempt++) {
            try { Thread.sleep(attempt == 0 ? 800 : 500); } catch (InterruptedException ignore) {}
            found = findNativeForward(chatType, peer, groupId, userId, ids);
        }
        if (found != null) return found;
        L.e("multiForward card not in history yet inners=" + ids.size(), null);
        return new JSONObject().put("native_forward", true).put("message_id", 0);
    }

    /** Newest merge-forward in history that is not one of the inner scaffolding msgIds. */
    private JSONObject findNativeForward(int chatType, String peer, long groupId, long userId,
                                         java.util.ArrayList<Long> innerIds) {
        QQClient.MsgListResult hist = qq.getMsgs(chatType, peer, 0, 20);
        if (hist.records == null) return null;
        for (int i = hist.records.size() - 1; i >= 0; i--) {
            Object rec = hist.records.get(i);
            long msgId = 0;
            try { msgId = Ref.asLong(qq.ref.get(rec, "msgId")); } catch (Throwable ignore) {}
            if (msgId != 0 && innerIds.contains(msgId)) continue;
            // self=0: own cards must not be dropped (live listener still skips self).
            JSONObject ev = conv.recordToEvent(rec, 0);
            if (ev == null) continue;
            JSONArray msg = ev.optJSONArray("message");
            if (msg == null) continue;
            JSONObject data = null;
            for (int j = 0; j < msg.length(); j++) {
                JSONObject s = msg.optJSONObject(j);
                if (s != null && "forward".equals(s.optString("type"))) {
                    data = s.optJSONObject("data");
                    break;
                }
            }
            if (data == null) continue;
            MsgStore.Rec stored = new MsgStore.Rec();
            stored.chatType = chatType;
            stored.peerUin = groupId != 0 ? groupId : userId;
            stored.peerUid = groupId != 0 ? "" : peer;
            stored.msgId = msgId;
            stored.senderUin = selfUin();
            stored.msgRecord = rec;
            int obId = store.put(stored);
            JSONObject out;
            try {
                out = new JSONObject().put("message_id", obId).put("native_forward", true);
                if (data.has("id") && !data.optString("id").isEmpty()) {
                    out.put("res_id", data.optString("id")).put("forward_id", data.optString("id"));
                }
                if (data.has("filename")) out.put("filename", data.optString("filename"));
                if (data.has("element_type")) out.put("element_type", data.optInt("element_type"));
            } catch (Exception e) {
                return null;
            }
            if (msgId != 0) qq.prefetchForward(chatType, peer, msgId);
            return out;
        }
        return null;
    }

    private JSONObject sendForwardElements(long groupId, long userId, java.util.ArrayList<Object> els,
                                           String label) throws Exception {
        if (els == null || els.isEmpty()) {
            L.e("multiForward " + label + " element empty", null);
            return null;
        }
        if (groupId != 0) {
            QQClient.SendResult sr = sendTracked(QQClient.CT_GROUP, String.valueOf(groupId), els);
            if (sr.code == 0) {
                JSONObject sent = afterSend(sr, QQClient.CT_GROUP, groupId, "");
                qq.prefetchForward(QQClient.CT_GROUP, String.valueOf(groupId), sr.msgId);
                return sent;
            }
            L.e("multiForward " + label + " send failed code=" + sr.code + " " + sr.msg, null);
            return null;
        }
        String uid = store.uidOf(userId);
        if (uid == null || uid.isEmpty()) uid = qq.resolveUid(userId);
        if (uid != null && !uid.isEmpty()) store.learnUid(userId, uid);
        if (uid == null || uid.isEmpty())
            throw new ApiError(1404, "cannot resolve uid for user " + userId);
        QQClient.SendResult sr = sendTracked(QQClient.CT_C2C, uid, els);
        if (sr.code == 0) {
            JSONObject sent = afterSend(sr, QQClient.CT_C2C, userId, uid);
            qq.prefetchForward(QQClient.CT_C2C, uid, sr.msgId);
            return sent;
        }
        L.e("multiForward " + label + " send failed code=" + sr.code + " " + sr.msg, null);
        return null;
    }

    private JSONObject sendForwardArk(long groupId, long userId, String cardJson) throws Exception {
        JSONArray msg = new JSONArray().put(new JSONObject().put("type", "json")
                .put("data", new JSONObject().put("data", cardJson)));
        return groupId != 0 ? sendGroup(groupId, msg) : sendPrivate(userId, msg);
    }

    /** get_forward_msg: download a merged-forward by res_id via SsoRecvLongMsg, return its nodes. */
    private JSONObject getForwardMsg(String resId) throws Exception {
        if (resId == null || resId.isEmpty()) throw new ApiError(1400, "missing id (forward res_id)");
        String selfUid = qq.resolveUid(selfUin());
        if (selfUid == null || selfUid.isEmpty()) throw new ApiError(1500, "cannot resolve self uid");
        PacketSvc.Result r = qq.packets().sendSso(LongMsg.RECV_CMD, LongMsg.buildDownloadReq(selfUid, resId));
        if (!r.ok()) throw new ApiError(1500, "get_forward_msg failed: " + r.describe());
        List<LongMsg.Node> nodes = LongMsg.parseDownload(r.body);
        if (nodes.isEmpty()) throw new ApiError(1404, "forward not found or empty: " + resId);
        JSONArray messages = new JSONArray();
        for (LongMsg.Node n : nodes) {
            JSONArray content = segsToContent(n);
            messages.put(new JSONObject().put("type", "node").put("data", new JSONObject()
                    .put("user_id", n.senderUin)
                    .put("nickname", n.senderName == null ? "" : n.senderName)
                    .put("time", n.time)
                    .put("content", content)));
        }
        return new JSONObject().put("messages", messages);
    }

    private JSONArray segsToContent(LongMsg.Node n) throws Exception {
        JSONArray content = new JSONArray();
        if (n.segs.isEmpty()) {
            content.put(new JSONObject().put("type", "text")
                    .put("data", new JSONObject().put("text", n.text == null ? "" : n.text)));
            return content;
        }
        for (LongMsg.Seg s : n.segs) {
            JSONObject data = new JSONObject();
            switch (s.type) {
                case "at":
                    data.put("qq", s.qq == null || s.qq.isEmpty() ? "all" : s.qq);
                    if (s.text != null && !s.text.isEmpty()) data.put("name", s.text);
                    content.put(new JSONObject().put("type", "at").put("data", data));
                    break;
                case "face":
                    content.put(new JSONObject().put("type", "face")
                            .put("data", new JSONObject().put("id", s.id)));
                    break;
                case "reply":
                    content.put(new JSONObject().put("type", "reply")
                            .put("data", new JSONObject().put("id", s.id)));
                    break;
                case "image": {
                    String id = s.file == null ? "" : s.file;
                    if (!id.isEmpty()) {
                        store.putResource("image", id, "", s.url, s.name, s.size);
                    }
                    data.put("file", id);
                    if (s.url != null && !s.url.isEmpty()) data.put("url", s.url);
                    if (s.size > 0) data.put("file_size", s.size);
                    content.put(new JSONObject().put("type", "image").put("data", data));
                    break;
                }
                case "file": {
                    String id = s.file == null ? "" : s.file;
                    if (!id.isEmpty()) {
                        store.putResource("file", id, "", s.url, s.name, s.size);
                    }
                    data.put("file", id);
                    data.put("file_id", id);
                    if (s.name != null && !s.name.isEmpty()) data.put("name", s.name);
                    if (s.size > 0) data.put("file_size", s.size);
                    if (s.busid > 0) data.put("busid", s.busid);
                    content.put(new JSONObject().put("type", "file").put("data", data));
                    break;
                }
                default:
                    content.put(new JSONObject().put("type", "text")
                            .put("data", new JSONObject().put("text", s.text == null ? "" : s.text)));
                    break;
            }
        }
        return content;
    }

    private List<LongMsg.Node> parseForwardNodes(Object messages, boolean group) {
        List<LongMsg.Node> out = new java.util.ArrayList<>();
        if (!(messages instanceof JSONArray)) return out;
        JSONArray arr = (JSONArray) messages;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject seg = arr.optJSONObject(i);
            if (seg == null) continue;
            JSONObject d = seg.optJSONObject("data");
            if (d == null) continue;
            String type = seg.optString("type", "node");
            if (!type.isEmpty() && !"node".equals(type)) continue;
            LongMsg.Node n = new LongMsg.Node();
            n.senderUin = parseNodeUin(d);
            n.senderName = d.optString("name", d.optString("nickname", String.valueOf(n.senderUin)));
            n.time = d.optLong("time", 0);
            Object content = d.opt("content");
            n.text = extractText(content);
            encodeForwardContent(n, content, group);
            out.add(n);
        }
        return out;
    }

    /** Encode node content into im_msg_body Elems (text/at/face/reply/image/file). */
    private void encodeForwardContent(LongMsg.Node n, Object content, boolean group) {
        if (content instanceof String) {
            n.elems.add(LongMsg.elemText((String) content));
            return;
        }
        if (!(content instanceof JSONArray)) {
            if (n.text != null && !n.text.isEmpty()) n.elems.add(LongMsg.elemText(n.text));
            return;
        }
        JSONArray a = (JSONArray) content;
        for (int i = 0; i < a.length(); i++) {
            JSONObject s = a.optJSONObject(i);
            if (s == null) {
                String t = a.optString(i);
                if (!t.isEmpty()) n.elems.add(LongMsg.elemText(t));
                continue;
            }
            String type = s.optString("type", "text");
            JSONObject d = s.optJSONObject("data");
            if (d == null) d = new JSONObject();
            switch (type) {
                case "text":
                    n.elems.add(LongMsg.elemText(d.optString("text", "")));
                    break;
                case "at": {
                    String atQq = d.optString("qq", "");
                    boolean all = "all".equalsIgnoreCase(atQq);
                    long uin = all ? 0 : parseLongQuiet(atQq);
                    String uid = all ? "" : store.uidOf(uin);
                    if (!all && (uid == null || uid.isEmpty()) && uin != 0) {
                        try { uid = this.qq.resolveUid(uin); } catch (Exception ignore) { uid = ""; }
                        if (uid != null && !uid.isEmpty()) store.learnUid(uin, uid);
                    }
                    String display = d.optString("name", all ? "@全体成员" : ("@" + atQq));
                    n.elems.add(LongMsg.elemAt(display, all, uin, uid == null ? "" : uid));
                    break;
                }
                case "face":
                    n.elems.add(LongMsg.elemFace((int) parseLongQuiet(d.optString("id", "0"))));
                    break;
                case "reply": {
                    String id = d.optString("id", "");
                    MsgStore.Rec rec = store.get((int) parseLongQuiet(id));
                    long seq = rec != null ? rec.msgSeq : parseLongQuiet(id);
                    long uin = rec != null ? rec.senderUin : 0;
                    long msgId = rec != null ? rec.msgId : 0;
                    String uid = rec != null ? rec.senderUid : "";
                    n.elems.add(LongMsg.elemReply(seq, uin, 0, msgId, uid, "[回复]"));
                    break;
                }
                case "image": {
                    LongMsg.Pic pic = imageForForward(d, group);
                    n.elems.add(LongMsg.elemImage(pic));
                    break;
                }
                case "file": {
                    LongMsg.FileRef file = fileForForward(d);
                    n.elems.add(LongMsg.elemFile(file));
                    break;
                }
                default:
                    n.elems.add(LongMsg.elemText(extractText(new JSONArray().put(s))));
                    break;
            }
        }
        if (n.elems.isEmpty()) n.elems.add(LongMsg.elemText(n.text == null ? "" : n.text));
    }

    private LongMsg.Pic imageForForward(JSONObject d, boolean group) {
        LongMsg.Pic pic = new LongMsg.Pic();
        pic.group = group;
        String spec = d.optString("file", d.optString("file_id", ""));
        String url = d.optString("url", "");
        MsgStore.Resource res = store.getResource(spec);
        if (res != null) {
            if (url.isEmpty() && res.url != null) url = res.url;
            pic.size = (int) res.size;
            pic.fileName = res.name == null ? "" : res.name;
            pic.md5 = LongMsg.md5Hex(res.id);
            if (pic.md5 == null) pic.md5 = LongMsg.md5Hex(res.name);
        }
        if (pic.md5 == null) pic.md5 = LongMsg.md5Hex(spec);
        java.io.File local = Media.resolve(spec, url);
        if (local == null && res != null) local = Media.resolve(res.path, res.url);
        if (local != null) {
            if (pic.md5 == null) pic.md5 = LongMsg.md5Of(local);
            pic.size = (int) Math.min(Integer.MAX_VALUE, local.length());
            if (pic.fileName.isEmpty()) pic.fileName = local.getName();
            try {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(local.getAbsolutePath(), o);
                if (o.outWidth > 0) pic.width = o.outWidth;
                if (o.outHeight > 0) pic.height = o.outHeight;
            } catch (Throwable ignore) {}
        }
        pic.origUrl = url;
        if (pic.md5 == null) pic.md5 = new byte[0];
        return pic;
    }

    private LongMsg.FileRef fileForForward(JSONObject d) {
        LongMsg.FileRef f = new LongMsg.FileRef();
        f.fileId = firstNonEmpty(d.optString("file_id", ""), d.optString("file", ""));
        f.name = d.optString("name", "");
        f.size = d.optLong("file_size", d.optLong("size", 0));
        f.busId = d.optInt("busid", d.optInt("bus_id", 102));
        MsgStore.Resource res = store.getResource(f.fileId);
        if (res != null) {
            if (f.name.isEmpty() && res.name != null) f.name = res.name;
            if (f.size == 0) f.size = res.size;
            f.md5 = LongMsg.md5Hex(res.id);
        }
        if (f.md5 == null) f.md5 = LongMsg.md5Hex(f.fileId);
        return f;
    }

    /** Flatten node content (string or segment array) to display text for a forward node. */
    private String extractText(Object content) {
        if (content == null) return "";
        if (content instanceof String) return (String) content;
        if (!(content instanceof JSONArray)) return String.valueOf(content);
        StringBuilder sb = new StringBuilder();
        JSONArray a = (JSONArray) content;
        for (int i = 0; i < a.length(); i++) {
            JSONObject s = a.optJSONObject(i);
            if (s == null) { sb.append(a.optString(i)); continue; }
            String t = s.optString("type", "");
            JSONObject sd = s.optJSONObject("data");
            switch (t) {
                case "text": sb.append(sd == null ? "" : sd.optString("text", "")); break;
                case "at": sb.append(sd == null ? "" : sd.optString("name",
                        "@" + sd.optString("qq", ""))); break;
                case "face": sb.append("[表情]"); break;
                case "image": sb.append("[图片]"); break;
                case "file": sb.append("[文件]"); break;
                case "reply": sb.append("[回复]"); break;
                case "json": case "lightapp": sb.append("[卡片]"); break;
                default: break;
            }
        }
        return sb.toString();
    }

    /** Build a one-segment `file` message from upload_*_file params (file/url + name). */
    private JSONArray fileSeg(JSONObject p) throws Exception {
        JSONObject data = new JSONObject()
                .put("file", p.optString("file", p.optString("url", "")))
                .put("name", p.optString("name", ""));
        return new JSONArray().put(new JSONObject().put("type", "file").put("data", data));
    }

    /**
     * Chat-channel file send, then poll the group filesystem for the real file_id.
     * Chat uploads land in the root; a non-root folder moves after the id is known.
     */
    private JSONObject uploadGroupFile(JSONObject p) throws Exception {
        long groupId = p.optLong("group_id", 0);
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        String name = p.optString("name", "").trim();
        if (name.isEmpty()) {
            String file = p.optString("file", p.optString("url", ""));
            int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
            name = slash >= 0 ? file.substring(slash + 1) : file;
        }
        if (name.isEmpty()) throw new ApiError(1400, "missing name");
        String folder = firstNonEmpty(p.optString("folder_id", ""), p.optString("folder", "/"));
        if (folder == null || folder.isEmpty()) folder = "/";
        JSONObject sent = sendGroup(groupId, fileSeg(p));
        JSONObject found = waitGroupFile(groupId, "/", name);
        if (found == null)
            throw new ApiError(1500, "uploaded file did not appear in group file system");
        if (!"/".equals(folder)) {
            moveGroupFile(groupId, found.optString("file_id"), "/", folder,
                    found.optInt("busid", found.optInt("bus_id", 0)));
            found.put("parent_id", folder);
        }
        found.put("message_id", sent.optInt("message_id"));
        return found;
    }

    private JSONObject uploadPrivateFile(JSONObject p) throws Exception {
        long userId = p.optLong("user_id", 0);
        if (userId == 0) throw new ApiError(1400, "missing user_id");
        String name = p.optString("name", "").trim();
        if (name.isEmpty()) {
            String file = p.optString("file", p.optString("url", ""));
            int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
            name = slash >= 0 ? file.substring(slash + 1) : file;
        }
        JSONObject sent = sendPrivate(userId, fileSeg(p));
        int obId = sent.optInt("message_id");
        MsgStore.Rec rec = store.get(obId);
        JSONObject found = rec == null ? null
                : waitPrivateFile(rec.chatType, rec.peerUid, rec.msgId, name);
        if (found == null) return sent;
        found.put("message_id", obId);
        return found;
    }

    private JSONObject waitPrivateFile(int chatType, String peerUid, long msgId, String name)
            throws Exception {
        if (msgId == 0 || peerUid == null || peerUid.isEmpty()) return null;
        final int attempts = 8;
        final long delayMs = 1500L;
        for (int i = 0; i < attempts; i++) {
            if (i > 0) Thread.sleep(delayMs);
            QQClient.MsgListResult hist = qq.getMsgsByMsgId(chatType, peerUid, msgId);
            if (!hist.ok() || hist.records.isEmpty()) continue;
            for (Object rec : hist.records) {
                JSONObject ev = conv.recordToEvent(rec, 0);
                if (ev == null) continue;
                JSONArray segs = ev.optJSONArray("message");
                if (segs == null) continue;
                for (int j = 0; j < segs.length(); j++) {
                    JSONObject seg = segs.optJSONObject(j);
                    if (seg == null || !"file".equals(seg.optString("type"))) continue;
                    JSONObject d = seg.optJSONObject("data");
                    if (d == null) continue;
                    String id = firstNonEmpty(d.optString("file_id", ""), d.optString("file", ""));
                    if (id.isEmpty()) continue;
                    JSONObject out = new JSONObject()
                            .put("file_id", id)
                            .put("file", id)
                            .put("file_name", d.optString("name", name))
                            .put("file_size", d.optLong("file_size", d.optLong("size", 0)));
                    return out;
                }
            }
        }
        return null;
    }

    private JSONObject waitGroupFile(long groupId, String folderId, String name) throws Exception {
        final int attempts = 8;
        final long delayMs = 1500L;
        for (int i = 0; i < attempts; i++) {
            if (i > 0) Thread.sleep(delayMs);
            JSONObject listed = getGroupFiles(groupId, folderId);
            JSONArray files = listed.optJSONArray("files");
            if (files == null) continue;
            for (int j = 0; j < files.length(); j++) {
                JSONObject file = files.optJSONObject(j);
                if (file != null && name.equals(file.optString("file_name"))) return file;
            }
        }
        return null;
    }

    private JSONObject sendGroup(long groupId, Object message) throws Exception {
        return sendGroup(groupId, message, "");
    }

    private JSONObject sendGroup(long groupId, Object message, String content) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        if (looksLikeForward(message)) return sendForward(groupId, 0, message);
        java.util.ArrayList<Object> els = conv.toElements(message, QQClient.CT_GROUP);
        QQClient.SendResult r = sendTracked(QQClient.CT_GROUP, String.valueOf(groupId), els);
        return afterSend(r, QQClient.CT_GROUP, groupId, String.valueOf(groupId), content);
    }

    private JSONObject sendPrivate(long userId, Object message) throws Exception {
        return sendPrivate(userId, message, "");
    }

    private JSONObject sendPrivate(long userId, Object message, String content) throws Exception {
        if (userId == 0) throw new ApiError(1400, "missing user_id");
        if (looksLikeForward(message)) return sendForward(0, userId, message);
        String uid = store.uidOf(userId);
        if (uid == null || uid.isEmpty()) {
            uid = qq.resolveUid(userId);            // resolve uin -> uid via profile service
            if (uid != null && !uid.isEmpty()) store.learnUid(userId, uid);
        }
        if (uid == null || uid.isEmpty())
            throw new ApiError(1404, "cannot resolve uid for user " + userId);
        java.util.ArrayList<Object> els = conv.toElements(message, QQClient.CT_C2C);
        QQClient.SendResult r = sendTracked(QQClient.CT_C2C, uid, els);
        return afterSend(r, QQClient.CT_C2C, userId, uid, content);
    }

    /**
     * ayjx (and go-cqhttp-style clients) send merge-forward as {@code send_msg}
     * whose message array is {@code node} segments, not {@code send_*_forward_msg}.
     */
    static boolean looksLikeForward(Object message) {
        if (!(message instanceof JSONArray)) return false;
        JSONArray arr = (JSONArray) message;
        boolean anyNode = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject seg = arr.optJSONObject(i);
            if (seg == null) continue;
            String type = seg.optString("type", "");
            if ("node".equals(type)) anyNode = true;
            else if (!type.isEmpty()) return false;
        }
        return anyNode;
    }

    /** ayjx {@code node_custom} serializes user_id as a JSON string. */
    private long parseNodeUin(JSONObject d) {
        long uin = d.optLong("uin", d.optLong("user_id", 0));
        if (uin != 0) return uin;
        String raw = d.optString("uin", d.optString("user_id", ""));
        if (!raw.isEmpty()) {
            try { return Long.parseLong(raw.trim()); } catch (NumberFormatException ignored) {}
        }
        return selfUin();
    }

    private QQClient.SendResult sendTracked(int chatType, String peer, java.util.ArrayList<Object> els) {
        outboundEcho.incrementAndGet();
        try {
            QQClient.SendResult r = qq.sendMsg(chatType, peer, els);
            if (r != null && r.msgId != 0) seen.add(r.msgId);
            return r;
        } finally {
            outboundEcho.decrementAndGet();
        }
    }

    private JSONObject afterSend(QQClient.SendResult r, int chatType, long peerUin, String peerUid)
            throws Exception {
        return afterSend(r, chatType, peerUin, peerUid, "");
    }

    private JSONObject afterSend(QQClient.SendResult r, int chatType, long peerUin, String peerUid,
                                 String content) throws Exception {
        if (r.code != 0) throw new ApiError(1500, "send failed (code=" + r.code + "): " + r.msg);
        String peer = (peerUid != null && !peerUid.isEmpty()) ? peerUid : String.valueOf(peerUin);
        Object fetched = null;
        try {
            fetched = qq.fetchRecord(chatType, peer, r.msgId);
            qq.dumpSent(chatType, peer, r.msgId);
        } catch (Throwable t) {
            L.e("dumpSent", t);
        }
        MsgStore.Rec rec = new MsgStore.Rec();
        rec.chatType = chatType; rec.peerUin = peerUin; rec.peerUid = peer;
        rec.msgId = r.msgId; rec.senderUin = selfUin();
        rec.msgRecord = fetched;
        if (fetched != null)
            rec.msgSeq = qq.ref.getLong(Convert.unwrapRecord(fetched), "msgSeq");
        rec.content = content == null ? "" : content;
        int id = store.put(rec);
        JSONObject out = new JSONObject().put("store_id", id);
        if (r.msgId != 0) {
            out.put("message_id", String.valueOf(r.msgId));
            out.put("qq_msg_id", r.msgId);
        } else {
            out.put("message_id", id);
        }
        return out;
    }

    private void recall(int messageId) throws Exception {
        MsgStore.Rec r = store.get(messageId);
        if (r == null) throw new ApiError(1404, "message not found: " + messageId);
        Object msgService = qq.getMsgService();
        if (msgService == null) throw new ApiError(1500, "kernel not ready");
        String peer = r.peerUid == null || r.peerUid.isEmpty() ? String.valueOf(r.peerUin) : r.peerUid;
        Object contact = qq.ref.neu(QQClient.CONTACT, r.chatType, peer, "");
        java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
        ids.add(r.msgId);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final int[] code = new int[]{-1};
        final String[] wording = new String[]{""};
        Object cb = java.lang.reflect.Proxy.newProxyInstance(qq.ref.cl,
            new Class[]{qq.ref.cls(QQClient.IOPERATE_CB)}, (proxy, m, args) -> {
                if ("onResult".equals(m.getName()) && args != null && args.length >= 1) {
                    code[0] = Ref.asInt(args[0]);
                    if (args.length >= 2) wording[0] = Ref.asStr(args[1]);
                    latch.countDown();
                }
                return null;
            });
        qq.ref.call(msgService, "recallMsg", contact, ids, cb);
        if (!latch.await(15, java.util.concurrent.TimeUnit.SECONDS))
            throw new ApiError(1500, "recall timeout");
        if (code[0] != 0) throw new ApiError(1500, "recall failed: code=" + code[0] + " " + wording[0]);
        emitRecall(r.chatType == QQClient.CT_GROUP, r.peerUin, r.senderUin, selfUin(), r.id, r.msgId);
    }

    private void emitRecall(boolean group, long peer, long user, long operator, int storeId) {
        emitRecall(group, peer, user, operator, storeId, 0);
    }

    private void emitRecall(boolean group, long peer, long user, long operator, int storeId, long qqMsgId) {
        if (qqMsgId == 0 && storeId != 0) {
            MsgStore.Rec rec = store.get(storeId);
            if (rec != null) qqMsgId = rec.msgId;
        }
        long key = qqMsgId != 0 ? qqMsgId : storeId;
        if (key != 0 && !seenRecalls.add(key)) return;
        if (seenRecalls.size() > 8000) seenRecalls.clear();
        try {
            JSONObject n = Notices.recall(selfUin(), System.currentTimeMillis() / 1000,
                    group, peer, user, operator, storeId, qqMsgId);
            emitObEvent(n);
        } catch (Throwable t) {
            L.e("emitRecall", t);
        }
    }

    private JSONObject getMsg(int messageId) throws Exception {
        MsgStore.Rec r = store.get(messageId);
        if (r == null) throw new ApiError(1404, "message not found: " + messageId);
        String peer = r.peerUid == null || r.peerUid.isEmpty()
                ? String.valueOf(r.peerUin) : r.peerUid;
        String liveText = qq.peekRecordText(r.msgRecord);
        if (r.msgId != 0 && (r.msgRecord == null || !msgRecordHasElements(r.msgRecord))) {
            Object fetched = qq.fetchRecord(r.chatType, peer, r.msgId);
            String fetchedText = qq.peekRecordText(fetched);
            if (fetched != null && (r.msgRecord == null || !fetchedText.isEmpty())) {
                r.msgRecord = fetched;
                if (!fetchedText.isEmpty()) liveText = fetchedText;
            }
        }
        if ((liveText == null || liveText.isEmpty()) && r.msgId != 0
                && (r.content == null || r.content.isEmpty())) {
            Object dbRec = findDbRecord(r.chatType, peer, r.msgId);
            if (dbRec != null) {
                r.msgRecord = dbRec;
                liveText = qq.peekRecordText(dbRec);
            }
        }
        if (!liveText.isEmpty() && (r.content == null || r.content.isEmpty())) r.content = liveText;
        JSONObject ev = r.msgRecord == null ? null : conv.recordToEvent(r.msgRecord, 0);
        if (ev != null) fillEmptyText(ev, r.msgRecord);
        if (ev == null || "notice".equals(ev.optString("post_type")))
            ev = synthesizeFromRec(r);
        if (ev == null) throw new ApiError(1500, "cannot render message");
        JSONObject d = new JSONObject();
        String mid = Codec.publicMessageId(ev);
        if (mid.isEmpty() && r.msgId != 0) mid = String.valueOf(r.msgId);
        if (mid.isEmpty()) mid = String.valueOf(messageId);
        long when = r.msgTime > 0 ? r.msgTime : Codec.eventTime(ev);
        if (when <= 0 && r.msgRecord != null) {
            when = qq.ref.getLong(Convert.unwrapRecord(r.msgRecord), "msgTime");
            if (when > 10_000_000_000L) when /= 1000L;
        }
        if (when > 0) r.msgTime = when;
        d.put("time", when);
        if (when > 0) d.put("msg_time", String.valueOf(when));
        d.put("message_type", ev.optString("message_type"));
        d.put("message_id", mid);
        d.put("real_id", mid);
        if (ev.has("qq_msg_id")) d.put("qq_msg_id", ev.opt("qq_msg_id"));
        else if (r.msgId != 0) d.put("qq_msg_id", r.msgId);
        d.put("user_id", ev.optLong("user_id", r.senderUin));
        if (ev.has("group_id") || r.chatType == QQClient.CT_GROUP) {
            d.put("group_id", ev.optLong("group_id", r.peerUin));
            attachGroupName(d);
        }
        d.put("sender", ev.optJSONObject("sender"));
        d.put("message", ev.optJSONArray("message"));
        String text = ev.optString("raw_message", "");
        if (text.isEmpty()) text = snapshotText(mid, r);
        if (!text.isEmpty()) {
            d.put("raw_message", text);
            if (d.optJSONArray("message") == null || d.optJSONArray("message").length() == 0) {
                try { d.put("message", Codec.toSegments(text)); } catch (Exception ignore) {}
            }
            if (r.content == null || r.content.isEmpty()) r.content = text;
        }
        return d;
    }

    private Object findDbRecord(int chatType, String peer, long msgId) {
        if (msgId == 0 || peer == null || peer.isEmpty()) return null;
        try {
            QQClient.MsgListResult db = qq.getLatestDbMsgs(chatType, peer, 40);
            if (db == null || db.records == null) return null;
            for (Object rec : db.records) {
                if (rec == null) continue;
                Object inner = Convert.unwrapRecord(rec);
                if (Ref.asLong(qq.ref.get(inner, "msgId")) == msgId) return inner;
            }
        } catch (Throwable ignore) {}
        return null;
    }

    private boolean msgRecordHasElements(Object rec) {
        if (rec == null) return false;
        try {
            Object els = qq.ref.get(rec, "elements");
            if (!(els instanceof java.util.List)) return false;
            for (Object e : (java.util.List<?>) els) {
                if (e == null) continue;
                int et = Ref.asInt(qq.ref.get(e, "elementType"));
                if (et == 1) {
                    Object t = qq.ref.get(e, "textElement");
                    String c = t == null ? "" : Ref.asStr(qq.ref.get(t, "content"));
                    if (c != null && !c.isEmpty()) return true;
                    continue;
                }
                if (et != 0) return true;
            }
            return false;
        } catch (Throwable ignore) {
            return false;
        }
    }

    private JSONObject synthesizeFromRec(MsgStore.Rec r) throws Exception {
        if (r == null || (r.msgId == 0 && r.senderUin == 0)) return null;
        boolean group = r.chatType == QQClient.CT_GROUP;
        JSONObject sender = new JSONObject()
                .put("user_id", r.senderUin)
                .put("nickname", r.senderUin == selfUin() ? qq.selfNick() : "");
        JSONObject ev = new JSONObject()
                .put("post_type", "message")
                .put("message_type", group ? "group" : "private")
                .put("message_id", r.msgId != 0 ? String.valueOf(r.msgId) : String.valueOf(r.id))
                .put("qq_msg_id", r.msgId)
                .put("user_id", r.senderUin)
                .put("sender", sender)
                .put("time", System.currentTimeMillis() / 1000)
                .put("message_seq", r.msgSeq);
        if (group) ev.put("group_id", r.peerUin);
        JSONArray segs = new JSONArray();
        if (r.content != null && !r.content.isEmpty()) {
            try { segs = Codec.toSegments(r.content); } catch (Exception ignore) {}
        }
        ev.put("message", segs);
        return ev;
    }

    private JSONObject getGroupMsgHistory(long groupId, long messageSeq, int count,
                                          boolean queryOrder, String direction) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        return historyToArray(qq.getHistory(QQClient.CT_GROUP, String.valueOf(groupId),
                messageSeq, count, queryOrder), messageSeq, direction,
                QQClient.CT_GROUP, groupId, String.valueOf(groupId));
    }

    private JSONObject getFriendMsgHistory(long userId, long messageSeq, int count,
                                           boolean queryOrder, String direction) throws Exception {
        if (userId == 0) throw new ApiError(1400, "missing user_id");
        String uid = uidFor(0, userId);
        return historyToArray(qq.getHistory(QQClient.CT_C2C, uid,
                messageSeq, count, queryOrder), messageSeq, direction,
                QQClient.CT_C2C, userId, uid);
    }

    private JSONObject historyToArray(QQClient.MsgListResult hist, long cursorSeq,
                                      String direction, int chatType, long peerUin,
                                      String peerUid) throws Exception {
        if (hist.timedOut) throw new ApiError(1500, hist.describe());
        JSONArray messages = new JSONArray();
        int skipped = 0;
        if (hist.records != null) {
            for (Object rec : hist.records) {
                Object inner = Convert.unwrapRecord(rec);
                long recId = inner == null ? 0 : qq.ref.getLong(inner, "msgId");
                long recTime = inner == null ? 0 : qq.ref.getLong(inner, "msgTime");
                if (recTime > 10_000_000_000L) recTime /= 1000L;
                long recSeq = inner == null ? 0 : qq.ref.getLong(inner, "msgSeq");
                JSONObject ev = conv.recordToEvent(rec, 0);
                if (ev == null) {
                    skipped++;
                    if (skipped == 1 && rec != null)
                        L.e("recordToEvent skip class=" + rec.getClass().getName(), null);
                    continue;
                }
                fillEmptyText(ev, rec);
                fillEmptyTextFromMap(ev, hist);
                JSONObject item = historyItem(ev, cursorSeq, direction);
                if (item != null) {
                    applyTextMap(item, hist);
                    String tid = recId != 0 ? String.valueOf(recId) : String.valueOf(item.opt("message_id"));
                    String t = hist.texts.get(tid);
                    if (t == null) t = hist.texts.get(item.optString("message_id", ""));
                    try {
                        item.put("raw_message", t == null ? "" : t);
                        if (t != null) {
                            JSONArray arr = new JSONArray();
                            arr.put(new JSONObject().put("type", "text").put("data", new JSONObject().put("text", t)));
                            item.put("message", arr);
                        }
                    } catch (Exception ignore) {}
                    lastHistRows.add(new HistRow(item, recId, recTime, recSeq, t));
                    messages.put(item);
                }
            }
        }
        if (messages.length() == 0) {
            for (MsgStore.Rec rec : store.listPeer(chatType, peerUin, peerUid, 20)) {
                JSONObject ev = rec.msgRecord == null ? null : conv.recordToEvent(rec.msgRecord, 0);
                if (ev == null || "notice".equals(ev.optString("post_type")))
                    ev = synthesizeFromRec(rec);
                if (ev == null) continue;
                JSONObject item = historyItem(ev, cursorSeq, direction);
                if (item != null) {
                    lastHistRows.add(new HistRow(item, rec.msgId, rec.msgTime, rec.msgSeq, rec.content));
                    messages.put(item);
                }
            }
        }
        if (messages.length() == 0 && !hist.ok())
            throw new ApiError(1500, "history failed: " + hist.describe());
        lastListTexts.putAll(hist.texts);
        persistHistoryTexts(hist, chatType, peerUin, peerUid);
        JSONObject out = new JSONObject().put("messages", messages);
        if (hist.trace != null && !hist.trace.isEmpty()) out.put("_hist", hist.trace);
        out.put("_keys", String.valueOf(hist.texts.keySet()));
        out.put("_textN", hist.texts.size());
        if (messages.length() > 0)
            out.put("_mid0", messages.optJSONObject(0).optString("message_id"));
        if (conv.lastParseDebug != null && !conv.lastParseDebug.isEmpty())
            out.put("_parse", conv.lastParseDebug);
        if (messages.length() > 0) {
            JSONObject first = messages.optJSONObject(0);
            if (first != null) {
                out.put("_raw0", first.optString("raw_message"));
                out.put("_seg0", first.optJSONArray("message") == null
                        ? -1 : first.optJSONArray("message").length());
            }
        }
        return out;
    }

    private static JSONObject copyJson(JSONObject src) throws Exception {
        JSONObject out = new JSONObject();
        if (src == null) return out;
        java.util.Iterator<?> keys = src.keys();
        while (keys.hasNext()) {
            String k = String.valueOf(keys.next());
            out.put(k, src.get(k));
        }
        return out;
    }

    private String snapshotText(String msgId, MsgStore.Rec rec) {
        if (msgId != null && !msgId.isEmpty()) {
            String t = lastListTexts.get(msgId);
            if (t != null && !t.isEmpty()) return t;
        }
        if (rec == null && msgId != null && !msgId.isEmpty())
            rec = store.getByMsgId(parseLongQuiet(msgId));
        if (rec != null) {
            if (rec.content != null && !rec.content.isEmpty()) return rec.content;
            String peek = qq.peekRecordText(rec.msgRecord);
            if (peek != null && !peek.isEmpty()) {
                rec.content = peek;
                return peek;
            }
        }
        return "";
    }

    private void persistHistoryTexts(QQClient.MsgListResult hist, int chatType, long peerUin,
                                     String peerUid) {
        if (hist == null || hist.texts == null || hist.texts.isEmpty()) return;
        for (java.util.Map.Entry<String, String> e : hist.texts.entrySet()) {
            String text = e.getValue();
            if (text == null || text.isEmpty()) continue;
            long id = parseLongQuiet(e.getKey());
            if (id == 0) continue;
            MsgStore.Rec r = store.getByMsgId(id);
            if (r == null) {
                r = new MsgStore.Rec();
                r.chatType = chatType;
                r.peerUin = peerUin;
                r.peerUid = peerUid;
                r.msgId = id;
                r.content = text;
                store.put(r);
            } else if (r.content == null || r.content.isEmpty()) {
                r.content = text;
            }
        }
    }

    private void applyTextMap(JSONObject item, QQClient.MsgListResult hist) {
        if (item == null || hist == null || hist.texts == null || hist.texts.isEmpty()) return;
        String id = item.optString("message_id", "");
        String text = hist.texts.get(id);
        if ((text == null || text.isEmpty()) && !hist.texts.isEmpty()) {
            // last resort: any recorded text for this page (ids should match; keep for diagnosis)
            text = null;
        }
        if (text == null || text.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray();
            arr.put(new JSONObject().put("type", "text").put("data", new JSONObject().put("text", text)));
            item.put("message", arr);
            item.put("raw_message", text);
        } catch (Exception ignore) {}
    }

    private void fillEmptyTextFromMap(JSONObject ev, QQClient.MsgListResult hist) {
        if (ev == null || hist == null || hist.texts == null || hist.texts.isEmpty()) return;
        if (eventHasText(ev)) return;
        String id = ev.optString("message_id", "");
        if (id.isEmpty() && ev.has("qq_msg_id")) id = String.valueOf(ev.opt("qq_msg_id"));
        String text = hist.texts.get(id);
        if (text == null || text.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray();
            arr.put(new JSONObject().put("type", "text").put("data", new JSONObject().put("text", text)));
            ev.put("message", arr);
            ev.put("raw_message", text);
        } catch (Exception ignore) {}
    }

    private void fillEmptyText(JSONObject ev, Object rec) {
        if (ev == null) return;
        if (eventHasText(ev)) return;
        String text = qq.peekRecordText(rec);
        if (text == null || text.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray();
            arr.put(new JSONObject().put("type", "text").put("data", new JSONObject().put("text", text)));
            ev.put("message", arr);
            ev.put("raw_message", text);
        } catch (Exception ignore) {}
    }

    private static boolean eventHasText(JSONObject ev) {
        if (ev == null) return false;
        if (ev.optString("raw_message", "").length() > 0) return true;
        JSONArray segs = ev.optJSONArray("message");
        if (segs == null) return false;
        for (int i = 0; i < segs.length(); i++) {
            JSONObject s = segs.optJSONObject(i);
            if (s == null) continue;
            JSONObject d = s.optJSONObject("data");
            if (d == null) continue;
            if (d.optString("text", "").length() > 0) return true;
            if (d.optString("file", "").length() > 0) return true;
        }
        return false;
    }

    private JSONObject historyItem(JSONObject ev, long cursorSeq, String direction) throws Exception {
        if (ev == null || "notice".equals(ev.optString("post_type"))) return null;
        long seq = ev.optLong("message_seq");
        if (cursorSeq > 0 && "before".equals(direction) && seq >= cursorSeq) return null;
        if (cursorSeq > 0 && "after".equals(direction) && seq <= cursorSeq) return null;
        ev.put("self_id", selfUin());
        long uid = ev.optLong("user_id", 0);
        if (uid == 0) {
            JSONObject sender = ev.optJSONObject("sender");
            if (sender != null) uid = sender.optLong("user_id", 0);
        }
        String mid = Codec.publicMessageId(ev);
        if (mid.isEmpty()) mid = String.valueOf(ev.opt("message_id"));
        JSONObject item = new JSONObject()
                .put("time", ev.optLong("time"))
                .put("msg_time", ev.optString("msg_time", ev.optString("time", "")))
                .put("message_type", ev.optString("message_type"))
                .put("message_id", mid)
                .put("real_id", mid)
                .put("message_seq", seq)
                .put("user_id", uid)
                .put("sender", ev.optJSONObject("sender"))
                .put("message", ev.optJSONArray("message"))
                .put("raw_message", ev.optString("raw_message"));
        if (ev.has("qq_msg_id")) item.put("qq_msg_id", ev.opt("qq_msg_id"));
        if (ev.has("group_id")) {
            item.put("group_id", ev.optLong("group_id"));
            attachGroupName(item);
        }
        return item;
    }

    private static void requireOp(QQClient.OpResult r) {
        if (r == null || !r.ok()) {
            throw new ApiError(1500, r == null ? "group op failed" : r.describe());
        }
    }

    private JSONArray getGroupList() {
        JSONArray arr = new JSONArray();
        for (Object gi : qq.getGroupList()) {
            try {
                JSONObject o = new JSONObject();
                long gid = Ref.asLong(qq.ref.get(gi, "groupCode"));
                String name = Ref.asStr(qq.ref.get(gi, "groupName"));
                rememberGroup(gid, name);
                o.put("group_id", gid);
                o.put("group_name", name);
                o.put("member_count", Ref.asInt(qq.ref.get(gi, "memberCount")));
                o.put("max_member_count", Ref.asInt(qq.ref.get(gi, "maxMember")));
                arr.put(o);
            } catch (Throwable ignore) {}
        }
        return arr;
    }

    private JSONArray getFriendList() throws Exception {
        JSONArray arr = new JSONArray();
        for (java.util.Map.Entry<String, Object> entry : qq.getFriendCoreInfos().entrySet()) {
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

    private JSONObject getStrangerInfo(long userId) throws Exception {
        if (userId == 0) throw new ApiError(1400, "missing user_id");
        Object info = qq.getCoreInfo(userId);
        if (info == null) throw new ApiError(1404, "profile not found for user " + userId);
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

    private JSONObject getGroupMemberInfo(long groupId, long userId) throws Exception {
        if (groupId == 0 || userId == 0) throw new ApiError(1400, "missing group_id/user_id");
        java.util.Map<String, Object> members = qq.getAllMembers(groupId, true);
        if (members == null) throw new ApiError(1500, "cannot fetch group members");
        for (Object mi : members.values()) {
            if (Ref.asLong(qq.ref.get(mi, "uin")) == userId) return memberJson(groupId, mi);
        }
        throw new ApiError(1404, "member " + userId + " not found in group " + groupId);
    }

    private JSONArray getGroupMemberList(long groupId) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        java.util.Map<String, Object> members = qq.getAllMembers(groupId);
        if (members == null) throw new ApiError(1500, "cannot fetch group members");
        JSONArray arr = new JSONArray();
        for (Object mi : members.values()) arr.put(memberJson(groupId, mi));
        return arr;
    }

    private JSONObject memberJson(long groupId, Object mi) throws Exception {
        JSONObject o = new JSONObject();
        long uin = Ref.asLong(qq.ref.get(mi, "uin"));
        String card = Ref.asStr(qq.ref.get(mi, "cardName"));
        o.put("group_id", groupId);
        o.put("user_id", uin);
        o.put("nickname", Ref.asStr(qq.ref.get(mi, "nick")));
        o.put("card", card);
        o.put("sex", "unknown");
        o.put("age", 0);
        o.put("area", "");
        o.put("join_time", Ref.asInt(qq.ref.get(mi, "joinTime")));
        o.put("last_sent_time", Ref.asInt(qq.ref.get(mi, "lastSpeakTime")));
        o.put("level", String.valueOf(Ref.asInt(qq.ref.get(mi, "memberLevel"))));
        o.put("role", roleStr(qq.ref.get(mi, "role")));
        o.put("unfriendly", false);
        o.put("title", Ref.asStr(qq.ref.get(mi, "memberSpecialTitle")));
        o.put("title_expire_time", Ref.asLong(qq.ref.get(mi, "specialTitleExpireTime")));
        o.put("card_changeable", true);
        store.learnUid(uin, Ref.asStr(qq.ref.get(mi, "uid")));
        return o;
    }

    private String roleStr(Object roleEnum) {
        try {
            String n = String.valueOf(qq.ref.call(roleEnum, "name")).toUpperCase();
            if (n.contains("OWNER")) return "owner";
            if (n.contains("ADMIN")) return "admin";
        } catch (Throwable ignore) {}
        return "member";
    }

    private JSONObject groupInfoJson(long groupId) throws Exception {
        Object gi = qq.groupInfo(groupId);
        if (gi == null) throw new ApiError(1404, "group not found: " + groupId);
        JSONObject o = new JSONObject();
        o.put("group_id", Ref.asLong(qq.ref.get(gi, "groupCode")));
        o.put("group_name", Ref.asStr(qq.ref.get(gi, "groupName")));
        o.put("member_count", Ref.asInt(qq.ref.get(gi, "memberCount")));
        o.put("max_member_count", Ref.asInt(qq.ref.get(gi, "maxMember")));
        int flag3 = Ref.asInt(qq.ref.get(gi, "groupFlagExt3"));
        o.put("group_flag_ext3", flag3);
        o.put("honor_open", (flag3 & QQClient.HONOR_AIO_FLAG) == 0);
        return o;
    }

    private JSONObject getGroupFileSystemInfo(long groupId) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        PacketSvc.Result countPacket = qq.packets().sendOidb(GroupFiles.VIEW_CMD,
                GroupFiles.COUNT_SUB, GroupFiles.countRequest(groupId), true, 15_000L);
        if (!countPacket.ok())
            throw new ApiError(1500, "group file count failed: " + countPacket.describe());
        GroupFiles.CountResult count = GroupFiles.parseCount(countPacket.body);
        if (count.code != 0)
            throw new ApiError(1500, "group file count failed (code=" + count.code + "): " + count.message);

        PacketSvc.Result spacePacket = qq.packets().sendOidb(GroupFiles.VIEW_CMD,
                GroupFiles.SPACE_SUB, GroupFiles.spaceRequest(groupId), true, 15_000L);
        if (!spacePacket.ok())
            throw new ApiError(1500, "group file space failed: " + spacePacket.describe());
        GroupFiles.SpaceResult space = GroupFiles.parseSpace(spacePacket.body);
        if (space.code != 0)
            throw new ApiError(1500, "group file space failed (code=" + space.code + "): " + space.message);
        return new JSONObject()
                .put("file_count", count.fileCount)
                .put("limit_count", count.limitCount)
                .put("used_space", space.usedSpace)
                .put("total_space", space.totalSpace);
    }

    private JSONObject getGroupFiles(long groupId, String folderId) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        if (folderId == null || folderId.isEmpty())
            throw new ApiError(1400, "missing folder_id");
        JSONArray files = new JSONArray();
        JSONArray folders = new JSONArray();
        int startIndex = 0;
        final int pageSize = 50;
        for (int page = 0; page < 100; page++) {
            PacketSvc.Result packet = qq.packets().sendOidb(GroupFiles.VIEW_CMD,
                    GroupFiles.LIST_SUB,
                    GroupFiles.listRequest(groupId, folderId, startIndex, pageSize), true, 15_000L);
            if (!packet.ok())
                throw new ApiError(1500, "group file list failed: " + packet.describe());
            GroupFiles.ListResult result = GroupFiles.parseList(packet.body);
            if (result.code != 0)
                throw new ApiError(1500, "group file list failed (code=" + result.code + "): " + result.message);
            for (GroupFiles.Entry entry : result.entries) {
                if (entry.folder) {
                    folders.put(new JSONObject()
                            .put("folder_id", entry.id)
                            .put("folder_name", entry.name)
                            .put("create_time", entry.uploadTime)
                            .put("creator", entry.creatorUin)
                            .put("creator_name", entry.creatorName)
                            .put("total_file_count", entry.totalFileCount));
                } else {
                    files.put(new JSONObject()
                            .put("file_id", entry.id)
                            .put("file_name", entry.name)
                            .put("busid", entry.busId)
                            .put("file_size", entry.size)
                            .put("upload_time", entry.uploadTime)
                            .put("dead_time", entry.deadTime)
                            .put("modify_time", entry.modifyTime)
                            .put("download_times", entry.downloadTimes)
                            .put("uploader", entry.uploaderUin)
                            .put("uploader_name", entry.uploaderName));
                }
            }
            if (result.end) return new JSONObject().put("files", files).put("folders", folders);
            int next = result.nextIndex > startIndex ? result.nextIndex : startIndex + pageSize;
            if (next <= startIndex)
                throw new ApiError(1500, "group file list returned a stalled cursor");
            startIndex = next;
        }
        throw new ApiError(1500, "group file list exceeded pagination limit");
    }

    private JSONObject getGroupFileUrl(long groupId, String fileId, int busId) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        if (fileId == null || fileId.isEmpty()) throw new ApiError(1400, "missing file_id");
        PacketSvc.Result packet = qq.packets().sendOidb(GroupFiles.DOWNLOAD_CMD,
                GroupFiles.DOWNLOAD_SUB, GroupFiles.urlRequest(groupId, fileId, busId), true, 15_000L);
        if (!packet.ok())
            throw new ApiError(1500, "group file URL failed: " + packet.describe());
        GroupFiles.UrlResult result = GroupFiles.parseUrl(packet.body);
        if (result.code != 0)
            throw new ApiError(1500, "group file URL failed (code=" + result.code + "): " + result.message);
        if (result.url.isEmpty()) throw new ApiError(1500, "group file URL response is empty");
        return new JSONObject().put("url", result.url);
    }

    private JSONObject createGroupFileFolder(long groupId, String parentId, String name) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        if (name == null || name.isEmpty()) throw new ApiError(1400, "missing folder_name");
        GroupFiles.OpResult parsed = GroupFiles.parseCreateFolder(
                sendGroupFileBody(GroupFiles.FOLDER_CMD, GroupFiles.CREATE_FOLDER_SUB,
                        GroupFiles.createFolderRequest(groupId, parentId, name), "create group folder"));
        if (parsed.code != 0)
            throw new ApiError(1500, "create group folder failed (code=" + parsed.code + "): " + parsed.message);
        JSONObject created = folderJson(parsed.folder, name, parentId);
        if (created.optString("folder_id", "").isEmpty()) {
            JSONObject listed = getGroupFiles(groupId, parentId == null || parentId.isEmpty() ? "/" : parentId);
            JSONArray folders = listed.optJSONArray("folders");
            if (folders != null) {
                for (int i = 0; i < folders.length(); i++) {
                    JSONObject folder = folders.optJSONObject(i);
                    if (folder != null && name.equals(folder.optString("folder_name"))) {
                        created.put("folder_id", folder.optString("folder_id"));
                        created.put("create_time", folder.optLong("create_time"));
                        created.put("creator", folder.optLong("creator"));
                        created.put("creator_name", folder.optString("creator_name"));
                        break;
                    }
                }
            }
        }
        if (created.optString("folder_id", "").isEmpty())
            throw new ApiError(1500, "create group folder succeeded without folder_id");
        return created;
    }

    private JSONObject deleteGroupFolder(long groupId, String folderId) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        if (folderId == null || folderId.isEmpty()) throw new ApiError(1400, "missing folder_id");
        GroupFiles.OpResult parsed = GroupFiles.parseDeleteFolder(
                sendGroupFileBody(GroupFiles.FOLDER_CMD, GroupFiles.DELETE_FOLDER_SUB,
                        GroupFiles.deleteFolderRequest(groupId, folderId), "delete group folder"));
        if (parsed.code != 0)
            throw new ApiError(1500, "delete group folder failed (code=" + parsed.code + "): " + parsed.message);
        return new JSONObject().put("folder_id", folderId);
    }

    private JSONObject renameGroupFolder(long groupId, String folderId, String newName) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        if (folderId == null || folderId.isEmpty()) throw new ApiError(1400, "missing folder_id");
        if (newName == null || newName.isEmpty()) throw new ApiError(1400, "missing new_folder_name");
        GroupFiles.OpResult parsed = GroupFiles.parseRenameFolder(
                sendGroupFileBody(GroupFiles.FOLDER_CMD, GroupFiles.RENAME_FOLDER_SUB,
                        GroupFiles.renameFolderRequest(groupId, folderId, newName), "rename group folder"));
        if (parsed.code != 0)
            throw new ApiError(1500, "rename group folder failed (code=" + parsed.code + "): " + parsed.message);
        JSONObject o = folderJson(parsed.folder, newName, null);
        o.put("folder_id", folderId);
        return o;
    }

    private JSONObject deleteGroupFile(long groupId, String fileId, int busId) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        if (fileId == null || fileId.isEmpty()) throw new ApiError(1400, "missing file_id");
        GroupFiles.OpResult parsed = GroupFiles.parseDeleteFile(
                sendGroupFileBody(GroupFiles.DOWNLOAD_CMD, GroupFiles.DELETE_FILE_SUB,
                        GroupFiles.deleteFileRequest(groupId, fileId, busId), "delete group file"));
        if (parsed.code != 0)
            throw new ApiError(1500, "delete group file failed (code=" + parsed.code + "): " + parsed.message);
        return new JSONObject().put("file_id", fileId);
    }

    private JSONObject moveGroupFile(long groupId, String fileId, String parentId, String destId,
                                     int busId) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        if (fileId == null || fileId.isEmpty()) throw new ApiError(1400, "missing file_id");
        GroupFiles.OpResult parsed = GroupFiles.parseMoveFile(
                sendGroupFileBody(GroupFiles.DOWNLOAD_CMD, GroupFiles.MOVE_FILE_SUB,
                        GroupFiles.moveFileRequest(groupId, fileId, parentId, destId, busId),
                        "move group file"));
        if (parsed.code != 0)
            throw new ApiError(1500, "move group file failed (code=" + parsed.code + "): " + parsed.message);
        return new JSONObject().put("file_id", fileId).put("parent_id", destId);
    }

    private JSONObject renameGroupFile(long groupId, String fileId, String parentId, String newName,
                                       int busId) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        if (fileId == null || fileId.isEmpty()) throw new ApiError(1400, "missing file_id");
        if (newName == null || newName.isEmpty()) throw new ApiError(1400, "missing new_name");
        GroupFiles.OpResult parsed = GroupFiles.parseRenameFile(
                sendGroupFileBody(GroupFiles.DOWNLOAD_CMD, GroupFiles.RENAME_FILE_SUB,
                        GroupFiles.renameFileRequest(groupId, fileId, parentId, newName, busId),
                        "rename group file"));
        if (parsed.code != 0)
            throw new ApiError(1500, "rename group file failed (code=" + parsed.code + "): " + parsed.message);
        return new JSONObject().put("file_id", fileId).put("file_name", newName);
    }

    private byte[] sendGroupFileBody(int cmd, int sub, byte[] body, String action) throws Exception {
        PacketSvc.Result packet = qq.packets().sendOidb(cmd, sub, body, true, 15_000L);
        if (!packet.ok()) throw new ApiError(1500, action + " failed: " + packet.describe());
        return packet.body == null ? new byte[0] : packet.body;
    }

    private JSONObject folderJson(GroupFiles.Entry folder, String fallbackName, String fallbackParent)
            throws Exception {
        JSONObject o = new JSONObject();
        if (folder != null) {
            o.put("folder_id", folder.id);
            o.put("folder_name", folder.name);
            o.put("parent_id", folder.parentId);
            o.put("create_time", folder.uploadTime);
            o.put("modify_time", folder.modifyTime);
            o.put("creator", folder.creatorUin);
            o.put("creator_name", folder.creatorName);
            o.put("total_file_count", folder.totalFileCount);
        } else {
            if (fallbackName != null) o.put("folder_name", fallbackName);
            if (fallbackParent != null) o.put("parent_id", fallbackParent);
        }
        return o;
    }

    private static String firstNonEmpty(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : (b == null ? "" : b);
    }

    /** Resolve a uin to its uid for a group action: cache -> profile service -> group member list. */
    private String uidFor(long groupId, long uin) throws Exception {
        if (uin == 0) throw new ApiError(1400, "missing user_id");
        String uid = store.uidOf(uin);
        if (uid != null && !uid.isEmpty()) return uid;
        uid = qq.resolveUid(uin);
        if (uid != null && !uid.isEmpty()) { store.learnUid(uin, uid); return uid; }
        if (groupId != 0) {
            java.util.Map<String, Object> members = qq.getAllMembers(groupId);
            if (members != null) {
                for (Object mi : members.values()) {
                    if (Ref.asLong(qq.ref.get(mi, "uin")) == uin) {
                        String u = Ref.asStr(qq.ref.get(mi, "uid"));
                        store.learnUid(uin, u);
                        return u;
                    }
                }
            }
        }
        throw new ApiError(1404, "cannot resolve uid for user " + uin);
    }

    /** QQ NT needs msgSeq; sent messages often land in MsgStore before seq is filled. */
    private long ensureMsgSeq(MsgStore.Rec r) throws Exception {
        if (r.msgSeq != 0) return r.msgSeq;
        if (r.msgRecord != null) {
            long seq = qq.ref.getLong(Convert.unwrapRecord(r.msgRecord), "msgSeq");
            if (seq != 0) {
                r.msgSeq = seq;
                return seq;
            }
        }
        if (r.msgId == 0) throw new ApiError(1400, "message missing seq");
        String peer = r.peerUid == null || r.peerUid.isEmpty()
                ? String.valueOf(r.peerUin) : r.peerUid;
        Object fetched = qq.fetchRecord(r.chatType, peer, r.msgId);
        if (fetched != null) {
            r.msgRecord = fetched;
            long seq = qq.ref.getLong(Convert.unwrapRecord(fetched), "msgSeq");
            if (seq != 0) {
                r.msgSeq = seq;
                return seq;
            }
        }
        throw new ApiError(1400, "message missing seq");
    }

    /** 1=QQ face, 2=unicode — id string length > 3 (NapCat/QQNT convention). */
    private static long emojiTypeOf(String raw, long emojiId) {
        String id = raw != null && !raw.trim().isEmpty() ? raw.trim() : String.valueOf(emojiId);
        return id.length() > 3 ? 2L : 1L;
    }

    private static String emojiKeyOf(String raw, long emojiId) {
        return raw != null && !raw.trim().isEmpty() ? raw.trim() : String.valueOf(emojiId);
    }

    private void setEmojiLike(int messageId, long emojiId, String emojiRaw, boolean set) throws Exception {
        MsgStore.Rec r = store.get(messageId);
        if (r == null) throw new ApiError(1404, "message not found: " + messageId);
        Object msgService = qq.getMsgService();
        if (msgService == null) throw new ApiError(1500, "kernel not ready");
        Object contact = qq.ref.neu(QQClient.CONTACT, r.chatType,
                r.peerUid == null || r.peerUid.isEmpty() ? String.valueOf(r.peerUin) : r.peerUid, "");
        long msgSeq = ensureMsgSeq(r);
        long emojiType = emojiTypeOf(emojiRaw, emojiId);
        String emojiKey = emojiKeyOf(emojiRaw, emojiId);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final int[] code = new int[]{-1};
        final String[] message = new String[]{""};
        Object cb = java.lang.reflect.Proxy.newProxyInstance(qq.ref.cl,
                new Class[]{qq.ref.cls("com.tencent.qqnt.kernel.nativeinterface.ISetMsgEmojiLikesCallback")},
                (proxy, m, a) -> {
                    if ("onSetMsgEmojiLikes".equals(m.getName()) && a != null && a.length >= 1) {
                        code[0] = Ref.asInt(a[0]);
                        if (a.length >= 2) message[0] = Ref.asStr(a[1]);
                        latch.countDown();
                    }
                    return null;
                });
        qq.ref.call(msgService, "setMsgEmojiLikes", contact, msgSeq, emojiKey, emojiType, set, cb);
        if (!latch.await(15, java.util.concurrent.TimeUnit.SECONDS))
            throw new ApiError(1500, "reaction operation timeout");
        if (code[0] != 0)
            throw new ApiError(1500, "reaction operation failed: code=" + code[0] + " " + message[0]);
        String peer = r.peerUid == null || r.peerUid.isEmpty()
                ? String.valueOf(r.peerUin) : r.peerUid;
        if (r.msgId != 0) {
            Object fetched = qq.fetchRecord(r.chatType, peer, r.msgId);
            if (fetched != null) r.msgRecord = fetched;
        }
    }

    private JSONObject reactionList(int messageId, long emojiId, String emojiRaw, String cursor)
            throws Exception {
        MsgStore.Rec r = store.get(messageId);
        if (r == null) throw new ApiError(1404, "message not found: " + messageId);
        Object msgService = qq.getMsgService();
        if (msgService == null) throw new ApiError(1500, "kernel not ready");
        Object contact = qq.ref.neu(QQClient.CONTACT, r.chatType,
                r.peerUid == null || r.peerUid.isEmpty() ? String.valueOf(r.peerUin) : r.peerUid, "");
        long msgSeq = ensureMsgSeq(r);
        long emojiType = emojiTypeOf(emojiRaw, emojiId);
        String emojiKey = emojiKeyOf(emojiRaw, emojiId);
        String cookie = cursor == null ? "" : cursor;
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final int[] code = new int[]{-1};
        final String[] message = new String[]{""};
        final String[] next = new String[]{""};
        final java.util.List<?>[] users = new java.util.List<?>[1];
        Object cb = java.lang.reflect.Proxy.newProxyInstance(qq.ref.cl,
                new Class[]{qq.ref.cls("com.tencent.qqnt.kernel.nativeinterface.IGetMsgEmojiLikesListCallback")},
                (proxy, m, a) -> {
                    try {
                        if (a != null) for (Object arg : a)
                            absorbReactionListArg(arg, code, message, next, users);
                    } catch (Throwable t) {
                        L.e("reactionList cb " + m.getName(), t);
                    }
                    latch.countDown();
                    return null;
                });
        qq.ref.call(msgService, "getMsgEmojiLikesList", contact, msgSeq,
                emojiKey, emojiType, cookie, true, 50, cb);
        if (!latch.await(15, java.util.concurrent.TimeUnit.SECONDS))
            throw new ApiError(1500, "reaction list timeout");
        if (code[0] == -1 && users[0] != null) code[0] = 0;
        JSONArray data = reactionUsersToJson(users[0]);
        if (data.length() == 0) data = reactionListFromRecord(r, emojiKey);
        if (code[0] == -1 && data.length() > 0) code[0] = 0;
        if (code[0] != 0)
            throw new ApiError(1500, "reaction list failed: code=" + code[0] + " " + message[0]);
        JSONObject out = new JSONObject().put("data", data);
        if (next[0] != null && !next[0].isEmpty() && !next[0].equals(cursor)) out.put("next", next[0]);
        return out;
    }

    private void absorbReactionListArg(Object arg, int[] code, String[] message,
                                       String[] next, java.util.List<?>[] users) {
        try {
            if (arg == null) return;
            if (arg instanceof Number) {
                if (code[0] == -1) code[0] = Ref.asInt(arg);
                return;
            }
            if (arg instanceof java.util.List) {
                users[0] = (java.util.List<?>) arg;
                return;
            }
            if (arg instanceof String) {
                String s = Ref.asStr(arg);
                if (!s.isEmpty() && (next[0] == null || next[0].isEmpty())) next[0] = s;
                return;
            }
            String cn = arg.getClass().getName();
            if (cn.contains("Callback") || cn.startsWith("java.") || cn.startsWith("android."))
                return;
            int parsed = Ref.asInt(qq.ref.get(arg, "result"));
            if (code[0] == -1 || parsed != 0) code[0] = parsed;
            String err = Ref.asStr(qq.ref.get(arg, "errMsg"));
            if (!err.isEmpty()) message[0] = err;
            String ck = Ref.asStr(qq.ref.get(arg, "cookie"));
            if (!ck.isEmpty()) next[0] = ck;
            Object list = qq.ref.get(arg, "emojiLikesList");
            if (!(list instanceof java.util.List)) list = qq.ref.get(arg, "emojiLikeList");
            if (list instanceof java.util.List) users[0] = (java.util.List<?>) list;
        } catch (Throwable ignore) {}
    }

    private JSONArray reactionUsersToJson(java.util.List<?> users) throws Exception {
        JSONArray data = new JSONArray();
        if (users == null) return data;
        for (Object info : users) {
            long id = qq.ref.getLong(info, "tinyId");
            String nick = Ref.asStr(qq.ref.get(info, "nickName"));
            String avatar = Ref.asStr(qq.ref.get(info, "headUrl"));
            JSONObject user = Codec.user(id, nick, "");
            if (!avatar.isEmpty()) user.put("avatar", avatar);
            data.put(user);
        }
        return data;
    }

    /** When getMsgEmojiLikesList returns no rows, msgRecord still tracks our own click. */
    private JSONArray reactionListFromRecord(MsgStore.Rec r, String emojiKey) throws Exception {
        JSONArray data = new JSONArray();
        Object rec = r.msgRecord;
        if (rec == null && r.msgId != 0) {
            String peer = r.peerUid == null || r.peerUid.isEmpty()
                    ? String.valueOf(r.peerUin) : r.peerUid;
            rec = qq.fetchRecord(r.chatType, peer, r.msgId);
            if (rec != null) r.msgRecord = rec;
        }
        rec = rec == null ? null : Convert.unwrapRecord(rec);
        Object likes = rec == null ? null : qq.ref.get(rec, "emojiLikesList");
        if (!(likes instanceof java.util.List)) return data;
        for (Object like : (java.util.List<?>) likes) {
            if (!emojiKey.equals(Ref.asStr(qq.ref.get(like, "emojiId")))) continue;
            if (qq.ref.getLong(like, "likesCnt") <= 0) continue;
            Object clicked = qq.ref.get(like, "isClicked");
            boolean mine = clicked instanceof Boolean ? (Boolean) clicked
                    : Ref.asInt(clicked) != 0;
            if (!mine) continue;
            JSONObject user = Codec.user(selfUin(), qq.selfNick(), "");
            JSONObject login = loginSlim().optJSONObject("user");
            if (login != null) {
                String avatar = login.optString("avatar", "");
                if (!avatar.isEmpty()) user.put("avatar", avatar);
            }
            data.put(user);
            break;
        }
        return data;
    }

    /**
     * 群管理「成员群头衔」= userShowFlag / cGroupRankUserFlag (1=开).
     * Not 群标识: that is groupFlagExt3 0x2000000 / isTroopHonorOpen.
     * Server write is OIDB 0x8FC sub 0 (show_flag). Sub 2 only sets one member's title.
     */
    private JSONObject setGroupTitleDisplay(long groupId, boolean show) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        JSONArray paths = new JSONArray();
        int want = show ? 1 : 0;
        QQClient.MemberExtFlags before = qq.getMemberExtInfo(groupId);
        Pb.Writer body = Pb.w().varint(1, groupId).varint(2, want).varint(6, want);
        for (int i = 0; i < before.levelIds.size(); i++) {
            body.message(4, Pb.w().varint(1, before.levelIds.get(i)[0])
                    .string(2, before.levelNames.get(i)).toByteArray());
        }
        for (int i = 0; i < before.levelIdsNew.size(); i++) {
            body.message(10, Pb.w().varint(1, before.levelIdsNew.get(i)[0])
                    .string(2, before.levelNamesNew.get(i)).toByteArray());
        }
        byte[] body8fc = body.toByteArray();
        PacketSvc.Result r0 = qq.packets().sendOidb(0x8FC, 0, body8fc);
        paths.put("oidb_0x8fc_0:" + (r0.ok() ? "ok" : r0.describe()));
        PacketSvc.Result r0b = qq.packets().sendOidb(0x8FC, 0, body8fc, false);
        paths.put("oidb_0x8fc_0_nr:" + (r0b.ok() ? "ok" : r0b.describe()));
        PacketSvc.Result legacy = qq.packets().sendSso("OidbSvc.0x8fc_0",
                Pb.oidb(0x8FC, 0, body8fc, false));
        paths.put("oidb_svc_0x8fc_0:" + (legacy.ok() ? "ok" : legacy.describe()));
        JSONArray dumps = new JSONArray();
        for (String n : new String[]{
                "com.tencent.qqnt.kernel.nativeinterface.SetIdentityTitleInfoReq",
                "com.tencent.qqnt.kernel.nativeinterface.GIMSetGroupLevelInfoReq",
                "com.tencent.qqnt.kernel.nativeinterface.GIMSetGroupLevelInfoRsp"}) {
            JSONArray fs = new JSONArray();
            for (String f : qq.dumpClassFields(n)) fs.put(f);
            dumps.put(new JSONObject().put("cls", n).put("f", fs));
        }
        try {
            QQClient.OpResult id = qq.setIdentityTitleInfo(groupId, show);
            paths.put("setIdentityTitleInfo:" + (id.ok() ? "ok" : id.describe()));
        } catch (Throwable t) {
            paths.put("setIdentityTitleInfo:err:" + t);
        }
        try {
            QQClient.OpResult lv = qq.setGroupIdentityLevelInfo(groupId, show);
            paths.put("setGroupIdentityLevelInfo:" + lv.describe());
        } catch (Throwable t) {
            paths.put("setGroupIdentityLevelInfo:err:" + t);
        }
        JSONArray cbDump = new JSONArray();
        for (String m : qq.dumpServiceMethods(
                "com.tencent.qqnt.kernel.nativeinterface.ISetGroupIdentityLevelInfoCallback"))
            cbDump.put(m);
        dumps.put(new JSONObject().put("cls", "ISetGroupIdentityLevelInfoCallback").put("f", cbDump));
        if (qq.updateLocalRankSwitch(groupId, show)) paths.put("local_rank_switch");
        QQClient.MemberExtFlags lvl = qq.getGroupMemberLevelInfo(groupId);
        paths.put("getGroupMemberLevelInfo:" + lvl.code + ":" + lvl.msg);
        try { Thread.sleep(400); } catch (InterruptedException ignore) {}
        QQClient.MemberExtFlags ext = qq.getMemberExtInfo(groupId);
        int[] local = qq.troopExtRankFlags(groupId);
        boolean open = local[0] == 1 || ext.titleOpen();
        return new JSONObject()
                .put("title_open", open)
                .put("user_show_flag", ext.userShowFlag)
                .put("user_show_flag_new", ext.userShowFlagNew)
                .put("sys_show_flag", ext.sysShowFlag)
                .put("local_rank_flag", local[0])
                .put("local_rank_flag_new", local[1])
                .put("wanted", show)
                .put("before_flag", before.userShowFlag)
                .put("level_n", before.levelNames.size())
                .put("level_new_n", before.levelNamesNew.size())
                .put("paths", paths)
                .put("dump", dumps);
    }

    private JSONObject groupExtra(long groupId) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        qq.refreshGroupList();
        int flag = qq.groupFlagExt3(groupId);
        JSONObject g = groupInfoJson(groupId);
        QQClient.MemberExtFlags ext = qq.getMemberExtInfo(groupId);
        return new JSONObject()
                .put("guild_id", String.valueOf(groupId))
                .put("name", g.optString("group_name"))
                .put("group_flag_ext3", flag)
                .put("honor_open", (flag & QQClient.HONOR_AIO_FLAG) == 0)
                .put("title_open", ext.titleOpen())
                .put("user_show_flag", ext.userShowFlag)
                .put("user_show_flag_new", ext.userShowFlagNew)
                .put("sys_show_flag", ext.sysShowFlag);
    }

    private JSONObject groupSign(long groupId) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        String ver = qq.qqVersion();
        if (ver == null || ver.isEmpty()) ver = "9.3.55";
        byte[] inner = Pb.w()
                .string(1, String.valueOf(selfUin()))
                .string(2, String.valueOf(groupId))
                .string(3, ver)
                .toByteArray();
        PacketSvc.Result result = qq.packets().sendOidb(0xEB7, 1,
                Pb.w().message(2, inner).toByteArray(), false);
        if (!result.ok()) throw new ApiError(1500, "group sign failed: " + result.describe());
        return new JSONObject().put("ok", true);
    }

    private void setGroupEssence(JSONObject p, boolean add) throws Exception {
        long g = p.optLong("guild_id", p.optLong("group_id", 0));
        if (g == 0) g = Codec.channelPeer(p.optString("channel_id", ""));
        MsgStore.Rec rec = requireMessage(p.optString("message_id", ""), p);
        if (g == 0) g = rec.peerUin;
        long seq = rec.msgSeq;
        long random = 0;
        if (rec.msgRecord != null) {
            random = qq.ref.getLong(rec.msgRecord, "msgRandom");
            if (seq == 0) seq = qq.ref.getLong(rec.msgRecord, "msgSeq");
        }
        if (seq == 0 || random == 0)
            throw new ApiError(1400, "message missing seq/random");
        requireOp(qq.setGroupEssence(g, seq, random, add));
    }

    /** OidbSvcTrpcTcp.0x8FC_2: set (or clear) one member's special title. */
    private void setGroupSpecialTitle(long groupId, String targetUid, String title) {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        // D8FCReqBody: 1=groupCode, 2=showFlag, 3=MemberInfo{1=uid, 5=title, 6=expire(-1=永久), 7=uinName}.
        String t = title == null ? "" : title;
        byte[] member = Pb.w().string(1, targetUid).string(5, t).varint(6, -1L).string(7, t)
                .toByteArray();
        byte[] body = Pb.w().varint(1, groupId).varint(2, 1).message(3, member).toByteArray();
        PacketSvc.Result result = qq.packets().sendOidb(0x8FC, 2, body);
        if (!result.ok()) {
            throw new ApiError(1500, "set special title failed: " + result.describe());
        }
        qq.getAllMembers(groupId, true);
    }

    /** OidbSvcTrpcTcp.0x7E5_104: like a user's profile card `times` times (server caps daily total). */
    /**
     * OidbSvcTrpcTcp.0x7E5_104: like a user's profile card `times` times.
     * Body per LagrangeGo: 11=targetUid(str), 12=source(71), 13=count; envelope isReserved=0.
     *
     * <p>Device note (QQ 9.3.50, 2026-08): the transport reaches the server and the command routes
     * to the like service, but the server rejects it with oidb=319 "[oidb] rule type not match
     * appid" for every source value. This is Tencent's appid/rule gating (the same 319 seen across
     * clients since ~2026-08), not a packet-format bug — a wrong command number would return 236
     * "cmd not found" instead. It may also compound with this account's existing risk-control state.
     * Left as the protocol-correct implementation; it should succeed once the appid is un-gated or on
     * a non-restricted account.</p>
     */
    private void sendLike(long userId, int times) throws Exception {
        if (userId == 0) throw new ApiError(1400, "missing user_id");
        if (times < 1) times = 1;
        String uid = qq.resolveUid(userId);
        if (uid == null || uid.isEmpty()) throw new ApiError(1404, "cannot resolve uid for user " + userId);
        store.learnUid(userId, uid);
        byte[] body = Pb.w().string(11, uid).varint(12, 71).varint(13, times).toByteArray();
        PacketSvc.Result result = qq.packets().sendOidb(0x7E5, 104, body, false);
        if (!result.ok()) {
            throw new ApiError(1500, "send_like failed: " + result.describe());
        }
    }

    /** go-cqhttp send_poke: 0xED3_1. Group uses groupUin+target; friend uses friendUin=target. */
    private void sendPoke(long groupId, long userId) throws Exception {
        if (userId == 0) throw new ApiError(1400, "missing user_id");
        Pb.Writer body = Pb.w().varint(1, userId).varint(6, 0);
        if (groupId != 0) body.varint(2, groupId);
        else body.varint(5, userId);
        PacketSvc.Result result = qq.packets().sendOidb(0xED3, 1, body.toByteArray());
        if (!result.ok()) throw new ApiError(1500, "send_poke failed: " + result.describe());
    }

    // ============ QQ inbound: events ============
    private final java.util.Set<Long> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** >0 while message.create is inside kernel sendMsg; drops that echo so it does not re-enter Koishi. */
    private final java.util.concurrent.atomic.AtomicInteger outboundEcho =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.Map<String, FriendReq> pendingFriends = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, GroupReq> pendingGroups = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> seenRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<Long> seenRecalls = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> seenMemberChanges = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final class FriendReq {
        final String uid;
        final long reqTime;
        final JSONObject event;
        final boolean checked;
        FriendReq(String uid, long reqTime, JSONObject event, boolean checked) {
            this.uid = uid; this.reqTime = reqTime; this.event = event; this.checked = checked;
        }
    }
    private static final class GroupReq {
        final long seq;
        final long groupCode;
        final Object type;
        final JSONObject event;
        final boolean invite;
        final boolean checked;
        GroupReq(long seq, long groupCode, Object type, JSONObject event, boolean invite, boolean checked) {
            this.seq = seq; this.groupCode = groupCode; this.type = type;
            this.event = event; this.invite = invite; this.checked = checked;
        }
    }

    @Override public void onRecvMsgs(List<?> records) {
        long self = selfUin();
        for (Object rec : records) {
            try {
                rememberReactionState(rec);
                long msgId = Ref.asLong(qq.ref.get(rec, "msgId"));
                long senderUin = Ref.asLong(qq.ref.get(rec, "senderUin"));
                if (msgId != 0 && !seen.add(msgId)) continue; // dedupe
                if (seen.size() > 8000) seen.clear();
                // Echo of message.create — do not feed it back into Koishi as a new session.
                if (self != 0 && senderUin == self && outboundEcho.get() > 0) continue;
                JSONObject ev = conv.recordToEvent(rec, self);
                if (ev != null) {
                    String nt = ev.optString("notice_type", "");
                    if ("group_recall".equals(nt) || "friend_recall".equals(nt)) {
                        long mid = ev.optLong("qq_msg_id", 0);
                        if (mid == 0) mid = ev.optLong("message_id", 0);
                        if (mid != 0 && !seenRecalls.add(mid)) continue;
                    }
                    emitObEvent(ev);
                    L.d("event -> " + ev.optString("post_type") + "/"
                            + ev.optString("message_type", ev.optString("notice_type"))
                            + " from " + ev.optLong("user_id"));
                }
            } catch (Throwable t) {
                L.e("onRecvMsgs", t);
            }
        }
    }

    @Override public void onMsgUpdates(List<?> records) {
        if (records == null) return;
        for (Object record : records) {
            try { emitReactionChanges(record); }
            catch (Throwable t) { L.e("onMsgUpdates", t); }
        }
    }

    private void rememberReactionState(Object record) throws Exception {
        long msgId = Ref.asLong(qq.ref.get(record, "msgId"));
        if (msgId == 0) return;
        reactionCounts.put(msgId, reactionSnapshot(record));
        if (reactionCounts.size() > 8000) reactionCounts.clear();
    }

    private java.util.Map<String, Long> reactionSnapshot(Object record) throws Exception {
        java.util.LinkedHashMap<String, Long> out = new java.util.LinkedHashMap<>();
        Object likes = qq.ref.get(record, "emojiLikesList");
        if (!(likes instanceof java.util.List)) return out;
        for (Object like : (java.util.List<?>) likes) {
            String id = Ref.asStr(qq.ref.get(like, "emojiId"));
            if (!id.isEmpty()) out.put(id, Ref.asLong(qq.ref.get(like, "likesCnt")));
        }
        return out;
    }

    private void emitReactionChanges(Object record) throws Exception {
        long kernelId = Ref.asLong(qq.ref.get(record, "msgId"));
        if (kernelId == 0) return;
        java.util.Map<String, Long> current = reactionSnapshot(record);
        java.util.Map<String, Long> previous = reactionCounts.put(kernelId, current);
        if (previous == null) return;
        JSONObject ob = conv.recordToEvent(record, 0);
        if (ob == null || !"message".equals(ob.optString("post_type"))) return;
        java.util.HashSet<String> ids = new java.util.HashSet<>(previous.keySet());
        ids.addAll(current.keySet());
        for (String emojiId : ids) {
            long before = previous.containsKey(emojiId) ? previous.get(emojiId) : 0;
            long after = current.containsKey(emojiId) ? current.get(emojiId) : 0;
            if (before == after) continue;
            boolean group = "group".equals(ob.optString("message_type"));
            long peer = group ? ob.optLong("group_id") : ob.optLong("user_id");
            JSONObject body = new JSONObject()
                    .put("sn", eventSn.incrementAndGet())
                    .put("type", after > before ? "reaction-added" : "reaction-removed")
                    .put("timestamp", System.currentTimeMillis())
                    .put("login", loginSlim())
                    .put("emoji", new JSONObject().put("id", emojiId))
                    .put("message", new JSONObject().put("id", Codec.publicMessageId(ob)))
                    .put("channel", Codec.channel(group ? QQClient.CT_GROUP : QQClient.CT_C2C, peer, ""));
            if (group) body.put("guild", Codec.guild(peer, ""));
            emitSatoriEvent(body);
        }
    }

    @Override public void onRecall(int type, String info, long time) {
        try {
            recallFromCallback(type, info, time);
        } catch (Throwable t) {
            L.e("onRecall", t);
        }
    }

    @Override public void onBuddyReq(Object info) {
        if (info == null) return;
        try {
            Object list = qq.ref.get(info, "buddyReqs");
            if (!(list instanceof List)) return;
            for (Object req : (List<?>) list) {
                JSONObject ev = friendRequestEvent(req);
                if (ev != null) emitObEvent(ev);
            }
        } catch (Throwable t) {
            L.e("onBuddyReq", t);
        }
    }

    @Override public void onGroupNotifies(List<?> notifies) {
        if (notifies == null) return;
        for (Object n : notifies) {
            try {
                JSONObject ev = groupRequestEvent(n);
                if (ev != null) emitObEvent(ev);
            } catch (Throwable t) {
                L.e("onGroupNotifies", t);
            }
        }
    }

    @Override public void onMemberListChange(Object change) {
        if (change == null) return;
        try {
            long groupId = Ref.asLong(qq.ref.get(change, "groupCode"));
            Object typeEnum = qq.ref.get(change, "changeType");
            String typeName = typeEnum == null ? "" : String.valueOf(qq.ref.call(typeEnum, "name"));
            Object infosObj = qq.ref.get(change, "infos");
            if (!(infosObj instanceof java.util.Map) || groupId == 0) return;
            boolean add = typeName.contains("ADD");
            boolean remove = typeName.contains("REMOVE");
            if (!add && !remove) return;
            long now = System.currentTimeMillis() / 1000;
            for (Object mi : ((java.util.Map<?, ?>) infosObj).values()) {
                if (mi == null) continue;
                long uin = Ref.asLong(qq.ref.get(mi, "uin"));
                String uid = Ref.asStr(qq.ref.get(mi, "uid"));
                if (uin != 0 && uid != null && !uid.isEmpty()) store.learnUid(uin, uid);
                if (uin == 0) continue;
                String key = groupId + ":" + uin + ":" + (add ? "add" : "rm");
                if (!seenMemberChanges.add(key)) continue;
                if (seenMemberChanges.size() > 8000) seenMemberChanges.clear();
                JSONObject ev = add
                        ? Notices.groupIncrease(selfUin(), now, groupId, uin, selfUin())
                        : Notices.groupDecrease(selfUin(), now, groupId, uin, selfUin(), true);
                emitObEvent(ev);
            }
        } catch (Throwable t) {
            L.e("onMemberListChange", t);
        }
    }

    @Override public void onGroupListUpdate(Object updateType, List<?> groups) {
        if (groups == null) return;
        for (Object info : groups) {
            try {
                rememberGroup(Ref.asLong(qq.ref.get(info, "groupCode")),
                        Ref.asStr(qq.ref.get(info, "groupName")));
            } catch (Throwable ignore) {}
        }
        if (groups.isEmpty()) return;
        String update = enumName(updateType).toUpperCase(java.util.Locale.ROOT);
        if (update.contains("INIT") || update.contains("SYNC")
                || update.contains("REFRESH") || update.contains("RELOAD")
                || update.contains("LOAD") || update.contains("ALL")
                || update.contains("FULL")) return;
        String suffix;
        if (update.contains("DELETE") || update.contains("REMOVE") || update.contains("DEL")
                || update.contains("QUIT") || update.contains("EXIT")) suffix = "removed";
        else if (update.contains("ADD") || update.contains("INSERT") || update.contains("JOIN"))
            suffix = "added";
        else suffix = "updated";
        if ("updated".equals(suffix) && groups.size() > 3) return;
        for (Object info : groups) {
            try {
                long groupId = Ref.asLong(qq.ref.get(info, "groupCode"));
                if (groupId == 0) continue;
                String name = Ref.asStr(qq.ref.get(info, "groupName"));
                emitGuildChannelChange(suffix, groupId, name);
            } catch (Throwable t) {
                L.e("onGroupListUpdate " + update, t);
            }
        }
    }

    private void emitGuildChannelChange(String suffix, long groupId, String name) throws Exception {
        long now = System.currentTimeMillis();
        String eventKey = suffix + ":" + groupId + ":" + (name == null ? "" : name);
        Long previous = recentGroupEvents.put(eventKey, now);
        if (previous != null && now - previous < 3000) return;
        if (recentGroupEvents.size() > 1000) recentGroupEvents.clear();
        JSONObject guild = Codec.guild(groupId, name);
        JSONObject guildEvent = new JSONObject()
                .put("sn", eventSn.incrementAndGet())
                .put("type", "guild-" + suffix)
                .put("timestamp", now)
                .put("login", loginSlim())
                .put("guild", guild);
        emitSatoriEvent(guildEvent);
        JSONObject channel = Codec.channel(QQClient.CT_GROUP, groupId, name)
                .put("parent_id", String.valueOf(groupId));
        JSONObject channelEvent = new JSONObject()
                .put("sn", eventSn.incrementAndGet())
                .put("type", "channel-" + suffix)
                .put("timestamp", now)
                .put("login", loginSlim())
                .put("guild", guild)
                .put("channel", channel);
        emitSatoriEvent(channelEvent);
    }

    /**
     * Android 9.3.50: onMsgRecall(chatType, peerUid, msgSeqOrMsgId).
     * Desktop NapCat uses the same shape. JSON info is still accepted if present.
     */
    private void recallFromCallback(int type, String info, long third) throws Exception {
        if (info == null || info.isEmpty()) return;
        long msgId = 0, peer = 0, user = 0, operator = 0, msgSeq = 0;
        boolean group = type == QQClient.CT_GROUP;
        if (info.charAt(0) == '{') {
            JSONObject j = new JSONObject(info);
            msgId = j.optLong("msgId", j.optLong("msg_id", 0));
            msgSeq = j.optLong("msgSeq", j.optLong("msg_seq", 0));
            peer = j.optLong("peerUin", j.optLong("groupCode", j.optLong("group_id", 0)));
            user = j.optLong("senderUin", j.optLong("user_id", 0));
            operator = j.optLong("operatorUin", j.optLong("operator_id", 0));
            String uid = j.optString("peerUid", j.optString("operatorUid", ""));
            if (peer == 0 && !uid.isEmpty()) {
                try { peer = Long.parseLong(uid); } catch (Exception ignore) {}
            }
            if (j.has("chatType")) group = j.optInt("chatType") == QQClient.CT_GROUP;
        } else {
            msgSeq = third;
            msgId = third;
            try { peer = Long.parseLong(info.trim()); } catch (Exception ignore) {}
        }
        MsgStore.Rec rec = msgId != 0 ? store.getByMsgId(msgId) : null;
        if (rec == null) rec = store.findByPeerSeq(type, peer, info, msgSeq);
        int obId = rec != null ? rec.id : store.idOfMsgId(msgId);
        if (rec != null) {
            if (peer == 0) peer = rec.peerUin;
            if (user == 0) user = rec.senderUin;
            group = rec.chatType == QQClient.CT_GROUP;
        }
        if (obId == 0 && msgId == 0 && peer == 0) return;
        if (obId == 0 && msgId != 0) {
            MsgStore.Rec sr = new MsgStore.Rec();
            sr.chatType = group ? QQClient.CT_GROUP : QQClient.CT_C2C;
            sr.peerUin = peer;
            sr.peerUid = info;
            sr.msgId = msgId;
            sr.msgSeq = msgSeq;
            sr.senderUin = user;
            obId = store.put(sr);
        }
        emitRecall(group, peer, user, operator == 0 ? user : operator, obId, msgId);
    }

    private JSONObject friendRequestEvent(Object req) throws Exception {
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
        long userId = uinFromUid(uid);
        String comment = Ref.asStr(qq.ref.get(req, "extWords"));
        long time = reqTime > 1_000_000_000_000L ? reqTime / 1000 : reqTime;
        JSONObject ev = Notices.friendRequest(selfUin(), time, userId, comment, flag);
        pendingFriends.put(flag, new FriendReq(uid, reqTime, ev, decided));
        if (!seenRequests.add("f:" + flag)) return null;
        if (seenRequests.size() > 8000) seenRequests.clear();
        return ev;
    }

    private JSONObject groupRequestEvent(Object notify) throws Exception {
        if (notify == null) return null;
        Object status = qq.ref.get(notify, "status");
        boolean unhandled = status == null || enumName(status).contains("KUNHANDLE");
        Object type = qq.ref.get(notify, "type");
        String typeName = enumName(type);
        String sub;
        boolean user2 = false;
        boolean invite = false;
        if (typeName.contains("REQUESTJOINNEEDADMINISTRATORPASS")) sub = "add";
        else if (typeName.contains("INVITEDNEEDADMINISTRATORPASS")) sub = "add";
        else if (typeName.contains("INVITEDBYMEMBER")) { sub = "invite"; user2 = true; invite = true; }
        else return null;
        long seq = Ref.asLong(qq.ref.get(notify, "seq"));
        Object group = qq.ref.get(notify, "group");
        long groupCode = group == null ? 0 : Ref.asLong(qq.ref.get(group, "groupCode"));
        Object user = qq.ref.get(notify, user2 ? "user2" : "user1");
        String uid = user == null ? "" : Ref.asStr(qq.ref.get(user, "uid"));
        String flag = String.valueOf(seq);
        if (seq == 0 || groupCode == 0) return null;
        String comment = Ref.asStr(qq.ref.get(notify, "postscript"));
        long actionTime = Ref.asLong(qq.ref.get(notify, "actionTime"));
        long time = actionTime > 0 ? (actionTime > 1_000_000_000_000L ? actionTime / 1000 : actionTime)
                : System.currentTimeMillis() / 1000;
        JSONObject ev = Notices.groupRequest(selfUin(), time, groupCode, uinFromUid(uid), sub, comment, flag);
        pendingGroups.put(flag, new GroupReq(seq, groupCode, type, ev, invite, !unhandled));
        if (!unhandled) return null;
        if (!seenRequests.add("g:" + flag)) return null;
        if (seenRequests.size() > 8000) seenRequests.clear();
        return ev;
    }

    private void replayPendingRequests(WsConn conn) {
        for (FriendReq req : pendingFriends.values()) {
            if (req.event != null && !req.checked) sendSatori(conn, req.event);
        }
        for (GroupReq req : pendingGroups.values()) {
            if (req.event != null && !req.checked) sendSatori(conn, req.event);
        }
    }

    private void sendSatori(WsConn conn, JSONObject obEvent) {
        try {
            JSONObject body = Codec.toSatoriEvent(obEvent, loginSlim(), eventSn.incrementAndGet(), assetBase());
            if (body == null) return;
            conn.send(opJson(OP_EVENT, body).toString());
        } catch (Throwable ignore) {}
    }

    private void rememberGroup(long groupId, String name) {
        if (groupId != 0 && name != null && !name.isEmpty()) groupNames.put(groupId, name);
    }

    private void attachGroupName(JSONObject ob) {
        if (ob == null || !ob.has("group_id") || !ob.optString("group_name", "").isEmpty()) return;
        String name = groupNames.get(ob.optLong("group_id"));
        if (name == null || name.isEmpty()) return;
        try { ob.put("group_name", name); } catch (Exception ignore) {}
    }

    private void emitObEvent(JSONObject obEvent) {
        try {
            attachGroupName(obEvent);
            long sn = eventSn.incrementAndGet();
            JSONObject body = Codec.toSatoriEvent(obEvent, loginSlim(), sn, assetBase());
            if (body == null) return;
            emitSatoriEvent(body);
        } catch (Throwable t) {
            L.e("emit event", t);
        }
    }

    private void emitSatoriEvent(JSONObject body) throws Exception {
        rememberEvent(body);
        String payload = opJson(OP_EVENT, body).toString();
        for (WsConn c : identified) c.send(payload);
    }

    private void rememberEvent(JSONObject body) {
        recentEvents.add(body);
        while (recentEvents.size() > EVENT_BUFFER) {
            try { recentEvents.remove(0); } catch (Exception ignore) { break; }
        }
    }

    private void replayEvents(WsConn conn, long afterSn) {
        for (JSONObject body : recentEvents) {
            if (body.optLong("sn") <= afterSn) continue;
            try { conn.send(opJson(OP_EVENT, body).toString()); } catch (Throwable ignore) {}
        }
    }

    private JSONObject getFriendSystemMsg() throws Exception {
        qq.refreshBuddyReqs();
        JSONArray requests = new JSONArray();
        for (java.util.Map.Entry<String, FriendReq> e : pendingFriends.entrySet()) {
            FriendReq req = e.getValue();
            JSONObject ev = req.event;
            requests.put(new JSONObject()
                    .put("request_id", req.reqTime)
                    .put("requester_uin", ev == null ? 0 : ev.optLong("user_id"))
                    .put("message", ev == null ? "" : ev.optString("comment"))
                    .put("flag", e.getKey())
                    .put("checked", req.checked)
                    .put("user_id", ev == null ? 0 : ev.optLong("user_id"))
                    .put("comment", ev == null ? "" : ev.optString("comment")));
        }
        return new JSONObject().put("requests", requests);
    }

    private JSONObject getGroupSystemMsg() throws Exception {
        qq.refreshGroupNotifies();
        JSONArray join = new JSONArray();
        JSONArray invite = new JSONArray();
        for (java.util.Map.Entry<String, GroupReq> e : pendingGroups.entrySet()) {
            GroupReq req = e.getValue();
            JSONObject ev = req.event;
            JSONObject item = new JSONObject()
                    .put("request_id", req.seq)
                    .put("invitor_uin", ev == null ? 0 : ev.optLong("user_id"))
                    .put("invitor_nick", "")
                    .put("group_id", req.groupCode)
                    .put("message", ev == null ? "" : ev.optString("comment"))
                    .put("group_name", "")
                    .put("checked", req.checked)
                    .put("actor", 0)
                    .put("requester_nick", "")
                    .put("flag", e.getKey());
            if (req.invite) invite.put(item);
            else join.put(item);
        }
        return new JSONObject()
                .put("join_requests", join)
                .put("invited_requests", invite)
                .put("InvitedRequest", invite);
    }

    private static String enumName(Object e) {
        if (e == null) return "";
        if (e instanceof Enum) return ((Enum<?>) e).name();
        return String.valueOf(e);
    }

    private long uinFromUid(String uid) {
        if (uid == null || uid.isEmpty()) return 0;
        long uin = store.uinOf(uid);
        if (uin != 0) return uin;
        uin = qq.resolveUin(uid);
        if (uin != 0) store.learnUid(uin, uid);
        return uin;
    }

    private static boolean approveParam(JSONObject p) {
        if (!p.has("approve")) return true;
        Object v = p.opt("approve");
        if (v instanceof Boolean) return (Boolean) v;
        String s = String.valueOf(v);
        return !"false".equalsIgnoreCase(s) && !"0".equals(s);
    }

    private void setFriendAddRequest(String flag, boolean approve, String remark) {
        if (flag == null || flag.isEmpty()) throw new ApiError(1400, "missing flag");
        FriendReq req = pendingFriends.get(flag);
        if (req == null) {
            qq.refreshBuddyReqs();
            req = pendingFriends.get(flag);
        }
        long reqTime = req != null ? req.reqTime : parseLongQuiet(flag);
        String uid = req != null ? req.uid : "";
        if (uid.isEmpty()) throw new ApiError(1404, "unknown friend request flag");
        requireOp(qq.approvalFriendRequest(uid, approve, approve ? "" : remark, reqTime));
        pendingFriends.remove(flag);
    }

    private void setGroupAddRequest(String flag, boolean approve, String reason) {
        if (flag == null || flag.isEmpty()) throw new ApiError(1400, "missing flag");
        GroupReq req = pendingGroups.get(flag);
        if (req == null) {
            qq.refreshGroupNotifies();
            req = pendingGroups.get(flag);
        }
        if (req == null) throw new ApiError(1404, "unknown group request flag");
        requireOp(qq.operateGroupNotify(req.seq, req.groupCode, req.type, approve, reason));
        pendingGroups.remove(flag);
    }

    // ============ lifecycle / online status / heartbeat ============
    private void startStatusMonitor() {
        Thread t = new Thread(() -> {
            boolean previous = qq.isOnline();
            onlineSinceMs = previous ? System.currentTimeMillis() : 0;
            long interval = Math.max(1000L, cfg.heartbeatMs);
            long nextHeartbeat = System.currentTimeMillis() + interval;
            while (true) {
                try {
                    Thread.sleep(1000);
                    boolean online = qq.isOnline();
                    long now = System.currentTimeMillis();
                    if (online != previous) {
                        onlineSinceMs = online ? now : 0;
                        L.i("QQ kernel state -> " + (online ? "online" : "offline"));
                        emitLoginUpdated();
                        previous = online;
                    }
                    if (cfg.heartbeat && now >= nextHeartbeat) {
                        nextHeartbeat = now + interval;
                    }
                } catch (InterruptedException ie) { return; }
                catch (Throwable ignore) {}
            }
        }, "pool-5-thread-1");
        t.setDaemon(true);
        t.start();
    }

    private void emitLoginUpdated() {
        try {
            JSONObject body = new JSONObject()
                    .put("sn", eventSn.incrementAndGet())
                    .put("type", "login-updated")
                    .put("timestamp", System.currentTimeMillis())
                    .put("login", loginFull());
            String payload = opJson(OP_EVENT, body).toString();
            for (WsConn c : identified) c.send(payload);
        } catch (Throwable t) {
            L.e("login-updated", t);
        }
    }

    private JSONObject status(boolean online) throws Exception {
        return new JSONObject()
                .put("online", online)
                .put("good", online)
                .put("online_since_epoch_ms", onlineSinceMs)
                .put("outbound_guard", outboundGuard.stats())
                .put("fekit_attach", AntiDetect.fekitAttachStats(cfg.observeFekitAttach))
                .put("env_report", AntiDetect.envReportStats(cfg.blockO3Report));
    }

    private JSONObject versionInfo() throws Exception {
        String qqVersion = qq.qqVersion();
        return new JSONObject()
                .put("name", APP_NAME)
                .put("version", APP_VERSION)
                .put("protocol", "v1")
                .put("platform", PLATFORM)
                .put("adapter", ADAPTER)
                .put("qq_version", qqVersion.isEmpty() ? "unknown" : qqVersion)
                .put("runtime", "Android QQNT/Xposed")
                .put("hist", "60");
    }

    private JSONObject qzonePublish(JSONObject p) throws Exception {
        String content = p.optString("content", p.optString("text", ""));
        if (content.isEmpty()) throw new ApiError(1400, "missing content");
        int ugcRight = p.optInt("ugc_right", p.optInt("visibility", 1));
        return qzone.publish(content, ugcRight);
    }

    private JSONObject qzoneDelete(JSONObject p) throws Exception {
        String tid = p.optString("tid", p.optString("id", ""));
        if (tid.isEmpty()) throw new ApiError(1400, "missing tid");
        return qzone.delete(tid);
    }

    private JSONObject qzoneAuthDebug() throws Exception {
        JSONObject out = new JSONObject();
        String uin = qq.selfUin();
        out.put("uin", uin == null ? "" : uin);
        Object runtime = qq.appRuntime();
        String account = uin;
        if (runtime != null) {
            try {
                String acc = Ref.asStr(qq.ref.call(runtime, "getAccount"));
                if (!acc.isEmpty()) {
                    account = acc;
                    out.put("account", acc);
                }
            } catch (Throwable ignore) {}
        }
        Object ticketMgr = qq.getTicketManager();
        out.put("ticket_mgr", ticketMgr != null);
        if (ticketMgr != null && account != null && !account.isEmpty()) {
            for (String label : new String[]{"skey", "real_skey", "pskey", "stweb"}) {
                try {
                    Object v = null;
                    if ("skey".equals(label)) v = qq.ref.call(ticketMgr, "getSkey", account);
                    else if ("real_skey".equals(label)) v = qq.ref.call(ticketMgr, "getRealSkey", account);
                    else if ("pskey".equals(label)) v = qq.ref.call(ticketMgr, "getPskey", account, "qzone.qq.com");
                    else if ("stweb".equals(label)) v = qq.ref.call(ticketMgr, "getStweb", account);
                    out.put(label + "_len", Ref.asStr(v).length());
                } catch (Throwable t) {
                    out.put(label + "_err", String.valueOf(t.getMessage()));
                }
            }
        }
        try {
            String ps = qq.fetchPskeyViaManager("qzone.qq.com");
            out.put("mgr_pskey_len", ps == null ? 0 : ps.length());
        } catch (Throwable t) {
            out.put("mgr_pskey_err", String.valueOf(t.getMessage()));
        }
        try {
            QzoneSvc.Auth a = qq.fetchQzoneAuth();
            out.put("auth_skey_len", a.skey == null ? 0 : a.skey.length());
            out.put("auth_pskey_len", a.pskey == null ? 0 : a.pskey.length());
        } catch (Throwable t) {
            out.put("auth_err", String.valueOf(t.getMessage()));
        }
        return out;
    }

    private JSONObject qzoneList(JSONObject p) throws Exception {
        int limit = Math.max(1, Math.min(100, p.optInt("limit", 50)));
        java.util.List<QzoneSvc.Mood> moods = qzone.listAll(limit);
        JSONArray data = new JSONArray();
        for (QzoneSvc.Mood m : moods) {
            data.put(new JSONObject()
                    .put("tid", m.tid)
                    .put("content", m.content == null ? "" : m.content)
                    .put("created_at", m.createdTime)
                    .put("ugc_right", m.ugcRight));
        }
        return new JSONObject().put("data", data);
    }

    private void scheduleRestart(int delayMs) {
        Thread t = new Thread(() -> {
            try { Thread.sleep(delayMs); } catch (InterruptedException ignore) { return; }
            L.i("restart requested; exiting QQ for external watchdog recovery");
            Runtime.getRuntime().exit(0);
        }, "pool-5-thread-2");
        t.setDaemon(true);
        t.start();
    }
}
