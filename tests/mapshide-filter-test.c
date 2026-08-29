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
    int rc;
    if ((rc = expect(line_blocked(anon_rx, sizeof(anon_rx) - 1), 1, 1))) return rc;
    if ((rc = expect(line_blocked(qq_shadowhook, sizeof(qq_shadowhook) - 1), 0, 2))) return rc;
    if ((rc = expect(line_blocked(named_art, sizeof(named_art) - 1), 0, 3))) return rc;
    if ((rc = expect(line_blocked(generic_vector, sizeof(generic_vector) - 1), 0, 4))) return rc;
    if ((rc = expect(line_blocked(helper, sizeof(helper) - 1), 1, 5))) return rc;
    if ((rc = expect(is_mapping_header(anon_rx, sizeof(anon_rx) - 1), 1, 6))) return rc;
    if ((rc = expect(is_mapping_header("Size: 4 kB\n", 11), 0, 7))) return rc;
    return 0;
}
