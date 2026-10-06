import com.satori.qq.Cfg;
import com.satori.qq.net.HttpServer;
import com.satori.qq.net.WsConn;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import org.json.JSONObject;

/**
 * The wire behaviour the Satori docs ask of an HTTP surface, through the real socket server:
 * HEAD is GET without a body, every error is {"code","message"} JSON, 503 may carry Retry-After,
 * and an oversized body is refused up front with the limit in the message.
 */
public final class HttpServerTest {
    public static void main(String[] args) throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) { port = probe.getLocalPort(); }
        File payload = File.createTempFile("http-test", ".bin");
        payload.deleteOnExit();
        byte[] bytes = new byte[1000];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        Files.write(payload.toPath(), bytes);
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
                if ("/file".equals(req.path)) {
                    return HttpServer.HttpResult.file(206, "application/octet-stream", payload, 10, 20,
                            Collections.singletonMap("Content-Range", "bytes 10-29/1000"));
                }
                if ("/sink".equals(req.path)) {
                    // Reads a few bytes of a streamed body and answers; the server must discard the rest.
                    try {
                        byte[] first = new byte[10];
                        int n = req.stream.read(first);
                        return HttpServer.HttpResult.json(200, "{\"read\":" + n + ",\"declared\":" + req.length + "}");
                    } catch (Exception e) { return HttpServer.HttpResult.error(500, e.toString()); }
                }
                if ("/later".equals(req.path)) {
                    return HttpServer.HttpResult.error(503, "session_stabilizing", "wait",
                            Collections.singletonMap("Retry-After", "3"));
                }
                return null;
            }
            @Override public boolean streams(String method, String path) { return "/sink".equals(path); }
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

        // 正文来自文件的一段：长度是这一段的，内容也是。
        String file = raw(port, "GET /file HTTP/1.1\r\nHost: x\r\n\r\n");
        check(file.startsWith("HTTP/1.1 206 Partial Content"), "file result keeps its status: " + file);
        check(file.contains("Content-Length: 20\r\n") && file.contains("Content-Range: bytes 10-29/1000\r\n"), "file range headers");
        byte[] fileBytes = file.substring(file.indexOf("\r\n\r\n") + 4).getBytes(StandardCharsets.ISO_8859_1);
        check(fileBytes.length == 20, "20 bytes of body: " + fileBytes.length);

        // 流式路由：处理方只读一点点，剩下的由服务器丢掉，应答照样送达（不是 RST）。
        int sent = 3 * 1024 * 1024;
        String sink = rawWithBody(port, "POST /sink HTTP/1.1\r\nHost: x\r\nContent-Length: " + sent + "\r\n\r\n", sent);
        check(sink.startsWith("HTTP/1.1 200 OK") && sink.contains("\"declared\":" + sent), "streamed route answers: " + sink);

        // 声明超限：先应答 413，再把客户端还在发的请求体丢掉，客户端读得到这条 413。
        String early = rawWithBody(port, "POST /hello HTTP/1.1\r\nHost: x\r\nContent-Length: " + (HttpServer.MAX_BODY_BYTES + 1L)
                + "\r\n\r\n", 4 * 1024 * 1024);
        check(early.startsWith("HTTP/1.1 413 Payload Too Large"), "early 413 reaches a client that is still sending: " + early.length());

        // Expect: 100-continue 先收到 100，再发请求体。
        String cont = raw(port, "POST /hello HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\nExpect: 100-continue\r\n\r\n{}");
        check(cont.startsWith("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 "), "100 Continue first: " + cont);

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
            } catch (SocketTimeoutException ignore) {}
            return out.toString("UTF-8");
        }
    }

    /** Send the headers, then {@code bodyBytes} of body while the server may already be answering. */
    private static String rawWithBody(int port, String head, int bodyBytes) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(10000);
            OutputStream out = s.getOutputStream();
            out.write(head.getBytes(StandardCharsets.UTF_8));
            byte[] chunk = new byte[64 * 1024];
            for (int sent = 0; sent < bodyBytes; sent += chunk.length) out.write(chunk, 0, Math.min(chunk.length, bodyBytes - sent));
            out.flush();
            s.shutdownOutput();
            ByteArrayOutputStream got = new ByteArrayOutputStream();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[4096];
            try {
                int n;
                while ((n = in.read(buf)) > 0) got.write(buf, 0, n);
            } catch (SocketTimeoutException ignore) {}
            return got.toString("ISO-8859-1");
        }
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
