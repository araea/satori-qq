package com.satori.qq.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 顶部应用栏（M3E 可伸缩大标题）：页面顶端是内容里的大标题，向上滚过它之后，栏里的小标题淡入、
 * 栏底色升一级表面（surface → surface_container），提示内容在栏下方滚动。HIG 的大标题行为与此一致。
 *
 * <p>栏自己吃掉状态栏高度（edge-to-edge）；小标题不进无障碍树——页面里的大标题才是读屏的标题。
 */
final class TopBar extends LinearLayout {
    private static final ArgbEvaluator ARGB = new ArgbEvaluator();

    private final Tokens t;
    private final TextView title;
    private final LinearLayout actions;
    private ValueAnimator tint;
    private float lifted;
    private boolean liftedTarget;
    private int inset;
    private final int base, raised;

    TopBar(Ui ui, String text, int base, int raised) {
        super(ui.t.context);
        this.t = ui.t;
        this.base = base;
        this.raised = raised;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPaddingRelative(t.spaceXs, 0, t.spaceXs, 0);
        setMinimumHeight(t.topBar);
        setBackgroundColor(base);
        title = ui.text(text, Tokens.TITLE_LARGE, t.onSurface);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        title.setAlpha(0f);
        title.setPaddingRelative(t.spaceMd, 0, t.spaceSm, 0);
        addView(title, new LayoutParams(0, -2, 1));
        actions = ui.row();
        addView(actions, new LayoutParams(-2, -2));
    }

    /** 导航图标（返回）。 */
    void navigation(ImageButton button) {
        addView(button, 0, new LayoutParams(-2, -2));
        title.setPaddingRelative(t.spaceXs, 0, t.spaceSm, 0);
    }

    void action(View button) {
        actions.addView(button, new LayoutParams(-2, -2));
    }

    void setTopInset(int top) {
        if (inset == top) return;
        inset = top;
        setPadding(getPaddingLeft(), top, getPaddingRight(), 0);
        setMinimumHeight(t.topBar + top);
    }

    /** 让小标题与底色跟随滚动：大标题滚出栏下沿时小标题淡入。 */
    void follow(ScrollView scroll, View largeTitle) {
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> update(y, largeTitle));
    }

    private void update(int scrollY, View largeTitle) {
        float travel = Math.max(1, largeTitle.getBottom());
        title.setAlpha(Math.max(0, Math.min(1, (scrollY - largeTitle.getTop()) / (travel - largeTitle.getTop()))));
        boolean lift = scrollY > 0;
        if (lift == liftedTarget) return;
        liftedTarget = lift;
        if (tint != null) tint.cancel();
        float target = lift ? 1 : 0;
        if (!Spring.enabled()) {
            paint(target);
            return;
        }
        tint = t.effectsDefault.apply(ValueAnimator.ofFloat(lifted, target));
        tint.addUpdateListener(a -> paint((float) a.getAnimatedValue()));
        tint.start();
    }

    private void paint(float value) {
        lifted = value;
        setBackgroundColor((Integer) ARGB.evaluate(value, base, raised));
    }

    @Override protected void onDetachedFromWindow() {
        if (tint != null) tint.cancel();
        super.onDetachedFromWindow();
    }
}
