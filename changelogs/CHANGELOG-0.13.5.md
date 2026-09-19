## 0.13.5 · 知弦

### 修一个会让 QQ 反复崩的原生缺陷

模块的 native 层给检测库打补丁时，会在 `DT_JMPREL` 与 `DT_RELA` 两张表上一起补。`.got` 里那 24 条本来就在 `PT_GNU_RELRO` 里，而 `.data` 里还有 13 条 `R_AARCH64_ABS64`——是 libfekit 自带的 libc 指针副本，落在 `0x749000` / `0x74a000` 两页。补完它把整页 `mprotect` 成只读，于是这两页 `.data` 被冻住（装载器自己的 RELRO 只到 `0x748000`，那片只读孤岛是模块造成的）。

要命的是 libfekit 的 emutls 控制块 `index` 正好在 `base+0x749e18`，与它第一个补丁槽 `0x749ec0` 只差 `0xa8` 字节、同属一页。新起的进程里第一个访问那个 `thread_local` 的线程（`:MSF` 是 `MSFNewServiceSe`）必然去写只读页 → SIGSEGV，进程活 1~2 秒就死。

- 证据：`/data/system/dropbox` 里 82 份 `data_app_native_crash` 全是 `:MSF`、同一个签名（`libfekit __emutls_get_address+128`，pc 与写入地址恒定）；最早一份能追到 09-13（那时是另一个 libfekit build、QQ 9.3.5x），所以是长期存在、被业务状态放大，不是这一版改坏的
- 影响：`:MSF` 反复起不来，长连接与推送通道反复断。09-16 晚一次风暴里消息投递断了约 48 分钟，随后一次性补发 132 条（内部还乱序）
- 修法：从 `PT_GNU_RELRO` 算出页对齐范围，补完按它恢复——范围里的页（`.got`）回落只读（与装载器一致），范围外的（`.data`）恢复可写
- 补丁名单与数量都没动（libfekit 仍是 37 槽），过检测面不变
- 验收判据：任一 QQ 进程 `/proc/<pid>/maps` 里 libfekit 的 `.data` 段（`base+0x749000`、`base+0x74a000`）应是 `rw-p`；`dropbox` 里不再出现 `__emutls_get_address+128` 这一签名

### 上一版实际包含的改动

0.13.4 的说明写的是「只重画图标，代码与协议不变」，实际那个构建里还带着当晚早些时候的三处修复（21:08 的真机日志里能看到它们生效：`by=token-expired`、`login_state.kept=4`）：

- 看守改成「进程还在但 `/healthz` 不回时先打到前台解冻」，不再一律强停重启；解冻不消耗重启预算、也不会多一次登录
- 晚到的 `expired`（踢线后超过 15 分钟才到的那种）不再摘掉账号标记——它此前会让下一次登录从「一键」变成完整登录
- `token-expired` 那条支补上归因标记（`by=`）

升级提示：沿用包名与签名，直接覆盖升级。这一版的 native 修复要等 QQ 下次（重）启动才生效。
