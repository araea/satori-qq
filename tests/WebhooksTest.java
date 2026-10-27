package com.satori.qq.core;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/**
 * WebHook 推送：与官方服务端同形（POST 事件体、Satori-Opcode、Bearer 令牌），登记写盘、重启后接着推。
 */
public final class WebhooksTest {
    public static void main(String[] args) throws Exception {
        LinkedBlockingQueue<String[]> got = new LinkedBlockingQueue<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            got.add(new String[]{ex.getRequestHeaders().getFirst("Satori-Opcode"),
                    ex.getRequestHeaders().getFirst("Authorization"), body});
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
        File store = File.createTempFile("webhooks", ".json");
        store.delete();
        try {
            Webhooks hooks = new Webhooks(store);
            hooks.create(new JSONObject().put("url", url).put("token", "t0k"));
            check(store.isFile(), "登记要写盘");
            hooks.deliver(Webhooks.OP_EVENT, "{\"sn\":1,\"type\":\"message-created\"}");
            String[] one = got.poll(5, TimeUnit.SECONDS);
            check(one != null, "事件要送到");
            check("0".equals(one[0]), "Satori-Opcode 是 0：" + one[0]);
            check("Bearer t0k".equals(one[1]), "带登记时的令牌");
            check(new JSONObject(one[2]).optInt("sn") == 1, "事件体原样");

            // 进程重启：从盘上读回同一个地址。
            Webhooks again = new Webhooks(store);
            check(again.urls().contains(url), "重启后接着推");
            again.delete(new JSONObject().put("url", url));
            hooks.delete(new JSONObject().put("url", url));
            check(new String(Files.readAllBytes(store.toPath()), StandardCharsets.UTF_8).equals("[]"), "删除也写盘");

            try {
                hooks.create(new JSONObject().put("url", "ftp://x"));
                throw new AssertionError("非 http(s) 地址要拒");
            } catch (ApiError expected) {
                check(expected.http() == 400, "400");
            }
        } finally {
            server.stop(0);
            store.delete();
        }
        System.out.println("WebhooksTest OK");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
