#!/data/data/com.termux/files/usr/bin/bash
# NOTE: libs/r8.jar is gitignored. After a fresh clone, download it once:
#   curl -fsSL -o libs/r8.jar https://maven.google.com/com/android/tools/r8/8.9.35/r8-8.9.35.jar
set -e
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
R=${SATORI_QQ_ROOT:-$SCRIPT_DIR}
ANDROID_JAR=/data/data/com.termux/files/home/android/platform/android-35/android.jar
BT=/data/data/com.termux/files/home/android/android-sdk-tools/build-tools
AAPT=$BT/aapt
ZIPALIGN=$BT/zipalign
R8=$R/libs/r8.jar
FRAMEWORK=/system/framework/framework-res.apk
KS=$R/build/satori.keystore
OUT=${SATORI_QQ_OUT:-$R/build}
APK_UNSIGNED=$OUT/satori-qq.unsigned.apk
APK=$OUT/SatoriQQ.apk

echo "== 1. javac =="
rm -rf $OUT/classes && mkdir -p $OUT/classes
find $R/src $R/stubs -name '*.java' > $OUT/sources.txt
javac -classpath $ANDROID_JAR -source 8 -target 8 -encoding UTF-8 \
  -nowarn -d $OUT/classes @$OUT/sources.txt
echo "   compiled $(find $OUT/classes -name '*.class' | wc -l) classes"

echo "== 2. d8 -> dex =="
rm -rf $OUT/dex && mkdir -p $OUT/dex
# Exclude the compile-only Xposed API stubs (de/robv/**) from the dex — the framework
# provides them at runtime and refuses modules that bundle the Xposed API classes.
find $OUT/classes -name '*.class' | grep -v '/de/robv/' > $OUT/classlist.txt
java -cp $R8 com.android.tools.r8.D8 --release --min-api 26 \
  --lib $ANDROID_JAR --output $OUT/dex @$OUT/classlist.txt
echo "   dex: $(ls -la $OUT/dex/classes.dex | awk '{print $5}') bytes"


echo "== 2b. native maps-hider .so =="
CLANG=/data/data/com.termux/files/usr/bin/clang
if [ -x "$CLANG" ]; then
  mkdir -p $OUT/lib/arm64-v8a
  $CLANG --target=aarch64-linux-android24 -fPIC -shared -Os \
    -o $OUT/lib/arm64-v8a/libmapshide.so $R/native/mapshide.c -L/system/lib64 -lc -llog -ldl \
    && echo "   built libmapshide.so" || echo "   WARN native build failed (module still works, maps_hide off)"
else
  echo "   clang not found, skipping native (maps_hide unavailable)"
fi

echo "== 3. aapt package =="
$AAPT package -f -M $R/AndroidManifest.xml -I $FRAMEWORK -A $R/assets -F $APK_UNSIGNED
( cd $OUT/dex && $AAPT add $APK_UNSIGNED classes.dex >/dev/null )
if [ -f $OUT/lib/arm64-v8a/libmapshide.so ]; then ( cd $OUT && $AAPT add $APK_UNSIGNED lib/arm64-v8a/libmapshide.so >/dev/null ) && echo "   packaged libmapshide.so"; fi

echo "== 4. keystore (generate once) =="
if [ ! -f $KS ]; then
  keytool -genkeypair -keystore $KS -alias satori -storepass satori123 -keypass satori123 \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=SatoriQQ" >/dev/null 2>&1
  echo "   generated keystore"
fi

echo "== 5. zipalign + sign =="
rm -f $APK
$ZIPALIGN -f -p 4 $APK_UNSIGNED $OUT/satori-qq.aligned.apk
apksigner sign --ks $KS --ks-pass pass:satori123 --key-pass pass:satori123 \
  --out $APK $OUT/satori-qq.aligned.apk
echo "== DONE =="
ls -la $APK
