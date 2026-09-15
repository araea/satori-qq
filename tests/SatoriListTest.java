package com.satori.qq.core;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Offline checks for the Satori v1 list and mute semantics.
 *
 * <p>Both of these are client-shape questions, not QQ questions: the published spec and the
 * protocol table shipped to clients disagree about `channel.mute`, and pagination has to keep
 * behaving the old way for callers that never page. Neither needs a device to verify.</p>
 */
public final class SatoriListTest {
    public static void main(String[] args) throws Exception {
        unpagedKeepsWholeSet();
        pagedSlicesAndTokens();
        tokenWalksToTheEnd();
        blankTokenStartsAtZero();
        muteAcceptsBothShapes();
        System.out.println("SatoriListTest OK");
    }

    private static void unpagedKeepsWholeSet() throws Exception {
        JSONObject out = SatoriHub.pagedList(rows(5), new JSONObject(), 200);
        eq(5, out.getJSONArray("data").length(), "unpaged keeps every row");
        if (out.has("next")) throw new AssertionError("unpaged must not advertise next");
    }

    private static void pagedSlicesAndTokens() throws Exception {
        JSONObject out = SatoriHub.pagedList(rows(5), new JSONObject().put("limit", 2), 200);
        eq(2, out.getJSONArray("data").length(), "limit honoured");
        eq("2", out.getString("next"), "next token is the offset");
    }

    private static void tokenWalksToTheEnd() throws Exception {
        JSONObject out = SatoriHub.pagedList(rows(5), new JSONObject().put("next", "4"), 200);
        eq(1, out.getJSONArray("data").length(), "last page is short");
        if (out.has("next")) throw new AssertionError("last page must not advertise next");
    }

    private static void blankTokenStartsAtZero() throws Exception {
        JSONObject out = SatoriHub.pagedList(rows(5), new JSONObject().put("limit", 3), 200);
        eq(0, out.getJSONArray("data").optJSONObject(0).optInt("i"), "slice starts at zero");
    }

    private static void muteAcceptsBothShapes() throws Exception {
        long month = 30L * 24 * 3600 * 1000;
        eq(0L, SatoriHub.resolveChannelMuteMs(new JSONObject().put("duration", 0)),
                "duration 0 unmutes");
        eq(600_000L, SatoriHub.resolveChannelMuteMs(new JSONObject().put("duration", 600_000)),
                "duration is milliseconds");
        eq(0L, SatoriHub.resolveChannelMuteMs(new JSONObject().put("enable", false)),
                "enable false unmutes");
        eq(month, SatoriHub.resolveChannelMuteMs(new JSONObject().put("enable", true)),
                "enable true mutes until turned off");
        eq(month, SatoriHub.resolveChannelMuteMs(new JSONObject()),
                "bare mute uses the default window");
        eq(1000L, SatoriHub.resolveChannelMuteMs(
                        new JSONObject().put("duration", 1000).put("enable", false)),
                "explicit duration wins over enable");
    }

    private static JSONArray rows(int n) throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; i < n; i++) out.put(new JSONObject().put("i", i));
        return out;
    }

    private static void eq(long expected, long actual, String label) {
        if (expected != actual) throw new AssertionError(label + ": " + expected + " != " + actual);
    }

    private static void eq(int expected, int actual, String label) {
        if (expected != actual) throw new AssertionError(label + ": " + expected + " != " + actual);
    }

    private static void eq(String expected, String actual, String label) {
        if (!expected.equals(actual))
            throw new AssertionError(label + ": " + expected + " != " + actual);
    }
}
