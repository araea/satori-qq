package com.satori.qq.ui;

import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 列表项：前置图标 + 标题 + 辅助文字 + 尾部（开关 / 箭头 / 自定义视图）。分段列表的一格。
 *
 * <p>三种模式：
 * <ul>
 *   <li>{@link #STATIC}——只展示，不可聚焦；</li>
 *   <li>{@link #ACTION}——整行是一个按钮，读屏按按钮读；</li>
 *   <li>{@link #SWITCH}——整行是一个开关：点击整行切换，读屏读作「开关，已开启/已关闭」，
 *       标题与说明一并朗读。开关图形本身不进无障碍树。</li>
 * </ul>
 * 最小高度单行 56dp、双行 72dp，任何模式下点击区域都是整行（远超 48dp）。
 */
final class Item extends LinearLayout {
    static final int STATIC = 0;
    static final int ACTION = 1;
    static final int SWITCH = 2;

    interface OnToggle {
        void toggled(Item item, boolean checked);
    }

    final TextView headline;
    final TextView supporting;
    private final Tokens t;
    private final int mode;
    private final Shape shape;
    private Icon leadingIcon;
    private int leadingColor;
    private Toggle toggle;
    private Icon trailingIcon;
    private boolean checked;
    private boolean destructive;
    private OnToggle onToggle;

    Item(Tokens tokens, int mode, String title, String detail) {
        super(tokens.context);
        this.t = tokens;
        this.mode = mode;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBaselineAligned(false);
        setPadding(tokens.spaceLg, tokens.spaceMd, tokens.spaceLg, tokens.spaceMd);
        setMinimumHeight(detail == null ? tokens.listOneLine : tokens.listTwoLine);

        LinearLayout words = new LinearLayout(tokens.context);
        words.setOrientation(VERTICAL);
        headline = tokens.type(new TextView(tokens.context), Tokens.BODY_LARGE);
        headline.setTextColor(tokens.onSurface);
        headline.setText(title);
        words.addView(headline, new LayoutParams(-1, -2));
        supporting = tokens.type(new TextView(tokens.context), Tokens.BODY_MEDIUM);
        supporting.setTextColor(tokens.onSurfaceVariant);
        setSupporting(detail);
        words.addView(supporting, new LayoutParams(-1, -2));
        addView(words, new LayoutParams(0, -2, 1));

        shape = Shape.smooth(tokens.surfaceContainer, tokens.shapeXs).ring(tokens.primary, tokens.focusRing);
        if (mode == STATIC) {
            setBackground(shape);
        } else {
            setBackground(shape.pressable(Tokens.alpha(tokens.onSurface, tokens.pressedPct)));
            setClickable(true);
            setFocusable(true);
        }
        if (mode == SWITCH) {
            toggle = new Toggle(tokens);
            LayoutParams params = new LayoutParams(-2, -2);
            params.setMarginStart(tokens.spaceLg);
            addView(toggle, params);
            if (Build.VERSION.SDK_INT >= 30) setStateDescription("已关闭");
            super.setOnClickListener(v -> {
                boolean next = !checked;
                setChecked(next, true);
                if (onToggle != null) onToggle.toggled(this, next);
            });
        }
    }

    Item leading(int kind, int color) {
        leadingColor = color;
        if (leadingIcon == null) {
            leadingIcon = new Icon(t, kind, color);
            ImageView view = new ImageView(t.context);
            view.setImageDrawable(leadingIcon);
            view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            LayoutParams params = new LayoutParams(t.icon, t.icon);
            params.setMarginEnd(t.spaceLg);
            addView(view, 0, params);
        } else if (leadingIcon.kind() != kind) {
            ImageView view = (ImageView) getChildAt(0);
            leadingIcon = new Icon(t, kind, color);
            view.setImageDrawable(leadingIcon);
        } else {
            leadingIcon.tint(color);
        }
        return this;
    }

    /** 尾部箭头：表示"进入下一层"。 */
    Item chevron() {
        trailingIcon = new Icon(t, Icon.NEXT, t.onSurfaceVariant);
        ImageView view = new ImageView(t.context);
        view.setImageDrawable(trailingIcon);
        view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams params = new LayoutParams(t.icon, t.icon);
        params.setMarginStart(t.spaceLg);
        addView(view, params);
        return this;
    }

    /** 尾部自定义视图（例如复制用的图标按钮）。 */
    Item trailing(View view) {
        LayoutParams params = new LayoutParams(-2, -2);
        params.setMarginStart(t.spaceSm);
        setPaddingRelative(getPaddingStart(), getPaddingTop(), t.spaceXs, getPaddingBottom());
        addView(view, params);
        return this;
    }

    /** 破坏性动作：标题与图标用错误色；外观可辨，但动作仍须确认。 */
    Item destructive() {
        destructive = true;
        headline.setTextColor(t.error);
        if (leadingIcon != null) leadingIcon.tint(t.error);
        leadingColor = t.error;
        return this;
    }

    void setSupporting(CharSequence text) {
        if (TextUtils.equals(supporting.getText(), text == null ? "" : text)) return;
        supporting.setText(text == null ? "" : text);
        supporting.setVisibility(TextUtils.isEmpty(text) ? GONE : VISIBLE);
    }

    void setHeadline(CharSequence text) {
        if (!TextUtils.equals(headline.getText(), text)) headline.setText(text);
    }

    void setOnToggle(OnToggle listener) {
        onToggle = listener;
    }

    boolean isChecked() {
        return checked;
    }

    void setChecked(boolean value, boolean animate) {
        boolean changed = checked != value;
        checked = value;
        if (toggle != null) toggle.setChecked(value, animate);
        if (changed && Build.VERSION.SDK_INT >= 30) setStateDescription(value ? "已开启" : "已关闭");
    }

    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (headline == null) return;
        int ink = enabled ? (destructive ? t.error : t.onSurface) : Tokens.alpha(t.onSurface, t.disabledContentPct);
        headline.setTextColor(ink);
        supporting.setTextColor(enabled ? t.onSurfaceVariant : Tokens.alpha(t.onSurface, t.disabledContentPct));
        if (leadingIcon != null) {
            leadingIcon.tint(enabled ? leadingColor : Tokens.alpha(t.onSurface, t.disabledContentPct));
        }
        if (trailingIcon != null) {
            trailingIcon.tint(enabled ? t.onSurfaceVariant : Tokens.alpha(t.onSurface, t.disabledContentPct));
        }
        if (toggle != null) toggle.setEnabledLook(enabled);
    }

    /** 分段列表里的位置决定四个角：组首尾取大圆角，中间取小圆角。 */
    void corners(float top, float bottom) {
        shape.setRadii(top, top, bottom, bottom);
    }

    @Override public CharSequence getAccessibilityClassName() {
        if (mode == SWITCH) return "android.widget.Switch";
        if (mode == ACTION) return "android.widget.Button";
        return super.getAccessibilityClassName();
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (mode == SWITCH) {
            info.setCheckable(true);
            info.setChecked(checked);
        }
    }
}
