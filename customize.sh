#!/system/bin/sh
# 由 Magisk / KernelSU / APatch 的模块安装器 source。升级时保留管理页写的设置（config.json）。
SKIPUNZIP=0
[ "$ARCH" = arm64 ] || abort "此模块仅支持 arm64"
old=/data/adb/modules/satori_qq/config.json
[ -f "$old" ] && { cp "$old" "$MODPATH/config.json" || abort "无法保留配置"; }
set_perm_recursive "$MODPATH" 0 0 0755 0644
[ -f "$MODPATH/config.json" ] && set_perm "$MODPATH/config.json" 0 0 0600
for f in service.sh action.sh uninstall.sh ctl.sh qqguard.sh; do set_perm "$MODPATH/$f" 0 0 0755; done
ui_print "知弦：装好后在模块列表里点知弦打开管理页"
