## 0.8.9.18

- 反检测增强（对齐 Duck Detector `LSPosedPackageProbe` 的实测命中路径）：构建新增隐身变体 `build/SatoriQQ.stealth.apk`，清单不含任何 `xposed*` meta-data，消除跨应用包扫描（`getInstalledApplications(GET_META_DATA)`）对本模块的唯一可见命中。
- 引导 APK（`SatoriQQ.apk`）保持原样：vector/LSPosed 首次列模块与注册仍需 meta-data；守护进程对已启用模块只认 `assets/xposed_init`，`PACKAGE_REPLACED` 不触发再校验，因此「先启用、再 `pm install -r` 隐身包」可长期共存，升级版本照常。
- `./build.sh` 第 7 步用 `aapt dump xmltree` 断言双 APK 的 meta-data 面（引导 ≥4 条、隐身 =0），防止回归；新增 `ManifestStealthTest` 校验双清单的包名/版本/标签同步契约。
