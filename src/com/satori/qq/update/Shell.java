package com.satori.qq.update;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 以 root 跑一段脚本。脚本从标准输入喂给 {@code su}（不经命令行拼接，没有引号转义问题），
 * 开头先回显一句暗号：读到它才说明脚本真的在 su 里跑起来了，没有 root 或被拒绝时 {@code granted} 为 false。
 * 带时限，只在工作线程里调用。
 */
final class Shell {
    private static final String MARK = "__satori_update_root__";
    private static final int MAX_OUTPUT = 64 * 1024;

    private Shell() {}

    static final class Result {
        final boolean granted;
        final int exit;
        final String out;

        Result(boolean granted, int exit, String out) {
            this.granted = granted;
            this.exit = exit;
            this.out = out;
        }

        boolean ok() {
            return granted && exit == 0;
        }

        /** 输出的最后几行：安装器的报错都在末尾。 */
        String tail(int lines) {
            String[] all = out.trim().split("\n");
            StringBuilder text = new StringBuilder();
            for (int i = Math.max(0, all.length - lines); i < all.length; i++) {
                String line = all[i].trim();
                if (!line.isEmpty()) text.append(text.length() == 0 ? "" : "\n").append(line);
            }
            return text.toString();
        }
    }

    static Result run(String script, long timeoutMs) {
        Process process = null;
        try {
            process = new ProcessBuilder(su()).redirectErrorStream(true).start();
            try (OutputStream in = process.getOutputStream()) {
                in.write(("echo " + MARK + "\n" + script + "\nexit\n").getBytes(StandardCharsets.UTF_8));
            }
            final InputStream stream = process.getInputStream();
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                byte[] chunk = new byte[4096];
                int count;
                try {
                    while ((count = stream.read(chunk)) != -1) {
                        synchronized (buffer) {
                            if (buffer.size() + count <= MAX_OUTPUT) buffer.write(chunk, 0, count);
                        }
                    }
                } catch (Exception ignored) {
                    // 进程被结束时流会断开，读到哪算哪。
                }
            }, "update-su");
            reader.start();
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                reader.join(500);
                return new Result(false, -1, "");
            }
            reader.join(1000);
            String out;
            synchronized (buffer) {
                out = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            }
            int mark = out.indexOf(MARK);
            if (mark < 0) return new Result(false, process.exitValue(), out);
            int start = out.indexOf('\n', mark);
            return new Result(true, process.exitValue(), start < 0 ? "" : out.substring(start + 1));
        } catch (Exception error) {
            return new Result(false, -1, "");
        } finally {
            if (process != null) process.destroy();
        }
    }

    /** Root 通常挂在 /system/bin/su；先试绝对路径，失败再退回 PATH 查找。 */
    private static String su() {
        return new File("/system/bin/su").canExecute() ? "/system/bin/su" : "su";
    }
}
