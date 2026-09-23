import com.satori.qq.Cfg;
import com.satori.qq.core.MsgStore;
import com.satori.qq.core.SatoriHub;
import com.satori.qq.net.WsConn;
import com.satori.qq.qq.QQClient;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;

/** Exercises the real hub publication/replay path without loading QQ or Android. */
public final class HubDeliveryTest {
    static Object call(Object target, String method, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }
    static WsConn connection(ByteArrayOutputStream out) throws Exception {
        Constructor<WsConn> c = WsConn.class.getDeclaredConstructor(Socket.class, OutputStream.class);
        c.setAccessible(true);
        return c.newInstance(new Socket(), out);
    }
    static List<JSONObject> packets(ByteArrayOutputStream out) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
        List<JSONObject> packets = new ArrayList<>();
        while (in.available() > 0) {
            in.readUnsignedByte();
            long length = in.readUnsignedByte();
            if (length == 126) length = in.readUnsignedShort();
            else if (length == 127) length = in.readLong();
            byte[] body = new byte[(int)length];
            in.readFully(body);
            packets.add(new JSONObject(new String(body, "UTF-8")));
        }
        return packets;
    }
    public static void main(String[] args) throws Exception {
        QQClient qq = new QQClient(HubDeliveryTest.class.getClassLoader(), false);
        Field self = QQClient.class.getDeclaredField("selfUin");
        self.setAccessible(true); self.set(qq, "10001");
        Cfg cfg = new Cfg(); cfg.token = "";
        SatoriHub hub = new SatoriHub(cfg, qq, new MsgStore());
        ByteArrayOutputStream live = new ByteArrayOutputStream();
        WsConn conn = connection(live);
        hub.onWsText(conn, "{\"op\":3}");
        List<JSONObject> initial = packets(live);
        check(initial.size() == 1 && initial.get(0).getInt("op") == 4, "READY first");
        check(!initial.get(0).getJSONObject("body").getJSONObject("satori_qq").getString("session_id").isEmpty(), "session identity");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> work = new ArrayList<>();
        for (int n = 0; n < 4; n++) work.add(pool.submit(() -> {
            try {
                for (int i = 0; i < 50; i++) call(hub, "emitSatoriEvent", new Class[]{JSONObject.class},
                        new JSONObject().put("sn", 0).put("type", "internal"));
            } catch (Exception e) { throw new RuntimeException(e); }
        }));
        for (Future<?> f : work) f.get();
        pool.shutdown();
        List<JSONObject> all = packets(live);
        check(all.size() == 201, "all events delivered once");
        long sn = 0;
        for (int i = 1; i < all.size(); i++) {
            long next = all.get(i).getJSONObject("body").getLong("sn");
            check(next > sn, "concurrent delivery stays ordered"); sn = next;
        }
        long cursor = all.get(100).getJSONObject("body").getLong("sn");
        ByteArrayOutputStream replay = new ByteArrayOutputStream();
        WsConn resumed = connection(replay);
        hub.onWsText(resumed, new JSONObject().put("op",3).put("body",new JSONObject().put("sn",cursor)).toString());
        List<JSONObject> history = packets(replay);
        check(history.size() == 101 && history.get(0).getInt("op") == 4, "READY then exact missing history");
        for (int i=1; i<history.size(); i++) check(history.get(i).getJSONObject("body").getLong("sn") == all.get(i+100).getJSONObject("body").getLong("sn"), "original replay sequence");
        hub.onWsText(resumed, "{\"op\":3}");
        check(packets(replay).size() == 101, "repeated identify cannot replay again");
        // Pending requests have a connection-local event but consume the same global sn.
        // A broadcast immediately after it must be newer; otherwise the client's
        // monotonic cursor would discard that broadcast.
        JSONObject pending = com.satori.qq.core.Notices.friendRequest(10001, 10, 20002, "hi", "123");
        call(hub, "sendSatori", new Class[]{WsConn.class, JSONObject.class}, resumed, pending);
        call(hub, "emitSatoriEvent", new Class[]{JSONObject.class},
                new JSONObject().put("sn", 0).put("type", "internal"));
        List<JSONObject> afterPending = packets(replay);
        check(afterPending.size() == 103, "pending request and broadcast delivered");
        check(afterPending.get(102).getJSONObject("body").getLong("sn") >
                afterPending.get(101).getJSONObject("body").getLong("sn"), "pending request precedes broadcast");
        call(hub, "emitLoginUpdated", new Class[]{});
        ByteArrayOutputStream noLoginReplay = new ByteArrayOutputStream();
        hub.onWsText(connection(noLoginReplay), new JSONObject().put("op",3).put("body",new JSONObject().put("sn",
                afterPending.get(102).getJSONObject("body").getLong("sn"))).toString());
        check(packets(noLoginReplay).size() == 1, "login events never replay");
        try {
            call(hub, "dispatch", new Class[]{String.class, JSONObject.class}, "reaction.clear", new JSONObject());
            throw new AssertionError("standard clear must not mean self-only");
        } catch (InvocationTargetException e) { check(e.getCause().getMessage().contains("reaction.clear"), "clear unsupported"); }
        System.out.println("HubDeliveryTest OK");
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
