package com.satori.qq.ui;

import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** 两个页面共用的骨架：滚动容器、限宽居中的内容栏、说明文字的间距。 */
final class Pages {
    private Pages() {}

    static ScrollView scroll(Tokens t) {
        ScrollView scroll = new ScrollView(t.context);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(true);
        return scroll;
    }

    /**
     * 内容栏：最宽 640dp、在滚动区里居中。宽屏上一行不会长到难读；窄屏与大字号下完全交给内容换行
     * （WCAG 1.4.10）。在测量阶段直接夹宽，一次布局到位，不会先画满宽再缩回。
     */
    static LinearLayout content(Ui ui) {
        final int max = ui.t.contentMax;
        LinearLayout content = new LinearLayout(ui.t.context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = MeasureSpec.getSize(widthSpec);
                if (width > max) widthSpec = MeasureSpec.makeMeasureSpec(max, MeasureSpec.EXACTLY);
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        content.setOrientation(LinearLayout.VERTICAL);
        int gutter = ui.layout.gutter();
        content.setPadding(gutter, 0, gutter, ui.t.space2xl);
        return content;
    }

    static FrameLayout center(Ui ui, LinearLayout content) {
        FrameLayout frame = new FrameLayout(ui.t.context);
        frame.addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        return frame;
    }

    /** 列表下方的说明：与列表文字左缘对齐。 */
    static LinearLayout.LayoutParams note(Tokens t) {
        LinearLayout.LayoutParams params = Ui.stack(t.spaceSm);
        params.setMarginStart(t.spaceLg);
        params.setMarginEnd(t.spaceLg);
        return params;
    }
}
