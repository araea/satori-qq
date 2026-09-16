# 软件图标

Material 3 Expressive 的几何轮廓：浅鸢尾紫渐变底，紫墨色的一条弦。弦的两端落在同一水平线上，中间随振动起伏；弦对应模块名「知弦」，也对应 QQ 与客户端之间那条连接。整条弦落在自适应图标的安全圆内，圆形、方圆与方形遮罩都不裁切。单色版是同一条弦加粗，供主题图标与常驻通知在小尺寸下使用。

`icon.svg` 是彩色源稿，`icon-monochrome.svg` 是主题单色源稿。路径按 Android 的 108 × 108 坐标绘制，主要图形集中在中心安全区域。SVG 预览裁取中心 72 × 72，加 22 单位圆角。自适应图标使用完整前景与背景，由系统裁切。源稿只允许 `fill` 路径与 `stroke` 描边路径两种元素，生成器按此翻译。

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

设计尺寸参考 [Android 自适应图标规范](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。

常驻通知的 `StatusIcon.java` 也由单色 SVG 生成。它使用缓存的透明位图，不在 QQ 进程中解析模块资源；填充与描边都画在同一个白色画笔上，描边宽度取自源稿。通知沿用 Android 系统模板，适配不同系统的展开、深色模式与辅助功能。

设计依据：[Material 3 Expressive](https://m3.material.io/)。图形使用矢量源稿，PNG 仅用于模块市场。
