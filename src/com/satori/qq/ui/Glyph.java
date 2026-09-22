package com.satori.qq.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * 统一图标：24dp 网格、1.8 单位描边、圆头圆接、无填充（除圆点）；图形绕自身中心等比缩放。
 *
 * <p>图标是纯装饰：作为 View 时一律 {@code IMPORTANT_FOR_ACCESSIBILITY_NO}，语义由所在控件的
 * contentDescription / 文案承担（WCAG 4.1.2）。{@link #paint} 让按钮也能画同一套图形，
 * 避免"图标按钮"为了画一个图标而拼一个容器。
 */
final class Glyph extends View {
    static final int PULSE = 0;
    static final int SLIDERS = 1;
    static final int DOCUMENT = 2;
    static final int REFRESH = 3;
    static final int COPY = 4;
    static final int EXTERNAL = 5;
    static final int SHIELD = 6;

    private final int kind;
    private int color;

    Glyph(Tokens tokens, int kind, int color) {
        super(tokens.activity);
        this.kind = kind;
        this.color = color;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void tint(int color) {
        this.color = color;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        paint(canvas, kind, color, getWidth(), getHeight());
    }

    /** 在给定矩形内居中画一个 24 单位网格的图标。 */
    static void paint(Canvas canvas, int kind, int color, float width, float height) {
        if (width <= 0 || height <= 0) return;
        float size = Math.min(width, height);
        float stroke = size / 24f * 1.8f;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        Path path = new Path();
        RectF rect = new RectF();

        canvas.save();
        canvas.translate((width - size) / 2f, (height - size) / 2f);
        canvas.scale(size / 24f, size / 24f);

        switch (kind) {
            case PULSE:
                rect.set(3, 4, 21, 20);
                canvas.drawRoundRect(rect, 5, 5, paint);
                path.reset();
                path.moveTo(6, 12); path.lineTo(9, 12); path.lineTo(11, 8);
                path.lineTo(14, 16); path.lineTo(16, 12); path.lineTo(18, 12);
                canvas.drawPath(path, paint);
                break;
            case SLIDERS:
                canvas.drawLine(4, 7, 20, 7, paint);
                canvas.drawLine(4, 17, 20, 17, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(9, 7, 3, paint);
                canvas.drawCircle(16, 17, 3, paint);
                break;
            case DOCUMENT:
                rect.set(5, 3, 19, 21);
                canvas.drawRoundRect(rect, 3, 3, paint);
                canvas.drawLine(9, 8, 15, 8, paint);
                canvas.drawLine(9, 12, 15, 12, paint);
                canvas.drawLine(9, 16, 12, 16, paint);
                break;
            case REFRESH:
                rect.set(4.5f, 4.5f, 19.5f, 19.5f);
                canvas.drawArc(rect, -60, 300, false, paint);
                path.reset();
                path.moveTo(19.5f, 8.6f); path.lineTo(19.8f, 4.2f); path.lineTo(15.6f, 5.7f);
                canvas.drawPath(path, paint);
                break;
            case COPY:
                rect.set(8, 8, 20, 20);
                canvas.drawRoundRect(rect, 2.5f, 2.5f, paint);
                path.reset();
                path.moveTo(16, 5.5f); path.lineTo(6, 5.5f); path.lineTo(5, 6.5f);
                path.lineTo(5, 16.5f);
                canvas.drawPath(path, paint);
                break;
            case EXTERNAL:
                path.reset();
                path.moveTo(13, 4.5f); path.lineTo(19.5f, 4.5f); path.lineTo(19.5f, 11);
                canvas.drawPath(path, paint);
                canvas.drawLine(19.5f, 4.5f, 11.5f, 12.5f, paint);
                path.reset();
                path.moveTo(19, 14.5f); path.lineTo(19, 19); path.lineTo(5, 19);
                path.lineTo(5, 5.5f); path.lineTo(9.5f, 5.5f);
                canvas.drawPath(path, paint);
                break;
            default:
                path.reset();
                path.moveTo(12, 3.5f); path.lineTo(19.5f, 6.5f); path.lineTo(19.5f, 12.5f);
                path.quadTo(19.5f, 17.5f, 12, 20.5f);
                path.quadTo(4.5f, 17.5f, 4.5f, 12.5f);
                path.lineTo(4.5f, 6.5f); path.close();
                canvas.drawPath(path, paint);
                path.reset();
                path.moveTo(8.8f, 12); path.lineTo(11.2f, 14.4f); path.lineTo(15.4f, 9.8f);
                canvas.drawPath(path, paint);
                break;
        }
        canvas.restore();
    }
}
