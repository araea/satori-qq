package com.satori.qq.ui;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 组件层：把 Design Tokens 组装成界面用的控件。
 *
 * <p>三个体系在这里各有明确分工，不互相拼贴：
 * <ul>
 *   <li><b>M3E</b>——颜色角色、字级、形状刻度、状态层、按压缩放与形状形变；</li>
 *   <li><b>HIG</b>——一屏一个主操作、破坏性操作先确认且外观可辨、即时反馈（波纹）、
 *       尊重"减弱动态效果"、字号跟随系统设置；</li>
 *   <li><b>Carbon</b>——响应式断点与栅格、复杂信息用定义列表（{@link DataRow}）而不是大段文本、
 *       键盘可达与可见焦点环；</li>
 *   <li><b>Miuix</b>——只做视觉精修：更大的圆角、靠表面明度差分层而不是阴影、默认不画分隔线。</li>
 * </ul>
 *
 * <p>冲突时按 {@code 平台原生 > 可用性/无障碍 > 产品一致性 > M3E > Carbon > Miuix} 裁决。
 * 凡是与可访问性冲突的"更漂亮"的画法一律让步：可触达面积不小于 48dp、文字对比度不低于
 * 4.5:1、焦点必须可见、放大字号不截断。
 */
final class Widgets {

    // 按钮风格
    static final int FILLED = 0;
    static final int TONAL = 1;
    static final int OUTLINED = 2;
    static final int TEXT = 3;
    /** 破坏性操作：描边 + 错误色文字，可辨但不铺一整块红。 */
    static final int DANGER = 4;

    // 容器色调
    static final int TONE_NEUTRAL = 0;
    static final int TONE_HIGH = 1;
    static final int TONE_PRIMARY = 2;
    static final int TONE_SUCCESS = 3;
    static final int TONE_WARNING = 4;
    static final int TONE_ERROR = 5;

    private final Tokens t;
    private final Responsive r;

    Widgets(Tokens tokens, Responsive responsive) {
        this.t = tokens;
        this.r = responsive;
    }

    Tokens tokens() {
        return t;
    }

    // ------------------------------------------------------------------ 文本

    TextView text(String value, int role, int color) {
        TextView view = new TextView(t.activity);
        view.setText(value);
        view.setTextColor(color);
        return t.type(view, role);
    }

    /** 段落文本：可换行，行高走令牌。 */
    TextView body(String value, int color) {
        return text(value, Tokens.BODY_MEDIUM, color);
    }

    /** 区块标题。{@code setAccessibilityHeading} 让读屏能按标题跳转（WCAG 1.3.1）。 */
    TextView heading(String value, int role, int color) {
        TextView view = text(value, role, color);
        if (Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
        return view;
    }

    /** 状态类文本：内容变化时读屏会朗读（WCAG 4.1.3 状态消息）。 */
    TextView live(TextView view) {
        view.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        return view;
    }

    // ------------------------------------------------------------------ 布局

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(t.activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    LinearLayout row() {
        LinearLayout layout = new LinearLayout(t.activity);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    /** 占满宽度、按内容自适应高度的子项。 */
    LinearLayout.LayoutParams stack(int topGapDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = t.dp(topGapDp);
        return params;
    }

    /** 并列子项：占一份宽度。 */
    LinearLayout.LayoutParams share(int gapBeforeDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.leftMargin = t.dp(gapBeforeDp);
        return params;
    }

    // ------------------------------------------------------------------ 容器

    /** 卡片：只有一层底色，不描边、不投影；层次靠 surface 明度档。 */
    LinearLayout card(int tone, int radiusDp, int paddingDp) {
        LinearLayout layout = column();
        layout.setPadding(t.dp(paddingDp), t.dp(paddingDp), t.dp(paddingDp), t.dp(paddingDp));
        layout.setBackground(t.shape(fillOf(tone), radiusDp));
        return layout;
    }

    int fillOf(int tone) {
        switch (tone) {
            case TONE_PRIMARY: return t.primaryContainer;
            case TONE_SUCCESS: return t.successContainer;
            case TONE_WARNING: return t.warningContainer;
            case TONE_ERROR: return t.errorContainer;
            case TONE_HIGH: return t.surfaceContainerHigh;
            default: return t.surfaceContainer;
        }
    }

    int inkOf(int tone) {
        switch (tone) {
            case TONE_PRIMARY: return t.onPrimaryContainer;
            case TONE_SUCCESS: return t.onSuccessContainer;
            case TONE_WARNING: return t.onWarningContainer;
            case TONE_ERROR: return t.onErrorContainer;
            default: return t.onSurface;
        }
    }

    /**
     * 状态条：一个色块 + 一句结论。语义色只用来加强，文字本身已说清状态
     * （WCAG 1.4.1 颜色不能是唯一手段）。
     */
    LinearLayout banner(int tone, String title, String detail) {
        LinearLayout layout = card(tone, 20, 20);
        int ink = inkOf(tone);
        boolean hasTitle = title != null && !title.isEmpty();
        if (hasTitle) layout.addView(text(title, Tokens.TITLE_MEDIUM, ink), stack(0));
        if (detail != null && !detail.isEmpty()) {
            layout.addView(live(body(detail, ink)), stack(hasTitle ? 8 : 0));
        }
        return layout;
    }

    // ------------------------------------------------------------------ 控件

    /** 按钮：高度不低于 48dp（平台最小目标），文字可换行，放大字号时按钮长高。 */
    Button button(String label, int style) {
        GradientDrawable base = t.shape(backgroundColor(style), 28,
                style == OUTLINED || style == DANGER ? borderColor(style) : Color.TRANSPARENT,
                style == OUTLINED || style == DANGER ? 1 : 0);
        ShapeButton view = new ShapeButton();
        view.setText(label);
        t.type(view, Tokens.LABEL_LARGE);
        view.setAllCaps(false);
        view.setSingleLine(false);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(t.dimen("size_button_min_height"));
        view.setMinimumHeight(t.dimen("size_button_min_height"));
        view.setMinWidth(t.dimen("size_touch_target"));
        view.setPadding(t.dp(24), t.dp(12), t.dp(24), t.dp(12));
        view.setStateListAnimator(null);
        view.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{t.layer(t.onSurface, t.integer("state_disabled_content_pct"), t.surface),
                        foregroundColor(style)}));
        view.setSkin(paint(view, base, 28, 16,
                style == TONAL ? t.secondary : t.primary, ringOf(style)));
        return view;
    }

    private int backgroundColor(int style) {
        switch (style) {
            case FILLED: return t.primary;
            case TONAL: return t.secondaryContainer;
            default: return Color.TRANSPARENT;
        }
    }

    private int foregroundColor(int style) {
        switch (style) {
            case FILLED: return t.onPrimary;
            case TONAL: return t.onSecondaryContainer;
            case DANGER: return t.error;
            default: return t.primary;
        }
    }

    private int borderColor(int style) {
        return style == DANGER ? t.error : t.outline;
    }

    private int ringOf(int style) {
        switch (style) {
            case FILLED: return t.onPrimary;
            case TONAL: return t.onSecondaryContainer;
            case DANGER: return t.error;
            default: return t.primary;
        }
    }

    /**
     * 图标按钮：只有图标，语义全部由 contentDescription 承担（WCAG 4.1.2）。
     *
     * <p>用容器 + {@link Glyph} 子视图，而不是让按钮自己在 {@code onDraw} 里画：
     * 图形按 {@code size_icon}（24dp）居中，不随按钮面积放大。
     */
    View iconButton(int glyphKind, String contentDescription) {
        FrameLayout view = new FrameLayout(t.activity);
        GradientDrawable base = t.shape(t.surfaceContainerHigh, 999);
        paint(view, base, 999, 999, t.primary, t.primary);
        Glyph glyph = new Glyph(t, glyphKind, t.onSurfaceVariant);
        view.addView(glyph, new FrameLayout.LayoutParams(
                t.dimen("size_icon"), t.dimen("size_icon"), Gravity.CENTER));
        view.setMinimumWidth(t.dimen("size_touch_target"));
        view.setMinimumHeight(t.dimen("size_touch_target"));
        view.setClickable(true);
        asButton(view, contentDescription);
        return view;
    }

    /**
     * 开关行：整行可点，高度不低于 64dp；开关本身的可触达面积由行承担。
     * 读屏把这一行读成"标签 + 开关"。
     */
    Switch switchControl(String label) {
        Switch view = new Switch(t.activity);
        view.setText(label);
        view.setTextColor(t.onSurface);
        t.type(view, Tokens.BODY_LARGE);
        view.setMinHeight(t.dp(64));
        view.setMinimumHeight(t.dp(64));
        view.setPadding(t.dp(16), t.dp(8), t.dp(12), t.dp(8));
        view.setSwitchPadding(t.dp(16));
        view.setSaveEnabled(false);
        paint(view, t.shape(t.surfaceContainerLow, 20), 20, 20, t.primary, t.primary);
        GradientDrawable track = t.shape(t.surfaceContainerHighest, 16, t.outline, 2);
        track.setSize(t.dimen("size_switch_track_width"), t.dimen("size_switch_track_height"));
        view.setTrackDrawable(track);
        view.setTrackTintList(t.states(t.primary, t.surfaceContainerHighest));
        GradientDrawable thumb = t.shape(t.onPrimary, 12);
        int thumbSize = t.dimen("size_switch_thumb");
        thumb.setSize(thumbSize, thumbSize);
        view.setThumbDrawable(new InsetDrawable(thumb, t.dp(4)));
        view.setThumbTintList(t.states(t.onPrimary, t.outline));
        view.setSplitTrack(false);
        return view;
    }

    /** 输入框：标签在上、辅助文字在下，错误态用文字 + 描边双重表达。 */
    Field field(LinearLayout parent, String label, String hint, boolean secret) {
        TextView labelView = text(label, Tokens.LABEL_LARGE, t.onSurfaceVariant);
        parent.addView(labelView, stack(20));
        EditText input = new EditText(t.activity);
        input.setId(View.generateViewId());
        labelView.setLabelFor(input.getId());
        input.setSaveEnabled(false);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (secret
                ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_NORMAL));
        if (secret) {
            input.setTransformationMethod(PasswordTransformationMethod.getInstance());
            input.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }
        input.setTextColor(t.onSurface);
        t.type(input, Tokens.BODY_LARGE);
        input.setHintTextColor(t.onSurfaceVariant);
        input.setHint(hint);
        input.setPadding(t.dp(16), t.dp(16), t.dp(16), t.dp(16));
        input.setMinimumHeight(t.dimen("size_field_min_height"));
        input.setBackground(t.shape(t.surface, 12, t.outline, 1));
        parent.addView(input, stack(8));
        TextView helper = body("", t.onSurfaceVariant);
        parent.addView(helper, stack(6));
        Field field = new Field(input, helper);
        input.setOnFocusChangeListener((v, focused) -> field.restyle(focused));
        return field;
    }

    /** 一小段操作行：若干按钮横向排列，放不下时由外层在换行前先竖排。 */
    void actionRow(LinearLayout parent, List<Button> buttons, int topGapDp) {
        boolean vertical = !r.canPairCards();
        if (vertical) {
            for (int i = 0; i < buttons.size(); i++) parent.addView(buttons.get(i), stack(i == 0 ? topGapDp : 8));
            return;
        }
        LinearLayout line = row();
        line.setGravity(Gravity.TOP);
        for (int i = 0; i < buttons.size(); i++) {
            Button button = buttons.get(i);
            if (buttons.size() >= 3) {
                // 一行里塞三个，24dp 的横向内边距会把标签挤成两行
                button.setPadding(t.dp(12), t.dp(12), t.dp(12), t.dp(12));
            }
            line.addView(button, share(i == 0 ? 0 : 8));
        }
        parent.addView(line, stack(topGapDp));
    }

    // ------------------------------------------------------------------ 数据展示

    /**
     * Carbon 的定义列表：一行一个「名称 — 值」。宽屏并排、窄屏或放大字号时竖排，
     * 任何时候都不截断（由调用方在数据更新时 {@link DataRow#set}）。
     */
    DataRow dataRow(LinearLayout parent, String label, int topGapDp) {
        boolean paired = r.canPairCards();
        LinearLayout line = paired ? row() : column();
        if (paired) line.setGravity(Gravity.TOP);
        TextView name = text(label, Tokens.LABEL_LARGE, t.onSurfaceVariant);
        line.addView(name, paired ? new LinearLayout.LayoutParams(t.dp(112), -2) : stack(0));
        TextView value = text("—", Tokens.BODY_MEDIUM, t.onSurface);
        line.addView(value, paired ? new LinearLayout.LayoutParams(0, -2, 1) : stack(4));
        parent.addView(line, stack(topGapDp));
        return new DataRow(value);
    }

    // ------------------------------------------------------------------ 弹窗

    /**
     * 确认弹窗。破坏性操作用错误色按钮，并且默认焦点在「取消」上——
     * 破坏性动作不能是"顺手一点"就触发的那个（HIG）。
     */
    Dialog confirm(String title, String message, String confirmLabel, boolean destructive, Runnable onConfirm) {
        Dialog dialog = new Dialog(t.activity);
        LinearLayout content = column();
        content.setPadding(t.dp(24), t.dp(24), t.dp(24), t.dp(12));
        content.setBackground(t.shape(t.surfaceContainerHigh, 28));
        content.addView(heading(title, Tokens.HEADLINE_SMALL, t.onSurface), stack(0));
        content.addView(body(message, t.onSurfaceVariant), stack(12));
        Button cancel = button("取消", TEXT);
        Button accept = button(confirmLabel, destructive ? DANGER : FILLED);
        actionRow(content, java.util.Arrays.asList(cancel, accept), 24);
        ScrollView scroll = new ScrollView(t.activity);
        scroll.addView(content);
        dialog.setContentView(scroll);
        cancel.setOnClickListener(v -> dialog.dismiss());
        accept.setOnClickListener(v -> {
            dialog.dismiss();
            onConfirm.run();
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(t.shape(Color.TRANSPARENT, 28));
            dialog.getWindow().setDimAmount(0.4f);
            int width = Math.min(t.dimen("size_dialog_max"),
                    t.activity.getResources().getDisplayMetrics().widthPixels - t.dp(32));
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        cancel.setFocusableInTouchMode(true);
        cancel.requestFocus();
        return dialog;
    }

    /** 供调用方在数据更新时填充的一行。 */
    final class DataRow {
        private final TextView value;

        DataRow(TextView value) {
            this.value = value;
        }

        void set(String text) {
            value.setText(text);
        }
    }

    /** 输入框 + 辅助/错误文字。错误同时写进 {@code setError}，读屏才会播报。 */
    final class Field {
        final EditText input;
        private final TextView helper;
        private String hintText = "";
        private boolean failed;

        Field(EditText input, TextView helper) {
            this.input = input;
            this.helper = helper;
        }

        void hint(String text) {
            hintText = text == null ? "" : text;
            if (!failed) helper.setText(hintText);
            restyle(input.hasFocus());
        }

        void error(String message) {
            failed = message != null;
            if (failed) {
                input.setError(message);
                input.requestFocus();
                input.post(() -> input.requestRectangleOnScreen(
                        new android.graphics.Rect(0, 0, input.getWidth(), input.getHeight()), false));
                helper.setText(message);
                helper.setTextColor(t.error);
            } else {
                input.setError(null);
                helper.setText(hintText);
                helper.setTextColor(t.onSurfaceVariant);
            }
            restyle(input.hasFocus());
        }

        void restyle(boolean focused) {
            input.setBackground(t.shape(t.surface, 12,
                    failed ? t.error : (focused ? t.primary : t.outline),
                    failed || focused ? 2 : 1));
        }
    }

    // ------------------------------------------------------------------ 交互皮肤

    /**
     * 给交互控件装上可访问的皮肤：波纹反馈、可见焦点环、按压缩放与形状形变。
     * 动效跟着系统动画开关走，用户开了"减弱动态效果"就只剩颜色变化。
     *
     * @param restRadiusDp    常态圆角
     * @param pressedRadiusDp 按下时的圆角（等于 rest 则不做形状形变）
     * @param accent          波纹强调色
     * @param ringColor       焦点环颜色，必须与该控件底色有 3:1 以上的对比
     */
    Skin paint(View view, GradientDrawable base, int restRadiusDp, int pressedRadiusDp,
               int accent, int ringColor) {
        GradientDrawable ring = t.shape(Color.TRANSPARENT, restRadiusDp, Color.TRANSPARENT, 2);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{base, ring});
        Drawable mask = t.shape(Color.WHITE, restRadiusDp);
        RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(t.ripple(accent)), layers, mask);
        Skin skin = new Skin(this, base, ring, restRadiusDp, pressedRadiusDp, ringColor);
        view.setBackground(ripple);
        view.setOnFocusChangeListener((v, focused) -> skin.setFocused(focused));
        if (view instanceof Button) view.setStateListAnimator(null);
        return skin;
    }

    /** 交互控件的绘制状态：形状形变与焦点环改同一组 drawable，几何不跳。 */
    static final class Skin {
        private final Widgets widgets;
        private final GradientDrawable base;
        private final GradientDrawable ring;
        private final int restRadiusDp;
        private final int pressedRadiusDp;
        private final int ringColor;
        private final int fill;
        private ValueAnimator motion;

        Skin(Widgets widgets, GradientDrawable base, GradientDrawable ring,
             int restRadiusDp, int pressedRadiusDp, int ringColor) {
            this.widgets = widgets;
            this.base = base;
            this.ring = ring;
            this.restRadiusDp = restRadiusDp;
            this.pressedRadiusDp = pressedRadiusDp;
            this.ringColor = ringColor;
            ColorStateList fill = base.getColor();
            this.fill = fill == null ? Color.TRANSPARENT : fill.getDefaultColor();
        }

        /**
         * 禁用态换容器色（M3：on-surface 12%）。只对实心按钮做——
         * 描边与文字按钮的容器本来就是透明的，换成一个灰块反而更重。
         */
        void setEnabled(boolean enabled) {
            if (Color.alpha(fill) == 0) return;
            base.setColor(enabled ? fill
                    : widgets.t.layer(widgets.t.onSurface,
                            widgets.t.integer("state_disabled_pct"), widgets.t.surface));
        }

        void setFocused(boolean focused) {
            ring.setStroke(widgets.t.dp(2), focused ? ringColor : Color.TRANSPARENT);
        }

        void setPressed(boolean pressed) {
            if (base == null || restRadiusDp == pressedRadiusDp) return;
            Tokens tokens = widgets.t;
            float target = pressed ? pressedRadiusDp : restRadiusDp;
            if (motion != null) motion.cancel();
            if (!ValueAnimator.areAnimatorsEnabled()) {
                base.setCornerRadius(tokens.dp(target));
                ring.setCornerRadius(tokens.dp(target));
                return;
            }
            final float from = base.getCornerRadius()
                    / tokens.activity.getResources().getDisplayMetrics().density;
            motion = ValueAnimator.ofFloat(0, 1);
            motion.setDuration(tokens.integer(pressed ? "motion_short3" : "motion_medium3"));
            motion.setInterpolator(pressed ? tokens.easing("emphasized")
                    : new OvershootInterpolator(tokens.integer("motion_overshoot_pct") / 100f));
            motion.addUpdateListener(animation -> {
                float fraction = (float) animation.getAnimatedValue();
                float radius = from + (target - from) * fraction;
                base.setCornerRadius(tokens.dp(radius));
                ring.setCornerRadius(tokens.dp(radius));
            });
            motion.start();
        }

        void dispose() {
            if (motion != null) {
                motion.cancel();
                motion = null;
            }
        }
    }

    /** 会做形状形变的按钮（M3E 的按压表达）。 */
    private final class ShapeButton extends Button {
        private Skin skin;

        ShapeButton() {
            super(t.activity);
        }

        void setSkin(Skin skin) {
            this.skin = skin;
        }

        @Override public void setPressed(boolean pressed) {
            if (isPressed() == pressed) return;
            super.setPressed(pressed);
            if (skin != null) skin.setPressed(pressed);
        }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            if (skin != null) skin.setEnabled(isEnabled());
        }

        @Override protected void onDetachedFromWindow() {
            if (skin != null) skin.dispose();
            super.onDetachedFromWindow();
        }
    }

    /** 让自定义容器在读屏里表现为按钮（导航项、图标按钮这类整块可点的区域）。 */
    static void asButton(View view, String contentDescription) {
        view.setFocusable(true);
        view.setContentDescription(contentDescription);
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
            }
        });
    }

    /** 把一组子项按列数均分排布；列数为 1 时就是竖排。 */
    void addColumnWise(LinearLayout parent, List<View> children, int columns, int gapDp, int topGapDp) {
        if (columns <= 1) {
            for (int i = 0; i < children.size(); i++) {
                parent.addView(children.get(i), stack(i == 0 ? topGapDp : gapDp));
            }
            return;
        }
        int rows = (children.size() + columns - 1) / columns;
        for (int rowIndex = 0; rowIndex < rows; rowIndex++) {
            LinearLayout line = row();
            line.setGravity(Gravity.TOP);
            for (int column = 0; column < columns; column++) {
                int index = rowIndex * columns + column;
                if (index >= children.size()) {
                    line.addView(new View(t.activity), share(column == 0 ? 0 : gapDp));
                } else {
                    line.addView(children.get(index), share(column == 0 ? 0 : gapDp));
                }
            }
            parent.addView(line, stack(rowIndex == 0 ? topGapDp : gapDp));
        }
    }

    /** 内容不被父容器裁掉：放大字号后靠它保证文字完整（WCAG 1.4.4）。 */
    static void noClip(ViewGroup group) {
        group.setClipChildren(false);
        group.setClipToPadding(false);
    }
}
