# 软件图标

图标是 Material 紫（`#6750A4`，M3 主色 tone 40）底上的一条白色 S，用等宽圆帽描边画出。S 由两段椭圆弧构成，接缝在腰部，两段切线同向；脊线是斜的，与水平线成 22 度。图形绕画布中心 (54, 54) 旋转 180 度后与自身重合，视觉重心即几何中心，不做光学偏移。

画布为 108 × 108，可见区 72 × 72，安全圆直径 66。图形外缘到中心 31.5 单位，留在半径 33 的安全圆内，四周留 1.5 单位余量，圆形、方圆与方形遮罩都不裁到。描边宽 12.39 单位，等于 48dp 图标上的 5.51dp。

彩色与单色两版共用同一条路径。单色版没有底色，供主题图标使用：系统按壁纸取色，只保留图形。常驻通知也用单色版，裁剪窗口由 `generate-icons.py` 按图形外接框算出，四周留 7% 边距，不写死坐标。

`icon.svg` 是彩色源稿，`icon-monochrome.svg` 是主题单色源稿。路径按 Android 的 108 × 108 坐标绘制，只用三次贝塞尔曲线。源稿只允许 `fill` 路径与 `stroke` 描边路径两种元素。

编辑源稿后运行：

```sh
python scripts/generate-icons.py
python scripts/generate-icons.py --check
```

生成的 `res/` 矢量资源纳入 Git，普通 APK 构建无需 Python 或图像依赖。基础图标用于兼容读取传统资源的界面，v26 资源提供自适应前景与背景，v33 增加单色层。清单的 `icon` 与 `roundIcon` 引用同一套资源。

市场使用同一 SVG 导出的 512 × 512 透明 PNG。准备 `puppeteer-core` 与 Chromium 后运行：

```sh
CHROMIUM=/path/to/chromium node scripts/render-icon.cjs
```

依赖装在别处时用 `NODE_PATH` 指向其 `node_modules`。导出只加载本地 SVG，不访问网页。

常驻通知的 `StatusIcon.java` 也由单色 SVG 生成。它使用缓存的透明位图，不在 QQ 进程中解析模块资源；填充与描边都画在同一个白色画笔上，描边宽度取自源稿。通知沿用 Android 系统模板，适配不同系统的展开、深色模式与辅助功能。

设计尺寸参考 [Android 自适应图标规范](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)，配色取 [Material 3](https://m3.material.io/) 的主色与前景色角色，单色层按 [主题图标](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive#themed-app-icons) 的要求只保留图形。PNG 仅用于模块市场。
