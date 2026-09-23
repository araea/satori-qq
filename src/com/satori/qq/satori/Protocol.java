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

    /** Resolve a poke destination without letting conflicting fields redirect an action. */
    public static long[] pokeTarget(JSONObject p) {
        String channel = p.optString("channel_id", "");
        long group = p.optLong("guild_id", p.optLong("group_id", 0));
        long user = p.optLong("user_id", 0);
        try {
            if (channel.startsWith("private:")) {
                long peer = Long.parseLong(channel.substring(8));
                if (group != 0 || (user != 0 && user != peer))
                    throw new IllegalArgumentException("conflicting poke destination");
                user = peer;
            } else if (!channel.isEmpty()) {
                long peer = Long.parseLong(channel);
                if (peer <= 0 || (group != 0 && group != peer))
                    throw new IllegalArgumentException("conflicting poke destination");
                group = peer;
            }
            if (user <= 0 || group < 0) throw new IllegalArgumentException("missing user_id or invalid guild_id");
            return new long[]{group, user};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid channel_id");
        }
    }

    /** Non-login events carry only the compact Login reference required by Satori v1. */
    public static JSONObject eventLogin(JSONObject login) throws Exception {
        if (login == null) return new JSONObject();
        return eventLogin(login.optLong("sn", 0), login.optString("platform", ""),
                login.optJSONObject("user"));
    }

    public static JSONObject eventLogin(long sn, String platform, JSONObject user) throws Exception {
        JSONObject out = new JSONObject().put("sn", sn).put("platform", platform == null ? "" : platform);
        if (user != null) {
            out.put("user", user);
            // `selfId` is deprecated in favour of `login.user.id`, but clients still read it.
            String id = user.optString("id", "");
            if (!id.isEmpty()) out.put("self_id", id);
        }
        return out;
    }
}
