package com.satori.qq.qq;

import java.util.List;

/** Readable fallback for QQ bot markdown cards and their inline keyboard. */
public final class RichText {
    private RichText() {}

    public static String markdown(Ref ref, Object element) {
        Object markdown = ref.getOrNull(element, "markdownElement");
        return clean(Ref.asStr(ref.getOrNull(markdown, "content")));
    }

    /** Buttons are read-only: do not expose callback data as a URL or an executable command. */
    public static String keyboard(Ref ref, Object element) {
        Object keyboard = ref.getOrNull(element, "inlineKeyboardElement");
        Object rows = ref.getOrNull(keyboard, "rows");
        if (!(rows instanceof List)) return "";
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (Object row : (List<?>) rows) {
            Object buttons = ref.getOrNull(row, "buttons");
            if (!(buttons instanceof List)) continue;
            for (Object button : (List<?>) buttons) {
                String label = clean(Ref.asStr(ref.getOrNull(button, "label")))
                        .replace('\n', ' ').trim();
                if (label.isEmpty()) label = clean(Ref.asStr(ref.getOrNull(button, "visitedLabel")))
                        .replace('\n', ' ').trim();
                if (label.isEmpty()) continue;
                if (out.length() > 0) out.append(' ');
                out.append('[').append(label).append(']');
                if (++count >= 20) return out.toString();
            }
        }
        return out.toString();
    }

    private static String clean(String value) {
        if (value == null) return "";
        // Preserve markdown and line breaks, while removing NUL/control bytes from QQ payloads.
        return value.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
    }
}
