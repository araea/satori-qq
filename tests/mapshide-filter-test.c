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
    /* 匿名的可执行映射统一滤掉，rwxp 也算。0.8.9.44 之前在设备上核对过：主进程有两条
     * 无路径 rwxp（一条 4KB、与 :MSF 同地址，一条约 1.9MB、只有主进程有），读出来的字节
     * 都是 aarch64 蹦床（`ldr x17,#8; br x17` 一类），也就是 inline hook 的落地页。
     * libfekit 读的正是 maps，留着就是把"进程里有 trampoline 区"直接递过去。 */
    if ((rc = expect(line_blocked(qq_shadowhook, sizeof(qq_shadowhook) - 1), 1, 2))) return rc;
    const char named_rwx[] =
            "702a500000-702a501000 rwxp 00000000 00:00 0 [anon:dalvik-jit-code-cache]\n";
    if ((rc = expect(line_blocked(named_rwx, sizeof(named_rwx) - 1), 0, 117))) return rc;
    if ((rc = expect(is_exec_perm("7000-8000 rwxp 0 00:00 0 \n", 25), 1, 118))) return rc;
    if ((rc = expect(is_exec_perm("7000-8000 rw-p 0 00:00 0 \n", 24), 0, 119))) return rc;
    /* 环境变量那条路共用 line_blocked：一个带 x 的普通词不能被当成权限位。 */
    if ((rc = expect(is_exec_perm("FOO=abc xyz", 11), 0, 120))) return rc;
    if ((rc = expect(is_exec_perm("Size: 4 kB\n", 11), 0, 121))) return rc;
    if ((rc = expect(is_exec_perm("7d1c899000-7d1c8a1000 default anon=1 dirty=1\n", 42), 0, 122))) return rc;
    if ((rc = expect(line_blocked(named_art, sizeof(named_art) - 1), 0, 3))) return rc;
    if ((rc = expect(line_blocked(generic_vector, sizeof(generic_vector) - 1), 0, 4))) return rc;
    if ((rc = expect(line_blocked(helper, sizeof(helper) - 1), 1, 5))) return rc;
    if ((rc = expect(is_mapping_header(anon_rx, sizeof(anon_rx) - 1), 1, 6))) return rc;
    if ((rc = expect(is_mapping_header("Size: 4 kB\n", 11), 0, 7))) return rc;
    if ((rc = expect(is_detector_path("/data/app/x/libturingxq.so"), 1, 8))) return rc;
    if ((rc = expect(is_detector_path("/data/app/x/libfekit.so"), 1, 9))) return rc;
    if ((rc = expect(is_detector_path("/data/app/x/libQSec.so"), 1, 58))) return rc;
    if ((rc = expect(is_detector_path("/data/app/x/libmsfbootV2.so"), 1, 64))) return rc;
    if ((rc = expect(is_detector_path("/data/app/x/libMSFKernel.so"), 0, 65))) return rc;
    if ((rc = expect(is_proc_exposure_path("/proc/self/status"), 1, 10))) return rc;
    if ((rc = expect(is_proc_exposure_path("/proc/mounts"), 1, 11))) return rc;
    if ((rc = expect(is_proc_exposure_path("/proc/self/cmdline"), 1, 12))) return rc;
    if ((rc = expect(is_proc_exposure_path("/proc/1234/cmdline"), 1, 63))) return rc;
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
        if ((rc = expect(path_denied("/proc/self/mem"), 1, 87))) return rc;
        if ((rc = expect(path_denied("/proc/1234/mem"), 1, 88))) return rc;
        if ((rc = expect(path_denied("/proc/self/pagemap"), 1, 89))) return rc;
        if ((rc = expect(path_denied("/proc/kcore"), 1, 90))) return rc;
        if ((rc = expect(path_denied("/data/data/com.tencent.mobileqq/files/mem"), 0, 91))) return rc;
        if ((rc = expect(path_denied("/data/app/de.robv.android.xposed.installer"), 1, 52)))
            return rc;
        if ((rc = expect(path_denied("/data/data/com.koushikdutta.superuser"), 1, 66))) return rc;
        if ((rc = expect(path_denied("/system/etc/install-recovery.sh"), 1, 67))) return rc;
        const char zwsp[] = "/data/ad" "\xE2\x80\x8B" "b/magisk";
        if ((rc = expect(path_denied(zwsp), 1, 53))) return rc;
        const char shy[] = "/data/adb/mag\xC2\xADisk";
        if ((rc = expect(path_denied(shy), 1, 54))) return rc;
        char usb[16];
        if ((rc = expect(prop_safe_copy("persist.sys.usb.config", usb) == 3, 1, 55)))
            return rc;
        if ((rc = expect(strcmp(usb, "mtp") == 0, 1, 56))) return rc;
        if ((rc = expect(prop_safe_copy("ro.build.type", usb), 0, 57))) return rc;
        if ((rc = expect(prop_safe_copy("ro.debuggable", usb) == 1, 1, 68))) return rc;
        if ((rc = expect(strcmp(usb, "0") == 0, 1, 69))) return rc;
        if ((rc = expect(prop_safe_copy("ro.kernel.qemu", usb) == 1, 1, 70))) return rc;
        if ((rc = expect(strcmp(usb, "0") == 0, 1, 71))) return rc;
        if ((rc = expect(prop_safe_copy("ro.secure", usb) == 1, 1, 72))) return rc;
        if ((rc = expect(strcmp(usb, "1") == 0, 1, 73))) return rc;
    }
    {
        const char report[] = "prefix DeviceTokenV3 suffix";
        const char safe[] = "MessageSvc.PbSendMsg";
        if ((rc = expect(risk_payload(report, sizeof(report) - 1), 1, 59))) return rc;
        if ((rc = expect(risk_payload(safe, sizeof(safe) - 1), 0, 60))) return rc;
        if ((rc = expect(symbol_denied("bytehook_get_mode"), 1, 61))) return rc;
        if ((rc = expect(symbol_denied("malloc"), 0, 62))) return rc;
    }
    {
        /* /proc/<pid>/cmdline arrives as one NUL-joined line; the line filter drops a
         * root-manager cmdline and keeps QQ's own. */
        const char cmdlineRoot[] = "com.topjohnwu.magisk\0";
        const char cmdlineQq[] = "com.tencent.mobileqq\0";
        if ((rc = expect(line_blocked(cmdlineRoot, sizeof(cmdlineRoot) - 1), 1, 74))) return rc;
        if ((rc = expect(line_blocked(cmdlineQq, sizeof(cmdlineQq) - 1), 0, 75))) return rc;
    }
    {
        /* Detector token scans. The needle decides, not the symbol: a search for a
         * blacklist name hidden inside a longer string must come back empty on every
         * search primitive libfekit imports. */
        const char hay[] = "11 r-xp /data/app/~~x==/com.topjohnwu.magisk-abc==/base.apk";
        const char probe[] = "/data/app/~~x==/com.topjohnwu.magisk-abc==/base.apk";
        const char okProbe[] = "/data/app/~~x==/com.tencent.mobileqq-abc==/base.apk";
        const char okHay[] = "/system/lib64/libc.so";
        if ((rc = expect(my_strstr(hay, "magisk") == 0, 1, 76))) return rc;
        if ((rc = expect(my_strstr(hay, probe) == 0, 1, 77))) return rc;
        if ((rc = expect(my_strstr(hay, okHay) == 0, 1, 78))) return rc;
        /* A search for the bare word is answered too: that is how a detector looks up its
         * own blacklist catalog, so it must find nothing. */
        if ((rc = expect(my_strstr(hay, "magisk") == 0, 1, 79))) return rc;
        if ((rc = expect(my_strcasestr("MAPS: LSPOSED", "lsposed") == 0, 1, 80))) return rc;
        if ((rc = expect(my_strcasestr("maps", "/data/adb/modules/zygisk") == 0, 1, 81))) return rc;
        if ((rc = expect(my_strcasestr("maps: LIBC.so", "libc") != 0, 1, 82))) return rc;
        if ((rc = expect(my_memmem(hay, sizeof(hay) - 1, "zygisk", 6) == 0, 1, 83))) return rc;
        if ((rc = expect(my_memmem(hay, sizeof(hay) - 1, "base.apk", 8) != 0, 1, 84))) return rc;
        if ((rc = expect(needle_blocked("com.topjohnwu.magisk", 21), 1, 85))) return rc;
        if ((rc = expect(needle_blocked("com.tencent.mobileqq", 20), 0, 86))) return rc;
    }
    {
        /* 按库归因：升级 QQ 后哪个检测库改名/消失要靠这个看出来。 */
        if ((rc = expect(lib_index("/data/app/x/lib/libfekit.so"), 0, 92))) return rc;
        if ((rc = expect(lib_index("/data/app/x/lib/libturingxq.so"), 1, 93))) return rc;
        if ((rc = expect(lib_index("/data/app/x/lib/libturingmfa.so"), 2, 94))) return rc;
        if ((rc = expect(lib_index("/data/app/x/lib/libmsfbootV2.so"), 3, 95))) return rc;
        if ((rc = expect(lib_index("/data/app/x/lib/libQSec.so"), 4, 96))) return rc;
        if ((rc = expect(lib_index("/data/app/x/lib/libckguard.so"), 5, 97))) return rc;
        if ((rc = expect(lib_index("/data/app/x/lib/libwtecdh.so"), 6, 98))) return rc;
        if ((rc = expect(lib_index("/data/app/x/lib/libother.so"), 7, 99))) return rc;
        if ((rc = expect(lib_index(0), 7, 100))) return rc;
        if ((rc = expect(strcmp(LIB_NAMES[0], "fekit") == 0, 1, 101))) return rc;
        if ((rc = expect(strcmp(LIB_NAMES[7], "other") == 0, 1, 102))) return rc;
    }
    {
        /* 模块自己的 .so 挂在 /memfd:dalvik-jit-code-cache 上。ART 的 memfd 只有
         * jit-cache / jit-zygote-cache，dalvik-jit-code-cache 只当 anon_shmem 名字用，
         * 所以这条 BLOCK 只打模块那三行，ART 自己的行要照旧放过去。 */
        const char mod_rx[] =
                "7d1c899000-7d1c8a1000 r-xp 00000000 00:01 7866 /memfd:dalvik-jit-code-cache (deleted)\n";
        const char mod_rw[] =
                "7d1c8a8000-7d1c8a9000 rw-p 00007000 00:01 7866 /memfd:dalvik-jit-code-cache (deleted)\n";
        const char art_memfd[] =
                "64800000-66800000 r--s 00000000 00:01 7859 /memfd:jit-cache (deleted)\n";
        const char art_shmem[] =
                "66800000-68800000 r-xs 02000000 00:01 7859 [anon_shmem:dalvik-jit-code-cache]\n";
        const char art_zygote[] =
                "60800000-62800000 r--s 00000000 00:01 4 /memfd:jit-zygote-cache (deleted)\n";
        if ((rc = expect(line_blocked(mod_rx, sizeof(mod_rx) - 1), 1, 103))) return rc;
        if ((rc = expect(line_blocked(mod_rw, sizeof(mod_rw) - 1), 1, 104))) return rc;
        if ((rc = expect(line_blocked(art_memfd, sizeof(art_memfd) - 1), 0, 105))) return rc;
        if ((rc = expect(line_blocked(art_shmem, sizeof(art_shmem) - 1), 0, 106))) return rc;
        if ((rc = expect(line_blocked(art_zygote, sizeof(art_zygote) - 1), 0, 107))) return rc;
    }
    {
        /* fdinfo 的 name: 行、numa_maps 的 file= 行：都是按行的文本，走同一张 BLOCK 表就能盖住。 */
        if ((rc = expect(is_proc_exposure_path("/proc/self/fdinfo/57"), 1, 108))) return rc;
        if ((rc = expect(is_proc_exposure_path("/proc/1234/numa_maps"), 1, 109))) return rc;
        if ((rc = expect(is_proc_exposure_path("/proc/self/fd/57"), 0, 110))) return rc;
    }
    {
        /* readlink 的输入是 /proc/self/fd/<n>，关键字只在返回值里，所以要按结果判。 */
        const char target[] = "/memfd:dalvik-jit-code-cache (deleted)";
        const char benign[] = "socket:[12345]";
        const char apk[] = "/data/app/x/com.satori.qq-y/base.apk";
        const char lib[] = "/apex/com.android.art/lib64/libart.so";
        if ((rc = expect(link_result_blocked(target, sizeof(target) - 1), 1, 111))) return rc;
        if ((rc = expect(link_result_blocked(apk, sizeof(apk) - 1), 1, 112))) return rc;
        if ((rc = expect(link_result_blocked(benign, sizeof(benign) - 1), 0, 113))) return rc;
        if ((rc = expect(link_result_blocked(lib, sizeof(lib) - 1), 0, 114))) return rc;
        if ((rc = expect(link_result_blocked(target, 0), 0, 115))) return rc;
        /* 名字过滤认不出 dl_iterate_phdr 里的 /proc/self/fd/<n>，那条靠 g_self_base 排除。 */
        if ((rc = expect(module_name_blocked("/proc/self/fd/57"), 0, 116))) return rc;
    }
    return 0;
}
