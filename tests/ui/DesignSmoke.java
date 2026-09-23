package com.satori.qq.test;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Layout;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/**
 * 知弦管理界面的真机验收：交互契约、无障碍语义与实际排版。从不发消息、不重启 QQ，
 * 只读写知弦自己的设置文件，跑完还原。
 *
 * <p>验 JVM 上验不了的部分：可触达面积真的 ≥ 48dp；键盘焦点真的画出了焦点环；读屏角色与状态；
 * 状态文字是 live region；放大到 200% 字号、320dp 宽时文字不被截断；900dp 宽时两栏并列；
 * 表单校验、保存、草稿保留、令牌隐私与危险操作确认。深浅色、大字号、宽屏的长页截图留给人工复核。
 *
 * <p>视图按 {@code res/values/ids.xml} 的稳定 id 查找；注入测试数据时按名字反射的那几个字段与方法
 * 由 {@code tests/UiContractTest} 在 JVM 上钉住。
 */
public final class DesignSmoke extends Instrumentation {
    private static final String APP = "com.satori.qq";
    private static final int TOUCH_DP = 48;

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
            writeSettings();

            launch();
            fixture("ready");
            checkHomeStates();
            checkAccessibility();
            checkRefreshGlyph();
            checkTouchTargets();
            checkFocusRings();
            checkDangerConfirm();
            checkSettingsForm();
            checkDraftAndLeave();
            checkTokenPrivacy();
            capture("light-home", false);
            capture("light-settings", true);
            close();

            writeSettings();
            dark = true;
            launch();
            fixture("ready");
            capture("dark-home", false);
            fixture("unreachable");
            capture("dark-home-offline", false);
            capture("dark-settings", true);
            close();

            dark = false;
            fontScale = 2;
            widthDp = 320;
            density = Math.round(getTargetContext().getResources().getDisplayMetrics().widthPixels / 320f * 160);
            launch();
            fixture("unreachable");
            checkReflow("大字号 + 320dp 首页（未连接）", false);
            fixture("ready");
            checkReflow("大字号 + 320dp 首页", false);
            capture("large-home", false);
            checkReflow("大字号 + 320dp 设置", true);
            capture("large-settings", true);
            checkLargeDialog();
            close();

            fontScale = 1;
            widthDp = 900;
            density = Math.round(getTargetContext().getResources().getDisplayMetrics().widthPixels / 900f * 160);
            launch();
            fixture("ready");
            checkTwoPane();
            capture("wide", false);
            close();

            restore();
            out.putString("stream", "\n" + report + "PASS: 知弦界面\n");
            finish(Activity.RESULT_OK, out);
        } catch (Throwable error) {
            try { if (activity != null) close(); } catch (Throwable ignored) {}
            try { restore(); } catch (Throwable ignored) {}
            out.putString("stream", "\n" + report + "FAIL: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, out);
        }
    }

    // ------------------------------------------------------------------ 测试数据

    /** 注入一份确定的状态并冻结轮询，截图与断言不受真实服务波动影响。 */
    private void fixture(String kind) throws Exception {
        await("首次探测结束", () -> (Boolean) field(field(activity, "model"), "checked"));
        await("守护状态读出", () -> field(field(activity, "model"), "guard") != null);
        ui(() -> {
            try {
                set(activity, "resumed", false);
                ((android.os.Handler) field(activity, "main")).removeCallbacks((Runnable) field(activity, "poll"));
                set(activity, "guardAt", System.currentTimeMillis());
                Object model = field(activity, "model");
                JSONObject health = null;
                if (!"unreachable".equals(kind)) {
                    health = new JSONObject().put("name", "satori-qq").put("version", version())
                            .put("online", !"logged-out".equals(kind)).put("listening", true)
                            .put("connections", "ready".equals(kind) ? 1 : 0).put("qq_version", "9.3.65")
                            .put("config_revision", 0).put("config_status", "applied")
                            .put("online_since_epoch_ms", System.currentTimeMillis() - 7_380_000)
                            .put("notice", "enabled/posted=yes")
                            .put("compat", new JSONObject().put("passed", 204).put("total", 204))
                            .put("sso", new JSONObject().put("failures", 0).put("session_errors", 0));
                }
                set(model, "health", health);
                set(model, "checked", true);
                set(model, "probing", false);
                set(model, "port", 3001);
                set(model, "guard", guardResult());
                call(activity, "renderAll");
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        waitForIdleSync();
    }

    private Object guardResult() throws Exception {
        Class<?> type = activity.getClassLoader().loadClass("com.satori.qq.guard.GuardCommand$Result");
        Constructor<?> constructor = type.getDeclaredConstructor(boolean.class, JSONObject.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(true, new JSONObject().put("mode", "ARMED").put("running", true)
                .put("restarts_1h", 0).put("consec_fail", 0), null);
    }

    // ------------------------------------------------------------------ 首页

    private void checkHomeStates() throws Exception {
        String[][] cases = {
                {"unreachable", "未连接", "VISIBLE"},
                {"logged-out", "等待登录", "VISIBLE"},
                {"ready", "连接就绪", "GONE"},
        };
        for (String[] c : cases) {
            fixture(c[0]);
            ui(() -> {
                TextView title = (TextView) id("hero_title");
                check(c[1].contentEquals(title.getText()), "状态「" + c[0] + "」标题为 " + c[1] + "（实际 " + title.getText() + "）");
                View action = id("hero_action");
                check((action.getVisibility() == View.VISIBLE) == "VISIBLE".equals(c[2]),
                        "状态「" + c[0] + "」" + ("VISIBLE".equals(c[2]) ? "提供" : "不提供") + "打开 QQ");
            });
        }
        fixture("ready");
    }

    private void checkAccessibility() throws Exception {
        ui(() -> {
            check(id("hero_title").getAccessibilityLiveRegion() == View.ACCESSIBILITY_LIVE_REGION_POLITE,
                    "状态标题是 polite live region");
            check(id("hero_detail").getAccessibilityLiveRegion() == View.ACCESSIBILITY_LIVE_REGION_POLITE,
                    "状态说明是 polite live region");
            View guard = id("guard_switch");
            AccessibilityNodeInfo node = guard.createAccessibilityNodeInfo();
            check("android.widget.Switch".contentEquals(node.getClassName()), "守护开关读作开关（" + node.getClassName() + "）");
            check(node.isCheckable() && node.isChecked(), "守护开关可勾选且状态为已开启");
            check(guard.isClickable() && guard.isFocusable(), "整行可点、可聚焦");
            AccessibilityNodeInfo kill = id("guard_kill").createAccessibilityNodeInfo();
            check("android.widget.Button".contentEquals(kill.getClassName()), "危险操作读作按钮");
            for (String name : new String[]{"refresh", "open_settings"}) {
                CharSequence label = id(name).getContentDescription();
                check(label != null && label.length() > 0, name + " 图标按钮有名称");
            }
            check(((TextView) findText(activity.getWindow().getDecorView(), "知弦")).isAccessibilityHeading(),
                    "页面大标题是读屏标题");
        });
    }

    /** 官方 24 单位刷新图形居中，箭头完整、中心留白；避免再次出现错位的手绘稿。 */
    private void checkRefreshGlyph() throws Exception {
        ui(() -> {
            Drawable icon = ((ImageButton) id("refresh")).getDrawable();
            Bitmap bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888);
            Rect original = new Rect(icon.getBounds());
            icon.setBounds(0, 0, 240, 240);
            icon.draw(new Canvas(bitmap));
            icon.setBounds(original);
            int left = 240, right = 0, top = 240, bottom = 0;
            for (int y = 0; y < 240; y++) for (int x = 0; x < 240; x++) {
                if ((bitmap.getPixel(x, y) >>> 24) < 128) continue;
                left = Math.min(left, x);
                right = Math.max(right, x);
                top = Math.min(top, y);
                bottom = Math.max(bottom, y);
            }
            check(Math.abs(left + right - 239) <= 10 && Math.abs(top + bottom - 239) <= 10,
                    "刷新图形光学中心对齐 24dp 网格");
            check((bitmap.getPixel(190, 100) >>> 24) > 128 && (bitmap.getPixel(50, 120) >>> 24) > 128
                            && (bitmap.getPixel(120, 120) >>> 24) == 0,
                    "刷新图形右上箭头完整，中央留白");
        });
    }

    private void checkTouchTargets() throws Exception {
        for (boolean settings : new boolean[]{false, true}) {
            showPage(settings);
            List<String> small = new ArrayList<>();
            int min = Math.round(TOUCH_DP * activity.getResources().getDisplayMetrics().density) - 1;
            ui(() -> findSmall(activity.getWindow().getDecorView(), min, small));
            check(small.isEmpty(), (settings ? "设置页" : "首页") + "可操作控件都不小于 48dp" + (small.isEmpty() ? "" : "：" + small));
        }
        showPage(false);
    }

    private void findSmall(View view, int min, List<String> small) {
        if (view.getVisibility() != View.VISIBLE) return;
        if ((view.isClickable() || view.isFocusable()) && view.isEnabled() && !(view instanceof ScrollView)
                && view.getWidth() > 0 && (view.getWidth() < min || view.getHeight() < min)) {
            small.add(view.getClass().getSimpleName() + ":" + label(view) + " " + view.getWidth() + "x" + view.getHeight());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) findSmall(group.getChildAt(i), min, small);
        }
    }

    /** 键盘焦点可见（WCAG 2.4.7）：聚焦前后，控件内缘的像素必须变成焦点环颜色。 */
    private void checkFocusRings() throws Exception {
        showPage(false);
        ui(() -> {
            ring(id("guard_relaunch"), 0, "列表项");
            ring(id("refresh"), Math.round(4 * activity.getResources().getDisplayMetrics().density), "图标按钮");
        });
        showPage(true);
        ui(() -> {
            EditText port = (EditText) id("port");
            int before = pixel(port, port.getWidth() / 2, 0);
            port.requestFocus();
            int after = pixel(port, port.getWidth() / 2, 0);
            check(before != after, "输入框聚焦后描边变化（" + hex(before) + " → " + hex(after) + "）");
            port.clearFocus();
        });
        showPage(false);
    }

    private void ring(View view, int inset, String name) {
        int y = inset + Math.round(activity.getResources().getDisplayMetrics().density);
        // 由测试启动的窗口处在非触摸模式：清掉焦点后系统会把焦点交给第一个可聚焦控件，
        // 所以先把焦点放到别处，再取"未聚焦"的像素。
        View decoy = id("guard_switch") == view ? id("guard_kill") : id("guard_switch");
        decoy.setFocusableInTouchMode(true);
        decoy.requestFocus();
        check(!view.isFocused(), name + " 起始未聚焦");
        int before = pixel(view, view.getWidth() / 2, y);
        decoy.setFocusableInTouchMode(false);
        // 测试进程改不了系统的触摸模式；临时允许触摸模式下聚焦，效果与方向键/外接键盘聚焦相同。
        check(view.isFocusable(), name + " 可以获得键盘焦点");
        view.setFocusableInTouchMode(true);
        check(view.requestFocus(), name + " 可以拿到焦点");
        int after = pixel(view, view.getWidth() / 2, y);
        check(before != after, name + " 聚焦后出现焦点环（" + hex(before) + " → " + hex(after) + "）");
        view.clearFocus();
        view.setFocusableInTouchMode(false);
    }

    private static int pixel(View view, int x, int y) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        int value = bitmap.getPixel(Math.max(0, Math.min(x, bitmap.getWidth() - 1)), Math.max(0, Math.min(y, bitmap.getHeight() - 1)));
        bitmap.recycle();
        return value;
    }

    /** 危险操作：先确认，默认焦点在「取消」，取消不执行。 */
    private void checkDangerConfirm() throws Exception {
        showPage(false);
        AtomicReference<Dialog> dialog = new AtomicReference<>();
        ui(() -> id("guard_kill").performClick());
        waitForIdleSync();
        ui(() -> {
            Dialog shown = topDialog();
            check(shown != null && shown.isShowing(), "停止保活先弹出确认");
            dialog.set(shown);
            View focus = shown.getCurrentFocus();
            check(focus instanceof TextView && "取消".contentEquals(((TextView) focus).getText()), "确认弹窗默认聚焦取消");
            shown.cancel();
            Object model = field(activity, "model");
            check(field(model, "guardBusy") == null, "取消后没有执行任何 root 操作");
        });
    }

    // ------------------------------------------------------------------ 设置

    private void checkSettingsForm() throws Exception {
        showPage(true);
        ui(() -> {
            check(id("save_bar").getVisibility() == View.GONE, "没有改动时不显示保存栏");
            EditText port = (EditText) id("port");
            port.setText("80");
            check(id("save_bar").getVisibility() == View.VISIBLE, "改动后出现保存栏");
            id("save").performClick();
            check(port.getError() != null && port.hasFocus(), "端口越界：就地报错并获得焦点");
            port.setText("3002");
            id("save").performClick();
        });
        await("保存完成", () -> id("save_bar").getVisibility() == View.GONE);
        JSONObject saved = new JSONObject(new String(java.nio.file.Files.readAllBytes(settingsFile.toPath()), "UTF-8"));
        check(saved.getJSONObject("overrides").getInt("port") == 3002, "保存写入设置文件");
        check(saved.getLong("revision") == 1, "保存递增修订号");
        ui(() -> check(id("snackbar").getVisibility() == View.VISIBLE, "保存后给出结果提示"));
        ui(() -> {
            ((EditText) id("port")).setText("3001");
            id("save").performClick();
        });
        await("恢复端口", () -> id("save_bar").getVisibility() == View.GONE);
    }

    private void checkDraftAndLeave() throws Exception {
        showPage(true);
        AtomicReference<Bundle> state = new AtomicReference<>();
        ui(() -> {
            ((EditText) id("port")).setText("4000");
            Bundle bundle = new Bundle();
            callActivityOnSaveInstanceState(activity, bundle);
            state.set(bundle);
        });
        check("4000".equals(state.get().getString("draft_port")), "未保存的草稿进入实例状态");
        check(state.get().getInt("page") == 1, "当前页进入实例状态");
        ui(() -> activity.onBackPressed());
        waitForIdleSync();
        ui(() -> {
            Dialog shown = topDialog();
            check(shown != null && shown.isShowing(), "有未保存的修改时返回先确认");
            shown.cancel();
            check(id("settings").getVisibility() == View.VISIBLE, "取消后留在设置页");
            check("4000".contentEquals(((EditText) id("port")).getText()), "取消后草稿还在");
            ((EditText) id("port")).setText("3001");
            check(id("save_bar").getVisibility() == View.GONE, "改回原值后保存栏消失");
            activity.onBackPressed();
            check(id("home").getVisibility() == View.VISIBLE, "没有改动时返回首页");
        });
    }

    private void checkTokenPrivacy() throws Exception {
        showPage(true);
        ui(() -> {
            EditText token = (EditText) id("token");
            check(token.getTransformationMethod() instanceof PasswordTransformationMethod, "令牌默认隐藏");
            id("token_reveal").performClick();
            check(token.getTransformationMethod() == null, "可以显示令牌");
            check((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0,
                    "显示令牌期间禁止截屏");
            call(activity, "showPage", new Class[]{int.class, boolean.class}, 0, false);
            check(token.getTransformationMethod() instanceof PasswordTransformationMethod, "离开设置页重新隐藏令牌");
            check((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) == 0,
                    "隐藏后恢复截屏");
        });
    }

    // ------------------------------------------------------------------ 重排

    private void checkReflow(String name, boolean settings) throws Exception {
        showPage(settings);
        List<String> clipped = new ArrayList<>();
        ui(() -> clip(activity.getWindow().getDecorView(), clipped));
        check(clipped.isEmpty(), name + "：文字完整" + (clipped.isEmpty() ? "" : "（被截断：" + clipped.subList(0, Math.min(3, clipped.size())) + "）"));
        List<String> small = new ArrayList<>();
        int min = Math.round(TOUCH_DP * activity.getResources().getDisplayMetrics().density) - 1;
        ui(() -> findSmall(activity.getWindow().getDecorView(), min, small));
        check(small.isEmpty(), name + "：控件仍不小于 48dp" + (small.isEmpty() ? "" : "：" + small));
        if (settings) {
            ui(() -> {
                ((EditText) id("port")).setText("3003");
                View bar = id("save_bar");
                check(bar.getParent() != id("settings"), name + "：保存区进入滚动内容，不常驻底部");
                ((EditText) id("port")).setText("3001");
            });
        }
    }

    private void clip(View view, List<String> clipped) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView && !(view instanceof EditText)
                && view.getImportantForAccessibility() != View.IMPORTANT_FOR_ACCESSIBILITY_NO) {
            TextView text = (TextView) view;
            Layout layout = text.getLayout();
            if (layout != null) {
                boolean tall = layout.getHeight() > text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom() + 1;
                boolean ellipsized = false;
                for (int i = 0; i < layout.getLineCount(); i++) if (layout.getEllipsisCount(i) > 0) ellipsized = true;
                if (tall || ellipsized) clipped.add(text.getText().toString());
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) clip(group.getChildAt(i), clipped);
        }
    }

    private void checkLargeDialog() throws Exception {
        showPage(false);
        ui(() -> id("guard_relaunch").performClick());
        waitForIdleSync();
        ui(() -> {
            Dialog dialog = topDialog();
            check(dialog != null && dialog.isShowing(), "大字号下确认弹窗出现");
            View decor = dialog.getWindow().getDecorView();
            List<String> clipped = new ArrayList<>();
            clip(decor, clipped);
            check(clipped.isEmpty(), "200% 字号确认弹窗文字完整：" + clipped);
            View focus = dialog.getCurrentFocus();
            check(focus instanceof TextView && "取消".contentEquals(((TextView) focus).getText()), "大字号弹窗默认聚焦取消");
            dialog.cancel();
            check(field(field(activity, "model"), "guardBusy") == null, "取消重启不执行");
        });
    }

    private void checkTwoPane() throws Exception {
        ui(() -> {
            View home = id("home");
            View settings = id("settings");
            check(home.getVisibility() == View.VISIBLE && settings.getVisibility() == View.VISIBLE, "900dp：两栏同时显示");
            int[] a = new int[2], b = new int[2];
            home.getLocationInWindow(a);
            settings.getLocationInWindow(b);
            check(b[0] >= a[0] + home.getWidth() - 1, "900dp：设置在右栏");
            check(id("save_bar").getVisibility() == View.GONE, "900dp：打开时表单与已保存设置一致，没有假草稿");
            check("3001".contentEquals(((EditText) id("port")).getText()), "900dp：表单载入已保存的端口");
            check(activity.findViewById(activity.getResources().getIdentifier("open_settings", "id", APP)) == null,
                    "双栏时不再提供进入设置的按钮");
        });
    }

    // ------------------------------------------------------------------ 截图

    /** 把当前页的整条长页画进一张图：顶栏 + 滚动内容全部 + 保存栏（若可见）。 */
    private void capture(String name, boolean settings) throws Exception {
        showPage(settings);
        // 截的是"刚打开"的样子：滚回顶端（顶栏回到未抬升态），焦点交给页面根，不画键盘焦点环。
        ui(() -> {
            for (String page : new String[]{"home", "settings"}) {
                View root = activity.findViewById(activity.getResources().getIdentifier(page, "id", APP));
                if (root == null) continue;
                ViewGroup column = (ViewGroup) root;
                for (int i = 0; i < column.getChildCount(); i++) {
                    if (column.getChildAt(i) instanceof ScrollView) ((ScrollView) column.getChildAt(i)).scrollTo(0, 0);
                }
            }
            View frame = ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
            frame.setFocusableInTouchMode(true);
            frame.requestFocus();
        });
        waitForIdleSync();
        ui(() -> {
            try {
                View pages = id("home").getParent() instanceof android.widget.LinearLayout
                        ? (View) id("home").getParent() : null;
                List<View> columns = new ArrayList<>();
                if (pages != null) {
                    columns.add(id("home"));
                    columns.add(id("settings"));
                } else {
                    columns.add(id(settings ? "settings" : "home"));
                }
                int width = 0, height = 0;
                for (View column : columns) {
                    width += column.getWidth();
                    height = Math.max(height, longHeight((ViewGroup) column));
                }
                float scale = (pages != null ? 1400f : 720f) / Math.max(1, width);
                Bitmap bitmap = Bitmap.createBitmap(Math.round(width * scale), Math.round(height * scale), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                canvas.scale(scale, scale);
                View frame = ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
                canvas.drawColor(((android.graphics.drawable.ColorDrawable) frame.getBackground()).getColor());
                for (View column : columns) {
                    drawLong(canvas, (ViewGroup) column);
                    canvas.translate(column.getWidth(), 0);
                }
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
        report.append("shot ").append(name).append('\n');
    }

    private static int longHeight(ViewGroup column) {
        int height = 0;
        for (int i = 0; i < column.getChildCount(); i++) {
            View child = column.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            height += child instanceof ScrollView ? ((ScrollView) child).getChildAt(0).getHeight()
                    + child.getPaddingBottom() : child.getHeight();
        }
        return height;
    }

    private static void drawLong(Canvas canvas, ViewGroup column) {
        int save = canvas.save();
        if (column.getBackground() != null) {
            android.graphics.drawable.Drawable background = column.getBackground();
            android.graphics.Rect bounds = new android.graphics.Rect(background.getBounds());
            background.setBounds(0, 0, column.getWidth(), longHeight(column));
            background.draw(canvas);
            background.setBounds(bounds);
        }
        for (int i = 0; i < column.getChildCount(); i++) {
            View child = column.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child instanceof ScrollView) {
                View content = ((ScrollView) child).getChildAt(0);
                content.draw(canvas);
                canvas.translate(0, content.getHeight() + child.getPaddingBottom());
            } else {
                child.draw(canvas);
                canvas.translate(0, child.getHeight());
            }
        }
        canvas.restoreToCount(save);
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 切页，并确保这一页真的量过。锁屏或窗口还没遍历时视图树可能是 0×0，
     * 这时手动做一次与真实遍历等价的测量与布局，否则按尺寸与像素的检查会变成空跑。
     */
    private void showPage(boolean settings) throws Exception {
        ui(() -> {
            if (settings) call(activity, "ensureSettings");
            Object twoPane = field(activity, "twoPane");
            if (!(Boolean) twoPane) call(activity, "showPage", new Class[]{int.class, boolean.class}, settings ? 1 : 0, false);
        });
        waitForIdleSync();
        ui(() -> {
            View decor = activity.getWindow().getDecorView();
            View page = id(settings ? "settings" : "home");
            if (page.getWidth() == 0 || page.getHeight() == 0) {
                android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
                decor.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY));
                decor.layout(0, 0, metrics.widthPixels, metrics.heightPixels);
            }
            check(page.getWidth() > 0 && page.getHeight() > 0, (settings ? "设置页" : "首页") + "已完成测量");
        });
    }

    private View id(String name) {
        int value = activity.getResources().getIdentifier(name, "id", APP);
        if (value == 0) throw new AssertionError("ids.xml 里没有 " + name);
        View view = activity.findViewById(value);
        if (view == null) throw new AssertionError("界面上找不到 id/" + name);
        return view;
    }

    private static View findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())
                && view.getImportantForAccessibility() != View.IMPORTANT_FOR_ACCESSIBILITY_NO) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** 当前显示的对话框：窗口管理器里最上面那个属于本应用的 Dialog 窗口。 */
    private Dialog topDialog() {
        try {
            Class<?> global = Class.forName("android.view.WindowManagerGlobal");
            Object instance = global.getMethod("getInstance").invoke(null);
            Field views = global.getDeclaredField("mRoots");
            views.setAccessible(true);
            List<?> roots = (List<?>) views.get(instance);
            for (int i = roots.size() - 1; i >= 0; i--) {
                Object root = roots.get(i);
                Method getView = root.getClass().getDeclaredMethod("getView");
                View decor = (View) getView.invoke(root);
                if (decor == null) continue;
                Object window = findWindow(decor);
                if (window instanceof android.view.Window && ((android.view.Window) window).getCallback() instanceof Dialog) {
                    return (Dialog) ((android.view.Window) window).getCallback();
                }
            }
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
        return null;
    }

    private static Object findWindow(View decor) {
        for (Class<?> type = decor.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field window = type.getDeclaredField("mWindow");
                window.setAccessible(true);
                return window.get(decor);
            } catch (NoSuchFieldException ignored) {
            } catch (Exception error) {
                return null;
            }
        }
        return null;
    }

    private void launch() {
        activity = startActivitySync(new Intent().setClassName(getTargetContext(), APP + ".ui.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitForIdleSync();
    }

    private void close() {
        ui(() -> activity.finish());
        waitForIdleSync();
        activity = null;
    }

    private void writeSettings() throws Exception {
        JSONObject config = new JSONObject().put("port", 3001).put("token", "")
                .put("status_notification", true).put("wake_lock_auto", true)
                .put("wifi_sustain", true).put("manual_self_messages", true);
        JSONObject all = new JSONObject().put("revision", 0).put("overrides", config);
        java.nio.file.Files.write(settingsFile.toPath(), all.toString().getBytes("UTF-8"));
    }

    private void restore() throws Exception {
        if (settingsFile == null) return;
        if (hadOriginal) java.nio.file.Files.write(settingsFile.toPath(), original);
        else java.nio.file.Files.deleteIfExists(settingsFile.toPath());
        report.append("ok 原始设置已还原\n");
    }

    private String version() throws Exception {
        return getTargetContext().getPackageManager().getPackageInfo(APP, 0).versionName;
    }

    private static String label(View view) {
        CharSequence description = view.getContentDescription();
        if (description != null) return description.toString();
        if (view instanceof TextView) return ((TextView) view).getText().toString();
        return view.getId() == View.NO_ID ? "?" : Integer.toHexString(view.getId());
    }

    private static String hex(int color) {
        return String.format("#%08X", color);
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

    private static void call(Object target, String name) {
        call(target, name, new Class[0]);
    }

    private static void call(Object target, String name, Class<?>[] types, Object... args) {
        try {
            Method method = target.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(target, args);
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private interface Condition {
        boolean done() throws Exception;
    }

    private void await(String label, Condition condition) throws Exception {
        for (int i = 0; i < 600; i++) {
            AtomicReference<Boolean> result = new AtomicReference<>(false);
            ui(() -> {
                try {
                    result.set(condition.done());
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
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
