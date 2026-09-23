package com.satori.qq.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;
import android.widget.TextView;
import com.satori.qq.R;

/**
 * 知弦 Design Tokens 在运行期的唯一读口：构造时把颜色、尺度、字级与弹簧一次解析完，之后只读字段。
 *
 * <p>令牌本体在 {@code res/values/tokens.xml}（颜色，深浅两份）与 {@code res/values/dimens.xml}，
 * 这里经生成的 {@code R} 类引用，名字拼错在编译期就失败。组件只从这里取值，页面不直接碰资源，
 * 界面代码里也不出现字面色值（{@code tests/DesignTokenTest} 会拦）。
 *
 * <p>不跟随壁纸动态取色：动态色无法为任意壁纸保证 success / warning 这两个自定义角色的对比度，
 * 也会与固定源色 {@code #6750A4} 的应用图标分叉。裁决顺序里可用性与产品一致性都排在 M3E 之前。
 */
final class Tokens {
    // ---- 字级角色 ----
    static final int DISPLAY_SMALL = 0;
    static final int HEADLINE_LARGE = 1;
    static final int HEADLINE_SMALL = 2;
    static final int TITLE_LARGE = 3;
    static final int TITLE_MEDIUM = 4;
    static final int TITLE_SMALL = 5;
    static final int BODY_LARGE = 6;
    static final int BODY_MEDIUM = 7;
    static final int BODY_SMALL = 8;
    static final int LABEL_LARGE = 9;
    static final int LABEL_MEDIUM = 10;

    private static final int[][] TYPE = {
            {R.dimen.md_type_display_small_size, R.dimen.md_type_display_small_line, R.integer.md_weight_emphasized},
            {R.dimen.md_type_headline_large_size, R.dimen.md_type_headline_large_line, R.integer.md_weight_emphasized},
            {R.dimen.md_type_headline_small_size, R.dimen.md_type_headline_small_line, R.integer.md_weight_medium},
            {R.dimen.md_type_title_large_size, R.dimen.md_type_title_large_line, R.integer.md_weight_medium},
            {R.dimen.md_type_title_medium_size, R.dimen.md_type_title_medium_line, R.integer.md_weight_medium},
            {R.dimen.md_type_title_small_size, R.dimen.md_type_title_small_line, R.integer.md_weight_medium},
            {R.dimen.md_type_body_large_size, R.dimen.md_type_body_large_line, R.integer.md_weight_regular},
            {R.dimen.md_type_body_medium_size, R.dimen.md_type_body_medium_line, R.integer.md_weight_regular},
            {R.dimen.md_type_body_small_size, R.dimen.md_type_body_small_line, R.integer.md_weight_regular},
            {R.dimen.md_type_label_large_size, R.dimen.md_type_label_large_line, R.integer.md_weight_medium},
            {R.dimen.md_type_label_medium_size, R.dimen.md_type_label_medium_line, R.integer.md_weight_medium},
    };

    final Context context;
    final Resources res;
    final boolean dark;
    final float density;

    // ---- 颜色角色：与 res/values/tokens.xml 逐条对应 ----
    final int primary, onPrimary, primaryContainer, onPrimaryContainer, inversePrimary;
    final int secondary, onSecondary, secondaryContainer, onSecondaryContainer;
    final int tertiary, onTertiary, tertiaryContainer, onTertiaryContainer;
    final int error, onError, errorContainer, onErrorContainer;
    final int success, onSuccess, successContainer, onSuccessContainer;
    final int warning, onWarning, warningContainer, onWarningContainer;
    final int surface, surfaceContainerLowest, surfaceContainerLow, surfaceContainer,
            surfaceContainerHigh, surfaceContainerHighest;
    final int onSurface, surfaceVariant, onSurfaceVariant, inverseSurface, inverseOnSurface;
    final int outline, outlineVariant, scrim;

    // ---- 尺度（像素） ----
    final int space2xs, spaceXs, spaceSm, spaceMd, spaceLg, spaceXl, space2xl, space3xl;
    final int shapeXs, shapeSm, shapeMd, shapeLg, shapeLgIncreased, shapeXl, shapeXlIncreased;
    final int touchTarget, icon, iconButton, button, buttonProminent, field, listOneLine, listTwoLine;
    final int topBar, switchWidth, switchHeight, handleOff, handleOn, handlePressed;
    final int loading, logo, border, focusRing, contentMax, dialogMin, dialogMax, snackbarMax;

    // ---- 状态层与动效 ----
    final int hoverPct, pressedPct, disabledContainerPct, disabledContentPct, scrimPct;
    final Spring spatialFast, spatialDefault, expressiveFast, effectsFast, effectsDefault;
    final int motionMedium;
    final int bpMedium, bpExpanded, reflowPaneMin, reflowPairMin;

    private final Typeface[] faces = new Typeface[TYPE.length];

    Tokens(Context context) {
        this.context = context;
        this.res = context.getResources();
        this.dark = (res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        this.density = res.getDisplayMetrics().density;

        primary = c(R.color.md_primary);
        onPrimary = c(R.color.md_on_primary);
        primaryContainer = c(R.color.md_primary_container);
        onPrimaryContainer = c(R.color.md_on_primary_container);
        inversePrimary = c(R.color.md_inverse_primary);
        secondary = c(R.color.md_secondary);
        onSecondary = c(R.color.md_on_secondary);
        secondaryContainer = c(R.color.md_secondary_container);
        onSecondaryContainer = c(R.color.md_on_secondary_container);
        tertiary = c(R.color.md_tertiary);
        onTertiary = c(R.color.md_on_tertiary);
        tertiaryContainer = c(R.color.md_tertiary_container);
        onTertiaryContainer = c(R.color.md_on_tertiary_container);
        error = c(R.color.md_error);
        onError = c(R.color.md_on_error);
        errorContainer = c(R.color.md_error_container);
        onErrorContainer = c(R.color.md_on_error_container);
        success = c(R.color.md_success);
        onSuccess = c(R.color.md_on_success);
        successContainer = c(R.color.md_success_container);
        onSuccessContainer = c(R.color.md_on_success_container);
        warning = c(R.color.md_warning);
        onWarning = c(R.color.md_on_warning);
        warningContainer = c(R.color.md_warning_container);
        onWarningContainer = c(R.color.md_on_warning_container);
        surface = c(R.color.md_surface);
        surfaceContainerLowest = c(R.color.md_surface_container_lowest);
        surfaceContainerLow = c(R.color.md_surface_container_low);
        surfaceContainer = c(R.color.md_surface_container);
        surfaceContainerHigh = c(R.color.md_surface_container_high);
        surfaceContainerHighest = c(R.color.md_surface_container_highest);
        onSurface = c(R.color.md_on_surface);
        surfaceVariant = c(R.color.md_surface_variant);
        onSurfaceVariant = c(R.color.md_on_surface_variant);
        inverseSurface = c(R.color.md_inverse_surface);
        inverseOnSurface = c(R.color.md_inverse_on_surface);
        outline = c(R.color.md_outline);
        outlineVariant = c(R.color.md_outline_variant);
        scrim = c(R.color.md_scrim);

        space2xs = d(R.dimen.md_space_2xs);
        spaceXs = d(R.dimen.md_space_xs);
        spaceSm = d(R.dimen.md_space_sm);
        spaceMd = d(R.dimen.md_space_md);
        spaceLg = d(R.dimen.md_space_lg);
        spaceXl = d(R.dimen.md_space_xl);
        space2xl = d(R.dimen.md_space_2xl);
        space3xl = d(R.dimen.md_space_3xl);
        shapeXs = d(R.dimen.md_shape_xs);
        shapeSm = d(R.dimen.md_shape_sm);
        shapeMd = d(R.dimen.md_shape_md);
        shapeLg = d(R.dimen.md_shape_lg);
        shapeLgIncreased = d(R.dimen.md_shape_lg_increased);
        shapeXl = d(R.dimen.md_shape_xl);
        shapeXlIncreased = d(R.dimen.md_shape_xl_increased);
        touchTarget = d(R.dimen.md_size_touch_target);
        icon = d(R.dimen.md_size_icon);
        iconButton = d(R.dimen.md_size_icon_button);
        button = d(R.dimen.md_size_button);
        buttonProminent = d(R.dimen.md_size_button_prominent);
        field = d(R.dimen.md_size_field);
        listOneLine = d(R.dimen.md_size_list_one_line);
        listTwoLine = d(R.dimen.md_size_list_two_line);
        topBar = d(R.dimen.md_size_top_bar);
        switchWidth = d(R.dimen.md_size_switch_width);
        switchHeight = d(R.dimen.md_size_switch_height);
        handleOff = d(R.dimen.md_size_switch_handle_off);
        handleOn = d(R.dimen.md_size_switch_handle_on);
        handlePressed = d(R.dimen.md_size_switch_handle_pressed);
        loading = d(R.dimen.md_size_loading);
        logo = d(R.dimen.md_size_logo);
        border = d(R.dimen.md_size_border);
        focusRing = d(R.dimen.md_size_focus_ring);
        contentMax = d(R.dimen.md_size_content_max);
        dialogMin = d(R.dimen.md_size_dialog_min);
        dialogMax = d(R.dimen.md_size_dialog_max);
        snackbarMax = d(R.dimen.md_size_snackbar_max);

        hoverPct = i(R.integer.md_state_hover_pct);
        pressedPct = i(R.integer.md_state_pressed_pct);
        disabledContainerPct = i(R.integer.md_state_disabled_container_pct);
        disabledContentPct = i(R.integer.md_state_disabled_content_pct);
        scrimPct = i(R.integer.md_scrim_pct);
        float standard = i(R.integer.md_spring_damping_standard) / 100f;
        float expressive = i(R.integer.md_spring_damping_expressive) / 100f;
        float effects = i(R.integer.md_spring_damping_effects) / 100f;
        spatialFast = new Spring(standard, i(R.integer.md_spring_spatial_fast));
        spatialDefault = new Spring(standard, i(R.integer.md_spring_spatial_default));
        expressiveFast = new Spring(expressive, i(R.integer.md_spring_expressive_fast));
        effectsFast = new Spring(effects, i(R.integer.md_spring_effects_fast));
        effectsDefault = new Spring(effects, i(R.integer.md_spring_effects_default));
        motionMedium = i(R.integer.md_motion_medium);
        bpMedium = i(R.integer.md_bp_medium);
        bpExpanded = i(R.integer.md_bp_expanded);
        reflowPaneMin = i(R.integer.md_reflow_pane_min);
        reflowPairMin = i(R.integer.md_reflow_pair_min);
    }

    private int c(int id) { return context.getColor(id); }
    private int d(int id) { return res.getDimensionPixelSize(id); }
    private int i(int id) { return res.getInteger(id); }

    int dp(float value) {
        return Math.round(value * density);
    }

    // ---------------------------------------------------------------- 字级

    /**
     * 把字级套到 TextView：字号、行高、字重，字距归零。
     *
     * <p>字号与行高都经 {@code getDimension} 取 sp 值，Android 14 起的非线性字号缩放两者一致生效；
     * 行高是"行距"而非固定高度，放大到 200% 时文字跟着长高，不会被裁（WCAG 1.4.4 / 1.4.12）。
     * M3 的拉丁文正字距会把汉字拆散，本界面以中文为主，按"可用性优先于 M3E"一律取 0。
     */
    <T extends TextView> T type(T view, int role) {
        int[] spec = TYPE[role];
        float size = res.getDimension(spec[0]);
        int line = res.getDimensionPixelSize(spec[1]);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        if (Build.VERSION.SDK_INT >= 28) view.setLineHeight(Math.max(line, Math.round(size)));
        else view.setLineSpacing(Math.max(0, line - size), 1f);
        view.setLetterSpacing(0);
        view.setIncludeFontPadding(false);
        view.setTypeface(face(role));
        return view;
    }

    private Typeface face(int role) {
        Typeface cached = faces[role];
        if (cached != null) return cached;
        int weight = res.getInteger(TYPE[role][2]);
        Typeface face = Build.VERSION.SDK_INT >= 28
                ? Typeface.create(Typeface.SANS_SERIF, weight, false)
                : Typeface.create(weight >= 500 ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL);
        faces[role] = face;
        return face;
    }

    // ---------------------------------------------------------------- 颜色运算

    /** 状态层：前景按百分比铺在背景上，得到不透明结果（禁用容器、按压底色）。 */
    static int layer(int foreground, int percent, int background) {
        int alpha = Math.max(0, Math.min(100, percent)) * 255 / 100;
        int rest = 255 - alpha;
        return Color.rgb(
                (Color.red(foreground) * alpha + Color.red(background) * rest) / 255,
                (Color.green(foreground) * alpha + Color.green(background) * rest) / 255,
                (Color.blue(foreground) * alpha + Color.blue(background) * rest) / 255);
    }

    static int alpha(int color, int percent) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(100, percent)) * 255 / 100 << 24);
    }

    /** 禁用态文字：on_surface 38%，与背景混成不透明色。 */
    int disabledContent(int background) {
        return layer(onSurface, disabledContentPct, background);
    }

    /** 禁用态容器：on_surface 12%。 */
    int disabledContainer(int background) {
        return layer(onSurface, disabledContainerPct, background);
    }

    /** WCAG 2.x 相对亮度与对比度，测试与运行期自检共用。 */
    static double contrast(int foreground, int background) {
        double a = luminance(foreground);
        double b = luminance(background);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    static double luminance(int color) {
        double[] weights = {0.2126, 0.7152, 0.0722};
        double value = 0;
        for (int i = 0; i < 3; i++) {
            double channel = ((color >> (16 - i * 8)) & 255) / 255.0;
            value += weights[i] * (channel <= 0.04045 ? channel / 12.92
                    : Math.pow((channel + 0.055) / 1.055, 2.4));
        }
        return value;
    }
}
