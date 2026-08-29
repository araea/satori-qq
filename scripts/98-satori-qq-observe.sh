#!/system/bin/sh
# KernelSU/Magisk service.d: keep session observe running after reboot.
OBSERVE=/data/adb/satori-qq/qq-satori-observe.sh
[ -x "$OBSERVE" ] && "$OBSERVE" start
