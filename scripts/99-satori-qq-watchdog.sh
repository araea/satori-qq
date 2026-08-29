#!/system/bin/sh
# KernelSU/Magisk service.d entry point.
WATCHDOG=/data/adb/satori-qq/qq-satori-watchdog.sh
[ -x "$WATCHDOG" ] && "$WATCHDOG" start
