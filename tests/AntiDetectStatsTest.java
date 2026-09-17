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
        // QQ 9.3.60.40970 起 libfekit 的 QSec_Channel 上报器换用这组命令，旧版命令表里没有。
        check(AntiDetect.isEnvReportCmd("OidbSvcTrpcTcp.0x9c00_0"), "fekit channel 0x9c00");
        check(AntiDetect.isEnvReportCmd("OidbSvcTrpcTcp.0x9c01_0"), "fekit channel 0x9c01");
        check(AntiDetect.isEnvReportCmd("OidbSvcTrpcTcp.0x9c02_0"), "fekit channel 0x9c02");
        check(AntiDetect.isEnvReportCmd("OidbSvcTrpcTcp.0x9c0c_0"), "fekit channel 0x9c0c");
        check(AntiDetect.isEnvReportCmd("OidbSvcTrpcTcp.0x9cdf_1"), "fekit channel 0x9cdf");
        check(!AntiDetect.isEnvReportCmd("OidbSvcTrpcTcp.0x9b80_1"), "keep neighbouring oidb");
        check(!AntiDetect.isEnvReportCmd("OidbSvcTrpcTcp.0x9c19_0"), "keep neighbouring oidb 2");
        check(!AntiDetect.isFekitChannelReportCmd(null), "null fekit channel cmd");
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
        check(AntiDetect.isDeniedPath("/data/data/com.koushikdutta.superuser"), "koushikdutta path");
        check(AntiDetect.isDeniedPath("/system/etc/install-recovery.sh"), "install-recovery path");
        check(AntiDetect.isHiddenPointQueryPackage("com.koushikdutta.superuser"), "hide koushikdutta");
        check(AntiDetect.frameworkSocketDenied("lsposed"), "socket lsposed");
        check(AntiDetect.frameworkSocketDenied("shamiko"), "socket shamiko");
        check(AntiDetect.frameworkSocketDenied("com.tencent.zygisk"), "socket zygisk");
        check(!AntiDetect.frameworkSocketDenied("com.tencent.mobileqq"), "keep qq socket");
        check(!AntiDetect.frameworkSocketDenied(null), "null socket");
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

        // 人脸核身那条链路（慧眼 + TuringFace）。事件名过滤只认这两个，别的 dt 事件
        // （sendRequestPB / orc_and_embeding 等）必须原样放行，否则人脸本身的请求也会被丢。
        check(AntiDetect.isFaceReportEvent("face_detect"), "face detect event");
        check(AntiDetect.isFaceReportEvent("camera_detect"), "camera detect event");
        check(!AntiDetect.isFaceReportEvent("sendRequestPB"), "keep sendRequestPB");
        check(!AntiDetect.isFaceReportEvent("orc_and_embeding"), "keep orc");
        check(!AntiDetect.isFaceReportEvent(""), "empty face event");
        check(!AntiDetect.isFaceReportEvent(null), "null face event");

        // turingcam 的进程表扫描：只滤敏感条目，普通进程名必须原样返回（整张表清空
        // 是虚拟机的长相，比读到真进程名更可疑）。
        check(!AntiDetect.processNameDenied("com.tencent.mobileqq"), "keep qq process name");
        check(!AntiDetect.processNameDenied("com.tencent.mobileqq:MSF"), "keep msf process name");
        check(!AntiDetect.processNameDenied(""), "empty process name");
        check(!AntiDetect.processNameDenied(null), "null process name");
        check(AntiDetect.processNameDenied("com.topjohnwu.magisk"), "magisk process");
        check(AntiDetect.processNameDenied("/data/adb/ksu/bin/ksud"), "ksud process");
        check(AntiDetect.processNameDenied("eu.chainfire.supersu"), "supersu process");
        check(!AntiDetect.processNameDenied("com.tencent.turingcam"), "keep turingcam java process");
        check("com.tencent.mobileqq".equals(
                AntiDetect.sanitizeProcessName("com.tencent.mobileqq")), "pass real name");
        check("".equals(AntiDetect.sanitizeProcessName("frida-server")), "blank denied name");
        check("".equals(AntiDetect.sanitizeProcessName(null)), "blank null name");

        long faceBefore = AntiDetect.faceStats(true).getLong("dropped");
        AntiDetect.recordFaceReportDrop("face_detect");
        AntiDetect.recordFaceReportDrop("camera_detect");
        JSONObject face = AntiDetect.faceStats(true);
        check(face.getBoolean("enabled"), "face enabled");
        eq(faceBefore + 2, face.getLong("dropped"), "face dropped");
        check(face.getJSONObject("events").getLong("face_detect") >= 1, "face event counted");
        check(face.getJSONObject("events").getLong("camera_detect") >= 1, "camera event counted");
        check("camera_detect".equals(face.getString("last")), "face last event");
        check(face.getJSONObject("hooks").has("face_report")
                && face.getJSONObject("hooks").has("turing_face")
                && face.getJSONObject("hooks").has("turing_process"), "face hook counters");
        check(face.has("intercepts_ready"), "face intercept readiness");
        check("main".equals(face.getString("process")), "face process key");

        // 被拦下的服务端踢线：计数与内容会进 /healthz，外部看守靠它重启 QQ。
        int kicks = AntiDetect.blockedKicks();
        AntiDetect.noteBlockedKick("type=KKICKBYMULTIINST security=0 sameDevice=false");
        eq(kicks + 1, AntiDetect.blockedKicks(), "blocked kick counted");
        check(AntiDetect.lastKick().contains("KKICKBYMULTIINST"), "blocked kick detail");
        check(AntiDetect.lastKickMs() > 0, "blocked kick time");

        // 三个踢线入口共用一个计数，但必须分得清是谁拦下的。
        AntiDetect.noteBlockedKick("ticket-refresh", "140022014 当前登录环境存在风险");
        check("ticket-refresh".equals(AntiDetect.lastKickSource()), "kick source label");
        check(AntiDetect.lastKick().contains("140022014"), "kick source detail");
        AntiDetect.noteBlockedKick("uid-fail", "no-args");
        check("uid-fail".equals(AntiDetect.lastKickSource()), "kick source replaced");

        // MSF 强踢那几个 reason 必须拦；用户自己退出、切号、票据自然过期要放行，
        // 拦错了会把正常退出登录也变成"踢线"。
        for (String reason : new String[]{"kicked", "secKicked", "forceLogout", "suspend"}) {
            check(AntiDetect.kickReasonBlocked(reason), "block reason " + reason);
        }
        for (String reason : new String[]{"user", "switchAccount", "expired", "tips", "gray",
                "restartProcess", "", null}) {
            check(!AntiDetect.kickReasonBlocked(reason), "pass reason " + reason);
        }

        // 踢线之后 5 分钟内不许本机登出（UID 那条线走的是 logout(true)，reason 分不出来，
        // 只能按窗口拦）；窗口一过必须放行，否则会一辈子拦着正常登出。
        // 判据除了内存里这一份，还会读 qk_kick.log 的 mtime（跨重启）。那一条在 JVM 里读不到
        // 手机路径，只能靠真机验：下面这几条断言验的是内存那一份的边界。
        AntiDetect.noteBlockedKick("guard-window", "probe");
        long now = System.currentTimeMillis();
        check(AntiDetect.inLogoutGuardWindow(now), "logout guard open right after kick");
        check(AntiDetect.inLogoutGuardWindow(now + 60_000L), "logout guard open at +60s");
        check(!AntiDetect.inLogoutGuardWindow(now + 300_001L), "logout guard closed after 5min");

        // 善后期比登出守卫长：踢线把账号标记与自动登录写坏之后，看守会 force-stop QQ 重启，
        // 修盘上那两样东西的动作必须在这个窗口里还有效。窗口一过同样要停手。
        check(AntiDetect.inKickAftermath(now + 300_001L), "aftermath open past guard window");
        check(AntiDetect.inKickAftermath(now + 899_000L), "aftermath open at 15min");
        check(!AntiDetect.inKickAftermath(now + 900_100L), "aftermath closed after 15min");
        // 只有用户自己按的退出登录才停手；expired/gray/tips 是 QQ 自己的生命周期，
        // 把它们当成用户意图会让善后期在一件跟用户无关的事上失效。
        for (String reason : new String[]{"user", "switchAccount"}) {
            check(AntiDetect.userInitiatedLogout(reason), "user logout " + reason);
        }
        for (String reason : new String[]{"expired", "gray", "tips", "restartProcess", "kicked",
                "secKicked", "forceLogout", "suspend", "", null}) {
            check(!AntiDetect.userInitiatedLogout(reason), "not user logout " + reason);
        }
        // 用户自己点了退出登录就不再替他保活，否则「退出登录」会退不掉。
        AntiDetect.noteDeliberateLogout();
        check(!AntiDetect.inKickAftermath(System.currentTimeMillis()), "user logout stops aftermath");

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
