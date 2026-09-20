package com.satori.qq.qq;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import com.satori.qq.L;

import java.io.RandomAccessFile;

/**
 * 不用 hook 也能做的「前台保护」等价物：让 QQ 自己的服务保持「已启动」。
 *
 * <p>为什么是这件事（2026-09-19 在本机实测）：ColorOS / Android 16 的 app freezer 阈值是
 * {@code freezer_cutoff_adj=900}，只有 adj ≥ 900 的缓存进程会被冻。QQ 主进程在**有已启动服务**时
 * 是 adj≈700（SERVICE_ADJ），冻不着；服务全停之后它掉到 CACHED（900+）就会被冻，表现是
 * 「TCP 连得上、一个字节不回」（`/proc/<pid>/wchan` = `do_freezer_trap`）。
 *
 * <p>0.22.x 及更早是用 hook 把 QQ 的 {@code QQDataSyncService} 提成前台服务；0.23.0 去掉 hook
 * 引擎后那条路没了，这里补的是**不 hook 也能做的那一半**：从进程内把它自己的服务重新 start
 * 一遍（同包同进程），并定期复查。真正的兜底在 root 侧 {@code scripts/qqguard.sh}（见
 * {@code docs/GUARD.md}）——万一还是被冻，它直接写进程自己的 freezer cgroup 解冻，不拉起、
 * 不重启、也不打断用户。
 *
 * <p>Android 12+ 会限制后台应用 startService，被挡时抛 {@code IllegalStateException}；这里不往外
 * 抛，结果记进 {@link #diag()}，healthz 的 {@code keepalive} 字段一眼能看到到底起没起。
 */
public final class Keepalive {

    /** 复查间隔。服务被系统回收不是瞬时的，10 分钟一次足够，也不至于反复动 QQ。 */
    private static final long INTERVAL_MS = 10 * 60 * 1000L;

    /**
     * 按顺序尝试的 QQ 自己的服务（都在主进程里，QQ 自己也会起）。
     * {@code CoreService} 是主进程的常驻核心服务，起它最贴合原意；后两个是兜底。
     */
    private static final String[] SERVICES = {
            "com.tencent.mobileqq.app.CoreService",
            "com.tencent.mobileqq.winkpublish.service.WinkPublishService",
    };

    private static volatile Context ctx;
    private static volatile String last = "not-attempted";
    private static volatile long lastAt;

    private Keepalive() {}

    public static void install(Context context) {
        if (context == null || ctx != null) return;
        ctx = context.getApplicationContext() == null ? context : context.getApplicationContext();
        Thread t = new Thread(() -> {
            for (;;) {
                try { kick(); } catch (Throwable e) { L.e("keepalive kick", e); }
                try { Thread.sleep(INTERVAL_MS); } catch (InterruptedException e) { return; }
            }
        }, "pool-8-thread-1");
        t.setDaemon(true);
        t.start();
    }

    /** 立刻踢一次（会话就绪、上线这些时点用）。 */
    public static void kickNow() {
        Thread t = new Thread(Keepalive::kick, "pool-8-thread-2");
        t.setDaemon(true);
        t.start();
    }

    private static void kick() {
        Context c = ctx;
        if (c == null) { last = "no-context"; return; }
        for (String cls : SERVICES) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName("com.tencent.mobileqq", cls));
                ComponentName got = c.startService(i);
                last = "ok " + cls.substring(cls.lastIndexOf('.') + 1)
                        + (got == null ? "(null)" : "");
                lastAt = System.currentTimeMillis();
                L.i("keepalive startService " + cls + " -> " + got);
                return;
            } catch (Throwable e) {
                // 后台启动服务被挡（BackgroundServiceStartNotAllowed）等：记下来，继续试下一个。
                last = e.getClass().getSimpleName() + " "
                        + cls.substring(cls.lastIndexOf('.') + 1);
                L.i("keepalive startService " + cls + " blocked: " + e);
            }
        }
        lastAt = System.currentTimeMillis();
    }

    /**
     * healthz 用的一行状态。
     *
     * <p>{@code adj} 是内核给本进程的 oom_score_adj：低于 freezer 阈值（本机 900）就不会被冻；
     * 记它是因为「QQ 被冻」这件事事后只能靠这个数说话。{@code wchan} 是进程当前在等什么
     * （`do_freezer_trap` 就是被冻住了）。
     */
    public static String diag() {
        StringBuilder b = new StringBuilder();
        b.append("adj=").append(readLong("/proc/self/oom_score_adj", -1));
        b.append(" wchan=").append(readText("/proc/self/wchan"));
        b.append(" service=").append(last);
        long at = lastAt;
        if (at > 0) b.append(" age=").append((System.currentTimeMillis() - at) / 1000).append("s");
        return b.toString();
    }

    private static long readLong(String path, long fallback) {
        try {
            String s = readText(path);
            return s.isEmpty() ? fallback : Long.parseLong(s.trim());
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static String readText(String path) {
        try {
            RandomAccessFile f = new RandomAccessFile(path, "r");
            try {
                byte[] buf = new byte[64];
                int n = f.read(buf);
                if (n <= 0) return "";
                StringBuilder s = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    char ch = (char) buf[i];
                    if (ch == '\n' || ch == 0) break;
                    s.append(ch);
                }
                return s.toString().trim();
            } finally {
                f.close();
            }
        } catch (Throwable t) {
            return "";
        }
    }
}
