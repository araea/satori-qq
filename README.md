# onebot-qq

[<img alt="github" src="https://img.shields.io/badge/github-araea/onebot--qq-8da0cb?style=for-the-badge&labelColor=555555&logo=github" height="20">](https://github.com/araea/onebot-qq)

把本机 **Android QQNT** 做成 **OneBot 11 正向 WebSocket** 实现端。以 vector / LSPosed
模块跑在 QQ 主进程里，给 [ayjx](https://github.com/araea/ayjx) 用。

当前按 **QQ 9.3.55** 核验（JNI 基线来自 9.3.50 反编译）。协议面已按 ayjx 源码冻结。

- **单轨** — 原生 QQ，不转桌面协议栈。
- **正向 WS** — 只绑 `127.0.0.1:3001`，Bearer 鉴权，ayjx 主动连上来。
- **内核直连** — hook `IQQNTWrapperSession$CppProxy`，走稳定 `nativeinterface`，避开混淆的 `api.*`。
- **反检测** — Java 中和 + maps 过滤 + 进程内 seccomp；服务器踢号无法本地根治。

```
ayjx  --ws://127.0.0.1:3001-->  onebot-qq (QQ 主进程)
                                 ├─ WsServer    正向 WS
                                 ├─ OneBotHub   动作 / 事件
                                 ├─ QQClient    NT 会话
                                 └─ MapsHide    GOT + seccomp
```

## 需要

- 已 root 的 Android（KernelSU 或 Magisk）
- Zygisk（建议 Zygisk Next）+ 兼容 LSPosed API 的框架（本机是 **vector**）
- QQNT，目前为 `com.tencent.mobileqq` **9.3.55**
- 构建：JDK 21、`android.jar`（API 35）、aapt / zipalign / apksigner、[r8](https://maven.google.com/com/android/tools/r8/8.9.35/r8-8.9.35.jar)

换一台机、换一套 root、QQ 升版本：先读 [`docs/STACK.md`](docs/STACK.md)。

## 构建

必须在 f2fs 后端编（本机 `/data/media/0/dev/onebot-qq`）。`/sdcard` 是 FUSE，不能拿来构建。

```sh
curl -fsSL -o libs/r8.jar \
  https://maven.google.com/com/android/tools/r8/8.9.35/r8-8.9.35.jar   # 克隆后一次
bash build.sh                                                          # -> build/OneBotQQ.apk
cp build/OneBotQQ.apk /data/local/tmp/OneBotQQ.apk
pm install -r -d /data/local/tmp/OneBotQQ.apk
sh /data/adb/modules/zygisk_vector/cli modules enable com.onebot.qq
sh /data/adb/modules/zygisk_vector/cli scope add com.onebot.qq com.tencent.mobileqq/0
am force-stop com.tencent.mobileqq; monkey -p com.tencent.mobileqq 1
```

Xposed 桩类 `stubs/de/robv/**` 只用于编译，**不能进 dex**（`build.sh` 已排除）。

## 配置

可选，QQ 能读到即可，缺省为端口 3001、不鉴权：

`/sdcard/Android/data/com.tencent.mobileqq/files/onebot-qq.json`

```json
{
  "port": 3001,
  "host": "127.0.0.1",
  "token": "",
  "anti_detect": true,
  "maps_hide": true,
  "block_qsec_tasks": true,
  "block_qsec_reports": true,
  "observe_fekit_attach": true,
  "verbose_logs": false
}
```

`token` 非空时，ayjx 的 `access_token` 必须一致。ayjx 空 token 会跳过连接。

## 反检测

腾讯 `libfekit.so` 在 native 扫 `/proc/self/maps` 和注入痕迹，把风险信号塞进登录/心跳签名；
**踢号由服务器签发**。本模块只降低本地可见指纹，不保证不再掉线。

全栈默认开，换机与层说明见 [`docs/STACK.md`](docs/STACK.md)。不要 hook `QSec.getSign` / 改 `getFeKitAttach` 返回，也不要全局 hook ART / libc。

## 文档

| 文档 | 内容 |
| --- | --- |
| [`docs/STACK.md`](docs/STACK.md) | 反检测全栈、换机、QQ 升版本 |
| [`docs/ONEBOT11_SUPPORT.md`](docs/ONEBOT11_SUPPORT.md) | 动作与消息段 |
| [`docs/HANDOFF.md`](docs/HANDOFF.md) | 本机构建与坑 |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 代码结构与 JNI |

## 排错

```sh
logcat -d -s Q.Kernel:I Q.Maps:I Q.Kernel:E
sh /data/adb/onebot-qq/qq-onebot-watchdog.sh status
grep 0BB9 /proc/net/tcp6                    # 3001
node tests/ws-health.js
```

紧急停注入（QQ 立刻回到无模块进程）：

```sh
sh /data/adb/modules/zygisk_vector/cli scope rm com.onebot.qq com.tencent.mobileqq/0
```

能登录就不要卸 scope。踢号只认 `ACCOUNT_KICKED` / `KICK_TO_LOGIN` / `account_kicks` 增加。
端口还在但 WS 无响应，多半是 OEM 冻进程，不是踢号。
