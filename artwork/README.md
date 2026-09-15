# 软件图标

采用 Material 3 Expressive 的双色图形与柔和轮廓：浅鸢尾紫底、深紫与青绿的对话气泡，以留白分隔，表达双向消息连接。单色版保留两个气泡和消息线。

`icon.svg` 是彩色源稿，`icon-monochrome.svg` 是主题单色源稿。路径按 Android 的 108 × 108 坐标绘制，主要图形集中在中心安全区域。SVG 预览裁取中心 72 × 72 并加 22 单位圆角；自适应图标使用完整前景与背景，由系统裁切。

编辑源稿后运行：

```sh
python scripts/generate-icons.py
python scripts/generate-icons.py --check
```

生成的 `res/` 矢量资源纳入 Git，普通 APK 构建无需 Python 或图像依赖。基础图标用于兼容读取传统资源的界面，v26 资源提供自适应前景/背景，v33 增加单色层；清单的 `icon` 与 `roundIcon` 引用同一套资源。

市场使用同一 SVG 导出的 512 × 512 透明 PNG。准备 `puppeteer-core` 与 Chromium 后运行：

```sh
CHROMIUM=/path/to/chromium node scripts/render-icon.cjs
```

依赖装在别处时用 `NODE_PATH` 指向其 `node_modules`。导出只加载本地 SVG，不访问网页。

设计尺寸参考 [Android 自适应图标规范](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。

常驻通知的 `StatusIcon.java` 也由单色 SVG 生成，使用缓存的透明位图，避免在 QQ 进程中解析模块资源。通知沿用 Android 系统模板，以适配不同系统的展开、深色模式与辅助功能。

设计依据：[Material 3 Expressive](https://m3.material.io/)。图形使用矢量源稿，PNG 仅用于模块市场。
