package com.satori.qq.qq;

import java.lang.reflect.Method;
import java.util.Collections;
import org.json.JSONArray;

/** Incoming wallet elements are display-only: never forward payment tokens or URLs. */
public final class ConvertWalletTest {
    public static final class Aio {
        public String title = "群红包";
        public String jumpUrl = "https://private.example/?token=secret";
    }
    public static final class Wallet {
        public String name = "";
        public String authkey = "secret-auth";
        public String billNo = "secret-bill";
        public Aio sender = new Aio();
        public Aio receiver = new Aio();
    }
    public static final class Element {
        public int elementType = 12;
        public Wallet walletElement = new Wallet();
    }

    public static void main(String[] args) throws Exception {
        Convert convert = new Convert(new QQClient(ConvertWalletTest.class.getClassLoader(), false), null);
        Method parse = Convert.class.getDeclaredMethod("parseElements", Object.class, JSONArray.class,
                StringBuilder.class, int.class, String.class, long.class);
        parse.setAccessible(true);
        JSONArray segments = new JSONArray();
        StringBuilder raw = new StringBuilder();
        parse.invoke(convert, Collections.singletonList(new Element()), segments, raw, 2, "123", 1L);
        if (!"[红包]".contentEquals(raw) || segments.length() != 1
                || !"text".equals(segments.getJSONObject(0).getString("type"))
                || !"[红包]".equals(segments.getJSONObject(0).getJSONObject("data").getString("text"))) {
            throw new AssertionError("wallet element should become a passive text marker: " + segments);
        }
        if (segments.toString().contains("secret")) throw new AssertionError("wallet credentials leaked");
        System.out.println("ConvertWalletTest OK");
    }
}
