package com.satori.qq.satori;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Minimal binary-safe RFC 7578 reader for {@code upload.create}. */
public final class Multipart {
    private Multipart() {}

    public static final class Part {
        public String name = "";
        public String filename = "";
        public String contentType = "";
        public byte[] data = new byte[0];
    }

    public static List<Part> parse(byte[] body, String contentType) {
        String boundary = boundaryOf(contentType);
        if (boundary.isEmpty()) throw new IllegalArgumentException("missing multipart boundary");
        if (boundary.length() > 200) throw new IllegalArgumentException("multipart boundary too long");
        byte[] source = body == null ? new byte[0] : body;
        byte[] marker = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] nextMarker = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] headerEnd = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        ArrayList<Part> out = new ArrayList<>();
        int cursor = indexOf(source, marker, 0);
        if (cursor < 0) throw new IllegalArgumentException("multipart boundary not found");
        cursor += marker.length;
        while (cursor < source.length) {
            if (startsWith(source, cursor, new byte[]{'-', '-'})) break;
            if (!startsWith(source, cursor, new byte[]{'\r', '\n'}))
                throw new IllegalArgumentException("malformed multipart delimiter");
            cursor += 2;
            int headersAt = indexOf(source, headerEnd, cursor);
            if (headersAt < 0) throw new IllegalArgumentException("multipart headers not terminated");
            String rawHeaders = new String(source, cursor, headersAt - cursor,
                    StandardCharsets.ISO_8859_1);
            Map<String, String> headers = headers(rawHeaders);
            int dataAt = headersAt + headerEnd.length;
            int dataEnd = indexOf(source, nextMarker, dataAt);
            if (dataEnd < 0) throw new IllegalArgumentException("multipart part not terminated");

            Part part = new Part();
            String disposition = headers.get("content-disposition");
            if (disposition == null || !disposition.toLowerCase(Locale.ROOT).startsWith("form-data"))
                throw new IllegalArgumentException("missing form-data disposition");
            part.name = parameter(disposition, "name");
            part.filename = parameter(disposition, "filename");
            part.contentType = value(headers.get("content-type"));
            part.data = new byte[dataEnd - dataAt];
            System.arraycopy(source, dataAt, part.data, 0, part.data.length);
            out.add(part);
            if (out.size() > 64) throw new IllegalArgumentException("too many multipart parts");
            cursor = dataEnd + 2 + marker.length;
        }
        return out;
    }

    private static String boundaryOf(String contentType) {
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data"))
            return "";
        return parameter(contentType, "boundary");
    }

    private static Map<String, String> headers(String raw) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (String line : raw.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            out.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    line.substring(colon + 1).trim());
        }
        return out;
    }

    private static String parameter(String header, String wanted) {
        if (header == null) return "";
        String[] pieces = header.split(";");
        for (int i = 1; i < pieces.length; i++) {
            String piece = pieces[i].trim();
            int eq = piece.indexOf('=');
            if (eq <= 0 || !wanted.equalsIgnoreCase(piece.substring(0, eq).trim())) continue;
            String result = piece.substring(eq + 1).trim();
            if (result.length() >= 2 && result.charAt(0) == '"'
                    && result.charAt(result.length() - 1) == '"') {
                result = result.substring(1, result.length() - 1)
                        .replace("\\\"", "\"").replace("\\\\", "\\");
            }
            return result;
        }
        return "";
    }

    private static String value(String value) { return value == null ? "" : value.trim(); }

    private static boolean startsWith(byte[] source, int at, byte[] wanted) {
        if (at < 0 || at + wanted.length > source.length) return false;
        for (int i = 0; i < wanted.length; i++) if (source[at + i] != wanted[i]) return false;
        return true;
    }

    private static int indexOf(byte[] source, byte[] wanted, int from) {
        if (wanted.length == 0) return Math.max(0, from);
        outer: for (int i = Math.max(0, from); i <= source.length - wanted.length; i++) {
            for (int j = 0; j < wanted.length; j++) {
                if (source[i + j] != wanted[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
