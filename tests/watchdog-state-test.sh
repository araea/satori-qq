#!/bin/sh
set -eu

repo_dir="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
test_state_dir="$(mktemp -d)"
trap 'rm -rf "$test_state_dir"' EXIT INT TERM

ONEBOT_WATCHDOG_STATE_DIR="$test_state_dir"
ONEBOT_WATCHDOG_SOURCE_ONLY=1
ONEBOT_CGROUP_APPS_ROOT="$test_state_dir/cgroup-apps"
export ONEBOT_WATCHDOG_STATE_DIR ONEBOT_WATCHDOG_SOURCE_ONLY ONEBOT_CGROUP_APPS_ROOT
. "$repo_dir/scripts/qq-onebot-watchdog.sh"

assert_login_state() {
    expected="$1"
    activity_dump="$2"
    dumpsys() { printf '%s\n' "$activity_dump"; }
    actual=no
    login_active && actual=yes
    [ "$actual" = "$expected" ] || {
        echo "expected login=$expected, got $actual" >&2
        exit 1
    }
}

assert_login_state yes '* Hist  #0: ActivityRecord{1 u0 com.tencent.mobileqq/.activity.LoginActivity t1}'
assert_login_state yes '* Hist  #0: ActivityRecord{1 u0 com.tencent.mobileqq/.activity.LoginPublicFragmentActivity t1}'
assert_login_state yes 'topResumedActivity=ActivityRecord{1 u0 com.tencent.mobileqq/.activity.NotificationActivity t1}'
assert_login_state yes "$(printf '%s\n' \
    'topResumedActivity=ActivityRecord{1 u0 com.termux/.app.TermuxActivity t1}' \
    'mLastPausedActivity: ActivityRecord{1 u0 com.tencent.mobileqq/.activity.LoginActivity t1}')"
assert_login_state no '* Hist  #0: ActivityRecord{1 u0 com.tencent.mobileqq/.activity.SplashActivity t1}'
# Buried recents LoginActivity must not override a live Splash session.
assert_login_state no '* Hist  #1: ActivityRecord{1 u0 com.tencent.mobileqq/.activity.LoginActivity t1}'
assert_login_state no "$(printf '%s\n' \
    'topResumedActivity=ActivityRecord{1 u0 com.termux/.app.TermuxActivity t1}' \
    'mLastPausedActivity: ActivityRecord{1 u0 com.tencent.mobileqq/.activity.SplashActivity t1}' \
    '* Hist  #0: ActivityRecord{1 u0 com.tencent.mobileqq/.activity.LoginActivity t2}')"

recent_account_kick() { return 0; }
save_counters() { :; }
log_msg() { :; }
current_state=online
previous_state=online
account_kicks=0
last_account_kick_epoch=0
last_action=none
last_action_at=0
record_state login
[ "$current_state" = login ]
[ "$account_kicks" -eq 1 ]
[ "$last_account_kick_epoch" -gt 0 ]
[ -f "$last_account_kick_file" ]
grep -q '^action=human-login-required$' "$last_account_kick_file"

# Cold start / process death -> LoginActivity is not a new server kick.
account_kicks=1
last_account_kick_epoch=1
current_state=qq_down
record_state login
[ "$account_kicks" -eq 1 ]

# Launch grace: LoginActivity flash within 180s of our own monkey/cold start.
account_kicks=1
last_account_kick_epoch=1
current_state=online
last_action=launch
last_action_at="$(date +%s)"
record_state login
[ "$account_kicks" -eq 1 ]

mkdir -p "$ONEBOT_CGROUP_APPS_ROOT/uid_123/pid_999"
printf '1\n' > "$ONEBOT_CGROUP_APPS_ROOT/uid_123/cgroup.freeze"
printf '1\n' > "$ONEBOT_CGROUP_APPS_ROOT/uid_123/pid_999/cgroup.freeze"
freeze_events=0
thaw_requests=0
thaw_failures=0
thaw_uid_cgroup 123
[ "$(cat "$ONEBOT_CGROUP_APPS_ROOT/uid_123/cgroup.freeze")" = "0" ]
[ "$(cat "$ONEBOT_CGROUP_APPS_ROOT/uid_123/pid_999/cgroup.freeze")" = "0" ]
[ "$freeze_events" -eq 1 ]
[ "$thaw_requests" -eq 2 ]
[ "$thaw_failures" -eq 0 ]

echo watchdog-state-tests=ok
