package com.satori.qq.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/**
 * 知弦管理页的真机验收：交互契约、设计体系与无障碍。从不发消息、不重启 QQ。
 *
 * <p>这里验的是"JVM 上验不了"的部分：令牌在运行期按名字解析得出来、可触达面积真的够大、
 * 焦点指示真的装上了、放大字号与 320dp 下文字不被截断、状态消息真的标成了 live region，
 * 以及深浅色与大字号下的实际排版（存成截图供人工复核）。
 *
 * <p>跑法见 {@code tests/ui/run.sh}；构建见 {@code tests/ui/build.sh}。
 */
public final class DesignSmoke extends Instrumentation {
    /** 与 res/values/dimens.xml 的 md_size_touch_target 对齐；这里写死是因为测试包不共享资源。 */
    private static final int TOUCH_TARGET_DP = 48;

    private boolean dark;
    private float fontScale = 1;
    private int density, widthDp;
    private Activity activity;
    private File settingsFile;
    private byte[] original;
    private boolean hadOriginal;
    private final StringBuilder report = new StringBuilder();

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        start();
    }

    @Override public Activity newActivity(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        Activity created = super.newActivity(loader, name, intent);
        Configuration config = new Configuration();
        config.uiMode = dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        config.fontScale = fontScale;
        if (density > 0) config.densityDpi = density;
        if (widthDp > 0) config.screenWidthDp = widthDp;
        created.applyOverrideConfiguration(config);
        return created;
    }

    @Override public void onStart() {
        Bundle out = new Bundle();
        try {
            settingsFile = new File(getTargetContext().getNoBackupFilesDir(), "zhixian-control.json");
            hadOriginal = settingsFile.exists();
            original = hadOriginal ? java.nio.file.Files.readAllBytes(settingsFile.toPath()) : new byte[0];
            JSONObject config = new JSONObject().put("port", 3001).put("token", "")
                    .put("status_notification", true).put("wake_lock_auto", true)
                    .put("wifi_sustain", true).put("manual_self_messages", true);
            writeSettings(config, 0, true);
            launch();
            // 先说清楚为什么分两段：instrumentation 刚起来时启动的那个实例，整棵视图树一直是
            // 0×0（消息队列空了、窗口却还没走到第一次 traversal，requestLayout 也推不动）。
            // 不需要尺寸的检查先跑；需要尺寸/像素的检查放到"重开一次实例"之后——那时布局正常。
            checkTokens();
            checkFormContract();
            checkAccessibility();
            checkHealthStates();
            checkDirtyBanner();
            checkPollingControl();
            close();
            launch();
            checkTouchTargets();
            checkFocusIndicators();
            close();

            writeSettings(config, 0, true);
            launch();
            for (int tab = 0; tab < 3; tab++) capture("light-" + tab, tab);
            close();
            dark = true;
            launch();
            for (int tab = 0; tab < 3; tab++) capture("dark-" + tab, tab);
            close();
            dark = false;
            fontScale = 2;
            widthDp = 320;
            density = Math.round(getTargetContext().getResources().getDisplayMetrics().widthPixels / 320f * 160);
            launch();
            for (int tab = 0; tab < 3; tab++) {
                showTab(tab);
                java.util.List<String> clipped = new java.util.ArrayList<>();
                ui(() -> bounds(activity.getWindow().getDecorView(), clipped));
                check(clipped.isEmpty(), "大字号 + 320dp 下第 " + (tab + 1) + " 页文字完整"
                        + (clipped.isEmpty() ? "" : "（被截断：" + clipped.subList(0, Math.min(3, clipped.size())) + "）"));
                capture("large-" + tab, tab);
            }
            checkLargeDialog();
            close();
            fontScale = 1;
            widthDp = 900;
            density = Math.round(getTargetContext().getResources().getDisplayMetrics().widthPixels / 900f * 160);
            launch();
            checkRailAndDock();
            for (int tab = 0; tab < 3; tab++) capture("wide-" + tab, tab);
            close();
            restore();
            out.putString("stream", "\n" + report + "PASS: 知弦界面\n");
            finish(Activity.RESULT_OK, out);
        } catch (Throwable error) {
            try { if (activity != null) close(); restore(); } catch (Throwable ignored) {}
            out.putString("stream", "\n" + report + "FAIL: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, out);
        }
    }

    /**
     * 切到某一页，并保证这一页真的量过。
     *
     * <p>为什么不能只靠 {@code waitForIdleSync()}：这个环境里切页之后的那次布局遍历不一定来
     * ——实测切到设置页后整页仍是 0×0（主队列已空，窗口却没有再走一次 traversal），
     * 于是所有按尺寸和像素做的检查都会变成空跑。所以这里退一步：没量过就自己量一次，
     * 做的事与一次真实遍历相同（父容器尺寸用父容器的，父容器没量过就退回显示区尺寸）。
     */
    private void showTab(int tab) throws Exception {
        ui(() -> call("switchTab", new Class[]{int.class, boolean.class}, tab, false));
        waitForIdleSync();
        final ScrollView page = ((ScrollView[]) field(activity, "pages"))[tab];
        for (int i = 0; i < 100 && !measured(page); i++) {
            ui(() -> {
                View parent = (View) page.getParent();
                android.util.DisplayMetrics metrics = getTargetContext().getResources().getDisplayMetrics();
                int width = parent.getWidth() > 0 ? parent.getWidth() : metrics.widthPixels;
                int height = parent.getHeight() > 0 ? parent.getHeight() : metrics.heightPixels;
                page.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                page.layout(0, 0, width, height);
            });
        }
        check(measured(page), "第 " + tab + " 页已完成测量：" + size(page));
    }

    private static boolean measured(View view) {
        return view.getWidth() > 0 && view.getHeight() > 0;
    }

    private static String size(View view) {
        return view.getWidth() + "x" + view.getHeight();
    }

    // ------------------------------------------------------------------ 令牌

    /**
     * 令牌在运行期解析出来了没有、解析出来的是不是那套过了对比度的值。
     * 界面按名字查资源（编译期没有 R 类），名字拼错只会在真机上炸，所以这条是必要守门。
     */
    private void checkTokens() throws Exception {
        Object tokens = field(activity, "ui");
        int ink = color(tokens, "onSurface");
        int surface = color(tokens, "surface");
        int muted = color(tokens, "onSurfaceVariant");
        int container = color(tokens, "surfaceContainer");
        int primary = color(tokens, "primary");
        int onPrimary = color(tokens, "onPrimary");
        check(contrast(ink, surface) >= 4.5, "正文/表面对比度 " + round(contrast(ink, surface)));
        check(contrast(muted, surface) >= 4.5, "次要文字/表面对比度 " + round(contrast(muted, surface)));
        check(contrast(muted, container) >= 4.5, "次要文字/容器对比度 " + round(contrast(muted, container)));
        check(contrast(onPrimary, primary) >= 4.5, "实心按钮文字对比度 " + round(contrast(onPrimary, primary)));
        for (String tone : new String[]{"success", "warning", "error", "primary"}) {
            int fill = color(tokens, tone + "Container");
            int text = color(tokens, "on" + Character.toUpperCase(tone.charAt(0)) + tone.substring(1) + "Container");
            check(contrast(text, fill) >= 4.5, tone + " 状态条对比度 " + round(contrast(text, fill)));
        }
        check(color(tokens, "outline") != 0, "outline 令牌解析成功");
    }

    // ------------------------------------------------------------------ 交互契约

    private void checkFormContract() throws Exception {
        ui(() -> check(!view("saveButton").isEnabled(), "干净表单下保存按钮不可用"));
        EditText port = (EditText) view("portInput");
        EditText token = (EditText) view("tokenInput");
        ui(() -> {
            port.setText("999");
            call("save", new Class[]{boolean.class}, false);
        });
        check(port.getError() != null, "非法端口就地报错");
        ui(() -> {
            port.setText("3101");
            token.setText("test-secret");
            call("save", new Class[]{boolean.class}, false);
        });
        await("保存设置落盘", () -> !((Boolean) field(activity, "saving")));
        JSONObject saved = new JSONObject(new String(
                java.nio.file.Files.readAllBytes(settingsFile.toPath()), "UTF-8")).getJSONObject("overrides");
        check(saved.getInt("port") == 3101 && saved.getString("token").equals("test-secret"), "设置已落盘");

        Bundle bridge = getTargetContext().getContentResolver()
                .call(Uri.parse("content://com.satori.qq.control"), "bootstrap", null, null);
        check(bridge != null && bridge.getLong("revision") == 1, "宿主可读到修订号");
        ClassLoader loader = getTargetContext().getClassLoader();
        Class<?> cfgClass = loader.loadClass("com.satori.qq.Cfg");
        Object cfg = cfgClass.newInstance();
        Class<?> bridgeClass = loader.loadClass("com.satori.qq.control.ControlBridge");
        long applied = (Long) bridgeClass.getMethod("bootstrap", android.content.Context.class, cfgClass, String.class)
                .invoke(null, getTargetContext(), cfg, "0.25.0");
        check(applied == 1 && cfgClass.getField("port").getInt(cfg) == 3101, "bootstrap 应用端口");
        check(cfgClass.getField("token").get(cfg).equals("test-secret"), "bootstrap 应用令牌");

        ui(() -> {
            port.setText("3201");
            token.setText("unsaved-secret");
            call("reveal", new Class[]{boolean.class}, true);
        });
        check((activity.getWindow().getAttributes().flags
                & android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0, "显示令牌时禁止截屏");
        ui(() -> call("switchTab", new Class[]{int.class, boolean.class}, 2, false));
        check(token.getTransformationMethod() != null, "离开设置页后令牌重新隐藏");
        ui(() -> call("switchTab", new Class[]{int.class, boolean.class}, 1, false));

        // 先确认"我们自己的保存契约"：写进实例状态的是草稿本身。
        Bundle draftState = new Bundle();
        java.lang.reflect.Method saveState = Activity.class
                .getDeclaredMethod("onSaveInstanceState", Bundle.class);
        saveState.setAccessible(true);
        ui(() -> {
            try {
                saveState.invoke(activity, draftState);
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        check("3201".equals(draftState.getString("port")) && draftState.getInt("tab") == 1,
                "草稿写入实例状态：port=" + draftState.getString("port")
                        + " tab=" + draftState.getInt("tab"));

        // recreate() 在本机不可靠：投给新实例的是任务里那份旧实例状态（实测草稿存的是
        // 3201/1，新实例起来是 3001/0），而且新实例在 instrumentation 里不会被布局
        // ——后面按尺寸和像素做的检查全都会跟着失效。所以这里不跑 recreate：
        // 保存端由上面"草稿写入实例状态"钉住（键名 + 取值），恢复端的键名一致由
        // tests/UiContractTest 钉住。
    }

    private void checkHealthStates() throws Exception {
        showTab(0);
        ui(() -> {
            set(activity, "health", null);
            set(activity, "checkedAt", System.currentTimeMillis());
            call("updateState", new Class[0]);
        });
        check(text("stateTitle").equals("尚未连接"), "探测失败清掉在线状态：" + text("stateTitle"));
        check(toneOf("hero").equals("md_error_container"), "未连接时状态面板用错误色");

        final JSONObject online = new JSONObject()
                .put("name", "satori-qq").put("version", version()).put("online", true).put("listening", true)
                .put("connections", 1).put("qq_version", "9.3.65").put("config_revision", 1)
                .put("online_since_epoch_ms", System.currentTimeMillis() - 7_200_000)
                .put("notice", "enabled/posted=yes")
                .put("compat", new JSONObject().put("passed", 204).put("total", 204))
                .put("sso", new JSONObject().put("failures", 0).put("session_errors", 0));
        ui(() -> {
            set(activity, "health", online);
            set(activity, "checkedAt", System.currentTimeMillis());
            call("updateState", new Class[0]);
            view("progress").setVisibility(View.INVISIBLE);
        });
        check(text("stateTitle").equals("连接就绪"), "在线时状态为连接就绪：" + text("stateTitle"));
        check(toneOf("hero").equals("md_success_container"), "在线时状态面板用成功色");
        check(row("diagnosticRows", 1).equals("204 / 204"), "诊断页内核接口用读到的值：" + row("diagnosticRows", 1));
        check(row("diagnosticRows", 0).equals("9.3.65"), "诊断页 QQ 版本用读到的值：" + row("diagnosticRows", 0));
        check(text("configurationStatus").equals("已保存的设置已生效"), "配置状态按修订号判定");
        check(((TextView) view("diagnostics")).getVisibility() != View.VISIBLE, "在线时隐藏离线提示");
    }

    private void checkDirtyBanner() throws Exception {
        showTab(1);
        // 先回到"已保存"的状态：上一步留下的是未保存的 3201 / unsaved-secret。
        ui(() -> {
            edit("portInput").setText("3101");
            edit("tokenInput").setText("test-secret");
        });
        check(view("dirtyCard").getVisibility() != View.VISIBLE, "与已保存一致时不显示修改提示");
        check(!view("saveButton").isEnabled(), "无修改时保存按钮不可用");
        ui(() -> edit("tokenInput").setText("dirty-check"));
        check(view("dirtyCard").getVisibility() == View.VISIBLE, "有未保存修改时出现提示");
        check(view("saveButton").isEnabled(), "有修改时保存按钮可用");
        ui(() -> edit("tokenInput").setText("test-secret"));
        check(view("dirtyCard").getVisibility() != View.VISIBLE, "改动还原后提示消失");
    }

    // ------------------------------------------------------------------ 无障碍

    private void checkAccessibility() throws Exception {
        showTab(1);
        for (String name : new String[]{"stateTitle", "stateDetail", "configurationStatus", "diagnosticHint"}) {
            TextView view = (TextView) view(name);
            check(view.getAccessibilityLiveRegion() == View.ACCESSIBILITY_LIVE_REGION_POLITE,
                    name + " 是状态消息，必须标成 polite live region");
        }
        for (int i = 0; i < 3; i++) {
            View item = ((View[]) field(activity, "navigation"))[i];
            check(item.getContentDescription() != null, "导航项 " + i + " 有可读名称");
            check(item.isFocusable(), "导航项 " + i + " 可获得焦点");
        }
        AtomicReference<String> headings = new AtomicReference<>("");
        ui(() -> {
            int tab = (Integer) field(activity, "selected");
            View page = ((ScrollView[]) field(activity, "pages"))[tab];
            headings.set("当前页 " + tab + "：可见 " + countHeadings(page) + " / 全页 " + countHeadings(page, false));
        });
        check(Integer.parseInt(headings.get().replaceAll(".*：可见 (\\d+) .*", "$1")) >= 3,
                "每页至少有三个标题（" + headings.get() + "）");
        check(hasLabel(activity.getWindow().getDecorView(), view("portInput").getId()), "端口输入框关联了标签");
        check(view("refreshButton").getContentDescription() != null, "只有图标的刷新按钮有可读名称");
    }

    /** 可触达面积：WCAG 2.5.8 与 Android 平台都要求足够大，这里按 48dp 卡。 */
    private void checkTouchTargets() throws Exception {
        final int dp = Math.round(TOUCH_TARGET_DP
                * getTargetContext().getResources().getDisplayMetrics().density);
        AtomicReference<String> small = new AtomicReference<>();
        for (int tab = 0; tab < 3; tab++) {
            showTab(tab);
            ui(() -> {
                findSmall(activity.getWindow().getDecorView(), dp, small);
            });
            waitForIdleSync();
            check(small.get() == null, "第 " + (tab + 1) + " 页可点控件都不小于 48dp" + (small.get() == null ? "" : "（" + small.get() + "）"));
        }
    }

    /** 焦点可见：聚焦时必须有可见变化（WCAG 2.4.7）。这一步靠画像素判断，不猜实现。 */
    private void checkFocusIndicators() throws Exception {
        // 挑的都是"一直是启用"的控件：saveButton 在表单干净时是禁用的（这是它该有的样子），
        // 禁用视图拿不到焦点，拿它验焦点环会验错东西。
        showTab(1);
        ui(() -> {
            checkRingToggles("revealButton", "onSecondaryContainer");
            checkInputRing();
        });
        showTab(0);
        ui(() -> {
            checkRingToggles("refreshButton", "primary");
            View[] nav = (View[]) field(activity, "navigation");
            for (int i = 0; i < nav.length; i++) checkRingToggles(nav[i], "navigation " + i, "primary");
        });
        showTab(1);
        ui(() -> {
            View[] toggles = (View[]) field(activity, "toggles");
            for (int i = 0; i < toggles.length; i++) checkRingToggles(toggles[i], "switch " + i, "primary");
            check((edit("tokenInput").getInputType() & android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0,
                    "令牌使用密码输入语义");
        });
    }

    /** 按钮类：背景里有一层独立的焦点环，未聚焦透明、聚焦后不透明，颜色与底色有对比。 */
    private void checkRingToggles(String name, String ringRole) {
        checkRingToggles(view(name), name, ringRole);
    }

    private void checkRingToggles(View view, String name, String ringRole) {
        check(view.getWidth() > 0 && view.getHeight() > 0, name + " 已完成测量："
                + view.getWidth() + "x" + view.getHeight()
                + " vis=" + view.getVisibility() + " attached=" + view.isAttachedToWindow()
                + " tab=" + (Integer) field(activity, "selected")
                + " 页宽=" + ((ScrollView[]) field(activity, "pages"))[1].getWidth()
                + "x" + ((ScrollView[]) field(activity, "pages"))[1].getHeight());
        check(view.isFocusable(), name + " 可获得焦点");
        Drawable ring = ringLayer(view);
        check(ring != null, name + " 的背景里有独立的焦点环图层");
        int size = Math.max(view.getHeight(), 8);
        check(edgeAlpha(ring, size) == 0, name + " 未聚焦时焦点环不可见");
        view.setFocusableInTouchMode(true);
        check(view.requestFocus(), name + " 可以拿到焦点");
        check(edgeAlpha(ring, size) != 0, name + " 聚焦后焦点环可见");
        check(edgeColor(ring, size) == color(field(activity, "ui"), ringRole),
                name + " 的焦点环用 " + ringRole + "（与底色有对比）");
        view.clearFocus();
        view.setFocusableInTouchMode(false);
    }

    /** 输入框：靠描边换色表达焦点，未聚焦是 outline、聚焦是主色。 */
    private void checkInputRing() {
        EditText input = (EditText) view("portInput");
        check(input.getHeight() > 0, "输入框已完成测量");
        int size = input.getHeight();
        check(edgeColor(input.getBackground(), size) == color(field(activity, "ui"), "outline"),
                "输入框未聚焦时描边用 outline");
        input.setFocusableInTouchMode(true);
        check(input.requestFocus(), "输入框可以拿到焦点");
        check(edgeColor(input.getBackground(), size) == color(field(activity, "ui"), "primary"),
                "输入框聚焦后描边换主色");
        input.clearFocus();
        input.setFocusableInTouchMode(false);
    }

    // ------------------------------------------------------------------ 视图遍历

    private static void findSmall(View view, int minimum, AtomicReference<String> found) {
        if (found.get() != null || view.getVisibility() != View.VISIBLE) return;
        if (view.getWidth() == 0 && view.getHeight() == 0) return;
        if (view.isClickable() && (view.getWidth() < minimum || view.getHeight() < minimum)) {
            found.set(view.getClass().getSimpleName() + " "
                    + view.getWidth() + "x" + view.getHeight() + "px"
                    + (view.getContentDescription() == null ? "" : " (" + view.getContentDescription() + ")"));
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) findSmall(group.getChildAt(i), minimum, found);
        }
    }

    private static boolean hasLabel(View view, int targetId) {
        if (view.getLabelFor() == targetId) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (hasLabel(group.getChildAt(i), targetId)) return true;
        }
        return false;
    }

    private static int countHeadings(View view) {
        return countHeadings(view, true);
    }

    private static int countHeadings(View view, boolean visibleOnly) {
        if (visibleOnly && view.getVisibility() != View.VISIBLE) return 0;
        int total = 0;
        if (view instanceof TextView && android.os.Build.VERSION.SDK_INT >= 28
                && ((TextView) view).isAccessibilityHeading()) total++;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) total += countHeadings(group.getChildAt(i), visibleOnly);
        }
        return total;
    }

    /**
     * 画一层 drawable，读回左边中点的像素。用来量"焦点环有没有真的画出来"——
     * GradientDrawable 不公开描边宽度与描边色，读私有的 mGradientState 又随版本变，
     * 直接量像素是最稳的判据。
     */
    private static int edgeColor(Drawable drawable, int size) {
        if (drawable == null || size <= 2) return 0;
        android.graphics.Rect original = new android.graphics.Rect(drawable.getBounds());
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, size, size);
        drawable.draw(canvas);
        int pixel = bitmap.getPixel(1, size / 2);
        drawable.setBounds(original);
        bitmap.recycle();
        return pixel;
    }

    private static int edgeAlpha(Drawable drawable, int size) {
        return android.graphics.Color.alpha(edgeColor(drawable, size));
    }

    /** 焦点环是背景里独立的一层（Ripple → 内容层 → [底色, 焦点环]）。 */
    private static Drawable ringLayer(View view) {
        Drawable background = view.getBackground();
        if (background instanceof RippleDrawable) background = ((RippleDrawable) background).getDrawable(0);
        if (background instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) background;
            if (layers.getNumberOfLayers() >= 2) return layers.getDrawable(layers.getNumberOfLayers() - 1);
        }
        return null;
    }

    /**
     * 放大字号下不许截断：每个 TextView 的绘制高度不能超过它拿到的高度。
     * 这是 WCAG 1.4.4（放大 200%）与 1.4.12（行距）在真机上唯一可靠的判据。
     */
    private void bounds(View view, java.util.List<String> clipped) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView && !(view instanceof EditText)) {
            TextView text = (TextView) view;
            if (text.getLayout() != null
                    && text.getLayout().getHeight()
                    > text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom()) {
                clipped.add(text.getText().toString());
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) bounds(group.getChildAt(i), clipped);
        }
    }

    private void checkPollingControl() throws Exception {
        showTab(0);
        await("探测结束", () -> !((Boolean) field(activity, "probing")));
        ui(() -> {
            view("pollingButton").performClick();
            check(!((Boolean) field(activity, "autoRefresh")), "可以暂停自动刷新");
            android.os.Handler handler = (android.os.Handler) field(activity, "main");
            check(!handler.hasCallbacks((Runnable) field(activity, "poll")), "暂停后移除刷新任务");
            view("pollingButton").performClick();
            check((Boolean) field(activity, "autoRefresh"), "可以恢复自动刷新");
        });
    }

    private void checkLargeDialog() throws Exception {
        AtomicReference<android.app.Dialog> shown = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean accepted = new java.util.concurrent.atomic.AtomicBoolean();
        ui(() -> {
            try {
                Object widgets = field(activity, "widgets");
                Method confirm = widgets.getClass().getDeclaredMethod("confirm", String.class, String.class,
                        String.class, boolean.class, Runnable.class);
                confirm.setAccessible(true);
                shown.set((android.app.Dialog) confirm.invoke(widgets, "停止保活并关闭 QQ？",
                        "停止后不会自动拉起。需要恢复时，请重新开启守护。", "确认关闭", true,
                        (Runnable) () -> accepted.set(true)));
            } catch (Exception error) { throw new RuntimeException(error); }
        });
        waitForIdleSync();
        ui(() -> {
            android.app.Dialog dialog = shown.get();
            View decor = dialog.getWindow().getDecorView();
            if (decor.getWidth() == 0) {
                decor.measure(View.MeasureSpec.makeMeasureSpec(dialog.getWindow().getAttributes().width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(activity.getResources().getDisplayMetrics().heightPixels, View.MeasureSpec.AT_MOST));
                decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
            }
            java.util.List<String> clipped = new java.util.ArrayList<>();
            bounds(decor, clipped);
            check(clipped.isEmpty(), "200% 字号确认弹窗文字完整：" + clipped);
            check(dialog.getCurrentFocus() instanceof TextView
                    && "取消".contentEquals(((TextView) dialog.getCurrentFocus()).getText()), "危险确认默认聚焦取消");
            dialog.cancel();
            check(!accepted.get(), "取消弹窗不会执行危险操作");
        });
    }

    private void checkRailAndDock() throws Exception {
        showTab(1);
        ui(() -> {
            View nav = (View) ((View[]) field(activity, "navigation"))[0].getParent();
            check(((android.widget.LinearLayout) nav).getOrientation() == android.widget.LinearLayout.VERTICAL,
                    "900dp 使用侧边导航");
            View dock = view("saveDock");
            ScrollView page = ((ScrollView[]) field(activity, "pages"))[1];
            check(dock.getParent() == page.getParent().getParent(), "保存区独立于滚动内容");
            check(dock.getVisibility() == View.VISIBLE, "设置页显示保存区");
            call("switchTab", new Class[]{int.class, boolean.class}, 0, false);
            check(dock.getVisibility() == View.GONE, "状态页隐藏保存区");
        });
    }

    // ------------------------------------------------------------------ 截图

    private void capture(String name, int tab) throws Exception {
        showTab(tab);
        // 先只改数据，让文案变更走完一次测量/布局，再截图——否则画的是改动前的尺寸，文字会被裁。
        ui(() -> {
            try {
                JSONObject fixture = new JSONObject().put("name", "satori-qq").put("version", version())
                        .put("online", true).put("listening", true).put("connections", 1)
                        .put("qq_version", "9.3.65").put("config_revision", 0)
                        .put("online_since_epoch_ms", System.currentTimeMillis() - 7_200_000)
                        .put("notice", "enabled/posted=yes").put("keepalive", "fgs=on")
                        .put("compat", new JSONObject().put("passed", 204).put("total", 204))
                        .put("sso", new JSONObject().put("failures", 0).put("session_errors", 0));
                set(activity, "health", fixture);
                set(activity, "checkedAt", System.currentTimeMillis());
                call("updateState", new Class[0]);
                view("progress").setVisibility(View.INVISIBLE);
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        waitForIdleSync();
        // 看守状态是另一条线程问 root 要的，等它落定了再画，截图里才是真实状态。
        await("守护状态读出", () -> !((TextView) view("guardState")).getText().toString().startsWith("正在读取"));
        ui(() -> {
            try {
                ScrollView scroll = ((ScrollView[]) field(activity, "pages"))[tab];
                View content = scroll.getChildAt(0);
                View nav = (View) ((View[]) field(activity, "navigation"))[0].getParent();
                View dock = view("saveDock");
                boolean rail = ((android.widget.LinearLayout) nav).getOrientation() == android.widget.LinearLayout.VERTICAL;
                boolean pinned = dock.getParent() == scroll.getParent().getParent() && dock.getVisibility() == View.VISIBLE;
                int dockHeight = pinned ? dock.getHeight() : 0;
                check(content.getWidth() > 0 && content.getHeight() > 0, "已测量 " + name);
                if (tab == 0) {
                    int limit = Math.round(16 * activity.getResources().getDisplayMetrics().density);
                    check(view("progress").getHeight() <= limit, "波形高度受限 " + name);
                }
                int totalWidth = content.getWidth() + (rail ? nav.getWidth() : 0);
                int totalHeight = content.getHeight() + dockHeight + (rail ? 0 : nav.getHeight());
                float scale = (rail ? 1200f : 600f) / totalWidth;
                Bitmap bitmap = Bitmap.createBitmap(Math.round(totalWidth * scale),
                        Math.round(totalHeight * scale), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                canvas.scale(scale, scale);
                canvas.drawColor(color(field(activity, "ui"), "surface"));
                if (rail) {
                    nav.draw(canvas);
                    canvas.translate(nav.getWidth(), 0);
                }
                content.draw(canvas);
                canvas.translate(0, content.getHeight());
                if (pinned) {
                    dock.draw(canvas);
                    canvas.translate(0, dockHeight);
                }
                if (!rail) nav.draw(canvas);
                File dir = new File(getTargetContext().getFilesDir(), "design-review");
                dir.mkdirs();
                try (FileOutputStream output = new FileOutputStream(new File(dir, name + ".png"))) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                }
                bitmap.recycle();
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
    }

    // ------------------------------------------------------------------ 辅助

    private void launch() {
        activity = startActivitySync(new Intent()
                .setClassName(getTargetContext(), "com.satori.qq.ui.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitForIdleSync();
    }

    private void close() {
        ui(() -> activity.finish());
        waitForIdleSync();
        activity = null;
    }

    private void writeSettings(JSONObject overrides, long revision, boolean overridesPresent) throws Exception {
        JSONObject all = new JSONObject().put("revision", revision);
        if (overridesPresent) all.put("overrides", overrides);
        java.nio.file.Files.write(settingsFile.toPath(), all.toString().getBytes("UTF-8"));
    }

    private void restore() {
        if (settingsFile == null) return;
        try {
            if (hadOriginal) java.nio.file.Files.write(settingsFile.toPath(), original);
            else java.nio.file.Files.deleteIfExists(settingsFile.toPath());
            check(true, "原始设置已还原");
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private String version() throws Exception {
        return getTargetContext().getPackageManager()
                .getPackageInfo("com.satori.qq", 0).versionName;
    }

    private String row(String fieldName, int index) {
        Object[] rows = (Object[]) field(activity, fieldName);
        return (String) ((TextView) field(rows[index], "value")).getText();
    }

    /** 读一个容器卡片的底色，用来断言语义色真的换了（而不是只有文字变）。 */
    private String toneOf(String fieldName) {
        View view = view(fieldName);
        Drawable background = view.getBackground();
        int fill = background instanceof GradientDrawable
                ? ((GradientDrawable) background).getColor().getDefaultColor() : 0;
        for (String role : new String[]{"successContainer", "warningContainer", "errorContainer",
                "primaryContainer", "surfaceContainerHigh"}) {
            if (color(field(activity, "ui"), role) == fill) return name(role);
        }
        return "unknown:" + Integer.toHexString(fill);
    }

    private static String name(String role) {
        return "md_" + role.replaceAll("([A-Z])", "_$1").toLowerCase(java.util.Locale.ROOT);
    }

    private String text(String fieldName) {
        return ((TextView) view(fieldName)).getText().toString();
    }

    private static int color(Object tokens, String name) {
        return (Integer) field(tokens, name);
    }

    private View view(String name) {
        return (View) field(activity, name);
    }

    private EditText edit(String name) {
        return (EditText) view(name);
    }

    private void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        report.append("ok ").append(label).append('\n');
    }

    private static Object field(Object target, String name) {
        try {
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (NoSuchFieldException ignored) {}
            }
            throw new NoSuchFieldException(name);
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private static void set(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private void call(String name, Class<?>[] types, Object... args) {
        try {
            Method method = activity.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(activity, args);
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private static double contrast(int foreground, int background) {
        double a = luminance(foreground);
        double b = luminance(background);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(int color) {
        double[] weights = {0.2126, 0.7152, 0.0722};
        double value = 0;
        for (int i = 0; i < 3; i++) {
            double channel = ((color >> (16 - i * 8)) & 255) / 255.0;
            value += weights[i] * (channel <= 0.04045 ? channel / 12.92
                    : Math.pow((channel + 0.055) / 1.055, 2.4));
        }
        return value;
    }

    private static String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private interface Condition {
        boolean done();
    }

    private void await(String label, Condition condition) throws Exception {
        for (int i = 0; i < 400; i++) {
            AtomicReference<Boolean> result = new AtomicReference<>(false);
            ui(() -> result.set(condition.done()));
            if (result.get()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("等待超时：" + label);
    }

    private void ui(Runnable task) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> {
            try {
                task.run();
            } catch (Throwable throwable) {
                error.set(throwable);
            }
        });
        if (error.get() != null) throw new AssertionError(error.get());
    }
}
