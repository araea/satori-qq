#!/data/data/com.termux/files/usr/bin/bash
# qqguard 状态机与重启预算的纯逻辑测试。
#
# 只 source 脚本里的函数（QQGUARD_LIB_ONLY=1），全部状态落在临时目录，不碰系统、不碰 QQ、
# 不需要 root。真机行为（解冻、强停、开机恢复）仍靠现场验证。
set -u
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=$(mktemp -d "${TMPDIR:-/tmp}/qqguard-test.XXXXXX")
trap 'rm -rf "$TMP"' EXIT

export QQGUARD_DIR="$TMP"
export QQGUARD_LIB_ONLY=1
export QQGUARD_MAX_RESTARTS=4
export QQGUARD_MIN_GAP=120
export QQGUARD_BACKOFF_BASE=60
export QQGUARD_BACKOFF_MAX=900
export QQGUARD_RESTART_WINDOW=3600
export QQGUARD_CRASH_WINDOW=600
# shellcheck source=/dev/null
. "$ROOT/scripts/qqguard.sh"

fails=0
check() { # check <what> <want> <got>
    if [ "$2" = "$3" ]; then
        printf '   ok   %s\n' "$1"
    else
        printf '   FAIL %s: want <%s> got <%s>\n' "$1" "$2" "$3"
        fails=$((fails + 1))
    fi
}

# 初始状态
state_init
check "fresh mode" PAUSED "$(cur_mode)"
check "fresh reason" fresh-install "$(state_get REASON)"

# ARMED / PAUSED 计数
set_mode ARMED first
check "armed mode" ARMED "$(cur_mode)"
check "arms count" 1 "$(num ARMS)"
set_mode PAUSED user-stop
check "paused mode" PAUSED "$(cur_mode)"
check "pauses count" 1 "$(num PAUSES)"
check "reason" user-stop "$(state_get REASON)"

# 退避：base 翻倍、封顶
state_set CONSEC_FAIL 0
check "backoff base" 60 "$(compute_backoff)"
state_set CONSEC_FAIL 3
check "backoff x8" 480 "$(compute_backoff)"
state_set CONSEC_FAIL 20
check "backoff cap" 900 "$(compute_backoff)"

# 冷却：刚重启过不能立刻再重启
state_set CONSEC_FAIL 0
state_set LAST_RESTART "$(now)"
if can_restart; then
    check "cooldown blocks" blocked ok
else
    case "$CAN_REASON" in
        *冷却*) check "cooldown blocks" blocked blocked ;;
        *)      check "cooldown blocks" blocked "$CAN_REASON" ;;
    esac
fi

# 冷却过后，写满每小时预算就应该被预算挡住
: > "$RESTARTS"
i=0
while [ "$i" -lt 4 ]; do printf '%s\n' "$(( $(now) - i ))"; i=$((i + 1)); done > "$RESTARTS"
state_set LAST_RESTART "$(( $(now) - 1000 ))"
check "restarts in window" 4 "$(restart_history | grep -c .)"
if can_restart; then
    check "budget blocks" blocked ok
else
    case "$CAN_REASON" in
        *预算*) check "budget blocks" blocked blocked ;;
        *)      check "budget blocks" blocked "$CAN_REASON" ;;
    esac
fi

# 预算没满、冷却也过了就能重启
: > "$RESTARTS"
printf '%s\n' "$(( $(now) - 30 ))" > "$RESTARTS"
state_set LAST_RESTART "$(( $(now) - 1000 ))"
if can_restart; then check "allows restart" ok ok; else check "allows restart" ok "$CAN_REASON"; fi

# 连续崩溃窗口计数
: > "$RESTARTS"
printf '%s\n' "$(( $(now) - 10 ))" "$(( $(now) - 20 ))" "$(( $(now) - 700 ))" > "$RESTARTS"
check "crash window count" 2 "$(restarts_in_window)"

# Fast thaw is independent of HTTP probing, and still respects PAUSED and cooldown.
CGROUP_APPS="$TMP/cgroup"
mkdir -p "$CGROUP_APPS/uid_123/pid_456"
qq_uid() { printf '123'; }
printf 1 > "$CGROUP_APPS/uid_123/cgroup.freeze"
printf 1 > "$CGROUP_APPS/uid_123/pid_456/cgroup.freeze"
last_thaw=0
set_mode PAUSED test
fast_thaw
check "paused does not thaw" 1 "$(cat "$CGROUP_APPS/uid_123/cgroup.freeze")"
set_mode ARMED test
fast_thaw
check "uid thawed" 0 "$(cat "$CGROUP_APPS/uid_123/cgroup.freeze")"
check "pid thawed" 0 "$(cat "$CGROUP_APPS/uid_123/pid_456/cgroup.freeze")"
printf 1 > "$CGROUP_APPS/uid_123/pid_456/cgroup.freeze"
fast_thaw
check "thaw cooldown respected" 1 "$(cat "$CGROUP_APPS/uid_123/pid_456/cgroup.freeze")"
last_thaw=$(( $(now) - THAW_COOLDOWN - 1 ))
fast_thaw
check "refreeze recovered" 0 "$(cat "$CGROUP_APPS/uid_123/pid_456/cgroup.freeze")"

if [ "$fails" -ne 0 ]; then
    echo "qqguard-test: $fails failed"
    exit 1
fi
echo "qqguard-test OK"
