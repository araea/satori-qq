package com.satori.qq.guard;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Thin bridge from the management app to the root {@code qqguard} watchdog.
 *
 * <p>知弦模块把看守脚本装到 {@code /data/adb} 下，那是 root-only 的目录，应用进程读不到，
 * 所以这里不 stat 文件，而是让 {@code su} 里的 shell 自己去找可执行脚本（两条已知路径），找到
 * 就用 {@code exec} 换进去。命令与参数全部是写死的字面量，没有任何来自界面或网络的输入。
 *
 * <p>所有方法都会阻塞到 root 命令结束（带超时），调用方必须在工作线程上调。
 */
public final class GuardCommand {
    /** 与 build.sh / 98-qqguard.sh 部署路径一致。 */
    private static final String[] PATHS = {
            "/data/adb/satori-qq/qqguard.sh",
            "/data/adb/modules/satori_qq/qqguard.sh",
    };
    private static final long TIMEOUT_MS = 20_000L;
    private static final String MISSING = "QQGUARD_MISSING";

    private GuardCommand() {}

    public static final class Result {
        public final boolean ok;
        public final JSONObject status;
        public final String error;

        Result(boolean ok, JSONObject status, String error) {
            this.ok = ok;
            this.status = status;
            this.error = error;
        }

        public String mode() { return status == null ? "" : status.optString("mode", ""); }
        public boolean armed() { return "ARMED".equals(mode()); }
        public boolean running() { return status != null && status.optBoolean("running"); }
        public boolean qqAlive() { return status != null && status.optBoolean("qq_alive"); }
        public boolean online() { return status != null && status.optBoolean("online"); }
    }

    public static Result status() { return run("status --json"); }
    public static Result arm() { return run("start"); }
    public static Result pause() { return run("stop"); }
    public static Result restart() { return run("restart"); }
    public static Result toggle() { return run("toggle"); }

    /** 停止保活并关闭 QQ（PAUSED + force-stop）。 */
    public static Result stopAndKill() { return run("kill"); }

    /** {@code apply} 只重配系统项，不动 ARMED/PAUSED。 */
    public static Result apply() { return run("apply"); }

    /**
     * 重新启动 QQ，让刚保存的设置生效，并保持看守原来的模式。
     *
     * <p>不能直接 force-stop：看守把系统里的「强行停止」当成用户意愿，会自己转入 PAUSED，
     * 保活就被悄悄关掉了。所以先记下模式，保活中就先暂停、关 QQ、重新打开，再恢复保活。
     * 只需要 root；没装看守也能用（跳过暂停与恢复）。
     */
    public static Result relaunchQQ() {
        StringBuilder script = new StringBuilder("G=; for p in");
        for (String p : PATHS) script.append(" '").append(p).append('\'');
        script.append("; do [ -x \"$p\" ] && G=\"$p\" && break; done; ");
        script.append("A=; if [ -n \"$G\" ] && \"$G\" status --json | grep -q '\"mode\":\"ARMED\"'; then ");
        script.append("A=1; \"$G\" stop >/dev/null 2>&1; fi; ");
        script.append("am force-stop ").append(QQ).append("; sleep 1; ");
        script.append("monkey -p ").append(QQ).append(" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; ");
        script.append("[ -n \"$A\" ] && \"$G\" start >/dev/null 2>&1; exit 0");
        return exec(script.toString());
    }

    private static final String QQ = "com.tencent.mobileqq";

    private static Result run(String args) {
        StringBuilder script = new StringBuilder();
        script.append("for p in");
        for (String p : PATHS) script.append(" '").append(p).append('\'');
        script.append("; do [ -x \"$p\" ] && exec \"$p\" ").append(args).append("; done; ");
        script.append("echo ").append(MISSING).append(" >&2; exit 127");
        return exec(script.toString());
    }

    private static Result exec(String script) {
        Process process = null;
        try {
            process = new ProcessBuilder(su(), "-c", script).start();
            boolean done = process.waitFor(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            String out = read(process.getInputStream());
            String err = read(process.getErrorStream());
            if (!done) {
                process.destroyForcibly();
                return new Result(false, null, "qqguard 超时未响应");
            }
            if (err.contains(MISSING) || process.exitValue() == 127) {
                return new Result(false, null, "未找到 qqguard，请先刷入知弦模块并授予 Root");
            }
            if (process.exitValue() != 0) {
                return new Result(false, null, firstLine(err, "root 拒绝了请求（exit " + process.exitValue() + "）"));
            }
            JSONObject json = lastJson(out);
            if (json == null) {
                // start/stop/kill 这些命令的人读输出不是 JSON，成功即成功。
                return new Result(true, null, null);
            }
            return new Result(true, json, null);
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null || message.isEmpty()) message = e.getClass().getSimpleName();
            return new Result(false, null, "无法调用 root：" + message);
        } finally {
            if (process != null) process.destroy();
        }
    }

    /** Root 通常挂在 /system/bin/su；先试绝对路径，失败再退回 PATH 查找。 */
    private static String su() {
        if (new java.io.File("/system/bin/su").canExecute()) return "/system/bin/su";
        return "su";
    }

    private static String read(InputStream in) {
        if (in == null) return "";
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = stream.read(buffer)) > 0) {
                if (out.size() + n > 262144) break;
                out.write(buffer, 0, n);
            }
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** 最后一行完整 JSON（SU 有时会在前面多打一行 banner）。 */
    private static JSONObject lastJson(String text) {
        if (text == null) return null;
        JSONObject found = null;
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                try { found = new JSONObject(trimmed); } catch (Exception ignored) {}
            }
        }
        return found;
    }

    private static String firstLine(String text, String fallback) {
        if (text != null) {
            for (String line : text.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.contains(MISSING)) return trimmed;
            }
        }
        return fallback;
    }
}
