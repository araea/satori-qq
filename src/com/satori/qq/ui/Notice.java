package com.satori.qq.ui;

import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 行内通知：图标 + 结论 + 说明，可带一个动作。结构取 Carbon 的 inline notification，
 * 外观是 M3E 的色调容器——状态色只加强，结论文字本身已说清楚（WCAG 1.4.1）。
 * 内容整块是 polite live region，状态变化会被读屏朗读一次（WCAG 4.1.3）。
 */
final class Notice extends LinearLayout {
    private final Tokens t;
    private final Shape shape;
    private final Icon icon;
    private final TextView title;
    private final TextView body;
    private final LinearLayout words;
    private Btn action;
    private int tone = -1;

    Notice(Tokens tokens) {
        super(tokens.context);
        this.t = tokens;
        setOrientation(HORIZONTAL);
        setBaselineAligned(false);
        setPadding(tokens.spaceLg, tokens.spaceLg, tokens.spaceLg, tokens.spaceLg);
        shape = Shape.smooth(tokens.surfaceContainerHigh, tokens.shapeLg);
        setBackground(shape);

        icon = new Icon(tokens, Icon.INFO, tokens.onSurface);
        ImageView mark = new ImageView(tokens.context);
        mark.setImageDrawable(icon);
        mark.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams markParams = new LayoutParams(tokens.icon, tokens.icon);
        markParams.setMarginEnd(tokens.spaceMd);
        addView(mark, markParams);

        words = new LinearLayout(tokens.context);
        words.setOrientation(VERTICAL);
        words.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        title = tokens.type(new TextView(tokens.context), Tokens.TITLE_SMALL);
        words.addView(title, new LayoutParams(-1, -2));
        body = tokens.type(new TextView(tokens.context), Tokens.BODY_MEDIUM);
        LayoutParams bodyParams = new LayoutParams(-1, -2);
        bodyParams.topMargin = tokens.spaceXs;
        words.addView(body, bodyParams);
        addView(words, new LayoutParams(0, -2, 1));
    }

    /** 可选动作按钮，放在说明下方、与文字左对齐。 */
    Btn action(String label, View.OnClickListener listener) {
        action = new Btn(t, label, Btn.TEXT, false);
        action.setOnClickListener(listener);
        LayoutParams params = new LayoutParams(-2, -2);
        params.topMargin = t.spaceXs;
        params.setMarginStart(-t.spaceMd);
        words.addView(action, params);
        return action;
    }

    void show(int tone, String heading, String detail, boolean withAction) {
        if (this.tone != tone) {
            this.tone = tone;
            int fill, ink, kind;
            switch (tone) {
                case Status.SUCCESS: fill = t.successContainer; ink = t.onSuccessContainer; kind = Icon.OK; break;
                case Status.WARNING: fill = t.warningContainer; ink = t.onWarningContainer; kind = Icon.WARNING; break;
                case Status.ERROR: fill = t.errorContainer; ink = t.onErrorContainer; kind = Icon.ERROR; break;
                default: fill = t.surfaceContainerHigh; ink = t.onSurface; kind = Icon.INFO; break;
            }
            shape.fill(fill);
            ImageView mark = (ImageView) getChildAt(0);
            Icon next = new Icon(t, kind, ink);
            mark.setImageDrawable(next);
            title.setTextColor(ink);
            body.setTextColor(tone == Status.NEUTRAL ? t.onSurfaceVariant : ink);
            if (action != null) action.setTextColor(ink);
        }
        if (!TextUtils.equals(title.getText(), heading)) title.setText(heading);
        if (!TextUtils.equals(body.getText(), detail)) body.setText(detail);
        if (action != null) action.setVisibility(withAction ? VISIBLE : GONE);
    }
}
