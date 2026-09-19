## 0.8.9.40

0.8.9.39 发布后做暴露面复核时发现两处自带模块名的线程，修掉。

- 模块自己的两个线程用模块名命名（`satori-self-send`、`satori-channel-unmute`），而 `/proc/<pid>/task/*/comm` 是外部可读的，等于把模块名写在 QQ 进程里。改成 `pool-8-thread-N` 与 `pool-9-thread-1`，与 QQ 自身的 `pool-N-thread-M` 风格一致。用 `scripts/qq-satori-exposure-audit.sh` 的 `suspicious_thread_names` 复核，已归零
- 顺带明确：新起线程不要用模块名，`docs/ANTIDETECT.md` 里记了这条与复核口径

其余与 0.8.9.39 相同（官方 internal 路由、23 个新内核动作、token 扫描按符号集合接管、`/proc/*/mem` 拒读、memfd 改名、自检与看守落盘位置迁移、看守宽限期）。

版本号 0.8.9.40（versionCode 75），沿用同一签名密钥，支持从 0.8.9.39 直接覆盖升级。
