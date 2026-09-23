package com.satori.qq.ui;

import android.content.Context;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * M3 信息条：一句操作结果 + 至多一个动作，出现在内容底部、不遮挡系统栏。
 *
 * <p>停留时长交给系统：{@code getRecommendedTimeoutMillis} 会按用户的无障碍「操作时限」设置
 * 延长（WCAG 2.2.1）；开着触摸浏览（TalkBack）且带动作时不自动消失，等用户处理。
 * 文字是 polite live region，出现时朗读一次。
 */
final class Snackbar {
    private static final int SHORT = 4000;
    private static final int WITH_ACTION = 8000;

    private final Tokens t;
    private final FrameLayout host;
    private final LinearLayout bar;
    private final TextView message;
    private final Btn action;
    private final Runnable hide = this::dismiss;
    private Runnable onAction;
    private int bottomInset;

    Snackbar(Ui ui, FrameLayout host) {
        this.t = ui.t;
        this.host = host;
        bar = ui.row();
        bar.setId(com.satori.qq.R.id.snackbar);
        bar.setPaddingRelative(t.spaceLg, t.spaceXs, t.spaceXs, t.spaceXs);
        bar.setMinimumHeight(t.button);
        bar.setBackground(Shape.round(t.inverseSurface, t.shapeXs));
        bar.setElevation(t.dp(3));
        message = Ui.live(ui.text("", Tokens.BODY_MEDIUM, t.inverseOnSurface));
        message.setPadding(0, t.spaceMd, 0, t.spaceMd);
        bar.addView(message, Ui.share(0));
        action = ui.button("", Btn.TEXT);
        action.setTextColor(t.inversePrimary);
        action.setOnClickListener(v -> {
            Runnable run = onAction;
            dismiss();
            if (run != null) run.run();
        });
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-2, -2);
        actionParams.setMarginStart(t.spaceSm);
        bar.addView(action, actionParams);
        bar.setVisibility(View.GONE);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        host.addView(bar, params);
        host.addOnLayoutChangeListener((v, l, top, r, b, ol, ot, or, ob) -> place());
    }

    void setBottomInset(int inset) {
        bottomInset = inset;
        place();
    }

    private void place() {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) bar.getLayoutParams();
        int width = Math.min(t.snackbarMax, host.getWidth() - 2 * t.spaceLg);
        int bottom = bottomInset + t.spaceLg;
        if (params.width != width || params.bottomMargin != bottom) {
            params.width = width > 0 ? width : -1;
            params.leftMargin = params.rightMargin = t.spaceLg;
            params.bottomMargin = bottom;
            bar.setLayoutParams(params);
        }
    }

    void show(String text, String actionLabel, Runnable run) {
        bar.removeCallbacks(hide);
        onAction = run;
        boolean hasAction = actionLabel != null && run != null;
        action.setVisibility(hasAction ? View.VISIBLE : View.GONE);
        if (hasAction) action.setText(actionLabel);
        bar.setVisibility(View.VISIBLE);
        message.setText(text);
        bar.bringToFront();
        if (Spring.enabled()) {
            bar.setAlpha(0f);
            bar.setTranslationY(t.dp(16));
            bar.animate().alpha(1f).translationY(0).setDuration(t.spatialDefault.duration)
                    .setInterpolator(t.spatialDefault).start();
        }
        AccessibilityManager manager = (AccessibilityManager) t.context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        boolean exploring = manager != null && manager.isTouchExplorationEnabled();
        if (hasAction && exploring) return;
        int timeout = hasAction ? WITH_ACTION : SHORT;
        if (manager != null && Build.VERSION.SDK_INT >= 29) {
            timeout = manager.getRecommendedTimeoutMillis(timeout, AccessibilityManager.FLAG_CONTENT_TEXT
                    | (hasAction ? AccessibilityManager.FLAG_CONTENT_CONTROLS : 0));
        }
        bar.postDelayed(hide, timeout);
    }

    void dismiss() {
        bar.removeCallbacks(hide);
        if (bar.getVisibility() != View.VISIBLE) return;
        if (!Spring.enabled()) {
            bar.setVisibility(View.GONE);
            return;
        }
        bar.animate().alpha(0f).setDuration(t.effectsFast.duration).setInterpolator(t.effectsFast)
                .withEndAction(() -> bar.setVisibility(View.GONE)).start();
    }

    boolean showing() {
        return bar.getVisibility() == View.VISIBLE;
    }
}
