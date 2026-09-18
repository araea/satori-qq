## 0.21.2 · 知弦

### 自查提速，healthz 回到毫秒级

0.21.1 把自查挂进了 `/healthz`，但每次都重扫 `/proc/self/smaps` 并枚举已安装列表，真机上一次
要 **15 秒**——看守按 60 秒轮询时会把它误判成离线。这一版改掉：

- 自查去掉 `smaps`，只留 maps / mounts / mountinfo / cmdline / status；
- `/healthz` 走轻量版 `shield`：只回答上一次自查的结论与自己的包名可见性，不重扫；
- 完整自查（`hits` / `findings` / `pm`）走 `POST /v1/internal/status` 的 `env` 段，结论缓存 30 秒；
- `pm` 自检只查一次 `getPackageInfo`，不再枚举已安装包。

真机实测：`/healthz` 0.003 秒；`env.hits=0`、`findings=0`、`pm.visible=false`。
