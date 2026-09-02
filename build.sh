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

APK_STEALTH=$OUT/SatoriQQ.stealth.apk
APK_STEALTH_UNSIGNED=$OUT/satori-qq.stealth.unsigned.apk

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
rm -f $APK_UNSIGNED $OUT/satori-qq.aligned.apk
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

echo "== 6. stealth variant (no Xposed meta-data) =="
# DuckDetector's LSPosedPackageProbe flags any installed app whose manifest
# meta-data contains a key starting with "xposed". LSPosed's daemon resolves
# enabled modules purely via assets/xposed_init (ConfigManager.getModuleApkPath
# + ConfigFileManager.loadModule) and never re-checks meta-data, and the daemon
# does not even handle ACTION_PACKAGE_REPLACED — so this variant keeps loading
# once the module was enabled with the bootstrap APK. Enable first, then:
#   pm install -r -d build/SatoriQQ.stealth.apk
rm -f $APK_STEALTH
rm -rf $OUT/stealth && mkdir -p $OUT/stealth
cp $R/AndroidManifest.stealth.xml $OUT/stealth/AndroidManifest.xml
$AAPT package -f -M $OUT/stealth/AndroidManifest.xml -I $FRAMEWORK -A $R/assets -F $APK_STEALTH_UNSIGNED
( cd $OUT/dex && $AAPT add $APK_STEALTH_UNSIGNED classes.dex >/dev/null )
if [ -f $OUT/lib/arm64-v8a/libmapshide.so ]; then ( cd $OUT && $AAPT add $APK_STEALTH_UNSIGNED lib/arm64-v8a/libmapshide.so >/dev/null ); fi
$ZIPALIGN -f -p 4 $APK_STEALTH_UNSIGNED $OUT/satori-qq.stealth.aligned.apk
apksigner sign --ks $KS --ks-pass pass:satori123 --key-pass pass:satori123 \
  --out $APK_STEALTH $OUT/satori-qq.stealth.aligned.apk

echo "== 7. assert anti-detection surface =="
# Bootstrap APK must keep the module marker (vector/LSPosed registration);
# stealth APK must expose zero xposed meta-data keys to package scans.
norm_xposed=$($AAPT dump xmltree $APK AndroidManifest.xml | grep -c 'android:name(0x01010003)="xposed' || true)
stealth_xposed=$($AAPT dump xmltree $APK_STEALTH AndroidManifest.xml | grep -c 'android:name(0x01010003)="xposed' || true)
if [ "${norm_xposed:-0}" -lt 4 ]; then
  echo "   FAIL bootstrap APK lost xposed meta-data ($norm_xposed/4)"; exit 1
fi
if [ "${stealth_xposed:-0}" -ne 0 ]; then
  echo "   FAIL stealth APK still exposes xposed meta-data ($stealth_xposed)"; exit 1
fi
echo "   ok xposed meta-data — bootstrap: $norm_xposed, stealth: $stealth_xposed"
echo "== DONE =="
ls -la $APK $APK_STEALTH
