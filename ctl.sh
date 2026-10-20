#!/system/bin/sh
# 知弦的管理入口：模块管理器里的网页界面（webroot/）只通过它读写，每个动作一条子命令。
#
#   ctl.sh probe                    一次快照：模块、设置、开关、QQ 进程、看守与服务的 /healthz
#   ctl.sh write-config <base64>    原子写入 config.json（有待生效的模块更新时一并写入）
#   ctl.sh set-enabled 0|1          模块主开关；关闭时暂停看守，重新开启时恢复关闭前的看守
#   ctl.sh guard start|stop|kill    常驻守护：保活 / 暂停 / 暂停并关闭 QQ
#   ctl.sh open | relaunch          打开 QQ / 重新启动 QQ（保持看守原来的模式）
#   ctl.sh update-check | update-install <url> <sha256> | reboot
#
# 输出是 key=value 行，任意文本走 base64。参数都由界面生成，这里仍逐一校验。
MODDIR=${0%/*}
case $MODDIR in /*) ;; *) MODDIR=$(cd "$MODDIR" && pwd) ;; esac

# Android 11 起每个应用有自己的挂载命名空间，管理器给的 root shell 可能沿用它：那里看不到 QQ 的数据目录。
if [ -z "$SATORI_CTL_NS" ] && [ "$(readlink /proc/self/ns/mnt)" != "$(readlink /proc/1/ns/mnt 2>/dev/null)" ]; then
    export SATORI_CTL_NS=1
    for n in /system/bin/nsenter /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox /data/adb/magisk/busybox; do
        [ -x "$n" ] || continue
        case $n in */nsenter) exec "$n" -t 1 -m -- /system/bin/sh "$0" "$@" ;; *) exec "$n" nsenter -t 1 -m -- /system/bin/sh "$0" "$@" ;; esac
    done
fi

ID=satori_qq
PKG=com.tencent.mobileqq
M=/data/adb/modules/$ID
U=/data/adb/modules_update/$ID
DATA=/data/user/0/$PKG
FLAG=$DATA/files/satori-qq/enabled
G=/data/adb/satori-qq
CONF=$M/config.json

b64() { base64 -w 0 2>/dev/null || base64; }
prop() { sed -n "s/^$1=//p" "$2" 2>/dev/null | head -n 1; }

guard() {
    for p in "$G/qqguard.sh" "$MODDIR/qqguard.sh"; do
        [ -f "$p" ] && { /system/bin/sh "$p" "$@"; return; }
    done
    return 127
}

probe() {
    [ -d "$M" ] && echo module=1
    [ -f "$M/disable" ] && echo disabled=1
    [ -f "$M/remove" ] && echo removing=1
    { [ -d "$U" ] || [ -f "$M/update" ]; } && echo update=1
    echo "version=$(prop version "$M/module.prop")"
    echo "version_code=$(prop versionCode "$M/module.prop")"
    [ -f "$U/module.prop" ] && echo "update_version=$(prop version "$U/module.prop")"
    [ -f "$CONF" ] && echo "config=$(b64 < "$CONF")"
    [ -f "$FLAG" ] && echo "enabled=$(cat "$FLAG")"
    if pm path "$PKG" >/dev/null 2>&1; then
        echo installed=1
        echo "qq_version=$(dumpsys package "$PKG" | sed -n 's/^ *versionName=//p' | head -n 1)"
    fi
    pid=$(pidof "$PKG" | cut -d ' ' -f 1)
    echo "pid=$pid"
    if [ -n "$pid" ]; then
        cg=$(sed -n 's/^0:://p' /proc/$pid/cgroup 2>/dev/null)
        if [ "$(sed -n 's/^frozen //p' /sys/fs/cgroup$cg/cgroup.events 2>/dev/null)" = 1 ] \
            || grep -q freezer /proc/$pid/wchan 2>/dev/null; then echo frozen=1; fi
    fi
    g=$(guard status --json 2>/dev/null | grep '^{' | tail -n 1)
    [ -n "$g" ] && echo "guard=$(printf '%s' "$g" | b64)"
    # 服务的 /healthz 不要令牌。先问设置里的端口；改了端口还没重启 QQ 时，旧服务还在默认端口上。
    port=$(sed -n 's/.*"port"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$CONF" 2>/dev/null | head -n 1)
    for p in ${port:-3001} 3001; do
        [ -n "$pid" ] || break
        h=$(curl -s -m 3 --noproxy '*' "http://127.0.0.1:$p/healthz" 2>/dev/null)
        case $h in
            '{'*) echo "health_port=$p"; echo "health=$(printf '%s' "$h" | b64)"; break ;;
        esac
        [ "$p" = 3001 ] && break
    done
    echo "now=$(date +%s)"
}

# 原子写入：先写同目录的临时文件、设好属主 0:0、权限 0600 与 module.prop 的 SELinux 上下文，再改名覆盖。
# Zygisk 在 QQ 启动时从模块目录读它，所以改动要重启 QQ 才生效。
write_config() {
    text=$(printf '%s' "$1" | base64 -d 2>/dev/null) || { echo "error=设置不是有效的 base64"; return 1; }
    case $text in '{'*'}') ;; *) echo "error=设置不是 JSON 对象"; return 1 ;; esac
    [ "${#text}" -lt 16000 ] || { echo "error=设置太大"; return 1; }
    umask 077
    wrote=0
    for D in "$M" "$U"; do
        [ -d "$D" ] || continue
        T=$D/.config.json.new
        printf '%s\n' "$text" > "$T" || return 1
        chown 0:0 "$T"; chmod 0600 "$T"
        ctx=$(stat -c %C "$D/module.prop" 2>/dev/null)
        [ -n "$ctx" ] && chcon "$ctx" "$T" 2>/dev/null
        mv -f "$T" "$D/config.json" || return 1
        wrote=1
    done
    [ "$wrote" = 1 ] || { echo "error=没有找到知弦模块"; return 1; }
    echo ok=1
}

# 开关文件在 QQ 的数据目录里，必须归 QQ 的 uid 所有并带上同一 SELinux 上下文，否则 QQ 读不到。
set_enabled() {
    case $1 in 0|1) ;; *) echo "error=参数须为 0 或 1"; return 2 ;; esac
    [ -d "$DATA" ] || { echo "error=没有找到 QQ 的数据目录"; return 1; }
    was=$(sed -n 's/^MODE=//p' "$G/guard.state" 2>/dev/null)
    dir=${FLAG%/*}
    umask 077
    mkdir -p "$dir"
    owner=$(stat -c %u:%g "$DATA")
    chown "$owner" "$dir"; chmod 0700 "$dir"
    printf '%s' "$1" > "$FLAG.tmp" && chown "$owner" "$FLAG.tmp" && chmod 0600 "$FLAG.tmp"
    ctx=$(stat -c %C "$dir" 2>/dev/null)
    [ -n "$ctx" ] && chcon "$ctx" "$FLAG.tmp" 2>/dev/null
    mv -f "$FLAG.tmp" "$FLAG"
    [ "$(cat "$FLAG")" = "$1" ] || { echo "error=没能写入模块开关"; return 1; }
    # 关闭时把看守一起暂停（否则它还在拉起 QQ），并记下原来的意图；重新开启时照着恢复。
    if [ "$1" = 0 ]; then
        if [ "$was" = ARMED ]; then echo 1 > "$G/guard.resume"; guard stop >/dev/null 2>&1; else rm -f "$G/guard.resume"; fi
    elif [ -f "$G/guard.resume" ]; then
        guard start >/dev/null 2>&1 && rm -f "$G/guard.resume"
    fi
    echo ok=1
}

open_app() { monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; }

# 不能直接 force-stop：看守把系统里的「强行停止」当成用户意愿，会自己转入 PAUSED。
# 所以保活中就先暂停、关 QQ、重新打开，再恢复保活。
relaunch() {
    armed=
    guard status --json 2>/dev/null | grep -q '"mode":"ARMED"' && { armed=1; guard stop >/dev/null 2>&1; }
    am force-stop $PKG
    sleep 1
    open_app
    [ -n "$armed" ] && guard start >/dev/null 2>&1
    echo ok=1
}

# ---- 更新：读 module.prop 的 updateJson，下载、校验后交给 root 管理器安装（两仓的这一段逐字相同） ----

update_check() {
    url=$(prop updateJson "$M/module.prop")
    [ -n "$url" ] || { echo "error=模块没有声明 updateJson"; return 1; }
    body=$(curl -fsSL -m 20 "$url" 2>/dev/null) || { echo "error=连不上 GitHub"; return 1; }
    printf 'manifest=%s\n' "$(printf '%s' "$body" | b64)"
}

update_install() {
    url=$1 sum=$2
    case $url in https://*) ;; *) echo "error=只接受 https 下载地址"; return 2 ;; esac
    printf '%s' "$sum" | grep -Eq '^[0-9a-f]{64}$' || { echo "error=缺少校验值"; return 2; }
    f=/data/local/tmp/$ID-update.zip
    rm -f "$f"
    curl -fsSL -m 300 -o "$f" "$url" 2>/dev/null || { rm -f "$f"; echo "error=下载失败"; return 1; }
    [ "$(sha256sum "$f" | cut -d ' ' -f 1)" = "$sum" ] || { rm -f "$f"; echo "error=校验失败，安装包与清单不符"; return 1; }
    if [ -x /data/adb/ksud ]; then log=$(/data/adb/ksud module install "$f" 2>&1)
    elif [ -x /data/adb/apd ]; then log=$(/data/adb/apd module install "$f" 2>&1)
    elif command -v magisk >/dev/null 2>&1; then log=$(magisk --install-module "$f" 2>&1)
    else rm -f "$f"; echo "error=没有找到 KernelSU、APatch 或 Magisk"; return 1
    fi
    rc=$?
    rm -f "$f"
    [ "$rc" = 0 ] || { printf 'error=安装器报错：%s\n' "$(printf '%s' "$log" | tail -n 1)"; return 1; }
    echo ok=1
}

case $1 in
    probe) probe ;;
    write-config) write_config "$2" ;;
    set-enabled) set_enabled "$2" ;;
    guard) case $2 in start|stop|kill) guard "$2" >/dev/null 2>&1 && echo ok=1 || echo "error=看守没有响应" ;; *) exit 2 ;; esac ;;
    open) open_app ;;
    relaunch) relaunch ;;
    update-check) update_check ;;
    update-install) update_install "$2" "$3" ;;
    reboot) svc power reboot 2>/dev/null || reboot ;;
    *) echo "usage: ctl.sh probe|write-config|set-enabled|guard|open|relaunch|update-check|update-install|reboot" >&2; exit 2 ;;
esac
