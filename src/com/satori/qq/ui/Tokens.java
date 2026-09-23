package com.satori.qq.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

/**
 * 知弦 Design Tokens 的唯一读取口。
 *
 * <p>界面代码只从这里按语义角色取色、取尺度，不写字面值，也不直接读 {@code R.color}——
 * {@code build.sh} 先跑 javac 再跑 aapt，编译期没有 R 类，所以按名字查（只在构造时查一次并缓存）。
 * 令牌本体在 {@code res/values/tokens.xml}（颜色，深浅两份）与 {@code res/values/dimens.xml}
 * （间距/形状/字级/动效/断点）。系统与无障碍的约束、组件规范见 {@code docs/DESIGN.md}。
 *
 * <p><b>不跟随系统动态取色。</b>动态色是 M3E 的可选层，本应用为保持已验证色对与品牌一致性采用固定色板，
 * 与固定源色（{@code #6750A4}）的应用图标分叉。按本项目的裁决顺序
 * （平台原生 > 可用性/无障碍 > 产品一致性 > M3E > Carbon > Miuix），可用性与一致性都排在 M3E 之前，
 * 所以这里只认令牌里那套经过对比度验证的色板。
 */
final class Tokens {

    // ---- 字级角色（M3 十五档里用到的十三档）----
    static final int DISPLAY_SMALL = 0;
    static final int HEADLINE_LARGE = 1;
    static final int HEADLINE_MEDIUM = 2;
    static final int HEADLINE_SMALL = 3;
    static final int TITLE_LARGE = 4;
    static final int TITLE_MEDIUM = 5;
    static final int TITLE_SMALL = 6;
    static final int BODY_LARGE = 7;
    static final int BODY_MEDIUM = 8;
    static final int BODY_SMALL = 9;
    static final int LABEL_LARGE = 10;
    static final int LABEL_MEDIUM = 11;
    static final int LABEL_SMALL = 12;

    private static final String[] TYPE_NAMES = {
            "display_small", "headline_large", "headline_medium", "headline_small",
            "title_large", "title_medium", "title_small",
            "body_large", "body_medium", "body_small",
            "label_large", "label_medium", "label_small",
    };
    /** 与 TYPE_NAMES 对齐：标题族用 Medium。M3 基线里 Display/Headline 是 Regular，
     *  但中文在 Regular 下字重差不够，层级只能靠字号，可读性吃亏；无障碍优先于 M3 基线。 */
    private static final boolean[] TYPE_MEDIUM = {
            true, true, true, true,
            true, true, true,
            false, false, false,
            true, true, true,
    };

    final Activity activity;
    final boolean dark;
    private final Resources res;
    private final String pkg;

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
    final int outline, outlineVariant, scrim, shadow;

    Tokens(Activity activity) {
        this.activity = activity;
        this.res = activity.getResources();
        this.pkg = activity.getPackageName();
        this.dark = (res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;

        primary = color("md_primary");
        onPrimary = color("md_on_primary");
        primaryContainer = color("md_primary_container");
        onPrimaryContainer = color("md_on_primary_container");
        inversePrimary = color("md_inverse_primary");
        secondary = color("md_secondary");
        onSecondary = color("md_on_secondary");
        secondaryContainer = color("md_secondary_container");
        onSecondaryContainer = color("md_on_secondary_container");
        tertiary = color("md_tertiary");
        onTertiary = color("md_on_tertiary");
        tertiaryContainer = color("md_tertiary_container");
        onTertiaryContainer = color("md_on_tertiary_container");
        error = color("md_error");
        onError = color("md_on_error");
        errorContainer = color("md_error_container");
        onErrorContainer = color("md_on_error_container");
        success = color("md_success");
        onSuccess = color("md_on_success");
        successContainer = color("md_success_container");
        onSuccessContainer = color("md_on_success_container");
        warning = color("md_warning");
        onWarning = color("md_on_warning");
        warningContainer = color("md_warning_container");
        onWarningContainer = color("md_on_warning_container");
        surface = color("md_surface");
        surfaceContainerLowest = color("md_surface_container_lowest");
        surfaceContainerLow = color("md_surface_container_low");
        surfaceContainer = color("md_surface_container");
        surfaceContainerHigh = color("md_surface_container_high");
        surfaceContainerHighest = color("md_surface_container_highest");
        onSurface = color("md_on_surface");
        surfaceVariant = color("md_surface_variant");
        onSurfaceVariant = color("md_on_surface_variant");
        inverseSurface = color("md_inverse_surface");
        inverseOnSurface = color("md_inverse_on_surface");
        outline = color("md_outline");
        outlineVariant = color("md_outline_variant");
        scrim = color("md_scrim");
        shadow = color("md_shadow");
    }

    private int color(String name) {
        int id = res.getIdentifier(name, "color", pkg);
        if (id == 0) throw new IllegalStateException("missing color token: " + name);
        return activity.getColor(id);
    }

    // ---------------------------------------------------------------- 尺度

    int dp(float value) {
        return Math.round(value * res.getDisplayMetrics().density);
    }

    /** 按名字取 dp 尺寸令牌，返回像素。 */
    int dimen(String name) {
        int id = res.getIdentifier("md_" + name, "dimen", pkg);
        if (id == 0) throw new IllegalStateException("missing dimen token: md_" + name);
        return res.getDimensionPixelSize(id);
    }

    /** 按名字取整数令牌（动效时长、断点、百分比、字距）。 */
    int integer(String name) {
        int id = res.getIdentifier("md_" + name, "integer", pkg);
        if (id == 0) throw new IllegalStateException("missing integer token: md_" + name);
        return res.getInteger(id);
    }

    /** 按名字取缓动控制点令牌，返回可直接交给动画的插值器。 */
    PathInterpolator easing(String name) {
        int id = res.getIdentifier("md_easing_" + name, "array", pkg);
        if (id == 0) throw new IllegalStateException("missing easing token: md_easing_" + name);
        String[] values = res.getStringArray(id);
        if (values.length != 4) throw new IllegalStateException("easing needs 4 control points: " + name);
        return new PathInterpolator(Float.parseFloat(values[0]), Float.parseFloat(values[1]),
                Float.parseFloat(values[2]), Float.parseFloat(values[3]));
    }

    // ---------------------------------------------------------------- 字级

    int typeSize(int role) {
        return res.getDimensionPixelSize(dimenId("md_type_" + TYPE_NAMES[role] + "_size"));
    }

    int typeLine(int role) {
        return res.getDimensionPixelSize(dimenId("md_type_" + TYPE_NAMES[role] + "_line"));
    }

    /**
     * 字距，em（{@code setLetterSpacing} 的单位）。
     *
     * <p>令牌里按 M3 的表存「千分之一 sp」，换算成 em 要除以字号；再乘
     * {@code md_tracking_scale_pct}——中文界面下它是 0，理由见令牌文件里的注释。
     */
    float tracking(int role) {
        int sizeSp = Math.round(typeSize(role) / res.getDisplayMetrics().scaledDensity);
        if (sizeSp <= 0) return 0;
        float spacingSp = integer("tracking_" + TYPE_NAMES[role]) / 1000f;
        float scale = integer("tracking_scale_pct") / 100f;
        return spacingSp * scale / sizeSp;
    }

    private int dimenId(String name) {
        int id = res.getIdentifier(name, "dimen", pkg);
        if (id == 0) throw new IllegalStateException("missing dimen token: " + name);
        return id;
    }

    /**
     * 把字级令牌套到一个 TextView 上：字号、行高、字距、字重。
     *
     * <p>行内高度不是写死的高度，而是行距令牌换算出来的额外行距，所以系统字号放大到 200%
     * 时文字跟着长高，不会被裁掉（WCAG 1.4.4 / 1.4.12）。
     */
    TextView type(TextView view, int role) {
        int size = typeSize(role);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        view.setLineSpacing(Math.max(0, typeLine(role) - size), 1f);
        view.setLetterSpacing(tracking(role));
        view.setTypeface(Typeface.create(TYPE_MEDIUM[role] ? "sans-serif-medium" : "sans-serif",
                Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }

    // ---------------------------------------------------------------- 颜色运算

    /**
     * 状态层：把前景色按百分比铺到背景色上，得到不透明结果。
     *
     * <p>自己算而不是用 {@code android.graphics.ColorUtils}：那个类不在本机的 android.jar 里
     * （构建用的平台包只含公开面的一部分），自己按 alpha 混合三段通道即可，结果一致。
     */
    int layer(int foreground, int percent, int background) {
        int alpha = Math.max(0, Math.min(100, percent)) * 255 / 100;
        int rest = 255 - alpha;
        return Color.rgb(
                (Color.red(foreground) * alpha + Color.red(background) * rest) / 255,
                (Color.green(foreground) * alpha + Color.green(background) * rest) / 255,
                (Color.blue(foreground) * alpha + Color.blue(background) * rest) / 255);
    }

    static int withAlpha(int color, int percent) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(100, percent)) * 255 / 100 << 24);
    }

    /** 波纹用色：主色 12% 的状态层。 */
    int ripple(int accent) {
        return withAlpha(accent, integer("state_pressed_pct"));
    }

    ColorStateList states(int checked, int normal) {
        return new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{checked, normal});
    }

    ColorStateList enabled(int disabled, int normal) {
        return new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{disabled, normal});
    }

    /** 选中/未选中的双层状态表，用于导航项这种「选中换容器色」的控件。 */
    ColorStateList selected(int checked, int normal) {
        return new ColorStateList(new int[][]{{android.R.attr.state_selected}, {}},
                new int[]{checked, normal});
    }

    // ---------------------------------------------------------------- 形状

    GradientDrawable shape(int color, int radiusDp) {
        return shape(color, radiusDp, 0, 0);
    }

    GradientDrawable shape(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    /** 胶囊：半径按高度取半，用于需要"完全圆头"的按钮与把手。 */
    GradientDrawable pill(int color, int heightPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(heightPx / 2f);
        return drawable;
    }

    /** 带状态层的背景：波纹/按压叠色，底仍是给定形状。 */
    RippleDrawable pressable(int accent, GradientDrawable base) {
        return new RippleDrawable(ColorStateList.valueOf(ripple(accent)), base, null);
    }

    // ---------------------------------------------------------------- 窗口

    /**
     * 系统栏：底色用 surface，图标明暗跟着深浅色；并把系统栏高度作为内边距留给根布局，
     * 内容不压到状态栏与手势条下面（安全区）。系统自带的手势条对比度遮罩关掉，
     * 否则底部会出现一条与 surface 不同的灰带。
     */
    void applyWindow() {
        android.view.Window window = activity.getWindow();
        window.setStatusBarColor(surface);
        window.setNavigationBarColor(surface);
        if (Build.VERSION.SDK_INT >= 28) window.setNavigationBarDividerColor(surface);
        if (Build.VERSION.SDK_INT >= 29) window.setNavigationBarContrastEnforced(false);
        int flags = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(flags);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    /** 把状态栏与导航栏的高度加到根视图的内边距上。 */
    void padSystemBars(View root) {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(0, top, 0, bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    /** 供颜色运算与测试复用的亮度/对比度（WCAG 2.x 相对亮度）。 */
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

    static double contrast(int foreground, int background) {
        double a = luminance(foreground);
        double b = luminance(background);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    /** 调试用：把颜色打成 #RRGGBB。 */
    static String hex(int color) {
        return String.format("#%06X", color & 0xFFFFFF);
    }

    static int parse(String value) {
        return Color.parseColor(value);
    }
}
