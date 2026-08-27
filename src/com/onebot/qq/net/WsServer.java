package com.onebot.qq.net;

import com.onebot.qq.Cfg;
import com.onebot.qq.L;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** Minimal RFC6455 forward WebSocket server. ayjx connects here as a client. */
public final class WsServer {
    public interface Handler {
        void onText(WsConn conn, String text);
        default void onOpen(WsConn conn) {}
        default void onClose(WsConn conn) {}
    }

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private final Cfg cfg;
    private final Handler handler;
    private volatile ServerSocket server;
    private volatile boolean running;
    private final Set<WsConn> conns = new CopyOnWriteArraySet<>();

    public WsServer(Cfg cfg, Handler handler) { this.cfg = cfg; this.handler = handler; }

    public void start() {
        Thread t = new Thread(this::acceptLoop, "pool-4-thread-1");
        t.setDaemon(true);
        t.start();
    }

    private void acceptLoop() {
        while (true) {
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(cfg.host, cfg.port));
                running = true;
                L.i("WebSocket server listening on " + cfg.host + ":" + cfg.port);
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

    public int connectionCount() { return conns.size(); }

    /** Send a text frame to every connected client. */
    public void broadcast(String text) {
        for (WsConn c : conns) c.send(text);
    }

    private void handleClient(Socket s) {
        WsConn conn = null;
        try {
            s.setTcpNoDelay(true);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            // ---- HTTP handshake ----
            String req = readHttpHeaders(in);
            if (req == null) { s.close(); return; }
            String key = null; String auth = null; String urlToken = null;
            String[] lines = req.split("\r\n");
            String reqLine = lines.length > 0 ? lines[0] : "";
            for (String line : lines) {
                int c = line.indexOf(':');
                if (c < 0) continue;
                String k = line.substring(0, c).trim().toLowerCase(Locale.ROOT);
                String v = line.substring(c + 1).trim();
                if (k.equals("sec-websocket-key")) key = v;
                else if (k.equals("authorization")) auth = v;
            }
            // token may also come as ?access_token=
            int q = reqLine.indexOf("access_token=");
            if (q >= 0) {
                String tail = reqLine.substring(q + "access_token=".length());
                int sp = tail.indexOf(' '); int amp = tail.indexOf('&');
                int end = tail.length();
                if (sp >= 0) end = Math.min(end, sp);
                if (amp >= 0) end = Math.min(end, amp);
                urlToken = tail.substring(0, end);
            }
            if (key == null) {
                out.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes("UTF-8")); out.flush(); s.close(); return;
            }
            // ---- auth ----
            if (cfg.token != null && !cfg.token.isEmpty()) {
                String given = null;
                if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) given = auth.substring(7).trim();
                else if (urlToken != null) given = urlToken;
                if (given == null || !given.equals(cfg.token)) {
                    L.w("Rejected client: bad token");
                    out.write("HTTP/1.1 401 Unauthorized\r\n\r\n".getBytes("UTF-8")); out.flush(); s.close(); return;
                }
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
            L.i("Client connected: " + s.getRemoteSocketAddress() + " (total " + conns.size() + ")");
            try { handler.onOpen(conn); } catch (Throwable t) { L.e("onOpen handler", t); }

            // ---- frame read loop ----
            readFrames(in, conn);
        } catch (Throwable e) {
            // connection reset etc. — normal on client disconnect
        } finally {
            if (conn != null) {
                conns.remove(conn);
                try { handler.onClose(conn); } catch (Throwable t) { L.e("onClose handler", t); }
            }
            try { s.close(); } catch (Throwable ignore) {}
            L.d("Client disconnected (total " + conns.size() + ")");
        }
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

    private void readFrames(InputStream in, WsConn conn) throws Exception {
        java.io.ByteArrayOutputStream frag = new java.io.ByteArrayOutputStream();
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
            if (len > 64L * 1024 * 1024) { // 64MB guard
                L.w("Frame too large: " + len); break;
            }
            byte[] payload = new byte[(int) len];
            int off = 0;
            while (off < payload.length) {
                int r = in.read(payload, off, payload.length - off);
                if (r < 0) return;
                off += r;
            }
            if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];

            switch (opcode) {
                case 0x0: // continuation
                    frag.write(payload);
                    if (fin) { deliver(conn, fragOpcode, frag.toByteArray()); frag.reset(); fragOpcode = 0; }
                    break;
                case 0x1: // text
                case 0x2: // binary
                    if (fin) { deliver(conn, opcode, payload); }
                    else { fragOpcode = opcode; frag.reset(); frag.write(payload); }
                    break;
                case 0x8: // close
                    conn.sendClose();
                    return;
                case 0x9: // ping
                    conn.sendPong(payload);
                    break;
                case 0xA: // pong
                    break;
                default:
                    break;
            }
        }
    }

    private void deliver(WsConn conn, int opcode, byte[] data) {
        if (opcode != 0x1) return; // only text carries OneBot JSON
        try {
            String text = new String(data, "UTF-8");
            handler.onText(conn, text);
        } catch (Throwable t) {
            L.e("handler error", t);
        }
    }

    private int readN(InputStream in) throws Exception {
        int v = in.read();
        if (v < 0) throw new java.io.EOFException();
        return v;
    }
}
