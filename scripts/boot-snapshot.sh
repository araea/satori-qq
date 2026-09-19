#!/system/bin/sh
# 知弦 0.23.0 首启快照：等 QQ 起来 + 模块引导跑完，把 logcat 与 /healthz 落盘，
# 供重启后核对（重启会掐掉 Termux 里的会话，所以必须由 root 侧落盘）。
OUT=/data/adb/satori-qq/boot-snapshot.log
LOG=/data/adb/satori-qq/boot-logcat.txt
: > "$OUT"

i=0
while [ $i -lt 60 ]; do
    pidof com.tencent.mobileqq >/dev/null 2>&1 && break
    sleep 5
    i=$((i + 1))
done
echo "--- qq process seen after $((i * 5))s at $(date)" >> "$OUT"

# 等引导线程出结论
i=0
while [ $i -lt 45 ]; do
    if logcat -d -s SatoriZygisk | grep -q "host classloader captured\|Application never appeared\|cannot attach bootstrap"; then
        break
    fi
    sleep 2
    i=$((i + 1))
done
sleep 20

{
    echo
    echo "=== znctl dump-zn ==="
    /data/adb/ksu/bin/znctl dump-zn 2>&1 | head -14
    echo
    echo "=== logcat SatoriZygisk / Q.Kernel ==="
    logcat -d -s SatoriZygisk Q.Kernel 2>&1 | tail -250
} >> "$OUT"

for i in $(seq 1 45); do
    body=$(curl -s --max-time 3 http://127.0.0.1:3001/healthz 2>/dev/null)
    if [ -n "$body" ]; then
        {
            echo
            echo "=== healthz at $(date) ==="
            echo "$body"
        } >> "$OUT"
        break
    fi
    sleep 4
done

logcat -d -s SatoriZygisk Q.Kernel > "$LOG" 2>&1
chmod 644 "$OUT" "$LOG"
