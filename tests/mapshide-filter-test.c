#include <stddef.h>

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
            "7100000000-7100001000 r-xp 00000000 00:00 0 /data/app/com.onebot.qq/libmapshide.so\n";
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
    if ((rc = expect(is_proc_exposure_path("/proc/net/tcp6"), 0, 18))) return rc;
    if ((rc = expect(is_net_stat_path("/proc/net/tcp"), 1, 19))) return rc;
    if ((rc = expect(is_net_stat_path("/proc/self/maps"), 0, 20))) return rc;
    {
        const char tcp3001[] =
                "   5: 0100007F:0BB9 00000000:0000 0A 00000000:00000000 00:00000000 00000000\n";
        const char tcpOther[] =
                "   6: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000\n";
        if ((rc = expect(net_local_port_hidden(tcp3001, sizeof(tcp3001) - 1), 1, 21))) return rc;
        if ((rc = expect(net_local_port_hidden(tcpOther, sizeof(tcpOther) - 1), 0, 22))) return rc;
    }
    if ((rc = expect(dent_name_blocked("su"), 1, 23))) return rc;
    if ((rc = expect(dent_name_blocked("maps"), 0, 24))) return rc;
    if ((rc = expect(line_blocked(frida, sizeof(frida) - 1), 1, 25))) return rc;
    return 0;
}
