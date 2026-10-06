package com.satori.qq.core;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import com.satori.qq.Cfg;
import com.satori.qq.L;
import com.satori.qq.control.ControlBridge;
import com.satori.qq.net.HttpServer;
import com.satori.qq.packet.PacketSvc;
import com.satori.qq.qq.Compat;
import com.satori.qq.qq.EnvProbe;
import com.satori.qq.qq.Keepalive;
import com.satori.qq.qq.QQClient;
import com.satori.qq.qq.WakeLockCtl;
import com.satori.qq.xp.Xp;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 服务的生命与健康：每秒一拍的状态循环（在线状态、心跳、换号、常驻通知与唤醒锁），
 * 以及 {@code /healthz}、{@code internal/status}、{@code internal/version} 报出去的诊断。
 */
final class Monitor {
    /** 静态自检不快，但也不必每次探针都跑；换了个 QQ 版本或过了 10 分钟就重算。 */
    private static final long COMPAT_TTL_MS = 10 * 60 * 1000L;

    private final Cfg cfg;
    private final QQClient qq;
    private final Identity identity;
    private final Events events;
    private final OutboundGate gate;
    private final Inbound inbound;
    private final Runnable clearAccountState;
    private volatile HttpServer server;
    private volatile StatusNotice notice;

    private volatile JSONObject compatCache;
    private volatile String compatCacheVersion = "";
    private volatile long compatCacheMs;

    /** READY 里报出去的账号。换了号就要通知并回收客户端——旧 id 在新账号下会被判成别的登录。 */
    private long advertisedUin;
    private long pendingUin;
    private int pendingUinTicks;

    private volatile long lastNameGuardMs;
    private volatile String nameGuard = "idle";

    Monitor(Cfg cfg, QQClient qq, Identity identity, Events events, OutboundGate gate, Inbound inbound,
            Runnable clearAccountState) {
        this.cfg = cfg;
        this.qq = qq;
        this.identity = identity;
        this.events = events;
        this.gate = gate;
        this.inbound = inbound;
        this.clearAccountState = clearAccountState;
    }

    /** 等 QQ 的 Application 建好、拿到 Context，再拉起 HTTP 端口与状态循环。 */
    void start(HttpServer httpServer) {
        server = httpServer;
        Thread bootstrap = Workers.daemon(Workers.BOOTSTRAP, () -> {
            // QQ hooks are installed immediately by Main. Only the listener waits for a Context.
            Context context = qq.appContext();
            for (int retry = 0; !contextReady(context) && retry < 200; retry++) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException stopped) {
                    return;
                }
                context = qq.appContext();
            }
            if (contextReady(context)) ControlBridge.bootstrap(context, cfg, SatoriHub.APP_VERSION);
            httpServer.start();
            refreshNotice();
            Workers.start(Workers.STATUS_MONITOR, this::loop);
        });
        new Handler(Looper.getMainLooper()).post(bootstrap::start);
    }

    private static boolean contextReady(Context context) {
        return context != null
                && (!(context instanceof ContextWrapper wrapper) || wrapper.getBaseContext() != null);
    }

    boolean listening() {
        HttpServer s = server;
        return s != null && s.isListening();
    }

    int connections() {
        HttpServer s = server;
        return s == null ? 0 : s.connectionCount();
    }

    // ------------------------------------------------------------------ 状态循环

    private void loop() {
        boolean previous = qq.isOnline();
        gate.onlineSince(previous ? System.currentTimeMillis() : 0);
        long interval = Math.max(1000L, cfg.heartbeatMs);
        long nextHeartbeat = System.currentTimeMillis() + interval;
        while (true) {
            try {
                Thread.sleep(1000);
                boolean online = qq.isOnline();
                qq.sustainKernel(cfg.kernelForeground && online && events.hasClients());
                long now = System.currentTimeMillis();
                if (online != previous) {
                    gate.onlineSince(online ? now : 0);
                    // 踢线台账里的 up=<秒> 就是从这里来的：一条踢线是「登录后多久」被推下来的，
                    // 是判周期还是事件驱动的唯一线索。
                    L.i("QQ kernel state -> " + (online ? "online" : "offline"));
                    events.loginUpdated();
                    previous = online;
                }
                trackLoginChange();
                try {
                    nameGuardTick(now);
                } catch (Throwable e) {
                    L.e("nameGuardTick", e);
                }
                if (cfg.heartbeat && now >= nextHeartbeat) {
                    nextHeartbeat = now + interval;
                    events.heartbeat(now, interval);
                }
                events.flushAwaiting();
                // Hold the wake lock from startup even when the status notification is off.
                if (cfg.wakeLockControl || cfg.wifiSustain) wakeLock();
                refreshNotice();
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable ignore) {
                // 一拍出错不能让循环死掉
            }
        }
    }

    /**
     * 盯住当前账号。切号时客户端攥着旧 READY 里的 id，会被判成「别的登录」，所有请求 404、整条
     * 连接哑掉——而 HTTP 与 WebSocket 是分开的，服务端没法只凭请求头认出「这个旧 id 是它当初从我们
     * 这儿拿的」。所以只能主动断开让它们重连重取 READY。换号要连续两拍才认，避免 selfUin() 抖动时
     * 来回断连。
     */
    private void trackLoginChange() {
        try {
            long uin = identity.selfUin();
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
            events.accountChanged(clearAccountState);
        } catch (Throwable t) {
            L.e("trackLoginChange", t);
        }
    }

    /**
     * 群名守卫。
     *
     * <p>2026-09-19 起，群名反复变空而 `satori-writes.log` 里没有任何写入记录。改名能力后来
     * 整个删掉了（见 QQClient），所以现在这层只做**观察与归因**：用一次全量刷新把「本地缓存没名字」
     * 与「真的没名字」分开（前者刷新就回来了，实测修好过 818965288），刷新后仍是空就记一行
     * `name_guard=empty:<群>(见过=<名字>)` 留在 healthz 里，供事后对照时间线，一个字也不写。
     */
    private void nameGuardTick(long now) {
        if (now - lastNameGuardMs < 120_000) return;
        lastNameGuardMs = now;
        Set<Long> known = qq.knownGroupCodes();
        if (known.isEmpty()) return;
        List<Long> empty = new ArrayList<>();
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
        StringBuilder diag = new StringBuilder();
        for (Long gid : empty) {
            if (!qq.groupName(gid).isEmpty()) {
                diag.append("refresh-back:").append(gid).append(' '); // 只是本地缓存空了，刷新就回来了，不写
                continue;
            }
            // 只观察、不写：本实现端已经没有任何写群名的路径（见 QQClient 里「改名能力已移除」），
            // 所以「名字变空」一定不是本进程干的；这里只把「哪个群、我们见过什么名字」记下来。
            diag.append("empty:").append(gid).append("(见过=").append(qq.knownGroupName(gid)).append(") ");
        }
        nameGuard = diag.length() == 0 ? "idle" : diag.toString().trim();
    }

    // ------------------------------------------------------------------ 常驻通知与唤醒锁

    /**
     * Refresh the resident notification and drive foreground-service keepalive. The Context-bound
     * helpers are created lazily: at hub start QQ's Application (and thus its Context) is not
     * ready yet, so we keep retrying each monitor tick until it is.
     */
    private void refreshNotice() {
        if (!cfg.statusNotification) {
            // Radio sustain is independent of whether a notification is requested.
            driveWifiSustain(qq.isOnline(), listening());
            return;
        }
        StatusNotice n = notice;
        if (n == null || !n.available()) {
            Context ctx = qq.appContext();
            if (ctx == null) return; // Application not created yet; try again next tick
            StatusNotice created = new StatusNotice(ctx);
            if (!created.available()) return;
            notice = n = created;
        }
        if (cfg.wakeLockControl || cfg.wifiSustain) {
            // The notification button only appears when the operator toggle itself is enabled;
            // wifi_sustain alone needs the controller, not the button.
            WakeLockCtl wake = wakeLock();
            if (wake != null && cfg.wakeLockControl) n.setWake(wake);
        }
        boolean online, listening;
        try {
            online = qq.isOnline();
            listening = listening();
        } catch (Throwable t) {
            return;
        }
        try {
            n.update(online, listening, qq.selfUin(), qq.selfNick(), cfg.port, connections(), gate.onlineSinceMs());
        } catch (Throwable t) {
            L.e("status notice refresh", t);
        }
        try {
            driveWifiSustain(online, listening);
        } catch (Throwable t) {
            L.e("wifi sustain tick", t);
        }
    }

    /** Create the wake-lock controller on first use. Acquires the lock itself when wake_lock_auto. */
    private WakeLockCtl wakeLock() {
        WakeLockCtl lock = gate.wakeLock();
        if (lock != null) return lock;
        Context ctx = qq.appContext();
        if (ctx == null) return null;
        lock = new WakeLockCtl(ctx, cfg.wakeLockAuto, this::refreshNotice);
        gate.wakeLock(lock);
        return lock;
    }

    /** Keep the radio out of screen-off power save while a client is actually attached to us. */
    private void driveWifiSustain(boolean online, boolean listening) {
        WakeLockCtl lock = gate.wakeLock();
        if (lock == null) return;
        lock.sustainWifi(cfg.wifiSustain && online && listening && connections() > 0);
    }

    /** 过 {@code delayMs} 毫秒后退出 QQ 进程（重新打开 QQ 服务就回来）；退出前先撤掉常驻通知，别留一条过期的「运行中」。 */
    void restart(int delayMs) {
        Workers.start(Workers.RESTART, () -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignore) {
                return;
            }
            L.i("restart requested; exiting QQ process (reopen to bring the service back)");
            StatusNotice n = notice;
            if (n != null) n.cancel();
            Runtime.getRuntime().exit(0);
        });
    }

    // ------------------------------------------------------------------ 内核接口面自检

    /** 内核接口面自检。QQ 版本变了就重算，便于升级后第一眼看到断在哪。 */
    JSONObject compat(boolean force) {
        try {
            String version = qq.qqVersion();
            long now = System.currentTimeMillis();
            JSONObject cached = compatCache;
            if (!force && cached != null && version.equals(compatCacheVersion) && now - compatCacheMs < COMPAT_TTL_MS) {
                return cached;
            }
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
        JSONObject full = compat(false);
        if (full.has("error")) return new JSONObject().put("ok", false).put("error", full.opt("error"));
        JSONArray missing = full.optJSONArray("missing");
        return new JSONObject()
                .put("ok", full.optBoolean("ok"))
                .put("passed", full.optInt("passed"))
                .put("total", full.optInt("total"))
                .put("missing", missing == null ? 0 : missing.length());
    }

    // ------------------------------------------------------------------ 诊断

    /**
     * Unauthenticated, local-only liveness an operator/tooling can poll to tell a truly-online
     * hub from "port up but kernel offline" without an activity dump.
     */
    JSONObject healthz() throws Exception {
        return new JSONObject()
                .put("name", SatoriHub.APP_NAME)
                .put("version", SatoriHub.APP_VERSION)
                .put("config_revision", ControlBridge.revision())
                .put("config_status", ControlBridge.status())
                .put("online", qq.isOnline())
                .put("listening", listening())
                .put("self_id", identity.selfUin())
                .put("qq_version", qq.qqVersion())
                .put("compat", compatSummary())
                .put("online_since_epoch_ms", gate.onlineSinceMs())
                .put("connections", connections())
                .put("notice", noticeDiag())
                .put("wakelock", wakeLockDiag())
                // 抗冻状态：adj 低于本机 freezer 阈值（900）就不会被冻，
                // service 是「让 QQ 自己的服务保持已启动」那一步的结果。
                .put("keepalive", Keepalive.diag())
                .put("send", qq.sendDiag())
                .put("kernel_foreground", qq.kernelForegroundDiag())
                .put("name_guard", nameGuard)
                // 模块自己发的 SSO 请求失败了几条。`session_errors` 涨了说明有请求
                // 撞上 QQ 认「票据失效」的那组错误码——即「接口层把会话打废」，
                // 而不是环境检测。这是把踢线成因分开的判据，详见 PacketSvc。
                .put("sso", new JSONObject()
                        .put("hooked", PacketSvc.ssoHookInstalled())
                        .put("native", Xp.ssoHookInfo())
                        .put("failures", PacketSvc.ssoFailures())
                        .put("session_errors", PacketSvc.ssoSessionErrors())
                        .put("log", new JSONArray(PacketSvc.ssoLog())))
                .put("session", qq.sessionDiag());
    }

    private String noticeDiag() {
        if (!cfg.statusNotification) return "off";
        StatusNotice n = notice;
        if (n == null) return "pending-context";
        try {
            return n.diag();
        } catch (Throwable t) {
            return "err:" + t;
        }
    }

    private String wakeLockDiag() {
        if (!cfg.wakeLockControl && !cfg.wifiSustain) return "off";
        WakeLockCtl lock = gate.wakeLock();
        if (lock == null) return "pending-context";
        try {
            return lock.diag();
        } catch (Throwable t) {
            return "err:" + t;
        }
    }

    /** internal/status。 */
    JSONObject status() throws Exception {
        boolean online = qq.isOnline();
        return new JSONObject()
                .put("online", online)
                .put("good", online)
                .put("online_since_epoch_ms", gate.onlineSinceMs())
                .put("outbound_guard", gate.stats())
                .put("heartbeat", events.heartbeatStats())
                .put("keepalive", Keepalive.diag())
                // QQ 自己的环境结论（只读探针）
                .put("qsec", EnvProbe.snapshot())
                .put("inbound", inbound.stats())
                .put("outbound_guard_ok", true);
    }

    /** internal/version。 */
    JSONObject version() throws Exception {
        String qqVersion = qq.qqVersion();
        return new JSONObject()
                .put("name", SatoriHub.APP_NAME)
                .put("version", SatoriHub.APP_VERSION)
                .put("protocol", "v1")
                .put("platform", Identity.PLATFORM)
                .put("adapter", Identity.ADAPTER)
                .put("qq_version", qqVersion.isEmpty() ? "unknown" : qqVersion)
                .put("runtime", "Android QQNT/Zygisk (JNI)")
                .put("manual_self_messages", cfg.manualSelfMessages)
                .put("manual_self_user_id", inbound.manualSelfUserId(identity.selfUin()))
                .put("hist", "60");
    }
}
