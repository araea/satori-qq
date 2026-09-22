#!/data/data/com.termux/files/usr/bin/bash
# 构建单独安装的真机界面测试包（与模块同签名）；不进模块 APK，也不进 build/SatoriQQ.apk。
#
# 测试类只用框架 API + 反射，不引用模块内部类，所以这里不需要编译 src/。
set -euo pipefail
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
OUT="$ROOT/build/design-tests"
ANDROID_JAR=/data/data/com.termux/files/home/android/platform/android-35/android.jar
BT=/data/data/com.termux/files/home/android/android-sdk-tools/build-tools
R8="$ROOT/libs/r8.jar"
KS="$ROOT/build/satori.keystore"

if [ ! -f "$R8" ]; then
  echo "missing $R8 — 见 build.sh 顶部的下载命令" >&2
  exit 1
fi
if [ ! -f "$KS" ]; then
  echo "missing $KS — 先跑一次 build.sh 生成签名" >&2
  exit 1
fi

rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex"
echo "== 1. javac =="
javac -classpath "$ANDROID_JAR" -source 8 -target 8 -encoding UTF-8 -nowarn \
  -d "$OUT/classes" "$ROOT/tests/ui/DesignSmoke.java"

echo "== 2. d8 =="
find "$OUT/classes" -name '*.class' > "$OUT/classlist.txt"
java -cp "$R8" com.android.tools.r8.D8 --release --min-api 26 \
  --lib "$ANDROID_JAR" --output "$OUT/dex" @"$OUT/classlist.txt"

echo "== 3. package + sign =="
"$BT/aapt" package -f -M "$ROOT/tests/ui/AndroidManifest.xml" \
  -I /system/framework/framework-res.apk -F "$OUT/unsigned.apk"
( cd "$OUT/dex" && "$BT/aapt" add "$OUT/unsigned.apk" classes.dex >/dev/null )
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
apksigner sign --ks "$KS" --ks-pass pass:satori123 --key-pass pass:satori123 \
  --out "$OUT/DesignTests.apk" "$OUT/aligned.apk"
ls -la "$OUT/DesignTests.apk"
