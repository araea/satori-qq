# 0.36.0 — 管理界面搬进 root 管理器

知弦不再需要单独安装「知弦」应用：管理页随模块一起装好，在 KernelSU / APatch 的模块列表里点知弦即可打开。

### 变化

- **模块网页界面**（`webroot/`）：原应用首页与设置页的内容——状态卡、客户端数与连续在线、连接链路、连接地址与令牌、端口 / 令牌 / 四个运行偏好的设置、模块开关、常驻守护（保持在线、打开 / 重新启动 QQ、停止保活并关闭 QQ）、检查更新与一键更新、诊断报告。
- **`ctl.sh`**：页面的每个 root 动作都是它的一条子命令（`probe`、`write-config`、`set-enabled`、`guard`、`relaunch`、`update-install` …），终端里也能直接用，Magisk 用户没有网页界面时可以用它。
- **设置改存在模块目录的 `config.json`**：Zygisk 在 QQ 启动时（`preAppSpecialize`）读出交给 Java，不再经应用的 ContentProvider，也就不再有「关联启动被拦、设置读不到」的问题。升级模块时 `customize.sh` 保留它。`config.json` 坏了服务保持关闭，不会带着无令牌的默认值跑起来。`/healthz` 的 `config_status` 现在是 `module` / `file:<路径>` / `default`。
- **模块开关**改为 QQ 数据目录里的 `files/satori-qq/enabled`，仍是一两秒内生效、不用重启 QQ。
- **qqguard** 的探活端口跟着 `config.json` 的 `port` 走。
- **更新**：打开管理页时检查（6 小时内不重复，可关闭），下载后核对 SHA-256，交给 `ksud` / `apd` / `magisk` 安装，装好后可在页首「立即重启」。模块列表里的更新提示照旧。
- 删除：管理应用（`SatoriQQ.apk`）、快捷设置的两个守护磁贴（模块列表的「操作」按钮仍可切换守护）。

### 升级

刷入新模块并重启手机后，可以卸载旧的「知弦」应用（`com.satori.qq`）。应用里保存过的设置不会自动迁移：如果改过端口或令牌，在管理页重新保存一次。
