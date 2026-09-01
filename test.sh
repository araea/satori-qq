#!/data/data/com.termux/files/usr/bin/bash
# JVM 单元测试。只跑不碰 QQ 内核的纯逻辑部分（元素编解码、协议信令、存储、
# 反检测统计、内部动作的确定性计算）；真机行为仍靠 tests/ws-health.js 与
# scripts/ 下的现场脚本。
#
# NOTE: libs/json.jar 与 libs/r8.jar 一样被 gitignore。首次克隆后下载一次：
#   curl -fsSL -o libs/json.jar https://repo1.maven.org/maven2/org/json/json/20250517/json-20250517.jar
# android.jar 里的 org.json 是会抛 "Stub!" 的桩，运行期必须让真实实现排在它前面。
set -e
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
R=${SATORI_QQ_ROOT:-$SCRIPT_DIR}
ANDROID_JAR=/data/data/com.termux/files/home/android/platform/android-35/android.jar
JSON_JAR=$R/libs/json.jar
OUT=${SATORI_QQ_OUT:-$R/build}
CLASSES=$OUT/test-classes

if [ ! -f "$ANDROID_JAR" ]; then
  echo "missing $ANDROID_JAR" >&2
  exit 1
fi
if [ ! -f "$JSON_JAR" ]; then
  echo "missing $JSON_JAR — see the download line at the top of this script" >&2
  exit 1
fi

echo "== 1. javac =="
rm -rf "$CLASSES" && mkdir -p "$CLASSES"
find "$R/src" "$R/stubs" "$R/tests" -name '*.java' > "$OUT/test-sources.txt"
javac -classpath "$JSON_JAR:$ANDROID_JAR" -encoding UTF-8 -nowarn \
  -d "$CLASSES" @"$OUT/test-sources.txt"
echo "   compiled $(find "$CLASSES" -name '*.class' | wc -l) classes"

echo "== 2. run =="
# json.jar 必须排在 android.jar 之前，否则 org.json 会命中桩实现。
CP="$JSON_JAR:$ANDROID_JAR:$CLASSES"
failed=0
total=0
for class in $(cd "$CLASSES" && find . -name '*Test.class' \
    | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort); do
  total=$((total + 1))
  if out=$(java -cp "$CP" "$class" 2>&1); then
    printf '   ok   %s\n' "$class"
  else
    failed=$((failed + 1))
    printf '   FAIL %s\n' "$class"
    printf '%s\n' "$out" | sed 's/^/        /'
  fi
done

if [ "$failed" -ne 0 ]; then
  echo "== $failed/$total FAILED =="
  exit 1
fi
echo "== DONE: $total passed =="
