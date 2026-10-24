#!/data/data/com.termux/files/usr/bin/bash
# 知弦的构建：Java dex → Zygisk 原生模块（纯 JNI 层，无 ART hook 引擎）→ 模块包（带管理页 webroot/）。
# 版本只在 module.prop 里写一处（另有 SatoriHub.APP_VERSION，构建时核对两者一致）。
#
# 0.23.0 起不再依赖 libxposed，也不再带 ART hook 引擎：模块由 Zygisk Next 注入，全部能力走 JNI 层
# （引导用 ActivityThread.currentApplication 轮询，回包用 RegisterNatives 换 native_onSendSSOReply）。
#
# NOTE: libs/r8.jar 与 libs/json.jar 被 gitignore。首次克隆后下载一次：
#   curl -fsSL -o libs/r8.jar https://maven.google.com/com/android/tools/r8/8.9.35/r8-8.9.35.jar
#   curl -fsSL -o libs/json.jar https://repo1.maven.org/maven2/org/json/json/20250517/json-20250517.jar
set -e
SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
R=${SATORI_QQ_ROOT:-$SCRIPT_DIR}
ANDROID_JAR=/data/data/com.termux/files/home/android/platform/android-35/android.jar
R8=$R/libs/r8.jar
OUT=${SATORI_QQ_OUT:-$R/build}
MODULE=$OUT/module

VER_NAME=$(sed -n 's/^version=//p' $R/module.prop)
VER_CODE=$(sed -n 's/^versionCode=//p' $R/module.prop)
grep -q "APP_VERSION = \"$VER_NAME\"" $R/src/com/satori/qq/core/SatoriHub.java \
  || { echo "SatoriHub.APP_VERSION 与 module.prop 的 version=$VER_NAME 不一致" >&2; exit 1; }

echo "== 1. javac =="
rm -rf $OUT/classes && mkdir -p $OUT/classes
find $R/src -name '*.java' > $OUT/sources.txt
javac --release 17 -classpath $ANDROID_JAR:$R/libs/json.jar -encoding UTF-8 \
  -Xlint:all,-deprecation,-serial,-rawtypes -d $OUT/classes @$OUT/sources.txt
echo "   compiled $(find $OUT/classes -name '*.class' | wc -l) classes"

echo "== 2. d8 -> dex =="
rm -rf $OUT/module-dex && mkdir -p $OUT/module-dex
find $OUT/classes -name '*.class' > $OUT/module-classlist.txt
java -cp $R8 com.android.tools.r8.D8 --release --min-api 26 \
  --lib $ANDROID_JAR --output $OUT/module-dex @$OUT/module-classlist.txt
echo "   module dex: $(ls -la $OUT/module-dex/classes.dex | awk '{print $5}') bytes"

echo "== 2c. libsatori.so（纯 JNI 层，无第三方依赖） =="
# dex 内嵌进 .so 的 rodata：注入后进程已在应用沙箱里，读不了 /data/adb/modules 下的文件。
cat > $OUT/dex_blob.S <<EOF
	.section .rodata
	.global satori_dex_start
satori_dex_start:
	.incbin "$OUT/module-dex/classes.dex"
	.global satori_dex_end
satori_dex_end:
EOF
clang++ -c -o $OUT/dex_blob.o $OUT/dex_blob.S
clang++ -shared -fPIC -std=c++20 -O2 -fno-exceptions -fno-rtti -fno-threadsafe-statics -nostdinc++ -nostdlib++ \
  -I $R/native \
  -o $OUT/libsatori.so $R/native/satori.cpp $OUT/dex_blob.o \
  -Wl,--no-undefined -llog -ldl -lm
echo "   libsatori.so: $(ls -la $OUT/libsatori.so | awk '{print $5}') bytes"
# -Wl,--no-undefined 保证所有符号在链接期就对上了系统库：漏了什么会直接链接失败，
# 而不是等到 dlopen 时报 "cannot locate symbol"（那要烧一次重启才发现）。
NEEDED=$(readelf -d $OUT/libsatori.so | grep NEEDED | grep -v 'liblog\|libdl\|libm\|libc\.so' || true)
if [ -n "$NEEDED" ]; then
  echo "   FAIL unexpected dependencies:"; echo "$NEEDED"; exit 1
fi

echo "== 3. 模块包 =="
rm -rf $MODULE && mkdir -p $MODULE/zygisk
cp $OUT/libsatori.so $MODULE/zygisk/arm64-v8a.so
cp $R/module.prop $R/customize.sh $R/ctl.sh $MODULE/
# 模块管理页：KernelSU / APatch 在模块列表里直接打开 webroot/index.html。
cp -r $R/webroot $MODULE/webroot
# Zygisk Next 只认模块目录里这张表；缺它不会加载（踩过）。
printf 'name=com.tencent.mobileqq zygisk/arm64-v8a.so\n' > $MODULE/zn_modules.txt

# 常驻看守：模块自带的 service.sh 在 KernelSU late_start 阶段恢复 qqguard。
# 状态与日志放 /data/adb/satori-qq（模块之外），升级模块不会把 ARMED/PAUSED 冲掉。
cp $R/scripts/qqguard.sh $MODULE/qqguard.sh
chmod 0755 $MODULE/qqguard.sh
cat > $MODULE/service.sh <<'EOF'
#!/system/bin/sh
# KernelSU / ReSukiSU service：开机恢复 qqguard 状态（独立 watchdog，root 运行）。
MODDIR=${0%/*}
GUARD=/data/adb/satori-qq/qqguard.sh
mkdir -p /data/adb/satori-qq
cp -f "$MODDIR/qqguard.sh" "$GUARD" 2>/dev/null
chmod 0755 "$GUARD" 2>/dev/null
[ -x "$GUARD" ] || exit 0
setsid "$GUARD" boot </dev/null >>/data/adb/satori-qq/boot-guard.log 2>&1 &
EOF
cat > $MODULE/action.sh <<'EOF'
#!/system/bin/sh
# KernelSU 模块管理页的「操作」按钮：切换 Guard ON/OFF。
GUARD=/data/adb/satori-qq/qqguard.sh
[ -x "$GUARD" ] || GUARD="${0%/*}/qqguard.sh"
"$GUARD" toggle
EOF
cat > $MODULE/uninstall.sh <<'EOF'
#!/system/bin/sh
# 卸载模块时先暂停 watchdog，别留下一个还在动 QQ 的看守；再把 QQ 目录里属于模块的
# 东西清掉（files/.svc 与旧条目）。卸载之后模块就没有机会自己清了，这是最后一次。
[ -x /data/adb/satori-qq/qqguard.sh ] && /data/adb/satori-qq/qqguard.sh stop
MODDIR=${0%/*}
case $MODDIR in /*) ;; *) MODDIR=$(cd "$MODDIR" && pwd) ;; esac
[ -f "$MODDIR/ctl.sh" ] && /system/bin/sh "$MODDIR/ctl.sh" purge >/dev/null 2>&1
exit 0
EOF
chmod 0755 $MODULE/service.sh $MODULE/action.sh $MODULE/uninstall.sh $MODULE/ctl.sh

rm -f $OUT/SatoriQQ-module.zip
# 刷机包要求 module.prop 在 zip 根目录（Magisk / KernelSU 都按根目录读）。
( cd $MODULE && zip -qr $OUT/SatoriQQ-module.zip . )
echo "   module zip: $(ls -la $OUT/SatoriQQ-module.zip | awk '{print $5}') bytes"

# 每次构建顺带在共享存储下载目录留一份（scripts/release.sh 发布时也会再刷一次），
# 方便直接分享；不在 Android / 没存储权限时只提示，不让构建失败。
"$R/scripts/share.sh" || true
echo "== DONE =="
ls -la $OUT/SatoriQQ-module.zip
