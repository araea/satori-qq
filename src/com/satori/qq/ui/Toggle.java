package com.satori.qq.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * M3E 开关的图形部分：轨道 + 把手。关时把手小而空心色，开时长大、填 on_primary 并带对勾，
 * 按下时再大一圈。位置与尺寸走空间弹簧，颜色走效果弹簧。
 *
 * <p>只负责画。点击、焦点与读屏语义由所在的整行 {@link Item} 承担，这个视图不可聚焦、
 * 不进无障碍树——否则读屏会在同一行里读到两个开关。
 */
final class Toggle extends View {
    private static final ArgbEvaluator ARGB = new ArgbEvaluator();

    private final Tokens t;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Icon check;
    private float position;
    private float press;
    private boolean checked;
    private boolean enabledLook = true;
    private ValueAnimator slide, squeeze;

    Toggle(Tokens tokens) {
        super(tokens.context);
        this.t = tokens;
        this.check = new Icon(tokens, Icon.CHECK, tokens.primary);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
        setClickable(false);
        setDuplicateParentStateEnabled(true);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(t.switchWidth, t.switchHeight);
    }

    void setChecked(boolean value, boolean animate) {
        if (checked == value && (slide == null || !slide.isRunning())) {
            position = value ? 1 : 0;
            invalidate();
            return;
        }
        checked = value;
        if (slide != null) slide.cancel();
        float target = value ? 1 : 0;
        if (!animate || !Spring.enabled() || !isAttachedToWindow()) {
            position = target;
            invalidate();
            return;
        }
        slide = t.spatialFast.apply(ValueAnimator.ofFloat(position, target));
        slide.addUpdateListener(a -> {
            position = (float) a.getAnimatedValue();
            invalidate();
        });
        slide.start();
    }

    void setEnabledLook(boolean enabled) {
        enabledLook = enabled;
        invalidate();
    }

    @Override protected void drawableStateChanged() {
        super.drawableStateChanged();
        boolean pressed = false;
        for (int state : getDrawableState()) if (state == android.R.attr.state_pressed) pressed = true;
        float target = pressed ? 1 : 0;
        if (press == target && (squeeze == null || !squeeze.isRunning())) return;
        if (squeeze != null) squeeze.cancel();
        if (!Spring.enabled() || !isAttachedToWindow()) {
            press = target;
            invalidate();
            return;
        }
        squeeze = t.spatialFast.apply(ValueAnimator.ofFloat(press, target));
        squeeze.addUpdateListener(a -> {
            press = (float) a.getAnimatedValue();
            invalidate();
        });
        squeeze.start();
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float p = Math.max(0, Math.min(1, position));
        int trackOff = t.surfaceContainerHighest;
        int trackOn = t.primary;
        int handleOff = t.outline;
        int handleOn = t.onPrimary;
        int track = (Integer) ARGB.evaluate(p, trackOff, trackOn);
        int handle = (Integer) ARGB.evaluate(p, handleOff, handleOn);
        int border = (Integer) ARGB.evaluate(p, t.outline, trackOn);
        if (!enabledLook) {
            track = Tokens.alpha(t.onSurface, p > 0.5f ? t.disabledContainerPct : 0);
            border = Tokens.alpha(t.onSurface, t.disabledContainerPct);
            handle = p > 0.5f ? t.surface : Tokens.alpha(t.onSurface, t.disabledContentPct);
        }

        rect.set(0, 0, w, h);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(track);
        canvas.drawRoundRect(rect, h / 2, h / 2, paint);
        float stroke = t.dp(2);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setColor(border);
        rect.inset(stroke / 2, stroke / 2);
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint);

        float size = t.handleOff + (t.handleOn - t.handleOff) * p;
        size += (t.handlePressed - size) * press;
        float cx = h / 2 + (w - h) * p;
        float cy = h / 2;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(handle);
        canvas.drawCircle(cx, cy, size / 2, paint);

        if (p > 0.5f) {
            int iconSize = t.dp(16);
            check.tint(enabledLook ? t.primary : Tokens.alpha(t.onSurface, t.disabledContentPct));
            check.setAlpha(Math.round(255 * Math.min(1, (p - 0.5f) * 2)));
            check.setBounds(Math.round(cx - iconSize / 2f), Math.round(cy - iconSize / 2f),
                    Math.round(cx + iconSize / 2f), Math.round(cy + iconSize / 2f));
            check.draw(canvas);
        }
    }

    @Override protected void onDetachedFromWindow() {
        if (slide != null) slide.cancel();
        if (squeeze != null) squeeze.cancel();
        super.onDetachedFromWindow();
    }
}
