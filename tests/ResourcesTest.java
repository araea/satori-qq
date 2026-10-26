package com.satori.qq.core;

import com.satori.qq.qq.QQClient;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Received images can use their fresh URL immediately, then survive without the URL server. */
public final class ResourcesTest {
    public static void main(String[] args) throws Exception {
        File scratch = Files.createTempDirectory("satori-resources").toFile();
        System.setProperty("satori.qq.media_tmp", scratch.getAbsolutePath());
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 1, 2, 3};
        AtomicInteger hits = new AtomicInteger();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/image", exchange -> {
            hits.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, png.length);
            exchange.getResponseBody().write(png);
            exchange.close();
        });
        http.start();
        try {
            String url = "http://127.0.0.1:" + http.getAddress().getPort() + "/image";
            QQClient qq = new QQClient(ResourcesTest.class.getClassLoader(), false);
            MsgStore store = new MsgStore();
            store.putResource("image", "picture", "", url, "picture.png", png.length);
            Resources resources = new Resources(qq, store, new Identity(qq));

            JSONObject first = resources.lookup(new JSONObject().put("file", "picture"), "image");
            File downloaded = new File(first.getString("file"));
            check(downloaded.isFile(), "URL image is materialized");
            check(Arrays.equals(png, Files.readAllBytes(downloaded.toPath())), "downloaded bytes");
            check(first.getLong("file_size") == png.length, "downloaded size");

            // The source disappearing must not invalidate the resource: its local path is remembered.
            http.stop(0);
            JSONObject cached = resources.lookup(new JSONObject().put("file", "picture"), "image");
            check(downloaded.getAbsolutePath().equals(cached.getString("file")), "local path is cached");
            check(hits.get() == 1, "cached resource does not download again");
            System.out.println("ResourcesTest OK");
        } finally {
            http.stop(0);
            File[] files = scratch.listFiles();
            if (files != null) for (File file : files) file.delete();
            scratch.delete();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
