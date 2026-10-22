# 痕迹

模块活在 QQ 的进程里，QQ 能看见的只有这几种东西：`/proc/self`（线程名、映射）、自己的 `files/`、自己建的通知渠道、自己的 JNI 函数表。这里记这几处现在是什么样、为什么不动别的。

## 落盘

QQ 数据目录下只有一个隐藏目录 `files/.svc`（0700，Java 侧 `Paths`，native 侧同路径）：开关 `enabled`、native 日志 `native.log`、SSO 失败 `sso.log`、群名快照 `group-names.tsv`、写操作审计 `writes.log`、发送诊断 `last-send.txt` / `history.txt`（后两者只在 `verbose_logs` 打开时写）。

名字里没有任何模块标识：按 `satori` / `xposed` / `zygisk` 扫文件名不会命中。`native.log` 超过 256 KiB 时在下次启动的清空（它只服务上一次启动的事后翻查）。

升级后第一次启动由 `Paths.prepare()` 做两件事：把旧的 `files/satori-qq/enabled` 搬进 `.svc/enabled`（开关状态不丢），再删掉 `files/` 下所有 `satori-` 开头的条目与 `qk_sso.log`。

旧配置的检索路径 `/sdcard/Android/data/…/satori-qq.json` 保留（模块目录的 `config.json` 没有时才读），它不由模块创建。

## 线程

线程名从 `/proc/<pid>/task/*/comm` 读得到，所以自家的线程按 JVM 线程池的默认风格命名（`pool-4-thread-1`、`pool-5-thread-1` …）。QQ 自己就有一堆 `pool-N-thread-M`。

## 通知

状态通知的渠道 id 是 `svc_status_channel`，显示名仍是「知弦服务状态」。渠道 id 宿主能枚举，显示名不能改，通知本身是功能（`status_notification` 关掉就没有常驻条目）。

## JNI

整个模块只有一处 JNI 层改动：`IQQNTWrapperSession$CppProxy.native_onSendSSOReply` 用 `RegisterNatives` 换成自己的实现（非本模块的 requestId 转交原函数指针）。这是拿裸 SSO 回包的唯一一条路，没有更轻的替代；QQ 若比对自身 native 方法的入口地址就能发现，这一点无法消除。

其余能力都走公开接口（`IKernelService` 的七个服务、OIDB 发包交给 QQ 自己签名），不改 ArtMethod、不挂 hook 引擎——这是 0.22.0 的结论：带 LSPlant 的版本功能正常但人脸验证仍判失败，零动作只注入的探针能过。

## 不做

- 不伪造 QQ 自己的安全 SDK 结论。`internal/status` 的 `qsec` 块（`EnvProbe`）只读 QQ 的 QSec 结论，不做任何干预。
- 回环端口与 `/healthz` 是功能面：端口要监听才收得到请求，`/healthz` 免鉴权是为了配置还没读到时也能探活（`qqguard` 也在用）。端口本身已经是本机可见的监听，加不加鉴权都改变不了。
- 模块的 `.so` 怎么映射由 Zygisk Next 决定（它按 memfd 装载并隐藏路径），模块不额外处理；root 侧的痕迹（`/data/adb`）由 KernelSU 与管理器负责。
- 安装包 `com.satori.qq` 若已装在机器上，QQ 用 `QUERY_ALL_PACKAGES` 能列出来。那是用户自己的应用，模块不改包名也不隐藏它。

卸载模块之前跑一次 `ctl.sh purge`：它删掉 `files/.svc` 与 `files/` 下早前版本留下的条目。卸载之后模块就没有机会自己清了。

## 自查

```sh
su -c 'ls -la /data/data/com.tencent.mobileqq/files/.svc'
su -c 'grep -c satori /proc/$(pidof com.tencent.mobileqq)/maps'      # 模块 .so 不应出现路径
su -c 'for p in /proc/$(pidof com.tencent.mobileqq)/task/*/comm; do cat $p; done | sort | uniq -c | sort -rn | head'
```
