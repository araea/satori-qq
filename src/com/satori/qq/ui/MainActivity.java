package com.satori.qq.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import com.satori.qq.control.ControlStore;
import com.satori.qq.guard.GuardCommand;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * 知弦管理界面的宿主：两页导航、数据刷新、系统集成（安全区、返回手势、剪贴板、分享）。
 *
 * <p>窄屏是「首页 → 连接设置」的层级导航，支持预测性返回；宽屏且字号允许时两页并列（列表-详情）。
 * 本应用只管设置与查看状态，从不持有 QQ 服务或它的生命周期——关掉这个界面，服务照常运行。
 *
 * <p>线程：磁盘与回环探测走 {@link #worker}，root 调用走 {@link #rootWorker}。分开是必须的：
 * {@code su} 最长会占满 20 秒超时，排在一条队列上会把保存与刷新一起堵住。主线程不做任何 I/O。
 */
public final class MainActivity extends Activity implements HomePage.Actions, SettingsPage.Actions {
    private static final int HOME = 0;
    private static final int SETTINGS = 1;
    private static final long POLL_ONLINE = 5000;
    private static final long POLL_OFFLINE = 10000;
    private static final long GUARD_STALE = 15000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService rootWorker = Executors.newSingleThreadExecutor();
    private final HomePage.Model model = new HomePage.Model();

    private Tokens t;
    private Ui ui;
    private FrameLayout frame;
    private FrameLayout stage;
    private HomePage home;
    private SettingsPage settings;
    private Snackbar snackbar;
    private ControlStore store;
    private boolean twoPane;
    private int page = HOME;
    private boolean resumed;
    private long guardAt;
    private Bundle pendingDraft;
    private Runnable afterSave;
    private JSONObject storeSnapshot;
    private Object backCallback;
    private boolean backRegistered;
    private int insetTop, insetBottom, insetIme, insetLeft, insetRight;
    private final Runnable poll = () -> { if (resumed) refresh(); };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        t = new Tokens(this);
        Layout layout = new Layout(t, getResources().getConfiguration());
        ui = new Ui(t, layout);
        twoPane = layout.twoPane();
        store = new ControlStore(this);
        model.appVersion = appVersion();

        frame = new FrameLayout(this);
        frame.setBackgroundColor(t.surface);
        home = new HomePage(ui, this, !twoPane);
        if (twoPane) {
            LinearLayout panes = ui.row();
            panes.setGravity(android.view.Gravity.FILL_VERTICAL);
            panes.addView(home.root, new LinearLayout.LayoutParams(0, -1, 1));
            panes.addView(ensureSettings().root, new LinearLayout.LayoutParams(0, -1, 1));
            frame.addView(panes, new FrameLayout.LayoutParams(-1, -1));
            stage = null;
        } else {
            stage = new FrameLayout(this);
            stage.addView(home.root, new FrameLayout.LayoutParams(-1, -1));
            frame.addView(stage, new FrameLayout.LayoutParams(-1, -1));
        }
        snackbar = new Snackbar(ui, frame);
        frame.setOnApplyWindowInsetsListener(this::onInsets);
        setContentView(frame);
        edgeToEdge();

        if (state != null) {
            page = twoPane ? HOME : state.getInt("page", HOME);
            pendingDraft = state.containsKey("draft_port") ? state : null;
            if (pendingDraft != null || page == SETTINGS) ensureSettings();
            final int y = state.getInt("home_scroll");
            home.scroll.post(() -> home.scroll.scrollTo(0, y));
        }
        showPage(page, false);
        home.render(model);
        if (Build.VERSION.SDK_INT >= 33) setupBack();
    }

    // ------------------------------------------------------------------ 窗口与安全区

    /** 全屏绘制：系统栏透明，内容自己按安全区留白（targetSdk 35 起系统也强制如此）。 */
    private void edgeToEdge() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(t.dark ? 0 : light, light);
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (!t.dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            window.getDecorView().setSystemUiVisibility(flags);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private WindowInsets onInsets(View view, WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            insetTop = bars.top;
            insetBottom = bars.bottom;
            insetLeft = bars.left;
            insetRight = bars.right;
            insetIme = ime.bottom;
        } else {
            int bottom = insets.getSystemWindowInsetBottom();
            insetTop = insets.getSystemWindowInsetTop();
            insetLeft = insets.getSystemWindowInsetLeft();
            insetRight = insets.getSystemWindowInsetRight();
            // 旧系统把输入法高度并进底部安全区；明显高于导航栏的就当作输入法。
            boolean keyboard = bottom > getResources().getDisplayMetrics().heightPixels / 5;
            insetIme = keyboard ? bottom : 0;
            insetBottom = keyboard ? 0 : bottom;
        }
        applyInsets();
        return Build.VERSION.SDK_INT >= 30 ? WindowInsets.CONSUMED : insets.consumeSystemWindowInsets();
    }

    /**
     * 输入法弹出时把页面整体垫高（滚动区变矮，获得焦点的输入框会被滚进可见区域）；
     * 没有输入法时，底部系统栏高度留给滚动内容自己，内容可以画到手势条下方。
     */
    private void applyInsets() {
        int keyboard = insetIme > insetBottom ? insetIme : 0;
        int bars = keyboard > 0 ? 0 : insetBottom;
        frame.setPadding(insetLeft, 0, insetRight, 0);
        home.bar.setTopInset(insetTop);
        home.root.setPadding(0, 0, 0, keyboard);
        home.scroll.setPadding(0, 0, 0, bars);
        if (settings != null) {
            settings.bar.setTopInset(insetTop);
            settings.root.setPadding(0, 0, 0, keyboard);
            settings.setBottomInset(bars);
        }
        if (snackbar != null) snackbar.setBottomInset(keyboard + bars);
    }

    // ------------------------------------------------------------------ 导航

    private SettingsPage ensureSettings() {
        if (settings != null) return settings;
        settings = new SettingsPage(ui, this, twoPane);
        if (!twoPane) {
            settings.root.setVisibility(View.GONE);
            stage.addView(settings.root, new FrameLayout.LayoutParams(-1, -1));
        }
        JSONObject all = storeSnapshot;
        if (all != null) settings.load(ControlStore.editable(all), all.optJSONObject("overrides") != null, true);
        else loadStoreInto(settings);
        if (pendingDraft != null) {
            settings.restoreState(pendingDraft);
            pendingDraft = null;
        }
        if (Build.VERSION.SDK_INT >= 28) settings.root.setAccessibilityPaneTitle("连接设置");
        applyInsets();
        renderSettings();
        return settings;
    }

    private void loadStoreInto(SettingsPage target) {
        worker.execute(() -> {
            JSONObject all = readStore();
            main.post(() -> {
                if (isDestroyed()) return;
                storeSnapshot = all;
                target.load(ControlStore.editable(all), all.optJSONObject("overrides") != null, false);
            });
        });
    }

    private void showPage(int next, boolean animate) {
        page = twoPane ? HOME : next;
        setTitle(page == SETTINGS ? "知弦 · 连接设置" : "知弦");
        if (twoPane) return;
        View in = page == SETTINGS ? ensureSettings().root : home.root;
        View out = page == SETTINGS ? home.root : (settings == null ? null : settings.root);
        if (page == HOME) hideKeyboard();
        if (page == HOME && settings != null) settings.reveal(false);
        updateBackCallback();
        if (out == null || !animate || !Spring.enabled()) {
            in.setVisibility(View.VISIBLE);
            reset(in);
            if (out != null) {
                out.setVisibility(View.GONE);
                reset(out);
            }
            return;
        }
        // M3 共享轴（X）：新页从行进方向滑入并淡入，旧页向反方向让位并淡出。
        float shift = t.dp(48) * (page == SETTINGS ? 1 : -1);
        in.setVisibility(View.VISIBLE);
        in.bringToFront();
        if (page == HOME) home.root.setVisibility(View.VISIBLE);
        AnimatorSet set = new AnimatorSet();
        ObjectAnimator slideIn = ObjectAnimator.ofFloat(in, View.TRANSLATION_X, shift, 0);
        slideIn.setInterpolator(t.spatialDefault);
        slideIn.setDuration(t.spatialDefault.duration);
        ObjectAnimator fadeIn = ObjectAnimator.ofFloat(in, View.ALPHA, 0, 1);
        fadeIn.setInterpolator(t.effectsDefault);
        fadeIn.setDuration(t.effectsDefault.duration);
        ObjectAnimator slideOut = ObjectAnimator.ofFloat(out, View.TRANSLATION_X, out.getTranslationX(), -shift);
        slideOut.setInterpolator(t.spatialDefault);
        slideOut.setDuration(t.spatialDefault.duration);
        ObjectAnimator fadeOut = ObjectAnimator.ofFloat(out, View.ALPHA, out.getAlpha(), 0);
        fadeOut.setInterpolator(t.effectsFast);
        fadeOut.setDuration(t.effectsFast.duration);
        set.playTogether(slideIn, fadeIn, slideOut, fadeOut);
        set.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                out.setVisibility(View.GONE);
                reset(out);
                reset(in);
            }
        });
        set.start();
    }

    private static void reset(View view) {
        view.animate().cancel();
        view.setTranslationX(0);
        view.setAlpha(1);
        view.setScaleX(1);
        view.setScaleY(1);
    }

    /**
     * 返回：设置页先回首页；有未保存的修改时先问保存、放弃还是留下。
     * 其余情况交给系统（Android 13+ 由系统播放返回桌面的预测性动画）。
     */
    private boolean handleBack() {
        if (settings != null && settings.dirty()) {
            confirmLeave(() -> {
                if (page == SETTINGS) showPage(HOME, true);
                else finish();
            });
            return true;
        }
        if (page == SETTINGS) {
            showPage(HOME, true);
            return true;
        }
        return false;
    }

    private void confirmLeave(Runnable proceed) {
        Dialogs.show(ui, "保存这些修改？", "连接设置还没有保存。保存后，重新启动 QQ 时生效。",
                new Dialogs.Action("取消", Btn.TEXT, null),
                new Dialogs.Action("放弃", Btn.DANGER, () -> {
                    settings.discardNow();
                    proceed.run();
                }),
                new Dialogs.Action("保存", Btn.TEXT, () -> {
                    afterSave = proceed;
                    settings.submit();
                }));
    }

    @Override public void onBackPressed() {
        if (!handleBack()) super.onBackPressed();
    }

    /** Android 13+：只在需要拦截时注册回调，其余时候系统能播放预测性返回动画。 */
    private void setupBack() {
        if (Build.VERSION.SDK_INT >= 34) {
            backCallback = new android.window.OnBackAnimationCallback() {
                private boolean tracking;

                @Override public void onBackStarted(android.window.BackEvent event) {
                    tracking = page == SETTINGS && settings != null && !settings.dirty() && Spring.enabled();
                    if (tracking) home.root.setVisibility(View.VISIBLE);
                }

                @Override public void onBackProgressed(android.window.BackEvent event) {
                    if (!tracking) return;
                    // M3 预测性返回：页面随手势缩到 90%、向手势方向偏移，露出下层页面。
                    float p = event.getProgress();
                    View view = settings.root;
                    float scale = 1f - 0.1f * p;
                    view.setScaleX(scale);
                    view.setScaleY(scale);
                    float direction = event.getSwipeEdge() == android.window.BackEvent.EDGE_LEFT ? 1 : -1;
                    view.setTranslationX(direction * t.dp(24) * p);
                    view.setAlpha(1f - 0.2f * p);
                }

                @Override public void onBackInvoked() {
                    tracking = false;
                    if (!handleBack()) finish();
                }

                @Override public void onBackCancelled() {
                    if (!tracking) return;
                    tracking = false;
                    View view = settings.root;
                    view.animate().scaleX(1).scaleY(1).translationX(0).alpha(1)
                            .setDuration(t.spatialFast.duration).setInterpolator(t.spatialFast)
                            .withEndAction(() -> { if (page == SETTINGS) home.root.setVisibility(View.GONE); })
                            .start();
                }
            };
        } else {
            backCallback = (android.window.OnBackInvokedCallback) () -> { if (!handleBack()) finish(); };
        }
        updateBackCallback();
    }

    private void updateBackCallback() {
        if (Build.VERSION.SDK_INT < 33 || backCallback == null) return;
        boolean need = page == SETTINGS || (settings != null && settings.dirty());
        if (need == backRegistered) return;
        backRegistered = need;
        android.window.OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
        android.window.OnBackInvokedCallback callback = (android.window.OnBackInvokedCallback) backCallback;
        if (need) dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
        else dispatcher.unregisterOnBackInvokedCallback(callback);
    }

    // ------------------------------------------------------------------ 数据

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        refresh();
        if (System.currentTimeMillis() - guardAt > GUARD_STALE) refreshGuard();
    }

    @Override protected void onPause() {
        resumed = false;
        main.removeCallbacks(poll);
        if (settings != null) settings.reveal(false);
        super.onPause();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        rootWorker.shutdownNow();
        super.onDestroy();
    }

    private JSONObject readStore() {
        try {
            return store.snapshot();
        } catch (Exception error) {
            return new JSONObject();
        }
    }

    @Override public void refresh() {
        if (model.probing || isDestroyed()) return;
        main.removeCallbacks(poll);
        model.probing = true;
        home.render(model);
        worker.execute(() -> {
            JSONObject all = readStore();
            int port = ControlStore.runtimePort(all);
            JSONObject health = null;
            try {
                health = HealthClient.read(port);
            } catch (Exception ignored) {
                // 连不上就是连不上：界面显示"未连接"，绝不沿用上一次的在线结论。
            }
            final JSONObject result = health;
            main.post(() -> {
                if (isDestroyed()) return;
                storeSnapshot = all;
                model.health = result;
                model.checked = true;
                model.probing = false;
                model.port = port;
                JSONObject editable = ControlStore.editable(all);
                model.tokenSet = !editable.optString("token", "").isEmpty();
                if (settings != null) settings.load(editable, all.optJSONObject("overrides") != null, false);
                renderAll();
                if (resumed) main.postDelayed(poll, result == null ? POLL_OFFLINE : POLL_ONLINE);
            });
        });
    }

    private void renderAll() {
        home.render(model);
        renderSettings();
    }

    private void renderSettings() {
        if (settings == null) return;
        JSONObject all = storeSnapshot == null ? new JSONObject() : storeSnapshot;
        settings.notice(Status.config(model.health, all.optLong("revision", 0), all.optJSONObject("overrides") != null),
                model.guard != null && model.guard.ok);
    }

    private void refreshGuard() {
        guardAt = System.currentTimeMillis();
        rootWorker.execute(() -> {
            GuardCommand.Result result = GuardCommand.status();
            main.post(() -> {
                if (isDestroyed()) return;
                model.guard = result;
                renderAll();
            });
        });
    }

    // ------------------------------------------------------------------ 首页动作

    @Override public void openSettings() {
        if (twoPane) {
            ensureSettings().scroll.requestFocus();
            return;
        }
        showPage(SETTINGS, true);
    }

    @Override public void openQQ() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("com.tencent.mobileqq");
        if (intent == null) {
            snackbar.show("没有找到 QQ，请先安装 QQ", null, null);
            return;
        }
        try {
            startActivity(intent);
        } catch (Exception error) {
            snackbar.show("无法打开 QQ，请从桌面打开", null, null);
        }
    }

    @Override public void copyEndpoint() {
        copy("连接地址", "http://127.0.0.1:" + model.port, false);
    }

    @Override public void guard(boolean arm) {
        runGuard(arm ? "正在开启守护…" : "正在暂停守护…",
                arm ? GuardCommand::arm : GuardCommand::pause,
                arm ? "守护已开启" : "守护已暂停，QQ 保持运行");
    }

    @Override public void stopQQ() {
        Dialogs.show(ui, "停止保活并关闭 QQ？",
                "守护会先暂停，再关闭 QQ。之后 QQ 不会被自动拉起，客户端会断开，直到你重新开启守护或打开 QQ。",
                new Dialogs.Action("取消", Btn.TEXT, null),
                new Dialogs.Action("关闭 QQ", Btn.DANGER,
                        () -> runGuard("正在关闭 QQ…", GuardCommand::stopAndKill, "已停止保活并关闭 QQ")));
    }

    @Override public void relaunchQQ() {
        Dialogs.show(ui, "重新启动 QQ？",
                "QQ 会被关闭并立即重新打开，新设置随之生效；客户端会断开几十秒后自动重连。守护的开关状态保持不变。",
                new Dialogs.Action("取消", Btn.TEXT, null),
                new Dialogs.Action("重新启动", Btn.TEXT, () -> runGuard("正在重新启动 QQ…", GuardCommand::relaunchQQ,
                        "已重新启动 QQ，服务恢复需要几十秒")));
    }

    private interface RootCall {
        GuardCommand.Result run();
    }

    private void runGuard(String busy, RootCall call, String done) {
        if (model.guardBusy != null) return;
        model.guardBusy = busy;
        home.render(model);
        rootWorker.execute(() -> {
            GuardCommand.Result result = call.run();
            GuardCommand.Result status = GuardCommand.status();
            main.post(() -> {
                if (isDestroyed()) return;
                model.guardBusy = null;
                model.guard = status;
                guardAt = System.currentTimeMillis();
                renderAll();
                snackbar.show(result.ok ? done : result.error, null, null);
                main.postDelayed(this::refresh, 1500);
            });
        });
    }

    @Override public void copyReport() {
        copy("诊断报告", report(), false);
    }

    @Override public void shareReport() {
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "知弦诊断报告")
                .putExtra(Intent.EXTRA_TEXT, report());
        try {
            startActivity(Intent.createChooser(send, "分享诊断报告"));
        } catch (Exception error) {
            snackbar.show("没有可以分享的应用，请改用复制", null, null);
        }
    }

    private String report() {
        StringBuilder out = new StringBuilder(HealthClient.report(model.health, model.appVersion, model.port,
                System.currentTimeMillis()));
        GuardCommand.Result g = model.guard;
        out.append("常驻守护：").append(g == null ? "未读取" : (!g.ok ? "不可用" : (g.armed() ? "ARMED" : "PAUSED")
                + "，1h 重启 " + (g.status == null ? 0 : g.status.optInt("restarts_1h"))
                + " 次，连续失败 " + (g.status == null ? 0 : g.status.optInt("consec_fail")) + " 次")).append('\n');
        return out.toString();
    }

    // ------------------------------------------------------------------ 设置页动作

    @Override public void back() {
        handleBack();
    }

    @Override public void save(JSONObject value) {
        worker.execute(() -> {
            boolean ok = true;
            try {
                store.save(value);
            } catch (Exception error) {
                ok = false;
            }
            final boolean done = ok;
            final JSONObject all = readStore();
            main.post(() -> {
                if (isDestroyed()) return;
                storeSnapshot = all;
                settings.saved(value, done);
                model.tokenSet = !value.optString("token", "").isEmpty();
                hideKeyboard();
                renderSettings();
                home.render(model);
                Runnable next = afterSave;
                afterSave = null;
                if (!done) {
                    snackbar.show("保存失败，请重试", null, null);
                    return;
                }
                boolean root = model.guard != null && model.guard.ok;
                snackbar.show("已保存，重新启动 QQ 后生效", root ? "重新启动" : null, root ? this::relaunchQQ : null);
                if (next != null) next.run();
            });
        });
    }

    @Override public void discard() {
        snackbar.show("已恢复为上次保存的设置", null, null);
    }

    @Override public void useFile() {
        settings.setSaving(true);
        worker.execute(() -> {
            boolean ok = true;
            try {
                store.useFile();
            } catch (Exception error) {
                ok = false;
            }
            final boolean done = ok;
            final JSONObject all = readStore();
            main.post(() -> {
                if (isDestroyed()) return;
                storeSnapshot = all;
                settings.setSaving(false);
                settings.load(ControlStore.editable(all), all.optJSONObject("overrides") != null, true);
                renderSettings();
                snackbar.show(done ? "已改用文件配置，重新启动 QQ 后生效" : "切换失败，请重试", null, null);
            });
        });
    }

    @Override public void copyToken(String token) {
        copy("连接令牌", token, true);
    }

    @Override public void secure(boolean on) {
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    @Override public void message(String text) {
        snackbar.show(text, null, null);
    }

    @Override public void draftChanged(boolean dirty) {
        updateBackCallback();
    }

    // ------------------------------------------------------------------ 实例状态

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page", page);
        out.putInt("home_scroll", home.scroll.getScrollY());
        if (settings != null && settings.dirty()) settings.saveState(out);
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 复制到剪贴板。Android 13 起系统自己会弹出复制确认，这时不再重复提示；
     * 令牌标记为敏感内容，系统预览里不显示明文。
     */
    private void copy(String label, String value, boolean sensitive) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        ClipData clip = ClipData.newPlainText(label, value);
        if (sensitive) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
        }
        clipboard.setPrimaryClip(clip);
        if (Build.VERSION.SDK_INT < 33) snackbar.show("已复制" + label, null, null);
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus == null) return;
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        focus.clearFocus();
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception error) {
            return "";
        }
    }
}
