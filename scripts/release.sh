#!/data/data/com.termux/files/usr/bin/bash
# 发布：核对版本 → 生成 update.json → 创建 GitHub release 并上传安装包。
#
# 用法：scripts/release.sh [--dry-run] [--notes FILE]
#   默认说明取 changelogs/CHANGELOG-<版本>.md；--dry-run 只生成并核对，不创建 release。
#
# 上传的三个文件：SatoriQQ-module.zip、update.json（模块管理页与 root 管理器的模块列表读它）、
# CHANGELOG.md（模块列表的更新说明）。tag 为 <versionCode>-<versionName>，与历史一致。
# 发布成功后还会调用 scripts/share.sh，把模块包复制到共享存储下载目录（可用
# SATORI_QQ_SHARE_DIR 指定目标目录；失败只提示，不影响发布）。
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

NAME=$(sed -n 's/^version=//p' module.prop)
CODE=$(sed -n 's/^versionCode=//p' module.prop)
TAG="$CODE-$NAME"
NOTES=${NOTES:-changelogs/CHANGELOG-$NAME.md}
[ -f "$NOTES" ] || { echo "缺少更新说明：$NOTES" >&2; exit 1; }
[ -f build/SatoriQQ-module.zip ] || { echo "缺少 build/SatoriQQ-module.zip，先跑 ./build.sh" >&2; exit 1; }

MODVER=$(unzip -p build/SatoriQQ-module.zip module.prop | sed -n 's/^versionCode=//p')
[ "$MODVER" = "$CODE" ] || { echo "模块包的 versionCode（$MODVER）与清单（module.prop $CODE）不一致，重新 ./build.sh" >&2; exit 1; }
[ -z "$(git status --porcelain -- src native webroot ctl.sh module.prop build.sh)" ] || echo "注意：源码有未提交的改动" >&2

cp "$NOTES" build/CHANGELOG.md
python3 scripts/release-manifest.py --repo araea/satori-qq --tag "$TAG" --notes "$NOTES" \
  --module build/SatoriQQ-module.zip --out build/update.json

if [ "$DRY" = 1 ]; then echo "dry-run：不创建 release（tag $TAG）"; exit 0; fi
gh release create "$TAG" build/SatoriQQ-module.zip build/update.json build/CHANGELOG.md \
  --repo araea/satori-qq --target master --title "知弦 $NAME" --notes-file "$NOTES"
echo "已发布 $TAG"
# 发布成功后顺手放一份到共享存储下载目录（best-effort，失败不影响已完成的发布）。
scripts/share.sh
