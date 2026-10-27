#!/data/data/com.termux/files/usr/bin/bash
# QQ 升级后一条命令核对知弦：
#   1. 把手机上装着的 QQ 安装包存进 ~/archive/qq-apk/qq-<版本>.apk（已有就跳过）；
#   2. 与上一个版本的安装包做 dex-diff（模块点名的类、内核包的增删、成员签名变化），
#      报告存成 ~/archive/qq-apk/diff-<旧>-<新>.txt；
#   3. 让运行中的模块强制重算内核接口面自检，列出缺了什么、哪些调用的签名对不上；
#   4. 安装包只留最新两版（每份约 400 MB）。
#
# 只读：不改模块、不重启 QQ。需要 root（读安装包）与本机 3001 端口上的知弦。
#   scripts/qq-upgrade.sh [--port 3001] [--keep 2]
set -euo pipefail
HERE=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
ARCHIVE=${QQ_APK_ARCHIVE:-$HOME/archive/qq-apk}
PORT=3001
KEEP=2
while [ $# -gt 0 ]; do
  case "$1" in
    --port) PORT=$2; shift 2 ;;
    --keep) KEEP=$2; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
mkdir -p "$ARCHIVE"

ver=$(su -c "dumpsys package com.tencent.mobileqq" | sed -n 's/^ *versionName=//p' | head -1)
[ -n "$ver" ] || { echo "读不到 QQ 版本（root？）" >&2; exit 1; }
apk="$ARCHIVE/qq-$ver.apk"
if [ ! -s "$apk" ]; then
  base=$(su -c "pm path com.tencent.mobileqq" | sed -n 's/^package://p' | grep 'base.apk$' | head -1)
  echo "== 存安装包 $ver"
  su -c "cat '$base'" > "$apk.part" && mv "$apk.part" "$apk"
fi

# 版本号按数字排序，取当前版本之前最近的一份当基线。
prev=$(ls "$ARCHIVE"/qq-*.apk 2>/dev/null | sed 's|.*/qq-||; s|\.apk$||' | sort -t. -k1,1n -k2,2n -k3,3n -k4,4n \
  | awk -v v="$ver" '$0 == v { print last; exit } { last = $0 }')
if [ -n "$prev" ]; then
  report="$ARCHIVE/diff-$prev-$ver.txt"
  echo "== dex-diff $prev → $ver"
  python3 "$HERE/dex-diff.py" "$ARCHIVE/qq-$prev.apk" "$apk" > "$report"
  # 摘要：第一段（源码点名、新版没有的类）最要紧；成员变化只数行数。
  awk '/^== /{sec=$0; print; next} sec ~ /新版没有/ {print} ' "$report" | head -40
  echo "   成员变化 $(grep -c '^   [+-] ' "$report" || true) 行，全文 $report"
else
  echo "== 没有更早的安装包可比，下次升级才有 dex-diff"
fi

echo "== 内核接口面自检（模块重算）"
if out=$(curl -s -m 30 -X POST "http://127.0.0.1:$PORT/v1/internal/compat" -H 'content-type: application/json' -d '{"force":true}'); then
  python3 - "$out" <<'PY'
import json, sys
d = json.loads(sys.argv[1])
s = d.get('static', {})
print('   QQ %s：%s/%s 通过' % (s.get('qq_version'), s.get('passed'), s.get('total')))
for m in s.get('missing', []):
    print('   缺', m.get('kind'), m.get('name'), m.get('detail', ''))
o = d.get('observed', {})
bad = [c for c in o.get('calls', []) if c.get('failed') or c.get('timeout')]
for c in bad:
    print('   调用 %s：ok %s / 超时 %s / 失败 %s' % (c['label'], c['ok'], c['timeout'], c['failed']))
for k, v in (o.get('signature_mismatches') or {}).items():
    print('   签名对不上', k, '→', v)
PY
else
  echo "   127.0.0.1:$PORT 没有响应（模块没在跑？）"
fi

# 只留最新的 $KEEP 份安装包。
ls "$ARCHIVE"/qq-*.apk | sed 's|.*/qq-||; s|\.apk$||' | sort -t. -k1,1n -k2,2n -k3,3n -k4,4n \
  | head -n -"$KEEP" | while read -r old; do
    echo "== 删旧安装包 qq-$old.apk"
    rm -f "$ARCHIVE/qq-$old.apk"
  done
