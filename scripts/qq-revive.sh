#!/data/data/com.termux/files/usr/bin/bash
# QQ 僵尸会话看守。以 root 运行（service.d 或 `su -c`）。
#
# 为什么需要：模块的 block_server_kick 挡掉了 NTKickProcessor.b——服务端踢线时 QQ 唯一的
# 处理入口。好处是不被踢下线，代价是服务端会话已经作废、本地仍报在线：消息一条收不到，
# 也不会自己重连，直到有人重启 QQ。2026-09-13 22:10 就这么静默了 24 分钟。
#
# 判据（每轮 60 秒）：
#   kicklog  模块落盘的踢线记录行数（qk_kick.log）。行数增长 = 这次踢线刚被拦下。
#   upstream MSF 进程到服务端的存活 TCP 连接数，回环不算。健康时常驻 1 条以上。
#   online    /healthz 自报的内核在线状态。
#   kicks     /healthz 的被拦踢线计数（进程内，重启归零）。
#   age       QQ 主进程已经跑了多久；刚起来的前 GRACE 秒不做僵尸判定。
#
# 动作：
#   kicklog 增长              -> 立刻重启 QQ（服务端会话已作废，等下去不会好）
#   online=false 连续 N 轮    -> 重启 QQ（已退出登录，靠自动登录登回来）
#   online 且 upstream=0      -> 连续 STALE_LIMIT 轮且进程已过宽限期，重启
#   端口一直不通              -> 连续 OFFLINE_LIMIT 轮后拉起 QQ（进程没了/没起来）
#   设备自己没网              -> 只记一行，不动 QQ（重启也连不上）
#
# 为什么踢线判据要看落盘文件：模块的 blocked_kicks 是进程内的，QQ 一重启就归零；
# 而「踢线被拦下 = 服务端会话已作废」这件事在重启之后依然成立，必须还能看见。
# 另外 QQ 在踢线路径上会 setAutoLogin(false)（写进 mmkv，落盘），模块会把它顶回去；
# 顶不回去的话，这里重启多少次都只会停在登录页。
#
# 宽限期的来由：2026-09-15 实测，force-stop 后拉起 QQ 到 MSF 重新连上要 5 分钟左右
# （流量走 TUN 时更慢）。原来的 STALE_LIMIT=3 会在这段时间里判定成僵尸、把 QQ 再杀一次，
# 于是每 3 分钟重启一轮，永远等不到连接。真僵尸不会自己好，多等几分钟没有代价。
#
# 停止：kill $(cat $QQ_REVIVE_PIDFILE)；或注释掉 service.d 里的启动行。
set -u
export PATH=/data/data/com.termux/files/usr/bin:/system/bin:/system/xbin:${PATH:-}

PKG=${QQ_REVIVE_PKG:-com.tencent.mobileqq}
PORT=${QQ_REVIVE_PORT:-3001}
INTERVAL=${QQ_REVIVE_INTERVAL:-60}
STALE_LIMIT=${QQ_REVIVE_STALE_LIMIT:-5}
OFFLINE_LIMIT=${QQ_REVIVE_OFFLINE_LIMIT:-5}
LOGOUT_LIMIT=${QQ_REVIVE_LOGOUT_LIMIT:-3}
GRACE=${QQ_REVIVE_GRACE:-300}
PING_HOST=${QQ_REVIVE_PING_HOST:-223.5.5.5}
RECOVER_WAIT=${QQ_REVIVE_RECOVER_WAIT:-90}
MAX_LOG_BYTES=${QQ_REVIVE_MAX_LOG_BYTES:-2000000}
KICK_LOG=${QQ_REVIVE_KICK_LOG:-/data/data/${PKG}/files/qk_kick.log}

# 日志与 pid 放在 /data/adb/satori-qq 下（root 可读，普通应用读不到）。
# 不要放到 /data/local/tmp：那个目录普通应用能进，libfekit 里也带着这个路径字符串。
RUNDIR=${QQ_REVIVE_RUNDIR:-/data/adb/satori-qq}
if [ -z "${QQ_REVIVE_LOG:-}" ]; then
    case "${HOME:-}" in
        ""|/|/data/adb/satori-qq*) LOG=$RUNDIR/qq-revive.log ;;
        *)    LOG=$HOME/qq-revive.log ;;
    esac
else
    LOG=$QQ_REVIVE_LOG
fi
PIDFILE=${QQ_REVIVE_PIDFILE:-${LOG%.log}.pid}

log() {
    printf '%s %s\n' "$(date +%FT%T)" "$*" >> "$LOG" 2>/dev/null
    chmod 0600 "$LOG" 2>/dev/null
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

# 模块落盘的踢线记录行数。文件不存在（模块还没写过踢线）回 0。
kick_log_lines() {
    [ -r "$KICK_LOG" ] || { printf '0'; return; }
    wc -l < "$KICK_LOG" 2>/dev/null | tr -d ' ' || printf '0'
}

# 最后一行的原文，放进看守日志里，事后能看出是被谁踢的。
kick_log_tail() {
    [ -r "$KICK_LOG" ] || return 0
    tail -n 1 "$KICK_LOG" 2>/dev/null | cut -c1-160
}

# QQ 主进程已经跑了多少秒；进程不在时回 -1。用 /proc/<pid>/stat 的 starttime（第 22 字段，
# 单位是时钟滴答）配 /proc/uptime，免得依赖 ps 的 etime 格式。
main_age() {
    local pid hz up st
    pid=$(pgrep -f "^$PKG$" 2>/dev/null | head -1)
    [ -n "$pid" ] || pid=$(pgrep -f "$PKG" 2>/dev/null | grep -v ':MSF' | head -1)
    [ -n "$pid" ] || { printf '%s' -1; return; }
    hz=$(getconf CLK_TCK 2>/dev/null || echo 100)
    st=$(awk '{print $22}' "/proc/$pid/stat" 2>/dev/null)
    up=$(cut -d' ' -f1 /proc/uptime 2>/dev/null)
    [ -n "$st" ] && [ -n "$up" ] || { printf '%s' -1; return; }
    awk -v u="$up" -v s="$st" -v h="$hz" 'BEGIN{printf "%d", u - s/h}'
}

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
    echo "main_age: $(main_age)s (grace ${GRACE}s)"
    echo "kick_log: $(kick_log_lines) 行 ($KICK_LOG)"
    if [ -n "$hz" ]; then
        echo "healthz: online=$(field "$hz" online) blocked_kicks=$(field "$hz" blocked_kicks) kick_hook=$(field "$hz" kick_hook) self_id=$(field "$hz" self_id)"
    else
        echo "healthz: <无响应>"
    fi
    device_online && echo "device_net: ok" || echo "device_net: down"
    exit 0
fi

echo $$ > "$PIDFILE" 2>/dev/null
chmod 0600 "$PIDFILE" 2>/dev/null
log "watchdog start pid=$$ pkg=$PKG interval=${INTERVAL}s stale_limit=$STALE_LIMIT"

stale=0
offline=0
logouts=0
last_kicks=""
last_kicklog=""

while true; do
    hz=$(healthz)
    links=$(upstream_links)

    if [ -z "$hz" ]; then
        # 端口不通：模块没起来，或者 QQ 进程不在了。
        offline=$((offline + 1))
        stale=0
        logouts=0
        log "offline: /healthz 无响应 ${offline}/${OFFLINE_LIMIT}"
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

    # 落盘的踢线记录：行数增长说明这一轮里刚有踢线被拦下。它的好处是跨进程重启还看得见，
    # 而 /healthz 里的 blocked_kicks 会随 QQ 重启归零。
    kicklog=$(kick_log_lines)
    if [ -n "$last_kicklog" ] && [ "$kicklog" -gt "$last_kicklog" ]; then
        log "kick: 踢线记录 ${last_kicklog}->${kicklog} 行 ($(kick_log_tail)) 立刻重启"
        last_kicklog=$kicklog
        last_kicks=$kicks
        stale=0
        logouts=0
        restart_qq "服务端踢线被拦下"
        continue
    fi
    last_kicklog=$kicklog

    if [ -n "$last_kicks" ] && [ "$kicks" -gt "$last_kicks" ]; then
        log "kick: blocked_kicks ${last_kicks}->${kicks} ($(field "$hz" last_kick)) 立刻重启"
        last_kicks=$kicks
        stale=0
        logouts=0
        restart_qq "服务端踢线被拦下"
        continue
    fi
    last_kicks=$kicks

    # 已经退出登录：模块还活着、端口还在，但内核不在线，靠 STALE 判据看不出来（那条要求
    # online=true）。这时只能重启 QQ 让它按 mmkv 里的自动登录设置登回来。
    if [ "$online" != "true" ]; then
        age=$(main_age)
        if [ "$age" -ge 0 ] && [ "$age" -lt "$GRACE" ]; then
            if [ "$logouts" -eq 0 ]; then log "grace: QQ 主进程 ${age}s < ${GRACE}s，本轮不判离线"; fi
            logouts=0
            sleep "$INTERVAL"
            continue
        fi
        logouts=$((logouts + 1))
        stale=0
        log "logout: /healthz online=false ${logouts}/${LOGOUT_LIMIT} (pid_age=${age}s)"
        if [ "$logouts" -ge "$LOGOUT_LIMIT" ]; then
            logouts=0
            if device_online; then
                restart_qq "已退出登录，靠自动登录登回来"
            else
                log "skip: 已退出登录但设备没网，先不动 QQ"
            fi
            continue
        fi
        sleep "$INTERVAL"
        continue
    fi
    logouts=0

    if [ "$links" -eq 0 ]; then
        age=$(main_age)
        if [ "$age" -ge 0 ] && [ "$age" -lt "$GRACE" ]; then
            # 刚拉起来的 QQ 还没连上，这不算僵尸；只在刚开始宽限时记一行。
            if [ "$stale" -eq 0 ]; then log "grace: QQ 主进程 ${age}s < ${GRACE}s，本轮不判僵尸"; fi
            stale=0
            sleep "$INTERVAL"
            continue
        fi
        stale=$((stale + 1))
        log "stale: online 但 MSF 无上游连接 ${stale}/${STALE_LIMIT} (pid_age=${age}s)"
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
