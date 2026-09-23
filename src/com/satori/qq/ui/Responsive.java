package com.satori.qq.ui;

import android.app.Activity;
import android.content.res.Configuration;

/**
 * 断点与栅格：Android 窗口的 compact / medium / expanded 三段，落到这台设备上。
 *
 * <p>Carbon 在这里只贡献两样东西——响应式栅格与复杂信息（诊断页的定义列表）的排布规则；
 * 断点服从 Android 窗口规范；本应用没有 Web 界面，不引入 Web 组件。
 *
 * <p>宽度取 {@code Configuration.screenWidthDp} 而不是某个 View 的宽度：View 宽度是布局的
 * 结果，拿它决定布局会自激。字号倍率单独参与判断——320dp 宽 + 200% 字号时并排的两栏会各自
 * 只剩 150dp，这时必须竖排（WCAG 1.4.10 重排）。
 */
final class Responsive {
    /** 内容区一栏的宽度上限，超过就不再拉宽，避免长行难读。 */
    final int contentMaxDp;
    private final int widthDp;
    private final float fontScale;
    private final int mediumBreakpoint, expandedBreakpoint, railDp;

    Responsive(Activity activity, Tokens tokens) {
        Configuration configuration = activity.getResources().getConfiguration();
        widthDp = configuration.screenWidthDp;
        fontScale = configuration.fontScale;
        mediumBreakpoint = tokens.integer("bp_medium");
        expandedBreakpoint = tokens.integer("bp_expanded");
        railDp = Math.round(tokens.dimen("size_nav_rail") / activity.getResources().getDisplayMetrics().density);
        float density = activity.getResources().getDisplayMetrics().density;
        contentMaxDp = Math.round((widthDp >= tokens.integer("bp_expanded")
                ? tokens.dimen("size_content_max_expanded")
                : tokens.dimen("size_content_max_compact")) / density);
    }

    boolean useRail() {
        return medium() && fontScale < 1.5f;
    }

    boolean pinSave() {
        return fontScale < 1.5f;
    }

    boolean medium() {
        return widthDp >= mediumBreakpoint;
    }

    boolean expanded() {
        return widthDp >= expandedBreakpoint;
    }

    /**
     * 是否把并列的两块排到一行。屏幕上放得下、且放大字号后每栏仍够宽时才并排。
     * 阈值按内容反推：并排后每栏至少要 168dp 才放得下「客户端 / 3」这种卡片。
     */
    boolean canPairCards() {
        if (fontScale >= 1.5f) return false;
        return Math.min(contentMaxDp, widthDp - (useRail() ? railDp : 0)) - 2 * gutterDp() >= 2 * 168 + 12;
    }

    /** 页边距：compact 16dp、medium 24dp、expanded 32dp。 */
    int gutterDp() {
        return widthDp >= expandedBreakpoint ? 32 : (widthDp >= mediumBreakpoint ? 24 : 16);
    }

    /** 卡片之间的纵向间距：紧凑时小一些，宽屏时松一些。 */
    int sectionGapDp() {
        return expanded() ? 16 : 12;
    }

    /** 运行偏好这类重复小项的列数。 */
    int preferenceColumns() {
        return expanded() && fontScale < 1.5f ? 2 : 1;
    }
}
