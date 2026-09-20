package com.satori.qq.net;

import java.io.OutputStream;
import java.net.Socket;

/** One WebSocket connection; server->client frames are unmasked. */
public final class WsConn {
    private final Socket socket;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private volatile boolean closed;
    // Liveness for the server-side heartbeat: any inbound frame (data or pong) proves the peer is
    // still there, so the reaper can drop half-open sockets that TCP alone will not report.
    private volatile long lastInboundMs = System.currentTimeMillis();
    private volatile long lastPingMs;

    WsConn(Socket socket, OutputStream out) { this.socket = socket; this.out = out; }

    /** Called by the frame reader for every inbound frame, cheap and lock-free. */
    void noteInbound() { lastInboundMs = System.currentTimeMillis(); }

    public long lastInboundMs() { return lastInboundMs; }

    public long lastPingMs() { return lastPingMs; }

    /** Server-initiated WebSocket ping. Clients answer with a pong, which is what marks them live. */
    public void sendPing() {
        try { lastPingMs = System.currentTimeMillis(); writeFrame(0x9, new byte[0]); }
        catch (Throwable ignore) { close(); }
    }

    public void send(String text) {
        try {
            byte[] payload = text.getBytes("UTF-8");
            writeFrame(0x1, payload);
        } catch (Throwable t) {
            close();
        }
    }

    void sendPong(byte[] payload) { try { writeFrame(0xA, payload); } catch (Throwable ignore) {} }
    public void sendClose() { try { writeFrame(0x8, new byte[0]); } catch (Throwable ignore) {} finally { close(); } }

    private void writeFrame(int opcode, byte[] payload) throws Exception {
        if (closed) return;
        synchronized (writeLock) {
            int len = payload.length;
            java.io.ByteArrayOutputStream h = new java.io.ByteArrayOutputStream();
            h.write(0x80 | (opcode & 0x0F)); // FIN + opcode
            if (len < 126) {
                h.write(len);
            } else if (len < 65536) {
                h.write(126);
                h.write((len >>> 8) & 0xFF);
                h.write(len & 0xFF);
            } else {
                h.write(127);
                for (int i = 7; i >= 0; i--) h.write((int) (((long) len >>> (8 * i)) & 0xFF));
            }
            out.write(h.toByteArray());
            out.write(payload);
            out.flush();
        }
    }

    public void close() {
        closed = true;
        try { socket.close(); } catch (Throwable ignore) {}
    }
}
