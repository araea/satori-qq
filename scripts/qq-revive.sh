#!/data/data/com.termux/files/usr/bin/bash
# QQ 僵尸会话看守。以 root 运行（service.d 或 `su -c`）。
#
# 为什么需要：模块的 block_server_kick 挡掉了 NTKickProcessor.b——服务端踢线时 QQ 唯一的
# 处理入口。好处是不被踢下线，代价是服务端会话已经作废、本地仍报在线：消息一条收不到，
# 也不会自己重连，直到有人重启 QQ。2026-09-13 22:10 就这么静默了 24 分钟。
#
# 判据（每轮 60 秒）：
#   upstream  MSF 进程到服务端的存活 TCP 连接数，回环不算。健康时常驻 1 条。
#   online    /healthz 自报的内核在线状态。
#   kicks     /healthz 的被拦踢线计数，一旦增长说明这轮僵尸是踢线造成的。
#
# 动作：
#   kicks 增长                -> 立刻重启 QQ（会话已作废，等下去不会好）
#   online 且 upstream=0      -> 连续 STALE_LIMIT 轮后重启
#   端口一直不通              -> 连续 OFFLINE_LIMIT 轮后拉起 QQ（进程没了/没起来）
#   设备自己没网              -> 只记一行，不动 QQ（重启也连不上）
#
# 停止：kill $(cat $QQ_REVIVE_PIDFILE)；或注释掉 service.d 里的启动行。
set -u
export PATH=/data/data/com.termux/files/usr/bin:/system/bin:/system/xbin:${PATH:-}

PKG=${QQ_REVIVE_PKG:-com.tencent.mobileqq}
PORT=${QQ_REVIVE_PORT:-3001}
INTERVAL=${QQ_REVIVE_INTERVAL:-60}
STALE_LIMIT=${QQ_REVIVE_STALE_LIMIT:-3}
OFFLINE_LIMIT=${QQ_REVIVE_OFFLINE_LIMIT:-5}
PING_HOST=${QQ_REVIVE_PING_HOST:-223.5.5.5}
RECOVER_WAIT=${QQ_REVIVE_RECOVER_WAIT:-90}
MAX_LOG_BYTES=${QQ_REVIVE_MAX_LOG_BYTES:-2000000}

if [ -z "${QQ_REVIVE_LOG:-}" ]; then
    case "${HOME:-}" in
        ""|/) LOG=/data/local/tmp/qq-revive.log ;;
        *)    LOG=$HOME/qq-revive.log ;;
    esac
else
    LOG=$QQ_REVIVE_LOG
fi
PIDFILE=${QQ_REVIVE_PIDFILE:-${LOG%.log}.pid}

log() {
    printf '%s %s\n' "$(date +%FT%T)" "$*" >> "$LOG" 2>/dev/null
    chmod 0644 "$LOG" 2>/dev/null
    size=$(stat -c %s "$LOG" 2>/dev/null || echo 0)
    if [ "$size" -gt "$MAX_LOG_BYTES" ]; then
        tail -n 500 "$LOG" > "$LOG.tmp" 2>/dev/null && mv "$LOG.tmp" "$LOG"
    fi
}

# /healthz 是免鉴权的本机口，模块没起来时返回空。
healthz() { curl -s --max-time 6 --noproxy '*' "http://127.0.0.1:$PORT/healthz" 2>/dev/null; }

field() { printf '%s' "$1" | grep -o "\"$2\":[^,}]*" | head -1 | cut -d: -f2- | tr -d '"'; }

# MSF 进程持有的、对端不是回环的 ESTABLISHED 连接数。
upstream_links() {
    local pid ins
    pid=$(pgrep -f "com.tencent.mobileqq:MSF" 2>/dev/null | head -1)
    [ -n "$pid" ] || { printf '0'; return; }
    ins=$(ls -l "/proc/$pid/fd" 2>/dev/null \
          | sed -n 's/.*socket:\[\([0-9]*\)\].*/\1/p' | sort -u | tr '\n' ' ')
    [ -n "$ins" ] || { printf '0'; return; }
    cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | awk -v ins="$ins" '
        BEGIN { n = split(ins, a, " "); for (i = 1; i <= n; i++) keep[a[i]] = 1 }
        NR > 1 && $4 == "01" && ($10 in keep) {
            split($3, peer, ":")
            if (peer[1] != "0100007F" && peer[1] != "00000000" \
                && peer[1] != "0000000000000000FFFF00000100007F") c++
        }
        END { print c + 0 }'
}

device_online() { ping -c 1 -W 3 "$PING_HOST" >/dev/null 2>&1; }

restart_qq() {
    log "restart: $1"
    am force-stop "$PKG" >/dev/null 2>&1
    sleep 3
    monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep "$RECOVER_WAIT"
}

# 只看一轮判据、不动 QQ，用来确认探针本身工作正常。
if [ "${1:-}" = "--check" ]; then
    hz=$(healthz)
    links=$(upstream_links)
    echo "upstream_links: $links"
    if [ -n "$hz" ]; then
        echo "healthz: online=$(field "$hz" online) blocked_kicks=$(field "$hz" blocked_kicks) kick_hook=$(field "$hz" kick_hook) self_id=$(field "$hz" self_id)"
    else
        echo "healthz: <无响应>"
    fi
    device_online && echo "device_net: ok" || echo "device_net: down"
    exit 0
fi

echo $$ > "$PIDFILE" 2>/dev/null
log "watchdog start pid=$$ pkg=$PKG interval=${INTERVAL}s stale_limit=$STALE_LIMIT"

stale=0
offline=0
last_kicks=""

while true; do
    hz=$(healthz)
    links=$(upstream_links)

    if [ -z "$hz" ]; then
        # 端口不通：模块没起来，或者 QQ 进程不在了。
        offline=$((offline + 1))
        stale=0
        if [ "$offline" -ge "$OFFLINE_LIMIT" ]; then
            if pgrep -f "com.tencent.mobileqq" >/dev/null 2>&1; then
                restart_qq "模块端口不通 ${offline} 轮，QQ 进程还在"
            elif device_online; then
                restart_qq "模块端口不通 ${offline} 轮，QQ 进程不在"
            else
                log "skip: 端口不通 ${offline} 轮，但设备没网，先不动 QQ"
            fi
            offline=0
        fi
        sleep "$INTERVAL"
        continue
    fi
    offline=0

    online=$(field "$hz" online)
    kicks=$(field "$hz" blocked_kicks)
    [ -n "$kicks" ] || kicks=0

    if [ -n "$last_kicks" ] && [ "$kicks" -gt "$last_kicks" ]; then
        log "kick: blocked_kicks ${last_kicks}->${kicks} ($(field "$hz" last_kick)) 立刻重启"
        last_kicks=$kicks
        stale=0
        restart_qq "服务端踢线被拦下"
        continue
    fi
    last_kicks=$kicks

    if [ "$online" = "true" ] && [ "$links" -eq 0 ]; then
        stale=$((stale + 1))
        log "stale: online 但 MSF 无上游连接 ${stale}/${STALE_LIMIT}"
        if [ "$stale" -ge "$STALE_LIMIT" ]; then
            stale=0
            if device_online; then
                restart_qq "在线却收不到消息（上游连接为 0）"
            else
                log "skip: 判为僵尸但设备没网，先不动 QQ"
            fi
            continue
        fi
    else
        stale=0
    fi

    sleep "$INTERVAL"
done
