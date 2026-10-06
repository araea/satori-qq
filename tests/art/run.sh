#!/data/data/com.termux/files/usr/bin/bash
# 在真 ART（root 下的 app_process）上起一个离线的 SatoriHub，再用黑盒探针打它：
# Zygisk 把线上那份钉死在内存里，这条路不用重启手机就能验 Java 层的改动（D8 脱糖、类库差异、HTTP 与事件面）。
# 没有 QQ 内核，所以探针里 kernel_offline 的 6 个 503 是预期的 FAIL。需要先跑过 ./build.sh。
set -euo pipefail
R=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
ANDROID_JAR=/data/data/com.termux/files/home/android/platform/android-35/android.jar
OUT=${SATORI_QQ_OUT:-$R/build}/art
PORT=${1:-3039}
[ -d "$R/build/classes" ] || { echo "先跑一次 build.sh" >&2; exit 1; }

rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/tmp"
javac --release 17 -classpath "$ANDROID_JAR:$R/libs/json.jar:$R/build/classes" -encoding UTF-8 \
    -d "$OUT/classes" "$R/tests/art/Target.java"
find "$R/build/classes" "$OUT/classes" -name '*.class' > "$OUT/classlist.txt"
java -cp "$R/libs/r8.jar" com.android.tools.r8.D8 --release --min-api 26 \
    --lib "$ANDROID_JAR" --output "$OUT/dex" @"$OUT/classlist.txt"

su -c "CLASSPATH=$OUT/dex/classes.dex app_process -Xmx96m -Dsatori.qq.media_tmp=$OUT/tmp /system/bin Target $PORT 90" \
    > "$OUT/target.log" 2>&1 &
trap 'cat "$OUT/target.log"' EXIT
until curl -s -m2 -o /dev/null "http://127.0.0.1:$PORT/healthz"; do sleep 1; done
python3 "$R/tests/conformance.py" --base "http://127.0.0.1:$PORT" --token s3cret
