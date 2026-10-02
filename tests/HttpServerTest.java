import com.satori.qq.Cfg;
import com.satori.qq.net.HttpServer;
import com.satori.qq.net.WsConn;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * The wire behaviour the Satori docs ask of an HTTP surface, through the real socket server:
 * HEAD is GET without a body, every error is {"code","message"} JSON, 503 may carry Retry-After,
 * and an oversized body is refused up front with the limit in the message.
 */
public final class HttpServerTest {
    public static void main(String[] args) throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) { port = probe.getLocalPort(); }
        Cfg cfg = new Cfg();
        cfg.host = "127.0.0.1";
        cfg.port = port;
        HttpServer server = new HttpServer(cfg, new HttpServer.Handler() {
            @Override public HttpServer.HttpResult onHttp(HttpServer.HttpReq req) {
                if ("/hello".equals(req.path)) {
                    // HEAD must arrive as GET.
                    return "GET".equals(req.method) ? HttpServer.HttpResult.json(200, "{\"hi\":1}")
                            : HttpServer.HttpResult.error(405, "head not mapped to GET");
                }
                if ("/later".equals(req.path)) {
                    return HttpServer.HttpResult.error(503, "session_stabilizing", "wait",
                            Collections.singletonMap("Retry-After", "3"));
                }
                return null;
            }
            @Override public void onWsText(WsConn conn, String text) {}
        });
        server.start();
        long until = System.currentTimeMillis() + 5000;
        while (!server.isListening() && System.currentTimeMillis() < until) Thread.sleep(20);

        String head = raw(port, "HEAD /hello HTTP/1.1\r\nHost: x\r\n\r\n");
        check(head.startsWith("HTTP/1.1 200 OK"), "HEAD answers like GET: " + head);
        check(head.contains("Content-Length: 8\r\n"), "HEAD keeps the body length");
        check(head.endsWith("\r\n\r\n"), "HEAD writes no body: " + head);

        String later = raw(port, "POST /later HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n");
        check(later.startsWith("HTTP/1.1 503 Service Unavailable"), "503 reason phrase: " + later);
        check(later.contains("Retry-After: 3\r\n"), "Retry-After header");
        JSONObject laterBody = new JSONObject(later.substring(later.indexOf("\r\n\r\n") + 4));
        check("session_stabilizing".equals(laterBody.getString("code")) && laterBody.has("message"), "503 body has code and message");

        String missing = raw(port, "GET /nope HTTP/1.1\r\nHost: x\r\n\r\n");
        check(missing.startsWith("HTTP/1.1 404"), "unrouted is 404");
        check("not_found".equals(new JSONObject(missing.substring(missing.indexOf("\r\n\r\n") + 4)).getString("code")),
                "404 body is JSON with a default code");

        String big = raw(port, "POST /hello HTTP/1.1\r\nHost: x\r\nContent-Length: " + (HttpServer.MAX_BODY_BYTES + 1L) + "\r\n\r\n");
        check(big.startsWith("HTTP/1.1 413 Payload Too Large"), "oversized body is 413: " + big);
        JSONObject bigBody = new JSONObject(big.substring(big.indexOf("\r\n\r\n") + 4));
        check("payload_too_large".equals(bigBody.getString("code")), "413 code");
        check(bigBody.getString("message").contains(String.valueOf(HttpServer.MAX_UPLOAD_BYTES)), "413 names the file limit");

        System.out.println("HttpServerTest OK");
        System.exit(0);
    }

    /** One request, read until the server closes the connection. */
    private static String raw(int port, String request) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            s.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[4096];
            try {
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (java.net.SocketTimeoutException ignore) {}
            return out.toString("UTF-8");
        }
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
