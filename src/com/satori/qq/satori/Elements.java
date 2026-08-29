package com.satori.qq.satori;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Satori element tree: parse / stringify the XML-like {@code content} string. */
public final class Elements {
    private Elements() {}

    public static final class El {
        public final String type;
        public final JSONObject attrs;
        public final List<El> children = new ArrayList<>();

        public El(String type, JSONObject attrs) {
            this.type = type == null ? "text" : type;
            this.attrs = attrs == null ? new JSONObject() : attrs;
        }

        public static El text(String content) {
            JSONObject a = new JSONObject();
            try { a.put("content", content == null ? "" : content); } catch (Exception ignore) {}
            return new El("text", a);
        }

        public String text() { return attrs.optString("content", ""); }

        public String attr(String key) { return attrs.optString(key, ""); }

        public String toString(boolean strip) {
            if ("text".equals(type)) {
                String c = text();
                return strip ? c : escape(c, false);
            }
            String inner = "";
            for (El child : children) inner += child.toString(strip);
            if (strip) return inner;
            String tag = type;
            String a = attrString();
            if (children.isEmpty()) return "<" + tag + a + "/>";
            return "<" + tag + a + ">" + inner + "</" + tag + ">";
        }

        private String attrString() {
            StringBuilder sb = new StringBuilder();
            Iterator<String> keys = attrs.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if ("content".equals(key) && "text".equals(type)) continue;
                Object v = attrs.opt(key);
                if (v == null || v == JSONObject.NULL) continue;
                String hyphen = hyphenate(key);
                if (v instanceof Boolean) {
                    if ((Boolean) v) sb.append(' ').append(hyphen);
                    else sb.append(" no-").append(hyphen);
                    continue;
                }
                sb.append(' ').append(hyphen).append("=\"").append(escape(String.valueOf(v), true)).append('"');
            }
            return sb.toString();
        }
    }

    public static String stringify(List<El> els) {
        if (els == null || els.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (El el : els) sb.append(el.toString(false));
        return sb.toString();
    }

    public static String escape(String source, boolean attr) {
        String s = source == null ? "" : source;
        s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return attr ? s.replace("\"", "&quot;") : s;
    }

    public static String unescape(String source) {
        if (source == null || source.isEmpty()) return "";
        String s = source
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"");
        java.util.regex.Matcher m = Pattern.compile("&#(\\d+);").matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String replacement = m.group();
            try {
                int code = Integer.parseInt(m.group(1));
                if (code != 38 && Character.isValidCodePoint(code))
                    replacement = new String(Character.toChars(code));
            } catch (RuntimeException ignore) {}
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        s = out.toString();
        m = Pattern.compile("&#x([0-9a-f]+);", Pattern.CASE_INSENSITIVE).matcher(s);
        out = new StringBuffer();
        while (m.find()) {
            String replacement = m.group();
            try {
                int code = Integer.parseInt(m.group(1), 16);
                if (code != 0x26 && Character.isValidCodePoint(code))
                    replacement = new String(Character.toChars(code));
            } catch (RuntimeException ignore) {}
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString().replace("&amp;", "&");
    }

    private static final Pattern TAG = Pattern.compile(
            "(?<comment><!--[\\s\\S]*?-->)|<(?<closing>/?)(?<name>[a-z][a-z0-9-]*)(?<extra>[^>]*?)\\s*(?<self>/?)>");
    private static final Pattern ATTR = Pattern.compile(
            "([^\\s=]+)(?:=\"(?<v1>[^\"]*)\"|='(?<v2>[^']*)')?");

    public static List<El> parse(String source) {
        List<El> roots = new ArrayList<>();
        if (source == null || source.isEmpty()) return roots;
        ArrayList<Frame> stack = new ArrayList<>();
        int pos = 0;
        Matcher m = TAG.matcher(source);
        while (m.find()) {
            if (m.start() > pos) pushText(roots, stack, source.substring(pos, m.start()));
            pos = m.end();
            if (m.group("comment") != null) continue;
            boolean closing = "/".equals(m.group("closing"));
            String type = m.group("name") == null ? "" : m.group("name");
            String extra = m.group("extra") == null ? "" : m.group("extra");
            boolean empty = "/".equals(m.group("self"));
            if (closing) {
                closeTag(roots, stack, type, source, m.start(), m.end());
                continue;
            }
            El el = new El(type, parseAttrs(extra));
            if (empty) append(roots, stack, el);
            else stack.add(new Frame(el, source.substring(m.start(), m.end())));
        }
        if (pos < source.length()) pushText(roots, stack, source.substring(pos));
        // An unclosed element is text, not a partially valid tree. The outermost
        // dangling frame contains every nested dangling frame in its raw span.
        if (!stack.isEmpty()) appendDangling(roots, null, stack, 0);
        return roots;
    }

    private static final class Frame {
        final El el;
        final String rawOpen;
        Frame(El el, String rawOpen) { this.el = el; this.rawOpen = rawOpen; }
    }

    private static void closeTag(List<El> roots, ArrayList<Frame> stack, String type,
                                 String source, int closeAt, int closeEnd) {
        for (int i = stack.size() - 1; i >= 0; i--) {
            if (type.equals(stack.get(i).el.type)) {
                // Any elements opened inside this one but not closed before its end
                // are literal source according to the Satori message grammar.
                if (stack.size() > i + 1) {
                    appendDangling(null, stack.get(i).el.children, stack, i + 1);
                    while (stack.size() > i + 1) stack.remove(stack.size() - 1);
                }
                Frame complete = stack.remove(i);
                append(roots, stack, complete.el);
                return;
            }
        }
        pushText(roots, stack, source.substring(closeAt, closeEnd));
    }

    private static void append(List<El> roots, ArrayList<Frame> stack, El el) {
        if (stack.isEmpty()) roots.add(el);
        else stack.get(stack.size() - 1).el.children.add(el);
    }

    private static void appendDangling(List<El> roots, List<El> target,
                                       ArrayList<Frame> stack, int from) {
        for (int i = from; i < stack.size(); i++) {
            Frame frame = stack.get(i);
            El open = El.text(frame.rawOpen);
            if (target != null) target.add(open); else roots.add(open);
            for (El child : frame.el.children) {
                if (target != null) target.add(child); else roots.add(child);
            }
        }
    }

    private static void pushText(List<El> roots, ArrayList<Frame> stack, String raw) {
        String text = trimFormattingWhitespace(unescape(raw));
        if (text.isEmpty()) return;
        append(roots, stack, El.text(text));
    }

    private static String trimFormattingWhitespace(String text) {
        if (text == null || text.isEmpty()) return "";
        int first = 0;
        while (first < text.length() && Character.isWhitespace(text.charAt(first))) first++;
        if (first > 0) {
            String prefix = text.substring(0, first);
            if (prefix.indexOf('\n') >= 0 || prefix.indexOf('\r') >= 0) text = text.substring(first);
        }
        int last = text.length();
        while (last > 0 && Character.isWhitespace(text.charAt(last - 1))) last--;
        if (last < text.length()) {
            String suffix = text.substring(last);
            if (suffix.indexOf('\n') >= 0 || suffix.indexOf('\r') >= 0) text = text.substring(0, last);
        }
        return text;
    }

    private static JSONObject parseAttrs(String extra) {
        JSONObject attrs = new JSONObject();
        if (extra == null || extra.isEmpty()) return attrs;
        Matcher m = ATTR.matcher(extra);
        while (m.find()) {
            String raw = m.group(1);
            if (raw == null || raw.isEmpty()) continue;
            boolean negated = raw.startsWith("no-") && m.group("v1") == null && m.group("v2") == null;
            String key = camelize(negated ? raw.substring(3) : raw);
            String v1 = m.group("v1");
            String v2 = m.group("v2");
            try {
                if (v1 != null) attrs.put(key, unescape(v1));
                else if (v2 != null) attrs.put(key, unescape(v2));
                else attrs.put(key, !negated);
            } catch (Exception ignore) {}
        }
        return attrs;
    }

    static String camelize(String key) {
        if (key == null || key.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '-' || c == '_') { upper = true; continue; }
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return sb.toString();
    }

    static String hyphenate(String key) {
        if (key == null || key.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('-');
                sb.append(Character.toLowerCase(c));
            } else sb.append(c);
        }
        return sb.toString();
    }

    public static String joinText(List<El> els) {
        StringBuilder sb = new StringBuilder();
        if (els != null) for (El el : els) sb.append(el.toString(true));
        return sb.toString();
    }

    public static El text(String content) { return El.text(content); }

    public static El empty(String type) { return new El(type, new JSONObject()); }

    public static El of(String type, String k, String v) {
        JSONObject a = new JSONObject();
        try { if (k != null) a.put(k, v == null ? "" : v); } catch (Exception ignore) {}
        return new El(type, a);
    }
}
