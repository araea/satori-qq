package com.satori.qq.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.satori.qq.R;
import com.satori.qq.control.ManagedConfig;
import java.security.SecureRandom;
import org.json.JSONObject;

/**
 * 连接设置：端口、令牌与四个运行偏好。保存后在 QQ 下次启动时生效。
 *
 * <p>交互约定（HIG）：草稿跨页面切换与实例重建保留；有改动才出现保存栏；放弃改动与离开未保存的页面
 * 都先确认；令牌默认隐藏，显示期间禁止截屏。校验在保存时做（Carbon 表单模式），出错的字段
 * 就地显示原因并获得焦点。
 */
final class SettingsPage {
    interface Actions {
        void back();
        void save(JSONObject value);
        void discard();
        void useFile();
        void relaunchQQ();
        void copyToken(String token);
        void secure(boolean on);
        void message(String text);
        /** 草稿从"无改动"变成"有改动"或反过来：宿主据此决定是否拦截返回。 */
        void draftChanged(boolean dirty);
    }

    private static final String[] LABELS = {"状态通知", "启动时保持唤醒", "保持 Wi-Fi 连接", "投递手机上手动发的消息"};
    private static final String[] HINTS = {
            "在通知栏显示连接状态，点按可回到 QQ。",
            "QQ 启动后立即持有唤醒锁，减少掉线，待机耗电会增加。",
            "有客户端连接时持有 Wi-Fi 锁。只在 Wi-Fi 下有效。",
            "你在 QQ 里亲手发的消息也作为事件投递，作者为独立身份 qq-client:账号，方便测试机器人。",
    };

    final LinearLayout root;
    final ScrollView scroll;
    final TopBar bar;
    final Notice notice;
    private final Ui ui;
    private final Tokens t;
    private final Actions actions;
    private final Field port, token;
    private final ImageButton reveal;
    private final Item[] toggles = new Item[ManagedConfig.SWITCHES.length];
    private final Item source;
    private final LinearLayout saveBar;
    private final TextView saveState;
    private final Btn save, discard;
    private JSONObject saved = new JSONObject();
    private boolean overrides;
    private boolean saving;
    private boolean revealing;
    private boolean loading;
    private boolean lastDirty;
    /** 表单是否已有基准（载入过设置或恢复过草稿）；没有基准时空表单不算草稿。 */
    private boolean baseline;
    private int bottomInset;

    /**
     * @param pane 双栏模式：设置作为右侧窗格，底色降一级表面以与左栏区分，且没有返回按钮
     */
    SettingsPage(Ui ui, Actions actions, boolean pane) {
        this.ui = ui;
        this.t = ui.t;
        this.actions = actions;
        boolean withBack = !pane;
        int base = pane ? t.surfaceContainerLow : t.surface;

        root = ui.column();
        root.setId(R.id.settings);
        root.setBackgroundColor(base);
        bar = new TopBar(ui, "连接设置", base, t.surfaceContainer);
        if (withBack) {
            ImageButton up = ui.iconButton(Icon.BACK, "返回", t.onSurface);
            up.setId(R.id.navigate_up);
            up.setOnClickListener(v -> actions.back());
            bar.navigation(up);
        }
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        scroll = Pages.scroll(t);
        LinearLayout content = Pages.content(ui);
        scroll.addView(Pages.center(ui, content));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView title = ui.heading("连接设置", Tokens.HEADLINE_LARGE, t.onSurface);
        content.addView(title, Ui.stack(t.spaceSm));
        content.addView(ui.text("客户端连接知弦时使用的端口与令牌，以及服务的运行方式。",
                Tokens.BODY_LARGE, t.onSurfaceVariant), Ui.stack(t.spaceXs));
        bar.follow(scroll, title);

        notice = new Notice(t);
        notice.setId(R.id.config_notice);
        notice.action("重新启动 QQ", v -> actions.relaunchQQ());
        content.addView(notice, Ui.stack(t.spaceXl));

        // ---- 连接 ----
        content.addView(ui.sectionTitle("连接"), Ui.stack(t.space2xl));
        LinearLayout form = ui.column();
        form.setPadding(t.spaceLg, t.spaceLg + t.spaceXs, t.spaceLg, t.spaceLg + t.spaceXs);
        form.setBackground(Shape.smooth(t.surfaceContainer, t.shapeXl));
        port = new Field(t, "本机端口", "3001", false);
        port.input.setId(R.id.port);
        port.input.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(5)});
        port.hint("1024–65535。改动后，客户端里的地址也要一起改。");
        form.addView(port, Ui.stack(0));
        token = new Field(t, "连接令牌", "留空则不校验", true);
        token.input.setId(R.id.token);
        token.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(128)});
        token.hint("客户端须填写相同的令牌。只能用英文字母、数字与符号，不含空格。");
        reveal = ui.iconButton(Icon.SHOW, "显示令牌", t.onSurfaceVariant);
        reveal.setId(R.id.token_reveal);
        reveal.setOnClickListener(v -> reveal(!revealing));
        token.trailing(reveal);
        form.addView(token, Ui.stack(t.spaceXl));
        Btn generate = ui.button("生成令牌", Btn.TONAL).icon(Icon.KEY);
        generate.setOnClickListener(v -> {
            byte[] bytes = new byte[24];
            new SecureRandom().nextBytes(bytes);
            token.setText(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
            token.error(null);
            actions.message("已生成新令牌。保存后，记得同步到客户端。");
        });
        Btn copy = ui.button("复制令牌", Btn.TONAL).icon(Icon.COPY);
        copy.setOnClickListener(v -> {
            if (token.text().isEmpty()) actions.message("令牌为空，无需复制");
            else actions.copyToken(token.text());
        });
        form.addView(ui.connected(generate, copy), Ui.stack(t.spaceLg));
        content.addView(form, Ui.stack(0));

        // ---- 运行偏好 ----
        content.addView(ui.sectionTitle("运行偏好"), Ui.stack(t.space2xl));
        Ui.Group behavior = ui.group();
        for (int i = 0; i < toggles.length; i++) {
            toggles[i] = behavior.add(new Item(t, Item.SWITCH, LABELS[i], HINTS[i]));
            toggles[i].setOnToggle((item, checked) -> changed());
        }
        content.addView(behavior, Ui.stack(0));

        // ---- 保存栏 ----
        saveBar = ui.column();
        saveBar.setId(R.id.save_bar);
        saveState = Ui.live(ui.text("", Tokens.LABEL_LARGE, t.onSurface));
        discard = ui.button("放弃修改", Btn.TEXT);
        discard.setId(R.id.discard);
        discard.setOnClickListener(v -> confirmDiscard(null));
        save = ui.prominent("保存", Btn.FILLED);
        save.setId(R.id.save);
        save.setOnClickListener(v -> submit());
        boolean pinned = pinned();
        if (pinned) {
            saveBar.setPadding(ui.layout.gutter(), t.spaceMd, ui.layout.gutter(), t.spaceMd);
            saveBar.setBackgroundColor(t.surfaceContainer);
            LinearLayout line = ui.row();
            line.addView(saveState, Ui.share(0));
            line.addView(discard, new LinearLayout.LayoutParams(-2, -2));
            LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-2, -2);
            saveParams.setMarginStart(t.spaceSm);
            line.addView(save, saveParams);
            saveBar.addView(line, Ui.stack(0));
            root.addView(saveBar, new LinearLayout.LayoutParams(-1, -2));
        } else {
            // 大字号：保存区进入滚动内容，不再常驻底部挤占本就不多的可视高度。
            saveBar.setPadding(t.spaceLg, t.spaceLg, t.spaceLg, t.spaceLg);
            saveBar.setBackground(Shape.smooth(t.surfaceContainerHigh, t.shapeXl));
            saveBar.addView(saveState, Ui.stack(0));
            saveBar.addView(save, Ui.stack(t.spaceMd));
            saveBar.addView(discard, Ui.stack(t.spaceXs));
            content.addView(saveBar, Ui.stack(t.spaceXl));
        }
        // ---- 配置来源 ----
        content.addView(ui.sectionTitle("配置来源"), Ui.stack(t.space2xl));
        Ui.Group origin = ui.group();
        source = origin.add(new Item(t, Item.ACTION, "改用文件配置", "").leading(Icon.FILE, t.onSurfaceVariant));
        source.setId(R.id.use_file);
        source.setOnClickListener(v -> Dialogs.show(ui, "改用文件配置？",
                "下次启动 QQ 时，端口、令牌与运行偏好改为读取原 JSON 配置文件；没有文件时使用默认值。"
                        + "这里保存过的设置会被清除。",
                new Dialogs.Action("取消", Btn.TEXT, null),
                new Dialogs.Action("改用文件配置", Btn.TEXT, actions::useFile)));
        content.addView(origin, Ui.stack(0));
        content.addView(ui.text("其余高级选项（限频、合并转发方式等）只在 JSON 文件里设置。",
                Tokens.BODY_SMALL, t.onSurfaceVariant), Pages.note(t));

        saveBar.setVisibility(View.GONE);

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { changed(); }
        };
        port.input.addTextChangedListener(watcher);
        token.input.addTextChangedListener(watcher);
    }

    boolean pinned() {
        return ui.layout.fontScale < 1.5f;
    }

    boolean saveBarShown() {
        return pinned() && saveBar.getVisibility() == View.VISIBLE;
    }

    int saveBarHeight() {
        return saveBarShown() ? saveBar.getHeight() : 0;
    }

    /** 底部系统栏高度：常驻保存栏出现时由它垫在系统栏上方，否则留给滚动内容。 */
    void setBottomInset(int inset) {
        bottomInset = inset;
        applyInset();
    }

    private void applyInset() {
        boolean bar = saveBarShown();
        scroll.setPadding(0, 0, 0, bar ? 0 : bottomInset);
        if (pinned()) saveBar.setPadding(saveBar.getPaddingLeft(), t.spaceMd, saveBar.getPaddingRight(), t.spaceMd + bottomInset);
    }

    // ------------------------------------------------------------------ 表单

    /** 载入已保存的设置；有未保存草稿时不覆盖草稿（除非 force）。 */
    void load(JSONObject config, boolean hasOverrides, boolean force) {
        if (!baseline) force = true;
        baseline = true;
        overrides = hasOverrides;
        source.setSupporting(hasOverrides
                ? "当前由知弦管理，优先于文件里的同名选项"
                : "当前读取 JSON 配置文件或默认值");
        source.setEnabled(hasOverrides && !saving);
        if (!force && (dirty() || saving)) {
            saved = config;
            changed();
            return;
        }
        saved = config;
        loading = true;
        port.setText(String.valueOf(config.optInt("port", 3001)));
        token.setText(config.optString("token", ""));
        for (int i = 0; i < toggles.length; i++) {
            toggles[i].setChecked(config.optBoolean(ManagedConfig.SWITCHES[i], true), false);
        }
        loading = false;
        port.error(null);
        token.error(null);
        changed();
    }

    boolean dirty() {
        if (!port.text().equals(String.valueOf(saved.optInt("port", 3001)))) return true;
        if (!token.text().equals(saved.optString("token", ""))) return true;
        for (int i = 0; i < toggles.length; i++) {
            if (toggles[i].isChecked() != saved.optBoolean(ManagedConfig.SWITCHES[i], true)) return true;
        }
        return false;
    }

    private void changed() {
        if (loading) return;
        boolean dirty = dirty();
        if (dirty != lastDirty) {
            lastDirty = dirty;
            actions.draftChanged(dirty);
        }
        boolean show = dirty || saving;
        Ui.set(saveState, saving ? "正在保存…" : "有未保存的修改");
        save.setEnabled(dirty && !saving);
        discard.setEnabled(dirty && !saving);
        Ui.set(save, saving ? "正在保存" : "保存");
        if ((saveBar.getVisibility() == View.VISIBLE) == show) return;
        if (!show) {
            saveBar.setVisibility(View.GONE);
            applyInset();
            return;
        }
        saveBar.setVisibility(View.VISIBLE);
        applyInset();
        if (Spring.enabled() && pinned()) {
            saveBar.setAlpha(0f);
            saveBar.setTranslationY(t.dp(24));
            saveBar.animate().alpha(1f).translationY(0).setDuration(t.spatialDefault.duration)
                    .setInterpolator(t.spatialDefault).start();
        }
    }

    /** 校验并交给宿主保存；出错的字段就地提示并获得焦点。 */
    void submit() {
        if (saving) return;
        JSONObject value;
        try {
            int number;
            try {
                number = Integer.parseInt(port.text().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("端口须为 1024–65535 的整数");
            }
            JSONObject raw = new JSONObject().put("port", number).put("token", token.text());
            for (int i = 0; i < toggles.length; i++) raw.put(ManagedConfig.SWITCHES[i], toggles[i].isChecked());
            value = ManagedConfig.validate(raw);
        } catch (Exception error) {
            String message = error.getMessage() == null ? "设置无效" : error.getMessage();
            if (message.startsWith("令牌")) {
                port.error(null);
                token.error(message);
            } else {
                token.error(null);
                port.error(message);
            }
            return;
        }
        port.error(null);
        token.error(null);
        saving = true;
        changed();
        actions.save(value);
    }

    /** 宿主保存完成后回调。 */
    void saved(JSONObject value, boolean ok) {
        saving = false;
        if (ok) {
            load(value, true, true);
            reveal(false);
        } else {
            changed();
        }
    }

    void setSaving(boolean value) {
        saving = value;
        changed();
    }

    /** 不经确认直接回到已保存的设置（调用方已经问过用户）。 */
    void discardNow() {
        load(saved, overrides, true);
    }

    /** 放弃草稿前确认；确认后回到已保存的设置，再执行 then。 */
    void confirmDiscard(Runnable then) {
        if (!dirty() || saving) {
            if (then != null) then.run();
            return;
        }
        Dialogs.show(ui, "放弃这些修改？", "恢复为上次保存的设置。",
                new Dialogs.Action("取消", Btn.TEXT, null),
                new Dialogs.Action("放弃修改", Btn.DANGER, () -> {
                    load(saved, overrides, true);
                    actions.discard();
                    if (then != null) then.run();
                }));
    }

    void reveal(boolean value) {
        if (revealing == value && !value) {
            actions.secure(false);
            return;
        }
        revealing = value;
        int selection = token.input.getSelectionEnd();
        token.input.setTransformationMethod(value ? null : PasswordTransformationMethod.getInstance());
        token.input.setSelection(Math.max(0, Math.min(selection, token.input.length())));
        Ui.setIcon(reveal, t, value ? Icon.HIDE : Icon.SHOW, t.onSurfaceVariant);
        reveal.setContentDescription(value ? "隐藏令牌" : "显示令牌");
        if (android.os.Build.VERSION.SDK_INT >= 26) reveal.setTooltipText(reveal.getContentDescription());
        actions.secure(value);
    }

    boolean revealing() {
        return revealing;
    }

    void notice(Status.Line line, boolean canRelaunch) {
        notice.show(line.tone, line.title, line.detail, canRelaunch && line.tone == Status.WARNING);
    }

    // ------------------------------------------------------------------ 实例状态

    void saveState(Bundle out) {
        out.putString("draft_port", port.text());
        out.putString("draft_token", token.text());
        boolean[] values = new boolean[toggles.length];
        for (int i = 0; i < values.length; i++) values[i] = toggles[i].isChecked();
        out.putBooleanArray("draft_toggles", values);
        out.putInt("settings_scroll", scroll.getScrollY());
    }

    void restoreState(Bundle state) {
        if (state == null || !state.containsKey("draft_port")) return;
        // 恢复出来的是用户的草稿：之后异步读到的设置只更新基准，不覆盖草稿。
        baseline = true;
        loading = true;
        port.setText(state.getString("draft_port", ""));
        token.setText(state.getString("draft_token", ""));
        boolean[] values = state.getBooleanArray("draft_toggles");
        if (values != null) {
            for (int i = 0; i < Math.min(values.length, toggles.length); i++) toggles[i].setChecked(values[i], false);
        }
        loading = false;
        changed();
        final int y = state.getInt("settings_scroll");
        scroll.post(() -> scroll.scrollTo(0, y));
    }
}
