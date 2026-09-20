#!/system/bin/sh
# KernelSU / ReSukiSU service.d：开机恢复 qqguard 状态（独立 watchdog，root 运行）。
#
# 装法（不用知弦模块 zip 时的最小安装）：
#   mkdir -p /data/adb/satori-qq
#   cp scripts/qqguard.sh          /data/adb/satori-qq/qqguard.sh
#   cp scripts/98-qqguard.sh       /data/adb/service.d/98-qqguard.sh
#   chmod 0755 /data/adb/satori-qq/qqguard.sh /data/adb/service.d/98-qqguard.sh
#
# 若通过 KernelSU 刷入知弦模块 zip，模块自带的 service.sh 已经会做同一件事；这条脚本重复
# 存在也只会在 qqguard 的 pidfile 上去重，不会起两个 watchdog。
#
# 开机只按落盘的 MODE 恢复：ARMED 才应用系统配置并启动 watchdog；PAUSED 什么都不做。
# 想停：qqguard stop，或删掉本文件并重启。

for src in /data/adb/modules/satori_qq/qqguard.sh /data/adb/satori-qq/qqguard.sh; do
    [ -f "$src" ] || continue
    mkdir -p /data/adb/satori-qq
    cp -f "$src" /data/adb/satori-qq/qqguard.sh 2>/dev/null
    chmod 0755 /data/adb/satori-qq/qqguard.sh 2>/dev/null
    break
done

[ -x /data/adb/satori-qq/qqguard.sh ] || exit 0

# 后台跑：boot 要等 boot_completed 再补一段稳定期，不能卡住 service.d。
setsid /data/adb/satori-qq/qqguard.sh boot </dev/null >>/data/adb/satori-qq/boot-guard.log 2>&1 &
