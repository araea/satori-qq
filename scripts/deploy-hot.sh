#!/data/data/com.termux/files/usr/bin/bash
# 开发机热部署：替换已安装模块里的 .so，通知 Zygisk Next 重载，再重启 QQ。
# 仅适用于已安装并运行 Zygisk Next + satori_qq 的设备；首次安装仍需正常刷入。
set -euo pipefail
R=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SO="$R/build/libsatori.so"
DEST=/data/adb/modules/satori_qq/zygisk/arm64-v8a.so
GUARD=/data/adb/satori-qq/qqguard.sh
PKG=com.tencent.mobileqq
FORCE=0
case "${1:-}" in
    '') ;;
    --force) FORCE=1 ;;  # 仅供验证部署链路；即使内容相同也会重载并重启 QQ
    *) echo '用法: scripts/deploy-hot.sh [--force]' >&2; exit 2 ;;
esac

[ -s "$SO" ] || { echo "缺少 $SO；先运行 ./build.sh" >&2; exit 1; }
# 不通过模块管理器刷 zip：它通常安装到 modules_update，必须等下次开机才启用。
su -c "test -f '$DEST' && command -v znctl >/dev/null && znctl status | grep -q 'satori_qq'" || {
    echo '当前设备没有正在运行的 satori_qq / Zygisk Next；请先正常安装并重启一次。' >&2
    exit 1
}
if [ "$FORCE" -eq 0 ] && su -c "cmp -s '$SO' '$DEST'"; then
    echo '模块 .so 与构建产物相同，无需重载或中断 QQ。'
    exit 0
fi

# 在目标目录创建临时文件并原子替换，避免 zygiskd 读取到半写入的 ELF。
# Termux 文件的 SELinux 类型是 app_data_file；保留旧模块文件的安全上下文。
CONTEXT=$(su -c "ls -Z '$DEST'" | awk '{print $1}')
[[ "$CONTEXT" == u:* ]] || { echo '无法读取已安装模块的 SELinux 类型。' >&2; exit 1; }
su -c "cp '$SO' '$DEST.new' && chmod 0644 '$DEST.new' && chown 0:0 '$DEST.new' && chcon '$CONTEXT' '$DEST.new' && mv -f '$DEST.new' '$DEST' && znctl znmod reload satori_qq" || {
    echo '部署或重载失败；请检查 znctl status 和模块文件，暂勿重启 QQ。' >&2
    exit 1
}
# 保活处于 ARMED 时，先暂停，避免部署中的 force-stop 被误判为用户停用或触发重启预算。
ARMED=0
GUARD_STATUS=$(su -c "test -x '$GUARD' && '$GUARD' status" 2>/dev/null || true)
if [[ "$GUARD_STATUS" == 'qqguard: ARMED'* ]]; then
    ARMED=1
    su -c "'$GUARD' stop"
fi
su -c "/system/bin/am force-stop '$PKG' && /system/bin/am start -n '$PKG/.activity.SplashActivity'" || {
    echo 'QQ 未能重新拉起；模块已重载，请手动打开 QQ。' >&2
    if [ "$ARMED" -eq 1 ]; then su -c "'$GUARD' start"; fi
    exit 1
}
if [ "$ARMED" -eq 1 ]; then su -c "'$GUARD' start"; fi
su -c 'znctl status' | grep -E 'modules64:|modules_with_issue:'
echo '已重载模块并启动 QQ；等待 QQ 登录及 /healthz 恢复。'
