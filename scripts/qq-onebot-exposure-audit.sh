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
        # QQ 9.3.50 itself reserves one nameless RWX near-trampoline arena for ShadowHook.
        # The injected delta is the useful A/B signal, not that clean-app baseline mapping.
        maps_anon_exec_excess=$((maps_anon_exec > 1 ? maps_anon_exec - 1 : 0))
        maps_anon_rwx_excess=$((maps_anon_rwx > 1 ? maps_anon_rwx - 1 : 0))
        maps_memfd_jit_rx="$(awk '$2 ~ /r-xp/ && $0 ~ /\/memfd:jit-cache/ { n++ } END { print n+0 }' "/proc/$qq_pid/maps")"
        seccomp_mode="$(awk '/^Seccomp:/{print $2}' "/proc/$qq_pid/status" 2>/dev/null)"
        seccomp_filters="$(awk '/^Seccomp_filters:/{print $2}' "/proc/$qq_pid/status" 2>/dev/null)"
        nonewprivs="$(awk '/^NoNewPrivs:/{print $2}' "/proc/$qq_pid/status" 2>/dev/null)"
        msf_pid="$(ps -A 2>/dev/null | awk '/com\.tencent\.mobileqq:MSF/{print $2; exit}')"
        msf_seccomp_filters=0
        msf_nonewprivs=0
        if [ -n "$msf_pid" ] && [ -r "/proc/$msf_pid/status" ]; then
            msf_seccomp_filters="$(awk '/^Seccomp_filters:/{print $2}' "/proc/$msf_pid/status" 2>/dev/null)"
            msf_nonewprivs="$(awk '/^NoNewPrivs:/{print $2}' "/proc/$msf_pid/status" 2>/dev/null)"
        fi
    else
        thread_total=0
        suspicious_threads=0
        maps_total=0
        maps_anon_exec=0
        maps_anon_rwx=0
        maps_anon_exec_excess=0
        maps_anon_rwx_excess=0
        maps_memfd_jit_rx=0
        seccomp_mode=0
        seccomp_filters=0
        nonewprivs=0
        msf_pid=""
        msf_seccomp_filters=0
        msf_nonewprivs=0
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
        echo "format_version=5"
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
        echo "maps_anon_exec_excess=${maps_anon_exec_excess:-0}"
        echo "maps_anon_rwx_excess=${maps_anon_rwx_excess:-0}"
        echo "maps_shadowhook=$(count_maps 'shadowhook')"
        echo "maps_memfd_jit_rx=${maps_memfd_jit_rx:-0}"
        echo "seccomp_mode=${seccomp_mode:-0}"
        echo "seccomp_filters=${seccomp_filters:-0}"
        echo "nonewprivs=${nonewprivs:-0}"
        echo "msf_pid=${msf_pid:-0}"
        echo "msf_seccomp_filters=${msf_seccomp_filters:-0}"
        echo "msf_nonewprivs=${msf_nonewprivs:-0}"
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
