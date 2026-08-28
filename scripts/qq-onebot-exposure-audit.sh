#!/system/bin/sh
# Capture comparable, non-secret QQ injection exposure snapshots for anti-detection A/B tests.

STATE_DIR=/data/adb/onebot-qq
SNAPSHOT_DIR="$STATE_DIR/exposure-snapshots"
QQ_PACKAGE=com.tencent.mobileqq

mkdir -p "$SNAPSHOT_DIR"

count_maps() {
    keyword="$1"
    [ -n "$qq_pid" ] && grep -aEi "$keyword" "/proc/$qq_pid/maps" 2>/dev/null | wc -l || echo 0
}

capture() {
    quiet="$1"
    reason="${2:-manual}"
    now_epoch="$(date +%s)"
    now_name="$(date +%Y%m%d-%H%M%S)"
    qq_pid="$(pidof "$QQ_PACKAGE" 2>/dev/null | awk '{print $1}')"
    output="$SNAPSHOT_DIR/$now_name.status"
    temp="$output.$$"
    if [ -n "$qq_pid" ]; then
        thread_total="$(find "/proc/$qq_pid/task" -mindepth 1 -maxdepth 1 2>/dev/null | wc -l)"
        suspicious_threads="$(for comm in /proc/$qq_pid/task/*/comm; do cat "$comm" 2>/dev/null; done \
            | grep -Eic 'onebot|xposed|vector|zygisk|mapshide')"
        maps_total="$(wc -l < "/proc/$qq_pid/maps" 2>/dev/null)"
        maps_anon_exec="$(awk '$2 ~ /x/ && (NF < 6 || $6 == "") { n++ } END { print n+0 }' "/proc/$qq_pid/maps")"
        maps_anon_rwx="$(awk '$2 ~ /rwx/ && (NF < 6 || $6 == "") { n++ } END { print n+0 }' "/proc/$qq_pid/maps")"
    else
        thread_total=0
        suspicious_threads=0
        maps_total=0
        maps_anon_exec=0
        maps_anon_rwx=0
    fi
    port=down
    awk -v p=":0BB9" '$2 ~ p && $4 == "0A" { found=1 } END { exit !found }' \
        /proc/net/tcp /proc/net/tcp6 2>/dev/null && port=up
    module_version="$(dumpsys package com.onebot.qq 2>/dev/null \
        | sed -n 's/.*versionName=//p' | head -n 1)"
    top_activity="$(dumpsys activity activities 2>/dev/null \
        | grep -E 'Hist +#[0-9]+: ActivityRecord.*com\.tencent\.mobileqq/' | head -n 1 \
        | sed -n 's/.*com.tencent.mobileqq\/\([^ }]*\).*/\1/p')"
    legacy_logs="$(logcat -d 2>/dev/null | grep -Ec 'OneBotQQ|OneBot-QQ|MapsHide')"
    neutral_errors="$(logcat -d -s Q.Kernel:E 2>/dev/null | wc -l)"
    {
        echo "format_version=3"
        echo "captured_at_epoch=$now_epoch"
        echo "reason=$reason"
        echo "qq_pid=${qq_pid:-0}"
        echo "module_version=${module_version:-unknown}"
        echo "port_3001=$port"
        echo "top_activity=${top_activity:-unknown}"
        echo "maps_total=$maps_total"
        # InternalMmapVector is a normal Android runtime mapping found in clean apps too;
        # do not count that generic name as evidence of the Vector framework.
        echo "maps_vector=$(count_maps 'zygisk_vector|JingMatrix|libvector')"
        echo "maps_vector_generic=$(count_maps vector)"
        echo "maps_zygisk=$(count_maps zygisk)"
        echo "maps_xposed=$(count_maps xposed)"
        echo "maps_lspd=$(count_maps lspd)"
        echo "maps_onebot=$(count_maps onebot)"
        echo "maps_mapshide=$(count_maps mapshide)"
        echo "maps_fekit=$(count_maps fekit)"
        echo "maps_anon_exec=${maps_anon_exec:-0}"
        echo "maps_anon_rwx=${maps_anon_rwx:-0}"
        echo "zygisk_memory_type=$(cat /data/adb/zygisksu/memory_type 2>/dev/null || echo unknown)"
        echo "thread_total=$thread_total"
        echo "suspicious_thread_names=$suspicious_threads"
        echo "legacy_log_fingerprints=$legacy_logs"
        echo "neutral_error_lines=$neutral_errors"
    } > "$temp"
    chmod 0600 "$temp"
    mv "$temp" "$output"
    [ "$quiet" = "--quiet" ] || cat "$output"
}

case "${1:-snapshot}" in
    snapshot)
        capture "${2:-}" "${3:-manual}"
        ;;
    latest)
        latest="$(ls -t "$SNAPSHOT_DIR"/*.status 2>/dev/null | head -n 1)"
        [ -n "$latest" ] && cat "$latest" || { echo "no exposure snapshot" >&2; exit 1; }
        ;;
    diff)
        [ -n "$2" ] && [ -n "$3" ] || { echo "usage: $0 diff OLD NEW" >&2; exit 2; }
        diff -u "$2" "$3"
        ;;
    *)
        echo "usage: $0 {snapshot [--quiet] [reason]|latest|diff OLD NEW}" >&2
        exit 2
        ;;
esac
