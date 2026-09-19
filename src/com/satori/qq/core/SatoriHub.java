package com.satori.qq.core;

import com.satori.qq.Cfg;
import com.satori.qq.L;
import com.satori.qq.net.HttpServer;
import com.satori.qq.net.WsConn;
import com.satori.qq.packet.LongMsg;
import com.satori.qq.packet.PacketSvc;
import com.satori.qq.packet.Pb;
import com.satori.qq.qq.Convert;
import com.satori.qq.qq.Compat;
import com.satori.qq.qq.ExtraSvc;
import com.satori.qq.qq.Media;
import com.satori.qq.qq.QQClient;
import com.satori.qq.qq.Ref;
import com.satori.qq.satori.Codec;
import com.satori.qq.satori.Elements;
import com.satori.qq.satori.Multipart;
import com.satori.qq.satori.Protocol;
import com.satori.qq.xp.Xp;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Satori v1 hub: HTTP RPC in + WebSocket events out. QQ kernel ops stay below this layer. */
public final class SatoriHub implements HttpServer.Handler, QQClient.Listener {
    public static final String APP_NAME = "satori-qq";
    public static final String APP_VERSION = "0.23.14";
    public static final String PLATFORM = "red";
    public static final String ADAPTER = "satori-qq";

    private static final int OP_EVENT = 0, OP_PING = 1, OP_PONG = 2, OP_IDENTIFY = 3, OP_READY = 4, OP_META = 5;

    private final Cfg cfg;
    private final QQClient qq;
    private final MsgStore store;
    private final Convert conv;
    private final OutboundGuard outboundGuard;
    private final MessageFreshness messageFreshness = new MessageFreshness();
    private final Object eventEmitLock = new Object();
    // HTTP requests run on separate threads; never share a send condition between callers.
    private final ThreadLocal<MessageFreshness.Condition> sendCondition = new ThreadLocal<>();
    private HttpServer server;
    private volatile StatusNotice notice;
    private volatile com.satori.qq.qq.WakeLockCtl wakeLock;
    private volatile long onlineSinceMs;
    private final Set<WsConn> identified = ConcurrentHashMap.newKeySet();
    // Clients that identified before QQ had an account to name. READY is what hands a client the
    // login it latches onto, so it waits here until selfUin() resolves; see handleIdentify.
    private final ConcurrentHashMap<WsConn, JSONObject> awaitingReady = new ConcurrentHashMap<>();
    private final AtomicLong eventSn = new AtomicLong();
    private final Object recentEventsLock = new Object();
    private final java.util.ArrayDeque<JSONObject> recentEvents = new java.util.ArrayDeque<>();
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
    /** Replay storage only; events are broadcast before this method returns and are never batched. */
    private static final int EVENT_BUFFER = 4096;
    /**
     * All-member mute has no duration in QQ's kernel call, only a boolean, so the module owns the
     * timer. An `enable: true` request without an explicit duration therefore means "until turned
     * off"; 30 days is QQ's own ceiling for a timed mute and doubles as "no deadline" here.
     */
    private static final long DEFAULT_CHANNEL_MUTE_MS = 30L * 24 * 3600 * 1000;
    /** Page size used when a caller passes `next` without `limit`. */
    private static final int PAGE_DEFAULT = 200;

    public SatoriHub(Cfg cfg, QQClient qq, MsgStore store) {
        this.cfg = cfg; this.qq = qq; this.store = store;
        this.conv = new Convert(qq, store);
        this.outboundGuard = new OutboundGuard(cfg.outboundMinIntervalMs,
                cfg.outboundQueueTimeoutMs, cfg.outboundMaxQueued,
                cfg.outboundMaxPerMinute, cfg.outboundFailureThreshold,
                cfg.outboundCircuitOpenMs);
    }

    public void start() {
        server = new HttpServer(cfg, this);
        qq.setListener(this);
        Thread bootstrap = new Thread(() -> {
            // QQ hooks are installed immediately by Main. Only the listener waits for a Context.
            android.content.Context context = qq.appContext();
            for (int retry = 0; !contextReady(context) && retry < 200; retry++) {
                try { Thread.sleep(50); } catch (InterruptedException stopped) { return; }
                context = qq.appContext();
            }
            if (contextReady(context)) com.satori.qq.control.ControlBridge.bootstrap(context, cfg, APP_VERSION);
            server.start();
            refreshNotice();
            startStatusMonitor();
        }, "pool-4-bootstrap");
        bootstrap.setDaemon(true);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(bootstrap::start);
    }

    private static boolean contextReady(android.content.Context context) {
        return context != null && (!(context instanceof android.content.ContextWrapper)
                || ((android.content.ContextWrapper) context).getBaseContext() != null);
    }

    /** Refresh the resident notification and drive foreground-service keepalive. Only radio sustain when both are
     *  disabled. The Context-bound helpers are created lazily: at hub start QQ's Application (and thus
     *  its Context) is not ready yet, so we keep retrying each monitor tick until it is. */
    private void refreshNotice() {
        if (!cfg.statusNotification) {
            // Radio sustain is independent of whether a notification is requested.
            driveWifiSustain(qq.isOnline(), server != null && server.isListening());
            return;
        }
        StatusNotice n = notice;
        if (n == null || !n.available()) {
            android.content.Context ctx = qq.appContext();
            if (ctx == null) return; // Application not created yet; try again next tick
            StatusNotice created = new StatusNotice(ctx);
            if (!created.available()) return;
            notice = created;
            n = created;
        }
        if (cfg.wakeLockControl || cfg.wifiSustain) ensureWakeLock(n);
        boolean online, listening;
        try {
            online = qq.isOnline();
            listening = server != null && server.isListening();
        } catch (Throwable t) { return; }
        int conns = server == null ? 0 : server.connectionCount();
        String uin = qq.selfUin(), nick = qq.selfNick();

        // Human-readable status entry.
        try {
            n.update(online, listening, uin, nick, cfg.port, conns, onlineSinceMs);
        } catch (Throwable t) { L.e("status notice refresh", t); }

        try { driveWifiSustain(online, listening); }
        catch (Throwable t) { L.e("wifi sustain tick", t); }
    }

    /** Lazily create the Termux-style wake-lock toggle and hang it on the resident notification, so
     *  its acquire/release action button rides the same entry. A tap redraws the notice via refresh. */
    private void ensureWakeLock(StatusNotice n) {
        com.satori.qq.qq.WakeLockCtl w = ensureWakeLockController();
        // The notification button only appears when the operator toggle itself is enabled;
        // wifi_sustain alone needs the controller, not the button.
        if (w != null && n != null && cfg.wakeLockControl) n.setWake(w);
    }

    /** Create the wake-lock controller on first use. Acquires the lock itself when wake_lock_auto. */
    private com.satori.qq.qq.WakeLockCtl ensureWakeLockController() {
        com.satori.qq.qq.WakeLockCtl w = wakeLock;
        if (w != null) return w;
        android.content.Context ctx = qq.appContext();
        if (ctx == null) return null;
        w = new com.satori.qq.qq.WakeLockCtl(ctx, cfg.wakeLockAuto, this::refreshNotice);
        wakeLock = w;
        return w;
    }

    /** Keep the radio out of screen-off power save while a client is actually attached to us. */
    private void driveWifiSustain(boolean online, boolean listening) {
        com.satori.qq.qq.WakeLockCtl w = wakeLock;
        if (w == null) return;
        boolean serving = online && listening && server != null && server.connectionCount() > 0;
        w.sustainWifi(cfg.wifiSustain && serving);
    }

    private long selfUin() { try { return Long.parseLong(qq.selfUin()); } catch (Throwable t) { return 0; } }

    private volatile JSONObject compatCache;
    private volatile String compatCacheVersion = "";
    private volatile long compatCacheMs;
    /** 静态自检不快，但也不必每次探针都跑；换了个 QQ 版本或过了 10 分钟就重算。 */
    private static final long COMPAT_TTL_MS = 10 * 60 * 1000L;

    /** 内核接口面自检。QQ 版本变了就重算，便于升级后第一眼看到断在哪。 */
    private JSONObject compatReport(boolean force) {
        try {
            String version = qq.qqVersion();
            long now = System.currentTimeMillis();
            JSONObject cached = compatCache;
            if (!force && cached != null && version.equals(compatCacheVersion)
                    && now - compatCacheMs < COMPAT_TTL_MS) return cached;
            JSONObject fresh = Compat.audit(qq.ref, version);
            compatCache = fresh;
            compatCacheVersion = version;
            compatCacheMs = now;
            if (!fresh.optBoolean("ok")) {
                // 只在坏掉时才吱声：正常时每个探针周期打一行会把日志刷满。
                L.e("compat: " + fresh.optInt("passed") + "/" + fresh.optInt("total")
                        + " 内核接口面通过，缺 " + fresh.optJSONArray("missing"), null);
            }
            return fresh;
        } catch (Throwable t) {
            L.e("compat audit", t);
            try {
                return new JSONObject().put("ok", false).put("error", String.valueOf(t));
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }

    private JSONObject compatSummary() throws Exception {
        JSONObject full = compatReport(false);
        if (full.has("error")) return new JSONObject().put("ok", false).put("error", full.opt("error"));
        return new JSONObject()
                .put("ok", full.optBoolean("ok"))
                .put("passed", full.optInt("passed"))
                .put("total", full.optInt("total"))
                .put("missing", full.optJSONArray("missing") == null
                        ? 0 : full.optJSONArray("missing").length());
    }

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
        login.put("hidden", false);
        login.put("self_id", String.valueOf(selfUin()));
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
                .put("guild.member.role.list")
                .put("guild.role.list")
                .put("reaction.create")
                .put("reaction.delete")
                .put("reaction.clear")
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
        return Protocol.eventLogin(0, PLATFORM, Codec.user(selfUin(), qq.selfNick(), ""));
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
            if (path.startsWith("/v1/internal/") && "GET".equals(req.method)) {
                return serveInternalResource(path);
            }
            if ("GET".equals(req.method) && "/healthz".equals(path)) {
                // Unauthenticated, local-only liveness an operator/tooling can poll to tell a
                // truly-online hub from "port up but kernel offline" without an activity dump.
                boolean online = qq.isOnline();
                return HttpServer.HttpResult.json(online ? 200 : 503, new JSONObject()
                        .put("name", APP_NAME).put("version", APP_VERSION)
                        .put("config_revision", com.satori.qq.control.ControlBridge.revision())
                        .put("config_status", com.satori.qq.control.ControlBridge.status())
                        .put("online", online)
                        .put("listening", server != null && server.isListening())
                        .put("self_id", selfUin())
                        .put("qq_version", qq.qqVersion())
                        .put("compat", compatSummary())
                        .put("online_since_epoch_ms", onlineSinceMs)
                        .put("connections", server == null ? 0 : server.connectionCount())
                        .put("notice", noticeDiag())
                        .put("wakelock", wakeLockDiag())
                        // 抗冻状态：adj 低于本机 freezer 阈值（900）就不会被冻，
                        // service 是「让 QQ 自己的服务保持已启动」那一步的结果。
                        .put("keepalive", com.satori.qq.qq.Keepalive.diag())
                        .put("name_guard", nameGuardDiag())
                        // 模块自己发的 SSO 请求失败了几条。`session_errors` 涨了说明有请求
                        // 撞上 QQ 认「票据失效」的那组错误码——即「接口层把会话打废」，
                        // 而不是环境检测。这是把踢线成因分开的判据，详见 PacketSvc。
                        .put("sso", new JSONObject()
                                .put("hooked", PacketSvc.ssoHookInstalled())
                                .put("native", Xp.ssoHookInfo())
                                .put("failures", PacketSvc.ssoFailures())
                                .put("session_errors", PacketSvc.ssoSessionErrors())
                                .put("log", new org.json.JSONArray(PacketSvc.ssoLog())))
                        .put("session", qq.sessionDiag())
                        .toString());
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
            if (method.startsWith("internal/")) {
                return jsonResult(internalPg(req, dispatchInternal(method.substring("internal/".length()),
                        parseInternalBody(req))));
            }
            JSONObject body = parseBody(req);
            if (OutboundGuard.isMutation(method)) return jsonResult(guarded(method, () -> dispatch(method, body)));
            return jsonResult(dispatch(method, body));
        } catch (RemovedAction ra) {
            // 404 但带 code：这是「曾经有过、现在没了」，不是方法名写错。
            return HttpServer.HttpResult.json(404, errorJson(ra.getMessage(), "removed_action"));
        } catch (NotImplemented ni) {
            // 404, not 501: every method here is one QQ has no equivalent for at all. The body
            // stays JSON like every other error so a client can parse it uniformly.
            return HttpServer.HttpResult.json(404, errorJson(ni.getMessage()));
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
            if (!identified.contains(conn) && !awaitingReady.containsKey(conn)) conn.close();
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
                handleIdentify(conn, body);
                return;
            }
            if (!identified.contains(conn) && !awaitingReady.containsKey(conn)) return;
            if (op == OP_PING) {
                conn.send(opJson(OP_PONG, new JSONObject()).toString());
            }
        } catch (Throwable t) {
            L.e("ws opcode " + op, t);
        }
    }

    /**
     * Answer IDENTIFY with READY, but only once the login is nameable.
     *
     * <p>READY carries the login a client latches onto and echoes back as {@code Satori-User-ID} on
     * every later request. Emitting it while QQ's account is still unknown advertises id {@code 0},
     * which the client then holds for the life of the connection; {@link #validateLoginHeaders}
     * rejects every such request as addressed to a login we do not serve, so nothing can be sent
     * until the client reconnects. Hold READY until an account exists — it goes out on the next
     * status tick (see {@code startStatusMonitor}).
     */
    private void handleIdentify(WsConn conn, JSONObject body) throws Exception {
        if (cfg.token != null && !cfg.token.isEmpty()
                && !cfg.token.equals(body.optString("token", ""))) {
            conn.close();
            return;
        }
        if (selfUin() == 0) {
            awaitingReady.put(conn, body);
            return;
        }
        identified.add(conn);
        sendReady(conn, body);
    }

    private void sendReady(WsConn conn, JSONObject identifyBody) throws Exception {
        conn.send(opJson(OP_READY, new JSONObject()
                .put("logins", new JSONArray().put(loginFull()))
                .put("proxy_urls", new JSONArray())).toString());
        if (Protocol.shouldReplay(identifyBody)) replayEvents(conn, identifyBody.optLong("sn", 0));
        replayPendingRequests(conn);
    }

    @Override public void onWsClose(WsConn conn) {
        identified.remove(conn);
        awaitingReady.remove(conn);
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

    /**
     * Koishi's Satori internal proxy encodes method arguments as a JSON array, sent as JSON when
     * nothing is a blob and as {@code multipart/form-data} with the array under the field
     * {@code $} when something is. Both shapes arrive here.
     */
    private JSONObject parseInternalBody(HttpServer.HttpReq req) {
        String text = req.bodyText();
        String ctype = req.header("content-type");
        if (ctype != null && ctype.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/form-data")) {
            try {
                for (Multipart.Part part : Multipart.parse(req.body, ctype)) {
                    if ("$".equals(part.name)) text = new String(part.data, "UTF-8");
                }
            } catch (Exception e) {
                throw new ApiError(1400, "malformed internal multipart body");
            }
        }
        if (text == null || text.trim().isEmpty()) return new JSONObject();
        String value = text.trim();
        try {
            if (!value.startsWith("[")) return new JSONObject(value);
            JSONArray args = new JSONArray(value);
            if (args.length() == 0) return new JSONObject();
            JSONObject first = args.optJSONObject(0);
            if (args.length() == 1 && first != null) return first;
            return new JSONObject().put("_args", args);
        } catch (Exception e) {
            throw new ApiError(1400, "malformed internal request body");
        }
    }

    /**
     * Honour {@code Satori-Pagination: true}, which the client sends when it is going to drive
     * {@code for await}. It only accepts an object with a {@code data} array, so wrap a bare array
     * (our list-shaped actions return one) and leave an already-paginated result alone. Actions
     * return everything in one page; the client then stops on the missing {@code next}.
     */
    private Object internalPg(HttpServer.HttpReq req, Object data) throws Exception {
        String header = req.header("satori-pagination");
        if (header == null || !"true".equalsIgnoreCase(header.trim())) return data;
        if (data instanceof JSONArray) return new JSONObject().put("data", (JSONArray) data);
        if (data instanceof JSONObject) {
            JSONObject o = (JSONObject) data;
            if (o.optJSONArray("data") != null) return o;
            return new JSONObject().put("data", new JSONArray().put(o));
        }
        return new JSONObject().put("data", new JSONArray());
    }

    private HttpServer.HttpResult jsonResult(Object data) {
        if (data == null) return HttpServer.HttpResult.json(200, "null");
        return HttpServer.HttpResult.json(200, data.toString());
    }

    private static String errorJson(String msg) {
        return errorJson(msg, null);
    }

    /** 错误体。带 `code` 时客户端可以按机器可读的字段判断，不必去匹配文案。 */
    private static String errorJson(String msg, String code) {
        try {
            JSONObject o = new JSONObject().put("message", msg == null ? "" : msg);
            if (code != null) o.put("code", code);
            return o.toString();
        } catch (Exception e) { return "{\"message\":\"error\"}"; }
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

    /**
     * The Satori client's login-scoped internal route, {@code /v1/internal/{platform}/{selfId}/…}.
     *
     * <p>Resource ids that {@code upload.create} hands out are written as
     * {@code internal:{platform}/{selfId}/_tmp/{id}}. A client resolving one of those goes through
     * its own internal router and comes back here as a plain GET, so without this route the id we
     * just returned cannot be read. Local-only and unauthenticated for the same reason
     * {@code /v1/assets/{id}} is: the fetch carries no token.
     */
    private HttpServer.HttpResult serveInternalResource(String path) {
        String[] parts = path.substring("/v1/internal/".length()).split("/", 3);
        if (parts.length < 3 || parts[2].isEmpty()) return HttpServer.HttpResult.text(404, "not found");
        if (!PLATFORM.equals(parts[0]) || isForeignLogin(parts[1], selfUin()))
            return HttpServer.HttpResult.json(404, errorJson("internal login not found"));
        if (parts[2].startsWith("_tmp/")) return serveAsset(parts[2].substring("_tmp/".length()));
        // _api is POST-only; anything else under a login is not a resource we publish.
        return HttpServer.HttpResult.text(404, "not found");
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
        if (isForeignLogin(userId, selfUin()))
            throw new ApiError(1404, "unknown Satori-User-ID: " + userId);
    }

    /**
     * Whether a {@code Satori-User-ID} names a login we do not serve.
     *
     * <p>An absent selector is not a foreign one. Neither is {@code 0}: that is the placeholder this
     * hub used to advertise in READY before QQ's account was known, and a client that connected
     * then keeps echoing it for the life of its connection. Treating it as foreign rejects every
     * request from that client, and since nothing about the selector ever changes on its own, the
     * client stays mute until it reconnects. Anything else really is another login.
     */
    public static boolean isForeignLogin(String userId, long selfUin) {
        if (userId == null || userId.isEmpty() || "0".equals(userId)) return false;
        return selfUin != 0 && !String.valueOf(selfUin).equals(userId);
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

    /**
     * 曾经提供、后来移除的动作。
     *
     * <p>必须和「从来没这个方法」分开报：客户端要靠这个把能力标成不可用，而不是每轮重试
     * （ayjx 的资料卡点赞就是这种用法——它按回执文案记住「平台不让做」，之后就不再调）。
     * 所以除了 404，响应体里还带 `code=removed_action`。
     */
    private static final class RemovedAction extends RuntimeException {
        RemovedAction(String method, String since, String why) {
            super(method + " 已移除（" + since + "起）：" + why);
        }
    }

    /** 0.17.0 收掉的 QQ 内核查询与本地会话状态接口。 */
    private static final java.util.Set<String> REMOVED_0_17 = new java.util.HashSet<>(java.util.Arrays.asList(
            "group_overview", "group_extra", "member_info", "group_member_search", "recent_contacts",
            "contact_search", "friend_relation", "group_remark", "profile_self", "group_honor",
            "group_shut_up_list", "group_active", "group_anniversary", "group_detail",
            "group_statistic", "user_detail", "voice_to_text", "message_context", "message_search",
            "group_file", "get_resource", "mark_read", "session_top", "group_msg_mask",
            "qzone.publish", "offline"));

    /** 0.23.0 收掉的：这些在旧实现里靠通用 Java hook 才成立。 */
    private static final java.util.Map<String, String> REMOVED_0_23 = new java.util.HashMap<>();
    static {
        REMOVED_0_23.put("like", "资料卡点赞走 QQ 的 WUP/Handler 通道，本实现端只走 JNI 层");
    }

    /** 动作是不是「移除过」，是的话返回移除时的版本。 */
    private static String removedSince(String name) {
        if (REMOVED_0_23.containsKey(name)) return "0.23.0";
        if (REMOVED_0_17.contains(name)) return "0.17.0";
        return null;
    }

    private static String removedWhy(String name, String since) {
        String why = REMOVED_0_23.get(name);
        return why != null ? why : "QQ 内核查询接口不再向客户端暴露";
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
        boolean kernelNeutral = false;
        com.satori.qq.qq.WakeLockCtl w = wakeLock;
        // QQ's kernel uploads media inline while sendMsg runs; on a locked screen a parked CPU
        // and Wi-Fi radio make that transfer fail while plain text still rides the live socket.
        if (w != null) w.begin();
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
        } catch (Throwable t) {
            kernelNeutral = isKernelNeutralFailure(t);
            throw t;
        } finally {
            if (lease != null) {
                if (!ok && kernelNeutral) lease.failTransport(); else lease.complete(ok);
            }
            if (w != null) w.end();
        }
    }

    /** True for an error whose cause is the network under one payload, not QQ refusing to work. */
    /**
     * 这次失败说不说明内核不健康？说不说明的，就不该开熔断。
     *
     * <p>两类：
     * <ul>
     *   <li>富媒体上传失败——调用方的下一个动作通常是同一连接上的纯文本兜底，那一条往往还能过；
     *       把它算进熔断，等于把「降级但可用」变成持续 {@code circuitOpenMs} 的全断。</li>
     *   <li>**调用方参数就不对**（1400／1404）。那是我们自己在进内核之前回绝的，跟内核健不健康没有
     *       关系。不排除的话，任何客户端连发三个缺参请求就能把出站通道锁两分钟
     *       （2026-09-19 实测：巡检里的缺参用例正好把熔断打开了，后面十几项全被拒）。</li>
     * </ul>
     */
    private static boolean isKernelNeutralFailure(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof ApiError) {
                int code = ((ApiError) c).code;
                if (code == 1400 || code == 1404) return true;
            }
            if (c instanceof NotImplemented) return true;
            String m = c.getMessage();
            if (m == null) continue;
            String lower = m.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("rich media") || lower.contains("media transfer")
                    || lower.contains("富媒体")) return true;
        }
        return false;
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
                muteChannel(guildIdOf(p), resolveChannelMuteMs(p));
                return new JSONObject();
            }
            case "user.channel.create": {
                long uid = parseId(p.optString("user_id", ""));
                if (uid == 0) throw new ApiError(1400, "missing user_id");
                return Codec.channel(QQClient.CT_C2C, uid, "");
            }
            case "guild.get": return satoriGuildGet(p);
            case "guild.list": return pagedList(satoriGuildList(), p);
            case "guild.member.get": return satoriMember(p);
            case "guild.member.list": return pagedList(satoriMemberList(p), p);
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
            case "guild.member.role.list": {
                long g = guildIdOf(p), u = parseId(p.optString("user_id", ""));
                if (u == 0) throw new ApiError(1400, "missing user_id");
                JSONObject member = memberToSatori(getGroupMemberInfo(g, u));
                JSONArray roles = member.optJSONArray("roles");
                return pagedList(roles == null ? new JSONArray() : roles, p);
            }
            case "guild.role.list":
                return pagedList(guildRoles(guildIdOf(p)), p);
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
            case "reaction.clear": {
                String emojiRaw = p.optString("emoji_id", "");
                int messageId = requireMessage(p.optString("message_id", ""), p).id;
                validateMessageChannel(p, messageId);
                if (!emojiRaw.trim().isEmpty()) {
                    setEmojiLike(messageId, parseEmoji(emojiRaw), emojiRaw, false);
                    return new JSONObject();
                }
                // QQ has no "drop every reaction on this message" call. The kernel can only
                // withdraw the ones this login set, so enumerate those and remove each.
                java.util.List<String> keys = reactionEmojiKeys(messageId);
                if (keys.isEmpty())
                    throw new ApiError(1404, "no reaction set by this login on the message");
                String lastError = "";
                int cleared = 0;
                for (String key : keys) {
                    try {
                        setEmojiLike(messageId, parseEmoji(key), key, false);
                        cleared++;
                    } catch (ApiError e) {
                        lastError = e.getMessage();
                    }
                }
                if (cleared == 0) throw new ApiError(1500, "reaction clear failed: " + lastError);
                return new JSONObject().put("cleared", cleared);
            }
            case "reaction.list": {
                String emojiRaw = p.optString("emoji_id", "").trim();
                int messageId = requireMessage(p.optString("message_id", ""), p).id;
                validateMessageChannel(p, messageId);
                // 协议里 emoji_id 是可选的，但 QQ 内核的 getMsgEmojiLikesList 一次只认一个表情，
                // 也没有「列出这条消息上所有表态的人」的调用。不带 emoji_id 时给出本登录号自己
                // 加过的那些（与 reaction.clear 同源），至少不把合规客户端挡在 400 外面。
                if (emojiRaw.isEmpty()) return localReactionUsers(messageId);
                return reactionList(messageId, parseEmoji(emojiRaw), emojiRaw,
                        p.optString("next", ""));
            }
            case "user.get": return satoriUser(p);
            case "friend.list": return pagedList(satoriFriendList(), p);
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
            default:
                throw new NotImplemented(method);
        }
    }

    private Object dispatchInternal(String name, JSONObject p) throws Exception {
        final JSONObject params = p == null ? new JSONObject() : p;
        name = normalizeInternalPath(name);
        if (name.startsWith("_api/")) name = name.substring(5);
        if (name.startsWith("/")) name = name.substring(1);
        name = normalizeInternalName(name);
        // 只留这一份白名单。QQ 的批量查询与本地会话状态那批内核接口在 0.17.0 整批撤掉：
        // 它们要按号去问 QQ 要资料，跟真人客户端的行为对不上，是这批接口里最显眼的一类。
        // 保留的是「会真的产生一次出站动作」的那几个（戳、赞、头衔、名片、打卡、精华、
        // 邀请、骰子、猜拳），加上合并转发元素唯一的那条解析路径（get_forward），
        // 以及模块自己的运维口。其余一律 404。
        switch (name) {
            case "poke":
                return guarded("internal.poke", () -> {
                    sendPoke(params.optLong("guild_id", params.optLong("group_id", 0)),
                            parseId(params.optString("user_id", "")));
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
            case "honor-display":
            case "honor_display":
                return guarded("internal.honor_display", () -> setGroupHonorDisplay(
                        params.optLong("guild_id", params.optLong("group_id", 0)),
                        params.optBoolean("show", params.optBoolean("enable", true))));
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
            case "invite":
                return guarded("internal.invite", () -> {
                    long g = params.optLong("guild_id", params.optLong("group_id", 0));
                    long u = parseId(params.optString("user_id", ""));
                    if (g == 0 || u == 0) throw new ApiError(1400, "missing guild_id/user_id");
                    requireOp(qq.inviteToGroup(g, uidFor(g, u)));
                    return new JSONObject();
                });
            case "dice":
                return guarded("internal.dice", () -> sendSpecialFace(params, Codec.DICE_FACE, "dice"));
            case "rps":
            case "rock-paper-scissors":
            case "rock_paper_scissors":
                return guarded("internal.rps", () -> sendSpecialFace(params, Codec.RPS_FACE, "rps"));
            case "get-forward":
            case "get_forward":
                // 合并转发在入站只有 `<message forward id="…"/>` 一个元素，没有正文，
                // 也没有第二条能取回它的路，所以这条解析保留。
                return satoriGetForward(params);
            case "capabilities":
            case "help":
                return internalCapabilities();
            case "compat":
            case "selftest":
                return new JSONObject()
                        .put("static", compatReport(p.optBoolean("force", false)))
                        .put("observed", Compat.observed());
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
            default:
                String gone = removedSince(name);
                if (gone != null) throw new RemovedAction("internal/" + name, gone, removedWhy(name, gone));
                throw new NotImplemented("internal/" + name);
        }
    }

    /** Accept both direct internal/name URLs and the official adapter's login-scoped proxy URL. */
    private String normalizeInternalPath(String name) {
        String value = name == null ? "" : name;
        String platformPrefix = PLATFORM + "/";
        if (!value.startsWith(platformPrefix)) return value;
        String expected = platformPrefix + selfUin() + "/";
        if (!value.startsWith(expected)) throw new ApiError(1404, "internal login not found");
        return value.substring(expected.length());
    }

    /** JavaScript callers naturally use camelCase; direct HTTP callers often use snake_case. */
    private static String normalizeInternalName(String name) {
        String value = name == null ? "" : name.trim();
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '-') c = '_';
            if (Character.isUpperCase(c)) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != '_'
                        && out.charAt(out.length() - 1) != '.') out.append('_');
                out.append(Character.toLowerCase(c));
            } else {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    /** QQ's built-in random dice/RPS faces (358/359), sent to a group or direct channel. */
    /**
     * 超级表情的 `FaceElement.faceType`：3 = 动画贴纸（NapCat 的 `FaceType.AniSticke`）。
     * 骰子与猜拳只有用这个值、并带上贴纸身份，QQ 才会画成整条放大的动画。
     */
    private static final int SPECIAL_FACE_TYPE = 3;

    private JSONObject sendSpecialFace(JSONObject p, int faceId, String kind) throws Exception {
        String channelId = p.optString("channel_id", "");
        long groupId = p.optLong("guild_id", p.optLong("group_id", 0));
        long userId = parseId(p.optString("user_id", ""));
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
        JSONObject sent;
        if (groupId != 0) sent = sendGroup(groupId, message, "<emoji id=\"" + faceId + "\"/>");
        else if (userId != 0) sent = sendPrivate(userId, message, "<emoji id=\"" + faceId + "\"/>");
        else throw new ApiError(1400, "missing channel_id, guild_id, or user_id");
        sent.put("kind", kind).put("face_id", faceId).put("face_type", SPECIAL_FACE_TYPE);
        return sent;
    }

    // ---------------------------------------------- personal profile / groups / buddies

    // ------------------------------------------------------------------ helpers

    /** Reflect a kernel payload into JSON so read actions stay tolerant of QQ's field churn. */
    private static Object toJson(Object v) throws Exception {
        return toJson(v, 4, 50);
    }

    private static Object toJson(Object v, int depth, int listCap) throws Exception {
        if (v == null) return JSONObject.NULL;
        if (v instanceof String || v instanceof Number || v instanceof Boolean) return v;
        if (v instanceof byte[]) return "bytes:" + ((byte[]) v).length;
        if (v instanceof Character) return String.valueOf(v);
        if (v.getClass().isEnum()) return String.valueOf(v);
        if (depth <= 0) return String.valueOf(v);
        if (v instanceof java.util.Map) {
            JSONObject o = new JSONObject();
            int n = 0;
            for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) v).entrySet()) {
                if (n++ >= 200) break;
                putQuiet(o, String.valueOf(e.getKey()), toJson(e.getValue(), depth - 1, listCap));
            }
            return o;
        }
        if (v instanceof java.util.Collection) {
            JSONArray a = new JSONArray();
            int n = 0;
            for (Object e : (java.util.Collection<?>) v) {
                if (n++ >= listCap) { a.put("..."); break; }
                a.put(toJson(e, depth - 1, listCap));
            }
            return a;
        }
        JSONObject o = new JSONObject();
        for (Class<?> c = v.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    putQuiet(o, f.getName(), toJson(f.get(v), depth - 1, listCap));
                } catch (Throwable ignore) {}
            }
        }
        return o;
    }

    private static void putQuiet(JSONObject o, String k, Object val) {
        try { o.put(k, val); } catch (Throwable ignore) {}
    }

    /** Machine-readable inventory for clients that want QQ-only extensions. */
    private JSONObject internalCapabilities() throws Exception {
        // 目录与 dispatchInternal 的白名单一一对应。0.17.0 起这里不再列 QQ 的批量查询与
        // 本地会话状态那些内核接口；客户端原来靠它探测能力，现在探测到的就是全部。
        return new JSONObject()
                .put("version", APP_VERSION)
                // 只列真正还实现得出来的（移除过的见下面的 removed）：列了却 404 会让客户端
                // 白试一轮，ayjx 的资料卡点赞就吃过这个亏。
                .put("actions", new JSONArray()
                        .put("poke").put("invite")
                        .put("card").put("special_title").put("title_display")
                        .put("honor_display").put("sign").put("essence")
                        .put("dice").put("rps").put("get_forward")
                        .put("capabilities").put("compat")
                        .put("status").put("version")
                        .put("clean_cache").put("restart"))
                .put("special_faces", new JSONObject()
                        .put("dice", Codec.DICE_FACE).put("rps", Codec.RPS_FACE)
                        .put("face_type", SPECIAL_FACE_TYPE))
                .put("params", new JSONObject()
                        .put("poke", "guild_id, user_id")
                        .put("invite", "guild_id, user_id")
                        .put("card", "guild_id, user_id?, card")
                        .put("special_title", "guild_id, user_id, title")
                        .put("title_display", "guild_id, show?")
                        .put("honor_display", "guild_id, show?")
                        .put("sign", "guild_id")
                        .put("essence", "guild_id, message_id, remove?")
                        .put("dice", "channel_id|guild_id")
                        .put("rps", "channel_id|guild_id")
                        .put("get_forward", "id 或 native:<父消息 ID>，可选 channel_id")
                        .put("compat", "force?"))
                .put("read_actions", new JSONArray()
                        .put("get_forward")
                        .put("status").put("version").put("capabilities").put("compat"))
                .put("write_actions", new JSONArray()
                        .put("poke").put("invite").put("card")
                        .put("special_title").put("title_display").put("honor_display")
                        .put("sign").put("essence").put("dice").put("rps")
                        .put("clean_cache").put("restart"))
                // 曾经有过、现在没有的动作。客户端按这个把能力标成不可用，不必逐轮试。
                .put("removed", removedActionsJson());
    }

    /** `removed` 段的构造：key 是动作名，value 是「哪个版本、为什么」。 */
    private static JSONObject removedActionsJson() throws Exception {
        JSONObject o = new JSONObject();
        for (java.util.Map.Entry<String, String> e : REMOVED_0_23.entrySet()) {
            o.put(e.getKey(), "0.23.0 起移除：" + e.getValue());
        }
        for (String name : REMOVED_0_17) {
            o.put(name, "0.17.0 起移除：QQ 内核查询接口不再向客户端暴露");
        }
        return o;
    }

    private JSONArray messageCreate(JSONObject p) throws Exception {
        String channelId = p.optString("channel_id", "");
        if (channelId.isEmpty()) throw new ApiError(1400, "missing channel_id");
        String content = p.optString("content", "");
        boolean group = !Codec.isPrivateChannel(channelId);
        long peer = Codec.channelPeer(channelId);
        if (peer == 0) throw new ApiError(1400, "invalid channel_id");
        MessageFreshness.Condition condition;
        try { condition = MessageFreshness.condition(p); }
        catch (IllegalArgumentException e) { throw new ApiError(1400, e.getMessage()); }
        sendCondition.set(condition);
        JSONArray result = new JSONArray();
        try {
            messageFreshness.check(condition); // after OutboundGuard's queue and minimum interval
            java.util.List<java.util.List<Elements.El>> batches = splitMessages(Elements.parse(content));
            // 顺媒体（语音/视频/群文件）与别的段落放同一条消息里时 QQ 渲染不出来，
            // 在这里兼容成几条发出去——见 Batching。
            java.util.ArrayList<java.util.List<Elements.El>> sends = new java.util.ArrayList<>();
            for (java.util.List<Elements.El> batch : batches)
                sends.addAll(com.satori.qq.satori.Batching.splitChunkMedia(batch));
            for (java.util.List<Elements.El> batch : sends) {
                messageFreshness.check(condition);
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
        } catch (MessageFreshness.Stale ignored) {
            L.d("message.create: skipped stale conditional send");
        } finally {
            sendCondition.remove();
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
            String text = snapshotContent(msg.optString("id", ""), rec);
            if (!text.isEmpty()) msg.put("content", text);
        }
        if (rec.msgTime > 0) msg.put("created_at", rec.msgTime * 1000L);
        return msg;
    }

    private synchronized JSONObject satoriMsgList(JSONObject p) throws Exception {
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
                    msg.put("content", Codec.fromCqText(row.text, assetBase()));
                if (msg.optString("content", "").isEmpty()) {
                    String t = snapshotContent(String.valueOf(row.msgId), store.getByMsgId(row.msgId));
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
        return pagedList(data, p);
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
        }, "pool-9-thread-1");
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

    /**
     * QQ's rank model is the built-in owner/admin/member trio, and that is all the kernel can
     * grant ({@code guild.member.role.set} only acts on `admin`). The richer per-member identity
     * data — level, titles, tags — is a separate concept and lives in `internal/member_identity`.
     */
    private JSONArray guildRoles(long groupId) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing guild_id");
        return new JSONArray()
                .put(new JSONObject().put("id", "owner").put("name", "owner"))
                .put(new JSONObject().put("id", "admin").put("name", "admin"))
                .put(new JSONObject().put("id", "member").put("name", "member"));
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

    private JSONObject satoriGetForward(JSONObject p) throws Exception {
        String id = p == null ? "" : p.optString("id", p.optString("message_id", ""));
        if (id != null && id.startsWith("native:")) return satoriGetNativeForward(id, p);
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

    /**
     * The kernel copy of a merge-forward: real per-node ids, images and timestamps, unlike the
     * resId path whose fake-node protocol drops NT media entirely. `getMultiMsg` only needs the
     * contact plus the parent msgId, so a caller that knows the channel can still reach it after
     * our own LRU dropped the parent (module restart, or just an old message).
     */
    private JSONObject satoriGetNativeForward(String id, JSONObject p) throws Exception {
        long kernelMsgId = parseLongQuiet(id.substring("native:".length()));
        if (kernelMsgId == 0) throw new ApiError(1400, "invalid native forward id");
        MsgStore.Rec parent = store.getByMsgId(kernelMsgId);
        int chatType = parent == null ? 0 : parent.chatType;
        String peer = parent == null ? null : parent.peerUid;
        if (parent != null && (peer == null || peer.isEmpty()) && parent.chatType == QQClient.CT_GROUP)
            peer = String.valueOf(parent.peerUin);
        if (peer == null || peer.isEmpty()) {
            String channelId = p == null ? "" : p.optString("channel_id", "");
            long peerUin = Codec.channelPeer(channelId);
            if (peerUin == 0)
                throw new ApiError(1404, "native forward is not cached; pass channel_id");
            boolean group = !Codec.isPrivateChannel(channelId);
            chatType = group ? QQClient.CT_GROUP : QQClient.CT_C2C;
            peer = group ? String.valueOf(peerUin) : uidFor(0, peerUin);
        }
        QQClient.MsgListResult result = qq.getMultiMsg(chatType, peer, kernelMsgId);
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

    /**
     * Satori {@code List}: `{data, next}`. A request that passes neither `next` nor `limit` keeps
     * the whole result set, which is what the earlier releases returned; a request that pages gets
     * an opaque offset token back. Callers that just want one page pass `limit`.
     */
    private JSONObject pagedList(JSONArray all, JSONObject p) throws Exception {
        return pagedList(all, p, PAGE_DEFAULT);
    }

    static JSONObject pagedList(JSONArray all, JSONObject p, int defaultLimit) throws Exception {
        JSONArray data = all == null ? new JSONArray() : all;
        String token = p == null ? "" : p.optString("next", "");
        int limit = p == null ? 0 : p.optInt("limit", 0);
        if (limit <= 0 && token.isEmpty()) return new JSONObject().put("data", data);
        if (limit <= 0) limit = defaultLimit;
        long offset = 0;
        if (!token.isEmpty()) {
            offset = parseLongQuiet(token);
            if (offset < 0) throw new ApiError(1400, "invalid next token: " + token);
        }
        JSONArray slice = new JSONArray();
        for (long i = offset; i < data.length() && slice.length() < limit; i++)
            slice.put(data.opt((int) i));
        JSONObject out = new JSONObject().put("data", slice);
        long nextOffset = offset + slice.length();
        if (nextOffset < data.length()) out.put("next", String.valueOf(nextOffset));
        return out;
    }

    /**
     * Milliseconds for a `channel.mute` request. The published spec sends `duration`; the
     * protocol table shipped by @satorijs/protocol sends `enable`. A bare `enable: true` means
     * "until turned off", which QQ's boolean kernel call expresses as its 30-day ceiling.
     */
    static long resolveChannelMuteMs(JSONObject p) {
        if (p == null) return DEFAULT_CHANNEL_MUTE_MS;
        if (p.has("duration")) return Math.max(0, p.optLong("duration", 0));
        if (p.has("enable")) return p.optBoolean("enable", true) ? DEFAULT_CHANNEL_MUTE_MS : 0;
        return DEFAULT_CHANNEL_MUTE_MS;
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
            // 只有真的没给才算 1400；给了但查不到（含非数字这种本模块不认的 id）是 404，
            // 否则客户端拿着一个可疑 id 会看到 "missing message_id" 这种误导性的说法。
            boolean missing = raw == null || raw.trim().isEmpty();
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
        int chatType = group ? QQClient.CT_GROUP : QQClient.CT_C2C;
        // Quotes emitted before the msgId fix carry a msgSeq. Resolve those inside this channel
        // rather than letting them fall through to an unrelated legacy store id. A real msgId is
        // 19 digits and never collides with a seq, and this runs only after resolve() missed.
        MsgStore.Rec bySeq = store.findByPeerSeq(chatType, peer, null, msgId);
        if (bySeq != null) return bySeq;
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
     * Merge-forward. Two deliverable paths, selected by {@code forward_mode}:
     * <ul>
     *   <li><b>native</b> — inner messages are sent to the bot's own self-chat (never the
     *       destination), then packed via kernel {@code multiForwardMsg}. Verified on QQNT
     *       9.3.55: the group sees only one card and it opens normally.</li>
     *   <li><b>fake</b> — nodes are uploaded via {@code SsoSendLongMsg} and delivered as a
     *       type-16/13/ark card. Nothing lands in any chat, but on QQNT 9.3.55 the card is a
     *       dead end: it renders and cannot be opened.</li>
     * </ul>
     * {@code auto} (default) tries native first and only falls back to fake when native
     * fails outright; {@code native}/{@code fake} pin one path for testing. Exactly one of groupId/userId is non-zero.
     */
    private JSONObject sendForward(long groupId, long userId, Object messages) throws Exception {
        if (groupId == 0 && userId == 0) throw new ApiError(1400, "missing group_id/user_id");
        List<LongMsg.Node> nodes = parseForwardNodes(messages, groupId != 0);
        if (nodes.isEmpty()) throw new ApiError(1400, "empty forward messages");
        String mode = cfg.forwardMode == null ? "auto" : cfg.forwardMode;
        if ("fake".equals(mode)) return sendForwardFake(groupId, userId, nodes);
        JSONObject nativeSent = sendForwardNative(groupId, userId, messages);
        if (nativeSent != null) return nativeSent;
        if ("native".equals(mode))
            throw new ApiError(1500, "native forward failed (self-chat scaffolding); see logs");
        return sendForwardFake(groupId, userId, nodes);
    }

    /**
     * Fake path: upload nodes to the long-msg store, then send a card referencing the resId.
     *
     * <p>Confirmed unusable on QQNT 9.3.55 (2026-09-02, group 280183116): the card renders in
     * the chat but tapping it fails to open — the viewer resolves content via kernel
     * {@code getMultiMsg(msgId)}, which only works for cards built by {@code multiForwardMsg}
     * from real local messages. Kept as an explicit {@code forward_mode=fake} opt-in for
     * re-testing on other clients; {@code auto} prefers the native path.</p>
     */
    private JSONObject sendForwardFake(long groupId, long userId, List<LongMsg.Node> nodes)
            throws Exception {
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
     *
     * <p>The inner scaffolding messages are sent to the bot's own self-chat, never to the
     * destination: multiForwardMsg only needs their msgIds, and this way the group never sees
     * them — not even as recallable flashes. They simply stay in the self-chat as private
     * by-products of building the card.</p>
     */
    private JSONObject sendForwardNative(long groupId, long userId, Object messages)
            throws Exception {
        int dstChatType = groupId != 0 ? QQClient.CT_GROUP : QQClient.CT_C2C;
        String dstPeer;
        if (groupId != 0) {
            dstPeer = String.valueOf(groupId);
        } else {
            dstPeer = store.uidOf(userId);
            if (dstPeer == null || dstPeer.isEmpty()) dstPeer = qq.resolveUid(userId);
            if (dstPeer != null && !dstPeer.isEmpty()) store.learnUid(userId, dstPeer);
            if (dstPeer == null || dstPeer.isEmpty()) return null;
        }
        String selfUid = qq.resolveUid(selfUin());
        if (selfUid == null || selfUid.isEmpty()) {
            L.e("native forward: cannot resolve self uid", null);
            return null;
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
            // Elements are built for the self-chat they land in (C2C), not the destination.
            QQClient.SendResult sr = sendMedia(QQClient.CT_C2C, selfUid, content, true);
            if (sr == null) continue;
            if (sr.code != 0 || sr.msgId == 0) {
                L.e("native forward inner send failed code=" + sr.code + " " + sr.msg, null);
                return null;
            }
            ids.add(sr.msgId);
            String name = d.optString("nickname", d.optString("name", ""));
            names.add(name);
            afterSend(sr, QQClient.CT_C2C, selfUin(), selfUid);
        }
        if (ids.isEmpty()) return null;
        // Anything the destination chat already had is older than this card.
        long baselineSeq = newestMsgSeq(dstChatType, dstPeer);
        QQClient.SendResult fw = qq.multiForward(QQClient.CT_C2C, selfUid, dstChatType, dstPeer,
                ids, names);
        if (fw.code != 0) {
            L.e("multiForwardMsg failed code=" + fw.code + " " + fw.msg, null);
            return null;
        }
        JSONObject found = null;
        for (int attempt = 0; attempt < 10 && found == null; attempt++) {
            try { Thread.sleep(attempt == 0 ? 800 : 500); } catch (InterruptedException ignore) {}
            found = findNativeForward(dstChatType, dstPeer, groupId, userId, ids, baselineSeq);
        }
        if (found != null) return found;
        L.e("multiForward card not in history yet inners=" + ids.size(), null);
        return new JSONObject().put("native_forward", true).put("message_id", 0);
    }

    /**
     * Newest merge-forward in the destination chat. Preferred match is a record that parsed
     * into a {@code forward} segment; when the kernel stored the card without one, the newest
     * own message past {@code baselineSeq} is taken instead — the card has to be registered
     * either way, because without a stored message id it cannot be recalled.
     */
    private JSONObject findNativeForward(int chatType, String peer, long groupId, long userId,
                                         java.util.ArrayList<Long> innerIds, long baselineSeq)
            throws Exception {
        // Merge-forward cards are authored by self and may only surface through the DB/AIO
        // caches, not the plain getMsgsIncludeSelf view. Reuse getHistory (the same query
        // message.list relies on) so the freshly sent card is found reliably.
        QQClient.MsgListResult hist = qq.getHistory(chatType, peer, 0, 20, true);
        if (hist.records == null) return null;
        // Prefer a record whose elements are a merge-forward (type 16) or struct long msg (13);
        // fall back to the newest qualifying record so a card we can't structurally detect is
        // still registered and therefore recallable.
        Object bestRec = null; long bestId = 0; long bestSeq = 0; JSONObject bestData = null;
        for (int i = hist.records.size() - 1; i >= 0; i--) {
            Object rec = hist.records.get(i);
            long msgId = recordId(rec);
            if (msgId == 0 || innerIds.contains(msgId)) continue;
            long seq = recordSeq(rec);
            if (baselineSeq > 0 && seq <= baselineSeq) continue;
            if (isForwardRecord(rec)) {
                JSONObject data = extractForwardData(rec); // best-effort, may be null
                return registerForwardCard(rec, msgId, seq, chatType, groupId, userId, peer, data);
            }
            if (bestRec == null || seq > bestSeq) {
                bestRec = rec; bestId = msgId; bestSeq = seq;
            }
        }
        if (bestRec != null)
            return registerForwardCard(bestRec, bestId, bestSeq, chatType, groupId, userId, peer, null);
        return null;
    }

    /** msgId/msgSeq of a history record, or 0 when the kernel record cannot be unwrapped. */
    private long recordSeq(Object rec) {
        Object inner = Convert.unwrapRecord(rec);
        if (inner == null) return 0;
        try { return qq.ref.getLong(inner, "msgSeq"); } catch (Throwable ignore) { return 0; }
    }

    private long recordId(Object rec) {
        Object inner = Convert.unwrapRecord(rec);
        if (inner == null) return 0;
        try { return qq.ref.getLong(inner, "msgId"); } catch (Throwable ignore) { return 0; }
    }

    /** True when a history record's elements are a merge-forward (16) or struct long msg (13). */
    private boolean isForwardRecord(Object rec) {
        Object inner = Convert.unwrapRecord(rec);
        if (inner == null) return false;
        try {
            Object els = qq.ref.get(inner, "elements");
            if (!(els instanceof java.util.List)) return false;
            for (Object e : (java.util.List<?>) els) {
                if (e == null) continue;
                int t = qq.ref.asInt(qq.ref.get(e, "elementType"));
                if (t == 16 || t == 13) return true;
            }
        } catch (Throwable ignore) {}
        return false;
    }

    /** Best-effort forward-segment data (for res_id); null when the record can't be parsed. */
    private JSONObject extractForwardData(Object rec) {
        try {
            JSONObject ev = conv.recordToEvent(rec, 0);
            if (ev == null) return null;
            JSONArray msg = ev.optJSONArray("message");
            if (msg == null) return null;
            for (int j = 0; j < msg.length(); j++) {
                JSONObject s = msg.optJSONObject(j);
                if (s != null && "forward".equals(s.optString("type")))
                    return s.optJSONObject("data");
            }
        } catch (Throwable ignore) {}
        return null;
    }

    /** Newest msgSeq in a chat; the baseline for spotting messages that arrive later. */
    private long newestMsgSeq(int chatType, String peer) {
        // Include self: the merge-forward card is self-authored and must count toward baseline.
        // getHistory mirrors message.list so it sees the card even when plain getMsgs would not.
        QQClient.MsgListResult hist = qq.getHistory(chatType, peer, 0, 5, true);
        if (hist.records == null) return 0;
        long max = 0;
        for (Object rec : hist.records) {
            long seq = recordSeq(rec);
            if (seq > max) max = seq;
        }
        return max;
    }

    /**
     * Store the card so it resolves like any other message. The returned id is the QQ msgId,
     * which is what {@code message.delete} and {@code message.get} accept.
     */
    private JSONObject registerForwardCard(Object rec, long msgId, long seq, int chatType,
                                           long groupId, long userId, String peer,
                                           JSONObject data) throws Exception {
        MsgStore.Rec stored = new MsgStore.Rec();
        stored.chatType = chatType;
        stored.peerUin = groupId != 0 ? groupId : userId;
        stored.peerUid = groupId != 0 ? "" : peer;
        stored.msgId = msgId;
        stored.msgSeq = seq;
        stored.senderUin = selfUin();
        stored.msgRecord = rec;
        int obId = store.put(stored);
        JSONObject out = new JSONObject()
                .put("message_id", obId)
                .put("qq_msg_id", msgId)
                .put("native_forward", true);
        if (data != null) {
            String resId = data.optString("id", "");
            if (!resId.isEmpty()) out.put("res_id", resId).put("forward_id", resId);
            if (data.has("filename")) out.put("filename", data.optString("filename"));
            if (data.has("element_type")) out.put("element_type", data.optInt("element_type"));
        }
        if (msgId != 0) qq.prefetchForward(chatType, peer, msgId);
        return out;
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

    private JSONObject sendGroup(long groupId, Object message) throws Exception {
        return sendGroup(groupId, message, "");
    }

    private JSONObject sendGroup(long groupId, Object message, String content) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        if (looksLikeForward(message)) return sendForward(groupId, 0, message);
        QQClient.SendResult r = sendMedia(QQClient.CT_GROUP, String.valueOf(groupId), message);
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
        QQClient.SendResult r = sendMedia(QQClient.CT_C2C, uid, message);
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
        // Conversion/downloads and retry backoff can take time after leaving the queue.
        messageFreshness.check(sendCondition.get());
        return qq.sendMsg(chatType, peer, els, this::rememberOutboundMsgId);
    }

    /**
     * Send segments, retrying when QQ's kernel fails to upload their media.
     *
     * <p>Image/voice/video/file payloads are transferred to QQ's rich-media servers from inside
     * {@code sendMsg}, before the message itself is dispatched. That transfer is the first thing to
     * break when the screen has been locked long enough for the radio to be parked — the callback
     * comes back {@code code=-1 "rich media transfer failed"} while plain text sent a second later
     * still rides the already-established MSF socket. Nothing reached the peer in that case, so
     * re-entering {@code sendMsg} cannot duplicate a delivered message.
     *
     * <p>Elements are rebuilt from the original segments on every attempt: the kernel writes upload
     * state (file ids, paths, sizes) into the MsgElement it was handed, and a half-filled element
     * from a failed attempt is not safe to hand back.
     */
    private QQClient.SendResult sendMedia(int chatType, String peer, Object message) throws Exception {
        return sendMedia(chatType, peer, message, false);
    }

    /** {@code skipEmpty} returns null instead of entering the kernel when nothing converted. */
    private QQClient.SendResult sendMedia(int chatType, String peer, Object message, boolean skipEmpty)
            throws Exception {
        int attempts = 1 + (hasMediaSegment(message) ? Math.max(0, cfg.mediaRetryAttempts) : 0);
        // Clients wait on one HTTP call, so the retries have to fit inside their timeout budget.
        long deadline = System.currentTimeMillis() + cfg.mediaRetryBudgetMs;
        QQClient.SendResult r = null;
        for (int i = 0; i < attempts; i++) {
            if (i > 0) {
                long backoff = (long) cfg.mediaRetryBackoffMs * i;
                if (System.currentTimeMillis() + backoff >= deadline) {
                    L.e("media transfer failed (" + r.msg + "); retry budget spent", null);
                    return r;
                }
                L.e("media transfer failed (" + r.msg + "); retry " + i + "/" + (attempts - 1), null);
                Thread.sleep(backoff);
            }
            java.util.ArrayList<Object> els = conv.toElements(message, chatType);
            if (skipEmpty && (els == null || els.isEmpty())) return null;
            r = sendTracked(chatType, peer, els);
            if (r.code == 0 || !isMediaTransferFailure(r)) return r;
        }
        return r;
    }

    /** QQ's wording for an upload that never left the device; the send failed before dispatch. */
    private static boolean isMediaTransferFailure(QQClient.SendResult r) {
        if (r == null || r.code == 0 || r.msg == null) return false;
        String m = r.msg.toLowerCase(java.util.Locale.ROOT);
        return m.contains("rich media") || m.contains("media transfer") || m.contains("upload")
                || m.contains("富媒体");
    }

    /** True when the outgoing segments carry something QQ has to upload before it can send. */
    private static boolean hasMediaSegment(Object message) {
        if (!(message instanceof JSONArray)) return false;
        JSONArray arr = (JSONArray) message;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject seg = arr.optJSONObject(i);
            if (seg == null) continue;
            switch (seg.optString("type", "")) {
                case "image": case "record": case "video": case "file": return true;
                default: break;
            }
        }
        return false;
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
        rec.contentIsElements = !rec.content.isEmpty();
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
                try { d.put("message", Codec.cqToSegments(text)); } catch (Exception ignore) {}
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

    private JSONObject synthesizeInboundFromRecord(Object rec, QQClient.MsgListResult hist)
            throws Exception {
        Object inner = Convert.unwrapRecord(rec);
        if (inner == null) return null;
        long msgId = qq.ref.getLong(inner, "msgId");
        long msgSeq = qq.ref.getLong(inner, "msgSeq");
        long msgTime = qq.ref.getLong(inner, "msgTime");
        if (msgTime > 10_000_000_000L) msgTime /= 1000L;
        long senderUin = qq.ref.getLong(inner, "senderUin");
        long peerUin = qq.ref.getLong(inner, "peerUin");
        int chatType = Ref.asInt(qq.ref.get(inner, "chatType"));
        if (senderUin == 0) return null;
        if (msgId != 0) {
            MsgStore.Rec stored = store.getByMsgId(msgId);
            if (stored != null) {
                JSONObject ev = synthesizeFromRec(stored);
                if (ev != null && !ev.optString("raw_message", "").isEmpty()) return ev;
            }
        }
        String text = "";
        if (msgId != 0 && hist != null && hist.texts != null) {
            String t = hist.texts.get(String.valueOf(msgId));
            if (t != null) text = t;
        }
        if (text.isEmpty()) text = qq.peekRecordText(rec);
        if (text.isEmpty()) return null;
        boolean group = chatType == QQClient.CT_GROUP;
        String nick = Ref.asStr(qq.ref.get(inner, "sendNickName"));
        String card = Ref.asStr(qq.ref.get(inner, "sendMemberName"));
        JSONObject sender = new JSONObject()
                .put("user_id", senderUin)
                .put("nickname", nick == null ? "" : nick);
        if (group) sender.put("card", card == null ? "" : card);
        JSONArray segs = Codec.toSegments(text);
        JSONObject ev = new JSONObject()
                .put("post_type", "message")
                .put("message_type", group ? "group" : "private")
                .put("sub_type", group ? "normal" : "friend")
                .put("message_id", msgId != 0 ? String.valueOf(msgId) : "0")
                .put("qq_msg_id", msgId)
                .put("user_id", senderUin)
                .put("sender", sender)
                .put("message", segs)
                .put("raw_message", text)
                .put("message_seq", msgSeq);
        if (msgTime > 0) {
            ev.put("time", msgTime);
            ev.put("msg_time", String.valueOf(msgTime));
        }
        if (group) ev.put("group_id", peerUin);
        else ev.put("peer_id", peerUin);
        return ev;
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
        else ev.put("peer_id", r.peerUin);
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
        if (chatType == QQClient.CT_GROUP) qq.ensureGroupMembers(peerUin);
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

    /**
     * 快照有两种来源：本端 API 发出的消息存的是 Satori 元素串，内核历史存的是 CQ 文本。
     * 前者原样返回，后者按协议转成元素串。
     */
    private String snapshotContent(String msgId, MsgStore.Rec rec) {
        String text = snapshotText(msgId, rec);
        if (text.isEmpty()) return "";
        MsgStore.Rec r = rec != null ? rec : store.getByMsgId(parseLongQuiet(msgId));
        if (r != null && r.contentIsElements && text.equals(r.content)) return text;
        return Codec.fromCqText(text, assetBase());
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
                String name = qq.groupName(gid);
                qq.rememberGroupName(gid, name);
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
        String role = roleStr(qq.ref.get(mi, "role"));
        o.put("role", role);
        o.put("unfriendly", false);
        o.put("title", Ref.asStr(qq.ref.get(mi, "memberSpecialTitle")));
        o.put("title_expire_time", Ref.asLong(qq.ref.get(mi, "specialTitleExpireTime")));
        o.put("card_changeable", true);
        store.learnUid(uin, Ref.asStr(qq.ref.get(mi, "uid")));
        store.learnRole(groupId, uin, role);
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
        String groupName = qq.groupName(groupId);
        qq.rememberGroupName(groupId, groupName);
        o.put("group_name", groupName);
        o.put("member_count", Ref.asInt(qq.ref.get(gi, "memberCount")));
        o.put("max_member_count", Ref.asInt(qq.ref.get(gi, "maxMember")));
        int flag3 = Ref.asInt(qq.ref.get(gi, "groupFlagExt3"));
        o.put("group_flag_ext3", flag3);
        o.put("honor_open", (flag3 & QQClient.HONOR_AIO_FLAG) == 0);
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
        rememberMyReaction(messageId, emojiKey, set);
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

    /**
     * 本登录号在这条消息上加过的表情键（store 消息号 → emojiKey 集合）。
     *
     * <p>QQ 内核的「谁点了赞」列表不含自己，`reaction.list` 只能退化成读本地记录；而那条记录
     * 是内核推来的，滞后一拍（2026-09-19 实测：加完立刻回读是空、清完立刻回读还是 1）。
     * 自己做的动作自己记一份，回读才跟得上手。
     */
    private final ConcurrentHashMap<Integer, Set<String>> myReactions = new ConcurrentHashMap<>();

    private void rememberMyReaction(int messageId, String emojiKey, boolean set) {
        if (messageId == 0 || emojiKey == null || emojiKey.isEmpty()) return;
        Set<String> mine = myReactions.computeIfAbsent(messageId,
                k -> ConcurrentHashMap.newKeySet());
        if (set) mine.add(emojiKey); else mine.remove(emojiKey);
        if (myReactions.size() > 4000) myReactions.clear();
    }

    /** 不带 emoji_id 的 reaction.list：把本登录号在这条消息上加过的表态按账号去重后返回。 */
    private JSONObject localReactionUsers(int messageId) throws Exception {
        MsgStore.Rec r = store.get(messageId);
        if (r == null) throw new ApiError(1404, "message not found: " + messageId);
        JSONArray data = new JSONArray();
        for (String key : reactionEmojiKeys(messageId)) {
            JSONArray one = reactionListFromRecord(r, key);
            for (int i = 0; i < one.length(); i++) {
                JSONObject u = one.optJSONObject(i);
                if (u == null) continue;
                boolean dup = false;
                for (int j = 0; j < data.length(); j++) {
                    JSONObject seen = data.optJSONObject(j);
                    if (seen != null && seen.optString("id").equals(u.optString("id"))) { dup = true; break; }
                }
                if (!dup) data.put(u);
            }
        }
        return new JSONObject().put("data", data);
    }

    /**
     * 本地兜底：内核那条「谁点了赞」的记录里，本登录号自己那一条。
     *
     * <p>两个来源的优先级是**本地记账优先**：`myReactions` 记的是本进程亲眼看到的加/取消动作，
     * 而内核记录滞后一拍。反过来的写法会把「刚清掉的表情」重新读出来——2026-09-19 实测：
     * `reaction.clear` 之后立刻 `reaction.list` 会回 1 个人（就是我们自己），等到内核推来新
     * 记录才消失，于是巡检与客户端都会看到「清不掉」的假象。没有本地记账时（模块重启过）才退到
     * 内核记录的 `isClicked`。
     */
    private JSONArray reactionListFromRecord(MsgStore.Rec r, String emojiKey) throws Exception {
        JSONArray data = new JSONArray();
        Set<String> tracked = myReactions.get(r.id);
        boolean mine;
        if (tracked != null) {
            mine = tracked.contains(emojiKey);
        } else {
            Object rec = r.msgRecord;
            if (rec == null && r.msgId != 0) {
                String peer = r.peerUid == null || r.peerUid.isEmpty()
                        ? String.valueOf(r.peerUin) : r.peerUid;
                rec = qq.fetchRecord(r.chatType, peer, r.msgId);
                if (rec != null) r.msgRecord = rec;
            }
            rec = rec == null ? null : Convert.unwrapRecord(rec);
            Object likes = rec == null ? null : qq.ref.get(rec, "emojiLikesList");
            mine = false;
            if (likes instanceof java.util.List) {
                for (Object like : (java.util.List<?>) likes) {
                    if (!emojiKey.equals(Ref.asStr(qq.ref.get(like, "emojiId")))) continue;
                    if (qq.ref.getLong(like, "likesCnt") <= 0) continue;
                    Object clicked = qq.ref.get(like, "isClicked");
                    mine = clicked instanceof Boolean ? (Boolean) clicked : Ref.asInt(clicked) != 0;
                    break;
                }
            }
        }
        if (!mine) return data;
        JSONObject user = Codec.user(selfUin(), qq.selfNick(), "");
        JSONObject login = loginSlim().optJSONObject("user");
        if (login != null) {
            String avatar = login.optString("avatar", "");
            if (!avatar.isEmpty()) user.put("avatar", avatar);
        }
        data.put(user);
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

    /** 群聊资料中的「群荣誉/群标识」开关; distinct from member title display. */
    private JSONObject setGroupHonorDisplay(long groupId, boolean show) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        int before = qq.groupFlagExt3(groupId);
        requireOp(qq.setHonorAioSwitch(groupId, show));
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

    private JSONObject groupSign(long groupId) throws Exception {
        if (groupId == 0) throw new ApiError(1400, "missing group_id");
        String ver = qq.qqVersion();
        if (ver == null || ver.isEmpty()) ver = "9.3.60";
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
    /** message.create 回声的 msgId；短期保留，覆盖内核的 add/update/recv 多路回调。 */
    private final java.util.Set<Long> outboundMsgIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 已成功投递的 msgId，防内核重复推送。 */
    private final java.util.Set<Long> emittedMsgIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Map<String, FriendReq> pendingFriends = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, GroupReq> pendingGroups = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> seenRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<Long> seenRecalls = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> seenMemberChanges = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * Only empty shell records need polling; complete UI messages take the synchronous fast path.
     *
     * <p>线程名是外部可见的：`/proc/<pid>/task/comm` 里能读到。带上模块名字的线程等于把
     * 模块写在自己的进程里（Duck Detector 一类检测器就在扫线程名），所以这里按 JVM 线程池
     * 的默认风格命名，QQ 自己也有若干 `pool-N-thread-M`。
     */
    private final java.util.concurrent.ScheduledExecutorService selfSendScheduler =
            java.util.concurrent.Executors.newScheduledThreadPool(4, new java.util.concurrent.ThreadFactory() {
                private final java.util.concurrent.atomic.AtomicInteger seq =
                        new java.util.concurrent.atomic.AtomicInteger();
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "pool-8-thread-" + (seq.incrementAndGet() % 100));
                    t.setDaemon(true);
                    return t;
                }
            });
    private final java.util.Set<Long> selfSendPollScheduled = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // 入站侧的计数。事件收不到时先看这几个：能区分「记录压根没进来」（回调计数不动）、
    // 「进来了但没转成事件」（有计数没有 emit）与「转了但客户端没消费」（有 emit）。
    private final java.util.concurrent.atomic.AtomicLong inRecv = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong inAdd = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong inUpdate = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong inGrayTip = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong inEmit = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong inNotice = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong inManualSelf = new java.util.concurrent.atomic.AtomicLong();

    /** 记录里带不带灰条元素（elementType 8）——戳一戳、禁言、成员变动都从这儿来。 */
    private boolean hasGrayTip(Object rec) {
        try {
            Object elements = qq.ref.get(Convert.unwrapRecord(rec), "elements");
            if (!(elements instanceof List)) return false;
            for (Object e : (List<?>) elements) {
                if (e != null && Ref.asInt(qq.ref.get(e, "elementType")) == 8) return true;
            }
        } catch (Throwable ignore) { }
        return false;
    }

    private static final long OUTBOUND_MSG_TTL_MS = 120_000;

    private void rememberOutboundMsgId(long msgId) {
        if (msgId == 0) return;
        if (!outboundMsgIds.add(msgId)) return;
        selfSendScheduler.schedule(() -> outboundMsgIds.remove(msgId), OUTBOUND_MSG_TTL_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private String recordPeerUid(Object rec) throws Exception {
        Object inner = Convert.unwrapRecord(rec);
        String peerUid = Ref.asStr(qq.ref.get(inner, "peerUid"));
        if (peerUid != null && !peerUid.isEmpty()) return peerUid;
        return String.valueOf(Ref.asLong(qq.ref.get(inner, "peerUin")));
    }

    private Object recordField(Object rec, String field) throws Exception {
        return qq.ref.get(Convert.unwrapRecord(rec), field);
    }

    private boolean isSelfRecord(Object rec) throws Exception {
        long self = selfUin();
        return self != 0 && Ref.asLong(recordField(rec, "senderUin")) == self;
    }

    private void scheduleSelfSendEmit(Object rec) {
        try {
            if (!isSelfRecord(rec)) return;
            long msgId = Ref.asLong(recordField(rec, "msgId"));
            if (msgId == 0 || !selfSendPollScheduled.add(msgId)) return;
            final int chatType = Ref.asInt(recordField(rec, "chatType"));
            final String peer = recordPeerUid(rec);
            scheduleSelfSendAttempt(msgId, chatType, peer, 0);
        } catch (Throwable t) {
            L.e("scheduleSelfSendEmit", t);
        }
    }

    private static final long[] SELF_SEND_RETRY_MS = {100, 250, 500, 1000, 2000, 4000};

    /** One outstanding retry per message; successful messages stop immediately. */
    private void scheduleSelfSendAttempt(long msgId, int chatType, String peer, int attempt) {
        if (attempt >= SELF_SEND_RETRY_MS.length) {
            selfSendPollScheduled.remove(msgId);
            return;
        }
        selfSendScheduler.schedule(() -> {
            boolean retry = false;
            try {
                if (emittedMsgIds.contains(msgId) || outboundMsgIds.contains(msgId)) return;
                Object fetched = qq.fetchRecord(chatType, peer, msgId);
                QQClient.MsgListResult hist = null;
                if (fetched == null && chatType == QQClient.CT_GROUP) {
                    hist = qq.getHistory(chatType, peer, 0, 20, true);
                    if (hist.records != null) {
                        for (Object cand : hist.records) {
                            if (Ref.asLong(qq.ref.get(cand, "msgId")) == msgId) {
                                fetched = cand;
                                break;
                            }
                        }
                    }
                }
                int result = fetched == null ? EMIT_SKIP_NULL : tryEmitInboundMsgInternal(fetched, hist);
                retry = result == EMIT_SKIP_EMPTY || result == EMIT_SKIP_NULL;
            } catch (Throwable t) {
                retry = true;
                L.e("selfSendPoll", t);
            } finally {
                if (retry && attempt + 1 < SELF_SEND_RETRY_MS.length) {
                    scheduleSelfSendAttempt(msgId, chatType, peer, attempt + 1);
                } else {
                    selfSendPollScheduled.remove(msgId);
                }
            }
        }, SELF_SEND_RETRY_MS[attempt], java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static final int EMIT_OK = 0;
    private static final int EMIT_SKIP_OUTBOUND = 1;
    private static final int EMIT_SKIP_DEDUPE = 2;
    private static final int EMIT_SKIP_EMPTY = 3;
    private static final int EMIT_SKIP_NULL = 4;

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

    /** 内核有时会先推空壳再推正文；空壳不占位，避免正文被 dedupe 掉。 */
    private static boolean isDeliverableMessage(JSONObject ev) {
        if (ev == null || !"message".equals(ev.optString("post_type"))) return true;
        String raw = ev.optString("raw_message", "");
        if (raw != null && !raw.trim().isEmpty()) return true;
        JSONArray segs = ev.optJSONArray("message");
        if (segs == null) return false;
        for (int i = 0; i < segs.length(); i++) {
            JSONObject seg = segs.optJSONObject(i);
            if (seg == null) continue;
            if ("text".equals(seg.optString("type"))) {
                JSONObject data = seg.optJSONObject("data");
                if (data != null && !data.optString("text", "").trim().isEmpty()) return true;
            } else if (!seg.optString("type", "").isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void tryEmitInboundMsg(Object rec) {
        tryEmitInboundMsg(rec, null);
    }

    private void tryEmitInboundMsg(Object rec, QQClient.MsgListResult hist) {
        tryEmitInboundMsgInternal(rec, hist);
    }

    private String manualSelfUserId(long self) {
        return cfg.manualSelfUserId.isEmpty() ? "qq-client:" + self : cfg.manualSelfUserId;
    }

    private void markManualSelfMessage(JSONObject ev, long self) throws Exception {
        if (ev == null || !cfg.manualSelfMessages) return;
        String virtualId = manualSelfUserId(self);
        ev.put("satori_user_id", virtualId);
        ev.put("manual_self", true);
        ev.put("actual_user_id", String.valueOf(self));
    }

    private int tryEmitInboundMsgInternal(Object rec, QQClient.MsgListResult hist) {
        long self = selfUin();
        try {
            Object inner = Convert.unwrapRecord(rec);
            rememberReactionState(inner);
            long msgId = Ref.asLong(qq.ref.get(inner, "msgId"));
            long senderUin = Ref.asLong(qq.ref.get(inner, "senderUin"));

            // 机器人 API 发出的回声：sendTracked 已登记 msgId。
            if (msgId != 0 && outboundMsgIds.contains(msgId)) return EMIT_SKIP_OUTBOUND;

            // 已完整投递过（内核重复推送同一条）。
            if (msgId != 0 && emittedMsgIds.contains(msgId)) return EMIT_SKIP_DEDUPE;

            JSONObject ev = conv.recordToEvent(rec, self);
            // Preserve notices (recall, poke, member changes) returned by Convert.
            // Synthesis is only a fallback for an otherwise unparseable message record.
            if (ev == null) ev = synthesizeInboundFromRecord(rec, hist);
            if (ev != null && self != 0) ev.put("self_id", self);
            fillEmptyText(ev, rec);
            if (hist != null) fillEmptyTextFromMap(ev, hist);
            if (ev == null) return EMIT_SKIP_NULL;
            if (!isDeliverableMessage(ev)) return EMIT_SKIP_EMPTY;
            if (self != 0 && senderUin == self) markManualSelfMessage(ev, self);

            String nt = ev.optString("notice_type", "");
            if ("group_recall".equals(nt) || "friend_recall".equals(nt)) {
                long mid = ev.optLong("qq_msg_id", 0);
                if (mid == 0) mid = ev.optLong("message_id", 0);
                if (mid != 0 && !seenRecalls.add(mid)) return EMIT_SKIP_DEDUPE;
            }

            if (msgId != 0) {
                emittedMsgIds.add(msgId);
                if (emittedMsgIds.size() > 8000) emittedMsgIds.clear();
            }
            inEmit.incrementAndGet();
            if (!ev.optString("notice_type", "").isEmpty()) inNotice.incrementAndGet();
            if (ev.optBoolean("manual_self", false)) inManualSelf.incrementAndGet();
            emitObEvent(ev);
            L.d("event -> " + ev.optString("post_type") + "/"
                    + ev.optString("message_type", ev.optString("notice_type"))
                    + " from " + ev.optLong("user_id"));
            return EMIT_OK;
        } catch (Throwable t) {
            L.e("tryEmitInboundMsg", t);
            return EMIT_SKIP_NULL;
        }
    }

    @Override public void onRecvMsgs(List<?> records) {
        if (records != null) inRecv.addAndGet(records.size());
        for (Object rec : records) {
            try {
                int result = tryEmitInboundMsgInternal(rec, null);
                if (isSelfRecord(rec)
                        && (result == EMIT_SKIP_EMPTY || result == EMIT_SKIP_NULL)) {
                    scheduleSelfSendEmit(rec);
                }
            } catch (Throwable t) {
                L.e("onRecvMsgs", t);
            }
        }
    }

    @Override public void onAddSendMsg(Object rec) {
        if (rec == null) return;
        inAdd.incrementAndGet();
        try {
            int result = tryEmitInboundMsgInternal(rec, null);
            if (result == EMIT_SKIP_EMPTY || result == EMIT_SKIP_NULL) scheduleSelfSendEmit(rec);
        } catch (Throwable t) {
            L.e("onAddSendMsg", t);
        }
    }

    @Override public void onMsgUpdates(List<?> records) {
        if (records == null) return;
        inUpdate.addAndGet(records.size());
        for (Object record : records) {
            try {
                if (hasGrayTip(record)) inGrayTip.incrementAndGet();
                emitReactionChanges(record);
                // Updates also carry reactions and edits to old records. Only a self-authored
                // candidate seen through add/recv can be a newly typed message. This prevents
                // an update to an old self-authored message from being replayed as new input.
                if (isSelfRecord(record)) {
                    long msgId = Ref.asLong(recordField(record, "msgId"));
                    if (msgId != 0 && selfSendPollScheduled.contains(msgId)) {
                        int result = tryEmitInboundMsgInternal(record, null);
                        if (result != EMIT_SKIP_EMPTY && result != EMIT_SKIP_NULL) {
                            selfSendPollScheduled.remove(msgId);
                        }
                    }
                }
            } catch (Throwable t) {
                L.e("onMsgUpdates", t);
            }
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

    /**
     * Emoji ids this login itself clicked on a message. {@code MsgEmojiLikes.isClicked} is the
     * kernel's own flag for that, so nothing has to be guessed from the elsewhere-unkeyed counts.
     */
    private java.util.List<String> reactionEmojiKeys(int messageId) throws Exception {
        java.util.List<String> out = new java.util.ArrayList<>();
        MsgStore.Rec r = store.get(messageId);
        if (r == null) return out;
        Object rec = r.msgRecord;
        if (rec == null && r.msgId != 0) {
            String peer = r.peerUid == null || r.peerUid.isEmpty()
                    ? String.valueOf(r.peerUin) : r.peerUid;
            rec = qq.fetchRecord(r.chatType, peer, r.msgId);
        }
        if (rec == null) return out;
        Object likes = qq.ref.get(rec, "emojiLikesList");
        if (!(likes instanceof java.util.List)) return out;
        for (Object like : (java.util.List<?>) likes) {
            String id = Ref.asStr(qq.ref.get(like, "emojiId"));
            if (id.isEmpty()) continue;
            if (Ref.asBool(qq.ref.get(like, "isClicked"))) out.add(id);
        }
        Set<String> tracked = myReactions.get(messageId);
        if (tracked != null) {
            for (String key : tracked) if (!out.contains(key)) out.add(key);
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
        synchronized (eventEmitLock) {
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
    }

    private void emitSatoriEvent(JSONObject body) throws Exception {
        synchronized (eventEmitLock) {
            messageFreshness.observe(body);
            rememberEvent(body);
            String payload = opJson(OP_EVENT, body).toString();
            for (WsConn c : identified) c.send(payload);
        }
    }

    private void rememberEvent(JSONObject body) {
        synchronized (recentEventsLock) {
            recentEvents.addLast(body);
            while (recentEvents.size() > EVENT_BUFFER) recentEvents.removeFirst();
        }
    }

    private void replayEvents(WsConn conn, long afterSn) {
        java.util.ArrayList<JSONObject> snapshot;
        synchronized (recentEventsLock) {
            snapshot = new java.util.ArrayList<>(recentEvents);
        }
        for (JSONObject body : snapshot) {
            if (body.optLong("sn") <= afterSn) continue;
            try { conn.send(opJson(OP_EVENT, body).toString()); } catch (Throwable ignore) {}
        }
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
    /** READY 里报出去的账号。换了号就要通知并回收客户端——旧 id 在新账号下会被判成别的登录。 */
    private volatile long advertisedUin = 0;
    private volatile long pendingUin = 0;
    private volatile int pendingUinTicks = 0;

    private volatile long lastNameGuardMs;
    private long nameRestores;
    private long nameRestoreWindowStart;
    private volatile String nameGuardDiag = "idle";

    /** 名字守卫的最近一次结论，healthz / internal/status 里看得到。 */
    private String nameGuardDiag() { return nameGuardDiag; }

    /**
     * 群名守卫。
     *
     * <p>2026-09-19 起，测试群的名字反复变空，而 `satori-writes.log` 里**没有任何写入记录**：
     * 不是我们的改名路径干的，也一直没抓到是谁。这里做两件确定有用的事——
     * 先用**全量刷新**把「本地缓存没名字」与「真的没名字」分开（前者刷新就回来了，一个字也不写），
     * 刷新后还是空，就用我们见过的那个名字写回去，并记进审计。每小时最多恢复 2 次：
     * 万一有个未知的清除者在打拉锯，日志里看得见，也不至于变成无限改名。
     */
    private void nameGuardTick(long now) {
        if (now - lastNameGuardMs < 120_000) return;
        lastNameGuardMs = now;
        java.util.Set<Long> known = qq.knownGroupCodes();
        if (known.isEmpty()) return;
        java.util.List<Long> empty = new java.util.ArrayList<>();
        for (Long gid : known) {
            if (gid == null || gid == 0) continue;
            // 已经不在群列表里的（退群、被移出、群被解散）读出来也是空，但那不是「名字变空」，
            // 别一直挂在 name_guard 里——2026-09-19 测试群解散后就留下过一条这样的噪音。
            if (qq.groupInfo(gid) == null) continue;
            if (!qq.groupName(gid).isEmpty()) continue;
            if (qq.knownGroupName(gid) == null) continue;
            empty.add(gid);
        }
        if (empty.isEmpty()) return;
        qq.refreshGroupList();
        if (nameRestoreWindowStart == 0 || now - nameRestoreWindowStart > 3_600_000L) {
            nameRestoreWindowStart = now;
            nameRestores = 0;
        }
        StringBuilder d = new StringBuilder();
        for (Long gid : empty) {
            if (!qq.groupName(gid).isEmpty()) {
                d.append("refresh-back:").append(gid).append(' ');
                continue;   // 只是本地缓存空了，刷新就回来了，不写
            }
            String want = qq.knownGroupName(gid);
            if (!cfg.restoreEmptyGroupName) {
                // 默认只观察。名字变空更可能是平台侧处置（见 Cfg.restoreEmptyGroupName），
                // 跟平台抢着改回去不是实现端该做的事。
                d.append("empty:").append(gid).append("(见过=").append(want)
                        .append("，restore_empty_group_name=false 不写)").append(' ');
                continue;
            }
            if (nameRestores >= 2) {
                d.append("budget-used:").append(gid).append(' ');
                continue;
            }
            QQClient.OpResult r = qq.setGroupName(gid, want);
            nameRestores++;
            d.append("restored:").append(gid).append('=').append(want).append('/').append(r.describe()).append(' ');
        }
        nameGuardDiag = d.length() == 0 ? "idle" : d.toString().trim();
    }

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
                        // 踢线台账里的 up=<秒> 就是从这里来的：一条踢线是「登录后多久」被推下来的，
                        // 是判周期还是事件驱动的唯一线索。
                                    L.i("QQ kernel state -> " + (online ? "online" : "offline"));
                        emitLoginUpdated();
                        previous = online;
                    }
                    trackLoginChange();
                    try { nameGuardTick(now); } catch (Throwable e) { L.e("nameGuardTick", e); }
                    if (cfg.heartbeat && now >= nextHeartbeat) {
                        nextHeartbeat = now + interval;
                    }
                    flushAwaitingReady();
                    // 被踢之后把落盘的登录态修回来（账号标记与自动登录开关）。只在本进程
                    // 报离线时动手：在线时改这两个没有意义，也省得跟 QQ 自己的写入打架。
                    // 内部还有善后期判定与 30 秒节流。
                    // Hold the wake lock from startup even when the status notification is off.
                    if (cfg.wakeLockControl || cfg.wifiSustain) ensureWakeLockController();
                    refreshNotice();
                } catch (InterruptedException ie) { return; }
                catch (Throwable ignore) {}
            }
        }, "pool-5-thread-1");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 盯住当前账号。切号时客户端攥着旧 READY 里的 id，会被 {@link #isForeignLogin} 判成
     * 「别的登录」，所有请求 404、整条连接哑掉——而 HTTP 与 WebSocket 是分开的，服务端没法
     * 只凭请求头认出「这个旧 id 是它当初从我们这儿拿的」。所以只能主动断开让它们重连重取
     * READY。换号要连续两拍才认，避免 selfUin() 抖动时来回断连。
     */
    private void trackLoginChange() {
        try {
            long uin = selfUin();
            if (uin == 0) return;
            if (advertisedUin == 0) {
                advertisedUin = uin;
                return;
            }
            if (uin == advertisedUin) {
                pendingUin = 0;
                pendingUinTicks = 0;
                return;
            }
            if (uin != pendingUin) {
                pendingUin = uin;
                pendingUinTicks = 1;
                return;
            }
            if (++pendingUinTicks < 2) return;

            L.i("login changed " + advertisedUin + " -> " + uin + "; recycling clients");
            advertisedUin = uin;
            pendingUin = 0;
            pendingUinTicks = 0;
            emitLoginUpdated();
            recycleClients();
        } catch (Throwable t) {
            L.e("trackLoginChange", t);
        }
    }

    /** 断开所有客户端，让它们重连后拿到带新账号的 READY。 */
    private void recycleClients() {
        for (WsConn c : new java.util.ArrayList<>(identified)) {
            try { c.sendClose(); } catch (Throwable ignore) { }
        }
        identified.clear();
        for (WsConn c : new java.util.ArrayList<>(awaitingReady.keySet())) {
            try { c.sendClose(); } catch (Throwable ignore) { }
        }
        awaitingReady.clear();
    }

    /** Deliver READY to the clients that identified before QQ could name its account. */
    private void flushAwaitingReady() {
        if (awaitingReady.isEmpty() || selfUin() == 0) return;
        for (java.util.Map.Entry<WsConn, JSONObject> e : awaitingReady.entrySet()) {
            WsConn conn = e.getKey();
            if (!awaitingReady.remove(conn, e.getValue())) continue;
            identified.add(conn);
            try { sendReady(conn, e.getValue()); }
            catch (Throwable t) { L.e("deferred READY", t); }
        }
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

    private String noticeDiag() {
        if (!cfg.statusNotification) return "off";
        StatusNotice n = notice;
        if (n == null) return "pending-context";
        try { return n.diag(); } catch (Throwable t) { return "err:" + t; }
    }

    private String wakeLockDiag() {
        if (!cfg.wakeLockControl && !cfg.wifiSustain) return "off";
        com.satori.qq.qq.WakeLockCtl w = wakeLock;
        if (w == null) return "pending-context";
        try { return w.diag(); } catch (Throwable t) { return "err:" + t; }
    }

    private JSONObject status(boolean online) throws Exception {
        return new JSONObject()
                .put("online", online)
                .put("good", online)
                .put("online_since_epoch_ms", onlineSinceMs)
                .put("outbound_guard", outboundGuard.stats())
                .put("keepalive", com.satori.qq.qq.Keepalive.diag())
                // QQ 自己的环境结论（只读探针）
                .put("qsec", com.satori.qq.qq.EnvProbe.snapshot())
                .put("inbound", new JSONObject()
                        .put("recv", inRecv.get())
                        .put("add", inAdd.get())
                        .put("update", inUpdate.get())
                        .put("gray_tip", inGrayTip.get())
                        .put("emitted", inEmit.get())
                        .put("notices", inNotice.get())
                        .put("manual_self", inManualSelf.get()))
                .put("outbound_guard_ok", true);
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
                .put("manual_self_messages", cfg.manualSelfMessages)
                .put("manual_self_user_id", manualSelfUserId(selfUin()))
                .put("hist", "60");
    }

    private void scheduleRestart(int delayMs) {
        Thread t = new Thread(() -> {
            try { Thread.sleep(delayMs); } catch (InterruptedException ignore) { return; }
            L.i("restart requested; exiting QQ process (reopen to bring the service back)");
            StatusNotice n = notice;
            if (n != null) n.cancel(); // drop the stale "running" entry before the process dies
            Runtime.getRuntime().exit(0);
        }, "pool-5-thread-2");
        t.setDaemon(true);
        t.start();
    }
}
