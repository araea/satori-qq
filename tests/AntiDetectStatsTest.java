import com.satori.qq.qq.AntiDetect;
import org.json.JSONObject;

/** Offline checks for count-only getFeKitAttach observation. */
public final class AntiDetectStatsTest {
    public static void main(String[] args) throws Exception {
        long before = AntiDetect.fekitAttachStats(true).getLong("total");
        AntiDetect.recordFekitAttach("0x810", "0x11", 32, false);
        AntiDetect.recordFekitAttach("account-data", "0x11", -1, true);
        JSONObject stats = AntiDetect.fekitAttachStats(true);
        check(stats.getBoolean("enabled"), "enabled");
        eq(before + 2, stats.getLong("total"), "total");
        check(stats.getLong("errors") >= 1, "errors");
        eq(-1, stats.getLong("last_length"), "last length");
        JSONObject commands = stats.getJSONObject("commands");
        check(commands.getLong("0x810/0x11") >= 1, "known command");
        check(commands.getLong("other/0x11") >= 1, "sensitive command redacted");
        check(AntiDetect.isEnvReportCmd("trpc.o3.report.Report.SsoReport"), "sso report");
        check(AntiDetect.isEnvReportCmd("trpc.o3.report.Report.SsoEventReport"), "event report");
        check(AntiDetect.isEnvReportCmd("trpc.o3.mobile_security.MobileSecurity.SsoCheckSwitch"), "mobile security");
        check(AntiDetect.isEnvReportCmd("trpc.gc_indust.device_report.SsoHome.SsoHomeReport"), "device report");
        check(AntiDetect.isEnvReportCmd("trpc.ilive_cdn.report.StreamReport"), "cdn report");
        check(AntiDetect.isEnvReportCmd("OidbSvc.0xd79"), "oidb device report");
        check(!AntiDetect.isEnvReportCmd("trpc.o3.ecdh_access.EcdhAccess.SsoSecureAccess"), "keep ecdh");
        check(!AntiDetect.isEnvReportCmd("trpc.o3.ecdh_access.EcdhAccess.SsoEstablishShareKey"), "keep sharekey");
        check(!AntiDetect.isEnvReportCmd("trpc.o3.guard.GuardHello"), "keep other o3");
        check(!AntiDetect.isEnvReportCmd("CliLogSvc.UploadReq"), "keep clilog");
        check(!AntiDetect.isEnvReportCmd("MessageSvc.PbSendMsg"), "keep send");
        check(!AntiDetect.isEnvReportCmd("StatSvc.register"), "keep statsvc");
        check(AntiDetect.envNameDenied("MAGISK_VER"), "magisk env");
        check(AntiDetect.envNameDenied("ZYGISK_ENABLED"), "zygisk env");
        check(!AntiDetect.envNameDenied("PATH"), "keep PATH");
        check(!AntiDetect.envNameDenied(null), "null env");
        long droppedBefore = AntiDetect.envReportStats(true).getLong("dropped");
        AntiDetect.recordEnvReportDrop("trpc.o3.report.Report.SsoReport");
        AntiDetect.recordEnvReportDrop("in:trpc.o3.report.Report.SsoReport");
        JSONObject env = AntiDetect.envReportStats(true);
        check(env.getBoolean("enabled"), "env enabled");
        eq(droppedBefore + 2, env.getLong("dropped"), "dropped");
        check(env.getJSONObject("commands").getLong("in:trpc.o3.report.Report.SsoReport") >= 1,
                "inbound drop prefix");
        JSONObject hooks = env.getJSONObject("hooks");
        check(hooks.has("channel_send") && hooks.has("channel_in")
                && hooks.has("msf_send") && hooks.has("msf_in")
                && hooks.has("hardening"), "hook counters");
        check(env.has("intercepts_ready"), "intercept readiness");
        check("main".equals(env.getString("process")), "process key");
        check(AntiDetect.isDeniedPath("/data/adb/magisk"), "adb magisk");
        check(AntiDetect.isDeniedPath("/data/./adb/modules/foo"), "dot-slash adb");
        check(AntiDetect.isDeniedPath("/data/adb/../adb/magisk"), "dot-dot adb");
        check(AntiDetect.isDeniedPath("/system/bin/../xbin/su"), "dot-dot su");
        check(AntiDetect.isDeniedPath("/data/app/de.robv.android.xposed.installer"), "xposed path");
        check(!AntiDetect.isDeniedPath("/data/data/com.tencent.mobileqq"), "keep qq data");
        check(AntiDetect.isDeniedCommand("su"), "bare su");
        check(AntiDetect.isDeniedCommand("/system/bin/su 0"), "su with args");
        check(AntiDetect.isDeniedCommand("/system/bin/sh -c type su"), "type su");
        check(AntiDetect.isDeniedCommand("sh -c command -v su"), "command -v su");
        check(!AntiDetect.isDeniedCommand("id"), "keep id");
        check(AntiDetect.isHiddenInstalledPackage("com.satori.qq"), "hide module from lists");
        check(!AntiDetect.isHiddenPointQueryPackage("com.satori.qq"), "keep module point query");
        check(AntiDetect.isHiddenInstalledPackage("org.lsposed.manager"), "hide lsposed list");
        check(AntiDetect.isHiddenPointQueryPackage("org.lsposed.manager"), "hide lsposed point");
        check(AntiDetect.isDeniedPath("/data/ad\u200bb/magisk"), "zwsp adb");
        check(AntiDetect.isDeniedPath("/data/adb/mag\u00adisk"), "soft-hyphen magisk");
        check(AntiDetect.isHiddenPointQueryPackage("com.resukisu.resukisu"), "hide resukisu");
        check(AntiDetect.isHiddenPointQueryPackage("com.tsng.hidemyapplist"), "hide hma");
        check(AntiDetect.isXposedMetaKey("xposedmodule"), "xposed meta");
        check(!AntiDetect.isXposedMetaKey("android.app.lib_name"), "keep other meta");
        check("mtp".equals(AntiDetect.adbPropSafe("persist.sys.usb.config")), "usb config");
        check(AntiDetect.adbPropSafe("ro.build.type") == null, "keep build type");
        check("0".equals(AntiDetect.safeStringProperty("ro.debuggable", "")), "debug property");
        check("1".equals(AntiDetect.safeStringProperty("ro.secure", "")), "secure property");
        check("serial-1".equals(AntiDetect.safeStringProperty("ro.boot.serialno", "serial-1")),
                "serial property");
        check(Integer.valueOf(0).equals(AntiDetect.safeIntProperty("ro.kernel.qemu")),
                "emulator int property");
        check(Boolean.FALSE.equals(AntiDetect.safeBooleanProperty("ro.debuggable")),
                "debug boolean property");
        check("Path dalvik bridge dalvik".equals(
                AntiDetect.sanitizeFrameworkText("Path LSPosed bridge com.satori.qq")),
                "framework text");
        check(AntiDetect.shouldHideProcMapLine(
                "7000-8000 r-xp 0 00:00 0 /data/app/com.satori.qq/libmapshide.so"),
                "java maps line");
        check(!AntiDetect.shouldHideProcMapLine(
                "7000-8000 r-xp 0 00:00 0 /apex/com.android.art/lib64/libart.so"),
                "keep java maps line");
        System.out.println("AntiDetectStatsTest OK");
    }

    private static void eq(long expected, long actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": " + expected + " != " + actual);
        }
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
