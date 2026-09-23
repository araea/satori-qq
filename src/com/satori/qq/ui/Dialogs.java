package com.satori.qq.ui;

import android.app.Dialog;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/**
 * M3 基础对话框：标题、说明、右对齐的文字按钮。
 *
 * <p>按钮顺序服从 Android（肯定操作在最右），这是平台规范，优先于 HIG 的习惯；
 * 采纳 HIG 的是"破坏性操作用错误色、默认焦点落在取消上"。放大字号或窄屏时按钮竖排、整宽。
 * 系统返回与点遮罩都等同取消；内容过长时可滚动；宽度夹在 280–560dp。
 */
final class Dialogs {
    private Dialogs() {}

    static final class Action {
        final String label;
        final int style;
        final Runnable run;

        Action(String label, int style, Runnable run) {
            this.label = label;
            this.style = style;
            this.run = run;
        }
    }

    /**
     * @param actions 从左到右；约定第一个是「取消」，它拿默认焦点，run 可为 null
     */
    static Dialog show(Ui ui, String title, String message, Action... actions) {
        Tokens t = ui.t;
        Dialog dialog = new Dialog(t.context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout content = ui.column();
        content.setPadding(t.spaceXl, t.spaceXl, t.spaceXl, t.spaceLg);
        content.setBackground(Shape.smooth(t.surfaceContainerHigh, t.shapeXl));
        content.addView(ui.heading(title, Tokens.HEADLINE_SMALL, t.onSurface), Ui.stack(0));
        content.addView(ui.text(message, Tokens.BODY_MEDIUM, t.onSurfaceVariant), Ui.stack(t.spaceLg));

        boolean stacked = ui.layout.stackActions();
        LinearLayout bar = stacked ? ui.column() : ui.row();
        if (!stacked) bar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Btn first = null;
        for (int i = 0; i < actions.length; i++) {
            final Action action = actions[stacked ? actions.length - 1 - i : i];
            Btn button = ui.button(action.label, action.style);
            button.setOnClickListener(v -> {
                dialog.dismiss();
                if (action.run != null) action.run.run();
            });
            if (action == actions[0]) first = button;
            if (stacked) {
                bar.addView(button, Ui.stack(i == 0 ? 0 : t.spaceXs));
            } else {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
                params.setMarginStart(i == 0 ? 0 : t.spaceSm);
                bar.addView(button, params);
            }
        }
        content.addView(bar, Ui.stack(t.spaceXl));

        ScrollView scroll = new ScrollView(t.context);
        scroll.addView(content);
        dialog.setContentView(scroll);
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            window.setDimAmount(t.scrimPct / 100f);
            int screen = t.res.getDisplayMetrics().widthPixels;
            int width = Math.max(Math.min(t.dialogMin, screen - 2 * t.spaceLg),
                    Math.min(t.dialogMax, screen - 2 * t.spaceXl));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        if (first != null) {
            first.setFocusableInTouchMode(true);
            first.requestFocus();
            final View focus = first;
            // 触摸模式下保留焦点会让下一次点击先"聚焦"而不触发；拿到初始焦点后立刻恢复常规行为。
            focus.post(() -> focus.setFocusableInTouchMode(false));
        }
        return dialog;
    }
}
