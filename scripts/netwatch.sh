#!/data/data/com.termux/files/usr/bin/bash
# 网络异常取证探针：每 60s 一行，把「物理链路 / FlClash / 出站 / 模块 / 电源」分开记。
# 下次再断网时直接看 ~/netwatch.log，就知道断在哪一层，不用再靠推理。
#
#   link   ping 默认网关，走 wlan0 直连，完全绕开 tun0 —— 只反映物理链路
#   proxy  经 127.0.0.1:7890 请求，任何 HTTP 状态码都算通；timeout 才是 FlClash 出问题
#   tun    普通出站（QQ 与 ayjx 实际走的路径：tun0 -> clash -> 上游）
#   satori 模块 /healthz 的 online/连接数
#   screen/idle/clash  电源与冻结状态，用来判断是不是系统在关网
export PATH=/system/bin:$PATH
LOG="$HOME/netwatch.log"
PIDFILE="$HOME/.netwatch.pid"
# 用 pidfile 停止，别用 pkill -f：这个脚本名会出现在调用者自己的命令行里，
# pkill 会把发起停止的那个 shell 一并杀掉。
echo $$ > "$PIDFILE"

while true; do
    ts=$(date +%FT%T)

    gw=$(ip route show 2>/dev/null | /system/bin/grep -m1 '^default via' | awk '{print $3}')
    [ -n "$gw" ] || gw=192.168.0.1
    rtt=$(ping -c 3 -W 3 -q "$gw" 2>/dev/null | /system/bin/grep -o 'rtt.*' | cut -d= -f2 | cut -d/ -f2)
    if [ -n "$rtt" ]; then link="ok/${rtt}ms"; else link="FAIL"; fi

    proxy=$(curl -s -o /dev/null -w '%{http_code}' --max-time 12 \
            -x http://127.0.0.1:7890 https://223.5.5.5/ 2>/dev/null)
    [ -n "$proxy" ] && [ "$proxy" != "000" ] || proxy=FAIL

    tun=$(curl -s -o /dev/null -w '%{http_code}' --max-time 12 --noproxy '*' \
          'https://aihot.virxact.com/api/v1/items?limit=1' 2>/dev/null)
    [ -n "$tun" ] && [ "$tun" != "000" ] || tun=FAIL

    satori=$(curl -s --max-time 6 --noproxy '*' http://127.0.0.1:3001/healthz 2>/dev/null \
             | python3 -c 'import sys,json;d=json.load(sys.stdin);print("%s/%s"%(d["online"],d["connections"]))' 2>/dev/null)
    [ -n "$satori" ] || satori=DOWN

    screen=$(su -c 'dumpsys power' 2>/dev/null | /system/bin/grep -oE 'mWakefulness=[A-Za-z]+' | head -1 | cut -d= -f2)
    idle=$(su -c 'dumpsys deviceidle' 2>/dev/null | /system/bin/grep -oE 'mState=[A-Z_]+' | head -1 | cut -d= -f2)
    # 必须用 root 找：Android 的 hidepid 让 Termux 这个 uid 看不见别的应用的进程
    # 必须用 root 找（hidepid 让 Termux 看不见别的 uid 的进程），且 comm 只有 15 个字符，
    # "com.follow.clash" 会被截成 "om.follow.clash" —— 用 -x 匹配截断后的名字才准。
    cpid=$(su -c "pgrep -x om.follow.clash" 2>/dev/null | head -1)
    if [ -n "$cpid" ]; then
        cstate=$(su -c "cat /proc/$cpid/status" 2>/dev/null | /system/bin/grep -m1 '^State:' | awk '{print $2}')
        frozen=$(su -c "cat /proc/$cpid/cgroup" 2>/dev/null | /system/bin/grep -c frozen)
        clash="${cstate:-?}/frz${frozen:-?}"
    else
        clash=GONE
    fi

    printf '%s link=%s proxy=%s tun=%s satori=%s screen=%s idle=%s clash=%s\n' \
        "$ts" "$link" "$proxy" "$tun" "$satori" "${screen:-?}" "${idle:-?}" "$clash" >> "$LOG"

    if [ "$(wc -l < "$LOG" 2>/dev/null || echo 0)" -gt 6000 ]; then
        tail -4000 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
    fi
    sleep 60
done
