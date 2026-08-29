#include <stddef.h>
#include <string.h>

/* Include the implementation so its intentionally-static parsers can be tested off-device. */
#include "../native/mapshide.c"

static int expect(int actual, int wanted, int code) {
    return actual == wanted ? 0 : code;
}

int main(void) {
    const char anon_rx[] =
            "2e9377e000-2e938a8000 r-xp 00000000 00:00 0 \n";
    const char qq_shadowhook[] =
            "702a305000-702a4ce000 rwxp 00000000 00:00 0 \n";
    const char named_art[] =
            "702a4ce000-702a4cf000 r-xp 00400000 07:58 958976 /apex/com.android.art/lib64/libart.so\n";
    const char generic_vector[] =
            "7000000000-7000001000 r-xp 00000000 00:00 0 [anon:InternalMmapVector]\n";
    const char helper[] =
            "7100000000-7100001000 r-xp 00000000 00:00 0 /data/app/com.satori.qq/libmapshide.so\n";
    const char turing[] =
            "7200000000-7200001000 r-xp 00000000 00:00 0 /data/app/com.tencent.mobileqq/lib/arm64/libturingxq.so\n";
    const char ksu[] =
            "7300000000-7300001000 r-xp 00000000 00:00 0 /data/adb/ksu/bin/ksud\n";
    const char frida[] =
            "7400000000-7400001000 r-xp 00000000 00:00 0 /data/local/tmp/frida-agent.so\n";
    int rc;
    if ((rc = expect(line_blocked(anon_rx, sizeof(anon_rx) - 1), 1, 1))) return rc;
    if ((rc = expect(line_blocked(qq_shadowhook, sizeof(qq_shadowhook) - 1), 0, 2))) return rc;
    if ((rc = expect(line_blocked(named_art, sizeof(named_art) - 1), 0, 3))) return rc;
    if ((rc = expect(line_blocked(generic_vector, sizeof(generic_vector) - 1), 0, 4))) return rc;
    if ((rc = expect(line_blocked(helper, sizeof(helper) - 1), 1, 5))) return rc;
    if ((rc = expect(is_mapping_header(anon_rx, sizeof(anon_rx) - 1), 1, 6))) return rc;
    if ((rc = expect(is_mapping_header("Size: 4 kB\n", 11), 0, 7))) return rc;
    if ((rc = expect(is_detector_path("/data/app/x/libturingxq.so"), 1, 8))) return rc;
    if ((rc = expect(is_detector_path("/data/app/x/libfekit.so"), 1, 9))) return rc;
    if ((rc = expect(is_proc_exposure_path("/proc/self/status"), 1, 10))) return rc;
    if ((rc = expect(is_proc_exposure_path("/proc/mounts"), 1, 11))) return rc;
    if ((rc = expect(is_proc_exposure_path("/proc/self/cmdline"), 0, 12))) return rc;
    if ((rc = expect(line_blocked(ksu, sizeof(ksu) - 1), 1, 13))) return rc;
    if ((rc = expect(line_blocked(turing, sizeof(turing) - 1), 0, 14))) return rc;
    if ((rc = expect(path_denied("/data/adb/modules/foo"), 1, 15))) return rc;
    if ((rc = expect(prop_denied("persist.sys.zygisk.enabled"), 1, 16))) return rc;
    if ((rc = expect(prop_denied("ro.build.version.sdk"), 0, 17))) return rc;
    if ((rc = expect(is_proc_exposure_path("/proc/net/tcp6"), 1, 18))) return rc;
    if ((rc = expect(is_net_stat_path("/proc/net/tcp"), 1, 19))) return rc;
    if ((rc = expect(is_net_stat_path("/proc/self/maps"), 0, 20))) return rc;
    {
        const char tcp3001[] =
                "   5: 0100007F:0BB9 00000000:0000 0A 00000000:00000000 00:00000000 00000000\n";
        const char tcpOther[] =
                "   6: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000\n";
        const char tcpPeer[] =
                "  47: 0100007F:9406 0100007F:0BB9 06 00000000:00000000 03:00001580 00000000\n";
        if ((rc = expect(net_local_port_hidden(tcp3001, sizeof(tcp3001) - 1), 1, 21))) return rc;
        if ((rc = expect(net_local_port_hidden(tcpOther, sizeof(tcpOther) - 1), 0, 22))) return rc;
        if ((rc = expect(net_local_port_hidden(tcpPeer, sizeof(tcpPeer) - 1), 1, 26))) return rc;
        if ((rc = expect(is_proc_exposure_path("/proc/net/tcp"), 1, 27))) return rc;
    }
    if ((rc = expect(dent_name_blocked("su"), 1, 23))) return rc;
    if ((rc = expect(dent_name_blocked("maps"), 0, 24))) return rc;
        if ((rc = expect(line_blocked(frida, sizeof(frida) - 1), 1, 25))) return rc;
    if ((rc = expect(is_proc_exposure_path("/proc/self/environ"), 1, 28))) return rc;
    if ((rc = expect(is_environ_path("/proc/1/environ"), 1, 29))) return rc;
    if ((rc = expect(env_name_denied("MAGISK_VER"), 1, 30))) return rc;
    if ((rc = expect(env_name_denied("ZYGISK_ENABLED"), 1, 31))) return rc;
    if ((rc = expect(env_name_denied("PATH"), 0, 32))) return rc;
    if ((rc = expect(env_entry_denied("MAGISK_VER=26.4", 15), 1, 33))) return rc;
    if ((rc = expect(env_entry_denied("PATH=/system/bin", 16), 0, 34))) return rc;
    if ((rc = expect(env_entry_denied("LD_PRELOAD=/data/adb/modules/foo.so", 35), 1, 35))) return rc;
    if ((rc = expect(hide_loop_ok(0, 0, 0, 2), 1, 36))) return rc;
    if ((rc = expect(hide_loop_ok(0, 0, 0, 0), 0, 37))) return rc;
    if ((rc = expect(hide_loop_ok(1, 0, 0, 2), 0, 38))) return rc;
    if ((rc = expect(hide_loop_ok(0, 1, 0, 2), 0, 39))) return rc;
    if ((rc = expect(hide_loop_ok(-1, 0, 0, 2), 0, 40))) return rc;
    if ((rc = expect(hide_loop_ok(0, -1, -1, 2), 1, 41))) return rc;
    {
        char norm[128];
        if ((rc = expect(collapse_path("/data/./adb/modules/foo", norm, sizeof(norm)), 1, 42)))
            return rc;
        if ((rc = expect(strcmp(norm, "/data/adb/modules/foo") == 0, 1, 43))) return rc;
        if ((rc = expect(collapse_path("/data/adb/../adb/magisk", norm, sizeof(norm)), 1, 44)))
            return rc;
        if ((rc = expect(strcmp(norm, "/data/adb/magisk") == 0, 1, 45))) return rc;
        if ((rc = expect(path_denied("/data/./adb/modules/foo"), 1, 46))) return rc;
        if ((rc = expect(path_denied("/data/adb/../adb/magisk"), 1, 47))) return rc;
        if ((rc = expect(path_denied("/system/bin/../xbin/su"), 1, 48))) return rc;
        if ((rc = expect(path_denied("/sbin/su"), 1, 49))) return rc;
        if ((rc = expect(path_denied("su"), 1, 50))) return rc;
        if ((rc = expect(path_denied("/data/data/com.tencent.mobileqq"), 0, 51))) return rc;
        if ((rc = expect(path_denied("/data/app/de.robv.android.xposed.installer"), 1, 52)))
            return rc;
        const char zwsp[] = "/data/ad" "\xE2\x80\x8B" "b/magisk";
        if ((rc = expect(path_denied(zwsp), 1, 53))) return rc;
        const char shy[] = "/data/adb/mag\xC2\xADisk";
        if ((rc = expect(path_denied(shy), 1, 54))) return rc;
        char usb[16];
        if ((rc = expect(adb_prop_safe_copy("persist.sys.usb.config", usb) == 3, 1, 55)))
            return rc;
        if ((rc = expect(strcmp(usb, "mtp") == 0, 1, 56))) return rc;
        if ((rc = expect(adb_prop_safe_copy("ro.build.type", usb), 0, 57))) return rc;
    }
    return 0;
}
