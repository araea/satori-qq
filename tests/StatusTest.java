import com.satori.qq.guard.GuardCommand;
import com.satori.qq.ui.Status;
import org.json.JSONObject;

/**
 * 界面状态推导（{@code ui/Status}）：服务状态、连接链路、设置是否生效、守护说明与诊断列表。
 *
 * <p>钉住的底线：拿不到 {@code /healthz} 时绝不显示"就绪/已登录"；设置"已生效"只在运行中的
 * 修订号与本机一致时出现；诊断列表不带账号、令牌等隐私字段。
 */
public final class StatusTest {
    public static void main(String[] args) throws Exception {
        JSONObject ready = health(true, true, 2);
        JSONObject loggedOut = health(false, true, 0);
        JSONObject deaf = health(true, false, 0);

        // ---- 服务状态 ----
        eq(Status.CHECKING, Status.state(null, false), "未检查");
        eq(Status.CHECKING, Status.state(ready, false), "未检查时不看数据");
        eq(Status.UNREACHABLE, Status.state(null, true), "连不上");
        eq(Status.NOT_LISTENING, Status.state(deaf, true), "端口未就绪优先于登录");
        eq(Status.LOGGED_OUT, Status.state(loggedOut, true), "等待登录");
        eq(Status.READY, Status.state(ready, true), "就绪");

        eq(Status.ERROR, Status.hero(Status.UNREACHABLE, null).tone, "未连接是错误色");
        eq(Status.SUCCESS, Status.hero(Status.READY, ready).tone, "就绪是成功色");
        check(Status.hero(Status.READY, ready).detail.contains("2 个"), "就绪时说明客户端数量");
        check(Status.hero(Status.READY, health(true, true, 0)).detail.contains("等待"), "无客户端时说明在等待");
        check(!Status.offersOpenQQ(Status.READY) && Status.offersOpenQQ(Status.UNREACHABLE)
                && Status.offersOpenQQ(Status.LOGGED_OUT), "只有未就绪时引导打开 QQ");

        // ---- 连接链路：没有数据时不能出现 OK ----
        for (int step : Status.steps(Status.UNREACHABLE, null)) {
            check(step != Status.STEP_OK, "连不上时链路里不能有「已就绪」");
        }
        for (int step : Status.steps(Status.CHECKING, null)) eq(Status.STEP_UNKNOWN, step, "检查中一律未知");
        int[] steps = Status.steps(Status.READY, ready);
        eq(Status.STEP_OK, steps[0], "账号");
        eq(Status.STEP_OK, steps[1], "服务");
        eq(Status.STEP_OK, steps[2], "客户端");
        steps = Status.steps(Status.LOGGED_OUT, loggedOut);
        eq(Status.STEP_WAIT, steps[0], "未登录是等待");
        eq(Status.STEP_WAIT, steps[2], "无客户端是等待");
        eq(Status.STEP_FAIL, Status.steps(Status.NOT_LISTENING, deaf)[1], "端口未就绪是失败");
        check(Status.stepValues(Status.READY, ready, 3001)[1].contains("3001"), "链路写出端口");

        // ---- 设置是否生效 ----
        eq(Status.NEUTRAL, Status.config(null, 3, true).tone, "连不上时无法确认");
        JSONObject applied = health(true, true, 0).put("config_revision", 3).put("config_status", "applied");
        eq(Status.SUCCESS, Status.config(applied, 3, true).tone, "修订号一致即生效");
        check(Status.config(applied, 3, false).title.contains("文件"), "没有接管时说明在用文件配置");
        eq(Status.WARNING, Status.config(applied, 4, true).tone, "本机更新后等待重启");
        JSONObject blocked = health(true, true, 0).put("config_revision", -1)
                .put("config_status", "provider-unavailable:SecurityException");
        eq(Status.ERROR, Status.config(blocked, 4, true).tone, "关联启动被拦是错误");
        check(Status.config(blocked, 4, true).detail.contains("关联启动"), "给出系统设置路径");

        // ---- 守护说明 ----
        check(Status.guard(null).contains("读取"), "未读取");
        check(Status.guard(guard(false, null, "未找到 qqguard")).startsWith("不可用"), "不可用写出原因");
        check(Status.guard(guard(true, new JSONObject().put("mode", "ARMED").put("running", true), null))
                .startsWith("保活中"), "保活中");
        check(Status.guard(guard(true, new JSONObject().put("mode", "ARMED").put("running", false), null))
                .contains("未运行"), "看守进程没起来要说");
        check(Status.guard(guard(true, new JSONObject().put("mode", "PAUSED"), null)).startsWith("已暂停"), "已暂停");

        // ---- 诊断：值齐全、不泄露隐私、版本不一致时提示 ----
        JSONObject leaky = health(true, true, 1).put("version", "0.26.1").put("self_id", "PRIVATE_ID")
                .put("token", "SECRET").put("compat", new JSONObject().put("passed", 203).put("total", 204));
        String[][] rows = Status.diagnostics(leaky, "0.27.0", null);
        StringBuilder all = new StringBuilder();
        for (String[] row : rows) {
            eq(2, row.length, "每行一个标签一个值");
            check(!row[1].isEmpty(), "值不为空：" + row[0]);
            all.append(row[1]).append('\n');
        }
        check(!all.toString().contains("PRIVATE_ID") && !all.toString().contains("SECRET"), "诊断不含隐私字段");
        check(all.toString().contains("重启手机"), "运行版本与应用不同时提示");
        check(all.toString().contains("缺 1 项"), "接口缺失时写出数量");
        for (String[] row : Status.diagnostics(null, "0.27.0", null)) {
            check(!row[1].contains("已显示") && !row[1].contains("全部可用"), "连不上时不给出正面结论");
        }

        // ---- 时长 ----
        eq("不到 1 分钟", Status.duration(30_000), "秒级");
        eq("5 分钟", Status.duration(5 * 60_000), "分钟");
        eq("2 小时 5 分", Status.duration(125 * 60_000), "小时");
        eq("1 天 3 小时", Status.duration((27 * 60 + 10) * 60_000L), "天");

        System.out.println("StatusTest passed");
    }

    private static JSONObject health(boolean online, boolean listening, int clients) throws Exception {
        return new JSONObject().put("name", "satori-qq").put("version", "0.27.0").put("online", online)
                .put("listening", listening).put("connections", clients).put("qq_version", "9.3.65")
                .put("notice", "enabled/posted=yes");
    }

    private static GuardCommand.Result guard(boolean ok, JSONObject status, String error) throws Exception {
        java.lang.reflect.Constructor<GuardCommand.Result> constructor =
                GuardCommand.Result.class.getDeclaredConstructor(boolean.class, JSONObject.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(ok, status, error);
    }

    private static void eq(Object expect, Object got, String what) {
        if (!expect.equals(got)) throw new AssertionError(what + ": expect <" + expect + "> got <" + got + ">");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
