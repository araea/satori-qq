package com.satori.qq.core;

import com.satori.qq.Cfg;
import com.satori.qq.L;
import com.satori.qq.net.WsConn;
import com.satori.qq.qq.QQClient;
import com.satori.qq.satori.Codec;
import com.satori.qq.satori.Protocol;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Satori 的事件通道：WebSocket 握手（IDENTIFY → READY）、事件广播与断线重放、心跳。
 *
 * <p>所有对客户端的写都在 {@link #lock} 下，保证 {@code sn} 与送达顺序一致：客户端的游标只增不减，
 * 先分到小号的事件若后到，会被客户端当成旧事件丢掉。
 */
final class Events {
    private static final int OP_EVENT = 0, OP_PING = 1, OP_PONG = 2, OP_IDENTIFY = 3, OP_READY = 4;

    /** Replay storage only; events are broadcast before this method returns and are never batched. */
    private static final int REPLAY_BUFFER = 4096;

    private final Cfg cfg;
    private final Identity identity;
    private final Directory directory;
    private final MsgStore store;
    private final MessageFreshness freshness;

    private final Object lock = new Object();
    private final Set<WsConn> identified = ConcurrentHashMap.newKeySet();
    /**
     * Clients that identified before QQ had an account to name. READY is what hands a client the
     * login it latches onto, so it waits here until selfUin() resolves; see {@link #identify}.
     */
    private final Map<WsConn, JSONObject> awaiting = new ConcurrentHashMap<>();
    private final AtomicLong sn = new AtomicLong(System.currentTimeMillis());
    private final String session = UUID.randomUUID().toString();
    private final ArrayDeque<JSONObject> recent = new ArrayDeque<>();
    private final Set<Long> seenRecalls = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> recentGuildChanges = new ConcurrentHashMap<>();
    private volatile Supplier<Collection<JSONObject>> pending = List::of;

    // Server-side heartbeat counters, surfaced in /healthz. Application-level OP_PING answers
    // are handled in onText; these count the transport-level pings we initiate.
    private final AtomicLong pings = new AtomicLong();
    private final AtomicLong pongs = new AtomicLong();
    private final AtomicLong reaped = new AtomicLong();

    Events(Cfg cfg, Identity identity, Directory directory, MsgStore store, MessageFreshness freshness) {
        this.cfg = cfg;
        this.identity = identity;
        this.directory = directory;
        this.store = store;
        this.freshness = freshness;
    }

    /** 重连时要补给客户端的、还没处理的请求事件（好友申请、入群申请）。 */
    void pendingSource(Supplier<Collection<JSONObject>> source) { pending = source; }

    boolean hasClients() { return !identified.isEmpty(); }

    // ------------------------------------------------------------------ WebSocket

    void onOpen(WsConn conn) {
        Workers.start(Workers.IDENTIFY_TIMEOUT, () -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                return;
            }
            if (!identified.contains(conn) && !awaiting.containsKey(conn)) conn.close();
        });
    }

    void onText(WsConn conn, String text) {
        JSONObject msg;
        try {
            msg = new JSONObject(text);
        } catch (Throwable t) {
            return;
        }
        int op = msg.optInt("op", -1);
        JSONObject body = msg.optJSONObject("body");
        if (body == null) body = new JSONObject();
        try {
            if (op == OP_IDENTIFY) {
                identify(conn, body);
            } else if ((identified.contains(conn) || awaiting.containsKey(conn)) && op == OP_PING) {
                conn.send(frame(OP_PONG, new JSONObject()).toString());
            }
        } catch (Throwable t) {
            L.e("ws opcode " + op, t);
        }
    }

    void onClose(WsConn conn) {
        identified.remove(conn);
        awaiting.remove(conn);
    }

    /**
     * Answer IDENTIFY with READY, but only once the login is nameable.
     *
     * <p>READY carries the login a client latches onto and echoes back as {@code Satori-User-ID} on
     * every later request. Emitting it while QQ's account is still unknown advertises id {@code 0},
     * which the client then holds for the life of the connection; the login-header check rejects
     * every such request as addressed to a login we do not serve, so nothing can be sent until the
     * client reconnects. Hold READY until an account exists — it goes out on the next status tick
     * (see {@link #flushAwaiting}).
     */
    private void identify(WsConn conn, JSONObject body) throws Exception {
        if (cfg.token != null && !cfg.token.isEmpty() && !cfg.token.equals(body.optString("token", ""))) {
            conn.closeWith(4004);
            return;
        }
        if (identity.selfUin() == 0) {
            awaiting.put(conn, body);
            return;
        }
        ready(conn, body);
    }

    private void ready(WsConn conn, JSONObject identifyBody) throws Exception {
        synchronized (lock) {
            if (identified.contains(conn)) return;
            conn.send(frame(OP_READY, new JSONObject()
                    .put("logins", new JSONArray().put(identity.loginFull()))
                    .put("proxy_urls", new JSONArray())
                    .put("satori_qq", new JSONObject().put("session_id", session).put("sn", sn.get()))).toString());
            if (Protocol.shouldReplay(identifyBody)) replay(conn, identifyBody.optLong("sn", 0));
            else for (JSONObject event : pending.get()) sendPending(conn, event);
            identified.add(conn);
        }
    }

    /** Deliver READY to the clients that identified before QQ could name its account. */
    void flushAwaiting() {
        if (awaiting.isEmpty() || identity.selfUin() == 0) return;
        for (Map.Entry<WsConn, JSONObject> entry : awaiting.entrySet()) {
            WsConn conn = entry.getKey();
            if (!awaiting.remove(conn, entry.getValue())) continue;
            try {
                ready(conn, entry.getValue());
            } catch (Throwable t) {
                L.e("deferred READY", t);
            }
        }
    }

    private void replay(WsConn conn, long afterSn) {
        List<JSONObject> snapshot;
        synchronized (recent) {
            snapshot = new ArrayList<>(recent);
        }
        for (JSONObject body : snapshot) {
            if (body.optLong("sn") <= afterSn) continue;
            try {
                conn.send(frame(OP_EVENT, body).toString());
            } catch (Throwable ignore) {
                // 这条连接写不出去了，由心跳回收
            }
        }
    }

    private static JSONObject frame(int op, JSONObject body) throws Exception {
        return new JSONObject().put("op", op).put("body", body == null ? JSONObject.NULL : body);
    }

    // ------------------------------------------------------------------ 发布

    /** QQ 侧产生的事件（OneBot 形状）→ Satori 事件 → 广播。 */
    void emit(JSONObject ob) {
        synchronized (lock) {
            try {
                directory.attachGroupName(ob);
                JSONObject body = Codec.promote(Codec.toSatoriEvent(ob, identity.loginSlim(), 0, identity.assetBase()));
                if (body != null) emitSatori(body);
            } catch (Throwable t) {
                L.e("emit event", t);
            }
        }
    }

    void emitSatori(JSONObject body) throws Exception {
        synchronized (lock) {
            body.put("sn", sn.incrementAndGet());
            freshness.observe(body);
            synchronized (recent) {
                recent.addLast(body);
                while (recent.size() > REPLAY_BUFFER) recent.removeFirst();
            }
            String payload = frame(OP_EVENT, body).toString();
            for (WsConn c : identified) c.send(payload);
        }
    }

    /**
     * Pending requests are connection-local (not replayable), but their sequence numbers share the
     * same clock as broadcast events. Allocate only after conversion and under the delivery lock,
     * otherwise a concurrent broadcast can arrive with a smaller sn.
     */
    void sendPending(WsConn conn, JSONObject ob) {
        synchronized (lock) {
            try {
                JSONObject body = Codec.promote(Codec.toSatoriEvent(ob, identity.loginSlim(), 0, identity.assetBase()));
                if (body == null) return;
                body.put("sn", sn.incrementAndGet());
                conn.send(frame(OP_EVENT, body).toString());
            } catch (Throwable t) {
                L.e("pending request delivery", t);
            }
        }
    }

    /** 一次撤回的通知；同一条消息的撤回从多路回调进来，只推第一次。 */
    void recalled(boolean group, long peer, long user, long operator, int storeId, long qqMsgId) {
        if (qqMsgId == 0 && storeId != 0) {
            MsgStore.Rec rec = store.get(storeId);
            if (rec != null) qqMsgId = rec.msgId;
        }
        long key = qqMsgId != 0 ? qqMsgId : storeId;
        if (key != 0 && !firstRecall(key)) return;
        try {
            emit(Notices.recall(identity.selfUin(), System.currentTimeMillis() / 1000, group, peer, user,
                    operator, storeId, qqMsgId));
        } catch (Throwable t) {
            L.e("emitRecall", t);
        }
    }

    /** 这条撤回是第一次见到吗。 */
    boolean firstRecall(long key) {
        boolean first = seenRecalls.add(key);
        if (seenRecalls.size() > 8000) seenRecalls.clear();
        return first;
    }

    /** 群（及它唯一的频道）新增、更新或移除：连着两条事件一起推；3 秒内的重复通知只算一次。 */
    void guildChanged(String suffix, long groupId, String name) throws Exception {
        long now = System.currentTimeMillis();
        Long previous = recentGuildChanges.put(suffix + ":" + groupId + ":" + (name == null ? "" : name), now);
        if (previous != null && now - previous < 3000) return;
        if (recentGuildChanges.size() > 1000) recentGuildChanges.clear();
        JSONObject guild = Codec.guild(groupId, name);
        emitSatori(new JSONObject()
                .put("sn", 0)
                .put("type", "guild-" + suffix)
                .put("timestamp", now)
                .put("login", identity.loginSlim())
                .put("guild", guild));
        emitSatori(new JSONObject()
                .put("sn", 0)
                .put("type", "channel-" + suffix)
                .put("timestamp", now)
                .put("login", identity.loginSlim())
                .put("guild", guild)
                .put("channel", Codec.channel(QQClient.CT_GROUP, groupId, name).put("parent_id", String.valueOf(groupId))));
    }

    void loginUpdated() {
        synchronized (lock) {
            try {
                JSONObject body = new JSONObject()
                        .put("sn", sn.incrementAndGet())
                        .put("type", "login-updated")
                        .put("timestamp", System.currentTimeMillis())
                        .put("login", identity.loginFull());
                String payload = frame(OP_EVENT, body).toString();
                for (WsConn c : identified) c.send(payload);
            } catch (Throwable t) {
                L.e("login-updated", t);
            }
        }
    }

    /**
     * 换号：旧账号的重放缓冲与去重表一并作废，{@code clearOthers} 在同一把锁里清掉别处的缓存，
     * 然后通知并断开所有客户端，让它们重连后拿到带新账号的 READY。
     */
    void accountChanged(Runnable clearOthers) {
        synchronized (lock) {
            synchronized (recent) {
                recent.clear();
            }
            seenRecalls.clear();
            recentGuildChanges.clear();
            clearOthers.run();
            loginUpdated();
            for (WsConn c : new ArrayList<>(identified)) closeQuietly(c);
            identified.clear();
            for (WsConn c : new ArrayList<>(awaiting.keySet())) closeQuietly(c);
            awaiting.clear();
        }
    }

    private static void closeQuietly(WsConn conn) {
        try {
            conn.sendClose();
        } catch (Throwable ignore) {
            // 对端已经走了
        }
    }

    // ------------------------------------------------------------------ 心跳

    /**
     * Transport heartbeat: ping every attached client and reap the ones that stopped answering.
     *
     * <p>Satori clients answer WebSocket pings in the protocol library, so a missing pong is not
     * client misbehaviour — it is a half-open socket (NAT timeout, radio loss, frozen peer) that
     * would otherwise sit in {@link #identified} forever and swallow events. Timeout is two
     * heartbeat periods so a single dropped frame does not drop a healthy client.
     */
    void heartbeat(long now, long interval) {
        long timeout = Math.max(2 * interval, 30_000L);
        for (WsConn c : identified) {
            long idle = now - c.lastInboundMs();
            if (idle > timeout) {
                reaped.incrementAndGet();
                L.i("heartbeat: dropping client idle " + (idle / 1000) + "s");
                identified.remove(c);
                // A close frame, not a bare socket drop: a client that was only frozen reads it when
                // it thaws and reconnects with its cursor, instead of seeing the connection reset.
                c.closeWith(1001);
                continue;
            }
            // A pong is what we expect; OP_PONG / any data frame counts as liveness too.
            if (c.lastPingMs() > 0 && c.lastInboundMs() >= c.lastPingMs()) pongs.incrementAndGet();
            c.sendPing();
            pings.incrementAndGet();
        }
    }

    JSONObject heartbeatStats() throws Exception {
        return new JSONObject()
                .put("enabled", cfg.heartbeat)
                .put("interval_ms", Math.max(1000L, cfg.heartbeatMs))
                .put("pings", pings.get())
                .put("pongs", pongs.get())
                .put("reaped", reaped.get());
    }
}
