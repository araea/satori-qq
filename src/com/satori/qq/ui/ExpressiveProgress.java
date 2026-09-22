package com.satori.qq.ui;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Indeterminate wavy progress; no animation when hidden or system animations are disabled. */
final class ExpressiveProgress extends View {
    private final Tokens ui;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wave = new Path();
    private ValueAnimator motion;
    private float phase;

    ExpressiveProgress(Tokens tokens) {
        super(tokens.activity);
        this.ui = tokens;
        setMinimumHeight(tokens.dimen("size_progress_height"));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(tokens.dp(3));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float middle = getHeight() / 2f;
        float amplitude = ui.dp(2);
        float wavelength = ui.dp(24);
        wave.reset();
        for (int x = ui.dp(2); x <= getWidth() - ui.dp(2); x += 2) {
            float y = middle + amplitude * (float) Math.sin(x / wavelength * Math.PI * 2 - phase * Math.PI * 2);
            if (x == ui.dp(2)) wave.moveTo(x, y); else wave.lineTo(x, y);
        }
        paint.setColor(ui.outline);
        paint.setAlpha(48);
        canvas.drawPath(wave, paint);
        paint.setColor(ui.primary);
        paint.setAlpha(255);
        float width = getWidth() * 0.4f;
        float start = motion != null && motion.isRunning() ? (getWidth() + width) * phase - width : 0;
        canvas.save();
        canvas.clipRect(start, 0, start + width, getHeight());
        canvas.drawPath(wave, paint);
        canvas.restore();
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        if (motion != null) { motion.cancel(); motion = null; }
        if (visible && ValueAnimator.areAnimatorsEnabled()) {
            motion = ValueAnimator.ofFloat(0, 1);
            motion.setDuration(1600);
            motion.setRepeatCount(ValueAnimator.INFINITE);
            motion.setInterpolator(new LinearInterpolator());
            motion.addUpdateListener(value -> { phase = (float) value.getAnimatedValue(); invalidate(); });
            motion.start();
        }
    }

    @Override protected void onDetachedFromWindow() {
        if (motion != null) { motion.cancel(); motion = null; }
        super.onDetachedFromWindow();
    }
}
