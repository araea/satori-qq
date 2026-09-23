package com.satori.qq.ui;

import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 文本输入：标签常驻在上、辅助说明在下，错误就地显示（Carbon 表单模式，外观走 M3 描边输入框）。
 *
 * <p>标签用 {@code labelFor} 关联，读屏聚焦输入框时读出名称；错误同时写进 {@code setError}，
 * 读屏会播报，并且描边加粗换色 + 前置错误图标 + 文字三重表达，不只靠颜色（WCAG 1.4.1 / 3.3.1）。
 * 焦点态描边 2dp 主色，对表面对比度 ≥ 3:1（WCAG 2.4.7 / 1.4.11）。
 */
final class Field extends LinearLayout {
    final EditText input;
    private final Tokens t;
    private final TextView helper;
    private final Icon errorIcon;
    private final Shape frame;
    private String hint = "";
    private boolean failed;
    private ImageButton trailing;

    Field(Tokens tokens, String label, String placeholder, boolean secret) {
        super(tokens.context);
        this.t = tokens;
        setOrientation(VERTICAL);

        TextView name = tokens.type(new TextView(tokens.context), Tokens.TITLE_SMALL);
        name.setTextColor(tokens.onSurface);
        name.setText(label);
        addView(name, new LayoutParams(-1, -2));

        FrameLayout box = new FrameLayout(tokens.context);
        input = tokens.type(new EditText(tokens.context), Tokens.BODY_LARGE);
        input.setId(View.generateViewId());
        name.setLabelFor(input.getId());
        input.setSaveEnabled(false);
        input.setSingleLine(true);
        input.setTextColor(tokens.onSurface);
        input.setHintTextColor(tokens.onSurfaceVariant);
        input.setHint(placeholder);
        input.setMinimumHeight(tokens.field);
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setPaddingRelative(tokens.spaceLg, tokens.spaceMd, tokens.spaceLg, tokens.spaceMd);
        if (secret) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            input.setTransformationMethod(PasswordTransformationMethod.getInstance());
            input.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            input.setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO);
        } else {
            input.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        }
        frame = Shape.round(0, tokens.shapeXs);
        input.setBackground(frame);
        input.setOnFocusChangeListener((v, focused) -> restyle());
        box.addView(input, new FrameLayout.LayoutParams(-1, -2));
        LayoutParams boxParams = new LayoutParams(-1, -2);
        boxParams.topMargin = tokens.spaceSm;
        addView(box, boxParams);

        LinearLayout line = new LinearLayout(tokens.context);
        line.setOrientation(HORIZONTAL);
        errorIcon = new Icon(tokens, Icon.ERROR, tokens.error);
        ImageView mark = new ImageView(tokens.context);
        mark.setImageDrawable(errorIcon);
        mark.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        mark.setVisibility(GONE);
        LayoutParams markParams = new LayoutParams(tokens.dp(16), tokens.dp(16));
        markParams.setMarginEnd(tokens.spaceXs);
        markParams.topMargin = tokens.dp(0.5f);
        line.addView(mark, markParams);
        helper = tokens.type(new TextView(tokens.context), Tokens.BODY_SMALL);
        helper.setTextColor(tokens.onSurfaceVariant);
        line.addView(helper, new LayoutParams(0, -2, 1));
        LayoutParams lineParams = new LayoutParams(-1, -2);
        lineParams.topMargin = tokens.spaceXs;
        lineParams.setMarginStart(tokens.spaceLg);
        addView(line, lineParams);
        restyle();
    }

    /** 尾部图标按钮（例如令牌的显示/隐藏），放在输入框内侧。 */
    void trailing(ImageButton button) {
        trailing = button;
        FrameLayout box = (FrameLayout) input.getParent();
        box.addView(button, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        input.setPaddingRelative(t.spaceLg, t.spaceMd, t.touchTarget + t.spaceXs, t.spaceMd);
    }

    void hint(String text) {
        hint = text == null ? "" : text;
        if (!failed) helper.setText(hint);
    }

    String text() {
        return input.getText().toString();
    }

    void setText(String value) {
        if (!input.getText().toString().equals(value)) {
            input.setText(value);
            input.setSelection(input.length());
        }
    }

    /** 显示或清除错误。显示时把焦点移到输入框并滚入可见区域。 */
    void error(String message) {
        failed = message != null;
        View mark = ((LinearLayout) helper.getParent()).getChildAt(0);
        mark.setVisibility(failed ? VISIBLE : GONE);
        input.setError(failed ? message : null, null);
        helper.setText(failed ? message : hint);
        helper.setTextColor(failed ? t.error : t.onSurfaceVariant);
        if (failed) {
            input.requestFocus();
            input.post(() -> input.requestRectangleOnScreen(
                    new android.graphics.Rect(0, 0, input.getWidth(), getHeight()), false));
        }
        restyle();
    }

    private void restyle() {
        boolean focused = input.hasFocus();
        frame.stroke(failed ? t.error : (focused ? t.primary : t.outline),
                failed || focused ? t.focusRing : t.border);
    }
}
