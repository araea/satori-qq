import com.satori.qq.Cfg;
import com.satori.qq.control.ManagedConfig;
import org.json.JSONObject;

public final class ManagedConfigTest {
    public static void main(String[] args) throws Exception {
        Cfg cfg = new Cfg();
        JSONObject input = ManagedConfig.snapshot(cfg);
        input.put("port", 4321).put("token", "secret-not-logged").put("wifi_sustain", false).put("media_retry_attempts", 4);
        ManagedConfig.apply(cfg, input);
        check(cfg.port == 4321 && cfg.token.equals("secret-not-logged"), "managed connection applied");
        check(cfg.mediaRetryAttempts == 2 && !cfg.wifiSustain, "advanced file settings preserved");
        for (Object port : new Object[]{0, 1023, 65536, 3001.5, "3001"}) {
            JSONObject invalid = new JSONObject(input.toString()).put("port", port);
            reject(invalid); check(cfg.port == 4321, "invalid config does not mutate original");
        }
        for (String token : new String[]{"a b", "a\nb", "令牌", new String(new char[129]).replace('\0', 'a')})
            reject(new JSONObject(input.toString()).put("token", token));
        reject(new JSONObject(input.toString()).put("wifi_sustain", "false"));
        try {
            ManagedConfig.apply(cfg, new JSONObject(input.toString()).put("port", 5000).put("wifi_sustain", "invalid"));
            throw new AssertionError("partial invalid configuration accepted");
        } catch (IllegalArgumentException expected) {}
        check(cfg.port == 4321 && cfg.token.equals("secret-not-logged"), "validation is atomic");
        JSONObject clean = ManagedConfig.validate(new JSONObject(input.toString()).put("port", 65535).put("token", ""));
        check(!clean.has("media_retry_attempts"), "unknown settings stripped");
        check(clean.getString("token").isEmpty(), "empty legacy token remains valid");
        System.out.println("ManagedConfigTest passed");
    }
    private static void reject(JSONObject input) throws Exception {
        try { ManagedConfig.validate(input); throw new AssertionError("invalid config accepted"); }
        catch (IllegalArgumentException expected) {}
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
