package com.satori.qq.core;

import com.satori.qq.satori.Elements;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Bounded, fail-closed selection of a QQ history window. Never return a partial range. */
public final class ShotRange {
    public static final int MAX_MESSAGES = 40;
    private final String start, end;
    private final HashSet<String> ids = new HashSet<>();
    private final ArrayList<JSONObject> messages = new ArrayList<>();
    private boolean started, finished;
    private long previousSeq;

    public ShotRange(String start, String end) {
        if (start == null || start.isEmpty() || end == null || end.isEmpty())
            throw new IllegalArgumentException("missing range endpoint");
        this.start = start;
        this.end = end;
    }

    public boolean finished() { return finished; }
    public List<JSONObject> messages() {
        if (!finished) throw new IllegalStateException("range incomplete");
        return new ArrayList<>(messages);
    }

    /** Page must be in chronological order, from message.list(direction=after, order=asc). */
    public void add(JSONArray page) {
        if (finished) throw new IllegalStateException("range already complete");
        if (page == null || page.length() == 0) throw new IllegalArgumentException("history page empty");
        for (int i = 0; i < page.length(); i++) {
            JSONObject msg = page.optJSONObject(i);
            if (msg == null) throw new IllegalArgumentException("invalid history row");
            String id = msg.optString("id", "");
            if (id.isEmpty() || !ids.add(id)) throw new IllegalArgumentException("duplicate or missing message id");
            long seq = msg.optLong("message_seq", 0);
            if (seq <= 0 || (started && seq <= previousSeq))
                throw new IllegalArgumentException("history rows out of order or missing sequence");
            if (!started) {
                if (!start.equals(id)) throw new IllegalArgumentException("start message not in history window");
                started = true;
            }
            if (messages.size() == MAX_MESSAGES) throw new IllegalArgumentException("range exceeds 40 messages");
            messages.add(msg);
            previousSeq = seq;
            if (end.equals(id)) { finished = true; break; }
        }
    }

    /** Render only local element text and explicit placeholders. Never fetch remote links or HTML. */
    public static String displayText(String content) {
        StringBuilder out = new StringBuilder();
        for (Elements.El el : Elements.parse(content == null ? "" : content)) append(out, el, 0);
        String s = out.toString().trim();
        return s.isEmpty() ? "[空消息]" : s;
    }

    private static void append(StringBuilder out, Elements.El el, int depth) {
        if (out.length() > 1200 || depth > 8) return;
        switch (el.type) {
            case "text": out.append(el.text()); return;
            case "img": out.append("[图片]"); return;
            case "audio": out.append("[语音]"); return;
            case "video": out.append("[视频]"); return;
            case "file": out.append("[文件]"); return;
            case "emoji": out.append("[表情]"); return;
            case "message": out.append("[合并转发]"); return;
            case "at": out.append('@').append(el.attr("name").isEmpty() ? el.attr("id") : el.attr("name")); return;
            case "br": out.append('\n'); return;
            default:
                if (el.children.isEmpty()) out.append('[').append(el.type).append(']');
                else for (Elements.El child : el.children) append(out, child, depth + 1);
        }
    }
}
