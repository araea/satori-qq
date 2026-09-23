package com.satori.qq.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 统一图标：24 单位网格、2 单位描边、圆头圆接，路径只在类加载时建一次，各处共享。
 *
 * <p>图标永远不单独承载语义：装饰性的放进 {@code IMPORTANT_FOR_ACCESSIBILITY_NO} 的视图，
 * 图标按钮的名称由 contentDescription 给出（WCAG 1.1.1 / 4.1.2）。方向性图标（返回、箭头）
 * 声明 autoMirrored，从右到左的语言里自动翻转。
 */
final class Icon extends Drawable {
    static final int REFRESH = 0;
    static final int SETTINGS = 1;
    static final int BACK = 2;
    static final int COPY = 3;
    static final int SHARE = 4;
    static final int CHECK = 5;
    static final int OK = 6;
    static final int PENDING = 7;
    static final int ERROR = 8;
    static final int UNKNOWN = 9;
    static final int POWER = 10;
    static final int SHOW = 11;
    static final int HIDE = 12;
    static final int KEY = 13;
    static final int NEXT = 14;
    static final int INFO = 15;
    static final int OPEN = 16;
    static final int WARNING = 17;
    static final int RESTART = 18;
    static final int FILE = 19;

    private static final Path[] STROKES = new Path[20];
    private static final Path[] FILLS = new Path[20];
    private static final boolean[] MIRRORED = new boolean[20];

    static {
        for (int i = 0; i < STROKES.length; i++) {
            STROKES[i] = new Path();
            FILLS[i] = new Path();
        }
        Path p;
        RectF r = new RectF();

        p = STROKES[REFRESH];
        r.set(5, 5, 19, 19);
        p.addArc(r, -50, 295);
        p.moveTo(19.4f, 4.6f);
        p.lineTo(19.4f, 9.2f);
        p.lineTo(14.8f, 9.2f);

        p = STROKES[SETTINGS];
        for (int k = 0; k < 8; k++) {
            double a = Math.toRadians(k * 45);
            float[][] teeth = {{7.4f, -15}, {9.6f, -9}, {9.6f, 9}, {7.4f, 15}};
            for (int j = 0; j < teeth.length; j++) {
                double angle = a + Math.toRadians(teeth[j][1]);
                float x = 12 + teeth[j][0] * (float) Math.cos(angle);
                float y = 12 + teeth[j][0] * (float) Math.sin(angle);
                if (k == 0 && j == 0) p.moveTo(x, y);
                else p.lineTo(x, y);
            }
        }
        p.close();
        p.addCircle(12, 12, 3, Path.Direction.CW);

        p = STROKES[BACK];
        p.moveTo(19.5f, 12);
        p.lineTo(5, 12);
        p.moveTo(11, 5.5f);
        p.lineTo(4.5f, 12);
        p.lineTo(11, 18.5f);
        MIRRORED[BACK] = true;

        p = STROKES[COPY];
        r.set(8.5f, 8.5f, 19.5f, 19.5f);
        p.addRoundRect(r, 2.5f, 2.5f, Path.Direction.CW);
        p.moveTo(15.5f, 5);
        p.lineTo(7, 5);
        p.quadTo(4.5f, 5, 4.5f, 7.5f);
        p.lineTo(4.5f, 16);

        p = STROKES[SHARE];
        p.addCircle(17.5f, 5.5f, 2.5f, Path.Direction.CW);
        p.addCircle(6.5f, 12, 2.5f, Path.Direction.CW);
        p.addCircle(17.5f, 18.5f, 2.5f, Path.Direction.CW);
        p.moveTo(8.7f, 10.7f);
        p.lineTo(15.3f, 6.8f);
        p.moveTo(8.7f, 13.3f);
        p.lineTo(15.3f, 17.2f);

        p = STROKES[CHECK];
        p.moveTo(5, 12.5f);
        p.lineTo(9.5f, 17);
        p.lineTo(19, 7.5f);

        p = STROKES[OK];
        p.addCircle(12, 12, 9, Path.Direction.CW);
        p.moveTo(8, 12.3f);
        p.lineTo(10.8f, 15);
        p.lineTo(16.2f, 9.6f);

        p = STROKES[PENDING];
        p.addCircle(12, 12, 9, Path.Direction.CW);
        p.moveTo(12, 7);
        p.lineTo(12, 12);
        p.lineTo(15.5f, 14);

        p = STROKES[ERROR];
        p.addCircle(12, 12, 9, Path.Direction.CW);
        p.moveTo(12, 7.5f);
        p.lineTo(12, 12.8f);
        FILLS[ERROR].addCircle(12, 16.3f, 1.25f, Path.Direction.CW);

        p = STROKES[UNKNOWN];
        p.addCircle(12, 12, 9, Path.Direction.CW);
        p.moveTo(8, 12);
        p.lineTo(16, 12);

        p = STROKES[POWER];
        r.set(5, 5, 19, 19);
        p.addArc(r, -55, 290);
        p.moveTo(12, 3.5f);
        p.lineTo(12, 11);

        p = STROKES[SHOW];
        p.moveTo(2.5f, 12);
        p.quadTo(12, 2.5f, 21.5f, 12);
        p.quadTo(12, 21.5f, 2.5f, 12);
        p.close();
        p.addCircle(12, 12, 3, Path.Direction.CW);

        p = STROKES[HIDE];
        p.moveTo(2.5f, 12);
        p.quadTo(12, 2.5f, 21.5f, 12);
        p.quadTo(12, 21.5f, 2.5f, 12);
        p.close();
        p.addCircle(12, 12, 3, Path.Direction.CW);
        p.moveTo(4, 4);
        p.lineTo(20, 20);

        p = STROKES[KEY];
        p.addCircle(8, 16, 4, Path.Direction.CW);
        p.moveTo(10.9f, 13.1f);
        p.lineTo(20, 4);
        p.moveTo(17, 7);
        p.lineTo(19.5f, 9.5f);
        p.moveTo(14.5f, 9.5f);
        p.lineTo(16.5f, 11.5f);

        p = STROKES[NEXT];
        p.moveTo(9.5f, 6);
        p.lineTo(15.5f, 12);
        p.lineTo(9.5f, 18);
        MIRRORED[NEXT] = true;

        p = STROKES[INFO];
        p.addCircle(12, 12, 9, Path.Direction.CW);
        p.moveTo(12, 11);
        p.lineTo(12, 16.5f);
        FILLS[INFO].addCircle(12, 7.8f, 1.25f, Path.Direction.CW);

        p = STROKES[OPEN];
        p.moveTo(13.5f, 4.5f);
        p.lineTo(19.5f, 4.5f);
        p.lineTo(19.5f, 10.5f);
        p.moveTo(19.5f, 4.5f);
        p.lineTo(11.5f, 12.5f);
        p.moveTo(18.5f, 14.5f);
        p.lineTo(18.5f, 18);
        p.quadTo(18.5f, 19.5f, 17, 19.5f);
        p.lineTo(6, 19.5f);
        p.quadTo(4.5f, 19.5f, 4.5f, 18);
        p.lineTo(4.5f, 7);
        p.quadTo(4.5f, 5.5f, 6, 5.5f);
        p.lineTo(9.5f, 5.5f);
        MIRRORED[OPEN] = true;

        p = STROKES[WARNING];
        p.moveTo(12, 3.8f);
        p.lineTo(21, 19.5f);
        p.lineTo(3, 19.5f);
        p.close();
        p.moveTo(12, 9.5f);
        p.lineTo(12, 13.8f);
        FILLS[WARNING].addCircle(12, 16.7f, 1.2f, Path.Direction.CW);

        p = STROKES[RESTART];
        r.set(5, 5, 19, 19);
        p.addArc(r, 200, 290);
        p.moveTo(4.6f, 4.6f);
        p.lineTo(4.6f, 9.2f);
        p.lineTo(9.2f, 9.2f);

        p = STROKES[FILE];
        p.moveTo(14, 3.5f);
        p.lineTo(7, 3.5f);
        p.quadTo(5, 3.5f, 5, 5.5f);
        p.lineTo(5, 18.5f);
        p.quadTo(5, 20.5f, 7, 20.5f);
        p.lineTo(17, 20.5f);
        p.quadTo(19, 20.5f, 19, 18.5f);
        p.lineTo(19, 8.5f);
        p.close();
        p.moveTo(14, 3.5f);
        p.lineTo(14, 8.5f);
        p.lineTo(19, 8.5f);
        p.moveTo(8.5f, 13);
        p.lineTo(15.5f, 13);
        p.moveTo(8.5f, 16.5f);
        p.lineTo(13, 16.5f);
    }

    private final int kind;
    private final int size;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);

    Icon(Tokens tokens, int kind, int color) {
        this.kind = kind;
        this.size = tokens.icon;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        solid.setStyle(Paint.Style.FILL);
        tint(color);
    }

    Icon tint(int color) {
        if (stroke.getColor() != color) {
            stroke.setColor(color);
            solid.setColor(color);
            invalidateSelf();
        }
        return this;
    }

    int kind() {
        return kind;
    }

    @Override public int getIntrinsicWidth() {
        return size;
    }

    @Override public int getIntrinsicHeight() {
        return size;
    }

    @Override public boolean isAutoMirrored() {
        return MIRRORED[kind];
    }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        float scale = Math.min(b.width(), b.height()) / 24f;
        if (scale <= 0) return;
        int save = canvas.save();
        canvas.translate(b.exactCenterX() - 12 * scale, b.exactCenterY() - 12 * scale);
        if (MIRRORED[kind] && getLayoutDirection() == android.view.View.LAYOUT_DIRECTION_RTL) {
            canvas.scale(-1, 1, 12 * scale, 12 * scale);
        }
        canvas.scale(scale, scale);
        canvas.drawPath(STROKES[kind], stroke);
        if (!FILLS[kind].isEmpty()) canvas.drawPath(FILLS[kind], solid);
        canvas.restoreToCount(save);
    }

    @Override public void setAlpha(int alpha) {
        stroke.setAlpha(alpha);
        solid.setAlpha(alpha);
    }

    @Override public void setColorFilter(ColorFilter filter) {
        stroke.setColorFilter(filter);
        solid.setColorFilter(filter);
    }

    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
