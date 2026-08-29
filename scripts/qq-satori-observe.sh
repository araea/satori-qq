#!/system/bin/sh
# Persist session samples across Cursor terminals. Does not stop QQ or touch scope.
# start from service.d; samples every 120s into session-observe-YYYYMMDD.log.
# Version / pid / account_kicks changes also go to observe-events.log.

STATE_DIR="${SATORI_OBSERVE_STATE_DIR:-/data/adb/satori-qq}"
ENABLED="$STATE_DIR/observe.enabled"
PID_FILE="$STATE_DIR/observe.pid"
STATE_FILE="$STATE_DIR/observe.state"
EVENTS_FILE="$STATE_DIR/observe-events.log"
INTERVAL="${SATORI_OBSERVE_INTERVAL:-120}"
NODE=/data/data/com.termux/files/usr/bin/node
HEALTH_JS=/storage/emulated/0/Dev/satori-qq/tests/ws-health.js
COLDSTART="$STATE_DIR/qq-satori-coldstart-check.sh"
EXPOSURE_AUDIT="$STATE_DIR/qq-satori-exposure-audit.sh"

mkdir -p "$STATE_DIR"

last_version=
last_kicks=
last_qq_pid=

load_state() {
    [ -f "$STATE_FILE" ] || return 0
    last_version="$(sed -n 's/^last_version=//p' "$STATE_FILE" | tail -n 1)"
    last_kicks="$(sed -n 's/^last_kicks=//p' "$STATE_FILE" | tail -n 1)"
    last_qq_pid="$(sed -n 's/^last_qq_pid=//p' "$STATE_FILE" | tail -n 1)"
}

save_state() {
    state_tmp="$STATE_FILE.$$"
    {
        echo "last_version=${last_version}"
        echo "last_kicks=${last_kicks}"
        echo "last_qq_pid=${last_qq_pid}"
        echo "updated_epoch=$(date +%s)"
    } > "$state_tmp" && mv "$state_tmp" "$STATE_FILE"
}

daily_log() {
    echo "$STATE_DIR/session-observe-$(date '+%Y%m%d').log"
}

emit_event() {
    msg="$1"
    stamp="$(date '+%F %T')"
    echo "EVENT $stamp $msg" | tee -a "$(daily_log)" >> "$EVENTS_FILE"
}

sample_once() {
    log="$(daily_log)"
    echo "==== $(date '+%F %T') ====" >> "$log"
    summary=""
    if [ -x "$NODE" ] && [ -f "$HEALTH_JS" ]; then
        PATH=/data/data/com.termux/files/usr/bin:/system/bin:${PATH:-/system/bin}
        LD_LIBRARY_PATH=/data/data/com.termux/files/usr/lib:${LD_LIBRARY_PATH:-}
        export PATH LD_LIBRARY_PATH
        summary="$("$NODE" "$HEALTH_JS" 2>&1)" || summary="ws-unhealthy"
    else
        summary="ws-health-missing"
    fi
    echo "$summary" >> "$log"
    kicks="$(sed -n 's/^account_kicks=//p' "$STATE_DIR/watchdog.counters" 2>/dev/null | tail -n 1)"
    echo "account_kicks=${kicks:-}" >> "$log"
    wd_state="$(sed -n 's/^state=//p' "$STATE_DIR/watchdog.status" 2>/dev/null | tail -n 1)"
    echo "watchdog_state=${wd_state:-}" >> "$log"
    qq_pid="$(pidof com.tencent.mobileqq 2>/dev/null | awk '{print $1}')"
    echo "qq_pid=${qq_pid:-0}" >> "$log"

    version="$(printf '%s\n' "$summary" | sed -n 's/.*"version":"\([^"]*\)".*/\1/p')"
    if [ -n "$last_version" ] && [ -n "$version" ] && [ "$version" != "$last_version" ]; then
        emit_event "app_version ${last_version} -> ${version} qq_pid=${qq_pid:-0}"
        if [ -x "$COLDSTART" ]; then
            "$COLDSTART" >> "$log" 2>&1 || true
        fi
        if [ -x "$EXPOSURE_AUDIT" ]; then
            "$EXPOSURE_AUDIT" snapshot --quiet "observe-${last_version}-to-${version}" >/dev/null 2>&1 &
        fi
    fi
    if [ -n "$last_kicks" ] && [ -n "$kicks" ]; then
        case "$kicks" in *[!0-9]*) ;; *)
            case "$last_kicks" in *[!0-9]*) ;; *)
                if [ "$kicks" -gt "$last_kicks" ]; then
                    emit_event "account_kicks ${last_kicks} -> ${kicks} qq_pid=${qq_pid:-0}"
                fi
            ;; esac
        ;; esac
    fi
    if [ -n "$last_qq_pid" ]; then
        cur_pid="${qq_pid:-0}"
        if [ "$cur_pid" != "$last_qq_pid" ]; then
            emit_event "qq_pid ${last_qq_pid} -> ${cur_pid} version=${version:-none}"
        fi
    fi
    if [ "$summary" = "ws-unhealthy" ] && [ -n "$last_qq_pid" ] && [ "$last_qq_pid" != "0" ]; then
        emit_event "ws-unhealthy last_version=${last_version:-none} last_pid=${last_qq_pid}"
    fi
    [ -n "$version" ] && last_version="$version"
    [ -n "$kicks" ] && last_kicks="$kicks"
    if [ -n "$qq_pid" ]; then
        last_qq_pid="$qq_pid"
    else
        last_qq_pid="0"
    fi
    save_state
}

run_loop() {
    old_pid="$(cat "$PID_FILE" 2>/dev/null)"
    if [ -n "$old_pid" ] && kill -0 "$old_pid" 2>/dev/null; then
        return 0
    fi
    echo $$ > "$PID_FILE"
    trap 'rm -f "$PID_FILE"; exit 0' TERM INT EXIT
    load_state
    while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 5; done
    while [ -f "$ENABLED" ]; do
        sample_once
        sleep "$INTERVAL"
    done
    rm -f "$PID_FILE"
}

case "${1:-run}" in
    run)
        run_loop
        ;;
    start)
        touch "$ENABLED"
        old_pid="$(cat "$PID_FILE" 2>/dev/null)"
        if [ -z "$old_pid" ] || ! kill -0 "$old_pid" 2>/dev/null; then
            setsid "$0" run >/dev/null 2>&1 &
        fi
        ;;
    stop)
        rm -f "$ENABLED"
        old_pid="$(cat "$PID_FILE" 2>/dev/null)"
        if [ -n "$old_pid" ]; then
            kill "$old_pid" 2>/dev/null
            sleep 1
            kill -9 "$old_pid" 2>/dev/null
        fi
        ;;
    status)
        old_pid="$(cat "$PID_FILE" 2>/dev/null)"
        if [ -f "$ENABLED" ] && [ -n "$old_pid" ] && kill -0 "$old_pid" 2>/dev/null; then
            echo "enabled running pid=$old_pid last_version=$(sed -n 's/^last_version=//p' "$STATE_FILE" 2>/dev/null | tail -n 1) last_kicks=$(sed -n 's/^last_kicks=//p' "$STATE_FILE" 2>/dev/null | tail -n 1)"
        elif [ -f "$ENABLED" ]; then
            echo "enabled not-running"
            exit 1
        else
            echo "disabled"
            exit 1
        fi
        ;;
    sample)
        load_state
        sample_once
        ;;
    *)
        echo "usage: $0 {run|start|stop|status|sample}" >&2
        exit 2
        ;;
esac
