package com.satori.qq.net;

import com.satori.qq.Cfg;
import com.satori.qq.L;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
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
import org.json.JSONObject;

/** HTTP + RFC6455 server on one local port. Satori API is HTTP; events are WebSocket. */
public final class HttpServer {
    public interface Handler {
        HttpResult onHttp(HttpReq req);
        /**
         * 这条路由要不要流式读请求体。是的话 {@link HttpReq#stream} 给出限长的输入流，{@link HttpReq#body}
         * 为空，处理方自己读（上传一类大请求体不进堆）；读不完的部分由服务器在应答后丢弃。
         */
        default boolean streams(String method, String path) { return false; }
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
        /** 流式路由的请求体（见 {@link Handler#streams}），其余请求为 null。 */
        public final InputStream stream;
        /** 请求体声明的长度；流式路由时 {@link #body} 为空，长度以它为准。 */
        public final long length;
        public HttpReq(String method, String path, String query, Map<String, String> headers, byte[] body) {
            this(method, path, query, headers, body, null, body == null ? 0 : body.length);
        }
        public HttpReq(String method, String path, String query, Map<String, String> headers, byte[] body,
                       InputStream stream, long length) {
            this.method = method;
            this.path = path;
            this.query = query == null ? "" : query;
            this.headers = headers;
            this.body = body == null ? new byte[0] : body;
            this.stream = stream;
            this.length = length;
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
        /** 正文来自文件的一段（{@link #file} 非空时 {@link #body} 为空）：按块写出，不整份读进堆。 */
        public final File file;
        public final long fileOffset;
        public final long fileLength;
        public HttpResult(int status, String contentType, byte[] body) {
            this(status, contentType, body, null);
        }
        public HttpResult(int status, String contentType, byte[] body, Map<String, String> extra) {
            this(status, contentType, body, extra, null, 0, 0);
        }
        private HttpResult(int status, String contentType, byte[] body, Map<String, String> extra,
                           File file, long fileOffset, long fileLength) {
            this.status = status;
            this.contentType = contentType == null ? "text/plain; charset=utf-8" : contentType;
            this.body = body == null ? new byte[0] : body;
            this.extraHeaders = extra;
            this.file = file;
            this.fileOffset = fileOffset;
            this.fileLength = fileLength;
        }
        /** 文件里 [offset, offset+length) 这一段做正文（整份文件就是 0 到 file.length()）。 */
        public static HttpResult file(int status, String contentType, File file, long offset, long length,
                                      Map<String, String> extra) {
            return new HttpResult(status, contentType, null, extra, file, offset, length);
        }
        public static HttpResult json(int status, String json) {
            return json(status, json, null);
        }
        public static HttpResult json(int status, String json, Map<String, String> extra) {
            byte[] b;
            try { b = json.getBytes("UTF-8"); } catch (Exception e) { b = new byte[0]; }
            return new HttpResult(status, "application/json; charset=utf-8", b, extra);
        }
        /**
         * 错误体：{@code {"code": "<机器可读的短名>", "message": "<给人看的>"}}。
         * 本实现端发出的每一个非 2xx 响应都是这个形状，客户端按 {@code code} 判断，不必匹配文案。
         * {@code code} 缺省按状态码取（400 invalid_request、404 not_found……）。
         */
        public static HttpResult error(int status, String message) {
            return error(status, null, message, null);
        }
        public static HttpResult error(int status, String code, String message, Map<String, String> extra) {
            String slug = code != null && !code.isEmpty() ? code : defaultCode(status);
            return json(status, errorBody(slug, message), extra);
        }
        public static String errorBody(String code, String message) {
            try {
                return new JSONObject().put("code", code)
                        .put("message", message == null ? "" : message).toString();
            } catch (Exception e) { return "{\"code\":\"" + code + "\",\"message\":\"error\"}"; }
        }
        public static String defaultCode(int status) {
            switch (status) {
                case 400: return "invalid_request";
                case 401: return "missing_token";
                case 403: return "forbidden";
                case 404: return "not_found";
                case 405: return "method_not_allowed";
                case 413: return "payload_too_large";
                case 416: return "range_not_satisfiable";
                case 429: return "rate_limited";
                case 503: return "unavailable";
                default: return status >= 500 ? "internal_error" : "error";
            }
        }
        public static HttpResult text(int status, String text) {
            byte[] b;
            try { b = text.getBytes("UTF-8"); } catch (Exception e) { b = new byte[0]; }
            return new HttpResult(status, "text/plain; charset=utf-8", b);
        }
    }

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    /** 一个请求体的字节上限。整份读进内存，所以设防；对客户端的口径见 {@link #MAX_UPLOAD_BYTES}。 */
    public static final int MAX_BODY_BYTES = 64 * 1024 * 1024;
    /** {@code upload.create} 里单个文件能有多大：请求体上限扣掉 multipart 的封装余量。 */
    public static final long MAX_UPLOAD_BYTES = MAX_BODY_BYTES - 1024 * 1024;
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

    /** True while the accept loop holds an open, bound socket on the Satori port. */
    public boolean isListening() {
        ServerSocket s = server;
        return running && s != null && s.isBound() && !s.isClosed();
    }

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
            // HEAD 就是不带正文的 GET：照 GET 路由处理，响应只写头（Content-Length 仍是正文的长度）。
            final boolean headOnly = "HEAD".equals(method);
            if (headOnly) method = "GET";
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
                    writeResult(out, HttpResult.error(404, "not found"), false);
                    s.close();
                    return;
                }
                String key = headers.get("sec-websocket-key");
                if (key == null) {
                    writeResult(out, HttpResult.error(400, "bad request"), false);
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

            long contentLength = 0;
            try { contentLength = Long.parseLong(headers.getOrDefault("content-length", "0").trim()); }
            catch (Exception ignore) {}
            if (contentLength < 0) contentLength = 0;
            if (contentLength > MAX_BODY_BYTES) {
                // 先应答再把没读的丢掉：客户端还在发请求体时就关连接，对方会收到 RST 而读不到这条 413。
                writeResult(out, HttpResult.error(413, "payload_too_large",
                        "request body exceeds " + (MAX_BODY_BYTES >> 20) + " MiB; a single upload.create file may be at most "
                                + MAX_UPLOAD_BYTES + " bytes", null), false);
                lingerAndDrain(s, in, contentLength);
                return;
            }
            if (contentLength > 0 && "100-continue".equalsIgnoreCase(headers.getOrDefault("expect", "").trim())) {
                out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes("UTF-8"));
                out.flush();
            }
            LimitedInput stream = null;
            HttpReq req;
            if (handler.streams(method, path)) {
                stream = new LimitedInput(in, contentLength);
                req = new HttpReq(method, path, query, headers, new byte[0], stream, contentLength);
            } else {
                byte[] body = readFully(in, (int) contentLength);
                req = new HttpReq(method, path, query, headers, body);
            }
            HttpResult result;
            try {
                result = handler.onHttp(req);
            } catch (Throwable t) {
                L.e("http handler " + method + " " + path, t);
                result = HttpResult.error(500, String.valueOf(t));
            }
            if (result == null) result = HttpResult.error(404, "not found");
            writeResult(out, result, headOnly);
            if (stream != null && stream.remaining() > 0) lingerAndDrain(s, in, stream.remaining());
        } catch (IOException e) {
            // connection reset, client went away mid-request
        } catch (Throwable e) {
            L.e("http connection", e);
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

    private static void writeResult(OutputStream out, HttpResult r, boolean headOnly) throws Exception {
        if (r.file != null) {
            writeHttp(out, r.status, r.contentType, null, r.fileLength, r.extraHeaders, true);
            if (!headOnly) copyRange(out, r.file, r.fileOffset, r.fileLength);
            out.flush();
            return;
        }
        writeHttp(out, r.status, r.contentType, r.body, r.body.length, r.extraHeaders, headOnly);
    }

    /** 文件里 [offset, offset+length) 按 64 KiB 一块写出。文件在写的途中变短就提前断开，对方看到长度不符。 */
    private static void copyRange(OutputStream out, File file, long offset, long length) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);
            byte[] buf = new byte[64 * 1024];
            long left = length;
            while (left > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new EOFException("file shrank while serving");
                out.write(buf, 0, n);
                left -= n;
            }
        }
    }

    /**
     * 应答已发出后收尾：先关写端让对方读到应答，再把还没读的请求体丢掉（限时限量），最后才关连接。
     * 直接 close 一个还有未读数据的 socket 会发 RST，对方可能连应答都读不到，只看见「发送失败」。
     */
    private static void lingerAndDrain(Socket s, InputStream in, long remaining) {
        try {
            s.shutdownOutput();
            s.setSoTimeout(2000);
            long deadline = System.currentTimeMillis() + 10_000L;
            long budget = Math.min(remaining, 512L * 1024 * 1024);
            byte[] sink = new byte[64 * 1024];
            while (budget > 0 && System.currentTimeMillis() < deadline) {
                int n = in.read(sink, 0, (int) Math.min(sink.length, budget));
                if (n < 0) break;
                budget -= n;
            }
        } catch (Throwable ignore) {
        }
    }

    /** 至多读 {@code limit} 字节的输入流，不关底层连接；{@link #remaining} 是还没被读走的字节数。 */
    private static final class LimitedInput extends InputStream {
        private final InputStream in;
        private long left;
        LimitedInput(InputStream in, long limit) { this.in = in; this.left = limit; }
        long remaining() { return left; }
        @Override public int read() throws IOException {
            if (left <= 0) return -1;
            int b = in.read();
            if (b >= 0) left--;
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
    }

    private static void writeHttp(OutputStream out, int status, String type, byte[] body, long length,
                                  Map<String, String> extra, boolean headOnly) throws Exception {
        String reason;
        switch (status) {
            case 200: reason = "OK"; break;
            case 204: reason = "No Content"; break;
            case 206: reason = "Partial Content"; break;
            case 400: reason = "Bad Request"; break;
            case 401: reason = "Unauthorized"; break;
            case 403: reason = "Forbidden"; break;
            case 404: reason = "Not Found"; break;
            case 405: reason = "Method Not Allowed"; break;
            case 413: reason = "Payload Too Large"; break;
            case 416: reason = "Range Not Satisfiable"; break;
            case 429: reason = "Too Many Requests"; break;
            case 502: reason = "Bad Gateway"; break;
            case 503: reason = "Service Unavailable"; break;
            default: reason = status >= 500 ? "Server Error" : "Error"; break;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        sb.append("Connection: close\r\n");
        if (type != null) sb.append("Content-Type: ").append(type).append("\r\n");
        sb.append("Content-Length: ").append(length).append("\r\n");
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
        if (!headOnly && body != null && body.length > 0) out.write(body);
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
            conn.noteInbound();

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
        if (v < 0) throw new EOFException();
        return v;
    }
}
