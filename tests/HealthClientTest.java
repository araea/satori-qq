import com.satori.qq.ui.HealthClient;
import org.json.JSONObject;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class HealthClientTest {
    public static void main(String[] args) throws Exception {
        JSONObject value = new JSONObject().put("name", "satori-qq").put("version", "0.11.0")
                .put("online", false).put("listening", true).put("token", "SECRET_TOKEN")
                .put("self_id", "PRIVATE_ID").put("last_kick", "PRIVATE_KICK");
        JSONObject read = serve(503, value.toString());
        if (read.optBoolean("online") || !read.optBoolean("listening")) throw new AssertionError("offline health not parsed");
        String report = HealthClient.report(read, "0.11.0", 3001, 1000);
        for (String privateValue : new String[]{"SECRET_TOKEN", "PRIVATE_ID", "PRIVATE_KICK"})
            if (report.contains(privateValue)) throw new AssertionError("report leaks private fields");
        try { serve(200, "{\"name\":\"other-server\"}"); throw new AssertionError("foreign server accepted"); }
        catch (IllegalStateException expected) {}
        try { serve(302, "redirect"); throw new AssertionError("redirect followed"); }
        catch (IllegalStateException expected) {}
        if (!HealthClient.report(null, "0.11.0", 3001, 0).contains("未能连接")) throw new AssertionError("disconnected report");
        System.out.println("HealthClientTest passed");
    }
    private static JSONObject serve(int status, String response) throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            Thread thread = new Thread(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(3000);
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String line; while ((line = reader.readLine()) != null && !line.isEmpty()) {}
                    byte[] body = response.getBytes(StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("HTTP/1.1 " + status + " Result\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().write(body);
                } catch (Exception e) { throw new RuntimeException(e); }
            }); thread.start();
            try { return HealthClient.read(listener.getLocalPort()); } finally { thread.join(5000); }
        }
    }
}
