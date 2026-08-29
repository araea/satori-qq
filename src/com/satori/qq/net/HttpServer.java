package com.satori.qq.net;

import com.satori.qq.Cfg;
import com.satori.qq.L;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** HTTP + RFC6455 server on one local port. Satori API is HTTP; events are WebSocket. */
public final class HttpServer {
    public interface Handler {
        HttpResult onHttp(HttpReq req);
        void onWsText(WsConn conn, String text);
        default void onWsOpen(WsConn conn) {}
        default void onWsClose(WsConn conn) {}
    }

    public static final class HttpReq {
        public final String method;
        public final String path;
        public final String query;
        public final Map<String, String> headers;
        public final byte[] body;
        public HttpReq(String method, String path, String query, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.path = path;
            this.query = query == null ? "" : query;
            this.headers = headers;
            this.body = body == null ? new byte[0] : body;
        }
        public String header(String name) {
            String v = headers.get(name.toLowerCase(Locale.ROOT));
            return v == null ? "" : v;
        }
        public String bodyText() {
            try { return new String(body, "UTF-8"); } catch (Exception e) { return ""; }
        }
    }

    public static final class HttpResult {
        public final int status;
        public final String contentType;
        public final byte[] body;
        public final Map<String, String> extraHeaders;
        public HttpResult(int status, String contentType, byte[] body) {
            this(status, contentType, body, null);
        }
        public HttpResult(int status, String contentType, byte[] body, Map<String, String> extra) {
            this.status = status;
            this.contentType = contentType == null ? "text/plain; charset=utf-8" : contentType;
            this.body = body == null ? new byte[0] : body;
            this.extraHeaders = extra;
        }
        public static HttpResult json(int status, String json) {
            byte[] b;
            try { b = json.getBytes("UTF-8"); } catch (Exception e) { b = new byte[0]; }
            return new HttpResult(status, "application/json; charset=utf-8", b);
        }
        public static HttpResult text(int status, String text) {
            byte[] b;
            try { b = text.getBytes("UTF-8"); } catch (Exception e) { b = new byte[0]; }
            return new HttpResult(status, "text/plain; charset=utf-8", b);
        }
    }

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private final Cfg cfg;
    private final Handler handler;
    private volatile ServerSocket server;
    private volatile boolean running;
    private final Set<WsConn> conns = new CopyOnWriteArraySet<>();

    public HttpServer(Cfg cfg, Handler handler) { this.cfg = cfg; this.handler = handler; }

    public void start() {
        Thread t = new Thread(this::acceptLoop, "pool-4-thread-1");
        t.setDaemon(true);
        t.start();
    }

    public int connectionCount() { return conns.size(); }

    public void broadcast(String text) {
        for (WsConn c : conns) c.send(text);
    }

    private void acceptLoop() {
        while (true) {
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(cfg.host, cfg.port));
                running = true;
                L.i("Satori server listening on " + cfg.host + ":" + cfg.port);
                while (running) {
                    Socket s = server.accept();
                    Thread ct = new Thread(() -> handleClient(s), "pool-4-thread-2");
                    ct.setDaemon(true);
                    ct.start();
                }
            } catch (Throwable e) {
                L.e("accept loop error, retry in 3s", e);
                try { if (server != null) server.close(); } catch (Throwable ignore) {}
                try { Thread.sleep(3000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private void handleClient(Socket s) {
        WsConn conn = null;
        try {
            s.setTcpNoDelay(true);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String raw = readHttpHeaders(in);
            if (raw == null) { s.close(); return; }
            String[] lines = raw.split("\r\n");
            String reqLine = lines.length > 0 ? lines[0] : "";
            String[] parts = reqLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String target = parts.length > 1 ? parts[1] : "/";
            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c < 0) continue;
                headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT),
                        lines[i].substring(c + 1).trim());
            }
            String path = target;
            String query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);

            String upgrade = headers.get("upgrade");
            boolean ws = upgrade != null && upgrade.equalsIgnoreCase("websocket");
            if (ws) {
                if (!"/v1/events".equals(path)) {
                    writeHttp(out, 404, "text/plain; charset=utf-8", "not found".getBytes("UTF-8"), null);
                    s.close();
                    return;
                }
                String key = headers.get("sec-websocket-key");
                if (key == null) {
                    writeHttp(out, 400, "text/plain; charset=utf-8", "bad request".getBytes("UTF-8"), null);
                    s.close();
                    return;
                }
                String accept = Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes("UTF-8")));
                String resp = "HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
                out.write(resp.getBytes("UTF-8"));
                out.flush();
                conn = new WsConn(s, out);
                conns.add(conn);
                L.i("Satori events connected: " + s.getRemoteSocketAddress() + " (total " + conns.size() + ")");
                try { handler.onWsOpen(conn); } catch (Throwable t) { L.e("onWsOpen", t); }
                readFrames(in, conn);
                return;
            }

            int contentLength = 0;
            try { contentLength = Integer.parseInt(headers.getOrDefault("content-length", "0")); }
            catch (Exception ignore) {}
            if (contentLength < 0) contentLength = 0;
            if (contentLength > 64 * 1024 * 1024) {
                writeHttp(out, 413, "text/plain; charset=utf-8", "too large".getBytes("UTF-8"), null);
                s.close();
                return;
            }
            byte[] body = readFully(in, contentLength);
            HttpReq req = new HttpReq(method, path, query, headers, body);
            HttpResult result;
            try {
                result = handler.onHttp(req);
            } catch (Throwable t) {
                L.e("http handler " + method + " " + path, t);
                result = HttpResult.text(500, String.valueOf(t));
            }
            if (result == null) result = HttpResult.text(404, "not found");
            writeHttp(out, result.status, result.contentType, result.body, result.extraHeaders);
        } catch (Throwable e) {
            // connection reset etc.
        } finally {
            if (conn != null) {
                conns.remove(conn);
                try { handler.onWsClose(conn); } catch (Throwable t) { L.e("onWsClose", t); }
            }
            try { s.close(); } catch (Throwable ignore) {}
            if (conn != null) L.d("Satori events disconnected (total " + conns.size() + ")");
        }
    }

    boolean authOk(Map<String, String> headers, String query) {
        if (cfg.token == null || cfg.token.isEmpty()) return true;
        String given = null;
        String auth = headers.get("authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) given = auth.substring(7).trim();
        if (given == null && query != null) {
            for (String part : query.split("&")) {
                int eq = part.indexOf('=');
                String k = eq < 0 ? part : part.substring(0, eq);
                String v = eq < 0 ? "" : part.substring(eq + 1);
                if ("access_token".equals(k)) {
                    try { given = URLDecoder.decode(v, "UTF-8"); } catch (Exception e) { given = v; }
                    break;
                }
            }
        }
        return given != null && given.equals(cfg.token);
    }

    private static void writeHttp(OutputStream out, int status, String type, byte[] body,
                                  Map<String, String> extra) throws Exception {
        String reason;
        switch (status) {
            case 200: reason = "OK"; break;
            case 204: reason = "No Content"; break;
            case 400: reason = "Bad Request"; break;
            case 401: reason = "Unauthorized"; break;
            case 403: reason = "Forbidden"; break;
            case 404: reason = "Not Found"; break;
            case 405: reason = "Method Not Allowed"; break;
            case 413: reason = "Payload Too Large"; break;
            default: reason = status >= 500 ? "Server Error" : "Error"; break;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        sb.append("Connection: close\r\n");
        if (type != null) sb.append("Content-Type: ").append(type).append("\r\n");
        sb.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n");
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
        if (body != null && body.length > 0) out.write(body);
        out.flush();
    }

    private String readHttpHeaders(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c; int prev = -1; int state = 0;
        while ((c = in.read()) != -1) {
            sb.append((char) c);
            if (c == '\n' && prev == '\r') { state++; if (state == 2) break; }
            else if (c != '\r') state = 0;
            prev = c;
            if (sb.length() > 16384) break;
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static byte[] readFully(InputStream in, int n) throws Exception {
        if (n <= 0) return new byte[0];
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) break;
            off += r;
        }
        if (off == n) return buf;
        byte[] slim = new byte[off];
        System.arraycopy(buf, 0, slim, 0, off);
        return slim;
    }

    private void readFrames(InputStream in, WsConn conn) throws Exception {
        ByteArrayOutputStream frag = new ByteArrayOutputStream();
        int fragOpcode = 0;
        while (true) {
            int b0 = in.read();
            if (b0 < 0) break;
            int b1 = in.read();
            if (b1 < 0) break;
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = ((long) readN(in) << 8) | readN(in);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | readN(in);
            }
            byte[] mask = new byte[4];
            if (masked) { for (int i = 0; i < 4; i++) mask[i] = (byte) readN(in); }
            if (len > 64L * 1024 * 1024) { L.w("Frame too large: " + len); break; }
            byte[] payload = new byte[(int) len];
            int off = 0;
            while (off < payload.length) {
                int r = in.read(payload, off, payload.length - off);
                if (r < 0) return;
                off += r;
            }
            if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];

            switch (opcode) {
                case 0x0:
                    frag.write(payload);
                    if (fin) { deliver(conn, fragOpcode, frag.toByteArray()); frag.reset(); fragOpcode = 0; }
                    break;
                case 0x1:
                case 0x2:
                    if (fin) deliver(conn, opcode, payload);
                    else { fragOpcode = opcode; frag.reset(); frag.write(payload); }
                    break;
                case 0x8:
                    conn.sendClose();
                    return;
                case 0x9:
                    conn.sendPong(payload);
                    break;
                default:
                    break;
            }
        }
    }

    private void deliver(WsConn conn, int opcode, byte[] data) {
        if (opcode != 0x1) return;
        try {
            handler.onWsText(conn, new String(data, "UTF-8"));
        } catch (Throwable t) {
            L.e("ws handler", t);
        }
    }

    private int readN(InputStream in) throws Exception {
        int v = in.read();
        if (v < 0) throw new java.io.EOFException();
        return v;
    }
}
