package com.satori.qq.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Consistent rounded 24dp navigation glyphs. Labels live on their accessible parent buttons. */
final class Glyph extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int kind;
    private int color;
    Glyph(MaterialStyle ui, int kind, int color) {
        super(ui.activity); this.kind = kind; this.color = color;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
    }
    void tint(int color) { this.color = color; invalidate(); }
    @Override protected void onDraw(Canvas canvas) {
        canvas.save(); canvas.scale(getWidth()/24f, getHeight()/24f); paint.setColor(color);
        if (kind == 0) {
            canvas.drawRoundRect(3, 4, 21, 20, 5, 5, paint);
            path.reset(); path.moveTo(6, 12); path.lineTo(9, 12); path.lineTo(11, 8); path.lineTo(14, 16); path.lineTo(16, 12); path.lineTo(18, 12); canvas.drawPath(path, paint);
        } else if (kind == 1) {
            canvas.drawLine(4, 7, 20, 7, paint); canvas.drawLine(4, 17, 20, 17, paint);
            paint.setStyle(Paint.Style.FILL); canvas.drawCircle(9, 7, 3, paint); canvas.drawCircle(16, 17, 3, paint); paint.setStyle(Paint.Style.STROKE);
        } else {
            canvas.drawRoundRect(5, 3, 19, 21, 3, 3, paint);
            canvas.drawLine(9, 8, 15, 8, paint); canvas.drawLine(9, 12, 15, 12, paint); canvas.drawLine(9, 16, 12, 16, paint);
        }
        canvas.restore();
    }
}
