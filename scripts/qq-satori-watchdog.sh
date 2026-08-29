#!/system/bin/sh
# Keep the QQ main process and satori-qq HTTP (127.0.0.1:3001) alive.
# Install under /data/adb/satori-qq and start from KernelSU/Magisk service.d.
# Never force-stop a live QQ session to recover the Satori port.

STATE_DIR="${SATORI_WATCHDOG_STATE_DIR:-/data/adb/satori-qq}"
ENABLED="$STATE_DIR/watchdog.enabled"
PID_FILE="$STATE_DIR/watchdog.pid"
LOG_FILE="$STATE_DIR/watchdog.log"
STATUS_FILE="$STATE_DIR/watchdog.status"
COUNTERS_FILE="$STATE_DIR/watchdog.counters"
EXPOSURE_AUDIT="$STATE_DIR/qq-satori-exposure-audit.sh"
INCIDENT_DIR="$STATE_DIR/incidents"
CGROUP_APPS_ROOT="${SATORI_CGROUP_APPS_ROOT:-/sys/fs/cgroup/apps}"
QQ_PACKAGE=com.tencent.mobileqq
SATORI_PORT_HEX=0BB9
CHECK_SECONDS=15
THAW_SECONDS=1
START_GRACE_SECONDS=90
RESTART_BACKOFF_SECONDS=300

mkdir -p "$STATE_DIR"
mkdir -p "$INCIDENT_DIR"

offline_events=0
recovery_events=0
launch_requests=0
cold_restarts=0
state_changes=0
watchdog_starts=0
account_kicks=0
last_account_kick_epoch=0
freeze_events=0
thaw_requests=0
thaw_failures=0

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
            freeze_events) freeze_events="$counter_value" ;;
            thaw_requests) thaw_requests="$counter_value" ;;
            thaw_failures) thaw_failures="$counter_value" ;;
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
        echo "freeze_events=$freeze_events"
        echo "thaw_requests=$thaw_requests"
        echo "thaw_failures=$thaw_failures"
    } > "$counters_tmp" && mv "$counters_tmp" "$COUNTERS_FILE"
}

write_status() {
    checked_at="$1"
    observed_pid="$2"
    observed_port="$3"
    observed_login="$4"
    status_tmp="$STATUS_FILE.$$"
    {
        echo "format_version=3"
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
        echo "last_account_kick_file=${last_account_kick_file:-}"
        echo "freeze_events=$freeze_events"
        echo "thaw_requests=$thaw_requests"
        echo "thaw_failures=$thaw_failures"
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
    if should_count_account_kick "$previous_state" "$observed_state"; then
        account_kicks=$((account_kicks + 1))
        last_account_kick_epoch="$(date +%s)"
        capture_account_kick "$previous_state" "$observed_state"
        log_msg "server account kick observed"
    fi
    save_counters
    log_msg "state ${previous_state:-unknown} -> $current_state"
    if [ -x "$EXPOSURE_AUDIT" ]; then
        "$EXPOSURE_AUDIT" snapshot --quiet "${previous_state:-unknown}-to-$current_state" >/dev/null 2>&1 &
    fi
}

capture_account_kick() {
    from_state="$1"
    to_state="$2"
    incident_name="account-kick-$(date '+%Y%m%d-%H%M%S').status"
    incident="$INCIDENT_DIR/$incident_name"
    incident_tmp="$incident.$$"
    incident_pid="$(qq_pid)"
    incident_start_epoch=0
    incident_minutes=-1
    if [ -n "$incident_pid" ] && [ -d "/proc/$incident_pid" ]; then
        incident_start_epoch="$(stat -c %Y "/proc/$incident_pid" 2>/dev/null || echo 0)"
        if [ "${incident_start_epoch:-0}" -gt 0 ] 2>/dev/null; then
            incident_minutes=$(( (last_account_kick_epoch - incident_start_epoch) / 60 ))
        fi
    fi
    incident_apk="$(dumpsys package com.satori.qq 2>/dev/null \
        | sed -n 's/.*versionName=//p' | head -n 1)"
    incident_activity="$(dumpsys activity activities 2>/dev/null \
        | grep -E 'topResumedActivity=ActivityRecord.*com\.tencent\.mobileqq/|mLastPausedActivity: ActivityRecord.*com\.tencent\.mobileqq/|Hist +#0: ActivityRecord.*com\.tencent\.mobileqq/' \
        | head -n 8 | tr '\n' ' ')"
    kick_since_epoch=$(( $(date +%s) - 240 ))
    kick_since="$(date -d "@$kick_since_epoch" '+%m-%d %H:%M:%S.000' 2>/dev/null)"
    incident_evidence=""
    if [ -n "$kick_since" ]; then
        incident_evidence="$(/system/bin/logcat -b events -d -T "$kick_since" 2>/dev/null \
            | grep -E 'mqq\.intent\.action\.(ACCOUNT_KICKED|KICK_TO_LOGIN)' \
            | tail -n 8 | tr '\n' ' ')"
        [ -n "$incident_evidence" ] || incident_evidence="$(/system/bin/logcat -d -T "$kick_since" 2>/dev/null \
            | grep -E 'mqq\.intent\.action\.(ACCOUNT_KICKED|KICK_TO_LOGIN)' \
            | tail -n 8 | tr '\n' ' ')"
    fi
    {
        echo "format_version=2"
        echo "captured_at_epoch=$last_account_kick_epoch"
        echo "transition=$from_state-to-$to_state"
        echo "account_kicks=$account_kicks"
        echo "qq_pid=${incident_pid:-0}"
        echo "qq_start_epoch=$incident_start_epoch"
        echo "minutes_since_start=$incident_minutes"
        echo "apk_version=${incident_apk:-unknown}"
        echo "activity=${incident_activity:-unknown}"
        echo "kick_evidence=${incident_evidence:-intent-observed-but-log-buffer-unavailable}"
        echo "action=human-login-required"
    } > "$incident_tmp" && chmod 0600 "$incident_tmp" && mv "$incident_tmp" "$incident"
    last_account_kick_file="$incident"
}

# When QQ comes online after a natural restart, capture running app_version / hooks
# without stopping the process. Path to Termux node is fixed; ignore failures.
capture_online_health() {
    node_bin=/data/data/com.termux/files/usr/bin/node
    health_js=/storage/emulated/0/Dev/satori-qq/tests/ws-health.js
    [ -x "$node_bin" ] && [ -f "$health_js" ] || return 0
    health_log="$STATE_DIR/online-health.log"
    {
        echo "==== $(date '+%F %T') qq_pid=$(qq_pid) ===="
        PATH=/data/data/com.termux/files/usr/bin:/system/bin:$PATH
        LD_LIBRARY_PATH=/data/data/com.termux/files/usr/lib:${LD_LIBRARY_PATH:-}
        export PATH LD_LIBRARY_PATH
        "$node_bin" "$health_js"
    } >> "$health_log" 2>&1 &
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
    awk -v p=":$SATORI_PORT_HEX" '$2 ~ p && $4 == "0A" { found=1 } END { exit !found }' \
        /proc/net/tcp /proc/net/tcp6 2>/dev/null
}

login_active() {
    # Splash/Chat as the current or last-paused QQ activity means the account is still
    # in. Matching any LoginActivity in the dump (including recents Hist #1) caused
    # cold-start false kicks: account_kicks=8 at 19:38 while login stayed true.
    # When Termux is in front after a real kick, mLastPausedActivity is Login*.
    dump="$(dumpsys activity activities 2>/dev/null)"
    echo "$dump" | grep -Eq 'topResumedActivity=ActivityRecord.*com\.tencent\.mobileqq/\.activity\.(SplashActivity|ChatActivity)|mLastPausedActivity: ActivityRecord.*com\.tencent\.mobileqq/\.activity\.(SplashActivity|ChatActivity)' && return 1
    echo "$dump" | grep -Eq 'topResumedActivity=ActivityRecord.*com\.tencent\.mobileqq/\.activity\.(LoginActivity|LoginPublicFragmentActivity|NotificationActivity|IdentificationFragmentActivity)|mLastPausedActivity: ActivityRecord.*com\.tencent\.mobileqq/\.activity\.(LoginActivity|LoginPublicFragmentActivity|NotificationActivity|IdentificationFragmentActivity)|mResumedActivity: ActivityRecord.*com\.tencent\.mobileqq/\.activity\.(LoginActivity|LoginPublicFragmentActivity|NotificationActivity|IdentificationFragmentActivity)' && return 0
    echo "$dump" | grep -Eq 'Hist +#0: ActivityRecord.*com\.tencent\.mobileqq/\.activity\.(LoginActivity|LoginPublicFragmentActivity|NotificationActivity|IdentificationFragmentActivity)'
}

should_count_account_kick() {
    prev="$1"
    now_state="$2"
    [ "$now_state" = "login" ] || return 1
    [ "$prev" = "online" ] || return 1
    now="$(date +%s)"
    last_act="${last_action:-none}"
    last_at="${last_action_at:-0}"
    if [ "$last_act" = "launch" ] || [ "$last_act" = "cold_restart" ]; then
        if [ "$last_at" -gt 0 ] && [ $((now - last_at)) -lt 180 ]; then
            log_msg "login after launch ignored for kick count"
            return 1
        fi
    fi
    if [ "${last_account_kick_epoch:-0}" -gt 0 ] && [ $((now - last_account_kick_epoch)) -lt 300 ]; then
        return 1
    fi
    recent_account_kick
}

recent_account_kick() {
    # Server kicks usually show ACCOUNT_KICKED in the events buffer. kick-7 only had
    # LoginActivity + KICK_TO_LOGIN in main logcat, so also search that action and the
    # main buffer. Manual logout still lacks these intents.
    kick_since_epoch=$(( $(date +%s) - 180 ))
    kick_since="$(date -d "@$kick_since_epoch" '+%m-%d %H:%M:%S.000' 2>/dev/null)"
    [ -n "$kick_since" ] || return 1
    if /system/bin/logcat -b events -d -T "$kick_since" 2>/dev/null \
            | grep -Eq 'mqq\.intent\.action\.(ACCOUNT_KICKED|KICK_TO_LOGIN)'; then
        return 0
    fi
    /system/bin/logcat -d -T "$kick_since" 2>/dev/null \
        | grep -Eq 'mqq\.intent\.action\.(ACCOUNT_KICKED|KICK_TO_LOGIN)'
}

launch_qq() {
    /system/bin/monkey -p "$QQ_PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    launch_requests=$((launch_requests + 1))
    last_action="${1:-launch}"
    last_action_at="$(date +%s)"
    save_counters
    log_msg "launch requested"
}

# ColorOS Hans freezes QQ in the background (OplusHansManager F enter).
# Sticky unfreeze does not stick; a 1s loop writes cgroup.freeze=0 on the UID
# and every pid_* child. Do not rewrite oom_score_adj: fekit can read it.
protect_qq() {
    pid="$1"
    [ -n "$pid" ] || return 0
    /system/bin/cmd activity unfreeze "$QQ_PACKAGE" >/dev/null 2>&1
    /system/bin/cmd activity unfreeze "${QQ_PACKAGE}:MSF" >/dev/null 2>&1
    /system/bin/am set-inactive "$QQ_PACKAGE" false >/dev/null 2>&1
    # QQ's own CoreService keeps the UI process at SVC (adj~450) instead of
    # cached (~905). Do not write oom_score_adj: fekit reads it.
    /system/bin/am startservice -n "$QQ_PACKAGE/.app.CoreService" >/dev/null 2>&1
    /system/bin/am startservice -n "$QQ_PACKAGE/.msf.service.MsfService" >/dev/null 2>&1
    dumpsys deviceidle whitelist +"$QQ_PACKAGE" >/dev/null 2>&1
    uid="$(awk '/^Uid:/{print $2; exit}' "/proc/$pid/status" 2>/dev/null)"
    thaw_uid_cgroup "$uid"
}

protect_qq_fast() {
    pid="$1"
    [ -n "$pid" ] || return 0
    /system/bin/cmd activity unfreeze "$QQ_PACKAGE" >/dev/null 2>&1
    /system/bin/cmd activity unfreeze "${QQ_PACKAGE}:MSF" >/dev/null 2>&1
    uid="$(awk '/^Uid:/{print $2; exit}' "/proc/$pid/status" 2>/dev/null)"
    thaw_uid_cgroup_write "$uid"
}

# ColorOS may report cmd activity unfreeze success while the UID-level cgroup v2
# freezer remains at 1, and it also freezes pid_* children independently.
thaw_uid_cgroup_write() {
    uid="$1"
    case "$uid" in ''|*[!0-9]*) return 1 ;; esac
    uid_dir="$CGROUP_APPS_ROOT/uid_$uid"
    [ -d "$uid_dir" ] || return 1
    thawed=0
    failed=0
    for freeze_file in "$uid_dir/cgroup.freeze" "$uid_dir"/pid_*/cgroup.freeze; do
        [ -f "$freeze_file" ] || continue
        [ "$(cat "$freeze_file" 2>/dev/null)" = "1" ] || continue
        if ! echo 0 > "$freeze_file" 2>/dev/null \
                || [ "$(cat "$freeze_file" 2>/dev/null)" != "0" ]; then
            failed=1
        else
            thawed=1
            thaw_requests=$((thaw_requests + 1))
        fi
    done
    [ "$failed" -eq 0 ] || return 2
    [ "$thawed" -eq 1 ]
}

thaw_uid_cgroup() {
    uid="$1"
    thaw_uid_cgroup_write "$uid"
    rc=$?
    [ "$rc" -eq 1 ] && return 0
    if [ "$rc" -eq 2 ]; then
        thaw_failures=$((thaw_failures + 1))
        freeze_events=$((freeze_events + 1))
        log_msg "uid=$uid cgroup thaw failed"
        save_counters
        return 0
    fi
    freeze_events=$((freeze_events + 1))
    log_msg "uid=$uid cgroup thawed"
    save_counters
}

fast_thaw_loop() {
    while [ -f "$ENABLED" ]; do
        protect_qq_fast "$(qq_pid)"
        sleep "$THAW_SECONDS"
    done
}

# BPM persist list already has Termux; QQ was missing and 25366 died at ~33 min
# with no ACCOUNT_KICKED / LMK. Adding the package is the ColorOS-side keep-alive.
ensure_bpm_persist() {
    xml=/data/oplus/os/bpm/bpm.xml
    [ -f "$xml" ] || return 0
    grep -q 'att="com.tencent.mobileqq"' "$xml" 2>/dev/null && return 0
    cp -f "$xml" "$STATE_DIR/bpm.xml.pre-satori" 2>/dev/null
    if sed -i 's|</gs>|<p att="com.tencent.mobileqq" /></gs>|' "$xml" 2>/dev/null \
            && grep -q 'att="com.tencent.mobileqq"' "$xml"; then
        log_msg "bpm persist added com.tencent.mobileqq"
    else
        log_msg "bpm persist add failed"
    fi
}

# Log hide-loop self-audit once per ok/bad transition. Never force-stop for this.
note_hide_loop() {
    maps_json="/storage/emulated/0/Android/data/com.tencent.mobileqq/files/qk_env_maps_main.json"
    [ -f "$maps_json" ] || return 0
    if grep -q '"loop_ok":1' "$maps_json"; then
        [ "${hide_loop_logged:-}" = ok ] && return 0
        hide_loop_logged=ok
        log_msg "hide loop ok"
    elif grep -q '"loop_ok":0' "$maps_json"; then
        [ "${hide_loop_logged:-}" = bad ] && return 0
        hide_loop_logged=bad
        log_msg "hide loop incomplete $(tr -d '\n' < "$maps_json")"
    fi
}

# ColorOS Athena o-stop(40) killed the QQ UI process at 21:30 while MSF was
# already in key_proc. Protect the main process the same way WeChat push is.
ensure_key_proc() {
    f=/data/oplus/os/bpm/key_proc.xml
    [ -f "$f" ] || return 0
    grep -qx 'com.tencent.mobileqq#com.tencent.mobileqq' "$f" 2>/dev/null && return 0
    cp -f "$f" "$STATE_DIR/key_proc.xml.pre-satori" 2>/dev/null
    if printf '%s\n' 'com.tencent.mobileqq#com.tencent.mobileqq' >> "$f" \
            && grep -qx 'com.tencent.mobileqq#com.tencent.mobileqq' "$f"; then
        log_msg "key_proc added com.tencent.mobileqq main"
    else
        log_msg "key_proc add failed"
    fi
}

run_loop() {
    old_pid="$(cat "$PID_FILE" 2>/dev/null)"
    if [ -n "$old_pid" ] && kill -0 "$old_pid" 2>/dev/null; then
        log_msg "already running pid=$old_pid"
        return 0
    fi
    echo $$ > "$PID_FILE"
    thaw_loop_pid=0
    trap 'kill "$thaw_loop_pid" 2>/dev/null; rm -f "$PID_FILE"; log_msg "stopped"; exit 0' TERM INT EXIT
    load_counters
    watchdog_starts=$((watchdog_starts + 1))
    save_counters
    current_state="$(sed -n 's/^state=//p' "$STATUS_FILE" 2>/dev/null | tail -n 1)"
    case "$current_state" in online|login|qq_down|port_missing) ;; *) current_state="" ;; esac
    previous_state="$current_state"
    last_action=none
    last_action_at=0
    last_seen_qq_pid=""
    last_account_kick_file="$(ls -t "$INCIDENT_DIR"/account-kick-*.status 2>/dev/null | head -n 1)"
    hide_loop_logged=""
    log_msg "started pid=$$"
    ensure_bpm_persist
    ensure_key_proc

    while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 5; done

    thaw_loop_pid=0
    fast_thaw_loop &
    thaw_loop_pid=$!
    log_msg "fast thaw loop pid=$thaw_loop_pid"

    missing_since=0
    last_restart=0
    while [ -f "$ENABLED" ]; do
        now="$(date +%s)"
        pid="$(qq_pid)"
        protect_qq "$pid"
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
        note_hide_loop
        if [ "$observed_state" = "online" ] && [ -n "$pid" ]; then
            if [ "$pid" != "$last_seen_qq_pid" ]; then
                capture_online_health
            fi
            last_seen_qq_pid="$pid"
        else
            last_seen_qq_pid=""
        fi

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
            # port_missing: QQ is alive but Satori HTTP is down (frozen, old
            # process before module load, or the hub crashed). Never force-stop:
            # that drops a live login. Thaw + CoreService already ran above.
            if [ "$missing_since" -eq 0 ]; then
                missing_since="$now"
                log_msg "pid=$pid alive but port 3001 down; waiting (no force-stop)"
            fi
        fi
        write_status "$now" "$pid" "$observed_port" "$observed_login"
        sleep "$CHECK_SECONDS"
    done
    kill "$thaw_loop_pid" 2>/dev/null
    log_msg "disabled"
}

if [ "${SATORI_WATCHDOG_SOURCE_ONLY:-0}" = "1" ]; then
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
        if [ -n "$old_pid" ]; then
            kill "$old_pid" 2>/dev/null
            sleep 1
            kill -9 "$old_pid" 2>/dev/null
        fi
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
