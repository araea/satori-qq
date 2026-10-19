package com.satori.qq.ui;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 首页上的更新卡：图标（或加载指示器）+ 标题 + 说明 + 至多两个按钮。
 * 外观是色调容器，结论文字本身已说清状态（颜色只加强），内容整块是 polite live region。
 * 没有内容时整张卡 GONE，不占位。
 */
final class UpdateCard extends LinearLayout {
    interface Actions {
        void run(int action);
    }

    private final Tokens t;
    private final Shape shape;
    private final ImageView mark;
    private final LoadingIndicator loading;
    private final TextView title;
    private final TextView body;
    private final Btn primary;
    private final Btn secondary;
    private final LinearLayout buttons;
    private final Actions actions;
    private int tone = -1;
    private int icon = -1;
    private int ink;
    private int primaryAction, secondaryAction;

    UpdateCard(Ui ui, Actions actions) {
        super(ui.t.context);
        this.t = ui.t;
        this.actions = actions;
        setOrientation(VERTICAL);
        setPadding(t.spaceXl, t.spaceLg + t.spaceXs, t.spaceXl, t.spaceLg);
        shape = Shape.smooth(t.primaryContainer, t.shapeXl);
        setBackground(shape);
        setVisibility(GONE);

        LinearLayout head = ui.row();
        mark = new ImageView(t.context);
        mark.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        loading = new LoadingIndicator(t, t.onPrimaryContainer);
        FrameLayout slot = new FrameLayout(t.context);
        slot.addView(mark, new FrameLayout.LayoutParams(t.icon, t.icon, Gravity.CENTER));
        slot.addView(loading, new FrameLayout.LayoutParams(t.icon, t.icon, Gravity.CENTER));
        head.addView(slot, new LayoutParams(t.icon, t.icon));
        title = Ui.live(ui.heading("", Tokens.TITLE_MEDIUM, t.onPrimaryContainer));
        head.addView(title, Ui.share(t.spaceMd));
        addView(head, Ui.stack(0));

        body = Ui.live(ui.text("", Tokens.BODY_MEDIUM, t.onPrimaryContainer));
        addView(body, Ui.stack(t.spaceSm));

        primary = ui.button("", Btn.FILLED);
        primary.setOnClickListener(v -> actions.run(primaryAction));
        secondary = ui.button("", Btn.TEXT);
        secondary.setOnClickListener(v -> actions.run(secondaryAction));
        buttons = ui.layout.stackActions() ? ui.column() : ui.row();
        if (buttons.getOrientation() == HORIZONTAL) buttons.setGravity(Gravity.CENTER_VERTICAL);
        if (buttons.getOrientation() == HORIZONTAL) {
            buttons.addView(primary, new LayoutParams(-2, -2));
            LayoutParams next = new LayoutParams(-2, -2);
            next.setMarginStart(t.spaceSm);
            buttons.addView(secondary, next);
        } else {
            buttons.addView(primary, Ui.stack(0));
            buttons.addView(secondary, Ui.stack(t.spaceXs));
        }
        addView(buttons, Ui.stack(t.spaceLg));
    }

    void show(UpdateFaces.Face face) {
        if (face == null) {
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
        if (tone != face.tone) {
            tone = face.tone;
            int fill;
            switch (face.tone) {
                case Status.ERROR: fill = t.errorContainer; ink = t.onErrorContainer; break;
                case Status.WARNING: fill = t.warningContainer; ink = t.onWarningContainer; break;
                case Status.SUCCESS: fill = t.successContainer; ink = t.onSuccessContainer; break;
                default: fill = t.primaryContainer; ink = t.onPrimaryContainer; break;
            }
            shape.fill(fill);
            title.setTextColor(ink);
            body.setTextColor(ink);
            loading.tint(ink);
            secondary.setTextColor(ink);
            icon = -1;
        }
        if (icon != face.icon) {
            icon = face.icon;
            mark.setImageDrawable(new Icon(t, face.icon, ink));
        }
        mark.setVisibility(face.busy ? GONE : VISIBLE);
        loading.setVisibility(face.busy ? VISIBLE : GONE);
        Ui.set(title, face.title);
        Ui.set(body, face.body);
        primaryAction = face.primaryAction;
        secondaryAction = face.secondaryAction;
        primary.setVisibility(face.primary == null ? GONE : VISIBLE);
        if (face.primary != null) Ui.set(primary, face.primary);
        secondary.setVisibility(face.secondary == null ? GONE : VISIBLE);
        if (face.secondary != null) Ui.set(secondary, face.secondary);
        buttons.setVisibility(face.primary == null ? GONE : View.VISIBLE);
    }
}
