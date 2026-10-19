package com.satori.qq.update;

/**
 * 对照"线上最新"与"本机现状"，得出这次要做什么。纯函数，JVM 上直接测。
 *
 * <p>模块的"现状"优先取 root 探测到的磁盘版本号；没有 root 时退到服务自己报上来的版本名
 * （{@code running}，服务不在线则为空串，那就什么都不下结论）。
 */
public final class Plan {
    public static final Plan NONE = new Plan(false, false, false, false);

    /** 应用有新版本。 */
    public final boolean app;
    /** 模块有新版本，或者还没装。 */
    public final boolean module;
    /** 模块还没装（只有拿到 root 才能下这个结论）。 */
    public final boolean missing;
    /** 磁盘上的模块已经比正在运行的新：装好了，等重启。 */
    public final boolean reboot;

    private Plan(boolean app, boolean module, boolean missing, boolean reboot) {
        this.app = app;
        this.module = module;
        this.missing = missing;
        this.reboot = reboot;
    }

    public boolean any() {
        return app || module;
    }

    /**
     * @param release 线上最新，可空（还没检查过）
     * @param appCode 本应用的 versionCode
     * @param running 正在运行的模块版本名（服务 /healthz 或状态里报的），不在线为空串
     */
    public static Plan of(Release release, long appCode, ModuleState state, String running) {
        boolean live = running != null && !running.isEmpty();
        boolean reboot = state.known && state.present && !state.removing
                && (state.staged || (live && Versions.compare(state.effectiveVersion(), running) > 0));
        if (release == null) return new Plan(false, false, false, reboot);
        boolean app = release.app != null && release.app.code > appCode;
        boolean module = false;
        boolean missing = false;
        if (release.module != null) {
            if (state.known) {
                missing = !state.present || state.removing;
                module = missing || release.module.code > state.effectiveCode();
            } else if (live) {
                module = Versions.compare(release.module.version, running) > 0;
            }
        }
        return new Plan(app, module, missing, reboot);
    }
}
