import com.satori.qq.Cfg;
import com.satori.qq.core.MsgStore;
import com.satori.qq.core.SatoriHub;
import com.satori.qq.net.HttpServer;
import com.satori.qq.net.WsConn;
import com.satori.qq.qq.QQClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The HTTP status table and event shape Satori prescribes, exercised through the real hub without
 * QQ: api.md's status codes, resource.md's proxy route, events.md's IDENTIFY close code and the
 * resource promotion rule of the protocol overview.
 */
public final class HubHttpTest {
    static SatoriHub hub;

    static HttpServer.HttpResult http(String method, String path, String token, String... headerPairs) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (token != null) headers.put("authorization", "Bearer " + token);
        for (int i = 0; i + 1 < headerPairs.length; i += 2) headers.put(headerPairs[i], headerPairs[i + 1]);
        return hub.onHttp(new HttpServer.HttpReq(method, path, "", headers, "{}".getBytes()));
    }

    static String body(HttpServer.HttpResult r) throws Exception { return new String(r.body, "UTF-8"); }

    public static void main(String[] args) throws Exception {
        QQClient qq = new QQClient(HttpServer.class.getClassLoader(), false);
        Field self = QQClient.class.getDeclaredField("selfUin");
        self.setAccessible(true); self.set(qq, "10001");
        Cfg cfg = new Cfg(); cfg.token = "s3cret";
        hub = new SatoriHub(cfg, qq, new MsgStore());

        // api.md 状态码：缺失鉴权 401，令牌不对 403；错误体是 JSON。
        HttpServer.HttpResult missing = http("POST", "/v1/meta", null);
        check(missing.status == 401, "missing token is 401, got " + missing.status);
        check(new JSONObject(body(missing)).has("message"), "error bodies are JSON");
        check("missing_token".equals(new JSONObject(body(missing)).optString("code")), "error bodies carry a machine-readable code");
        check(http("POST", "/v1/meta", "wrong").status == 403, "wrong token is 403");
        check("invalid_token".equals(new JSONObject(body(http("POST", "/v1/meta", "wrong"))).optString("code")), "invalid_token code");
        check(http("POST", "/v1/meta", "s3cret").status == 200, "right token passes");

        // 未知的登录（Satori-User-ID / Satori-Platform）：官方服务端答 403，不是 404。
        check(http("POST", "/v1/login.get", "s3cret", "satori-user-id", "99999").status == 403, "foreign user is 403");
        check(http("POST", "/v1/login.get", "s3cret", "satori-platform", "other").status == 403, "foreign platform is 403");
        // 标准方法在平台上不存在：404，不是 501。
        HttpServer.HttpResult unsupported = http("POST", "/v1/reaction.clear", "s3cret");
        check(unsupported.status == 404, "unsupported standard method is 404, got " + unsupported.status + " " + body(unsupported));
        check("unsupported_method".equals(new JSONObject(body(unsupported)).optString("code")), "unsupported method code");
        check(http("GET", "/v1/message.create", "s3cret").status == 405, "GET on an RPC route is 405");
        // QQ 内核没上线：503（不是 500），且没有可承诺的恢复时间，所以不带 Retry-After。
        HttpServer.HttpResult offline = http("POST", "/v1/message.get", "s3cret");
        check(offline.status == 503, "kernel offline is 503, got " + offline.status + " " + body(offline));
        check("kernel_offline".equals(new JSONObject(body(offline)).optString("code")), "kernel_offline code");
        check(offline.extraHeaders == null || !offline.extraHeaders.containsKey("Retry-After"), "no promise, no Retry-After");
        // 能力声明与 login.features 同源：标准方法一一对应，限额写明。
        JSONObject caps = new JSONObject(body(http("POST", "/v1/internal/capabilities", "s3cret")));
        check("satori-qq".equals(caps.optString("adapter")) && "red".equals(caps.optString("platform")), "capabilities names the adapter");
        check(caps.getJSONArray("standard_methods").length() > 20, "capabilities lists the standard methods");
        check(caps.getJSONObject("limits").getLong("upload_bytes") == HttpServer.MAX_UPLOAD_BYTES, "capabilities states the upload limit");
        check(caps.getJSONArray("event_types").length() > 5 && caps.getJSONArray("message_elements").length() > 5, "capabilities lists events and elements");

        // resource.md 代理路由：不需要任何头。非法 URL 400；内部链接格式不对 400；登录不存在 404；
        // 不在 proxy_urls 里的合法外链 403。
        check(http("GET", "/v1/proxy/not%20a%20url", null).status == 400, "invalid url is 400");
        check(http("GET", "/v1/proxy/internal:bad", null).status == 400, "malformed internal url is 400");
        check(http("GET", "/v1/proxy/internal:red/10001/", null).status == 400, "empty internal path is 400");
        check(http("GET", "/v1/proxy/internal:red/20002/_tmp/x", null).status == 404, "unknown login is 404");
        check(http("GET", "/v1/proxy/internal:other/10001/_tmp/x", null).status == 404, "unknown platform is 404");
        check(http("GET", "/v1/proxy/internal:red/10001/_tmp/nope", null).status == 404, "unknown resource is 404");
        check(http("GET", "/v1/proxy/https://example.com/a.png", null).status == 403, "unlisted link is 403");
        check(http("GET", "/v1/proxy/https:///nohost", null).status == 400, "hostless link is 400");

        // events.md：IDENTIFY 的令牌不对，用 4004 关闭。
        ByteArrayOutputStream wrong = new ByteArrayOutputStream();
        hub.onWsText(HubDeliveryTest.connection(wrong),
                new JSONObject().put("op", 3).put("body", new JSONObject().put("token", "nope")).toString());
        byte[] frame = wrong.toByteArray();
        check(frame.length == 4 && (frame[0] & 0x0F) == 8, "a close frame was written");
        check((((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF)) == 4004, "close code is 4004");

        // 推送的事件遵守资源提升：message 里没有 channel/guild/user/member，member 里没有 user。
        ByteArrayOutputStream live = new ByteArrayOutputStream();
        WsConn conn = HubDeliveryTest.connection(live);
        hub.onWsText(conn, new JSONObject().put("op", 3).put("body", new JSONObject().put("token", "s3cret")).toString());
        JSONObject ob = new JSONObject()
                .put("post_type", "message").put("message_type", "group")
                .put("group_id", 42L).put("group_name", "测试群")
                .put("message_id", "7000000000000000001").put("qq_msg_id", 7000000000000000001L)
                .put("time", 1000)
                .put("sender", new JSONObject().put("user_id", 7L).put("nickname", "小七").put("card", "七").put("role", "member"))
                .put("message", new JSONArray().put(new JSONObject().put("type", "text")
                        .put("data", new JSONObject().put("text", "你好"))));
        HubDeliveryTest.call(hub, "emitObEvent", new Class[]{JSONObject.class}, ob);
        List<JSONObject> packets = HubDeliveryTest.packets(live);
        check(packets.size() == 2, "READY then the event");
        JSONObject event = packets.get(1).getJSONObject("body");
        check("message-created".equals(event.getString("type")), "message-created");
        for (String key : new String[]{"channel", "guild", "user", "member"}) {
            check(event.has(key), "event has " + key);
            check(!event.getJSONObject("message").has(key), "message has no " + key);
        }
        check(!event.getJSONObject("member").has("user"), "member has no user");
        check("你好".equals(event.getJSONObject("message").getString("content")), "content kept");
        check(event.getJSONObject("message").getLong("created_at") == 1000000L, "created_at in ms");
        // 群名片归成员：user 是这个人（QQ 昵称），member.nick 才是这个群里的叫法。
        check("小七".equals(event.getJSONObject("user").optString("nick")), "user.nick is the QQ nickname, not the card");
        check("七".equals(event.getJSONObject("member").optString("nick")), "member.nick is the group card");
        // 收到的媒体是 internal: 链接，不带本机地址。
        JSONObject media = new JSONObject()
                .put("post_type", "message").put("message_type", "group").put("group_id", 42L)
                .put("message_id", "7000000000000000002").put("qq_msg_id", 7000000000000000002L).put("time", 1001)
                .put("sender", new JSONObject().put("user_id", 7L).put("nickname", "小七").put("role", "member"))
                .put("message", new JSONArray().put(new JSONObject().put("type", "image")
                        .put("data", new JSONObject().put("file", "abc123.image").put("url", "https://gchat.qpic.cn/x"))));
        HubDeliveryTest.call(hub, "emitObEvent", new Class[]{JSONObject.class}, media);
        String content = HubDeliveryTest.packets(live).get(2).getJSONObject("body").getJSONObject("message").getString("content");
        check(content.contains("src=\"internal:red/10001/_tmp/abc123.image\""), "received media is an internal: link: " + content);
        check(!content.contains("127.0.0.1") && !content.contains("/v1/assets/"), "no address baked into the link");

        uploadsStreamAndServeWithRanges();
        System.out.println("HubHttpTest OK");
    }

    /** upload.create 流到磁盘、回 internal: 链接；/v1/proxy 按块回读，支持 Range 与 HEAD。 */
    static void uploadsStreamAndServeWithRanges() throws Exception {
        java.io.File scratch = java.nio.file.Files.createTempDirectory("hub-upload").toFile();
        System.setProperty("satori.qq.media_tmp", scratch.getAbsolutePath());
        byte[] data = new byte[700_000];
        new java.util.Random(11).nextBytes(data);
        data[0] = (byte) 0xff; data[1] = (byte) 0xd8; // looks like a JPEG, so the extension is guessed
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.write(("--B\r\nContent-Disposition: form-data; name=\"pic\"\r\nContent-Type: image/jpeg\r\n\r\n").getBytes());
        raw.write(data);
        raw.write("\r\n--B--\r\n".getBytes());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("authorization", "Bearer s3cret");
        headers.put("content-type", "multipart/form-data; boundary=B");
        HttpServer.HttpResult up = hub.onHttp(new HttpServer.HttpReq("POST", "/v1/upload.create", "", headers, new byte[0],
                new java.io.ByteArrayInputStream(raw.toByteArray()), raw.size()));
        check(up.status == 200, "streamed upload is 200: " + up.status + " " + new String(up.body, "UTF-8"));
        String link = new JSONObject(new String(up.body, "UTF-8")).getString("pic");
        check(link.startsWith("internal:red/10001/_tmp/"), "upload answers an internal: link: " + link);

        HttpServer.HttpResult whole = http("GET", "/v1/proxy/" + link, null);
        check(whole.status == 200 && whole.file != null && whole.fileLength == data.length, "proxy serves the stored file: " + whole.status);
        check("image/jpeg".equals(whole.contentType), "type sniffed from the bytes: " + whole.contentType);
        check("bytes".equals(whole.extraHeaders.get("Accept-Ranges")), "ranges are advertised");

        HttpServer.HttpResult part = http("GET", "/v1/proxy/" + link, null, "range", "bytes=100-199");
        check(part.status == 206 && part.fileOffset == 100 && part.fileLength == 100, "range is 206");
        check(("bytes 100-199/" + data.length).equals(part.extraHeaders.get("Content-Range")), "Content-Range");
        HttpServer.HttpResult tail = http("GET", "/v1/proxy/" + link, null, "range", "bytes=-50");
        check(tail.status == 206 && tail.fileOffset == data.length - 50 && tail.fileLength == 50, "suffix range");
        HttpServer.HttpResult open = http("GET", "/v1/proxy/" + link, null, "range", "bytes=" + (data.length - 10) + "-");
        check(open.status == 206 && open.fileLength == 10, "open-ended range");
        HttpServer.HttpResult past = http("GET", "/v1/proxy/" + link, null, "range", "bytes=" + data.length + "-");
        check(past.status == 416 && ("bytes */" + data.length).equals(past.extraHeaders.get("Content-Range")), "range past the end is 416");
        check(http("GET", "/v1/proxy/" + link, null, "range", "bytes=0-1,5-6").status == 200, "a range list is ignored, whole body");

        // 超过单个文件上限：413，不留残片。
        byte[] before = null;
        HttpServer.HttpResult bad = hub.onHttp(new HttpServer.HttpReq("POST", "/v1/upload.create", "", headers, new byte[0],
                new java.io.ByteArrayInputStream("--B\r\nnot a part".getBytes()), 16));
        check(bad.status == 400 && "invalid_request".equals(new JSONObject(new String(bad.body, "UTF-8")).optString("code")),
                "malformed multipart is 400: " + bad.status);
        String[] files = scratch.list();
        check(files != null && files.length == 1, "only the adopted upload remains on disk: " + java.util.Arrays.toString(files));
    }

    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
