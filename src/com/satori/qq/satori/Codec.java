package com.satori.qq.satori;

import com.satori.qq.qq.QQClient;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Bidirectional mapping between Satori content / events and the kernel's internal
 * message segments ({@code {type, data}}) produced by {@code Convert}.
 */
public final class Codec {
    private Codec() {}

    /**
     * Legacy CQ text -> internal segments.
     *
     * <p>History lookups can only reach a text snapshot of a message (the kernel keeps no
     * {@code msgRecord} for it), and that snapshot is CQ-coded text.  Satori requires
     * {@code Message.content} to be a standard element string, so the snapshot has to be parsed
     * back into segments before {@link #fromSegments} turns it into elements.  Plain text is
     * returned as a single text segment, which also gets it XML-escaped instead of being spliced
     * into the element string raw.</p>
     */
    public static JSONArray cqToSegments(String text) {
        JSONArray segs = new JSONArray();
        if (text == null || text.isEmpty()) return segs;
        // A json card carries commas and brackets inside its payload; when it is the whole
        // message, take everything up to the last bracket instead of the first.
        java.util.regex.Matcher whole = CQ_JSON_ONLY.matcher(text);
        if (whole.matches()) {
            JSONObject d = new JSONObject();
            try { d.put("data", unescapeCq(whole.group(1))); } catch (Exception ignore) {}
            segObj(segs, "json", d);
            return segs;
        }
        java.util.regex.Matcher m = CQ_CODE.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) segText(segs, text.substring(last, m.start()));
            JSONObject seg = cqSegment(m.group(1), m.group(2));
            if (seg != null) segs.put(seg); else segText(segs, m.group(0));
            last = m.end();
        }
        if (last < text.length()) segText(segs, text.substring(last));
        return segs;
    }

    /** CQ text -> Satori element string. */
    public static String fromCqText(String text, String assetBase) {
        if (text == null || text.isEmpty()) return "";
        return fromSegments(cqToSegments(text), assetBase);
    }

    private static final java.util.regex.Pattern CQ_CODE =
            java.util.regex.Pattern.compile("\\[CQ:([A-Za-z][A-Za-z0-9_\\-]*)((?:,[^\\]]*)?)\\]");
    private static final java.util.regex.Pattern CQ_JSON_ONLY =
            java.util.regex.Pattern.compile("\\[CQ:(?:json|lightapp),data=(.*)\\]", java.util.regex.Pattern.DOTALL);
    /** Params are separated by commas; a comma inside a value is not followed by "key=". */
    private static final java.util.regex.Pattern CQ_PARAM_SPLIT =
            java.util.regex.Pattern.compile(",(?=[A-Za-z_][A-Za-z0-9_]*=)");

    /** Known CQ types map onto the same segment shapes {@code Convert} emits. */
    private static JSONObject cqSegment(String type, String params) {
        JSONObject d = new JSONObject();
        if (params != null && params.startsWith(",")) {
            for (String pair : CQ_PARAM_SPLIT.split(params.substring(1))) {
                int eq = pair.indexOf(61);
                if (eq <= 0) continue;
                try { d.put(pair.substring(0, eq).trim(), unescapeCq(pair.substring(eq + 1))); }
                catch (Exception ignore) {}
            }
        }
        switch (type) {
            case "at": case "face": case "image": case "record": case "video": case "file":
            case "reply": case "json": case "lightapp": case "mface": case "poke": case "forward": {
                JSONObject seg = new JSONObject();
                try {
                    seg.put("type", "lightapp".equals(type) ? "json" : type);
                    seg.put("data", d);
                } catch (Exception ignore) { return null; }
                return seg;
            }
            default:
                return null;
        }
    }

    private static void segText(JSONArray segs, String text) {
        if (text == null || text.isEmpty()) return;
        JSONObject d = new JSONObject();
        try { d.put("text", unescapeCq(text)); } catch (Exception ignore) {}
        segObj(segs, "text", d);
    }

    private static void segObj(JSONArray segs, String type, JSONObject data) {
        try { segs.put(new JSONObject().put("type", type).put("data", data)); }
        catch (Exception ignore) {}
    }

    /** CQ escaping: &amp;#91; &amp;#93; &amp;#44; &amp;amp; */
    private static String unescapeCq(String raw) {
        if (raw == null || raw.indexOf(38) < 0) return raw == null ? "" : raw;
        return raw.replace("&#91;", "[").replace("&#93;", "]")
                .replace("&#44;", ",").replace("&amp;", "&");
    }

    public static JSONArray toSegments(String content) {
        return toSegments(Elements.parse(content));
    }

    public static JSONArray toSegments(List<Elements.El> els) {
        JSONArray segs = new JSONArray();
        if (els == null) return segs;
        boolean onlyForward = !els.isEmpty();
        JSONArray nodes = new JSONArray();
        for (Elements.El el : els) {
            if ("message".equals(el.type) && (el.attrs.optBoolean("forward", false)
                    || hasMessageChildren(el))) {
                collectNodes(el, nodes);
            } else {
                onlyForward = false;
                appendSeg(segs, el);
            }
        }
        if (onlyForward && nodes.length() > 0) return nodes;
        if (nodes.length() > 0) {
            for (int i = 0; i < nodes.length(); i++) segs.put(nodes.opt(i));
        }
        return segs;
    }

    private static boolean hasMessageChildren(Elements.El el) {
        for (Elements.El c : el.children) if ("message".equals(c.type)) return true;
        return false;
    }

    private static void collectNodes(Elements.El el, JSONArray nodes) {
        if ("message".equals(el.type) && !el.attrs.optBoolean("forward", false)
                && !hasMessageChildren(el)) {
            nodes.put(messageToNode(el));
            return;
        }
        boolean any = false;
        for (Elements.El c : el.children) {
            if ("message".equals(c.type)) {
                collectNodes(c, nodes);
                any = true;
            }
        }
        if (!any && "message".equals(el.type)) nodes.put(messageToNode(el));
    }

    private static JSONObject messageToNode(Elements.El el) {
        JSONObject data = new JSONObject();
        String userId = "";
        String nick = "";
        List<Elements.El> body = new ArrayList<>();
        for (Elements.El c : el.children) {
            if ("author".equals(c.type) || "user".equals(c.type)) {
                userId = first(c.attr("id"), c.attr("userId"));
                nick = first(c.attr("name"), c.attr("nickname"));
            } else body.add(c);
        }
        // message.id identifies an existing message; it is not an author id.
        if (userId.isEmpty()) userId = el.attr("userId");
        if (nick.isEmpty()) nick = first(el.attr("name"), el.attr("nickname"));
        try {
            if (!userId.isEmpty()) data.put("user_id", userId).put("uin", userId);
            if (!nick.isEmpty()) data.put("nickname", nick).put("name", nick);
            data.put("content", toSegments(body));
            return new JSONObject().put("type", "node").put("data", data);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void appendSeg(JSONArray segs, Elements.El el) {
        try {
            String t = el.type;
            JSONObject d = new JSONObject();
            switch (t) {
                case "text":
                    segs.put(seg("text", new JSONObject().put("text", el.text())));
                    break;
                case "at": {
                    String id = el.attr("id");
                    String type = el.attr("type");
                    String role = el.attr("role");
                    if ("all".equalsIgnoreCase(type) || "all".equalsIgnoreCase(id)
                            || id.isEmpty() && "all".equalsIgnoreCase(role))
                        d.put("qq", "all");
                    else if (id.isEmpty()) {
                        String fallback = "here".equalsIgnoreCase(type) ? "在线成员"
                                : first(el.attr("name"), role, type);
                        if (!fallback.isEmpty())
                            segs.put(seg("text", new JSONObject().put("text", "@" + fallback)));
                        break;
                    } else d.put("qq", id);
                    String name = el.attr("name");
                    if (!name.isEmpty()) d.put("name", name);
                    segs.put(seg("at", d));
                    break;
                }
                case "sharp":
                    segs.put(seg("text", new JSONObject().put("text", "#" + first(el.attr("name"), el.attr("id")))));
                    break;
                case "quote": {
                    String id = first(el.attr("id"), el.attr("messageId"));
                    segs.put(seg("reply", new JSONObject().put("id", id)));
                    break;
                }
                case "face":
                case "emoji":
                    segs.put(seg("face", new JSONObject().put("id", first(el.attr("id"), el.attr("name")))));
                    break;
                case "br":
                    segs.put(seg("text", new JSONObject().put("text", "\n")));
                    break;
                case "p": {
                    JSONArray inner = toSegments(el.children);
                    for (int i = 0; i < inner.length(); i++) segs.put(inner.opt(i));
                    segs.put(seg("text", new JSONObject().put("text", "\n")));
                    break;
                }
                case "a": {
                    JSONArray inner = toSegments(el.children);
                    for (int i = 0; i < inner.length(); i++) segs.put(inner.opt(i));
                    String href = el.attr("href");
                    String label = Elements.joinText(el.children);
                    if (!href.isEmpty() && !href.equals(label)) {
                        String suffix = label.isEmpty() ? href : " (" + href + ")";
                        segs.put(seg("text", new JSONObject().put("text", suffix)));
                    }
                    break;
                }
                case "img":
                case "image":
                    putSrc(d, el, "file");
                    segs.put(seg("image", d));
                    break;
                case "audio":
                case "record":
                    putSrc(d, el, "file");
                    segs.put(seg("record", d));
                    break;
                case "video":
                    putSrc(d, el, "file");
                    segs.put(seg("video", d));
                    break;
                case "file":
                    putSrc(d, el, "file");
                    String title = first(el.attr("title"), el.attr("name"));
                    if (!title.isEmpty()) d.put("name", title);
                    segs.put(seg("file", d));
                    break;
                case "json":
                    d.put("data", first(el.attr("data"), Elements.joinText(el.children)));
                    segs.put(seg("json", d));
                    break;
                case "mface":
                    copyAttrs(el, d);
                    segs.put(seg("mface", d));
                    break;
                case "poke":
                    copyAttrs(el, d);
                    segs.put(seg("poke", d));
                    break;
                default:
                    if (!el.children.isEmpty()) {
                        JSONArray inner = toSegments(el.children);
                        for (int i = 0; i < inner.length(); i++) segs.put(inner.opt(i));
                    } else if (!el.text().isEmpty()) {
                        segs.put(seg("text", new JSONObject().put("text", el.text())));
                    }
                    break;
            }
        } catch (Exception ignore) {}
    }

    private static void putSrc(JSONObject d, Elements.El el, String fileKey) throws Exception {
        String src = first(el.attr("src"), el.attr("url"), el.attr("file"));
        if (src.startsWith("http://") || src.startsWith("https://")) d.put("url", src);
        else d.put(fileKey, src);
        String title = first(el.attr("title"), el.attr("filename"), el.attr("name"));
        if (!title.isEmpty()) d.put("name", title);
        if (el.attrs.has("fileSize")) d.put("file_size", el.attrs.opt("fileSize"));
        if (el.attrs.has("cache")) d.put("cache", el.attrs.opt("cache"));
        if (el.attrs.has("timeout")) d.put("timeout", el.attrs.opt("timeout"));
        if (el.attrs.has("duration")) d.put("duration", el.attrs.opt("duration"));
        String poster = el.attr("poster");
        if (!poster.isEmpty()) d.put("poster", poster);
    }

    private static void copyAttrs(Elements.El el, JSONObject d) {
        java.util.Iterator<String> keys = el.attrs.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            try { d.put(snake(k), el.attrs.opt(k)); } catch (Exception ignore) {}
        }
    }

    public static String fromSegments(JSONArray segs, String assetBase) {
        List<Elements.El> els = new ArrayList<>();
        if (segs != null) {
            boolean allNodes = segs.length() > 0;
            List<Elements.El> nodes = new ArrayList<>();
            for (int i = 0; i < segs.length(); i++) {
                JSONObject seg = segs.optJSONObject(i);
                if (seg == null) { allNodes = false; continue; }
                if ("node".equals(seg.optString("type"))) {
                    nodes.add(nodeToMessage(seg.optJSONObject("data"), assetBase));
                } else {
                    allNodes = false;
                    Elements.El el = segToEl(seg, assetBase);
                    if (el != null) els.add(el);
                }
            }
            if (allNodes && !nodes.isEmpty()) {
                Elements.El wrap = Elements.empty("message");
                try { wrap.attrs.put("forward", true); } catch (Exception ignore) {}
                wrap.children.addAll(nodes);
                return wrap.toString(false);
            }
            els.addAll(0, nodes);
        }
        return Elements.stringify(els);
    }

    private static Elements.El nodeToMessage(JSONObject data, String assetBase) {
        Elements.El msg = Elements.empty("message");
        if (data == null) return msg;
        Elements.El author = Elements.empty("author");
        try {
            String uid = first(data.optString("user_id"), data.optString("uin"));
            String nick = first(data.optString("nickname"), data.optString("name"));
            if (!uid.isEmpty()) author.attrs.put("id", uid);
            if (!nick.isEmpty()) author.attrs.put("name", nick);
        } catch (Exception ignore) {}
        msg.children.add(author);
        JSONArray content = data.optJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.length(); i++) {
                Elements.El el = segToEl(content.optJSONObject(i), assetBase);
                if (el != null) msg.children.add(el);
            }
        } else {
            String text = data.optString("content", "");
            if (!text.isEmpty()) msg.children.add(Elements.text(text));
        }
        return msg;
    }

    private static Elements.El segToEl(JSONObject seg, String assetBase) {
        if (seg == null) return null;
        String type = seg.optString("type", "text");
        JSONObject d = seg.optJSONObject("data");
        if (d == null) d = new JSONObject();
        try {
            switch (type) {
                case "text":
                    return Elements.text(d.optString("text", ""));
                case "at": {
                    String qq = d.optString("qq", "");
                    Elements.El el = "all".equalsIgnoreCase(qq)
                            ? Elements.of("at", "type", "all")
                            : Elements.of("at", "id", qq);
                    String name = d.optString("name", "");
                    if (!name.isEmpty()) el.attrs.put("name", name);
                    return el;
                }
                case "face":
                    return Elements.of("emoji", "id", d.optString("id", "0"));
                case "reply":
                    return Elements.of("quote", "id", d.optString("id", ""));
                case "image":
                    return resource("img", d, assetBase);
                case "record":
                    return resource("audio", d, assetBase);
                case "video":
                    return resource("video", d, assetBase);
                case "file":
                    return resource("file", d, assetBase);
                case "json":
                    return Elements.of("json", "data", d.optString("data", d.optString("content", "")));
                case "mface": {
                    Elements.El el = Elements.empty("mface");
                    copySnake(d, el);
                    return el;
                }
                case "poke": {
                    Elements.El el = Elements.empty("poke");
                    copySnake(d, el);
                    return el;
                }
                case "forward": {
                    Elements.El el = Elements.empty("message");
                    el.attrs.put("forward", true);
                    String id = d.optString("id", "");
                    if (!id.isEmpty()) el.attrs.put("id", id);
                    return el;
                }
                default:
                    return Elements.text(d.optString("text", ""));
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static Elements.El resource(String tag, JSONObject d, String assetBase) throws Exception {
        Elements.El el = Elements.empty(tag);
        String url = d.optString("url", "");
        String file = first(d.optString("file", ""), d.optString("file_id", ""));
        String src = "";
        // Prefer the local asset server: qpic origin URLs expire and stall in Koishi / puppeteer.
        if (assetBase != null && !assetBase.isEmpty() && !file.isEmpty()
                && !file.startsWith("http://") && !file.startsWith("https://")
                && !file.startsWith("/") && !file.startsWith("file:") && !file.startsWith("data:"))
            src = assetBase + file;
        else if (url.startsWith("http://") || url.startsWith("https://")) src = url;
        else if (file.startsWith("http://") || file.startsWith("https://")) src = file;
        else if (file.startsWith("/")) src = "file://" + file;
        else src = file;
        if (!src.isEmpty()) el.attrs.put("src", src);
        String name = first(d.optString("name", ""), d.optString("file_name", ""));
        if (!name.isEmpty()) el.attrs.put("title", name);
        if (d.has("file_size")) el.attrs.put("fileSize", d.opt("file_size"));
        if (d.has("width")) el.attrs.put("width", d.opt("width"));
        if (d.has("height")) el.attrs.put("height", d.opt("height"));
        if (d.has("duration")) el.attrs.put("duration", d.opt("duration"));
        if (d.has("poster")) el.attrs.put("poster", d.opt("poster"));
        return el;
    }

    private static void copySnake(JSONObject d, Elements.El el) {
        java.util.Iterator<String> keys = d.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            try { el.attrs.put(Elements.camelize(k), d.opt(k)); } catch (Exception ignore) {}
        }
    }

    public static JSONObject channel(int chatType, long peerUin, String name) throws Exception {
        JSONObject ch = new JSONObject();
        if (chatType == QQClient.CT_GROUP) {
            ch.put("id", String.valueOf(peerUin));
            ch.put("type", 0);
            if (name != null && !name.isEmpty()) ch.put("name", name);
            if (peerUin != 0) ch.put("avatar", groupAvatarUrl(peerUin));
        } else {
            ch.put("id", "private:" + peerUin);
            ch.put("type", 1);
        }
        return ch;
    }

    public static JSONObject guild(long groupId, String name) throws Exception {
        JSONObject g = new JSONObject();
        g.put("id", String.valueOf(groupId));
        if (name != null && !name.isEmpty()) g.put("name", name);
        if (groupId != 0) g.put("avatar", groupAvatarUrl(groupId));
        return g;
    }

    /** QQ public group-avatar CDN. */
    public static String groupAvatarUrl(long groupId) {
        return "https://p.qlogo.cn/gh/" + groupId + "/" + groupId + "/640/";
    }

    public static JSONObject user(long id, String name, String nick) throws Exception {
        JSONObject u = new JSONObject();
        u.put("id", String.valueOf(id));
        if (name != null && !name.isEmpty()) u.put("name", name);
        if (nick != null && !nick.isEmpty()) u.put("nick", nick);
        if (id != 0) u.put("avatar", avatarUrl(id));
        return u;
    }

    /** Unix seconds from NT msgTime. Prefer the string snapshot; Android optLong("time") is unreliable. */
    public static long eventTime(JSONObject ob) {
        if (ob == null) return 0;
        String s = ob.optString("msg_time", "");
        if (!s.isEmpty()) {
            try {
                long t = Long.parseLong(s.trim());
                if (t > 10_000_000_000L) t /= 1000L;
                if (t > 0) return t;
            } catch (Exception ignore) {}
        }
        long t = ob.optLong("time", 0);
        if (t > 10_000_000_000L) t /= 1000L;
        return t;
    }

    /** Prefer QQ NT msgId, then an explicit string, then the legacy store id. */
    public static String publicMessageId(JSONObject ob) {
        if (ob == null) return "";
        long qqMsg = ob.optLong("qq_msg_id", 0);
        if (qqMsg != 0) return String.valueOf(qqMsg);
        String asStr = ob.optString("message_id_str", "");
        if (!asStr.isEmpty()) return asStr;
        Object raw = ob.opt("message_id");
        if (raw instanceof String) {
            String s = ((String) raw).trim();
            if (!s.isEmpty() && !"0".equals(s)) return s;
        }
        long n = ob.optLong("message_id", 0);
        return n == 0 ? "" : String.valueOf(n);
    }

    /** Top-level user_id, or sender.user_id from history items that only kept sender. */
    public static long eventUserId(JSONObject ob) {
        if (ob == null) return 0;
        long id = ob.optLong("user_id", 0);
        if (id != 0) return id;
        JSONObject sender = ob.optJSONObject("sender");
        return sender == null ? 0 : sender.optLong("user_id", 0);
    }

    /** QQ public avatar CDN. Koishi console / Satori User.avatar. */
    public static String avatarUrl(long uin) {
        return "https://q.qlogo.cn/headimg_dl?dst_uin=" + uin + "&spec=640";
    }

    public static boolean isPrivateChannel(String channelId) {
        return channelId != null && channelId.startsWith("private:");
    }

    public static long channelPeer(String channelId) {
        if (channelId == null || channelId.isEmpty()) return 0;
        String raw = isPrivateChannel(channelId) ? channelId.substring("private:".length()) : channelId;
        try { return Long.parseLong(raw.trim()); } catch (Exception e) { return 0; }
    }

    public static JSONObject toSatoriEvent(JSONObject ob, JSONObject loginSlim, long sn,
                                           String assetBase) throws Exception {
        if (ob == null) return null;
        String post = ob.optString("post_type", "");
        JSONObject ev = new JSONObject();
        ev.put("sn", sn);
        long ts = eventTime(ob);
        ev.put("timestamp", (ts > 0 ? ts : System.currentTimeMillis() / 1000) * 1000);
        ev.put("login", loginSlim);
        if ("message".equals(post)) return messageCreated(ob, ev, assetBase);
        if ("notice".equals(post)) return noticeEvent(ob, ev);
        if ("request".equals(post)) return requestEvent(ob, ev);
        return null;
    }

    private static JSONObject messageCreated(JSONObject ob, JSONObject ev, String assetBase)
            throws Exception {
        boolean group = "group".equals(ob.optString("message_type"));
        long userId = eventUserId(ob);
        long peer = group ? ob.optLong("group_id") : ob.optLong("peer_id",
                ob.optLong("user_id", 0) != 0 ? ob.optLong("user_id") : userId);
        JSONObject sender = ob.optJSONObject("sender");
        String nick = sender == null ? "" : sender.optString("nickname", "");
        String card = sender == null ? "" : sender.optString("card", "");
        String display = first(nick, card);
        String content = fromSegments(ob.optJSONArray("message"), assetBase);
        if (content.isEmpty()) content = fromCqText(ob.optString("raw_message", ""), assetBase);
        if (content.isEmpty() && userId == 0) return null;
        String gname = group ? ob.optString("group_name", "") : "";
        ev.put("type", "message-created");
        ev.put("channel", channel(group ? QQClient.CT_GROUP : QQClient.CT_C2C, peer, gname));
        if (group) ev.put("guild", guild(peer, gname));
        JSONObject author = user(userId, display, card);
        String satoriUserId = ob.optString("satori_user_id", "").trim();
        if (!satoriUserId.isEmpty()) author.put("id", satoriUserId);
        ev.put("user", author);
        if (ob.optBoolean("manual_self", false)) {
            ev.put("satori_qq", new JSONObject()
                    .put("manual_self", true)
                    .put("actual_user_id", ob.optString("actual_user_id", String.valueOf(userId)))
                    .put("user_id", author.optString("id")));
        }
        if (group) {
            JSONObject member = new JSONObject();
            member.put("user", ev.optJSONObject("user"));
            if (!card.isEmpty()) {
                member.put("name", card);
                member.put("nick", card);
            }
            String role = sender == null ? "" : sender.optString("role", "");
            if (role.isEmpty()) role = "member";
            JSONArray roles = new JSONArray();
            roles.put(new JSONObject().put("id", role).put("name", role));
            member.put("roles", roles);
            ev.put("member", member);
        }
        JSONObject msg = new JSONObject();
        String mid = publicMessageId(ob);
        if (!mid.isEmpty()) msg.put("id", mid);
        msg.put("content", content);
        msg.put("channel", ev.optJSONObject("channel"));
        if (group) msg.put("guild", ev.optJSONObject("guild"));
        msg.put("user", ev.optJSONObject("user"));
        if (group) msg.put("member", ev.optJSONObject("member"));
        long created = eventTime(ob);
        if (created > 0) msg.put("created_at", created * 1000);
        ev.put("message", msg);
        return ev;
    }

    private static JSONObject noticeEvent(JSONObject ob, JSONObject ev) throws Exception {
        String nt = ob.optString("notice_type", "");
        boolean group = ob.has("group_id") && ob.optLong("group_id") != 0;
        long peer = group ? ob.optLong("group_id") : ob.optLong("user_id");
        ev.put("channel", channel(group ? QQClient.CT_GROUP : QQClient.CT_C2C, peer, ""));
        if (group) ev.put("guild", guild(peer, ""));
        if ("group_recall".equals(nt) || "friend_recall".equals(nt)) {
            ev.put("type", "message-deleted");
            String mid = publicMessageId(ob);
            ev.put("message", new JSONObject().put("id", mid.isEmpty() ? "0" : mid));
            ev.put("user", user(eventUserId(ob), "", ""));
            ev.put("operator", user(ob.optLong("operator_id", 0), "", ""));
            return ev;
        }
        if ("notify".equals(nt) && "poke".equals(ob.optString("sub_type"))) {
            ev.put("type", "internal");
            ev.put("_type", "satori-qq/poke");
            ev.put("_data", new JSONObject()
                    .put("user_id", String.valueOf(ob.optLong("user_id")))
                    .put("target_id", String.valueOf(ob.optLong("target_id")))
                    .put("group_id", group ? String.valueOf(ob.optLong("group_id")) : ""));
            ev.put("user", user(eventUserId(ob), "", ""));
            return ev;
        }
        if ("group_increase".equals(nt)) {
            ev.put("type", "guild-member-added");
            ev.put("user", user(ob.optLong("user_id"), "", ""));
            ev.put("operator", user(ob.optLong("operator_id"), "", ""));
            ev.put("member", new JSONObject().put("user", ev.optJSONObject("user")));
            return ev;
        }
        if ("group_decrease".equals(nt)) {
            ev.put("type", "guild-member-removed");
            ev.put("user", user(ob.optLong("user_id"), "", ""));
            ev.put("operator", user(ob.optLong("operator_id"), "", ""));
            ev.put("member", new JSONObject().put("user", ev.optJSONObject("user")));
            return ev;
        }
        if ("group_ban".equals(nt)) {
            ev.put("type", "guild-member-updated");
            ev.put("user", user(ob.optLong("user_id"), "", ""));
            ev.put("operator", user(ob.optLong("operator_id"), "", ""));
            ev.put("member", new JSONObject().put("user", ev.optJSONObject("user")));
            ev.put("_type", "satori-qq/mute");
            ev.put("_data", new JSONObject().put("duration", ob.optLong("duration") * 1000));
            return ev;
        }
        return null;
    }

    private static JSONObject requestEvent(JSONObject ob, JSONObject ev) throws Exception {
        String rt = ob.optString("request_type", "");
        String flag = ob.optString("flag", "");
        ev.put("user", user(ob.optLong("user_id"), "", ""));
        ev.put("message", new JSONObject()
                .put("id", flag)
                .put("content", ob.optString("comment", "")));
        if ("friend".equals(rt)) {
            ev.put("type", "friend-request");
            return ev;
        }
        if ("group".equals(rt)) {
            long gid = ob.optLong("group_id");
            ev.put("guild", guild(gid, ""));
            ev.put("channel", channel(QQClient.CT_GROUP, gid, ""));
            ev.put("member", new JSONObject().put("user", ev.optJSONObject("user")));
            if ("invite".equals(ob.optString("sub_type"))) ev.put("type", "guild-request");
            else ev.put("type", "guild-member-request");
            return ev;
        }
        return null;
    }

    private static JSONObject seg(String type, JSONObject data) throws Exception {
        return new JSONObject().put("type", type).put("data", data);
    }

    private static String first(String... xs) {
        if (xs == null) return "";
        for (String x : xs) if (x != null && !x.isEmpty()) return x;
        return "";
    }

    private static String snake(String key) {
        return Elements.hyphenate(key).replace('-', '_');
    }
}
