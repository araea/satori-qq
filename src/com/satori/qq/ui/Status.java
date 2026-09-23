package com.satori.qq.ui;

import com.satori.qq.guard.GuardCommand;
import org.json.JSONObject;

/**
 * 界面状态的推导：把 {@code /healthz}、看守状态与本机设置翻译成页面上的结论与文案。
 *
 * <p>纯函数，只依赖 org.json，不碰任何 Android 视图，所以能在 JVM 上直接测
 * （{@code tests/StatusTest}）。规则只有一条底线：<b>拿不到数据时只说"未知"或"未连接"，
 * 绝不沿用上一次的在线结论。</b>
 */
public final class Status {
    private Status() {}

    // ---- 语调：决定容器色与图标，文字本身必须已经说清状态 ----
    public static final int NEUTRAL = 0;
    public static final int SUCCESS = 1;
    public static final int WARNING = 2;
    public static final int ERROR = 3;

    // ---- 服务状态 ----
    public static final int CHECKING = 0;
    public static final int UNREACHABLE = 1;
    public static final int NOT_LISTENING = 2;
    public static final int LOGGED_OUT = 3;
    public static final int READY = 4;

    // ---- 链路每一环 ----
    public static final int STEP_OK = 0;
    public static final int STEP_WAIT = 1;
    public static final int STEP_FAIL = 2;
    public static final int STEP_UNKNOWN = 3;

    /** 一句结论：语调 + 标题 + 说明。 */
    public static final class Line {
        public final int tone;
        public final String title;
        public final String detail;

        Line(int tone, String title, String detail) {
            this.tone = tone;
            this.title = title;
            this.detail = detail;
        }
    }

    public static int state(JSONObject health, boolean checked) {
        if (!checked) return CHECKING;
        if (health == null) return UNREACHABLE;
        if (!health.optBoolean("listening")) return NOT_LISTENING;
        if (!health.optBoolean("online")) return LOGGED_OUT;
        return READY;
    }

    public static Line hero(int state, JSONObject health) {
        switch (state) {
            case CHECKING:
                return new Line(NEUTRAL, "正在检查", "正在连接 QQ 里的知弦服务…");
            case UNREACHABLE:
                return new Line(ERROR, "未连接", "没有连上 QQ 里的知弦服务。按下面的步骤检查，然后打开 QQ。");
            case NOT_LISTENING:
                return new Line(WARNING, "服务待恢复", "模块已经加载，但本机端口还没有就绪。重新启动 QQ 通常可以恢复。");
            case LOGGED_OUT:
                return new Line(WARNING, "等待登录", "本机服务已就绪。在 QQ 里登录账号后，客户端就能收发消息。");
            default:
                int clients = health == null ? 0 : health.optInt("connections");
                return new Line(SUCCESS, "连接就绪", clients == 0
                        ? "QQ 与本机服务都已就绪，正在等待 Satori 客户端接入。"
                        : clients + " 个 Satori 客户端已接入。");
        }
    }

    /** 主状态卡片上的动作：未就绪时引导去打开 QQ，就绪时不放按钮。 */
    public static boolean offersOpenQQ(int state) {
        return state == UNREACHABLE || state == NOT_LISTENING || state == LOGGED_OUT;
    }

    /** 连接链路：QQ 账号 → 本机服务 → Satori 客户端。每环一个状态与一句值。 */
    public static int[] steps(int state, JSONObject health) {
        if (state == CHECKING || health == null) {
            return new int[]{STEP_UNKNOWN, state == CHECKING ? STEP_UNKNOWN : STEP_FAIL, STEP_UNKNOWN};
        }
        boolean listening = health.optBoolean("listening");
        int account = health.optBoolean("online") ? STEP_OK : STEP_WAIT;
        int service = listening ? STEP_OK : STEP_FAIL;
        int clients = health.optInt("connections") > 0 ? STEP_OK : (listening ? STEP_WAIT : STEP_UNKNOWN);
        return new int[]{account, service, clients};
    }

    public static String[] stepValues(int state, JSONObject health, int port) {
        if (state == CHECKING) return new String[]{"检查中", "检查中", "检查中"};
        if (health == null) return new String[]{"未知", "未连接", "未知"};
        int clients = health.optInt("connections");
        return new String[]{
                health.optBoolean("online") ? "已登录" : "未登录",
                health.optBoolean("listening") ? "端口 " + port + " 已就绪" : "端口 " + port + " 未就绪",
                clients > 0 ? clients + " 个已接入" : (health.optBoolean("listening") ? "等待接入" : "未知"),
        };
    }

    /**
     * 设置是否已经生效。{@code saved} 是本机最近一次保存的修订号，{@code overrides} 表示当前由知弦
     * 管理页接管设置（否则 QQ 读原 JSON 文件或默认值）。
     */
    public static Line config(JSONObject health, long saved, boolean overrides) {
        if (health == null) {
            return new Line(NEUTRAL, "暂时无法确认是否生效", "连上 QQ 里的知弦服务后，这里会显示设置是否已经生效。");
        }
        if (health.optString("config_status").startsWith("provider-unavailable")) {
            return new Line(ERROR, "QQ 没能读取这些设置",
                    "系统拦下了 QQ 对知弦的访问。在系统设置的「应用 → 关联启动」里允许知弦，然后重新启动 QQ。");
        }
        long applied = health.optLong("config_revision", -1);
        if (applied == saved) {
            return overrides
                    ? new Line(SUCCESS, "设置已生效", "QQ 正在使用最近一次保存的设置。")
                    : new Line(SUCCESS, "正在使用文件配置", "QQ 读取的是原 JSON 配置文件；没有文件时使用默认值。");
        }
        if (saved > applied) {
            return new Line(WARNING, "已保存，重启 QQ 后生效", "QQ 仍在使用旧设置。重新启动 QQ 后生效，客户端会短暂断开。");
        }
        return new Line(NEUTRAL, "尚未确认运行配置", "重新启动 QQ 后会同步。");
    }

    /** 常驻守护开关下方的一句说明。 */
    public static String guard(GuardCommand.Result result) {
        if (result == null) return "正在读取守护状态…";
        if (!result.ok) return "不可用：" + result.error;
        JSONObject status = result.status;
        if (!result.armed()) return "已暂停，QQ 退出后不会被自动拉起";
        StringBuilder line = new StringBuilder("保活中");
        if (!result.running()) line.append("，但看守进程未运行");
        if (status != null && status.optBoolean("qq_frozen")) line.append("；QQ 当前被系统冻结");
        return line.toString();
    }

    /** 诊断的定义列表：标签与值一一对应，只含状态与计数。 */
    public static String[][] diagnostics(JSONObject health, String appVersion, GuardCommand.Result guard) {
        String running, qq, compat, notice, failures, sessionErrors;
        if (health == null) {
            running = "未连接";
            qq = compat = notice = failures = sessionErrors = "—";
        } else {
            String version = health.optString("version", "未知");
            running = version.equals(appVersion) ? version : version + "（与应用不同：刷入新模块并重启手机）";
            qq = health.optString("qq_version", "未知");
            JSONObject c = health.optJSONObject("compat");
            if (c == null) {
                compat = "待检查";
            } else {
                int passed = c.optInt("passed");
                int total = c.optInt("total");
                compat = passed + " / " + total + (passed >= total ? "，全部可用" : "，缺 " + (total - passed) + " 项");
            }
            notice = health.optString("notice").contains("posted=yes") ? "已显示" : "未显示";
            JSONObject sso = health.optJSONObject("sso");
            failures = (sso == null ? 0 : sso.optInt("failures")) + " 次";
            sessionErrors = (sso == null ? 0 : sso.optInt("session_errors")) + " 次";
        }
        String keeper;
        if (guard == null) keeper = "读取中";
        else if (!guard.ok) keeper = "不可用";
        else keeper = (guard.armed() ? "保活中" : "已暂停") + " · 1 小时内重启 "
                + (guard.status == null ? 0 : guard.status.optInt("restarts_1h")) + " 次";
        return new String[][]{
                {"知弦版本", appVersion == null || appVersion.isEmpty() ? "未知" : appVersion},
                {"运行中的模块", running},
                {"QQ 版本", qq},
                {"内核接口", compat},
                {"状态通知", notice},
                {"请求失败", failures},
                {"会话错误", sessionErrors},
                {"常驻守护", keeper},
        };
    }

    public static String duration(long millis) {
        long minutes = Math.max(0, millis / 60000);
        if (minutes < 1) return "不到 1 分钟";
        if (minutes < 60) return minutes + " 分钟";
        if (minutes < 1440) return (minutes / 60) + " 小时 " + (minutes % 60) + " 分";
        return (minutes / 1440) + " 天 " + (minutes % 1440 / 60) + " 小时";
    }
}
