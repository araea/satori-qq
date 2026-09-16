#!/data/data/com.termux/files/usr/bin/bash
# Build the separately installed device UI test runner; does not ship in the module APK.
set -euo pipefail
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
OUT="$ROOT/build/design-tests"
ANDROID_JAR=/data/data/com.termux/files/home/android/platform/android-35/android.jar
AAPT=/data/data/com.termux/files/home/android/android-sdk-tools/build-tools/aapt
ZIPALIGN=/data/data/com.termux/files/home/android/android-sdk-tools/build-tools/zipalign
mkdir -p "$OUT/classes" "$OUT/dex"
javac -classpath "$ANDROID_JAR" -source 8 -target 8 -encoding UTF-8 -nowarn -d "$OUT/classes" "$ROOT/tests/ui/DesignSmoke.java"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
java -cp "$ROOT/libs/r8.jar" com.android.tools.r8.D8 --release --min-api 26 --lib "$ANDROID_JAR" --output "$OUT/dex" @"$OUT/classes.txt"
"$AAPT" package -f -M "$ROOT/tests/ui/AndroidManifest.xml" -I /system/framework/framework-res.apk -F "$OUT/unsigned.apk"
(cd "$OUT/dex" && "$AAPT" add "$OUT/unsigned.apk" classes.dex)
"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
apksigner sign --ks "$ROOT/build/satori.keystore" --ks-pass pass:satori123 --key-pass pass:satori123 --out "$OUT/DesignTests.apk" "$OUT/aligned.apk"
