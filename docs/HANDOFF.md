# 构建与本机环境

协议见 [`SATORI_SUPPORT.md`](SATORI_SUPPORT.md)。JNI 见 [`ARCHITECTURE.md`](ARCHITECTURE.md)。反检测见 [`STACK.md`](STACK.md)。

现场 PID / scope 以仓库外 `/storage/emulated/0/Dev/satori-qq-接手提示词.md` 为准。

## 环境

rooted **ColorOS**，KernelSU + Zygisk Next + **vector**（`zygisk_vector`）。构建在 Termux；PRoot 绑了 `/apex` `/system` `/data`。`su` 在 PRoot 里不可用。

`/sdcard` 是 FUSE，不能在里面构建。仓库：`/data/media/0/dev/satori-qq`。

## 工具链

`build.sh` 已封装。要点：

- `javac` 用 `-classpath android.jar`，不要 `-bootclasspath`
- `stubs/de/robv/**` 必须排除出 dex
- `r8.jar` gitignore，克隆后下载：`https://maven.google.com/com/android/tools/r8/8.9.35/r8-8.9.35.jar`

路径：`android.jar` → `~/android/platform/android-35/`；aapt / zipalign → `~/android/android-sdk-tools/build-tools/`。

```sh
cd /data/media/0/dev/satori-qq && bash build.sh
cp build/SatoriQQ.apk /data/local/tmp/SatoriQQ.apk
pm install -r -d /data/local/tmp/SatoriQQ.apk
sh /data/adb/modules/zygisk_vector/cli modules enable com.satori.qq
sh /data/adb/modules/zygisk_vector/cli scope add com.satori.qq com.tencent.mobileqq/0
am force-stop com.tencent.mobileqq
am start -n com.tencent.mobileqq/com.tencent.mobileqq.activity.SplashActivity
am startservice -n com.tencent.mobileqq/.app.CoreService
am startservice -n com.tencent.mobileqq/.msf.service.MsfService
```

vector cli 改的是运行中 daemon；手改 `modules_config.db` 开机前不生效。

Koishi：`adapter-satori`，`endpoint: http://127.0.0.1:3001`。`token` 为空则不鉴权。

## 注意

- `File.createTempFile` 前缀 ≥ 3 字符
- 私聊自己：retcode 0 但不投递
- `api.*` 混淆，只走 `nativeinterface` + `IQQNTWrapperSession$CppProxy`
- 不要 hook `getSign` / 改 `getFeKitAttach` 返回；不要拦 `trpc.o3.ecdh_access.*`
- 能登录就不卸 scope；踢号只认 `ACCOUNT_KICKED` / `KICK_TO_LOGIN` / `account_kicks`
- 端口在、WS 无响应 = OEM 冻进程，不是踢号
- `pm install -r` 后立刻重启 QQ；`force-stop` 不是踢号
- 参考 NapCat / Lagrange / `QQ.hap` 只当线索，最终以 9.3.55 jadx 为准
