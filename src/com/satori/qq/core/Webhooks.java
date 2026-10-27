package com.satori.qq.core;

import com.satori.qq.L;
import com.satori.qq.Paths;
import java.io.File;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Satori 的 WebHook 推送（元信息 API 的 {@code webhook.create} / {@code webhook.delete}）。
 *
 * <p>与官方服务端同形：每个事件原样 POST 给登记过的地址，头里带 {@code Satori-Opcode}（事件是 0）
 * 与登记时给的 {@code Authorization: Bearer <token>}。官方服务端把 WebHook 写进配置，这里写进
 * 模块数据目录的 {@code webhooks.json}：QQ 进程重启后接着推。
 *
 * <p>每个地址一条队列、一个发送线程，按事件顺序送达；对方慢或挂了只丢它自己的事件（队列满丢最旧），
 * 不拖住 WebSocket 那条通道。不重试：WebHook 没有游标，客户端要补事件就用 WebSocket 的 {@code sn}。
 */
final class Webhooks {
    static final int OP_EVENT = 0;
    private static final int QUEUE = 512;
    private static final int CONNECT_MS = 5_000;
    private static final int READ_MS = 10_000;
    private static final String FILE = "webhooks.json";

    private final Map<String, Hook> hooks = new ConcurrentHashMap<>();
    private final File store;

    Webhooks() {
        this(Paths.of(FILE));
    }

    Webhooks(File store) {
        this.store = store;
        load();
    }

    /** 登记一个地址；同一地址再登记只换令牌（官方服务端是忽略，换令牌对调用方更有用）。 */
    JSONObject create(JSONObject body) throws Exception {
        String url = body.optString("url", "").trim();
        checkUrl(url);
        String token = body.optString("token", "");
        Hook old = hooks.get(url);
        if (old != null && old.token.equals(token)) return new JSONObject();
        Hook hook = new Hook(url, token);
        Hook replaced = hooks.put(url, hook);
        if (replaced != null) replaced.stop();
        hook.start();
        save();
        L.i("webhook created: " + redact(url));
        return new JSONObject();
    }

    JSONObject delete(JSONObject body) throws Exception {
        String url = body.optString("url", "").trim();
        if (url.isEmpty()) throw ApiError.badRequest("missing url");
        Hook hook = hooks.remove(url);
        if (hook != null) {
            hook.stop();
            save();
            L.i("webhook deleted: " + redact(url));
        }
        return new JSONObject();
    }

    /** 给 healthz / meta 看的：地址、送达与丢弃计数，不含令牌。 */
    JSONArray describe() throws Exception {
        JSONArray out = new JSONArray();
        for (Hook h : hooks.values()) {
            out.put(new JSONObject().put("url", redact(h.url)).put("delivered", h.delivered)
                    .put("failed", h.failed).put("dropped", h.dropped).put("queued", h.queue.size())
                    .put("last_error", h.lastError));
        }
        return out;
    }

    boolean isEmpty() { return hooks.isEmpty(); }

    /** 推一帧。调用方已经拿到最终的事件体（带 sn）；这里只入队，不阻塞。 */
    void deliver(int opcode, String body) {
        for (Hook h : hooks.values()) h.offer(opcode, body);
    }

    static void checkUrl(String url) {
        if (url.isEmpty()) throw ApiError.badRequest("missing url");
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!scheme.equals("http") && !scheme.equals("https")) throw ApiError.badRequest("webhook url must be http(s)");
            if (uri.getHost() == null || uri.getHost().isEmpty()) throw ApiError.badRequest("webhook url has no host");
        } catch (URISyntaxException e) {
            throw ApiError.badRequest("invalid webhook url");
        }
    }

    /** 日志里不留查询串：有人把令牌放在 URL 里。 */
    static String redact(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q) + "?…";
    }

    private void load() {
        try {
            if (store == null || !store.isFile()) return;
            JSONArray list = new JSONArray(new String(Files.readAllBytes(store.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null || o.optString("url", "").isEmpty()) continue;
                Hook h = new Hook(o.optString("url"), o.optString("token", ""));
                hooks.put(h.url, h);
                h.start();
            }
        } catch (Throwable t) {
            L.e("webhooks load", t);
        }
    }

    private synchronized void save() {
        if (store == null) return;
        try {
            JSONArray list = new JSONArray();
            for (Hook h : hooks.values()) list.put(new JSONObject().put("url", h.url).put("token", h.token));
            File parent = store.getParentFile();
            if (parent != null) parent.mkdirs();
            File tmp = new File(store.getPath() + ".tmp");
            Files.write(tmp.toPath(), list.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(store)) throw new IllegalStateException("rename failed");
        } catch (Throwable t) {
            L.e("webhooks save", t);
        }
    }

    /** 一个地址的发送队列。 */
    private static final class Hook {
        final String url;
        final String token;
        final LinkedBlockingQueue<String[]> queue = new LinkedBlockingQueue<>(QUEUE);
        volatile boolean running = true;
        volatile long delivered;
        volatile long failed;
        volatile long dropped;
        volatile String lastError = "";
        private Thread thread;

        Hook(String url, String token) {
            this.url = url;
            this.token = token == null ? "" : token;
        }

        void start() {
            thread = Workers.start("pool-9-thread-" + (2 + Math.abs(url.hashCode() % 90)), this::loop);
        }

        void stop() {
            running = false;
            if (thread != null) thread.interrupt();
        }

        void offer(int opcode, String body) {
            String[] item = {String.valueOf(opcode), body};
            while (!queue.offer(item)) {
                if (queue.poll() != null) dropped++;
            }
        }

        private void loop() {
            while (running) {
                String[] item;
                try {
                    item = queue.take();
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    post(item[0], item[1]);
                    delivered++;
                } catch (Throwable t) {
                    failed++;
                    String why = t.getMessage() == null ? t.toString() : t.getMessage();
                    // 对方长时间挂着时每个事件都会失败一次，日志只记换了说法的那一条。
                    if (!why.equals(lastError)) L.i("webhook " + redact(url) + ": " + why);
                    lastError = why;
                }
            }
        }

        private void post(String opcode, String body) throws Exception {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_MS);
            conn.setReadTimeout(READ_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Satori-Opcode", opcode);
            if (!token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
            int status = conn.getResponseCode();
            try {
                (status >= 400 ? conn.getErrorStream() : conn.getInputStream()).close();
            } catch (Throwable ignore) {
                // 没有回包体
            }
            conn.disconnect();
            if (status >= 300) throw new IllegalStateException("http " + status);
        }
    }

    /** 测试用：当前登记的地址。 */
    List<String> urls() {
        return new ArrayList<>(hooks.keySet());
    }
}
