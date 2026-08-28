#!/system/bin/sh
# Read-only libfekit / remaining-maps inventory. Does not inject or change QQ.

export PATH=/data/data/com.termux/files/usr/bin:/system/bin:$PATH
QQ_PACKAGE=com.tencent.mobileqq
qq_pid="$(pidof "$QQ_PACKAGE" 2>/dev/null | awk '{print $1}')"

fekit_from_maps() {
    [ -n "$qq_pid" ] || return
    awk '/libfekit\.so$/ { print $NF; exit }' "/proc/$qq_pid/maps"
}

FEKIT="${1:-$(fekit_from_maps)}"
[ -n "$FEKIT" ] || FEKIT="$(find /data/app -name libfekit.so 2>/dev/null | grep mobileqq | head -n 1)"

echo "qq_pid=${qq_pid:-0}"
echo "fekit_path=${FEKIT:-missing}"
echo "captured_at_epoch=$(date +%s)"
echo "zygisk_memory_type=$(cat /data/adb/zygisksu/memory_type 2>/dev/null || echo unknown)"

if [ -n "$qq_pid" ]; then
    echo
    echo "== nameless executable maps =="
    awk '$2 ~ /x/ && (NF < 6 || $6 == "") { print }' "/proc/$qq_pid/maps"
    echo
    echo "== anonymous rwx maps =="
    awk '$2 ~ /rwx/ && (NF < 6 || $6 == "") { print }' "/proc/$qq_pid/maps"
fi

if [ -n "$FEKIT" ] && [ -r "$FEKIT" ]; then
    echo
    echo "== needed =="
    llvm-readelf -d "$FEKIT" 2>/dev/null | grep NEEDED || true
    echo
    echo "== libc imports of interest =="
    llvm-nm -D "$FEKIT" 2>/dev/null | grep -E ' U (open|openat|fopen|read|pread|syscall|dl_iterate_phdr|dlsym|dlopen|access|stat|fstat|readlink|prctl)(@|$)' || true
    echo
    echo "== detection strings =="
    strings "$FEKIT" | grep -E '^/proc|/proc/self/(maps|smaps|mountinfo|cmdline)|lsposed|zygisk|libriru|frida|\.magisk|libart' | sort -u
fi
