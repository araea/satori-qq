#!/system/bin/sh
# KernelSU / Magisk service.d：开机把 QQ 僵尸会话看守拉起来（看守以 root 运行）。
#
# 装法（两个文件，权限都给 755）：
#   /data/adb/service.d/98-qq-revive.sh          <- 本文件
#   /data/adb/satori-qq/qq-revive.sh             <- 仓库 scripts/qq-revive.sh
# 日志与 pid 默认在 /data/adb/satori-qq/qq-revive.log 与同名 .pid（普通应用读不到）；
# 若 HOME 指向别处（Termux 里手跑）则跟随 HOME。
# 手动起一次：su -c "/data/adb/satori-qq/qq-revive.sh &" ；
# 停：kill "$(cat /data/adb/satori-qq/qq-revive.pid)"。
BIN=/data/data/com.termux/files/usr/bin/bash
WATCH=/data/adb/satori-qq/qq-revive.sh
PIDFILE=/data/adb/satori-qq/qq-revive.pid

[ -x "$BIN" ] || exit 0
[ -f "$WATCH" ] || exit 0
if [ -f "$PIDFILE" ]; then
    pid=$(cat "$PIDFILE" 2>/dev/null)
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && exit 0
fi
nohup "$BIN" "$WATCH" >/dev/null 2>&1 &
