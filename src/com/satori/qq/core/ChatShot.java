package com.satori.qq.core;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Render a chat-history image off-screen with Android Canvas; NOT a capture of QQ's UI. */
public final class ChatShot {
    private ChatShot() {}
    private static final int WIDTH = 720, MAX_HEIGHT = 8192, PAD = 24, LINE = 43;

    private static final class Row {
        final boolean self;
        final String name, time;
        final List<String> lines;
        final int top, bottom;
        Row(boolean self, String name, String time, List<String> lines, int top) {
            this.self = self; this.name = name; this.time = time; this.lines = lines;
            this.top = top;
            this.bottom = top + 28 + 42 + lines.size() * LINE + 32;
        }
    }

    public static byte[] render(List<JSONObject> messages, long selfUin, String channel) throws Exception {
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTextSize(30);
        ArrayList<Row> rows = new ArrayList<>();
        int height = 92;
        SimpleDateFormat clock = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        for (JSONObject msg : messages) {
            JSONObject user = msg.optJSONObject("user");
            long sender = user == null ? 0 : parseLong(user.optString("id", ""));
            boolean self = sender != 0 && sender == selfUin;
            String name = user == null ? "" : user.optString("name", "");
            if (name.isEmpty()) name = sender > 0 ? String.valueOf(sender) : "未知用户";
            String body = ShotRange.displayText(msg.optString("content", ""));
            if (body.length() > 1200) body = body.substring(0, 1200) + "…";
            List<String> lines = wrap(text, body, 496);
            long when = msg.optLong("created_at", 0);
            String time = when <= 0 ? "" : clock.format(new Date(when));
            Row row = new Row(self, name, time, lines, height);
            rows.add(row);
            height = row.bottom + 16;
            if (height + PAD > MAX_HEIGHT)
                throw new IllegalArgumentException("screenshot exceeds 8192 pixels; choose a shorter range");
        }
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, Math.max(128, height + PAD), Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.rgb(242, 244, 248));
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.rgb(40, 49, 67));
            paint.setTextSize(26);
            canvas.drawText("聊天记录 · " + ellipsize(paint, channel, 480), 36, 55, paint);
            for (Row row : rows) {
                int left = row.self ? 128 : 24;
                int right = row.self ? WIDTH - 24 : WIDTH - 128;
                paint.setColor(row.self ? Color.rgb(220, 244, 218) : Color.WHITE);
                canvas.drawRoundRect(new RectF(left, row.top, right, row.bottom), 20, 20, paint);
                paint.setColor(Color.rgb(97, 106, 124));
                paint.setTextSize(23);
                canvas.drawText(ellipsize(paint, row.name + "  " + row.time, 500), left + 18, row.top + 35, paint);
                text.setColor(Color.rgb(34, 39, 49));
                int y = row.top + 81;
                for (String line : row.lines) {
                    canvas.drawText(line, left + 18, y, text);
                    y += LINE;
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                throw new IllegalStateException("PNG encode failed");
            return out.toByteArray();
        } finally { bitmap.recycle(); }
    }

    private static long parseLong(String raw) {
        try { return Long.parseLong(raw); } catch (Exception ignored) { return 0; }
    }

    private static String ellipsize(Paint p, String raw, int width) {
        if (p.measureText(raw) <= width) return raw;
        int n = p.breakText(raw, true, width - p.measureText("…"), null);
        return raw.substring(0, Math.max(0, n)) + "…";
    }

    private static List<String> wrap(Paint paint, String raw, int width) {
        ArrayList<String> out = new ArrayList<>();
        for (String para : raw.replace('\r', ' ').split("\n", -1)) {
            if (para.isEmpty()) { out.add(""); continue; }
            int offset = 0;
            while (offset < para.length()) {
                int n = Math.max(1, paint.breakText(para, offset, para.length(), true, width, null));
                int end = Math.min(para.length(), offset + n);
                if (end < para.length() && Character.isHighSurrogate(para.charAt(end - 1))) end--;
                if (end <= offset) end = Math.min(para.length(), offset + 2);
                out.add(para.substring(offset, end));
                offset = end;
            }
        }
        return out;
    }
}
