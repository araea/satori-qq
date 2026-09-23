package com.satori.qq.ui;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * M3E 加载指示器：一枚在几种「饼干」形状之间形变、同时旋转的实心图形，用于时长未知的首次检查。
 *
 * <p>形变做成连续的：先把当前形状的起伏收到零（圆），再换瓣数展开，所以任意时刻都不跳变。
 * 只在可见且系统允许动画时运行，隐藏或脱离窗口立刻停；关闭动画时画一枚静止的形状。
 * 纯装饰，语义由旁边的状态文字承担。
 */
final class LoadingIndicator extends View {
    private static final int[] LOBES = {7, 5, 9, 4, 6};
    private static final long CYCLE = 650;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private ValueAnimator motion;
    private float progress;

    LoadingIndicator(Tokens tokens, int color) {
        super(tokens.context);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setMinimumWidth(tokens.loading);
        setMinimumHeight(tokens.loading);
    }

    void tint(int color) {
        paint.setColor(color);
        invalidate();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), widthSpec),
                resolveSize(getSuggestedMinimumHeight(), heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        float size = Math.min(getWidth(), getHeight());
        if (size <= 0) return;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = size * 0.38f;
        int step = (int) Math.floor(progress);
        float phase = progress - step;
        int lobes = LOBES[step % LOBES.length];
        // 前半段展开、后半段收拢；收拢到圆的那一刻换瓣数。
        float amplitude = 0.14f * (float) Math.sin(Math.PI * phase);
        if (motion == null) amplitude = 0.12f;
        float rotation = (float) (progress * Math.PI * 2 / 3);
        shape.reset();
        for (int i = 0; i <= 96; i++) {
            double theta = Math.PI * 2 * i / 96;
            double r = radius * (1 + amplitude * Math.cos(lobes * theta));
            float x = cx + (float) (r * Math.cos(theta + rotation));
            float y = cy + (float) (r * Math.sin(theta + rotation));
            if (i == 0) shape.moveTo(x, y);
            else shape.lineTo(x, y);
        }
        shape.close();
        canvas.drawPath(shape, paint);
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        stop();
        if (visible && Spring.enabled()) {
            motion = ValueAnimator.ofFloat(0, LOBES.length);
            motion.setDuration(CYCLE * LOBES.length);
            motion.setRepeatCount(ValueAnimator.INFINITE);
            motion.setInterpolator(new LinearInterpolator());
            motion.addUpdateListener(value -> {
                progress = (float) value.getAnimatedValue();
                invalidate();
            });
            motion.start();
        }
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    private void stop() {
        if (motion != null) {
            motion.cancel();
            motion = null;
        }
    }
}
