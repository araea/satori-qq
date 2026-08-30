package com.satori.qq.satori;

import org.json.JSONObject;

/** Small Satori wire-format helpers shared by the HTTP and event layers. */
public final class Protocol {
    private Protocol() {}

    /** A missing IDENTIFY sn starts a fresh session; an explicit zero requests replay from zero. */
    public static boolean shouldReplay(boolean hasSn, boolean nullSn) {
        return hasSn && !nullSn;
    }

    public static boolean shouldReplay(JSONObject identifyBody) {
        return identifyBody != null
                && shouldReplay(identifyBody.has("sn"), identifyBody.isNull("sn"));
    }

    /** Non-login events carry only the compact Login reference required by Satori v1. */
    public static JSONObject eventLogin(JSONObject login) throws Exception {
        if (login == null) return new JSONObject();
        return eventLogin(login.optLong("sn", 0), login.optString("platform", ""),
                login.optJSONObject("user"));
    }

    public static JSONObject eventLogin(long sn, String platform, JSONObject user) throws Exception {
        JSONObject out = new JSONObject().put("sn", sn).put("platform", platform == null ? "" : platform);
        if (user != null) out.put("user", user);
        return out;
    }
}
