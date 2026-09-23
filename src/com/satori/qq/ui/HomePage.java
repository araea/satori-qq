package com.satori.qq.ui;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.satori.qq.R;
import com.satori.qq.guard.GuardCommand;
import org.json.JSONObject;

/**
 * 首页：服务是否就绪 → 断在哪一环 → 下一步做什么，然后是连接地址、常驻守护与诊断。
 *
 * <p>只负责视图与把 {@link Model} 画上去；数据怎么来、按钮做什么由 {@link MainActivity} 决定
 * （{@link Actions}）。每次渲染只改真的变了的文字，轮询不会引起整页重排或重复朗读。
 */
final class HomePage {
    interface Actions {
        void refresh();
        void openSettings();
        void openQQ();
        void copyEndpoint();
        void guard(boolean arm);
        void stopQQ();
        void relaunchQQ();
        void copyReport();
        void shareReport();
    }

    /** 首页渲染需要的全部输入。 */
    static final class Model {
        JSONObject health;
        boolean checked;
        boolean probing;
        int port = 3001;
        boolean tokenSet;
        GuardCommand.Result guard;
        String guardBusy;
        String appVersion = "";
    }

    final LinearLayout root;
    final ScrollView scroll;
    final TopBar bar;
    private final Ui ui;
    private final Tokens t;
    private final Actions actions;
    private final Shape heroShape;
    private final LinearLayout hero;
    private final ImageView heroIcon;
    private final TextView heroLabel, heroTitle, heroDetail;
    private final LinearLayout heroSteps;
    private final Btn heroAction;
    private final LoadingIndicator loading;
    private final TextView clients, uptime;
    private final Item[] chain = new Item[3];
    private final Item endpoint, token;
    private final Item guardSwitch, relaunch, kill;
    private final Ui.Group guardGroup;
    private final TextView[] data;
    private final ImageButton refresh;
    private int heroTone = -1;
    private int[] stepStates = new int[0];

    HomePage(Ui ui, Actions actions, boolean showSettingsEntry) {
        this.ui = ui;
        this.t = ui.t;
        this.actions = actions;

        root = ui.column();
        root.setId(R.id.home);
        bar = new TopBar(ui, "知弦", t.surface, t.surfaceContainer);
        refresh = ui.iconButton(Icon.REFRESH, "刷新状态", t.onSurfaceVariant);
        refresh.setId(R.id.refresh);
        refresh.setOnClickListener(v -> actions.refresh());
        bar.action(refresh);
        if (showSettingsEntry) {
            ImageButton settings = ui.iconButton(Icon.SETTINGS, "连接设置", t.onSurfaceVariant);
            settings.setId(R.id.open_settings);
            settings.setOnClickListener(v -> actions.openSettings());
            bar.action(settings);
        }
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        scroll = Pages.scroll(t);
        LinearLayout content = Pages.content(ui);
        scroll.addView(Pages.center(ui, content));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        // ---- 大标题 ----
        LinearLayout brand = ui.row();
        ImageView logo = new ImageView(t.context);
        logo.setImageDrawable(t.context.getApplicationInfo().loadIcon(t.context.getPackageManager()));
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        brand.addView(logo, new LinearLayout.LayoutParams(t.logo, t.logo));
        TextView title = ui.heading("知弦", Tokens.DISPLAY_SMALL, t.onSurface);
        brand.addView(title, Ui.share(t.spaceMd));
        content.addView(brand, Ui.stack(t.spaceSm));
        content.addView(ui.text("QQ 的 Satori 服务", Tokens.BODY_LARGE, t.onSurfaceVariant), Ui.stack(t.spaceXs));
        bar.follow(scroll, brand);

        // ---- 状态卡片 ----
        hero = ui.column();
        hero.setId(R.id.hero);
        hero.setPadding(t.spaceXl, t.spaceXl, t.spaceXl, t.spaceXl);
        heroShape = Shape.smooth(t.surfaceContainerHigh, t.shapeXlIncreased);
        hero.setBackground(heroShape);
        LinearLayout labelRow = ui.row();
        heroIcon = new ImageView(t.context);
        heroIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        labelRow.addView(heroIcon, new LinearLayout.LayoutParams(t.icon, t.icon));
        heroLabel = ui.text("本机服务", Tokens.LABEL_LARGE, t.onSurfaceVariant);
        labelRow.addView(heroLabel, Ui.share(t.spaceSm));
        loading = new LoadingIndicator(t, t.primary);
        loading.setId(R.id.loading);
        FrameLayout loadingBox = new FrameLayout(t.context);
        loadingBox.addView(loading, new FrameLayout.LayoutParams(t.loading, t.loading, Gravity.CENTER));
        labelRow.addView(loadingBox, new LinearLayout.LayoutParams(t.loading, t.dp(24)));
        hero.addView(labelRow, Ui.stack(0));
        heroTitle = Ui.live(ui.heading("正在检查", Tokens.HEADLINE_LARGE, t.onSurface));
        heroTitle.setId(R.id.hero_title);
        hero.addView(heroTitle, Ui.stack(t.spaceLg));
        heroDetail = Ui.live(ui.text("", Tokens.BODY_LARGE, t.onSurfaceVariant));
        heroDetail.setId(R.id.hero_detail);
        hero.addView(heroDetail, Ui.stack(t.spaceSm));
        heroSteps = ui.column();
        String[] steps = {
                "刷入知弦模块（SatoriQQ-module.zip）后重启过手机。",
                "打开 QQ 并登录账号。",
                "在 Satori 客户端填写下方的连接地址，令牌与设置里保持一致。",
        };
        for (int i = 0; i < steps.length; i++) {
            LinearLayout step = ui.row();
            step.setGravity(Gravity.TOP);
            TextView index = ui.text(String.valueOf(i + 1), Tokens.LABEL_LARGE, t.onErrorContainer);
            index.setGravity(Gravity.CENTER);
            index.setBackground(Shape.round(Tokens.alpha(t.onErrorContainer, 12), Shape.FULL));
            index.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            step.addView(index, new LinearLayout.LayoutParams(t.icon, t.icon));
            TextView line = ui.text(steps[i], Tokens.BODY_MEDIUM, t.onErrorContainer);
            line.setContentDescription("第 " + (i + 1) + " 步：" + steps[i]);
            step.addView(line, Ui.share(t.spaceMd));
            heroSteps.addView(step, Ui.stack(i == 0 ? 0 : t.spaceSm));
        }
        heroSteps.setVisibility(View.GONE);
        hero.addView(heroSteps, Ui.stack(t.spaceLg));
        heroAction = ui.prominent("打开 QQ", Btn.FILLED).icon(Icon.OPEN);
        heroAction.setId(R.id.hero_action);
        heroAction.setOnClickListener(v -> actions.openQQ());
        heroAction.setVisibility(View.GONE);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-2, -2);
        actionParams.topMargin = t.spaceXl;
        hero.addView(heroAction, actionParams);
        content.addView(hero, Ui.stack(t.spaceXl));

        // ---- 两个指标 ----
        boolean pair = ui.layout.pair();
        LinearLayout metrics = pair ? ui.row() : ui.column();
        TextView[] values = new TextView[2];
        String[] labels = {"客户端", "连续在线"};
        for (int i = 0; i < 2; i++) {
            LinearLayout tile = ui.column();
            tile.setPadding(t.spaceLg + t.spaceXs, t.spaceLg, t.spaceLg + t.spaceXs, t.spaceLg);
            float big = t.shapeXl, small = t.shapeXs;
            Shape shape = Shape.smooth(t.surfaceContainer, 0);
            if (pair) shape.setRadii(i == 0 ? big : small, i == 0 ? small : big, i == 0 ? small : big, i == 0 ? big : small);
            else shape.setRadii(i == 0 ? big : small, i == 0 ? big : small, i == 0 ? small : big, i == 0 ? small : big);
            tile.setBackground(shape);
            tile.addView(ui.text(labels[i], Tokens.LABEL_MEDIUM, t.onSurfaceVariant), Ui.stack(0));
            values[i] = ui.text("—", Tokens.HEADLINE_SMALL, t.onSurface);
            tile.addView(values[i], Ui.stack(t.spaceXs));
            if (android.os.Build.VERSION.SDK_INT >= 28) tile.setScreenReaderFocusable(true);
            if (pair) metrics.addView(tile, Ui.share(i == 0 ? 0 : t.space2xs));
            else metrics.addView(tile, Ui.stack(i == 0 ? 0 : t.space2xs));
        }
        if (pair) {
            // 两块等高：并排时按较高的那块拉齐
            metrics.setGravity(Gravity.FILL_VERTICAL);
            for (int i = 0; i < 2; i++) ((LinearLayout.LayoutParams) metrics.getChildAt(i).getLayoutParams()).height = -1;
        }
        clients = values[0];
        uptime = values[1];
        content.addView(metrics, Ui.stack(t.spaceMd));

        // ---- 连接链路 ----
        content.addView(ui.sectionTitle("连接链路"), Ui.stack(t.space2xl));
        Ui.Group chainGroup = ui.group();
        String[] names = {"QQ 账号", "本机服务", "Satori 客户端"};
        for (int i = 0; i < 3; i++) {
            chain[i] = chainGroup.add(new Item(t, Item.STATIC, names[i], "检查中").leading(Icon.UNKNOWN, t.onSurfaceVariant));
            if (android.os.Build.VERSION.SDK_INT >= 28) chain[i].setScreenReaderFocusable(true);
        }
        content.addView(chainGroup, Ui.stack(0));

        // ---- 连接地址 ----
        content.addView(ui.sectionTitle("连接"), Ui.stack(t.space2xl));
        Ui.Group connect = ui.group();
        ImageButton copy = ui.iconButton(Icon.COPY, "复制连接地址", t.onSurfaceVariant);
        copy.setOnClickListener(v -> actions.copyEndpoint());
        endpoint = connect.add(new Item(t, Item.STATIC, "http://127.0.0.1:3001", "Satori 地址 · 只供本机客户端使用")
                .trailing(copy));
        endpoint.setId(R.id.endpoint);
        token = connect.add(new Item(t, Item.ACTION, "令牌", "未设置，客户端无需令牌").leading(Icon.KEY, t.onSurfaceVariant));
        if (showSettingsEntry) token.chevron();
        token.setOnClickListener(v -> actions.openSettings());
        content.addView(connect, Ui.stack(0));

        // ---- 常驻守护 ----
        content.addView(ui.sectionTitle("常驻守护"), Ui.stack(t.space2xl));
        guardGroup = ui.group();
        guardSwitch = guardGroup.add(new Item(t, Item.SWITCH, "保持 QQ 在线", "正在读取守护状态…"));
        guardSwitch.setId(R.id.guard_switch);
        guardSwitch.setOnToggle((item, checked) -> actions.guard(checked));
        relaunch = guardGroup.add(new Item(t, Item.ACTION, "重新启动 QQ", "应用新设置；客户端会短暂断开")
                .leading(Icon.RESTART, t.onSurfaceVariant));
        relaunch.setId(R.id.guard_relaunch);
        relaunch.setOnClickListener(v -> actions.relaunchQQ());
        kill = guardGroup.add(new Item(t, Item.ACTION, "停止保活并关闭 QQ", "之后不会自动拉起，直到重新开启守护")
                .leading(Icon.POWER, t.error).destructive());
        kill.setId(R.id.guard_kill);
        kill.setOnClickListener(v -> actions.stopQQ());
        content.addView(guardGroup, Ui.stack(0));
        content.addView(ui.text("需要 Root 与知弦模块。也可以在快捷设置里添加「知弦守护」磁贴。",
                Tokens.BODY_SMALL, t.onSurfaceVariant), Pages.note(t));

        // ---- 诊断 ----
        content.addView(ui.sectionTitle("诊断"), Ui.stack(t.space2xl));
        LinearLayout panel = ui.column();
        panel.setId(R.id.diagnostics);
        panel.setPadding(t.spaceLg, t.spaceLg + t.spaceXs, t.spaceLg, t.spaceLg + t.spaceXs);
        panel.setBackground(Shape.smooth(t.surfaceContainer, t.shapeXl));
        String[][] rows = Status.diagnostics(null, "", null);
        data = new TextView[rows.length];
        for (int i = 0; i < rows.length; i++) data[i] = ui.dataRow(panel, rows[i][0], i == 0);
        content.addView(panel, Ui.stack(0));
        Btn copyReport = ui.button("复制报告", Btn.TONAL).icon(Icon.COPY);
        copyReport.setOnClickListener(v -> actions.copyReport());
        Btn share = ui.button("分享报告", Btn.TONAL).icon(Icon.SHARE);
        share.setOnClickListener(v -> actions.shareReport());
        content.addView(ui.connected(copyReport, share), Ui.stack(t.spaceMd));
        content.addView(ui.text("报告只含版本、状态与错误计数，不含令牌、QQ 账号或消息内容。",
                Tokens.BODY_SMALL, t.onSurfaceVariant), Pages.note(t));

        TextView footer = ui.text("关闭知弦不影响服务运行：服务随 QQ 一起工作。", Tokens.BODY_SMALL, t.onSurfaceVariant);
        footer.setGravity(Gravity.CENTER);
        content.addView(footer, Ui.stack(t.space2xl));
    }

    void render(Model m) {
        int state = Status.state(m.health, m.checked);
        Status.Line line = Status.hero(state, m.health);
        loading.setVisibility(m.probing && !m.checked ? View.VISIBLE : View.GONE);
        refresh.setEnabled(!m.probing);
        refresh.setAlpha(m.probing ? t.disabledContentPct / 100f : 1f);

        if (heroTone != line.tone) {
            heroTone = line.tone;
            int fill, ink, kind;
            switch (line.tone) {
                case Status.SUCCESS: fill = t.successContainer; ink = t.onSuccessContainer; kind = Icon.OK; break;
                case Status.WARNING: fill = t.warningContainer; ink = t.onWarningContainer; kind = Icon.PENDING; break;
                case Status.ERROR: fill = t.errorContainer; ink = t.onErrorContainer; kind = Icon.ERROR; break;
                default: fill = t.surfaceContainerHigh; ink = t.onSurface; kind = Icon.INFO; break;
            }
            heroShape.fill(fill);
            heroIcon.setImageDrawable(new Icon(t, kind, ink));
            heroLabel.setTextColor(ink);
            heroTitle.setTextColor(ink);
            heroDetail.setTextColor(line.tone == Status.NEUTRAL ? t.onSurfaceVariant : ink);
        }
        Ui.set(heroTitle, line.title);
        Ui.set(heroDetail, line.detail);
        heroSteps.setVisibility(state == Status.UNREACHABLE ? View.VISIBLE : View.GONE);
        heroAction.setVisibility(Status.offersOpenQQ(state) ? View.VISIBLE : View.GONE);

        boolean online = state == Status.READY;
        Ui.set(clients, m.health == null ? "—" : String.valueOf(m.health.optInt("connections")));
        long since = m.health == null ? 0 : m.health.optLong("online_since_epoch_ms");
        Ui.set(uptime, !online || since <= 0 ? "—" : Status.duration(System.currentTimeMillis() - since));

        int[] steps = Status.steps(state, m.health);
        String[] values = Status.stepValues(state, m.health, m.port);
        boolean restyle = !java.util.Arrays.equals(steps, stepStates);
        stepStates = steps;
        for (int i = 0; i < 3; i++) {
            if (restyle) {
                int kind, color;
                switch (steps[i]) {
                    case Status.STEP_OK: kind = Icon.OK; color = t.success; break;
                    case Status.STEP_WAIT: kind = Icon.PENDING; color = t.warning; break;
                    case Status.STEP_FAIL: kind = Icon.ERROR; color = t.error; break;
                    default: kind = Icon.UNKNOWN; color = t.onSurfaceVariant; break;
                }
                chain[i].leading(kind, color);
            }
            chain[i].setSupporting(values[i]);
        }

        endpoint.setHeadline("http://127.0.0.1:" + m.port);
        token.setSupporting(m.tokenSet ? "已设置，客户端须填写相同令牌" : "未设置，客户端无需令牌");

        GuardCommand.Result g = m.guard;
        boolean available = g != null && g.ok;
        if (m.guardBusy != null) {
            guardSwitch.setSupporting(m.guardBusy);
        } else {
            guardSwitch.setSupporting(Status.guard(g));
            if (available) guardSwitch.setChecked(g.armed(), true);
        }
        boolean idle = m.guardBusy == null;
        guardSwitch.setEnabled(available && idle);
        relaunch.setEnabled(available && idle);
        kill.setEnabled(available && idle);

        String[][] rows = Status.diagnostics(m.health, m.appVersion, g);
        for (int i = 0; i < rows.length; i++) Ui.set(data[i], rows[i][1]);
    }
}
