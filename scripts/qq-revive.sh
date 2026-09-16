#!/data/data/com.termux/files/usr/bin/bash
# QQ 会话看守。以 root 运行（service.d 或 `su -c`）。
#
# 为什么需要：服务端踢线后，模块拦下 QQ 的处理入口，本机停在「内核仍报在线、上游已断开」的
# 僵尸态——消息一条收不到，也不会自己重连。看守负责把这台机器带回在线。
#
# 2026-09-16 改版。旧版的三条判据都会「立刻 force-stop 并拉起」，实测把它自己变成了伤害源：
# 09-16 11:19→13:09 两小时内 force-stop 了 QQ 约 34 次，每 3~4 分钟一次。那段时间 QQ 正在
# 登录窗口里（force-stop 后再登上去要几分钟），于是每一轮都把它掐断重来，永远登不完。
# 更要紧的是服务端视角：force-stop 不会走 AppRuntime.logout()，而那条里才有
# sendOnlineStatus(offline)（QQAppInterface 那条还会走 IKernelService.offLine(UnregisterInfo)）。
# 每次强杀都等于「人不见了但不吭声」，紧接着又用同一个号重新登录——这正是「同账号第二个
# 登录实例」的形状。所以现在：
#
#   1. 踢线后不再立刻 force-stop。先等 AFTER_KICK_WAIT 秒，再按重启预算决定是否重启。
#      （「重启前先请模块补一次干净下线」这个做法实测有害，默认关，见 OFFLINE_FIRST 那段。）
#   2. 重启有硬预算：两次重启之间至少 MIN_RESTART_GAP 秒，任何 1 小时内最多
#      MAX_RESTARTS_PER_HOUR 次。超预算只记一行 skip，不动 QQ。
#   2b. 连续 FAIL_LIMIT 次重启都没换来在线就停手（giveup），等账号自己回到在线或再来一次
#      踢线。那种情况不是「自动登录能救」，继续重启只是一串没人需要的登录尝试。
#   3. 宽限期改看自己写的状态文件：刚重启过 GRACE 秒内一律不判。
#      （旧版看 main_age，风暴里它取到的不是刚拉起来的那个进程，宽限期形同虚设。）
#
# 判据（每轮 INTERVAL 秒）：
#   kicklog  模块落盘的踢线记录行数（qk_kick.log）。行数增长 = 这次踢线刚被拦下。
#   online   /healthz 自报的内核在线状态。
#   upstream MSF 进程到服务端的存活 TCP 连接数，回环不算。健康时稳定在 1 条以上。
#   端口一直不通 -> 连续 OFFLINE_LIMIT 轮后拉起 QQ（进程没了/没起来）。
#   设备自己没网 -> 只记一行，不动 QQ（重启也连不上）。
#
# 停止：kill $(cat $QQ_REVIVE_PIDFILE)；或注释掉 service.d 里的启动行。
set -u
# 系统工具优先。这条顺序是有代价换来的：Termux 的 $PREFIX/bin/am 是个转发给 Termux:API 的
# 脚本，**以 root 跑不通**（它要连 Termux:API 的本地 socket）。原来把 Termux 的 bin 放在最前，
# 于是 restart_qq 里的 `am force-stop` 一直静默失败——日志照样写 "restart: ..."，QQ 主进程
# 的 pid 却一个都没变。2026-09-15 17:29 那次真踢线上实测到：看守在 17:29:48 记了重启，
# 主进程 30047 一直活着。系统侧的 am/monkey/curl/pgrep/stat/ping 在 Android 16 上都有。
export PATH=/system/bin:/system/xbin:/data/data/com.termux/files/usr/bin:${PATH:-}

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
# 重启预算。默认值偏保守：这台机器上「晚几分钟恢复」远比「被服务端再踢一次」便宜。
MIN_RESTART_GAP=${QQ_REVIVE_MIN_RESTART_GAP:-600}
MAX_RESTARTS_PER_HOUR=${QQ_REVIVE_MAX_RESTARTS_PER_HOUR:-3}
# 连续几次重启都没换来在线就停手。默认 2：一次踢线重启就能在线回来，第二次还不行，
# 就不是「自动登录能救」的情形了。
FAIL_LIMIT=${QQ_REVIVE_FAIL_LIMIT:-2}
# 一次重启要给它多久才判定「没换回在线」。原来是在 restart_qq 末尾立刻 +1，
# 于是 2026-09-16 21:06:39 重启、21:06:51 就 giveup（只隔 12 秒），而账号 21:10:22
# 自己 recovered —— 登录本来就慢，立刻判失败会让看守过早停手。
RECOVER_CHECK=${QQ_REVIVE_RECOVER_CHECK:-300}
# 解冻：进程还在但 /healthz 无响应时，多半是被冻住（/proc/<pid>/wchan 是 do_freezer_trap）。
# 打到前台就能解冻，**不需要 force-stop** —— 强停会多一次重新登录，也挡不住下一次冻结。
# 判据（2026-09-16 实测校正）：wchan=do_freezer_trap + /sys/fs/cgroup/apps/uid_<qq uid>/cgroup.freeze=1
#   （本机是 ColorOS 的 OplusHansManager，按 uid 冻、而且**前台服务不在它的判据里**，
#    所以 dumpsys 里 FGS 正常也可能照样被冻）。oom_score_adj=200 不是「掉 cached 档」，
#    200 是前台服务/PERCEPTIBLE 档，被冻时才抬到 1001，别拿 200 当判据。
THAW_WAIT=${QQ_REVIVE_THAW_WAIT:-25}
THAW_LIMIT=${QQ_REVIVE_THAW_LIMIT:-3}
# 踢线之后先等一会儿再动手：给模块把「干净下线」发出去的时间，也避开踢线后立刻重登。
AFTER_KICK_WAIT=${QQ_REVIVE_AFTER_KICK_WAIT:-120}
# 重启前先请模块补一次干净下线（走 AppRuntime.logout 那条，服务端才收得到 offline）。
# **默认关**：2026-09-16 实测那条路会把登录票据一起放掉，账号从已登录列表里被摘掉、重启后
# 停在登录页连自动登录都回不来，比被踢一次更麻烦。确认凭据没救了才打开。
OFFLINE_FIRST=${QQ_REVIVE_OFFLINE_FIRST:-0}
KICK_LOG=${QQ_REVIVE_KICK_LOG:-/data/data/${PKG}/files/qk_kick.log}
# 绝对路径，别再让 PATH 决定杀不杀得掉 QQ。
AM=${QQ_REVIVE_AM:-/system/bin/am}
MONKEY=${QQ_REVIVE_MONKEY:-/system/bin/monkey}

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
# 重启历史。宽限期与重启预算都从它读，进程重启后依然成立。
STATEFILE=${QQ_REVIVE_STATEFILE:-$RUNDIR/qq-revive.state}
# 停手状态。giveup 必须落盘：看守开机由 service.d 拉起，重启一次内存里的计数就归零，
# 「连续两次没换回在线就停手」会变成「每 10 分钟再试一次，永远试下去」。
FLAGFILE=${QQ_REVIVE_FLAGFILE:-$RUNDIR/qq-revive.flags}

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

now() { date +%s; }

# 重启历史：一行一个 epoch，只留最近一小时。空文件回空串。
restart_history() {
    [ -r "$STATEFILE" ] || return 0
    awk -v t="$(now)" '$1 > t - 3600 { print $1 }' "$STATEFILE" 2>/dev/null
}

record_restart() {
    local keep
    keep=$(restart_history)
    { printf '%s\n' "$keep"; now; } | grep -v '^$' | sort -n > "$STATEFILE.tmp" 2>/dev/null
    mv "$STATEFILE.tmp" "$STATEFILE" 2>/dev/null
    chmod 0600 "$STATEFILE" 2>/dev/null
}

last_restart() { restart_history | tail -n 1; }

# 停手状态读写。文件是一行一个 key=value，缺省回 0。
flag_get() {
    [ -r "$FLAGFILE" ] || { printf '0'; return; }
    awk -F= -v k="$1" '$1 == k { v = $2 } END { print (v == "" ? "0" : v) }' "$FLAGFILE" 2>/dev/null
}

flag_set() {
    local tmp="$FLAGFILE.tmp"
    { grep -v "^$1=" "$FLAGFILE" 2>/dev/null; printf '%s=%s\n' "$1" "$2"; } > "$tmp" 2>/dev/null \
        && mv "$tmp" "$FLAGFILE" 2>/dev/null
    chmod 0600 "$FLAGFILE" 2>/dev/null
}

# 把停手状态清掉并落盘：账号回到在线、或又拦下一次踢线时调用。
reset_stop_state() {
    [ "$giveup" -eq 0 ] && [ "$restarts_no_online" -eq 0 ] && return 0
    giveup=0
    restarts_no_online=0
    flag_set giveup 0
    flag_set failures 0
}

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

device_online() {
    # toybox 与 procps 的 ping 在「等多久」这个参数上不一致：-W 是等一个回包，-w 是整体超时。
    ping -c 1 -W 3 "$PING_HOST" >/dev/null 2>&1 && return 0
    ping -c 1 -w 3 "$PING_HOST" >/dev/null 2>&1
}

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

# QQ 主进程已经跑了多少秒；进程不在时回 -1。只当参考，宽限期不用它。
main_age() {
    local pid hz up st
    pid=$(pgrep -f "^$PKG$" 2>/dev/null | head -1)
    [ -n "$pid" ] || { printf '%s' -1; return; }
    hz=$(getconf CLK_TCK 2>/dev/null || echo 100)
    st=$(awk '{print $22}' "/proc/$pid/stat" 2>/dev/null)
    up=$(cut -d' ' -f1 /proc/uptime 2>/dev/null)
    [ -n "$st" ] && [ -n "$up" ] || { printf '%s' -1; return; }
    awk -v u="$up" -v s="$st" -v h="$hz" 'BEGIN{printf "%d", u - s/h}'
}

# 请模块补一次干净下线：那条路会走 AppRuntime.logout()，服务端才收得到 offline。
# 失败只是少一次礼貌，不影响后面的重启。
ask_clean_offline() {
    [ "$OFFLINE_FIRST" = "1" ] || return 0
    curl -s --max-time 8 --noproxy '*' -X POST \
        "http://127.0.0.1:$PORT/v1/internal/offline" >/dev/null 2>&1
}

# 重启预算：两次之间至少 MIN_RESTART_GAP 秒，1 小时内最多 MAX_RESTARTS_PER_HOUR 次。
can_restart() {
    local last n
    last=$(last_restart)
    if [ -n "$last" ]; then
        if [ $(( $(now) - last )) -lt "$MIN_RESTART_GAP" ]; then
            printf 'cooldown %ss' "$(( MIN_RESTART_GAP - ($(now) - last) ))"
            return 1
        fi
    fi
    n=$(restart_history | grep -c . )
    if [ "$n" -ge "$MAX_RESTARTS_PER_HOUR" ]; then
        printf 'budget %s/%s in 1h' "$n" "$MAX_RESTARTS_PER_HOUR"
        return 1
    fi
    return 0
}

# 把被冻住的 QQ 打到前台。不消耗重启预算，也不会多一次登录。
thaw_qq() {
    log "thaw: $PKG 进程还在但 /healthz 无响应，打到前台解冻（第 ${thaws}/${THAW_LIMIT} 次，不重启）"
    "$MONKEY" -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep "$THAW_WAIT"
}

restart_qq() {
    local why=$1 blocked before after rc
    if [ "$giveup" -ne 0 ]; then
        log "skip: 想重启（$why）但已经放弃自动重启（连续 ${restarts_no_online} 次没换来在线），等账号回到在线或人工处理"
        return 0
    fi
    if ! blocked=$(can_restart); then
        log "skip: 想重启（$why）但 $blocked，不动 QQ"
        return 0
    fi
    log "restart: $why"
    ask_clean_offline
    record_restart
    before=$(pgrep -f "$PKG" 2>/dev/null | tr '\n' ' ')
    "$AM" force-stop "$PKG" >/dev/null 2>&1
    rc=$?
    sleep 3
    if pgrep -f "$PKG" >/dev/null 2>&1; then
        # 杀了还活着：多半是 am 取到了别的东西（Termux 的 am 以 root 跑不通），必须喊出来。
        # 一次失败的重启如果只留在日志里像成功，僵尸会话就永远等不到救。
        log "restart: force-stop 之后 $PKG 仍在 (am=$AM rc=$rc，重启前 pid: $before)，改用 kill -9 兜底"
        for p in $(pgrep -f "$PKG" 2>/dev/null); do kill -9 "$p" 2>/dev/null; done
        sleep 2
    fi
    "$MONKEY" -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    # monkey 是异步的，拉起后进程要过几秒才出现；这里只做记录，不据此判定失败。
    sleep 8
    after=$(pgrep -f "$PKG" 2>/dev/null | tr '\n' ' ')
    if [ -z "$after" ]; then
        log "restart: 拉起 8 秒后还没看到 $PKG 进程（monkey=$MONKEY），交给下一轮判据"
    else
        log "restart: 进程 $before -> $after"
    fi
    # 这次重启算不算失败，留给主循环在 RECOVER_CHECK 秒后判（登录要时间，不能立刻定罪）。
    sleep "$RECOVER_WAIT"
}

# 停手状态。主循环里还会用，--check 也要读，所以在这里就赋上初值；值从落盘的旗标读回来，
# 看守被 service.d 重启一次也不会把「已经放弃自动重启」这条忘掉。
restarts_no_online=$(flag_get failures)
giveup=$(flag_get giveup)
thaws=0
counted_restart=$(flag_get counted)
[ "$counted_restart" = "0" ] && counted_restart=""

# 只看一轮判据、不动 QQ，用来确认探针本身工作正常。
if [ "${1:-}" = "--check" ]; then
    hz=$(healthz)
    echo "upstream_links: $(upstream_links)"
    echo "main_age: $(main_age)s (grace ${GRACE}s)"
    echo "kick_log: $(kick_log_lines) 行 ($KICK_LOG)"
    echo "restarts_1h: $(restart_history | grep -c .) (上次 $(last_restart || echo -), gap ${MIN_RESTART_GAP}s, 上限 ${MAX_RESTARTS_PER_HOUR}/h)"
    echo "giveup: ${giveup} (连续 ${restarts_no_online} 次重启没换回在线，上限 ${FAIL_LIMIT})"
    echo "thaws: ${thaws} (解冻计数，上限 ${THAW_LIMIT}，超过才走重启预算)"
    echo "recover_check: ${RECOVER_CHECK}s（重启后给这么久才判失败；已计过的那次 ${counted_restart:-无}）"
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
log "watchdog start pid=$$ pkg=$PKG interval=${INTERVAL}s stale_limit=$STALE_LIMIT grace=${GRACE}s restart<=${MAX_RESTARTS_PER_HOUR}/h gap=${MIN_RESTART_GAP}s fail_limit=${FAIL_LIMIT} thaw_limit=${THAW_LIMIT}"

stale=0
offline=0
logouts=0
last_kicklog=""
last_kicks=""
kick_at=0
# giveup / restarts_no_online 不在这里清零：它们的值来自落盘的旗标（上面读过），
# 清零就等于让「连续失败就停手」在这次启动里失效。

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
                # 进程在、端口不听或听了不回：先当成被冻住，打到前台试试。
                # 这样既不消耗重启预算，也不会多一次重新登录。
                if [ "$thaws" -lt "$THAW_LIMIT" ]; then
                    thaws=$((thaws + 1))
                    thaw_qq
                elif device_online; then
                    log "thaw: 连续 ${thaws} 次解冻都没恢复，改走重启预算"
                    thaws=0
                    restart_qq "解冻 ${THAW_LIMIT} 次无效，QQ 进程还在"
                else
                    log "skip: 解冻 ${thaws} 次无效但设备没网，先不动 QQ"
                fi
            elif device_online; then
                thaws=0
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
    if [ "$thaws" -ne 0 ]; then
        log "thaw: 端口恢复，重置解冻计数（之前连续 ${thaws} 次）"
        thaws=0
    fi

    online=$(field "$hz" online)
    kicks=$(field "$hz" blocked_kicks)
    [ -n "$kicks" ] || kicks=0

    # 上一次重启到点了还没换回在线 → 计一次失败。计过的不重复计。
    last_r=$(last_restart)
    if [ -n "$last_r" ] && [ "$last_r" != "$counted_restart" ] \
            && [ $(( $(now) - last_r )) -ge "$RECOVER_CHECK" ] && [ "$online" != "true" ]; then
        counted_restart=$last_r
        flag_set counted "$counted_restart"
        restarts_no_online=$((restarts_no_online + 1))
        flag_set failures "$restarts_no_online"
        log "fail: 上次重启（$(( $(now) - last_r ))s 前）至今没换回在线，计第 ${restarts_no_online}/${FAIL_LIMIT} 次失败"
        if [ "$restarts_no_online" -ge "$FAIL_LIMIT" ]; then
            giveup=1
            flag_set giveup 1
            log "giveup: 连续 ${restarts_no_online} 次重启都没换来在线，停止自动重启（账号重新上线或再来一次踢线后自动恢复）"
        fi
    fi

    # 账号回到在线：把「连续几次重启都没换回在线」这笔账清掉，重新允许重启。
    if [ "$online" = "true" ] && [ "$restarts_no_online" -ne 0 ]; then
        log "recovered: 账号已回到在线，重置重启计数（之前连续 ${restarts_no_online} 次没换回在线）"
        reset_stop_state
        counted_restart=$(last_restart)
        flag_set counted "$counted_restart"
    fi

    # 落盘的踢线记录：行数增长说明这一轮里刚有踢线被拦下。跨进程重启还看得见，
    # 而 /healthz 里的 blocked_kicks 会随 QQ 重启归零。
    kicklog=$(kick_log_lines)
    if [ -n "$last_kicklog" ] && [ "$kicklog" -gt "$last_kicklog" ]; then
        log "kick: 踢线记录 ${last_kicklog}->${kicklog} 行 ($(kick_log_tail))"
        last_kicklog=$kicklog
        # 把进程内的那个计数也一起对齐：同一次踢线两处都会涨（qk_kick.log 增行 + blocked_kicks +1），
        # 不对齐的话下一轮会把它当第二次踢线再报一次、并把 AFTER_KICK_WAIT 重新计时。
        last_kicks=$kicks
        stale=0
        logouts=0
        reset_stop_state
        kick_at=$(now)
        sleep "$INTERVAL"
        continue
    fi
    last_kicklog=$kicklog

    if [ -n "$last_kicks" ] && [ "$kicks" -gt "$last_kicks" ]; then
        log "kick: blocked_kicks ${last_kicks}->${kicks} ($(field "$hz" last_kick))"
        last_kicks=$kicks
        stale=0
        logouts=0
        reset_stop_state
        kick_at=$(now)
        sleep "$INTERVAL"
        continue
    fi
    last_kicks=$kicks

    # 刚重启过：宽限期从自己写的状态文件读，不看 main_age。
    last=$(last_restart)
    if [ -n "$last" ] && [ $(( $(now) - last )) -lt "$GRACE" ]; then
        [ "$logouts" -eq 0 ] && [ "$stale" -eq 0 ] \
            && log "grace: 距上次重启 $(( $(now) - last ))s < ${GRACE}s，本轮不判"
        logouts=0
        stale=0
        sleep "$INTERVAL"
        continue
    fi

    # 刚被踢过：等 AFTER_KICK_WAIT 秒，让模块把干净下线发出去，避开踢线后立刻重登。
    if [ "$kick_at" -gt 0 ]; then
        waited=$(( $(now) - kick_at ))
        if [ "$waited" -lt "$AFTER_KICK_WAIT" ]; then
            sleep "$INTERVAL"
            continue
        fi
        kick_at=0
        log "kick: 踢线后已等 ${waited}s，按重启预算处理"
        if device_online; then
            restart_qq "踢线后会话已作废（up 由模块记录）"
        else
            log "skip: 踢线后设备没网，先不动 QQ"
        fi
        continue
    fi

    # 已经退出登录：模块还活着、端口还在，但内核不在线，靠 STALE 判据看不出来（那条要求
    # online=true）。这时只能重启 QQ 让它按 mmkv 里的自动登录设置登回来。
    if [ "$online" != "true" ]; then
        logouts=$((logouts + 1))
        stale=0
        log "logout: /healthz online=false ${logouts}/${LOGOUT_LIMIT} (pid_age=$(main_age)s)"
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
        stale=$((stale + 1))
        log "stale: online 但 MSF 无上游连接 ${stale}/${STALE_LIMIT} (pid_age=$(main_age)s)"
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
