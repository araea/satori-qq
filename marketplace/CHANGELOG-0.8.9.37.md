## 0.8.9.37

适配 QQ 9.3.60.40970（versionCode 16070）。本次 QQ 更新只动了 `libfekit.so` 与 `libMSFKernel.so`，turing 系、`libQSec.so`、`libmsfbootV2.so` 与上一版逐字节相同，`qsec.qsecurity` 包逐类相同。

- 补上 QQ 9.3.60.40970 新增的上报通道：libfekit 换上了会落盘并重试 3 次的 QSec_Channel 上报器（二进制里带 `HighReliabilityReporter saveToDisk/loadFromDisk`、`QSec_Channel report retry`），命令表新增 `OidbSvcTrpcTcp.0x9c00_0`、`0x9c01_0`、`0x9c02_0`、`0x9c0c_0`、`0x9cdf_1` 五条。此前只按 `trpc.o3.report.*` 名单丢包挡不住这套上报器。这五条现在一并丢弃并回空成功，避免它继续重试
- 补上另外两个「踢回登录页」的入口。此前只拦 `NTKickProcessor.b`，被踢时界面仍会退回登录页
  - `login.ntlogin.ao.f(int, String)`：登录票据刷新失败时，错误码落在 140022014/140022015/140022016 或 `refreshMethodNeedKick` 为真
  - `login.api.impl.UidServiceImpl.kickToLoginPage()`：取不到 UID 时
- `/healthz` 与 `/v1/internal/status` 新增 `last_kick_source`，标明最近一次踢线是哪个入口拦下的（`nt-kick` / `ticket-refresh` / `uid-fail`）；`last_kick` 照旧记下参数，`ticket-refresh` 会带上服务端错误码与原文。`kick_hook` 现在是三个入口的 hook 数之和，正常为 3
- native 统计文件按进程分开写（`qk_env_maps_<进程名>.json`）。除 `:MSF` 之外的进程此前共用 `qk_env_maps_main.json`，后启动的 `:qzone` 会覆盖主进程的数字，曾让 `/healthz` 上的 `patched` 看起来从 62 掉到 21；读到的 `pid` 不是本进程时会补一个 `owner` 字段说明数据来源
- 真机逐槽核对主进程过检测状态：libfekit 的 GOT（`/proc/<pid>/mem`）六个关键槽全部指向 libmapshide 的包装，与 MSF 进程 40 个 slot 的口径一致
- 版本号 0.8.9.37（versionCode 72），沿用同一签名密钥，支持从 0.8.9.36 直接覆盖升级
