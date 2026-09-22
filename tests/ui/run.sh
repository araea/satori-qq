#!/data/data/com.termux/files/usr/bin/bash
# 真机跑知弦管理页的设计冒烟：交互契约、令牌解析、可触达面积、焦点环、状态 live region，
# 以及深浅色/大字号下的实际排版截图。
#
# 前置：已安装 com.satori.qq（跑一次 build.sh + 装机），本机有 root（su）。
# 不碰 QQ 服务：测试只读写知弦自己的配置文件，跑完还原。
set -euo pipefail
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
OUT="$ROOT/build/design-tests"
SHOTS="$OUT/design-review"
PKG=com.satori.qq.test
APP=com.satori.qq
NAMES="light-0 light-1 light-2 dark-0 dark-1 dark-2 large-0 large-1 large-2"

bash "$ROOT/tests/ui/build.sh"

echo "== 安装测试包 =="
su -c "pm install -r -d '$OUT/DesignTests.apk'" | tail -2

echo "== 运行 =="
# 先把应用的任务清掉再跑。留着一个已存在的 MainActivity，它的实例状态（onSaveInstanceState 存下的
# 那份）会被下面 startActivitySync 的同组件 ActivityRecord 继承，"草稿跨重建保留"那条就会读到旧值
# 而不是本次写进去的草稿——表现为 port=3001 tab=0，看起来像应用丢了草稿，其实是环境没清干净。
su -c "am force-stop $APP"
su -c "am instrument -w -r $PKG/.DesignSmoke" | tee "$OUT/instrument.log"
if grep -q 'FAIL' "$OUT/instrument.log"; then
  echo "设计冒烟未通过，见 $OUT/instrument.log" >&2
  exit 1
fi

echo "== 取截图 =="
rm -rf "$SHOTS" && mkdir -p "$SHOTS"
for name in $NAMES; do
  # 应用私有目录只有 root 读得到，走 su cat 出来，不落 /sdcard。
  if su -c "cat /data/data/$APP/files/design-review/$name.png" > "$SHOTS/$name.png" 2>/dev/null \
      && [ -s "$SHOTS/$name.png" ]; then
    printf '   ok   %s (%s bytes)\n' "$name.png" "$(stat -c%s "$SHOTS/$name.png")"
  else
    printf '   MISS %s\n' "$name.png"
    rm -f "$SHOTS/$name.png"
  fi
done

echo "== DONE：截图在 $SHOTS =="
