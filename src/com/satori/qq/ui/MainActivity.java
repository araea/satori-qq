package com.satori.qq.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
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
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
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
import org.json.JSONObject;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The management UI owns preferences, never the QQ service or its lifetime. */
public final class MainActivity extends Activity {
    private MaterialStyle ui;
    private ControlStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScrollView[] pages = new ScrollView[3];
    private final LinearLayout[] navigation = new LinearLayout[3];
    private final TextView[] navLabels = new TextView[3];
    private final Glyph[] navIcons = new Glyph[3];
    private int selected;
    private boolean resumed, probing, saving, revealing;
    private JSONObject health;
    private JSONObject loadedConfig;
    private long checkedAt;
    private int currentPort = 3001;
    private TextView stateTitle, stateDetail, clients, uptime, endpoint, updated, configurationStatus;
    private TextView versionStatus, diagnostics, diagnosticHint, dirtyLabel, sourceLabel;
    private Button refreshButton, saveButton, revealButton, reportButton, shareButton;
    private EditText portInput, tokenInput;
    private final Switch[] toggles = new Switch[ManagedConfig.SWITCHES.length];
    private ExpressiveProgress progress;
    private final Runnable poll = new Runnable() { @Override public void run() { if (resumed) refresh(); } };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new MaterialStyle(this); ui.applyWindow();
        setTitle(getApplicationInfo().loadLabel(getPackageManager()));
        store = new ControlStore(this);
        pendingReport = state == null ? null : state.getString("report");
        LinearLayout root = column(); root.setBackgroundColor(ui.surface);
        FrameLayout body = new FrameLayout(this);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        pages[0] = page(home()); pages[1] = page(settings(state)); pages[2] = page(diagnostics());
        for (ScrollView page : pages) body.addView(page, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        nav.setBackgroundColor(ui.container);
        for (int i = 0; i < 3; i++) {
            final int tab = i;
            LinearLayout item = new LinearLayout(this) {
                @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(info); info.setClassName("android.widget.Button");
                }
            };
            item.setOrientation(LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER);
            item.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6)); item.setMinimumHeight(ui.dp(64));
            item.setFocusable(true); item.setContentDescription(new String[]{"状态", "设置", "诊断"}[i]);
            item.setOnClickListener(v -> switchTab(tab));
            Glyph icon = new Glyph(ui, i, ui.muted);
            item.addView(icon, new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)));
            TextView label = ui.text(new String[]{"状态", "设置", "诊断"}[i], 12, ui.muted, true);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); label.setGravity(Gravity.CENTER);
            item.addView(label, params(4));
            nav.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
            navigation[i] = item; navLabels[i] = label; navIcons[i] = icon;
        }
        root.addView(nav, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        switchTab(state == null ? 0 : state.getInt("tab"));
        updateDraft(); updateState();
        if (state != null) for (int i = 0; i < 3; i++) { final int tab = i; pages[i].post(() -> pages[tab].scrollTo(0, state.getInt("scroll" + tab))); }
    }

    private LinearLayout home() {
        LinearLayout content = content();
        LinearLayout brand = new LinearLayout(this); brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this); icon.setImageDrawable(getApplicationInfo().loadIcon(getPackageManager()));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        brand.addView(icon, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        LinearLayout words = column();
        words.addView(ui.text("知弦", 28, ui.ink, true), params(0));
        words.addView(ui.text("SATORI FOR QQ", 11, ui.muted, true), params(0));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1); wp.leftMargin = ui.dp(12); brand.addView(words, wp);
        TextView badge = ui.text(appVersion(), 12, ui.muted, true); badge.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6)); badge.setBackground(ui.shape(ui.container, 20)); brand.addView(badge);
        content.addView(brand, params(0));
        TextView headline = ui.text("让消息，\n自在相连。", 36, ui.ink, true); heading(headline); content.addView(headline, params(28));
        content.addView(ui.text("连接此刻，也连接每一个回应。", 14, ui.muted, false), params(6));
        LinearLayout hero = card(ui.primaryContainer, 32);
        hero.addView(ui.text("本机服务", 12, ui.onPrimaryContainer, true), params(0));
        stateTitle = ui.text("正在检查", 28, ui.onPrimaryContainer, true); hero.addView(stateTitle, params(10));
        stateDetail = ui.text("正在读取 QQ 中的模块状态…", 14, ui.onPrimaryContainer, false); hero.addView(stateDetail, params(8));
        progress = new ExpressiveProgress(ui); hero.addView(progress, params(12));
        content.addView(hero, params(24));
        LinearLayout metrics = new LinearLayout(this);
        boolean stacked = getResources().getConfiguration().fontScale >= 1.5f;
        metrics.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        LinearLayout clientCard = card(ui.container, 24); clientCard.addView(ui.text("客户端", 12, ui.muted, true), params(0));
        clients = ui.text("—", 28, ui.ink, true); clientCard.addView(clients, params(8));
        LinearLayout timeCard = card(ui.container, 24); timeCard.addView(ui.text("连续在线", 12, ui.muted, true), params(0));
        uptime = ui.text("—", 24, ui.ink, true); timeCard.addView(uptime, params(8));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(stacked ? -1 : 0, -2, stacked ? 0 : 1);
        metrics.addView(clientCard, cp);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(stacked ? -1 : 0, -2, stacked ? 0 : 1);
        if (stacked) tp.topMargin = ui.dp(12); else tp.leftMargin = ui.dp(12);
        metrics.addView(timeCard, tp); content.addView(metrics, params(12));
        LinearLayout connection = card(ui.container, 24);
        connection.addView(ui.text("连接地址", 16, ui.ink, true), params(0));
        endpoint = ui.text("", 16, ui.primary, true); endpoint.setTextIsSelectable(true); connection.addView(endpoint, params(8));
        connection.addView(ui.text("仅供这台设备上的 Satori 客户端使用。", 12, ui.muted, false), params(6));
        Button copy = ui.button("复制连接地址", false); copy.setOnClickListener(v -> copy("连接地址", endpoint.getText().toString(), false)); connection.addView(copy, params(16));
        content.addView(connection, params(12));
        Button open = ui.button("打开 QQ", true); open.setOnClickListener(v -> openQQ()); content.addView(open, params(20));
        refreshButton = ui.button("刷新状态", false); refreshButton.setOnClickListener(v -> refresh()); content.addView(refreshButton, params(8));
        updated = ui.text("", 12, ui.muted, false); updated.setGravity(Gravity.CENTER); content.addView(updated, params(12));
        TextView serviceHint = ui.text("管理页关闭后，服务仍随 QQ 运行。", 12, ui.muted, false);
        serviceHint.setGravity(Gravity.CENTER);
        content.addView(serviceHint, params(20));
        return content;
    }

    private LinearLayout settings(Bundle state) {
        LinearLayout content = content(); title(content, "连接设置", "把连接方式，调成习惯的样子。");
        configurationStatus = ui.text("", 14, ui.onPrimaryContainer, true); configurationStatus.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16)); configurationStatus.setBackground(ui.shape(ui.primaryContainer, 24)); content.addView(configurationStatus, params(20));
        JSONObject config = store.editable();
        loadedConfig = config;
        LinearLayout network = card(ui.container, 24); network.addView(ui.text("连接", 20, ui.ink, true), params(0));
        portInput = input(network, "本机端口", "3001", false);
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER); portInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(5)});
        portInput.setText(state == null ? String.valueOf(config.optInt("port", 3001)) : state.getString("port", "3001"));
        network.addView(ui.text("可用范围 1024–65535。修改后需同步客户端地址。", 12, ui.muted, false), params(6));
        tokenInput = input(network, "连接令牌", "留空时不校验令牌", true);
        tokenInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(128)});
        tokenInput.setText(state == null ? config.optString("token", "") : state.getString("token", ""));
        network.addView(ui.text("令牌用于客户端连接验证。留空时，本机客户端无需令牌即可访问。", 12, ui.muted, false), params(6));
        revealButton = ui.button("显示令牌", false); revealButton.setOnClickListener(v -> reveal(!revealing)); network.addView(revealButton, params(12));
        Button generate = ui.button("生成新令牌", false); generate.setOnClickListener(v -> { byte[] bytes = new byte[24]; new SecureRandom().nextBytes(bytes); tokenInput.setText(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)); toast("已生成；保存后请同步更新客户端令牌"); }); network.addView(generate, params(8));
        Button copyToken = ui.button("复制令牌", false); copyToken.setOnClickListener(v -> { if (tokenInput.length() == 0) toast("当前令牌为空"); else copy("连接令牌", tokenInput.getText().toString(), true); }); network.addView(copyToken, params(8));
        content.addView(network, params(16));
        LinearLayout behavior = card(ui.container, 24); behavior.addView(ui.text("运行偏好", 20, ui.ink, true), params(0));
        String[] labels = {"状态通知", "自动保持唤醒", "保持 Wi-Fi 连接", "手机手动发的消息也投递"};
        String[] hints = {"显示连接状态。", "QQ 启动时自动获取唤醒锁，会增加待机耗电。", "客户端连接期间保持 Wi-Fi 锁，有助于息屏传输。", "把你在 QQ 客户端里手动发出的消息也作为消息事件投递，作者用独立身份（qq-client:账号），便于在手机上手动发消息测试机器人。"};
        for (int i = 0; i < toggles.length; i++) {
            Switch toggle = toggle(labels[i]); final int index = i;
            toggle.setChecked(state == null ? config.optBoolean(ManagedConfig.SWITCHES[i], true) : state.getBoolean("toggle" + i));
            behavior.addView(toggle, params(16)); behavior.addView(ui.text(hints[i], 12, ui.muted, false), params(6)); toggles[index] = toggle;
        }
        content.addView(behavior, params(16));
        dirtyLabel = ui.text("", 12, ui.primary, true); content.addView(dirtyLabel, params(20));
        saveButton = ui.button("保存设置", true); saveButton.setOnClickListener(v -> save(false)); content.addView(saveButton, params(10));
        content.addView(ui.text("保存后，下次启动 QQ 时生效。需要立即应用时，请在 QQ 应用信息页停止 QQ，再重新打开；客户端会短暂断开。", 12, ui.muted, false), params(12));
        Button appInfo = ui.button("QQ 应用信息", false); appInfo.setOnClickListener(v -> openAppInfo("com.tencent.mobileqq")); content.addView(appInfo, params(12));
        content.addView(ui.text("若系统限制关联启动，请在系统设置的「应用 → 关联启动」里允许知弦。否则 QQ 可能无法在后台读取设置；也可先打开知弦，再重启 QQ。", 12, ui.muted, false), params(16));
        Button ownInfo = ui.button("知弦应用信息", false); ownInfo.setOnClickListener(v -> openAppInfo(getPackageName())); content.addView(ownInfo, params(8));
        LinearLayout advanced = card(ui.container, 24); sourceLabel = ui.text("", 14, ui.muted, false); advanced.addView(sourceLabel, params(0));
        advanced.addView(ui.text("其他高级选项继续读取原 JSON 配置文件。", 12, ui.muted, false), params(8));
        Button reset = ui.button("使用文件配置", false); reset.setOnClickListener(v -> confirm("使用文件配置？", "下次启动 QQ 时，页面中的连接与运行偏好将改为读取原 JSON 文件；没有文件时使用默认值。", "确认切换", () -> resetToFile())); advanced.addView(reset, params(16));
        content.addView(advanced, params(24));
        TextWatcher watcher = new TextWatcher() { public void beforeTextChanged(CharSequence s, int st, int c, int a) {} public void onTextChanged(CharSequence s, int st, int b, int c) { if (saveButton != null) updateDraft(); } public void afterTextChanged(Editable e) {} };
        portInput.addTextChangedListener(watcher); tokenInput.addTextChangedListener(watcher);
        for (Switch toggle : toggles) toggle.setOnCheckedChangeListener((v, checked) -> updateDraft());
        return content;
    }

    private LinearLayout diagnostics() {
        LinearLayout content = content(); title(content, "连接诊断", "让每一次排查，都有清晰的依据。");
        LinearLayout summary = card(ui.primaryContainer, 28);
        versionStatus = ui.text("", 18, ui.onPrimaryContainer, true); summary.addView(versionStatus, params(0));
        summary.addView(ui.text("已安装版本与正在运行的模块可能不同。更新模块后，QQ 需要重新加载。", 12, ui.onPrimaryContainer, false), params(8)); content.addView(summary, params(24));
        LinearLayout panel = card(ui.container, 24); diagnostics = ui.text("", 14, ui.ink, false); diagnostics.setTextIsSelectable(true); panel.addView(diagnostics, params(0)); content.addView(panel, params(16));
        diagnosticHint = ui.text("", 14, ui.muted, false); content.addView(diagnosticHint, params(16));
        reportButton = ui.button("复制诊断报告", true); reportButton.setOnClickListener(v -> copy("诊断报告", report(), false)); content.addView(reportButton, params(20));
        shareButton = ui.button("导出诊断报告", false); shareButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "知弦-诊断-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(new Date()) + ".txt");
            pendingReport = report(); try { startActivityForResult(intent, 42); } catch (Exception e) { toast("未找到文件保存应用，请使用复制报告"); }
        }); content.addView(shareButton, params(8));
        content.addView(ui.text("报告仅含服务状态与错误计数，不包含令牌、QQ 账号或消息内容。", 12, ui.muted, false), params(12));
        LinearLayout help = card(ui.container, 24); help.addView(ui.text("连接前，检查这三步", 20, ui.ink, true), params(0));
        help.addView(ui.text("01  在模块管理器启用知弦，并勾选 QQ。\n\n02  重新启动 QQ，确认账号已登录。\n\n03  在 Satori 客户端填写本机地址与相同令牌。", 14, ui.muted, false), params(12)); content.addView(help, params(24));
        return content;
    }

    private String pendingReport;
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 42 || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData(); final String value = pendingReport == null ? report() : pendingReport;
        worker.execute(() -> {
            boolean ok = false;
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri, "wt")) { if (out != null) { out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); ok = true; } } catch (Exception ignored) {}
            final boolean saved = ok; main.post(() -> { if (!isDestroyed()) toast(saved ? "诊断报告已保存" : "保存失败，请重试或复制报告"); });
        });
    }

    private void refresh() {
        if (probing || isDestroyed()) return;
        main.removeCallbacks(poll); probing = true; progress.setVisibility(View.VISIBLE); refreshButton.setEnabled(false);
        JSONObject runtime = store.runtime(); JSONObject config = runtime.optJSONObject("config");
        currentPort = config == null ? 3001 : config.optInt("port", 3001);
        endpoint.setText("http://127.0.0.1:" + currentPort);
        final int port = currentPort;
        worker.execute(() -> {
            JSONObject result = null;
            try { result = HealthClient.read(port); } catch (Exception ignored) {}
            final JSONObject response = result;
            main.post(() -> {
                if (isDestroyed()) return;
                health = response; checkedAt = System.currentTimeMillis(); probing = false;
                if (!saving && !dirty()) loadForm(store.editable());
                progress.setVisibility(View.INVISIBLE); refreshButton.setEnabled(true); updateState();
                if (resumed) main.postDelayed(poll, 5000);
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
        boolean listening = reachable && health.optBoolean("listening");
        if (checkedAt == 0) { stateTitle.setText("正在检查"); stateDetail.setText("正在读取 QQ 中的模块状态…"); }
        else if (!reachable) { stateTitle.setText("尚未连接"); stateDetail.setText("请确认模块已启用，并打开 QQ。更新模块后，需要重新启动 QQ。"); }
        else if (!listening) { stateTitle.setText("服务待恢复"); stateDetail.setText("模块已响应，本机端口尚未就绪。请查看诊断信息。"); }
        else if (!online) { stateTitle.setText("等待登录"); stateDetail.setText("本机服务已就绪，打开 QQ 登录账号后即可连接。"); }
        else { stateTitle.setText("连接就绪"); stateDetail.setText("QQ 与本机服务均已就绪。" + (health.optInt("connections") == 0 ? "等待客户端接入。" : "客户端已接入，消息桥梁正在运行。")); }
        clients.setText(reachable ? String.valueOf(health.optInt("connections")) : "—");
        long since = reachable ? health.optLong("online_since_epoch_ms") : 0;
        uptime.setText(!online || since == 0 ? "—" : duration(System.currentTimeMillis() - since));
        updated.setText(checkedAt == 0 ? "检查中…" : "最近检查 " + new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date(checkedAt)) + " · 前台每 5 秒更新");
        long applied = reachable ? health.optLong("config_revision", -1) : -1;
        configurationStatus.setText(reachable && health.optString("config_status").startsWith("provider-unavailable") ? "设置尚未读取 · 请允许知弦关联启动，或先打开知弦再重启 QQ" : applied == store.revision() ? "已保存的设置已生效" : (store.revision() > 0 ? "设置已保存 · 等待 QQ 重新启动" : "尚未确认运行配置 · 重启 QQ 后同步"));
        sourceLabel.setText(store.overrides().isEmpty() ? "设置来源：原 JSON 文件 / 默认值" : "设置来源：知弦管理页（优先于文件中的同名选项）");
        versionStatus.setText("应用 " + appVersion() + "\n运行 " + (reachable ? health.optString("version", "未知") : "未连接"));
        if (!reachable) {
            diagnostics.setText("尚未取得诊断数据\n\n请先打开 QQ，并确认模块作用域包含 QQ。若更新后仍无响应，请重新启动 QQ。");
            diagnosticHint.setText("连接失败时不会沿用上次的在线状态。管理页退出不会停止 QQ 服务。");
        } else {
            JSONObject compat = health.optJSONObject("compat"); JSONObject sso = health.optJSONObject("sso");
            diagnostics.setText("QQ 版本  " + health.optString("qq_version", "未知")
                    + "\n\n内核接口  " + (compat == null ? "待检查" : compat.optInt("passed") + " / " + compat.optInt("total"))
                    + "\n\n状态通知  " + (health.optString("notice").contains("posted=yes") ? "已发布" : "关闭或等待系统授权")
                    + "\n\n请求失败  " + (sso == null ? 0 : sso.optInt("failures")) + " 次"
                    + "\n\n会话错误  " + (sso == null ? 0 : sso.optInt("session_errors")) + " 次");
            diagnosticHint.setText(!appVersion().equals(health.optString("version")) ? "正在运行旧版模块。重新启动 QQ 后，新版本才会生效。" : "在线状态来自 QQ 内核，不能单独证明服务端会话有效。遇到消息不通时，请结合离线记录与实际连接排查。");
        }
    }

    private JSONObject draft() throws Exception {
        String rawPort = portInput.getText().toString().trim();
        int port; try { port = Integer.parseInt(rawPort); } catch (Exception e) { throw new IllegalArgumentException("端口须为 1024–65535 的整数"); }
        JSONObject json = new JSONObject().put("port", port).put("token", tokenInput.getText().toString());
        for (int i = 0; i < toggles.length; i++) json.put(ManagedConfig.SWITCHES[i], toggles[i].isChecked());
        return ManagedConfig.validate(json);
    }
    private boolean dirty() {
        JSONObject original = loadedConfig;
        if (!portInput.getText().toString().equals(String.valueOf(original.optInt("port", 3001))) || !tokenInput.getText().toString().equals(original.optString("token", ""))) return true;
        for (int i = 0; i < toggles.length; i++) if (toggles[i].isChecked() != original.optBoolean(ManagedConfig.SWITCHES[i], true)) return true;
        return false;
    }
    private void updateDraft() {
        boolean changed = dirty(); saveButton.setEnabled(!saving && changed);
        saveButton.setText(saving ? "正在保存…" : (changed ? "保存设置" : "已保存"));
        dirtyLabel.setText(changed ? "有未保存的修改" : "与已保存设置一致");
    }
    private void save(boolean finishAfter) {
        if (saving) return;
        final JSONObject value;
        try { value = draft(); } catch (Exception e) { if (e.getMessage() != null && e.getMessage().startsWith("令牌")) tokenInput.setError(e.getMessage()); else portInput.setError(e.getMessage()); toast(e.getMessage()); return; }
        portInput.setError(null); tokenInput.setError(null); saving = true; updateDraft();
        worker.execute(() -> {
            String error = null; try { store.save(value); } catch (Exception e) { error = "保存失败，请重试"; }
            final String message = error;
            main.post(() -> {
                if (isDestroyed()) return;
                saving = false; if (message == null) loadedConfig = value; updateDraft(); updateState();
                if (message != null) toast(message);
                else { hideKeyboard(); toast("已保存，下次启动 QQ 时生效"); if (finishAfter) finish(); }
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
        saving = true; updateDraft();
        worker.execute(() -> {
            boolean ok = true; try { store.useFile(); } catch (Exception e) { ok = false; }
            final boolean done = ok;
            main.post(() -> {
                if (isDestroyed()) return;
                saving = false;
                if (done) { loadForm(store.editable()); toast("已切换，下次启动 QQ 后读取文件配置"); }
                else toast("保存失败，请重试");
                updateDraft(); updateState();
            });
        });
    }
    private void switchTab(int index) {
        selected = Math.max(0, Math.min(2, index));
        if (selected != 1) { hideKeyboard(); reveal(false); }
        for (int i = 0; i < pages.length; i++) {
            boolean active = i == selected; pages[i].setVisibility(active ? View.VISIBLE : View.GONE);
            navigation[i].setSelected(active);
            navigation[i].setBackground(new RippleDrawable(ColorStateList.valueOf((ui.primary & 0xFFFFFF) | 0x20000000), ui.shape(active ? ui.primaryContainer : ui.container, active ? 28 : 16), null));
            navLabels[i].setTextColor(active ? ui.onPrimaryContainer : ui.muted); navIcons[i].tint(active ? ui.onPrimaryContainer : ui.muted);
        }
    }
    private void reveal(boolean value) {
        revealing = value;
        tokenInput.setTransformationMethod(value ? null : PasswordTransformationMethod.getInstance());
        tokenInput.setSelection(tokenInput.length()); revealButton.setText(value ? "隐藏令牌" : "显示令牌");
        if (value) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    @Override protected void onResume() { super.onResume(); resumed = true; refresh(); }
    @Override protected void onPause() { resumed = false; main.removeCallbacks(poll); reveal(false); super.onPause(); }
    @Override protected void onDestroy() { main.removeCallbacks(poll); worker.shutdown(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("tab", selected); out.putString("port", portInput.getText().toString()); out.putString("token", tokenInput.getText().toString());
        for (int i = 0; i < toggles.length; i++) out.putBoolean("toggle" + i, toggles[i].isChecked());
        for (int i = 0; i < pages.length; i++) out.putInt("scroll" + i, pages[i].getScrollY());
        out.putString("report", pendingReport); super.onSaveInstanceState(out);
    }
    @Override public void onBackPressed() {
        if (saving) { toast("正在保存，请稍候"); return; }
        if (dirty()) confirmLeave(); else if (selected != 0) switchTab(0); else super.onBackPressed();
    }
    private void confirmLeave() {
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("保存这次修改？").setMessage("连接设置尚未保存。保存后，下次启动 QQ 时生效。")
                .setPositiveButton("保存并退出", (d, w) -> save(true)).setNegativeButton("放弃修改", (d, w) -> finish()).setNeutralButton("继续编辑", (d, w) -> switchTab(1)).create(); styleDialog(dialog);
    }
    private void confirm(String title, String message, String action, Runnable run) {
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton(action, (d, w) -> run.run()).setNegativeButton("取消", null).create(); styleDialog(dialog);
    }
    private void styleDialog(AlertDialog dialog) {
        dialog.setOnShowListener(d -> { dialog.getWindow().setBackgroundDrawable(ui.shape(ui.container, 28)); for (int button : new int[]{-1,-2,-3}) dialog.getButton(button).setTextColor(ui.primary); }); dialog.show();
    }
    private void openQQ() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("com.tencent.mobileqq");
        if (intent == null) { toast("未找到 QQ，请先安装 QQ"); return; }
        try { startActivity(intent); } catch (Exception e) { toast("无法打开 QQ，请从桌面打开"); }
    }
    private void openAppInfo(String packageName) { try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName))); } catch (Exception e) { toast("请从系统设置打开应用信息"); } }
    private String report() { return HealthClient.report(health, appVersion(), currentPort, checkedAt); }
    private void copy(String label, String value, boolean sensitive) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE); ClipData clip = ClipData.newPlainText(label, value);
        if (sensitive && Build.VERSION.SDK_INT >= 24) { PersistableBundle extras = new PersistableBundle(); extras.putBoolean("android.content.extra.IS_SENSITIVE", true); clip.getDescription().setExtras(extras); }
        if (clipboard != null) { clipboard.setPrimaryClip(clip); toast("已复制" + label); }
    }
    private void hideKeyboard() { View focus = getCurrentFocus(); if (focus != null) { InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE); if (keyboard != null) keyboard.hideSoftInputFromWindow(focus.getWindowToken(), 0); focus.clearFocus(); } }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private String appVersion() { try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; } }
    private String duration(long millis) { long minutes = Math.max(0, millis / 60000); return minutes < 60 ? minutes + " 分" : (minutes < 1440 ? minutes / 60 + " 小时" : minutes / 1440 + " 天"); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout content() { LinearLayout v = column(); v.setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(32)); v.setFocusableInTouchMode(true); return v; }
    private LinearLayout card(int color, int radius) { LinearLayout v = column(); v.setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20)); v.setBackground(ui.shape(color, radius)); return v; }
    private LinearLayout.LayoutParams params(int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = ui.dp(top); return p; }
    private void heading(TextView view) { if (Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true); }
    private void title(LinearLayout content, String title, String subtitle) { TextView v = ui.text(title, 32, ui.ink, true); heading(v); content.addView(v, params(8)); content.addView(ui.text(subtitle, 14, ui.muted, false), params(8)); }
    private ScrollView page(LinearLayout content) {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        FrameLayout center = new FrameLayout(this); center.addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL)); scroll.addView(center, new ScrollView.LayoutParams(-1, -2));
        scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { int width = Math.min(r-l, ui.dp(640)); if (content.getLayoutParams().width != width) { content.getLayoutParams().width = width; content.requestLayout(); } }); return scroll;
    }
    private EditText input(LinearLayout parent, String title, String hint, boolean secret) {
        TextView label = ui.text(title, 14, ui.muted, true); parent.addView(label, params(20));
        EditText input = new EditText(this); input.setId(View.generateViewId()); label.setLabelFor(input.getId()); input.setSaveEnabled(false);
        input.setSingleLine(true); input.setTextSize(16); input.setTextColor(ui.ink); input.setHintTextColor(ui.muted); input.setHint(hint);
        input.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16)); input.setMinimumHeight(ui.dp(56)); input.setBackground(ui.shape(ui.surface, 12, ui.outline, 1));
        input.setOnFocusChangeListener((v, focused) -> input.setBackground(ui.shape(ui.surface, 12, focused ? ui.primary : ui.outline, focused ? 2 : 1)));
        if (secret) { input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); input.setTransformationMethod(PasswordTransformationMethod.getInstance()); input.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_ACTION_DONE); input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); }
        parent.addView(input, params(8)); return input;
    }
    private Switch toggle(String label) {
        Switch v = new Switch(this); v.setText(label); v.setTextSize(16); v.setTextColor(ui.ink); v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setMinHeight(ui.dp(64)); v.setPadding(ui.dp(16), ui.dp(8), ui.dp(12), ui.dp(8)); v.setSwitchPadding(ui.dp(16)); v.setBackground(ui.shape(ui.surface, 20)); v.setSaveEnabled(false);
        GradientDrawable track = ui.shape(ui.high, 16, ui.outline, 2); track.setSize(ui.dp(52), ui.dp(32)); v.setTrackDrawable(track); v.setTrackTintList(ui.states(ui.primary, ui.outline));
        GradientDrawable thumb = ui.shape(ui.onPrimary, 12); thumb.setSize(ui.dp(24), ui.dp(24)); v.setThumbDrawable(new InsetDrawable(thumb, ui.dp(4))); v.setThumbTintList(ui.states(ui.onPrimary, ui.surface)); v.setSplitTrack(false); return v;
    }
}
