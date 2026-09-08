#!/data/data/com.termux/files/usr/bin/bash
# Push marketplace metadata + release to Xposed-Modules-Repo/com.satori.qq
set -euo pipefail
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
MP="$ROOT/marketplace"
PKG=com.satori.qq
ORG_REPO="Xposed-Modules-Repo/$PKG"
GH=/data/data/com.termux/files/usr/bin/gh
APK="$ROOT/build/SatoriQQ.apk"
APK_STEALTH="$ROOT/build/SatoriQQ.stealth.apk"
TAG="64-0.8.9.29"

for artifact in "$APK" "$APK_STEALTH"; do
  if [ ! -f "$artifact" ]; then
    echo "missing $artifact - run ./build.sh first"
    exit 1
  fi
done

if ! "$GH" api "repos/$ORG_REPO" --jq .name >/dev/null 2>&1; then
  echo "Marketplace repo not ready yet: $ORG_REPO"
  exit 1
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
cd "$TMP"
git clone --depth=1 "git@github.com:$ORG_REPO.git" repo
"$GH" api "repos/$ORG_REPO" -X PATCH -f description='本机 QQ 的 Satori v1 实现端。' >/dev/null
cd repo
cp "$MP/SUMMARY" "$MP/README.md" "$MP/SOURCE_URL" "$MP/ic_launcher.png" .
git add SUMMARY README.md SOURCE_URL ic_launcher.png
if git diff --cached --quiet; then
  echo "Metadata already up to date"
else
  git commit -m "Update satori-qq icon for 0.8.9.29"
  git push origin HEAD
fi

"$GH" release create "$TAG" "$APK" "$APK_STEALTH" \
  --repo "$ORG_REPO" \
  --title "0.8.9.29" \
  --notes-file "$MP/CHANGELOG-0.8.9.29.md"

echo "Published to https://github.com/$ORG_REPO"
