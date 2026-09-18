#!/data/data/com.termux/files/usr/bin/bash
# 构建知弦的 Zygisk 原生模块依赖：LSPlant（自带 ART hook 引擎）+ Dobby + 一份可静态链接的 libc++。
#
# 产物（都在 build/third_party/ 下，顶层 build.sh 会拿去做链接）：
#   out/liblsplant_static.a / libdobby.a / libcxx.a / include/ 与 cxxConfig.cmake
#
# 说明（都踩过，别简化）：
#   - 用 JingMatrix/LSPlant（Vector 在用的 fork，支持 Android 5–17）。官方 LSPosed/LSPlant
#     只到 Android 14，Maven 上最新只到 6.4。
#   - native/lsplant.patch 是必需的：clang 21 会在它的模板元编程上崩 mangler / 推不出类型。
#   - libc++ 用 Maven 的 org.lsposed.libcxx:libcxx（topjohnwu/libcxx 的预编译静态库），
#     配合 -nostdinc++ -nostdlib++，产出的 .so 只依赖系统库，不用往模块里塞 libc++_shared.so。
#   - liblog.so / libz.so 从设备 /system/lib64 拷来当链接桩：Termux 没有 liblog，
#     而 Termux 的 libz 会让 .so 依赖 libz.so.1（Android 上叫 libz.so）。
set -e
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
R=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
TP=${SATORI_TP_DIR:-$R/build/third_party}
OUT=$TP/out
LSPLANT_COMMIT=49d2e5641dfb222e24cd1fc0e9968a5a00496baa
DOBBY_VER=1.2
LIBCXX_VER=29.0.14206865
CXX_INCLUDE=$TP/cxx/prefab/modules/cxx/include
CXX_LIB=$TP/cxx/prefab/modules/cxx/libs/android.arm64-v8a/libcxx.a

mkdir -p "$TP" "$OUT" "$TP/syslibs"

echo "== 0. 设备系统库当链接桩 =="
for lib in liblog.so libz.so; do
  [ -f "$TP/syslibs/$lib" ] || cp "/system/lib64/$lib" "$TP/syslibs/$lib"
done

echo "== 1. 预编译依赖 =="
if [ ! -f "$TP/dobby.aar" ]; then
  curl -fsSL -o "$TP/dobby.aar" \
    "https://repo1.maven.org/maven2/io/github/vvb2060/ndk/dobby/$DOBBY_VER/dobby-$DOBBY_VER.aar"
fi
if [ ! -f "$CXX_LIB" ]; then
  curl -fsSL -o "$TP/libcxx.aar" \
    "https://repo1.maven.org/maven2/org/lsposed/libcxx/libcxx/$LIBCXX_VER/libcxx-$LIBCXX_VER.aar"
  rm -rf "$TP/cxx" && mkdir -p "$TP/cxx"
  unzip -oq "$TP/libcxx.aar" 'prefab/modules/cxx/*' -d "$TP/cxx"
fi
if [ ! -f "$OUT/libdobby.a" ]; then
  rm -rf "$TP/dobby" && mkdir -p "$TP/dobby"
  unzip -oq "$TP/dobby.aar" 'prefab/modules/dobby/*' -d "$TP/dobby"
  cp "$TP/dobby/prefab/modules/dobby/libs/android.arm64-v8a/libdobby.a" "$OUT/libdobby.a"
fi
cp -f "$CXX_LIB" "$OUT/libcxx.a"

echo "== 2. LSPlant 源码 + 补丁 =="
if [ ! -d "$TP/LSPlant/.git" ]; then
  rm -rf "$TP/LSPlant"
  git clone --filter=blob:none https://github.com/JingMatrix/LSPlant.git "$TP/LSPlant"
fi
git -C "$TP/LSPlant" fetch --depth 1 origin "$LSPLANT_COMMIT" >/dev/null 2>&1 || true
git -C "$TP/LSPlant" checkout -q "$LSPLANT_COMMIT"
git -C "$TP/LSPlant" submodule update --init --depth 1 \
  lsplant/src/main/jni/external/dex_builder >/dev/null
git -C "$TP/LSPlant/lsplant/src/main/jni/external/dex_builder" submodule update --init --depth 1 >/dev/null
if ! grep -q DummyBackupFn "$TP/LSPlant/lsplant/src/main/jni/include/utils/hook_helper.hpp"; then
  patch -p1 -d "$TP/LSPlant" < "$SCRIPT_DIR/lsplant.patch"
fi

echo "== 3. libc++ 的 CMake 包（手工生成，prefab 那套要 AGP） =="
mkdir -p "$TP/cxxcfg"
cat > "$TP/cxxcfg/cxxConfig.cmake" <<EOF
add_library(cxx::cxx INTERFACE IMPORTED)
target_include_directories(cxx::cxx INTERFACE "$CXX_INCLUDE")
target_link_libraries(cxx::cxx INTERFACE "$CXX_LIB")
target_compile_options(cxx::cxx INTERFACE -nostdinc++ -fno-exceptions -fno-rtti)
EOF

echo "== 4. 编 LSPlant（standalone：不依赖任何外部 STL） =="
cmake -S "$TP/LSPlant/lsplant/src/main/jni" -B "$TP/lsplant-build" -G Ninja \
  -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_OBJCOPY=llvm-objcopy -DCMAKE_STRIP=llvm-strip -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DLSPLANT_STANDALONE=ON -Dcxx_DIR="$TP/cxxcfg" \
  -DDEBUG_SYMBOLS_PATH="$TP/lsplant-build/symbols" \
  -DCMAKE_SHARED_LINKER_FLAGS="-fuse-ld=lld -nostdlib++ -L$TP/syslibs" \
  -DCMAKE_EXE_LINKER_FLAGS="-fuse-ld=lld -nostdlib++ -L$TP/syslibs" >/dev/null
cmake --build "$TP/lsplant-build" -j4 --target lsplant_static >/dev/null
cp -f "$TP/lsplant-build/liblsplant_static.a" "$OUT/liblsplant_static.a"
# dex_builder 是 LSPlant 的 PRIVATE 依赖（生成 hook 桩要用）。静态库不会把它的目标文件带进来，
# 少了这个归档链接能过、但 .so 会留 20 个未定义的 startop::dex::*，装机后 dlopen 直接失败。
cp -f "$TP/lsplant-build/external/dex_builder/libdex_builder_static.a" "$OUT/libdex_builder_static.a"
mkdir -p "$OUT/include"
cp -f "$TP/LSPlant/lsplant/src/main/jni/include/lsplant.hpp" "$OUT/include/"

echo "== DONE =="
ls -la "$OUT"
