package com.satori.qq.ui;

import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.widget.Button;

/**
 * M3E 按钮：胶囊外形，按下时向较方的圆角形变并带回弹（表现力弹簧），松手弹回。
 *
 * <p>五种强调层级：填充（每屏唯一的主操作）、色调、描边、文字、危险（错误色文字，只用在确认弹窗与
 * 破坏性的列表行里，破坏性动作永远先确认）。标签可换行，放大字号时按钮长高而不截断；高度与
 * 最小宽度都不低于 48dp。系统关闭动画时不做形变，只剩波纹与颜色反馈。
 */
final class Btn extends Button {
    static final int FILLED = 0;
    static final int TONAL = 1;
    static final int OUTLINED = 2;
    static final int TEXT = 3;
    static final int DANGER = 4;

    private final Tokens t;
    private final int style;
    private final Shape shape;
    private Icon icon;
    private ValueAnimator motion;
    private float morph;

    Btn(Tokens tokens, String label, int style, boolean prominent) {
        super(tokens.context);
        this.t = tokens;
        this.style = style;
        setText(label);
        tokens.type(this, Tokens.LABEL_LARGE);
        setAllCaps(false);
        setSingleLine(false);
        setGravity(Gravity.CENTER);
        setStateListAnimator(null);
        int height = prominent ? tokens.buttonProminent : tokens.button;
        setMinHeight(height);
        setMinimumHeight(height);
        setMinWidth(tokens.touchTarget);
        setMinimumWidth(tokens.touchTarget);
        int side = style == TEXT || style == DANGER ? tokens.spaceMd : (prominent ? tokens.spaceXl : tokens.spaceLg);
        setPadding(side, tokens.spaceSm, side, tokens.spaceSm);
        setCompoundDrawablePadding(tokens.spaceSm);

        shape = Shape.round(container(), Shape.FULL)
                .pressedRadius(prominent ? tokens.shapeLg : tokens.shapeMd)
                .ring(ring(), tokens.focusRing);
        if (style == OUTLINED) shape.stroke(tokens.outline, tokens.border);
        setBackground(shape.pressable(Tokens.alpha(content(), tokens.pressedPct)));
        setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{Tokens.alpha(tokens.onSurface, tokens.disabledContentPct), content()}));
    }

    /** 前置图标：颜色跟随文字与启用状态。 */
    Btn icon(int kind) {
        icon = new Icon(t, kind, content());
        icon.setBounds(0, 0, t.dp(18), t.dp(18));
        setCompoundDrawablesRelative(icon, null, null, null);
        refreshIcon();
        return this;
    }

    /** 连接按钮组：内侧角取小圆角，外侧保持胶囊。 */
    void corners(float topLeft, float topRight, float bottomRight, float bottomLeft) {
        shape.setRadii(topLeft, topRight, bottomRight, bottomLeft);
    }

    private int container() {
        switch (style) {
            case FILLED: return t.primary;
            case TONAL: return t.secondaryContainer;
            default: return 0;
        }
    }

    private int content() {
        switch (style) {
            case FILLED: return t.onPrimary;
            case TONAL: return t.onSecondaryContainer;
            case DANGER: return t.error;
            default: return t.primary;
        }
    }

    private int ring() {
        return style == FILLED ? t.onPrimary : (style == DANGER ? t.error : t.primary);
    }

    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (shape == null) return;
        int fill = container();
        if (fill != 0) shape.fill(enabled ? fill : Tokens.alpha(t.onSurface, t.disabledContainerPct));
        if (style == OUTLINED) shape.stroke(enabled ? t.outline : Tokens.alpha(t.onSurface, t.disabledContainerPct), t.border);
        refreshIcon();
    }

    private void refreshIcon() {
        if (icon != null) icon.tint(isEnabled() ? content() : Tokens.alpha(t.onSurface, t.disabledContentPct));
    }

    @Override public void setPressed(boolean pressed) {
        if (isPressed() == pressed) {
            super.setPressed(pressed);
            return;
        }
        super.setPressed(pressed);
        animateMorph(pressed ? 1f : 0f);
    }

    private void animateMorph(float target) {
        if (motion != null) motion.cancel();
        if (!Spring.enabled()) {
            morph = target;
            shape.morph(target);
            return;
        }
        motion = t.expressiveFast.apply(ValueAnimator.ofFloat(morph, target));
        motion.addUpdateListener(animation -> {
            morph = (float) animation.getAnimatedValue();
            shape.morph(morph);
        });
        motion.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (motion != null) motion.cancel();
        super.onDetachedFromWindow();
    }

    @Override public CharSequence getAccessibilityClassName() {
        return Button.class.getName();
    }
}
