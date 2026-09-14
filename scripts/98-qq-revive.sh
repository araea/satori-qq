#!/system/bin/sh
# KernelSU / Magisk service.d：开机把 QQ 僵尸会话看守拉起来（看守以 root 运行）。
#
# 装法（三个文件，权限都给 755）：
#   /data/adb/service.d/98-qq-revive.sh          <- 本文件
#   /data/adb/satori-qq/qq-revive.sh             <- 仓库 scripts/qq-revive.sh
# 看守日志默认在 /data/local/tmp/qq-revive.log（root 启动时 HOME 不是 Termux 家目录）。
# 手动起一次：su -c "/data/adb/satori-qq/qq-revive.sh &" ；
# 停：kill "$(cat /data/local/tmp/qq-revive.pid)"。
BIN=/data/data/com.termux/files/usr/bin/bash
WATCH=/data/adb/satori-qq/qq-revive.sh
PIDFILE=/data/local/tmp/qq-revive.pid

[ -x "$BIN" ] || exit 0
[ -f "$WATCH" ] || exit 0
if [ -f "$PIDFILE" ]; then
    pid=$(cat "$PIDFILE" 2>/dev/null)
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && exit 0
fi
nohup "$BIN" "$WATCH" >/dev/null 2>&1 &
