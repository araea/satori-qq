#!/data/data/com.termux/files/usr/bin/bash
# 发布：核对版本 → 生成 update.json → 创建 GitHub release 并上传安装包。
#
# 用法：scripts/release.sh [--dry-run] [--notes FILE]
#   默认说明取 changelogs/CHANGELOG-<版本>.md；--dry-run 只生成并核对，不创建 release。
#
# 上传的四个文件：SatoriQQ.apk、SatoriQQ-module.zip、update.json（管理应用与 root 管理器的模块页读它）、
# CHANGELOG.md（模块页的更新说明）。tag 为 <versionCode>-<versionName>，与历史一致。
set -euo pipefail
R=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
cd "$R"
DRY=0
NOTES=
while [ $# -gt 0 ]; do
  case $1 in
    --dry-run) DRY=1 ;;
    --notes) NOTES=$2; shift ;;
    *) echo "usage: $0 [--dry-run] [--notes FILE]" >&2; exit 2 ;;
  esac
  shift
done

NAME=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml | head -1)
CODE=$(sed -n 's/.*android:versionCode="\([^"]*\)".*/\1/p' AndroidManifest.xml | head -1)
TAG="$CODE-$NAME"
NOTES=${NOTES:-changelogs/CHANGELOG-$NAME.md}
[ -f "$NOTES" ] || { echo "缺少更新说明：$NOTES" >&2; exit 1; }
for f in build/SatoriQQ.apk build/SatoriQQ-module.zip; do
  [ -f "$f" ] || { echo "缺少 $f，先跑 ./build.sh" >&2; exit 1; }
done

MODVER=$(unzip -p build/SatoriQQ-module.zip module.prop | sed -n 's/^versionCode=//p')
[ "$MODVER" = "$CODE" ] || { echo "模块包的 versionCode（$MODVER）与清单（$CODE）不一致，重新 ./build.sh" >&2; exit 1; }
[ -z "$(git status --porcelain -- src native AndroidManifest.xml build.sh)" ] || echo "注意：源码有未提交的改动" >&2

cp "$NOTES" build/CHANGELOG.md
python3 scripts/release-manifest.py --repo araea/satori-qq --tag "$TAG" --notes "$NOTES" \
  --module build/SatoriQQ-module.zip --apk build/SatoriQQ.apk --out build/update.json

if [ "$DRY" = 1 ]; then echo "dry-run：不创建 release（tag $TAG）"; exit 0; fi
gh release create "$TAG" build/SatoriQQ.apk build/SatoriQQ-module.zip build/update.json build/CHANGELOG.md \
  --repo araea/satori-qq --target master --title "知弦 $NAME" --notes-file "$NOTES"
echo "已发布 $TAG"
