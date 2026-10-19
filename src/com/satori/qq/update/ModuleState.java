package com.satori.qq.update;

/**
 * 模块在磁盘上的状态，由 root 侧的探测脚本给出（{@link #probeScript}）。
 *
 * <p>Magisk / KernelSU / APatch 安装模块都是先解包到 {@code modules_update/<id>}，并在
 * {@code modules/<id>} 下放一个 {@code update} 标记，下次开机才合并——所以"装好了"和"生效了"之间
 * 隔着一次重启，{@link #staged} 就是这段时间。没有 root 时探测不出来，{@link #UNKNOWN} 代表"不知道"，
 * 不代表"没装"。
 */
public final class ModuleState {
    public static final ModuleState UNKNOWN = new ModuleState(false, false, false, false, false, "", 0, "", 0, "");

    /** 探测成功（拿到了 root）；false 时其余字段都没有意义。 */
    public final boolean known;
    public final boolean present;
    public final boolean disabled;
    public final boolean removing;
    /** 已有一份新包等着开机合并。 */
    public final boolean staged;
    public final String version;
    public final long code;
    public final String stagedVersion;
    public final long stagedCode;
    /** 这台机器上的模块安装命令：ksud / apd / magisk；找不到为空。 */
    public final String cli;

    ModuleState(boolean known, boolean present, boolean disabled, boolean removing, boolean staged,
                String version, long code, String stagedVersion, long stagedCode, String cli) {
        this.known = known;
        this.present = present;
        this.disabled = disabled;
        this.removing = removing;
        this.staged = staged;
        this.version = version;
        this.code = code;
        this.stagedVersion = stagedVersion;
        this.stagedCode = stagedCode;
        this.cli = cli;
    }

    /** 已装或已暂存的最新版本号：KernelSU 在暂存时会把新的 module.prop 也放进 modules/，两处取大。 */
    public long effectiveCode() {
        return Math.max(code, stagedCode);
    }

    public String effectiveVersion() {
        return stagedCode > code ? stagedVersion : version;
    }

    /**
     * 探测脚本。{@code CLI} 一行按 ksud → apd → magisk 的顺序找：KernelSU 系（含各分支）与 APatch 的命令名固定，
     * 先查 PATH，再查它们各自的安装目录，因为应用里的 su 环境不一定带这些目录。
     */
    public static String probeScript(String id) {
        return String.join("\n",
                "M=/data/adb/modules/" + id,
                "U=/data/adb/modules_update/" + id,
                "[ -d \"$M\" ] && echo present=1",
                "[ -f \"$M/disable\" ] && echo disabled=1",
                "[ -f \"$M/remove\" ] && echo removing=1",
                "{ [ -d \"$U\" ] || [ -f \"$M/update\" ]; } && echo staged=1",
                "sed -n 's/^version=/version=/p' \"$M/module.prop\" 2>/dev/null | head -n 1",
                "sed -n 's/^versionCode=/code=/p' \"$M/module.prop\" 2>/dev/null | head -n 1",
                "sed -n 's/^version=/staged_version=/p' \"$U/module.prop\" 2>/dev/null | head -n 1",
                "sed -n 's/^versionCode=/staged_code=/p' \"$U/module.prop\" 2>/dev/null | head -n 1",
                "for c in /data/adb/ksud /data/adb/ksu/bin/ksud /data/adb/ap/bin/apd /data/adb/magisk/magisk64"
                        + " /data/adb/magisk/magisk; do [ -x \"$c\" ] && p=\"$c\" && break; done",
                "if [ -z \"$p\" ]; then for c in ksud apd magisk; do p=$(command -v $c) && break; done; fi",
                "[ -n \"$p\" ] && echo \"cli=$p\"",
                "true");
    }

    public static ModuleState parse(String out) {
        boolean present = false, disabled = false, removing = false, staged = false;
        String version = "", stagedVersion = "", cli = "";
        long code = 0, stagedCode = 0;
        for (String line : out.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1).trim();
            switch (key) {
                case "present": present = true; break;
                case "disabled": disabled = true; break;
                case "removing": removing = true; break;
                case "staged": staged = true; break;
                case "version": version = value; break;
                case "staged_version": stagedVersion = value; break;
                case "code": code = number(value); break;
                case "staged_code": stagedCode = number(value); break;
                case "cli": cli = value; break;
                default: break;
            }
        }
        return new ModuleState(true, present, disabled, removing, staged, version, code, stagedVersion, stagedCode, cli);
    }

    private static long number(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** 用管理器的哪条命令装一个 zip；cli 是上面探测到的绝对路径或命令名。 */
    public static String installCommand(String cli, String zip) {
        String name = cli.substring(cli.lastIndexOf('/') + 1);
        if (name.startsWith("magisk")) return "'" + cli + "' --install-module '" + zip + "'";
        return "'" + cli + "' module install '" + zip + "'";
    }
}
