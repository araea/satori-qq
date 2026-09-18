#!/data/data/com.termux/files/usr/bin/bash
# 知弦的构建：Java dex → Zygisk 原生模块（自带 ART hook 引擎）→ APK + Magisk 模块包。
#
# 0.22.0 起不再依赖 libxposed：模块由 Zygisk Next 注入，hook 引擎（LSPlant + Dobby）静态链接在
# native/libsatori.so 里，dex 用 .incbin 内嵌进这个 .so，运行时用 InMemoryDexClassLoader 加载。
#
# NOTE: libs/r8.jar 与 libs/json.jar 被 gitignore。首次克隆后下载一次：
#   curl -fsSL -o libs/r8.jar https://maven.google.com/com/android/tools/r8/8.9.35/r8-8.9.35.jar
#   curl -fsSL -o libs/json.jar https://repo1.maven.org/maven2/org/json/json/20250517/json-20250517.jar
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
TP=$OUT/third_party
MODULE=$OUT/module

echo "== 1. javac =="
rm -rf $OUT/classes && mkdir -p $OUT/classes
find $R/src -name '*.java' > $OUT/sources.txt
javac -classpath $ANDROID_JAR:$R/libs/json.jar -source 8 -target 8 -encoding UTF-8 \
  -nowarn -d $OUT/classes @$OUT/sources.txt
echo "   compiled $(find $OUT/classes -name '*.class' | wc -l) classes"

echo "== 2. d8 -> dex =="
rm -rf $OUT/dex && mkdir -p $OUT/dex
find $OUT/classes -name '*.class' > $OUT/classlist.txt
java -cp $R8 com.android.tools.r8.D8 --release --min-api 26 \
  --lib $ANDROID_JAR --output $OUT/dex @$OUT/classlist.txt
echo "   dex: $(ls -la $OUT/dex/classes.dex | awk '{print $5}') bytes"

echo "== 2b. native 依赖（LSPlant / Dobby / libc++） =="
bash $R/native/build.sh

echo "== 2c. libsatori.so =="
# dex 内嵌进 .so 的 rodata：注入后进程已在应用沙箱里，读不了 /data/adb/modules 下的文件。
cat > $OUT/dex_blob.S <<EOF
	.section .rodata
	.global satori_dex_start
satori_dex_start:
	.incbin "$OUT/dex/classes.dex"
	.global satori_dex_end
satori_dex_end:
EOF
clang++ -c -o $OUT/dex_blob.o $OUT/dex_blob.S
clang++ -shared -fPIC -std=c++20 -O2 -fno-exceptions -fno-rtti -nostdinc++ \
  -isystem $TP/cxx/prefab/modules/cxx/include -I $R/native -I $TP/out/include \
  -o $OUT/libsatori.so $R/native/satori.cpp $OUT/dex_blob.o \
  $TP/out/libdobby.a $TP/out/liblsplant_static.a $TP/out/libdex_builder_static.a $TP/out/libcxx.a \
  -nostdlib++ -Wl,--no-undefined -L$TP/syslibs -llog -lz -ldl -lm
echo "   libsatori.so: $(ls -la $OUT/libsatori.so | awk '{print $5}') bytes"
# -Wl,--no-undefined 保证所有符号在链接期就对上了系统库：漏了归档的话链接会直接失败，
# 而不是等到 dlopen 时报 "cannot locate symbol"（那要烧一次重启才发现）。
STRAY=$(llvm-nm -D -u $OUT/libsatori.so | awk '{print $2}' | grep -E '^_ZN7(startop|lsplant|art)' || true)
if [ -n "$STRAY" ]; then
  echo "   FAIL static archives not fully linked:"; echo "$STRAY" | head; exit 1
fi
NEEDED=$(readelf -d $OUT/libsatori.so | grep NEEDED | grep -v 'liblog\|libz\|libdl\|libm\|libc\.so' || true)
if [ -n "$NEEDED" ]; then
  echo "   FAIL unexpected dependencies:"; echo "$NEEDED"; exit 1
fi

echo "== 3. aapt package =="
rm -f $APK_UNSIGNED $OUT/satori-qq.aligned.apk
$AAPT package -f -M $R/AndroidManifest.xml -I $FRAMEWORK -S $R/res -F $APK_UNSIGNED
( cd $OUT/dex && $AAPT add $APK_UNSIGNED classes.dex >/dev/null )

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

echo "== 6. Magisk 模块包 =="
VER_NAME=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' $R/AndroidManifest.xml | head -1)
VER_CODE=$(sed -n 's/.*android:versionCode="\([^"]*\)".*/\1/p' $R/AndroidManifest.xml | head -1)
rm -rf $MODULE && mkdir -p $MODULE/zygisk
cp $OUT/libsatori.so $MODULE/zygisk/arm64-v8a.so
cat > $MODULE/module.prop <<EOF
id=satori_qq
name=知弦
version=$VER_NAME
versionCode=$VER_CODE
author=araea
description=QQ 的 Satori v1 实现端（Zygisk 注入，自带 ART hook 引擎，不需要 LSPosed）。
EOF
# Zygisk Next 只认模块目录里这张表；缺它不会加载（踩过）。
printf 'name=com.tencent.mobileqq zygisk/arm64-v8a.so\n' > $MODULE/zn_modules.txt
rm -f $OUT/SatoriQQ-module.zip
# 刷机包要求 module.prop 在 zip 根目录（Magisk / KernelSU 都按根目录读）。
( cd $MODULE && zip -qr $OUT/SatoriQQ-module.zip . )
echo "   module zip: $(ls -la $OUT/SatoriQQ-module.zip | awk '{print $5}') bytes"
echo "== DONE =="
ls -la $APK $OUT/SatoriQQ-module.zip
