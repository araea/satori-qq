package com.satori.qq.core;

import com.satori.qq.Cfg;
import com.satori.qq.net.HttpServer;
import com.satori.qq.net.WsConn;
import com.satori.qq.qq.Convert;
import com.satori.qq.qq.QQClient;

/**
 * Satori v1 hub: HTTP RPC in + WebSocket events out. QQ kernel ops stay below this layer.
 *
 * <p>本类只负责把各部件接起来，自己不含业务：
 * <ul>
 *   <li>{@link HttpRoutes} / {@link Dispatcher}——HTTP 路由与方法分派；</li>
 *   <li>{@link Events}——WebSocket 握手、事件广播与重放、心跳；{@link Inbound}——内核回调转事件；</li>
 *   <li>{@link Messages} / {@link Forwards} / {@link History} / {@link Reactions} / {@link GroupOps} /
 *       {@link Directory} / {@link Requests}——各类 API 的实现；</li>
 *   <li>{@link OutboundGate}——所有写动作的限频、熔断与唤醒锁；{@link Monitor}——状态循环与诊断。</li>
 * </ul>
 */
public final class SatoriHub implements HttpServer.Handler {
    public static final String APP_NAME = "satori-qq";
    public static final String APP_VERSION = "0.32.0";

    private final Cfg cfg;
    private final QQClient qq;
    final Events events;
    final Dispatcher dispatcher;
    private final Inbound inbound;
    private final Monitor monitor;
    private final HttpRoutes routes;

    public SatoriHub(Cfg cfg, QQClient qq, MsgStore store) {
        this.cfg = cfg;
        this.qq = qq;
        Identity identity = new Identity(qq);
        Convert conv = new Convert(qq, store);
        MessageFreshness freshness = new MessageFreshness();
        Directory directory = new Directory(qq, store);
        Resources resources = new Resources(qq, store, identity);
        OutboundGate gate = new OutboundGate(cfg, qq, identity);
        Lookup lookup = new Lookup(qq, store, conv, directory);

        events = new Events(cfg, identity, directory, store, freshness);
        Sender sender = new Sender(cfg, qq, store, conv, identity, directory, gate, freshness);
        Forwards forwards = new Forwards(cfg, qq, store, conv, identity, directory, sender);
        History history = new History(qq, store, conv, identity, directory, lookup, resources);
        Messages messages = new Messages(qq, identity, lookup, resources, sender, forwards, history, events, freshness);
        Reactions reactions = new Reactions(qq, conv, identity, lookup, events);
        GroupOps groupOps = new GroupOps(qq, identity, directory, lookup, resources, sender, events, gate);
        Requests requests = new Requests(qq, identity, directory);
        events.pendingSource(requests::pending);
        inbound = new Inbound(cfg, qq, store, conv, identity, directory, history, reactions, requests, events, sender);

        // 换号时旧账号的缓存一律作废：别让新登录看到上一个号的消息与资源。
        monitor = new Monitor(cfg, qq, identity, events, gate, inbound, () -> {
            requests.reset();
            reactions.reset();
            store.clear();
            freshness.clear();
            directory.reset();
            inbound.reset();
            sender.reset();
            history.reset();
        });
        dispatcher = new Dispatcher(qq, identity, gate, directory, messages, history, reactions, groupOps,
                requests, forwards, monitor);
        routes = new HttpRoutes(cfg, qq, identity, store, resources, gate, dispatcher, monitor);
    }

    public void start() {
        qq.setListener(inbound);
        monitor.start(new HttpServer(cfg, this));
    }

    @Override public boolean streams(String method, String path) { return routes.streams(method, path); }

    @Override public HttpServer.HttpResult onHttp(HttpServer.HttpReq req) { return routes.handle(req); }

    @Override public void onWsOpen(WsConn conn) { events.onOpen(conn); }

    @Override public void onWsText(WsConn conn, String text) { events.onText(conn, text); }

    @Override public void onWsClose(WsConn conn) { events.onClose(conn); }
}
