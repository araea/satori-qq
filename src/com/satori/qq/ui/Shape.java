package com.satori.qq.ui;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;

/**
 * 组件的形状层：填充、描边、键盘焦点环与按压形变，四个角可各自取值。
 *
 * <p>两种几何，按用途分工而不是混用：
 * <ul>
 *   <li><b>连续曲率角</b>（{@code smooth}）——卡片、分段列表、弹窗这类大容器。M3E 只规定圆角半径，
 *       不规定曲率，Miuix 的连续曲率在这里作视觉精修，与 M3E 不冲突；</li>
 *   <li><b>圆弧角</b>——按钮、开关、胶囊。半径取 {@link #FULL} 时按高度取半，按下时向
 *       {@code pressedRadius} 形变（M3E 的形状形变）。</li>
 * </ul>
 * 焦点环画在形状内侧：外扩会被父容器裁掉。环只在键盘/方向键焦点下出现，触摸不会给按钮焦点。
 */
final class Shape extends Drawable {
    /** 半径取高度的一半（胶囊）。 */
    static final float FULL = -1;

    /** iOS/Miuix 连续角的贝塞尔参数：一个半径 r 的角沿边占用 1.5287r，拆成三段三次曲线。 */
    private static final float EXTENT = 1.52866483f;
    private static final float[] CURVE = {
            1.08849323f, 0, 0.86840689f, 0, 0.63149399f, 0.07491100f,
            0.37282392f, 0.16905899f, 0.16905899f, 0.37282392f, 0.07491100f, 0.63149399f,
            0, 0.86840689f, 0, 1.08849323f, 0, EXTENT,
    };

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF box = new RectF();
    private final float[] radii = new float[4]; // 左上、右上、右下、左下
    private final float[] resolved = new float[4];
    private final boolean smooth;
    private float pressedRadius = FULL;
    private float morph;
    private int fill;
    private int stroke;
    private float strokeWidth;
    private int ringColor;
    private float ringWidth;
    private boolean focused;
    private boolean dirty = true;

    Shape(boolean smooth, float radius) {
        this.smooth = smooth;
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        setRadius(radius);
    }

    static Shape smooth(int fill, float radius) {
        return new Shape(true, radius).fill(fill);
    }

    static Shape round(int fill, float radius) {
        return new Shape(false, radius).fill(fill);
    }

    Shape fill(int color) {
        if (fill != color) {
            fill = color;
            invalidateSelf();
        }
        return this;
    }

    int fillColor() {
        return fill;
    }

    Shape stroke(int color, float width) {
        stroke = color;
        strokeWidth = width;
        invalidateSelf();
        return this;
    }

    Shape ring(int color, float width) {
        ringColor = color;
        ringWidth = width;
        return this;
    }

    Shape setRadius(float radius) {
        return setRadii(radius, radius, radius, radius);
    }

    Shape setRadii(float topStart, float topEnd, float bottomEnd, float bottomStart) {
        radii[0] = topStart;
        radii[1] = topEnd;
        radii[2] = bottomEnd;
        radii[3] = bottomStart;
        dirty = true;
        invalidateSelf();
        return this;
    }

    Shape pressedRadius(float radius) {
        pressedRadius = radius;
        return this;
    }

    /** 0 = 静止形状，1 = 按下形状。由调用方用弹簧驱动。 */
    void morph(float value) {
        if (morph == value) return;
        morph = value;
        dirty = true;
        invalidateSelf();
    }

    @Override protected void onBoundsChange(Rect bounds) {
        dirty = true;
    }

    @Override public boolean isStateful() {
        return ringWidth > 0;
    }

    @Override protected boolean onStateChange(int[] state) {
        boolean now = false;
        for (int value : state) {
            if (value == android.R.attr.state_focused) now = true;
        }
        if (now == focused) return false;
        focused = now;
        invalidateSelf();
        return true;
    }

    private void rebuild() {
        Rect b = getBounds();
        box.set(b);
        float w = box.width();
        float h = box.height();
        float full = Math.min(w, h) / 2f;
        for (int i = 0; i < 4; i++) {
            float rest = radii[i] == FULL ? full : radii[i];
            float r = pressedRadius == FULL ? rest : rest + (pressedRadius - rest) * morph;
            resolved[i] = Math.max(0, Math.min(r, full));
        }
        path.reset();
        if (!smooth) {
            path.addRoundRect(box, new float[]{
                    resolved[0], resolved[0], resolved[1], resolved[1],
                    resolved[2], resolved[2], resolved[3], resolved[3]}, Path.Direction.CW);
        } else {
            smoothPath(w, h);
        }
        dirty = false;
    }

    /** 每条边上两个角的占用之和不能超过边长，超了就按比例一起缩。 */
    private void smoothPath(float w, float h) {
        float[] scale = {1, 1, 1, 1};
        float[][] edges = {{0, 1, w}, {1, 2, h}, {2, 3, w}, {3, 0, h}};
        for (float[] edge : edges) {
            int a = (int) edge[0];
            int b = (int) edge[1];
            float used = (resolved[a] + resolved[b]) * EXTENT;
            if (used > edge[2] && used > 0) {
                float factor = edge[2] / used;
                scale[a] = Math.min(scale[a], factor);
                scale[b] = Math.min(scale[b], factor);
            }
        }
        float l = box.left, t = box.top, r = box.right, btm = box.bottom;
        // 角点、沿入边方向、沿出边方向（顺时针）
        float[][] corners = {
                {r, t, 1, 0, 0, 1},
                {r, btm, 0, 1, -1, 0},
                {l, btm, -1, 0, 0, -1},
                {l, t, 0, -1, 1, 0},
        };
        int[] order = {1, 2, 3, 0};
        float r0 = resolved[0] * scale[0];
        path.moveTo(l + r0 * EXTENT, t);
        for (int k = 0; k < 4; k++) {
            int index = order[k];
            float radius = resolved[index] * scale[index];
            float[] c = corners[k];
            float cx = c[0], cy = c[1], ux = c[2], uy = c[3], vx = c[4], vy = c[5];
            path.lineTo(cx - EXTENT * radius * ux, cy - EXTENT * radius * uy);
            if (radius <= 0) continue;
            for (int s = 0; s < CURVE.length; s += 6) {
                path.cubicTo(
                        cx - CURVE[s] * radius * ux + CURVE[s + 1] * radius * vx,
                        cy - CURVE[s] * radius * uy + CURVE[s + 1] * radius * vy,
                        cx - CURVE[s + 2] * radius * ux + CURVE[s + 3] * radius * vx,
                        cy - CURVE[s + 2] * radius * uy + CURVE[s + 3] * radius * vy,
                        cx - CURVE[s + 4] * radius * ux + CURVE[s + 5] * radius * vx,
                        cy - CURVE[s + 4] * radius * uy + CURVE[s + 5] * radius * vy);
            }
        }
        path.close();
    }

    Path path() {
        if (dirty) rebuild();
        return path;
    }

    @Override public void draw(Canvas canvas) {
        Path shape = path();
        if ((fill >>> 24) != 0) {
            fillPaint.setColor(fill);
            canvas.drawPath(shape, fillPaint);
        }
        if (strokeWidth > 0 && (stroke >>> 24) != 0) {
            drawInset(canvas, shape, stroke, strokeWidth);
        }
        if (focused && ringWidth > 0) {
            drawInset(canvas, shape, ringColor, ringWidth);
        }
    }

    /** 描边整条画在形状内侧：先按形状裁剪，再画两倍宽的线，外侧那一半被裁掉。 */
    private void drawInset(Canvas canvas, Path shape, int color, float width) {
        strokePaint.setColor(color);
        strokePaint.setStrokeWidth(width * 2);
        int save = canvas.save();
        canvas.clipPath(shape);
        canvas.drawPath(shape, strokePaint);
        canvas.restoreToCount(save);
    }

    @Override public void getOutline(Outline outline) {
        Path shape = path();
        if (Build.VERSION.SDK_INT >= 30) outline.setPath(shape);
        else if (shape.isConvex()) outline.setConvexPath(shape);
        else outline.setRoundRect(getBounds(), Math.max(resolved[0], resolved[2]));
    }

    @Override public void setAlpha(int alpha) {}

    @Override public void setColorFilter(ColorFilter filter) {}

    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    /** 与本形状同几何的不透明遮罩，给波纹裁边用（透明底的按钮也需要）。 */
    Drawable mask() {
        final Shape owner = this;
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override public void draw(Canvas canvas) {
                paint.setColor(0xFF000000);
                canvas.drawPath(owner.path(), paint);
            }

            @Override public void setAlpha(int alpha) {}

            @Override public void setColorFilter(ColorFilter filter) {}

            @Override public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }

    /** 带状态层（波纹、悬停、按压）的背景：底是本形状，波纹按同一几何裁剪。 */
    RippleDrawable pressable(int stateLayer) {
        return new RippleDrawable(ColorStateList.valueOf(stateLayer), this, mask());
    }
}
