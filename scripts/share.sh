#!/data/data/com.termux/files/usr/bin/bash
# 把已构建的模块包复制一份到共享存储下载目录，方便直接分享。
#
# 用法：scripts/share.sh          # 复制 build/SatoriQQ-module.zip
#       scripts/share.sh --quiet  # 只在失败时输出（release.sh 用）
#
# 目标目录依次尝试：$SATORI_QQ_SHARE_DIR、/sdcard/Download、~/storage/downloads。
# 复制是「尽力而为」：不在 Android / 没给存储权限时只提示，不影响构建或发布。
set -u
R=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
ZIP=$R/build/SatoriQQ-module.zip
QUIET=0
[ "${1:-}" = "--quiet" ] && QUIET=1
say() { [ "$QUIET" = 1 ] || echo "$@"; }

[ -f "$ZIP" ] || { echo "share: 缺少 $ZIP，先跑 ./build.sh" >&2; exit 1; }

NAME=$(sed -n 's/^version=//p' "$R/module.prop")
[ -n "$NAME" ] || { echo "share: 读不到 module.prop 的 version" >&2; exit 1; }

DEST_DIR=
for d in "${SATORI_QQ_SHARE_DIR:-}" /sdcard/Download "$HOME/storage/downloads"; do
  [ -n "$d" ] && [ -d "$d" ] && [ -w "$d" ] && { DEST_DIR=$d; break; }
done
[ -n "$DEST_DIR" ] || { echo "share: 找不到可写的共享下载目录，跳过（不影响发布）" >&2; exit 0; }

DEST=$DEST_DIR/SatoriQQ-$NAME-module.zip
if [ -f "$DEST" ] && cmp -s "$ZIP" "$DEST"; then
  say "下载目录里已是同一份：$DEST"
  exit 0
fi
# 先写同目录临时文件再 rename：分享到一半时别人不会拿到半截 zip。
TMP=$DEST_DIR/.SatoriQQ-$NAME-module.zip.part.$$
trap 'rm -f "$TMP"' EXIT
if cp -f "$ZIP" "$TMP" && mv -f "$TMP" "$DEST"; then
  say "已复制模块包：$DEST（$(stat -c %s "$DEST" 2>/dev/null || echo '?') 字节）"
else
  echo "share: 复制到 $DEST 失败，跳过（不影响发布）" >&2
fi
exit 0
