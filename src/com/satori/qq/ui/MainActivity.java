package com.satori.qq.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.satori.qq.control.ControlStore;
import com.satori.qq.control.ManagedConfig;
import com.satori.qq.guard.GuardCommand;
import org.json.JSONObject;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 管理页：状态、设置、诊断三页。只管设置，从不持有 QQ 服务或它的生命周期。
 *
 * <p>三层结构：{@link Tokens}（令牌）→ {@link Widgets}（组件）→ 本类（页面与状态）。
 * 页面里不出现字面色值与像素，排布走 {@link Responsive} 的断点。设计约定见 docs/DESIGN.md。
 */
public final class MainActivity extends Activity {
    private Tokens ui;
    private Widgets widgets;
    private Responsive layout;
    private ControlStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    /**
     * 网络探针与保存走这条；root 调用走 {@link #rootWorker}。
     * 分开是必须的：{@code su} 最长会占满 {@code GuardCommand.TIMEOUT_MS}（20 秒），
     * 排在一条队列上会把"保存设置"和"刷新状态"一起堵住。
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService rootWorker = Executors.newSingleThreadExecutor();
    private final ScrollView[] pages = new ScrollView[3];
    private final LinearLayout[] navigation = new LinearLayout[3];
    private final LinearLayout[] navIndicators = new LinearLayout[3];
    private final TextView[] navLabels = new TextView[3];
    private final Glyph[] navIcons = new Glyph[3];
    private int selected;
    private boolean resumed, probing, saving, revealing;
    private boolean autoRefresh = true;
    private Button pollingButton;
    private JSONObject health;
    private JSONObject loadedConfig;
    private long checkedAt;
    private int currentPort = 3001;
    private LinearLayout hero, saveDock;
    private Button heroAction;
    private final TextView[] connectionStages = new TextView[3];
    private TextView heroLabel, stateTitle, stateDetail, clients, uptime, endpoint, updated;
    private TextView configurationStatus, configurationNote;
    private LinearLayout configurationCard;
    private TextView versionStatus, diagnostics, diagnosticHint, dirtyLabel, sourceLabel;
    private LinearLayout dirtyCard;
    private TextView guardState;
    private boolean guardBusy;
    /** 状态页那个刷新按钮（真机用例按这个名字取），三个页面共用一份启用状态。 */
    private View refreshButton;
    private final java.util.List<View> refreshButtons = new java.util.ArrayList<>();
    private Button saveButton, revealButton, reportButton, shareButton;
    private EditText portInput, tokenInput;
    private Widgets.Field portField, tokenField;
    private final Switch[] toggles = new Switch[ManagedConfig.SWITCHES.length];
    private Widgets.DataRow[] diagnosticRows;
    private ExpressiveProgress progress;
    private final Runnable poll = new Runnable() {
        @Override public void run() { if (resumed) refresh(); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Tokens(this);
        layout = new Responsive(this, ui);
        widgets = new Widgets(ui, layout);
        ui.applyWindow();
        setTitle(getApplicationInfo().loadLabel(getPackageManager()));
        store = new ControlStore(this);
        pendingReport = state == null ? null : state.getString("report");
        autoRefresh = state == null || state.getBoolean("autoRefresh", true);

        LinearLayout root = widgets.column();
        root.setBackgroundColor(ui.surface);
        FrameLayout body = new FrameLayout(this);
        LinearLayout workspace = widgets.column();
        workspace.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        pages[0] = page(home());
        pages[1] = page(settings(state));
        pages[2] = page(diagnostics());
        for (ScrollView page : pages) body.addView(page, new FrameLayout.LayoutParams(-1, -1));
        if (layout.pinSave()) workspace.addView(saveDock, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout nav = navigationBar();
        if (layout.useRail()) {
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.addView(nav, new LinearLayout.LayoutParams(ui.dimen("size_nav_rail"), -1));
            root.addView(workspace, new LinearLayout.LayoutParams(0, -1, 1));
        } else {
            root.addView(workspace, new LinearLayout.LayoutParams(-1, 0, 1));
            root.addView(nav, new LinearLayout.LayoutParams(-1, -2));
        }
        ui.padSystemBars(root);
        setContentView(root);

        switchTab(state == null ? 0 : state.getInt("tab"), false);
        updateDraft();
        updateState();
        if (state != null) {
            for (int i = 0; i < 3; i++) {
                final int tab = i;
                pages[i].post(() -> pages[tab].scrollTo(0, state.getInt("scroll" + tab)));
            }
        }
    }

    // ------------------------------------------------------------------ 导航

    /**
     * M3 导航栏：选中项用一枚胶囊指示器，未选中项只有图标与文字。
     * 三项都是"整块可点"的按钮角色，读屏按按钮读，选中态由 selected 状态给出（WCAG 4.1.2）。
     */
    private LinearLayout navigationBar() {
        LinearLayout bar = layout.useRail() ? widgets.column() : widgets.row();
        if (layout.useRail()) bar.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = ui.dp(layout.gutterDp() / 2);
        bar.setPadding(layout.useRail() ? ui.dp(8) : pad, ui.dp(12),
                layout.useRail() ? ui.dp(8) : pad, ui.dp(12));
        bar.setBackgroundColor(ui.surfaceContainer);
        for (int i = 0; i < 3; i++) {
            final int tab = i;
            LinearLayout item = widgets.column();
            item.setGravity(Gravity.CENTER);
            item.setMinimumHeight(ui.dimen("size_nav_item_min_height"));
            widgets.paint(item, ui.shape(android.graphics.Color.TRANSPARENT, 20), 20, 20, ui.primary, ui.primary);
            widgets.asButton(item, new String[]{"状态", "设置", "诊断"}[i]);
            item.setOnClickListener(v -> switchTab(tab, true));

            LinearLayout indicator = widgets.row();
            indicator.setGravity(Gravity.CENTER);
            indicator.setMinimumWidth(ui.dp(64));
            indicator.setMinimumHeight(ui.dp(32));
            Glyph icon = new Glyph(ui, i, ui.onSurfaceVariant);
            indicator.addView(icon, new LinearLayout.LayoutParams(ui.dimen("size_icon"), ui.dimen("size_icon")));
            item.addView(indicator, new LinearLayout.LayoutParams(-2, -2));

            TextView label = widgets.text(new String[]{"状态", "设置", "诊断"}[i], Tokens.LABEL_MEDIUM, ui.onSurfaceVariant);
            label.setGravity(Gravity.CENTER);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            item.addView(label, widgets.stack(4));

            bar.addView(item, layout.useRail() ? widgets.stack(i == 0 ? 12 : 16)
                    : new LinearLayout.LayoutParams(0, -2, 1));
            navigation[i] = item;
            navIndicators[i] = indicator;
            navLabels[i] = label;
            navIcons[i] = icon;
        }
        return bar;
    }

    private void switchTab(int index, boolean animate) {
        int next = Math.max(0, Math.min(2, index));
        boolean changed = next != selected;
        selected = next;
        if (saveDock != null && layout.pinSave()) saveDock.setVisibility(next == 1 ? View.VISIBLE : View.GONE);
        setTitle(new String[]{"知弦 · 状态", "知弦 · 连接设置", "知弦 · 连接诊断"}[next]);
        if (selected != 1) {
            hideKeyboard();
            reveal(false);
        }
        for (int i = 0; i < pages.length; i++) {
            boolean active = i == selected;
            pages[i].animate().cancel();
            pages[i].setVisibility(active ? View.VISIBLE : View.GONE);
            navigation[i].setSelected(active);
            navIndicators[i].setBackground(ui.shape(active ? ui.secondaryContainer : android.graphics.Color.TRANSPARENT, 16));
            navLabels[i].setTextColor(active ? ui.onSurface : ui.onSurfaceVariant);
            navIcons[i].tint(active ? ui.onSecondaryContainer : ui.onSurfaceVariant);
        }
        if (changed && animate && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            View page = pages[selected];
            page.setAlpha(0f);
            page.animate().alpha(1f).setDuration(ui.integer("motion_medium1"))
                    .setInterpolator(ui.easing("emphasized_decelerate")).start();
        } else {
            pages[selected].setAlpha(1f);
        }
    }

    // ------------------------------------------------------------------ 状态页

    private LinearLayout home() {
        LinearLayout content = content();
        content.addView(header("知弦", "连接消息，让服务常在"), widgets.stack(0));

        hero = widgets.card(Widgets.TONE_HIGH, 28, 24);
        heroLabel = widgets.text("本机服务", Tokens.LABEL_MEDIUM, ui.onSurfaceVariant);
        hero.addView(heroLabel, widgets.stack(0));
        stateTitle = widgets.live(widgets.text("正在检查", Tokens.DISPLAY_SMALL, ui.onSurface));
        hero.addView(stateTitle, widgets.stack(12));
        stateDetail = widgets.body("正在读取 QQ 中的模块状态…", ui.onSurfaceVariant);
        hero.addView(widgets.live(stateDetail), widgets.stack(8));
        progress = new ExpressiveProgress(ui);
        hero.addView(progress, widgets.stack(12));
        heroAction = widgets.button("打开 QQ", Widgets.FILLED);
        heroAction.setOnClickListener(v -> {
            if (health != null && health.optBoolean("online")) switchTab(2, true);
            else openQQ();
        });
        hero.addView(heroAction, widgets.stack(8));
        content.addView(hero, widgets.stack(24));

        LinearLayout metricRow = layout.canPairCards() ? widgets.row() : widgets.column();
        if (layout.canPairCards()) metricRow.setGravity(Gravity.TOP);
        LinearLayout clientCard = widgets.card(Widgets.TONE_NEUTRAL, 20, 20);
        clientCard.addView(widgets.text("客户端", Tokens.LABEL_MEDIUM, ui.onSurfaceVariant), widgets.stack(0));
        clients = widgets.text("—", Tokens.HEADLINE_MEDIUM, ui.onSurface);
        clientCard.addView(clients, widgets.stack(8));
        LinearLayout timeCard = widgets.card(Widgets.TONE_NEUTRAL, 20, 20);
        timeCard.addView(widgets.text("连续在线", Tokens.LABEL_MEDIUM, ui.onSurfaceVariant), widgets.stack(0));
        uptime = widgets.text("—", Tokens.HEADLINE_MEDIUM, ui.onSurface);
        timeCard.addView(uptime, widgets.stack(8));
        if (layout.canPairCards()) {
            metricRow.addView(clientCard, widgets.share(0));
            metricRow.addView(timeCard, widgets.share(12));
        } else {
            metricRow.addView(clientCard, widgets.stack(0));
            metricRow.addView(timeCard, widgets.stack(12));
        }
        content.addView(metricRow, widgets.stack(12));

        LinearLayout stages = widgets.card(Widgets.TONE_NEUTRAL, 20, 20);
        stages.addView(widgets.heading("连接链路", Tokens.TITLE_MEDIUM, ui.onSurface), widgets.stack(0));
        for (int i = 0; i < connectionStages.length; i++) {
            connectionStages[i] = widgets.body("", ui.onSurfaceVariant);
            stages.addView(connectionStages[i], widgets.stack(8));
        }
        content.addView(stages, widgets.stack(12));

        LinearLayout connection = widgets.card(Widgets.TONE_NEUTRAL, 20, 20);
        connection.addView(widgets.heading("连接地址", Tokens.TITLE_LARGE, ui.onSurface), widgets.stack(0));
        endpoint = widgets.text("", Tokens.TITLE_MEDIUM, ui.primary);
        endpoint.setTextIsSelectable(true);
        endpoint.setMinimumHeight(ui.dimen("size_touch_target"));
        endpoint.setGravity(Gravity.CENTER_VERTICAL);
        connection.addView(endpoint, widgets.stack(8));
        connection.addView(widgets.body("仅供这台设备上的 Satori 客户端使用。", ui.onSurfaceVariant), widgets.stack(6));
        Button copy = widgets.button("复制连接地址", Widgets.TONAL);
        copy.setOnClickListener(v -> {
            haptic(v);
            copy("连接地址", endpoint.getText().toString(), false);
        });
        Button openQQ = widgets.button("打开 QQ", Widgets.OUTLINED);
        openQQ.setOnClickListener(v -> openQQ());
        widgets.actionRow(connection, Arrays.asList(copy, openQQ), 16);
        content.addView(connection, widgets.stack(12));

        LinearLayout guard = widgets.card(Widgets.TONE_NEUTRAL, 28, 24);
        guard.addView(widgets.heading("常驻守护", Tokens.TITLE_LARGE, ui.onSurface), widgets.stack(0));
        guardState = widgets.body("正在读取守护状态…", ui.onSurfaceVariant);
        guard.addView(widgets.live(guardState), widgets.stack(8));
        Button guardOn = widgets.button("开启守护", Widgets.TONAL);
        guardOn.setOnClickListener(v -> guardAction("start"));
        Button guardOff = widgets.button("暂停守护", Widgets.TONAL);
        guardOff.setOnClickListener(v -> guardAction("stop"));
        widgets.actionRow(guard, Arrays.asList(guardOn, guardOff), 16);
        Button guardKill = widgets.button("停止保活并关闭 QQ", Widgets.DANGER);
        guardKill.setOnClickListener(v -> {
            haptic(v);
            widgets.confirm("停止保活并关闭 QQ？",
                    "先把看守切成 PAUSED，再强停 QQ。之后不会再自动拉起；想恢复请重新开启守护。",
                    "确认关闭", true, () -> guardAction("kill"));
        });
        guard.addView(guardKill, widgets.stack(8));
        guard.addView(widgets.body("需要 Root 与知弦模块。开启后自动检查进程并尝试恢复；暂停不会关闭 QQ。"
                + "也可通过快捷设置中的「知弦守护」控制。", ui.onSurfaceVariant), widgets.stack(12));
        content.addView(guard, widgets.stack(16));

        updated = widgets.text("", Tokens.LABEL_MEDIUM, ui.onSurfaceVariant);
        updated.setGravity(Gravity.CENTER);
        content.addView(updated, widgets.stack(24));
        pollingButton = widgets.button(autoRefresh ? "暂停自动刷新" : "恢复自动刷新", Widgets.TEXT);
        pollingButton.setOnClickListener(v -> {
            autoRefresh = !autoRefresh;
            pollingButton.setText(autoRefresh ? "暂停自动刷新" : "恢复自动刷新");
            main.removeCallbacks(poll);
            if (autoRefresh) refresh();
            updateState();
        });
        content.addView(pollingButton, widgets.stack(4));
        TextView serviceHint = widgets.text("管理页关闭后，服务仍随 QQ 运行。", Tokens.BODY_SMALL, ui.onSurfaceVariant);
        serviceHint.setGravity(Gravity.CENTER);
        content.addView(serviceHint, widgets.stack(6));
        return content;
    }

    private void updateHero() {
        int tone;
        String title, detail;
        boolean reachable = health != null;
        boolean online = reachable && health.optBoolean("online");
        boolean listening = reachable && health.optBoolean("listening");
        if (checkedAt == 0) {
            tone = Widgets.TONE_HIGH;
            title = "正在检查";
            detail = "正在读取 QQ 中的模块状态…";
        } else if (!reachable) {
            tone = Widgets.TONE_ERROR;
            title = "尚未连接";
            detail = "请确认模块已启用，并打开 QQ。更新模块后，需要重新启动 QQ。";
        } else if (!listening) {
            tone = Widgets.TONE_WARNING;
            title = "服务待恢复";
            detail = "模块已响应，本机端口尚未就绪。请查看诊断信息。";
        } else if (!online) {
            tone = Widgets.TONE_WARNING;
            title = "等待登录";
            detail = "本机服务已就绪，打开 QQ 登录账号后即可连接。";
        } else {
            tone = Widgets.TONE_SUCCESS;
            title = "连接就绪";
            detail = "QQ 与本机服务均已就绪。"
                    + (health.optInt("connections") == 0 ? "等待客户端接入。" : "客户端已接入，消息桥梁正在运行。");
        }
        int ink = widgets.inkOf(tone);
        int sub = tone == Widgets.TONE_HIGH ? ui.onSurfaceVariant : ink;
        hero.setBackground(ui.shape(widgets.fillOf(tone), 28));
        heroLabel.setTextColor(sub);
        stateTitle.setTextColor(ink);
        stateTitle.setText(title);
        stateDetail.setTextColor(sub);
        stateDetail.setText(detail);
        heroAction.setText(online ? "查看连接诊断" : "打开 QQ");
        String unknown = checkedAt == 0 ? "检查中" : "未连接";
        connectionStages[0].setText("01  QQ 账号 · " + (reachable ? (online ? "已登录" : "等待登录") : unknown));
        connectionStages[1].setText("02  本机服务 · " + (reachable ? (listening ? "已就绪" : "待恢复") : unknown));
        connectionStages[2].setText("03  Satori 客户端 · " + (reachable
                ? (health.optInt("connections") > 0 ? health.optInt("connections") + " 个已接入" : "等待接入") : "待服务连接"));
    }

    // ------------------------------------------------------------------ 设置页

    private LinearLayout settings(Bundle state) {
        LinearLayout content = content();
        content.addView(pageTitle("连接设置", "把连接方式，调成习惯的样子。"), widgets.stack(0));

        configurationCard = widgets.card(Widgets.TONE_PRIMARY, 20, 20);
        configurationStatus = widgets.text("", Tokens.TITLE_MEDIUM, ui.onPrimaryContainer);
        configurationCard.addView(widgets.live(configurationStatus), widgets.stack(0));
        configurationNote = widgets.text("", Tokens.BODY_SMALL, ui.onPrimaryContainer);
        configurationCard.addView(configurationNote, widgets.stack(6));
        content.addView(configurationCard, widgets.stack(20));

        JSONObject config = store.editable();
        loadedConfig = config;
        LinearLayout network = widgets.card(Widgets.TONE_NEUTRAL, 28, 24);
        network.addView(widgets.heading("连接", Tokens.TITLE_LARGE, ui.onSurface), widgets.stack(0));
        portField = widgets.field(network, "本机端口", "3001", false);
        portInput = portField.input;
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        portInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(5)});
        portInput.setText(state == null ? String.valueOf(config.optInt("port", 3001)) : state.getString("port", "3001"));
        portField.hint("可用范围 1024–65535。修改后需同步客户端地址。");
        tokenField = widgets.field(network, "连接令牌", "留空时不校验令牌", true);
        tokenInput = tokenField.input;
        tokenInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(128)});
        tokenInput.setText(state == null ? config.optString("token", "") : state.getString("token", ""));
        tokenField.hint("令牌用于客户端连接验证。留空时，本机客户端无需令牌即可访问。");
        revealButton = widgets.button("显示令牌", Widgets.TONAL);
        revealButton.setOnClickListener(v -> reveal(!revealing));
        Button generate = widgets.button("生成令牌", Widgets.TONAL);
        generate.setOnClickListener(v -> {
            byte[] bytes = new byte[24];
            new SecureRandom().nextBytes(bytes);
            tokenInput.setText(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
            toast("已生成；保存后请同步更新客户端令牌");
        });
        Button copyToken = widgets.button("复制令牌", Widgets.TONAL);
        copyToken.setOnClickListener(v -> {
            if (tokenInput.length() == 0) toast("当前令牌为空");
            else copy("连接令牌", tokenInput.getText().toString(), true);
        });
        widgets.actionRow(network, Arrays.asList(revealButton, generate, copyToken), 12);
        content.addView(network, widgets.stack(16));

        LinearLayout behavior = widgets.card(Widgets.TONE_NEUTRAL, 28, 24);
        behavior.addView(widgets.heading("运行偏好", Tokens.TITLE_LARGE, ui.onSurface), widgets.stack(0));
        String[] labels = {"状态通知", "自动保持唤醒", "保持 Wi-Fi 连接", "手机手动发的消息也投递"};
        String[] hints = {
                "显示连接状态。",
                "QQ 启动时自动获取唤醒锁，会增加待机耗电。",
                "只在 Wi-Fi 下有效：客户端连接期间保持 Wi-Fi 锁。走移动数据时没有对应的锁，靠的是进程不被冻住"
                        + "（模块会保持自身服务存活）。",
                "把你在 QQ 客户端里手动发出的消息也作为消息事件投递，作者用独立身份（qq-client:账号），"
                        + "便于在手机上手动发消息测试机器人。"};
        List<View> preferenceBlocks = new java.util.ArrayList<>();
        for (int i = 0; i < toggles.length; i++) {
            Switch toggle = widgets.switchControl(labels[i]);
            toggle.setChecked(state == null ? config.optBoolean(ManagedConfig.SWITCHES[i], true) : state.getBoolean("toggle" + i));
            toggles[i] = toggle;
            LinearLayout block = widgets.column();
            block.addView(toggle, widgets.stack(0));
            block.addView(widgets.body(hints[i], ui.onSurfaceVariant), widgets.stack(8));
            preferenceBlocks.add(block);
        }
        widgets.addColumnWise(behavior, preferenceBlocks, layout.preferenceColumns(), 20, 20);
        content.addView(behavior, widgets.stack(16));

        saveDock = widgets.column();
        saveDock.setBackgroundColor(ui.surfaceContainerLow);
        if (layout.pinSave()) saveDock.setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(12));
        dirtyCard = widgets.column();
        dirtyLabel = widgets.live(widgets.text("", Tokens.LABEL_LARGE, ui.onSurface));
        dirtyCard.addView(dirtyLabel, widgets.stack(0));
        saveDock.addView(dirtyCard, widgets.stack(0));
        saveButton = widgets.button("保存设置", Widgets.FILLED);
        saveButton.setOnClickListener(v -> save(false));
        saveDock.addView(saveButton, widgets.stack(8));
        if (!layout.pinSave()) content.addView(saveDock, widgets.stack(20));
        Button discard = widgets.button("撤销未保存的修改", Widgets.TEXT);
        discard.setOnClickListener(v -> {
            if (!dirty() || saving) return;
            widgets.confirm("撤销这次修改？", "恢复为上次保存的设置。", "撤销修改", true, () -> {
                JSONObject saved = loadedConfig;
                loadedConfig = null;
                loadForm(saved);
                portField.error(null);
                tokenField.error(null);
                reveal(false);
            });
        });
        content.addView(discard, widgets.stack(12));
        content.addView(widgets.body("保存后，下次启动 QQ 时生效。需要立即应用时，请在 QQ 应用信息页停止 QQ，"
                + "再重新打开；客户端会短暂断开。", ui.onSurfaceVariant), widgets.stack(12));

        Button appInfo = widgets.button("QQ 应用信息", Widgets.OUTLINED);
        appInfo.setOnClickListener(v -> openAppInfo("com.tencent.mobileqq"));
        Button ownInfo = widgets.button("知弦应用信息", Widgets.OUTLINED);
        ownInfo.setOnClickListener(v -> openAppInfo(getPackageName()));
        widgets.actionRow(content, Arrays.asList(appInfo, ownInfo), 12);
        content.addView(widgets.body("若系统限制关联启动，请在系统设置的「应用 → 关联启动」里允许知弦。"
                + "否则 QQ 可能无法在后台读取设置；也可先打开知弦，再重启 QQ。", ui.onSurfaceVariant), widgets.stack(16));

        LinearLayout advanced = widgets.card(Widgets.TONE_NEUTRAL, 28, 24);
        sourceLabel = widgets.text("", Tokens.TITLE_MEDIUM, ui.onSurface);
        advanced.addView(sourceLabel, widgets.stack(0));
        advanced.addView(widgets.body("其他高级选项继续读取原 JSON 配置文件。", ui.onSurfaceVariant), widgets.stack(8));
        Button reset = widgets.button("使用文件配置", Widgets.OUTLINED);
        reset.setOnClickListener(v -> widgets.confirm("使用文件配置？",
                "下次启动 QQ 时，页面中的连接与运行偏好将改为读取原 JSON 文件；没有文件时使用默认值。",
                "确认切换", false, this::resetToFile));
        advanced.addView(reset, widgets.stack(16));
        content.addView(advanced, widgets.stack(24));

        TextWatcher watcher = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { if (saveButton != null) updateDraft(); }
            public void afterTextChanged(Editable e) {}
        };
        portInput.addTextChangedListener(watcher);
        tokenInput.addTextChangedListener(watcher);
        for (Switch toggle : toggles) toggle.setOnCheckedChangeListener((v, checked) -> updateDraft());
        return content;
    }

    // ------------------------------------------------------------------ 诊断页

    private LinearLayout diagnostics() {
        LinearLayout content = content();
        content.addView(pageTitle("连接诊断", "让每一次排查，都有清晰的依据。", true), widgets.stack(0));

        LinearLayout summary = widgets.card(Widgets.TONE_HIGH, 24, 20);
        versionStatus = widgets.text("", Tokens.TITLE_MEDIUM, ui.onSurface);
        summary.addView(widgets.live(versionStatus), widgets.stack(0));
        summary.addView(widgets.body("已安装版本与正在运行的模块可能不同。更新模块后，QQ 需要重新加载。",
                ui.onSurfaceVariant), widgets.stack(8));
        content.addView(summary, widgets.stack(24));

        LinearLayout panel = widgets.card(Widgets.TONE_NEUTRAL, 24, 20);
        panel.addView(widgets.heading("运行数据", Tokens.TITLE_LARGE, ui.onSurface), widgets.stack(0));
        String[] rowLabels = {"QQ 版本", "内核接口", "状态通知", "请求失败", "会话错误"};
        diagnosticRows = new Widgets.DataRow[rowLabels.length];
        for (int i = 0; i < rowLabels.length; i++) {
            diagnosticRows[i] = widgets.dataRow(panel, rowLabels[i], i == 0 ? 16 : 14);
        }
        diagnostics = widgets.body("", ui.onSurfaceVariant);
        panel.addView(diagnostics, widgets.stack(16));
        content.addView(panel, widgets.stack(16));
        diagnosticHint = widgets.body("", ui.onSurfaceVariant);
        content.addView(widgets.live(diagnosticHint), widgets.stack(16));

        reportButton = widgets.button("复制诊断报告", Widgets.FILLED);
        reportButton.setOnClickListener(v -> copy("诊断报告", report(), false));
        shareButton = widgets.button("导出诊断报告", Widgets.OUTLINED);
        shareButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain")
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_TITLE, "知弦-诊断-"
                            + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(new Date()) + ".txt");
            pendingReport = report();
            try {
                startActivityForResult(intent, 42);
            } catch (Exception e) {
                toast("未找到文件保存应用，请使用复制报告");
            }
        });
        widgets.actionRow(content, Arrays.asList(reportButton, shareButton), 20);
        content.addView(widgets.body("报告仅含服务状态与错误计数，不包含令牌、QQ 账号或消息内容。",
                ui.onSurfaceVariant), widgets.stack(12));

        LinearLayout help = widgets.card(Widgets.TONE_NEUTRAL, 28, 24);
        help.addView(widgets.heading("连接前，检查这三步", Tokens.TITLE_LARGE, ui.onSurface), widgets.stack(0));
        String[][] steps = {
                {"01", "在模块管理器启用知弦，并勾选 QQ。"},
                {"02", "重新启动 QQ，确认账号已登录。"},
                {"03", "在 Satori 客户端填写本机地址与相同令牌。"},
        };
        for (int i = 0; i < steps.length; i++) {
            LinearLayout step = widgets.row();
            step.setGravity(Gravity.TOP);
            TextView index = widgets.text(steps[i][0], Tokens.LABEL_LARGE, ui.primary);
            step.addView(index, new LinearLayout.LayoutParams(ui.dp(40), -2));
            step.addView(widgets.body(steps[i][1], ui.onSurfaceVariant), widgets.share(0));
            help.addView(step, widgets.stack(16));
        }
        content.addView(help, widgets.stack(24));
        return content;
    }

    private String pendingReport;

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 42 || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final String value = pendingReport == null ? report() : pendingReport;
        worker.execute(() -> {
            boolean ok = false;
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out != null) {
                    out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    ok = true;
                }
            } catch (Exception ignored) {}
            final boolean saved = ok;
            main.post(() -> { if (!isDestroyed()) toast(saved ? "诊断报告已保存" : "保存失败，请重试或复制报告"); });
        });
    }

    // ------------------------------------------------------------------ 刷新与状态

    private void refresh() {
        if (probing || isDestroyed()) return;
        main.removeCallbacks(poll);
        probing = true;
        progress.setVisibility(View.VISIBLE);
        setRefreshing(true);
        JSONObject runtime = store.runtime();
        JSONObject config = runtime.optJSONObject("config");
        currentPort = config == null ? 3001 : config.optInt("port", 3001);
        endpoint.setText("http://127.0.0.1:" + currentPort);
        final int port = currentPort;
        worker.execute(() -> {
            JSONObject result = null;
            try {
                result = HealthClient.read(port);
            } catch (Exception ignored) {}
            final JSONObject response = result;
            main.post(() -> {
                if (isDestroyed()) return;
                health = response;
                checkedAt = System.currentTimeMillis();
                probing = false;
                if (!saving && !dirty()) loadForm(store.editable());
                progress.setVisibility(View.INVISIBLE);
                setRefreshing(false);
                updateState();
                if (resumed && autoRefresh) main.postDelayed(poll, 5000);
            });
        });
    }

    private void updateState() {
        JSONObject runtime = store.runtime();
        JSONObject config = runtime.optJSONObject("config");
        if (checkedAt == 0 && !probing) currentPort = config == null ? 3001 : config.optInt("port", 3001);
        endpoint.setText("http://127.0.0.1:" + currentPort);
        boolean reachable = health != null;
        boolean online = reachable && health.optBoolean("online");
        updateHero();
        clients.setText(reachable ? String.valueOf(health.optInt("connections")) : "—");
        long since = reachable ? health.optLong("online_since_epoch_ms") : 0;
        uptime.setText(!online || since == 0 ? "—" : duration(System.currentTimeMillis() - since));
        updated.setText(checkedAt == 0 ? "检查中…"
                : "最近检查 " + new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date(checkedAt))
                        + (autoRefresh ? " · 前台每 5 秒更新" : " · 自动刷新已暂停"));

        long applied = reachable ? health.optLong("config_revision", -1) : -1;
        boolean providerBlocked = reachable && health.optString("config_status").startsWith("provider-unavailable");
        String statusText, statusNote;
        int statusTone;
        if (providerBlocked) {
            statusTone = Widgets.TONE_ERROR;
            statusText = "设置尚未读取";
            statusNote = "请允许知弦关联启动，或先打开知弦再重启 QQ。";
        } else if (applied == store.revision()) {
            statusTone = Widgets.TONE_SUCCESS;
            statusText = "已保存的设置已生效";
            statusNote = "运行中的模块读到的修订号与本机记录一致。";
        } else if (store.revision() > 0) {
            statusTone = Widgets.TONE_WARNING;
            statusText = "设置已保存 · 等待 QQ 重新启动";
            statusNote = "重启 QQ 后新设置才会生效。";
        } else {
            statusTone = Widgets.TONE_HIGH;
            statusText = "尚未确认运行配置";
            statusNote = "重启 QQ 后同步。";
        }
        int statusInk = widgets.inkOf(statusTone);
        configurationCard.setBackground(ui.shape(widgets.fillOf(statusTone), 20));
        configurationStatus.setTextColor(statusInk);
        configurationStatus.setText(statusText);
        configurationNote.setTextColor(statusInk);
        configurationNote.setText(statusNote);
        sourceLabel.setText(store.overrides().isEmpty()
                ? "设置来源：原 JSON 文件 / 默认值" : "设置来源：知弦管理页（优先于文件中的同名选项）");
        versionStatus.setText("应用 " + appVersion() + " · 运行 " + (reachable ? health.optString("version", "未知") : "未连接"));

        if (!reachable) {
            for (Widgets.DataRow row : diagnosticRows) row.set("—");
            diagnostics.setText("尚未取得诊断数据。请先打开 QQ，并确认模块作用域包含 QQ。"
                    + "若更新后仍无响应，请重新启动 QQ。");
            diagnostics.setVisibility(View.VISIBLE);
            diagnosticHint.setText("连接失败时不会沿用上次的在线状态。管理页退出不会停止 QQ 服务。");
        } else {
            JSONObject compat = health.optJSONObject("compat");
            JSONObject sso = health.optJSONObject("sso");
            diagnosticRows[0].set(health.optString("qq_version", "未知"));
            diagnosticRows[1].set(compat == null ? "待检查" : compat.optInt("passed") + " / " + compat.optInt("total"));
            diagnosticRows[2].set(health.optString("notice").contains("posted=yes") ? "已发布" : "关闭或等待系统授权");
            diagnosticRows[3].set((sso == null ? 0 : sso.optInt("failures")) + " 次");
            diagnosticRows[4].set((sso == null ? 0 : sso.optInt("session_errors")) + " 次");
            diagnostics.setText("");
            diagnostics.setVisibility(View.GONE);
            diagnosticHint.setText(!appVersion().equals(health.optString("version"))
                    ? "正在运行旧版模块。重新启动 QQ 后，新版本才会生效。"
                    : "在线状态来自 QQ 内核，不能单独证明服务端会话有效。遇到消息不通时，请结合离线记录与实际连接排查。");
        }
    }

    // ------------------------------------------------------------------ 表单

    private JSONObject draft() throws Exception {
        String rawPort = portInput.getText().toString().trim();
        int port;
        try {
            port = Integer.parseInt(rawPort);
        } catch (Exception e) {
            throw new IllegalArgumentException("端口须为 1024–65535 的整数");
        }
        JSONObject json = new JSONObject().put("port", port).put("token", tokenInput.getText().toString());
        for (int i = 0; i < toggles.length; i++) json.put(ManagedConfig.SWITCHES[i], toggles[i].isChecked());
        return ManagedConfig.validate(json);
    }

    private boolean dirty() {
        JSONObject original = loadedConfig;
        if (!portInput.getText().toString().equals(String.valueOf(original.optInt("port", 3001)))
                || !tokenInput.getText().toString().equals(original.optString("token", ""))) return true;
        for (int i = 0; i < toggles.length; i++) {
            if (toggles[i].isChecked() != original.optBoolean(ManagedConfig.SWITCHES[i], true)) return true;
        }
        return false;
    }

    private void updateDraft() {
        boolean changed = dirty();
        saveButton.setEnabled(!saving && changed);
        saveButton.setText(saving ? "正在保存…" : (changed ? "保存设置" : "已保存"));
        dirtyLabel.setText(changed ? "有未保存的修改 · 保存后重启 QQ 生效" : "与已保存设置一致");
        dirtyCard.setVisibility(changed ? View.VISIBLE : View.GONE);
    }

    private void save(boolean finishAfter) {
        if (saving) return;
        final JSONObject value;
        try {
            value = draft();
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().startsWith("令牌")) tokenField.error(e.getMessage());
            else portField.error(e.getMessage());
            toast(e.getMessage());
            return;
        }
        portField.error(null);
        tokenField.error(null);
        saving = true;
        updateDraft();
        worker.execute(() -> {
            String error = null;
            try {
                store.save(value);
            } catch (Exception e) {
                error = "保存失败，请重试";
            }
            final String message = error;
            main.post(() -> {
                if (isDestroyed()) return;
                saving = false;
                if (message == null) loadedConfig = value;
                updateDraft();
                updateState();
                if (message != null) toast(message);
                else {
                    hideKeyboard();
                    toast("已保存，下次启动 QQ 时生效");
                    if (finishAfter) finish();
                }
            });
        });
    }

    private void loadForm(JSONObject config) {
        if (loadedConfig != null && loadedConfig.toString().equals(config.toString())) return;
        loadedConfig = config;
        portInput.setText(String.valueOf(config.optInt("port", 3001)));
        tokenInput.setText(config.optString("token", ""));
        for (int i = 0; i < toggles.length; i++) toggles[i].setChecked(config.optBoolean(ManagedConfig.SWITCHES[i], true));
        updateDraft();
    }

    private void resetToFile() {
        if (saving) return;
        saving = true;
        updateDraft();
        worker.execute(() -> {
            boolean ok = true;
            try {
                store.useFile();
            } catch (Exception e) {
                ok = false;
            }
            final boolean done = ok;
            main.post(() -> {
                if (isDestroyed()) return;
                saving = false;
                if (done) {
                    loadForm(store.editable());
                    toast("已切换，下次启动 QQ 后读取文件配置");
                } else {
                    toast("保存失败，请重试");
                }
                updateDraft();
                updateState();
            });
        });
    }

    private void reveal(boolean value) {
        revealing = value;
        tokenInput.setTransformationMethod(value ? null : android.text.method.PasswordTransformationMethod.getInstance());
        tokenInput.setSelection(tokenInput.length());
        revealButton.setText(value ? "隐藏令牌" : "显示令牌");
        if (value) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (autoRefresh || checkedAt == 0) refresh();
        refreshGuard();
    }

    /** 读一次 root 侧看守状态，套用界面。走独立的工作线程，绝不阻塞主线程，也不堵住网络与保存。 */
    private void refreshGuard() {
        if (guardBusy || guardState == null) return;
        guardBusy = true;
        rootWorker.execute(() -> {
            final GuardCommand.Result result = GuardCommand.status();
            main.post(() -> {
                guardBusy = false;
                if (isDestroyed() || guardState == null) return;
                if (!result.ok) {
                    guardState.setText("守护状态不可用：" + result.error + "\n需要 Root 与知弦模块（KernelSU / ReSukiSU）。");
                    return;
                }
                JSONObject status = result.status;
                StringBuilder line = new StringBuilder();
                line.append("模式 ").append(result.armed() ? "ARMED（保活中）" : "PAUSED（已暂停）");
                line.append("　·　watchdog ").append(result.running() ? "运行中" : "未运行");
                line.append("\nQQ ").append(result.qqAlive() ? "进程在" : "进程不在");
                line.append(status != null && status.optBoolean("qq_frozen") ? "（被冻结）" : "");
                line.append(result.online() ? "　·　在线" : "　·　离线");
                line.append("\n最近 1h 重启 ").append(status == null ? 0 : status.optInt("restarts_1h"))
                        .append(" 次　·　连续失败 ").append(status == null ? 0 : status.optInt("consec_fail"))
                        .append("　·　退避 ").append(status == null ? 0 : status.optInt("backoff_s")).append("s");
                guardState.setText(line.toString());
            });
        });
    }

    /** start / stop / kill 三种动作都走同一个 root 包装。 */
    private void guardAction(String action) {
        if (guardBusy) {
            toast("正在处理，请稍候");
            return;
        }
        guardBusy = true;
        guardState.setText("正在执行…");
        rootWorker.execute(() -> {
            GuardCommand.Result result;
            String message;
            switch (action) {
                case "start": result = GuardCommand.arm(); message = "守护已开启"; break;
                case "kill": result = GuardCommand.stopAndKill(); message = "已停止保活并关闭 QQ"; break;
                default: result = GuardCommand.pause(); message = "守护已暂停（QQ 未关闭）"; break;
            }
            final String text = result.ok ? message : result.error;
            main.post(() -> {
                guardBusy = false;
                if (!isDestroyed()) {
                    toast(text);
                    refreshGuard();
                }
            });
        });
    }

    @Override protected void onPause() {
        resumed = false;
        main.removeCallbacks(poll);
        reveal(false);
        super.onPause();
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(poll);
        worker.shutdown();
        rootWorker.shutdown();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("tab", selected);
        out.putBoolean("autoRefresh", autoRefresh);
        out.putString("port", portInput.getText().toString());
        out.putString("token", tokenInput.getText().toString());
        for (int i = 0; i < toggles.length; i++) out.putBoolean("toggle" + i, toggles[i].isChecked());
        for (int i = 0; i < pages.length; i++) out.putInt("scroll" + i, pages[i].getScrollY());
        out.putString("report", pendingReport);
        super.onSaveInstanceState(out);
    }

    @Override public void onBackPressed() {
        if (saving) {
            toast("正在保存，请稍候");
            return;
        }
        if (dirty()) confirmLeave();
        else if (selected != 0) switchTab(0, true);
        else super.onBackPressed();
    }

    private void confirmLeave() {
        widgets.confirm("保存这次修改？", "连接设置尚未保存。保存后，下次启动 QQ 时生效。",
                "保存并退出", false, () -> save(true));
    }

    // ------------------------------------------------------------------ 工具

    private void openQQ() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("com.tencent.mobileqq");
        if (intent == null) {
            toast("未找到 QQ，请先安装 QQ");
            return;
        }
        try {
            startActivity(intent);
        } catch (Exception e) {
            toast("无法打开 QQ，请从桌面打开");
        }
    }

    private void openAppInfo(String packageName) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName)));
        } catch (Exception e) {
            toast("请从系统设置打开应用信息");
        }
    }

    private String report() {
        return HealthClient.report(health, appVersion(), currentPort, checkedAt);
    }

    private void copy(String label, String value, boolean sensitive) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, value);
        if (sensitive && Build.VERSION.SDK_INT >= 24) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
        }
        if (clipboard != null) {
            clipboard.setPrimaryClip(clip);
            toast("已复制" + label);
        }
    }

    private void haptic(View view) {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) {
            android.view.inputmethod.InputMethodManager keyboard =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            focus.clearFocus();
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private String duration(long millis) {
        long minutes = Math.max(0, millis / 60000);
        return minutes < 60 ? minutes + " 分" : (minutes < 1440 ? minutes / 60 + " 小时" : minutes / 1440 + " 天");
    }

    /** 品牌头：图标 + 名称 + 版本，右侧一个刷新按钮（HIG：标题左、动作右）。 */
    private LinearLayout header(String name, String subtitle) {
        LinearLayout bar = widgets.row();
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(getApplicationInfo().loadIcon(getPackageManager()));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        bar.addView(icon, new LinearLayout.LayoutParams(ui.dimen("size_logo"), ui.dimen("size_logo")));
        LinearLayout words = widgets.column();
        words.addView(widgets.heading(name, Tokens.HEADLINE_MEDIUM, ui.onSurface), widgets.stack(0));
        words.addView(widgets.text(subtitle, Tokens.LABEL_MEDIUM, ui.onSurfaceVariant), widgets.stack(4));
        bar.addView(words, widgets.share(16));
        refreshButton = iconRefresh();
        bar.addView(refreshButton, new LinearLayout.LayoutParams(-2, -2));
        return bar;
    }

    /**
     * 页头：标题在左、读页的刷新动作在右。三个页面的标题用同一个字级，
     * 只有"有没有刷新"按页面性质区分（读页有、表单页没有）。
     */
    private LinearLayout pageTitle(String title, String subtitle, boolean withRefresh) {
        LinearLayout bar = widgets.row();
        LinearLayout block = widgets.column();
        block.addView(widgets.heading(title, Tokens.HEADLINE_MEDIUM, ui.onSurface), widgets.stack(0));
        block.addView(widgets.body(subtitle, ui.onSurfaceVariant), widgets.stack(8));
        bar.addView(block, widgets.share(0));
        if (withRefresh) bar.addView(iconRefresh(), new LinearLayout.LayoutParams(-2, -2));
        return bar;
    }

    private LinearLayout pageTitle(String title, String subtitle) {
        return pageTitle(title, subtitle, false);
    }

    /** 刷新按钮：状态页与诊断页各一个，启用状态一起变。 */
    private View iconRefresh() {
        View button = widgets.iconButton(Glyph.REFRESH, "刷新状态");
        button.setOnClickListener(v -> {
            refresh();
            refreshGuard();
        });
        refreshButtons.add(button);
        return button;
    }

    /** 探测期间刷新按钮置灰：容器降到 38% 不透明，与 M3 的禁用内容一致。 */
    private void setRefreshing(boolean busy) {
        float alpha = busy ? ui.integer("state_disabled_content_pct") / 100f : 1f;
        for (View button : refreshButtons) {
            button.setEnabled(!busy);
            button.setAlpha(alpha);
        }
    }

    private LinearLayout content() {
        LinearLayout content = widgets.column();
        int gutter = ui.dp(layout.gutterDp());
        content.setPadding(gutter, ui.dp(24), gutter, ui.dp(32));
        content.setFocusableInTouchMode(true);
        return content;
    }

    /**
     * 页容器：滚动 + 内容居中 + 宽度上限。上限跟断点走，宽屏不再把一行拉到 1000dp 宽，
     * 窄屏（320dp）与放大字号下则完全交给内容自己换行（WCAG 1.4.10 重排）。
     */
    private ScrollView page(LinearLayout content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        FrameLayout center = new FrameLayout(this);
        center.addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        scroll.addView(center, new ScrollView.LayoutParams(-1, -2));
        scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = Math.min(r - l, ui.dp(layout.contentMaxDp));
            if (content.getLayoutParams().width != width) {
                content.getLayoutParams().width = width;
                content.requestLayout();
            }
        });
        return scroll;
    }
}
