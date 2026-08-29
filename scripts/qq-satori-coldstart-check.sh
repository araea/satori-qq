#!/system/bin/sh
# Compare installed APK vs the running satori-qq process. Does not stop QQ or touch scope.
# Exit 0 = running version matches APK (hooks may still be zero if intercepts missed).
# Exit 2 = still on an older process (wait for natural restart).
# Exit 1 = WS/login unhealthy.

export PATH=/data/data/com.termux/files/usr/bin:/system/bin:$PATH
export LD_LIBRARY_PATH=/data/data/com.termux/files/usr/lib:${LD_LIBRARY_PATH:-}
NODE=/data/data/com.termux/files/usr/bin/node
HEALTH=/storage/emulated/0/Dev/satori-qq/tests/ws-health.js

apk_version="$(dumpsys package com.satori.qq 2>/dev/null \
    | sed -n 's/.*versionName=//p' | head -n 1)"
apk_version="${apk_version:-unknown}"
qq_pid="$(pidof com.tencent.mobileqq 2>/dev/null | awk '{print $1}')"
echo "apk_version=$apk_version"
echo "qq_pid=${qq_pid:-0}"

if [ -z "$qq_pid" ]; then
    echo "running_version=none"
    echo "result=qq_down"
    exit 1
fi

if [ ! -x "$NODE" ] || [ ! -f "$HEALTH" ]; then
    echo "result=ws-health-missing"
    exit 1
fi

summary="$("$NODE" "$HEALTH" 2>/dev/null)" || {
    echo "result=ws-unhealthy"
    exit 1
}
echo "ws_health=$summary"

running="$(printf '%s\n' "$summary" | sed -n 's/.*"version":"\([^"]*\)".*/\1/p')"
online="$(printf '%s\n' "$summary" | grep -q '"online":true' && echo true || echo false)"
login="$(printf '%s\n' "$summary" | grep -q '"login":true' && echo true || echo false)"
echo "running_version=${running:-unknown}"
echo "login=$login"
echo "online=$online"

if [ "$login" != true ] || [ "$online" != true ]; then
    echo "result=not-logged-in"
    exit 1
fi
if [ -n "$running" ] && [ "$running" = "$apk_version" ]; then
    if printf '%s\n' "$summary" | grep -q '"loop_ok":1'; then
        echo "hide_loop=ok"
        echo "result=loaded"
        exit 0
    fi
    echo "hide_loop=incomplete"
    echo "result=hide-incomplete"
    exit 3
fi
echo "result=waiting-natural-restart"
exit 2
