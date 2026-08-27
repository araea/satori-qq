#!/system/bin/sh
# KernelSU/Magisk service.d entry point.
WATCHDOG=/data/adb/onebot-qq/qq-onebot-watchdog.sh
[ -x "$WATCHDOG" ] && "$WATCHDOG" start
