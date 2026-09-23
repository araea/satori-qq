package com.satori.qq.ui;

import android.content.res.ColorStateList;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * 组件工厂：把令牌组装成页面用的积木。依赖方向固定为 令牌 → 组件 → 页面。
 *
 * <p>各体系在这里的分工（冲突按 平台原生 &gt; 可用性与无障碍 &gt; 产品一致性 &gt; M3E &gt; Carbon &gt; Miuix）：
 * M3E 给颜色角色、字级、形状刻度、弹簧与形状形变、分段列表与连接按钮组；HIG 给交互原则——
 * 每屏一个主操作、破坏性操作先确认、即时反馈、保留草稿；Carbon 给表单与定义列表的结构和重排；
 * Miuix 只给大容器的连续曲率与分组标题的留白节奏。
 */
final class Ui {
    final Tokens t;
    final Layout layout;

    Ui(Tokens tokens, Layout layout) {
        this.t = tokens;
        this.layout = layout;
    }

    // ------------------------------------------------------------------ 文本

    TextView text(CharSequence value, int role, int color) {
        TextView view = t.type(new TextView(t.context), role);
        view.setText(value);
        view.setTextColor(color);
        return view;
    }

    /** 标题：读屏可按标题跳转（WCAG 1.3.1 / 2.4.6）。 */
    TextView heading(CharSequence value, int role, int color) {
        TextView view = text(value, role, color);
        if (Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
        return view;
    }

    static <T extends View> T live(T view) {
        view.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        return view;
    }

    /** 只在内容真的变了才写：避免轮询时反复触发重新布局与 live region 播报。 */
    static void set(TextView view, CharSequence value) {
        if (!TextUtils.equals(view.getText(), value)) view.setText(value);
    }

    // ------------------------------------------------------------------ 布局

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(t.context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    LinearLayout row() {
        LinearLayout layout = new LinearLayout(t.context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setBaselineAligned(false);
        return layout;
    }

    static LinearLayout.LayoutParams stack(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = top;
        return params;
    }

    static LinearLayout.LayoutParams share(int start) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMarginStart(start);
        return params;
    }

    // ------------------------------------------------------------------ 分组

    /** 分组标题：小字、次要色，与列表文字左缘对齐（Miuix 的分组节奏）。 */
    TextView sectionTitle(String title) {
        TextView view = heading(title, Tokens.TITLE_SMALL, t.onSurfaceVariant);
        view.setPaddingRelative(t.spaceLg, 0, t.spaceLg, t.spaceSm);
        return view;
    }

    Group group() {
        return new Group();
    }

    /**
     * M3E 分段列表：各项之间 2dp 缝隙，组首尾大圆角、中间小圆角。
     * 某项隐藏后调用 {@link #refresh()} 重新分配圆角。
     */
    final class Group extends LinearLayout {
        private final List<Item> items = new ArrayList<>();

        Group() {
            super(t.context);
            setOrientation(VERTICAL);
        }

        Item add(Item item) {
            items.add(item);
            addView(item, stack(items.size() == 1 ? 0 : t.space2xs));
            refresh();
            return item;
        }

        void refresh() {
            Item first = null, last = null;
            for (Item item : items) {
                if (item.getVisibility() == GONE) continue;
                if (first == null) first = item;
                last = item;
            }
            boolean gap = false;
            for (Item item : items) {
                if (item.getVisibility() == GONE) continue;
                ((LayoutParams) item.getLayoutParams()).topMargin = gap ? t.space2xs : 0;
                gap = true;
                item.corners(item == first ? t.shapeXl : t.shapeXs, item == last ? t.shapeXl : t.shapeXs);
            }
            requestLayout();
        }
    }

    // ------------------------------------------------------------------ 按钮

    Btn button(String label, int style) {
        return new Btn(t, label, style, false);
    }

    Btn prominent(String label, int style) {
        return new Btn(t, label, style, true);
    }

    /**
     * 标准图标按钮：40dp 可视圆、48dp 点击区域，名称由 contentDescription 承担。
     */
    ImageButton iconButton(int kind, String description, int color) {
        ImageButton view = new ImageButton(t.context);
        Icon icon = new Icon(t, kind, color);
        view.setImageDrawable(icon);
        view.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        int inset = (t.touchTarget - t.iconButton) / 2;
        Shape shape = Shape.round(0, Shape.FULL).ring(t.primary, t.focusRing);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(Tokens.alpha(color, t.pressedPct)),
                new InsetDrawable(shape, inset), new InsetDrawable(shape.mask(), inset)));
        view.setContentDescription(description);
        view.setMinimumWidth(t.touchTarget);
        view.setMinimumHeight(t.touchTarget);
        view.setPadding(0, 0, 0, 0);
        if (Build.VERSION.SDK_INT >= 26) view.setTooltipText(description);
        return view;
    }

    static void setIcon(ImageButton button, Tokens t, int kind, int color) {
        button.setImageDrawable(new Icon(t, kind, color));
    }

    /**
     * M3E 连接按钮组：同一组相关操作并排，2dp 缝隙，内侧小圆角、外侧胶囊。
     * 放不下（大字号或窄屏）时竖排，各自恢复完整胶囊。
     */
    LinearLayout connected(Btn... buttons) {
        boolean stacked = layout.stackActions();
        LinearLayout group = stacked ? column() : row();
        for (int i = 0; i < buttons.length; i++) {
            Btn button = buttons[i];
            if (stacked) {
                group.addView(button, stack(i == 0 ? 0 : t.spaceSm));
                continue;
            }
            float inner = t.shapeSm;
            float start = i == 0 ? Shape.FULL : inner;
            float end = i == buttons.length - 1 ? Shape.FULL : inner;
            button.corners(start, end, end, start);
            group.addView(button, share(i == 0 ? 0 : t.space2xs));
        }
        return group;
    }

    // ------------------------------------------------------------------ 数据

    /**
     * Carbon 定义列表的一行：标签与值。宽屏同一行（标签定宽），窄屏或大字号上下排；值永不截断。
     */
    TextView dataRow(LinearLayout parent, String label, boolean first) {
        boolean inline = layout.inlineData();
        LinearLayout line = inline ? row() : column();
        if (inline) line.setGravity(Gravity.TOP);
        TextView name = text(label, Tokens.BODY_MEDIUM, t.onSurfaceVariant);
        TextView value = text("—", Tokens.BODY_MEDIUM, t.onSurface);
        if (inline) {
            line.addView(name, new LinearLayout.LayoutParams(t.dp(120), -2));
            line.addView(value, share(t.spaceMd));
        } else {
            line.addView(name, stack(0));
            line.addView(value, stack(t.space2xs));
        }
        // 读屏把"标签 + 值"作为一个整体读；键盘焦点不停在这种不可操作的行上。
        if (Build.VERSION.SDK_INT >= 28) line.setScreenReaderFocusable(true);
        parent.addView(line, stack(first ? 0 : t.spaceMd));
        return value;
    }
}
