#!/system/bin/sh
# 知弦 · QQ 常驻看守（qqguard）
#
# 目标：让 QQ（com.tencent.mobileqq）在一台已 root 的 Android 上尽可能不被冻结、不被断网、
# 不被回收；异常死亡能自动恢复；同时用户随时可以明确停止保活并正常关闭 QQ。
#
# 设计原则：
#   1. 只用系统与 KernelSU/ReSukiSU 的原生能力。以 /system/bin/sh 运行，工具取 /system/bin
#      （toybox），不依赖 Termux、LSPosed、Zygisk。
#   2. 尽量不改系统全局行为：Doze 只加白名单（不动 deviceidle 全局开关）、不碰全局 LMK、
#      不长期强占 oom_score_adj、不用永久亮屏。要「关掉整个 Doze」是另一件事，交给可选的
#      scripts/99-no-doze.sh。
#   3. 保活与用户主动停止用持久化的 ARMED / PAUSED 区分。绝不无条件拉起：只有 ARMED 且判定为
#      「崩溃或被系统回收」才恢复；PAUSED 一律不碰 QQ；用户在系统里手动强停（stopped=true）
#      默认被尊重并转入 PAUSED。
#   4. 只冻结不死：被冻住只写 freezer cgroup 解冻（有冷却），不因为冻结就强杀重启。
#   5. 重启有冷却、每小时预算、指数退避与连续崩溃保护，避免无限快速重启把账号送进风控。
#   6. 低功耗：默认 30 秒一轮，只在小睡分片之间检查状态，状态不变不写日志。
#
# 命令：
#   qqguard start|arm        进入 ARMED：应用系统配置并启动 watchdog（开机由 service.sh 调 boot）
#   qqguard stop|pause       进入 PAUSED：先暂停 watchdog，再允许你正常关闭 QQ（不杀 QQ）
#   qqguard kill             进入 PAUSED 并强停 QQ（停止保活并关闭 QQ）
#   qqguard restart          重启 watchdog（保持当前 ARMED/PAUSED）
#   qqguard toggle           在 ARMED / PAUSED 之间切换（KernelSU 模块 action 按钮用）
#   qqguard status [--json]  打印当前状态
#   qqguard apply            只应用 Doze/AppOps/网络/待机桶配置
#   qqguard once|check       只跑一轮探针，不动 QQ（排查用）
#   qqguard log [N]          看最近 N 行日志
#   qqguard run              watchdog 主循环（内部使用）
#   qqguard boot             开机恢复上次状态（service.sh 调用）
#
# 环境变量（也可写进 $DIR/guard.conf，脚本会 source；环境变量优先）：
#   QQGUARD_INTERVAL、QQGUARD_MIN_GAP、QQGUARD_MAX_RESTARTS、QQGUARD_CRASH_LIMIT、
#   QQGUARD_CRASH_WINDOW、QQGUARD_BACKOFF_BASE/MAX、QQGUARD_GRACE、QQGUARD_RESPECT_FORCE_STOP、
#   QQGUARD_THAW_COOLDOWN、QQGUARD_OEM=1 时额外尝试厂商 AppOps。
set -u
export PATH=/system/bin:/system/xbin:/data/adb/ksu/bin:/data/adb/magisk:${PATH:-}

SELF=$(readlink -f "$0" 2>/dev/null || echo "$0")

# ---- 路径与默认值 ---------------------------------------------------------
DIR=${QQGUARD_DIR:-/data/adb/satori-qq}
CONF=${QQGUARD_CONF:-$DIR/guard.conf}
[ -r "$CONF" ] && . "$CONF"

PKG=${QQGUARD_PKG:-com.tencent.mobileqq}
PORT=${QQGUARD_PORT:-3001}
INTERVAL=${QQGUARD_INTERVAL:-30}
MIN_GAP=${QQGUARD_MIN_GAP:-120}
MAX_RESTARTS=${QQGUARD_MAX_RESTARTS:-4}
RESTART_WINDOW=${QQGUARD_RESTART_WINDOW:-3600}
CRASH_LIMIT=${QQGUARD_CRASH_LIMIT:-3}
CRASH_WINDOW=${QQGUARD_CRASH_WINDOW:-600}
BACKOFF_BASE=${QQGUARD_BACKOFF_BASE:-60}
BACKOFF_MAX=${QQGUARD_BACKOFF_MAX:-900}
STABLE_WINDOW=${QQGUARD_STABLE_WINDOW:-600}
GRACE=${QQGUARD_GRACE:-180}
RECOVER_WAIT=${QQGUARD_RECOVER_WAIT:-60}
UNRESPONSIVE_LIMIT=${QQGUARD_UNRESPONSIVE_LIMIT:-6}
THAW_COOLDOWN=${QQGUARD_THAW_COOLDOWN:-5}
OFFLINE_LIMIT=${QQGUARD_OFFLINE_LIMIT:-10}
OFFLINE_RESTART_WINDOW=${QQGUARD_OFFLINE_RESTART_WINDOW:-1800}
RESPECT_FORCE_STOP=${QQGUARD_RESPECT_FORCE_STOP:-1}
OEM=${QQGUARD_OEM:-0}
PING_HOST=${QQGUARD_PING_HOST:-223.5.5.5}
MAX_LOG_BYTES=${QQGUARD_MAX_LOG_BYTES:-2000000}
# 系统 sleep：显式走外部二进制，别落到 mksh 的 sleep 内建上（内建在收到 TERM 后可能卡住）。
SLEEP=${QQGUARD_SLEEP:-/system/bin/sleep}

CGROUP_APPS=${QQGUARD_CGROUP_APPS:-/sys/fs/cgroup/apps}
STATE=$DIR/guard.state
PIDFILE=$DIR/guard.pid
LOG=$DIR/guard.log
RESTARTS=$DIR/guard.restarts
LOCKDIR=$DIR/guard.lock
VERSION=1

# ---- 基础工具 -------------------------------------------------------------
log() {
    mkdir -p "$DIR" 2>/dev/null
    printf '%s %s\n' "$(date '+%F %T')" "$*" >> "$LOG" 2>/dev/null
    chmod 0600 "$LOG" 2>/dev/null
    local size
    size=$(stat -c %s "$LOG" 2>/dev/null || echo 0)
    if [ "$size" -gt "$MAX_LOG_BYTES" ]; then
        tail -n 400 "$LOG" > "$LOG.tmp" 2>/dev/null && mv "$LOG.tmp" "$LOG"
    fi
}

nap() {  # 小睡分片，期间能及时看到 PAUSED
    local left=${1:-1} step=5
    [ "$left" -lt "$step" ] && step=$left
    while [ "$left" -gt 0 ]; do
        [ "$(cur_mode)" = "ARMED" ] || return 1
        "$SLEEP" "$step" 2>/dev/null || return 1
        fast_thaw
        left=$((left - step))
        [ "$left" -lt "$step" ] && [ "$left" -gt 0 ] && step=$left
    done
    return 0
}

now() { date +%s; }
is_num() { case "${1:-}" in ''|*[!0-9]*) return 1 ;; *) return 0 ;; esac; }

lock() {
    local n=0
    while ! mkdir "$LOCKDIR" 2>/dev/null; do
        n=$((n + 1))
        if [ "$n" -ge 40 ]; then
            rmdir "$LOCKDIR" 2>/dev/null
            mkdir "$LOCKDIR" 2>/dev/null && break
        fi
        "$SLEEP" 0.25 2>/dev/null || "$SLEEP" 1 2>/dev/null
    done
}
unlock() { rmdir "$LOCKDIR" 2>/dev/null; }

# ---- 持久化状态 -----------------------------------------------------------
mkdir -p "$DIR" 2>/dev/null
chmod 0700 "$DIR" 2>/dev/null

state_get() {
    [ -r "$STATE" ] || return 0
    awk -F= -v k="$1" '$1 == k { v = $2 } END { print v }' "$STATE" 2>/dev/null
}

state_set() {
    [ -f "$STATE" ] || : > "$STATE"
    local tmp="$STATE.tmp"
    { grep -v "^$1=" "$STATE" 2>/dev/null; printf '%s=%s\n' "$1" "$2"; } > "$tmp" 2>/dev/null \
        && mv "$tmp" "$STATE" 2>/dev/null
    chmod 0600 "$STATE" 2>/dev/null
}

state_init() {
    [ -s "$STATE" ] && return 0
    : > "$STATE"
    state_set MODE PAUSED
    state_set REASON fresh-install
    state_set SINCE "$(now)"
    state_set ARMS 0
    state_set PAUSES 0
    state_set CONSEC_FAIL 0
    state_set LAST_RESTART 0
    state_set LAST_ONLINE 0
}

num() { local v; v=$(state_get "$1"); is_num "$v" || v=0; printf '%s' "$v"; }
cur_mode() { local m; m=$(state_get MODE); [ -z "$m" ] && m=PAUSED; printf '%s' "$m"; }

set_mode() {  # set_mode ARMED|PAUSED <reason>
    state_set MODE "$1"
    state_set REASON "$2"
    state_set SINCE "$(now)"
    if [ "$1" = "ARMED" ]; then
        state_set ARMS "$(( $(num ARMS) + 1 ))"
    else
        state_set PAUSES "$(( $(num PAUSES) + 1 ))"
    fi
}

daemon_pid() { [ -r "$PIDFILE" ] && cat "$PIDFILE" 2>/dev/null; }

daemon_running() {
    local p
    p=$(daemon_pid)
    [ -n "$p" ] && kill -0 "$p" 2>/dev/null
}

# ---- QQ 进程与系统状态 ----------------------------------------------------
qq_main_pids() { pgrep -f "^$PKG$" 2>/dev/null; }
qq_any_pids()  { pgrep -f "^$PKG" 2>/dev/null; }
qq_uid() { stat -c %u "/data/data/$PKG" 2>/dev/null; }

# 用户手动「强行停止」会把 package 的 stopped 置位；系统自己回收（LMK/冻死）不会。
qq_user_stopped() {
    dumpsys package "$PKG" 2>/dev/null | grep -m1 "[[:space:]]User 0: " | grep -q "stopped=true"
}

device_online() {
    ping -c 1 -W 3 "$PING_HOST" >/dev/null 2>&1 && return 0
    ping -c 1 -w 3 "$PING_HOST" >/dev/null 2>&1
}

# 被冻住的判据：AOSP 按 pid 冻（pid_*/cgroup.freeze=1），ColorOS 的 OplusHansManager 按 uid 冻
# （uid_*/cgroup.freeze=1）；wchan=do_freezer_trap 是最后一道旁证。
qq_frozen() {  # qq_frozen <pid> <uid>
    local pid=$1 uid=$2 d
    case "$uid" in
        ''|*[!0-9]*) : ;;
        *)
            if [ -r "$CGROUP_APPS/uid_$uid/cgroup.freeze" ] \
                && [ "$(cat "$CGROUP_APPS/uid_$uid/cgroup.freeze" 2>/dev/null)" = "1" ]; then
                return 0
            fi
            for d in "$CGROUP_APPS/uid_$uid"/pid_*; do
                [ -r "$d/cgroup.freeze" ] || continue
                [ "$(cat "$d/cgroup.freeze" 2>/dev/null)" = "1" ] && return 0
            done
            ;;
    esac
    [ -n "$pid" ] && [ "$(cat "/proc/$pid/wchan" 2>/dev/null)" = "do_freezer_trap" ]
}

# 精确解冻：写 QQ 自己的 freezer cgroup 置 0。不拉起、不重启、不动前台、不打断用户。
thaw_qq() {  # thaw_qq <uid> <为什么>
    local uid=$1 why=$2 d n=0
    case "$uid" in ''|*[!0-9]*) uid="" ;; esac
    log "thaw: QQ 被冻住（$why），写 freezer cgroup 解冻"
    if [ -n "$uid" ]; then
        if [ -w "$CGROUP_APPS/uid_$uid/cgroup.freeze" ]; then
            printf 0 > "$CGROUP_APPS/uid_$uid/cgroup.freeze" 2>/dev/null && n=$((n + 1))
        fi
        for d in "$CGROUP_APPS/uid_$uid"/pid_*; do
            [ -w "$d/cgroup.freeze" ] || continue
            printf 0 > "$d/cgroup.freeze" 2>/dev/null && n=$((n + 1))
        done
    fi
    [ "$n" -eq 0 ] && log "thaw: 一个 freezer cgroup 都没写成（uid=${uid:-?}，路径 $CGROUP_APPS）"
    return 0
}

# Heavy health/login checks retain their 30s cadence and restart budgets. Between them,
# only inspect QQ's cgroup files every 5s: a 60s thaw cooldown exceeds ambient freshness.
fast_thaw() {
    local uid
    [ "$(cur_mode)" = "ARMED" ] || return 0
    [ $(( $(now) - ${last_thaw:-0} )) -ge "$THAW_COOLDOWN" ] || return 0
    uid=$(qq_uid)
    if qq_frozen "" "$uid"; then
        last_thaw=$(now)
        thaw_qq "$uid" "fast cgroup check"
    fi
}

# ---- /healthz 探针 --------------------------------------------------------
healthz() { curl -s --max-time 6 --noproxy '*' "http://127.0.0.1:$PORT/healthz" 2>/dev/null; }
field() { printf '%s' "$1" | grep -o "\"$2\":[^,}]*" | head -1 | cut -d: -f2- | tr -d '"'; }

# ---- 系统配置应用 ---------------------------------------------------------
apply_config() {
    local uid op applied="" failed="" bucket
    uid=$(qq_uid)
    if [ -z "$uid" ]; then
        log "apply: 拿不到 $PKG 的 uid，跳过"
        return 1
    fi
    # 1) Doze 白名单（只加白名单，不动 deviceidle 全局开关）
    if dumpsys deviceidle whitelist 2>/dev/null | grep -q "$PKG"; then
        applied="$applied doze"
    elif dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1; then
        applied="$applied doze+"
    else
        failed="$failed doze"
    fi
    # 2) 必要 AppOps：后台运行、任意后台、唤醒锁、前台服务
    for op in RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND WAKE_LOCK START_FOREGROUND; do
        if appops set "$PKG" "$op" allow >/dev/null 2>&1; then
            applied="$applied $op"
        else
            failed="$failed $op"
        fi
    done
    # 3) App Standby Bucket 尽量 active（Doze 白名单下通常是 EXEMPTED=5，比 active=10 更高）
    bucket=$(am get-standby-bucket "$PKG" 2>/dev/null)
    if [ "$bucket" = "5" ] || [ "$bucket" = "10" ]; then
        applied="$applied bucket=$bucket"
    elif am set-standby-bucket "$PKG" active >/dev/null 2>&1; then
        applied="$applied bucket->active"
    else
        failed="$failed bucket"
    fi
    # 4) Data Saver 白名单：锁屏/省流量模式下仍允许 QQ 后台联网
    if cmd netpolicy add restrict-background-whitelist "$uid" >/dev/null 2>&1; then
        applied="$applied netpolicy"
    else
        failed="$failed netpolicy"
    fi
    # 5) 厂商自启动/关联启动（best-effort，只在 QGGUARD_OEM=1 时尝试）
    if [ "$OEM" = "1" ]; then
        for op in AUTO_START BACKGROUND_START_ACTIVITY START_ACTIVITIES_FROM_BACKGROUND ALLOW_START_FOREGROUND_SERVICE; do
            appops set "$PKG" "$op" allow >/dev/null 2>&1 && applied="$applied $op"
        done
    fi
    log "apply: uid=$uid 已应用:${applied:- 无}${failed:+ 失败:$failed}"
}

# ---- 重启预算 / 退避 / 连续崩溃保护 --------------------------------------
restart_history() {
    [ -r "$RESTARTS" ] || return 0
    awk -v t="$(now)" -v w="$RESTART_WINDOW" '$1 > t - w { print $1 }' "$RESTARTS" 2>/dev/null
}

record_restart() {
    local keep
    keep=$(restart_history)
    { printf '%s\n' "$keep"; now; } | grep -v '^$' | sort -n > "$RESTARTS.tmp" 2>/dev/null
    mv "$RESTARTS.tmp" "$RESTARTS" 2>/dev/null
    chmod 0600 "$RESTARTS" 2>/dev/null
}

last_restart() { num LAST_RESTART; }

restarts_in_window() {
    [ -r "$RESTARTS" ] || { printf 0; return; }
    awk -v t="$(now)" -v w="$CRASH_WINDOW" '$1 > t - w { n++ } END { print n + 0 }' "$RESTARTS" 2>/dev/null
}

compute_backoff() {
    local n b i
    n=$(num CONSEC_FAIL)
    b=$BACKOFF_BASE; i=0
    while [ "$i" -lt "$n" ] && [ "$b" -lt "$BACKOFF_MAX" ]; do b=$((b * 2)); i=$((i + 1)); done
    [ "$b" -gt "$BACKOFF_MAX" ] && b=$BACKOFF_MAX
    printf '%s' "$b"
}

# 能不能重启。能则返回 0；不能则把原因写进 CAN_REASON。
can_restart() {
    local lr gap backoff n waited
    lr=$(last_restart)
    backoff=$(compute_backoff)
    gap=$MIN_GAP
    [ "$backoff" -gt "$gap" ] && gap=$backoff
    if [ "$lr" -gt 0 ]; then
        waited=$(( $(now) - lr ))
        if [ "$waited" -lt "$gap" ]; then
            CAN_REASON="冷却中（还差 $((gap - waited))s，退避 ${backoff}s）"
            return 1
        fi
    fi
    n=$(restart_history | grep -c . 2>/dev/null | tr -d ' ')
    is_num "$n" || n=0
    if [ "$n" -ge "$MAX_RESTARTS" ]; then
        CAN_REASON="每小时预算用尽（$n/$MAX_RESTARTS）"
        return 1
    fi
    return 0
}

# ---- 拉起 / 暂停 ----------------------------------------------------------
start_qq() {  # start_qq <原因> [force]
    local why=$1 force=${2:-}
    if [ "$force" = "force" ]; then
        log "restart: $why（强停后拉起）"
        am force-stop "$PKG" >/dev/null 2>&1
        "$SLEEP" 3 2>/dev/null
    else
        log "restart: $why"
    fi
    monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    record_restart
    state_set LAST_RESTART "$(now)"
    state_set CONSEC_FAIL "$(( $(num CONSEC_FAIL) + 1 ))"
    "$SLEEP" 8 2>/dev/null
    if [ -z "$(qq_main_pids)" ]; then
        log "restart: 拉起 8 秒后还没看到主进程，交给下一轮判据"
    else
        log "restart: 主进程已出现"
    fi
    "$SLEEP" "$RECOVER_WAIT" 2>/dev/null
}

do_pause() {  # do_pause <reason>
    local p
    lock
    set_mode PAUSED "$1"
    p=$(daemon_pid)
    unlock
    log "pause: 转入 PAUSED（原因：$1）"
    if [ -n "$p" ] && [ "$p" != "$$" ] && kill -0 "$p" 2>/dev/null; then
        kill "$p" 2>/dev/null
        "$SLEEP" 2 2>/dev/null
        kill -0 "$p" 2>/dev/null && kill -9 "$p" 2>/dev/null
    fi
    [ "$p" != "$$" ] && rm -f "$PIDFILE" 2>/dev/null
    return 0
}

# ---- watchdog 主循环 ------------------------------------------------------
run() {
    lock
    if daemon_running; then
        unlock
        log "watchdog: 已在运行 pid=$(daemon_pid)，本次不重复启动"
        return 0
    fi
    echo $$ > "$PIDFILE"
    chmod 0600 "$PIDFILE" 2>/dev/null
    unlock
    trap 'rm -f "$PIDFILE" 2>/dev/null; log "watchdog: 收到停止信号，退出"; exit 0' TERM INT HUP
    log "watchdog start pid=$$ interval=${INTERVAL}s gap=${MIN_GAP}s max=${MAX_RESTARTS}/h crash=${CRASH_LIMIT}/${CRASH_WINDOW}s version=$VERSION"

    unresponsive=0
    offline=0
    last_thaw=0
    while :; do
        [ "$(cur_mode)" = "ARMED" ] || { log "watchdog: MODE=$(cur_mode)，退出"; break; }
        tick
        nap "$INTERVAL" || break
    done
    rm -f "$PIDFILE" 2>/dev/null
    log "watchdog stop pid=$$"
}

tick() {
    local pids pid uid hz online lr lo
    pids=$(qq_main_pids)

    # 用户在系统设置里强停：默认尊重并转入 PAUSED，绝不跟用户抢。
    if [ "$RESPECT_FORCE_STOP" = "1" ] && qq_user_stopped; then
        do_pause "user-force-stop"
        return
    fi

    # 1) 进程不在 —— 崩溃或被系统回收
    if [ -z "$pids" ]; then
        unresponsive=0
        offline=0
        lr=$(last_restart)
        if [ "$lr" -gt 0 ] && [ $(( $(now) - lr )) -lt "$GRACE" ]; then
            log "dead: 进程不在，但距上次拉起 $(( $(now) - lr ))s < ${GRACE}s，等它启动"
            return
        fi
        if ! device_online; then
            log "skip: 进程不在但设备无网，先不动"
            return
        fi
        if [ "$(restarts_in_window)" -ge "$CRASH_LIMIT" ]; then
            log "quarantine: ${CRASH_WINDOW}s 内已拉起 $(restarts_in_window) 次，判定连续崩溃"
            do_pause "crash-loop"
            return
        fi
        if ! can_restart; then
            log "skip: 进程不在但$CAN_REASON"
            return
        fi
        start_qq "进程不在（崩溃或被回收）"
        return
    fi

    pid=$(printf '%s\n' "$pids" | head -1)
    uid=$(qq_uid)

    # 2) Don't spend the HTTP timeout waiting on a process we already know is frozen.
    fast_thaw
    hz=$(healthz)
    online=$(field "$hz" online)
    [ "$online" = "true" ] && state_set LAST_ONLINE "$(now)"

    # 3) 进程在但被冻住：只解冻，不因为冻结就强杀重启；同一冷却期内不重复写。
    if qq_frozen "$pid" "$uid"; then
        if [ $(( $(now) - last_thaw )) -ge "$THAW_COOLDOWN" ]; then
            last_thaw=$(now)
            thaw_qq "$uid" "wchan/cgroup.freeze"
        fi
        return
    fi

    # 4) 稳定够久：重置连续失败与退避
    lr=$(last_restart)
    if [ "$lr" -gt 0 ] && [ $(( $(now) - lr )) -gt "$STABLE_WINDOW" ] \
            && [ "$(num CONSEC_FAIL)" != "0" ]; then
        state_set CONSEC_FAIL 0
        log "recover: 已稳定运行超过 ${STABLE_WINDOW}s，重置连续失败与退避"
    fi

    # 5) 进程在、没被冻，但服务不回话 —— 判定挂死，按预算强拉起
    if [ -z "$hz" ]; then
        offline=0
        unresponsive=$((unresponsive + 1))
        log "unresponsive: /healthz 无响应 ${unresponsive}/${UNRESPONSIVE_LIMIT}"
        if [ "$unresponsive" -ge "$UNRESPONSIVE_LIMIT" ]; then
            unresponsive=0
            if ! can_restart; then
                log "skip: 端口一直无响应但$CAN_REASON"
                return
            fi
            start_qq "端口持续无响应" force
        fi
        return
    fi
    unresponsive=0

    if [ "$online" = "true" ]; then
        offline=0
        return
    fi

    # 6) 进程在、服务在，但不在线（掉登录）。只在「最近在线过」时才尝试自动登录，
    #    否则开机等扫码会被误判成故障、反复重启。
    offline=$((offline + 1))
    log "offline: /healthz online=false ${offline}/${OFFLINE_LIMIT}"
    if [ "$offline" -lt "$OFFLINE_LIMIT" ]; then
        return
    fi
    offline=0
    lo=$(num LAST_ONLINE)
    if [ "$lo" -eq 0 ] || [ $(( $(now) - lo )) -gt "$OFFLINE_RESTART_WINDOW" ]; then
        log "skip: 已离线但最近没在线过，不自动重启，等人工登录"
        return
    fi
    if ! device_online; then
        log "skip: 已离线但设备无网，先不动"
        return
    fi
    if [ "$(restarts_in_window)" -ge "$CRASH_LIMIT" ]; then
        log "quarantine: 反复掉线，${CRASH_WINDOW}s 内已拉起 $(restarts_in_window) 次"
        do_pause "crash-loop"
        return
    fi
    if ! can_restart; then
        log "skip: 已离线但$CAN_REASON"
        return
    fi
    start_qq "在线掉登录，触发自动登录"
}

# ---- 状态输出 -------------------------------------------------------------
sanitize() { printf '%s' "$1" | tr -d '"\\' | tr '\n' ' '; }

print_status_json() {
    local mode p running since reason qpid uid online frozen restarts backoff lr lo
    mode=$(cur_mode)
    p=$(daemon_pid); running=false
    daemon_running && running=true
    since=$(num SINCE)
    reason=$(state_get REASON)
    qpid=$(qq_main_pids | head -1)
    uid=$(qq_uid)
    frozen=false
    [ -n "$qpid" ] && qq_frozen "$qpid" "$uid" && frozen=true
    online=false
    [ "$(field "$(healthz)" online)" = "true" ] && online=true
    restarts=$(restart_history | grep -c . 2>/dev/null | tr -d ' '); is_num "$restarts" || restarts=0
    backoff=$(compute_backoff)
    lr=$(last_restart); lo=$(num LAST_ONLINE)
    printf '{"mode":"%s","version":%s,"running":%s,"pid":%s,"since":%s,"reason":"%s",' \
        "$mode" "$VERSION" "$running" "${p:-0}" "$since" "$(sanitize "$reason")"
    printf '"qq_alive":%s,"qq_pid":%s,"qq_frozen":%s,"online":%s,' \
        "$([ -n "$qpid" ] && echo true || echo false)" "${qpid:-0}" "$frozen" "$online"
    printf '"restarts_1h":%s,"consec_fail":%s,"backoff_s":%s,"last_restart":%s,"last_online":%s,' \
        "$restarts" "$(num CONSEC_FAIL)" "$backoff" "$lr" "$lo"
    printf '"log":"%s"}\n' "$(sanitize "$LOG")"
}

print_status() {
    local mode p qpid uid online frozen restarts backoff lr dead
    mode=$(cur_mode); p=$(daemon_pid)
    qpid=$(qq_main_pids | head -1); uid=$(qq_uid)
    frozen=no
    [ -n "$qpid" ] && qq_frozen "$qpid" "$uid" && frozen=yes
    online=no
    [ "$(field "$(healthz)" online)" = "true" ] && online=yes
    restarts=$(restart_history | grep -c . 2>/dev/null | tr -d ' '); is_num "$restarts" || restarts=0
    backoff=$(compute_backoff); lr=$(last_restart)
    dead=""
    if [ -n "$p" ] && ! kill -0 "$p" 2>/dev/null; then dead=" 已死"; fi
    echo "qqguard: $mode  (daemon pid ${p:-none}${dead})"
    echo "  原因      $(state_get REASON)  ·  自 epoch $(num SINCE)"
    echo "  累计      ARMED $(num ARMS) 次 / PAUSED $(num PAUSES) 次"
    echo "  QQ        pid=${qpid:-none}  冻结=$frozen  在线=$online"
    echo "  重启      最近1h=$restarts 次  连续失败=$(num CONSEC_FAIL)  当前退避=${backoff}s  上次=$lr"
    echo "  日志      $LOG"
}

# ---- 命令 -----------------------------------------------------------------
cmd_start() {
    state_init
    lock
    set_mode ARMED "start"
    unlock
    log "start: 进入 ARMED"
    apply_config
    if daemon_running; then
        log "start: watchdog 已在运行 pid=$(daemon_pid)"
        return 0
    fi
    setsid "$SELF" run </dev/null >>"$LOG" 2>&1 &
    "$SLEEP" 1 2>/dev/null
    if daemon_running; then
        log "start: watchdog pid=$(daemon_pid)"
        return 0
    fi
    log "start: watchdog 没能起来，直接后台兜底一次"
    "$SELF" run </dev/null >>"$LOG" 2>&1 &
}

cmd_stop() {
    state_init
    log "stop: 用户请求停止保活（不关闭 QQ）"
    do_pause "user-stop"
}

cmd_kill() {
    state_init
    log "kill: 停止保活并关闭 QQ"
    do_pause "user-stop-kill"
    "$SLEEP" 1 2>/dev/null
    am force-stop "$PKG" >/dev/null 2>&1
    "$SLEEP" 2 2>/dev/null
    if [ -n "$(qq_any_pids)" ]; then
        log "kill: force-stop 后仍有 QQ 进程，补 kill -9"
        for x in $(qq_any_pids); do kill -9 "$x" 2>/dev/null; done
    fi
    log "kill: QQ 已停止"
}

cmd_restart() {
    state_init
    local m
    m=$(cur_mode)
    log "restart: 重启 watchdog（保持 $m）"
    do_pause "restart"
    if [ "$m" = "ARMED" ]; then
        cmd_start
    fi
}

cmd_toggle() {
    state_init
    if [ "$(cur_mode)" = "ARMED" ]; then cmd_stop; else cmd_start; fi
    print_status
}

cmd_boot() {
    local n=0 m
    while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ] && [ "$n" -lt 120 ]; do
        "$SLEEP" 5 2>/dev/null; n=$((n + 1))
    done
    "$SLEEP" 15 2>/dev/null
    state_init
    m=$(cur_mode)
    log "boot: 恢复上次状态 MODE=$m"
    if [ "$m" = "ARMED" ]; then
        apply_config
        setsid "$SELF" run </dev/null >>"$LOG" 2>&1 &
    fi
}

cmd_check() {
    state_init
    echo "mode=$(cur_mode) pid=$(daemon_pid) running=$(daemon_running && echo yes || echo no)"
    echo "qq_main=$(qq_main_pids | tr '\n' ' ')"
    echo "qq_uid=$(qq_uid)"
    echo "user_stopped=$(qq_user_stopped && echo yes || echo no)"
    echo "restarts_1h=$(restart_history | grep -c .)"
    echo "restarts_window=$(restarts_in_window) (limit $CRASH_LIMIT/$CRASH_WINDOW)"
    echo "consec_fail=$(num CONSEC_FAIL) backoff=$(compute_backoff) last_restart=$(last_restart)"
    echo "device_online=$(device_online && echo yes || echo no)"
    echo "healthz=$(healthz)"
}

cmd_log() { tail -n "${1:-40}" "$LOG" 2>/dev/null; }

usage() { sed -n '2,45p' "$SELF" | grep '^#' | sed 's/^# \{0,1\}//'; }

main() {
    state_init
    case "${1:-status}" in
        start|arm)      cmd_start ;;
        stop|pause)     cmd_stop ;;
        kill)           cmd_kill ;;
        restart)        cmd_restart ;;
        toggle)         cmd_toggle ;;
        boot)           cmd_boot ;;
        run)            run ;;
        once|check)     cmd_check ;;
        apply)          apply_config ;;
        status)
            if [ "${2:-}" = "--json" ]; then print_status_json; else print_status; fi ;;
        log)            cmd_log "${2:-40}" ;;
        version)        echo "$VERSION" ;;
        help|-h|--help) usage ;;
        *) echo "qqguard: 未知命令 $1" >&2; usage >&2; exit 2 ;;
    esac
}

# QQGUARD_LIB_ONLY=1 时只加载函数，不执行命令（tests/qqguard-test.sh 用）。
[ "${QQGUARD_LIB_ONLY:-0}" = "1" ] || main "$@"
