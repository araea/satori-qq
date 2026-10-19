package com.satori.qq.ui;

import com.satori.qq.update.Plan;
import com.satori.qq.update.Release;
import com.satori.qq.update.Updater;

/**
 * 更新相关的文案与按钮：把 {@link Updater.State} 翻译成卡片上的一句话和至多两个动作。
 * 纯函数，不碰视图，JVM 上直接测（{@code tests/UpdateFacesTest}）。
 *
 * <p>首页有两张互相独立的卡：顶部的"更新"卡（发现新版本 / 正在更新 / 没成功 / 等你手动安装），
 * 和"重启"卡（模块已装好，等重启生效）。两件事可以同时成立，所以不合并成一张。
 */
public final class UpdateFaces {
    private UpdateFaces() {}

    // ---- 语调：在 Status 的四档之外多一档强调色（有新版本不是警告，也不是成功） ----
    public static final int ACCENT = 4;

    // ---- 按钮动作 ----
    public static final int NONE = 0;
    public static final int UPDATE = 1;
    public static final int NOTES = 2;
    public static final int OPEN_MODULE = 3;
    public static final int OPEN_APP = 4;
    public static final int REBOOT = 5;

    /** 一张卡的内容。 */
    public static final class Face {
        public final int tone;
        public final boolean busy;
        public final int icon;
        public final String title;
        public final String body;
        public final String primary;
        public final int primaryAction;
        public final String secondary;
        public final int secondaryAction;

        Face(int tone, boolean busy, int icon, String title, String body, String primary, int primaryAction,
             String secondary, int secondaryAction) {
            this.tone = tone;
            this.busy = busy;
            this.icon = icon;
            this.title = title;
            this.body = body;
            this.primary = primary;
            this.primaryAction = primaryAction;
            this.secondary = secondary;
            this.secondaryAction = secondaryAction;
        }
    }

    /** 顶部的更新卡；没有要说的返回 null。 */
    public static Face update(Updater.State s, String label, String appVersion) {
        if (s.working()) {
            return new Face(ACCENT, true, Icon.DOWNLOAD, "正在更新" + (s.release == null ? "" : "到 " + s.release.headline()),
                    s.step + "\n应用会在安装完成后自动重新打开。", null, NONE, null, NONE);
        }
        if (!s.exports.isEmpty()) return manual(s, label);
        if (!s.failure.isEmpty()) {
            return new Face(Status.ERROR, false, Icon.ERROR, "更新没有完成", s.failure,
                    "重试", UPDATE, s.release != null && !s.release.notes.isEmpty() ? "更新内容" : null, NOTES);
        }
        Plan plan = s.plan;
        if (!plan.any() || s.release == null) return null;
        Release r = s.release;
        String title = plan.missing && !plan.app ? "还没有安装" + label + "模块" : "发现新版本 " + r.headline();
        return new Face(ACCENT, false, Icon.DOWNLOAD, title, available(plan, r, appVersion, s),
                plan.missing && !plan.app ? "安装模块" : "更新", UPDATE,
                r.notes.isEmpty() ? null : "更新内容", NOTES);
    }

    private static String available(Plan plan, Release r, String appVersion, Updater.State s) {
        StringBuilder text = new StringBuilder();
        if (plan.app && plan.module) {
            text.append("应用 ").append(appVersion).append(" → ").append(r.app.version)
                    .append("，模块 → ").append(r.module.version);
        } else if (plan.app) {
            text.append("应用 ").append(appVersion).append(" → ").append(r.app.version);
        } else if (plan.missing) {
            text.append("一键安装模块 ").append(r.module.version).append("，装好后重启手机生效。");
        } else {
            String now = s.module.known ? s.module.effectiveVersion() : "";
            text.append("模块 ").append(now.isEmpty() ? "" : now + " → ").append(r.module.version);
        }
        if (plan.module && !plan.missing) text.append("。模块更新后需要重启手机才会生效。");
        else if (!plan.missing) text.append("。");
        return text.toString();
    }

    private static Face manual(Updater.State s, String label) {
        Updater.Export first = s.exports.get(0);
        Updater.Export second = s.exports.size() > 1 ? s.exports.get(1) : null;
        StringBuilder body = new StringBuilder();
        if (!s.failure.isEmpty()) body.append(s.failure).append("\n");
        body.append("更新包已放进「下载」文件夹：");
        for (int i = 0; i < s.exports.size(); i++) body.append(i == 0 ? "" : "、").append(s.exports.get(i).name);
        body.append("。");
        if (s.exports.stream().anyMatch(Updater.Export::module)) {
            body.append("在 root 管理器的模块页选择它安装（也可以点下面的按钮直接打开），装完重启手机。");
        }
        int firstAction = first.module() ? OPEN_MODULE : OPEN_APP;
        String firstLabel = first.module() ? "用管理器安装模块" : "安装应用";
        if (second != null) {
            return new Face(Status.WARNING, false, Icon.FILE, "等你完成安装", body.toString(),
                    firstLabel, firstAction, second.module() ? "安装模块" : "安装应用",
                    second.module() ? OPEN_MODULE : OPEN_APP);
        }
        return new Face(Status.WARNING, false, Icon.FILE, "等你完成安装", body.toString(),
                firstLabel, firstAction, "重试", UPDATE);
    }

    /** 重启卡：模块已装好（或磁盘上的比运行中的新），等重启生效。 */
    public static Face reboot(Updater.State s, String label, String running) {
        if (!s.plan.reboot || s.working()) return null;
        String version = s.module.known ? s.module.effectiveVersion() : "";
        String body = label + "模块" + (version.isEmpty() ? "" : " " + version) + " 已经装好，重启手机后才会生效"
                + (running == null || running.isEmpty() ? "。" : "；现在 QQ 里运行的仍是 " + running + "。");
        return new Face(ACCENT, false, Icon.RESTART, "重启手机后生效", body, "立即重启", REBOOT, null, NONE);
    }

    /** 「检查更新」那一行的说明。 */
    public static String row(Updater.State s, String appVersion, long now) {
        String head = appVersion == null || appVersion.isEmpty() ? "" : "当前 " + appVersion + " · ";
        if (s.working()) return head + "正在更新…";
        if (s.checking) return head + "正在检查…";
        if (!s.checkError.isEmpty()) return head + "检查失败：" + s.checkError;
        if (s.release == null) return head + "还没有检查过";
        if (s.plan.any()) return head + "有新版本 " + s.release.headline();
        return head + "已是最新 · " + ago(now - s.checkedAt);
    }

    static String ago(long millis) {
        long minutes = Math.max(0, millis / 60000);
        if (minutes < 1) return "刚刚检查过";
        if (minutes < 60) return minutes + " 分钟前检查";
        if (minutes < 1440) return (minutes / 60) + " 小时前检查";
        return (minutes / 1440) + " 天前检查";
    }
}
