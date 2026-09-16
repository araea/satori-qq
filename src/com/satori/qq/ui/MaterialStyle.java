package com.satori.qq.ui;

import android.app.Activity;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.view.animation.OvershootInterpolator;

/** Material 3 Expressive color roles, shape feedback and typography for the native view UI. */
final class MaterialStyle {
    final Activity activity;
    final boolean dark;
    final int surface, container, high, ink, muted, outline, primary, onPrimary,
            primaryContainer, onPrimaryContainer;

    MaterialStyle(Activity activity) {
        this.activity = activity;
        dark = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        surface = role("surface", dynamic("neutral1", dark ? 900 : 10, color("#FEF7FF", "#151218")));
        container = role("surface_container", dynamic("neutral1", dark ? 900 : 50, color("#F2ECF4", "#211E24")));
        high = role("surface_container_high", dynamic("neutral1", dark ? 800 : 100, color("#ECE6EE", "#2B282E")));
        ink = role("on_surface", dynamic("neutral1", dark ? 100 : 900, color("#1D1B20", "#E7E0E9")));
        muted = role("on_surface_variant", dynamic("neutral2", dark ? 200 : 700, color("#49454F", "#CAC4D0")));
        outline = role("outline", dynamic("neutral2", dark ? 400 : 500, color("#79747E", "#938F99")));
        primary = dynamic("accent1", dark ? 200 : 600, color("#5D438B", "#D3BCFD"));
        onPrimary = dynamic("accent1", dark ? 800 : 0, color("#FFFFFF", "#38205F"));
        primaryContainer = dynamic("accent1", dark ? 700 : 100, color("#E9DDFF", "#4F3776"));
        onPrimaryContainer = dynamic("accent1", dark ? 100 : 900, color("#210D3B", "#E9DDFF"));
    }

    private int color(String light, String night) { return Color.parseColor(dark ? night : light); }

    private int role(String name, int fallback) {
        if (Build.VERSION.SDK_INT < 34) return fallback;
        int id = activity.getResources().getIdentifier("system_" + name + (dark ? "_dark" : "_light"), "color", "android");
        return id == 0 ? fallback : activity.getColor(id);
    }

    private int dynamic(String palette, int tone, int fallback) {
        if (Build.VERSION.SDK_INT < 31) return fallback;
        int id = activity.getResources().getIdentifier("system_" + palette + "_" + tone, "color", "android");
        return id == 0 ? fallback : activity.getColor(id);
    }

    int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    GradientDrawable shape(int color, int radius) { return shape(color, radius, 0, 0); }

    GradientDrawable shape(int color, int radius, int stroke, int width) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (width > 0) d.setStroke(dp(width), stroke);
        return d;
    }

    ColorStateList states(int checked, int normal) {
        return new ColorStateList(new int[][] { { android.R.attr.state_checked }, {} },
                new int[] { checked, normal });
    }

    TextView text(String value, int size, int color, boolean medium) {
        TextView v = new TextView(activity);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setFontFeatureSettings("kern");
        v.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        v.setLineSpacing(dp(3), 1);
        return v;
    }

    Button button(String label, boolean filled) {
        GradientDrawable shape = shape(filled ? primary : primaryContainer, 28);
        Button v = new ExpressiveButton(shape);
        v.setText(label);
        v.setTextSize(14);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setAllCaps(false);
        v.setMinHeight(dp(52));
        v.setMinimumHeight(dp(52));
        v.setPadding(dp(24), dp(12), dp(24), dp(12));
        v.setStateListAnimator(null);
        v.setTextColor(new ColorStateList(new int[][] { {-android.R.attr.state_enabled}, {} },
                new int[] { muted, filled ? onPrimary : onPrimaryContainer }));
        v.setBackground(new RippleDrawable(ColorStateList.valueOf((primary & 0xFFFFFF) | 0x24000000),
                shape, null));
        v.setBackgroundTintList(new ColorStateList(new int[][] { {-android.R.attr.state_enabled}, {} },
                new int[] { high, filled ? primary : primaryContainer }));
        return v;
    }

    /** Shape and spatial feedback follows pressed state for touch, keyboard and accessibility. */
    private final class ExpressiveButton extends Button {
        private final GradientDrawable shape;
        private ValueAnimator motion;
        private float roundness = 28;

        ExpressiveButton(GradientDrawable shape) {
            super(activity);
            this.shape = shape;
        }

        @Override public void setPressed(boolean pressed) {
            if (isPressed() == pressed) return;
            super.setPressed(pressed);
            if (shape == null) return;
            if (motion != null) motion.cancel();
            float target = pressed ? 16 : 28;
            if (!ValueAnimator.areAnimatorsEnabled()) {
                roundness = target;
                shape.setCornerRadius(dp(Math.round(target)));
                setScaleX(1); setScaleY(1);
                return;
            }
            float from = roundness;
            float scale = getScaleX();
            motion = ValueAnimator.ofFloat(0, 1);
            motion.setDuration(pressed ? 140 : 350);
            motion.setInterpolator(new OvershootInterpolator(pressed ? 0 : 1.2f));
            motion.addUpdateListener(animation -> {
                float f = (float) animation.getAnimatedValue();
                roundness = from + (target - from) * f;
                shape.setCornerRadius(roundness * activity.getResources().getDisplayMetrics().density);
                float next = scale + ((pressed ? 0.97f : 1f) - scale) * f;
                setScaleX(next); setScaleY(next);
            });
            motion.start();
        }

        @Override protected void onDetachedFromWindow() {
            if (motion != null) motion.cancel();
            super.onDetachedFromWindow();
        }
    }

    void applyWindow() {
        activity.getWindow().setStatusBarColor(surface);
        activity.getWindow().setNavigationBarColor(surface);
        activity.getWindow().getDecorView().setSystemUiVisibility(dark ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        activity.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 29) activity.getWindow().setNavigationBarContrastEnforced(false);
    }
}
