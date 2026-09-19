package com.satori.qq.control;

import com.satori.qq.Cfg;
import org.json.JSONObject;

/** Deliberately small configuration surface; file-only advanced options remain untouched. */
public final class ManagedConfig {
    public static final String[] SWITCHES = {"status_notification", "wake_lock_auto", "wifi_sustain", "manual_self_messages"};
    private ManagedConfig() {}

    public static JSONObject validate(JSONObject raw) throws Exception {
        JSONObject clean = new JSONObject();
        Object portValue = raw.opt("port");
        if (!(portValue instanceof Number) || ((Number) portValue).doubleValue() != ((Number) portValue).intValue())
            throw new IllegalArgumentException("端口须为 1024–65535 的整数");
        int port = ((Number) portValue).intValue();
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("端口须为 1024–65535 的整数");
        clean.put("port", port);
        Object tokenValue = raw.opt("token");
        if (!(tokenValue instanceof String)) throw new IllegalArgumentException("请输入有效令牌");
        String token = (String) tokenValue;
        if (token.length() > 128) throw new IllegalArgumentException("令牌最多 128 个字符");
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < 33 || c > 126) throw new IllegalArgumentException("令牌请使用无空格的英文字母、数字或符号");
        }
        clean.put("token", token);
        for (String key : SWITCHES) {
            Object value = raw.opt(key);
            if (!(value instanceof Boolean)) throw new IllegalArgumentException("缺少设置：" + key);
            clean.put(key, value);
        }
        return clean;
    }

    public static void apply(Cfg cfg, JSONObject raw) throws Exception {
        JSONObject value = validate(raw); // Validate everything before mutating a live config.
        cfg.port = value.getInt("port");
        cfg.token = value.getString("token");
        cfg.statusNotification = value.getBoolean("status_notification");
        cfg.manualSelfMessages = value.getBoolean("manual_self_messages");
        cfg.wakeLockAuto = value.getBoolean("wake_lock_auto");
        cfg.wifiSustain = value.getBoolean("wifi_sustain");
    }

    public static JSONObject snapshot(Cfg cfg) throws Exception {
        return new JSONObject().put("port", cfg.port).put("token", cfg.token)
                .put("status_notification", cfg.statusNotification)
                .put("manual_self_messages", cfg.manualSelfMessages)
                .put("wake_lock_auto", cfg.wakeLockAuto).put("wifi_sustain", cfg.wifiSustain);
    }
}
