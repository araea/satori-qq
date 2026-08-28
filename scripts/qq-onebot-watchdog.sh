#!/system/bin/sh
# Keep the QQ main process and onebot-qq's forward WebSocket alive.
# Install under /data/adb/onebot-qq and start from KernelSU/Magisk service.d.

STATE_DIR="${ONEBOT_WATCHDOG_STATE_DIR:-/data/adb/onebot-qq}"
ENABLED="$STATE_DIR/watchdog.enabled"
PID_FILE="$STATE_DIR/watchdog.pid"
LOG_FILE="$STATE_DIR/watchdog.log"
STATUS_FILE="$STATE_DIR/watchdog.status"
COUNTERS_FILE="$STATE_DIR/watchdog.counters"
EXPOSURE_AUDIT="$STATE_DIR/qq-onebot-exposure-audit.sh"
QQ_PACKAGE=com.tencent.mobileqq
ONEBOT_PORT_HEX=0BB9
CHECK_SECONDS=15
START_GRACE_SECONDS=90
RESTART_BACKOFF_SECONDS=300

mkdir -p "$STATE_DIR"

offline_events=0
recovery_events=0
launch_requests=0
cold_restarts=0
state_changes=0
watchdog_starts=0
account_kicks=0
last_account_kick_epoch=0

load_counters() {
    [ -f "$COUNTERS_FILE" ] || return 0
    while IFS='=' read -r counter_name counter_value; do
        case "$counter_value" in ''|*[!0-9]*) continue ;; esac
        case "$counter_name" in
            offline_events) offline_events="$counter_value" ;;
            recovery_events) recovery_events="$counter_value" ;;
            launch_requests) launch_requests="$counter_value" ;;
            cold_restarts) cold_restarts="$counter_value" ;;
            state_changes) state_changes="$counter_value" ;;
            watchdog_starts) watchdog_starts="$counter_value" ;;
            account_kicks) account_kicks="$counter_value" ;;
            last_account_kick_epoch) last_account_kick_epoch="$counter_value" ;;
        esac
    done < "$COUNTERS_FILE"
}

save_counters() {
    counters_tmp="$COUNTERS_FILE.$$"
    {
        echo "offline_events=$offline_events"
        echo "recovery_events=$recovery_events"
        echo "launch_requests=$launch_requests"
        echo "cold_restarts=$cold_restarts"
        echo "state_changes=$state_changes"
        echo "watchdog_starts=$watchdog_starts"
        echo "account_kicks=$account_kicks"
        echo "last_account_kick_epoch=$last_account_kick_epoch"
    } > "$counters_tmp" && mv "$counters_tmp" "$COUNTERS_FILE"
}

write_status() {
    checked_at="$1"
    observed_pid="$2"
    observed_port="$3"
    observed_login="$4"
    status_tmp="$STATUS_FILE.$$"
    {
        echo "format_version=2"
        echo "checked_at_epoch=$checked_at"
        echo "state=$current_state"
        echo "previous_state=${previous_state:-unknown}"
        echo "qq_pid=${observed_pid:-0}"
        echo "port_3001=$observed_port"
        echo "login_activity=$observed_login"
        echo "last_action=${last_action:-none}"
        echo "last_action_epoch=${last_action_at:-0}"
        echo "offline_events=$offline_events"
        echo "recovery_events=$recovery_events"
        echo "launch_requests=$launch_requests"
        echo "cold_restarts=$cold_restarts"
        echo "state_changes=$state_changes"
        echo "watchdog_starts=$watchdog_starts"
        echo "account_kicks=$account_kicks"
        echo "last_account_kick_epoch=$last_account_kick_epoch"
    } > "$status_tmp" && mv "$status_tmp" "$STATUS_FILE"
}

record_state() {
    observed_state="$1"
    [ "$observed_state" = "$current_state" ] && return 0
    previous_state="$current_state"

    if [ "$current_state" = "online" ] && [ "$observed_state" != "online" ]; then
        offline_events=$((offline_events + 1))
    elif [ -n "$current_state" ] && [ "$current_state" != "online" ] \
            && [ "$observed_state" = "online" ]; then
        recovery_events=$((recovery_events + 1))
    fi
    state_changes=$((state_changes + 1))
    current_state="$observed_state"
    if [ "$observed_state" = "login" ] && recent_account_kick; then
        account_kicks=$((account_kicks + 1))
        last_account_kick_epoch="$(date +%s)"
        log_msg "server account kick observed"
    fi
    save_counters
    log_msg "state ${previous_state:-unknown} -> $current_state"
    if [ -x "$EXPOSURE_AUDIT" ]; then
        "$EXPOSURE_AUDIT" snapshot --quiet "${previous_state:-unknown}-to-$current_state" >/dev/null 2>&1 &
    fi
}

log_msg() {
    if [ -f "$LOG_FILE" ] && [ "$(wc -c < "$LOG_FILE" 2>/dev/null)" -gt 262144 ]; then
        mv "$LOG_FILE" "$LOG_FILE.1"
    fi
    echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG_FILE"
}

qq_pid() {
    pidof "$QQ_PACKAGE" 2>/dev/null | awk '{print $1}'
}

port_ready() {
    awk -v p=":$ONEBOT_PORT_HEX" '$2 ~ p && $4 == "0A" { found=1 } END { exit !found }' \
        /proc/net/tcp /proc/net/tcp6 2>/dev/null
}

login_active() {
    # LoginActivity is usually inside QQ's own background task, so checking only the
    # globally resumed activity misses server kicks whenever another app is foreground.
    # A live LoginActivity is destroyed after a successful login, making task presence a
    # better signal. LoginPublicFragmentActivity covers the verification flow.
    dumpsys activity activities 2>/dev/null \
        | grep -Eq 'ActivityRecord.*com\.tencent\.mobileqq/\.activity\.(LoginActivity|LoginPublicFragmentActivity)'
}

recent_account_kick() {
    # Only classify a login transition as a server kick when Android recorded QQ's
    # ACCOUNT_KICKED activity in the recent event buffer. Ordinary manual logout remains
    # a plain login transition.
    kick_since_epoch=$(( $(date +%s) - 120 ))
    kick_since="$(date -d "@$kick_since_epoch" '+%m-%d %H:%M:%S.000' 2>/dev/null)"
    [ -n "$kick_since" ] || return 1
    /system/bin/logcat -b events -d -T "$kick_since" 2>/dev/null \
        | grep -q 'mqq.intent.action.ACCOUNT_KICKED'
}

launch_qq() {
    /system/bin/monkey -p "$QQ_PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    launch_requests=$((launch_requests + 1))
    last_action="${1:-launch}"
    last_action_at="$(date +%s)"
    save_counters
    log_msg "launch requested"
}

run_loop() {
    old_pid="$(cat "$PID_FILE" 2>/dev/null)"
    if [ -n "$old_pid" ] && kill -0 "$old_pid" 2>/dev/null; then
        log_msg "already running pid=$old_pid"
        return 0
    fi
    echo $$ > "$PID_FILE"
    trap 'rm -f "$PID_FILE"; log_msg "stopped"; exit 0' TERM INT EXIT
    load_counters
    watchdog_starts=$((watchdog_starts + 1))
    save_counters
    current_state="$(sed -n 's/^state=//p' "$STATUS_FILE" 2>/dev/null | tail -n 1)"
    case "$current_state" in online|login|qq_down|port_missing) ;; *) current_state="" ;; esac
    previous_state="$current_state"
    last_action=none
    last_action_at=0
    log_msg "started pid=$$"

    while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 5; done

    missing_since=0
    last_restart=0
    while [ -f "$ENABLED" ]; do
        now="$(date +%s)"
        pid="$(qq_pid)"
        observed_port=down
        observed_login=no
        if [ -z "$pid" ]; then
            observed_state=qq_down
        elif login_active; then
            observed_state=login
            observed_login=yes
            port_ready && observed_port=up
        elif port_ready; then
            observed_state=online
            observed_port=up
        else
            observed_state=port_missing
        fi
        record_state "$observed_state"

        if [ "$observed_state" = "qq_down" ]; then
            missing_since=0
            if [ $((now - last_restart)) -ge "$RESTART_BACKOFF_SECONDS" ]; then
                launch_qq
                last_restart="$now"
            fi
        elif [ "$observed_state" = "online" ]; then
            missing_since=0
        elif [ "$observed_state" = "login" ]; then
            # Login needs a human/credential flow. Keep QQ open but never restart-loop it.
            missing_since=0
        else
            [ "$missing_since" -eq 0 ] && missing_since="$now"
            if [ $((now - missing_since)) -ge "$START_GRACE_SECONDS" ] \
                    && [ $((now - last_restart)) -ge "$RESTART_BACKOFF_SECONDS" ]; then
                log_msg "pid=$pid alive but port 3001 absent for ${START_GRACE_SECONDS}s; cold restarting"
                cold_restarts=$((cold_restarts + 1))
                save_counters
                /system/bin/am force-stop "$QQ_PACKAGE" >/dev/null 2>&1
                sleep 2
                launch_qq cold_restart
                last_restart="$now"
                missing_since=0
            fi
        fi
        write_status "$now" "$pid" "$observed_port" "$observed_login"
        sleep "$CHECK_SECONDS"
    done
    log_msg "disabled"
}

if [ "${ONEBOT_WATCHDOG_SOURCE_ONLY:-0}" = "1" ]; then
    return 0 2>/dev/null || exit 0
fi

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
        [ -n "$old_pid" ] && kill "$old_pid" 2>/dev/null
        ;;
    status)
        load_counters
        old_pid="$(cat "$PID_FILE" 2>/dev/null)"
        if [ -f "$ENABLED" ] && [ -n "$old_pid" ] && kill -0 "$old_pid" 2>/dev/null; then
            live_state="$(sed -n 's/^state=//p' "$STATUS_FILE" 2>/dev/null | tail -n 1)"
            echo "enabled running pid=$old_pid qq_pid=$(qq_pid) state=${live_state:-unknown} port=$(port_ready && echo up || echo down) offline=$offline_events recovered=$recovery_events account_kicks=$account_kicks launches=$launch_requests cold_restarts=$cold_restarts"
        elif [ -f "$ENABLED" ]; then
            echo "enabled not-running"
            exit 1
        else
            echo "disabled"
            exit 1
        fi
        ;;
    snapshot)
        [ -f "$STATUS_FILE" ] && cat "$STATUS_FILE" || {
            echo "no status snapshot" >&2
            exit 1
        }
        ;;
    *)
        echo "usage: $0 {run|start|stop|status|snapshot}" >&2
        exit 2
        ;;
esac
