# 软件图标

`icon.svg` 与 `icon-monochrome.svg` 是彩色和主题单色源稿。图形使用 Material 3 主色；主色同时定义在 `res/values/tokens.xml`，测试会校验二者一致。

修改源稿后生成 Android 矢量资源：

```sh
python scripts/generate-icons.py
python scripts/generate-icons.py --check
```

生成的 `res/` 文件纳入 Git，普通 APK 构建不需要 Python。通知图标也由单色源稿生成。

模块市场 PNG 从同一 SVG 导出，需要 `puppeteer-core` 与 Chromium：

```sh
CHROMIUM=/path/to/chromium node scripts/render-icon.cjs
```

依赖装在其他目录时，可用 `NODE_PATH` 指向对应的 `node_modules`。
