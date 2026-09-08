#!/system/bin/sh
# KernelSU / Magisk service.d：开机后关闭 Doze。
#
# 为什么需要：本机整机流量走 FlClash 的 tun0，而 deviceidle 进入 IDLE 后经该 tun 的流量
# 全部中断——FlClash、QQ、Termux 三个 uid 全在电池优化白名单里也没用。2026-09-08 实测：
# 息屏不动，mState=IDLE 期间连续 6 分钟 proxy/tun 全部失败，执行 deviceidle disable 变成
# ACTIVE 的瞬间恢复（proxy 0.18s / tun 0.34s），期间 Wi-Fi 链路、路由器、上游 TCP、DNS
# 与 FlClash 进程本身全部正常。Doze 没有 UI 开关，只能这样关。
#
# 代价：待机功耗上升。这台机器是专职 bot 主机（Termux 常驻唤醒锁 + QQ 前台服务 + VPN
# 常驻），Doze 本来就拦不住这些，关掉只是去掉最后一个悬崖。
# 撤销：删掉本文件并重启，或临时 `dumpsys deviceidle enable`。

# 等 system_server 起来；boot_completed 之后 deviceidle 才受理命令
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 5
done
sleep 30

LOG=/data/local/tmp/no-doze.log
{
    echo "$(date '+%F %T') applying deviceidle disable"
    /system/bin/dumpsys deviceidle disable 2>&1
    /system/bin/dumpsys deviceidle | grep -E 'mState=|mLightState=' 2>&1
} >> "$LOG" 2>&1
