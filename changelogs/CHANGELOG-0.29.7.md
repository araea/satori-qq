# 0.29.7 — 表情包子类型图片发不出去

- 修复 0.27.1 起所有 `sub-type` 非 0 的图片（收藏表情、偷来再发的表情包、复读的表情包）发送失败：表情标记与列表摘要原本写在 `MsgElement` 上，QQ 的 `MsgElement` 没有 `picSubType` 字段，抛 `NoSuchFieldError`，外面只看到 `image send failed: buildPicElement ntm….gif/jpg/png`。现在写在内层 `PicElement` 上。
- JVM 测试补上表情标记落在图片元素上的回归用例。

验证：31 组 JVM 测试与 qqguard 测试通过；现场复现为向沙箱群发 `<img src="data:…" sub-type="1"/>` 必现 500，日志 `NoSuchFieldError: MsgElement#picSubType`。新模块需整机重启加载。
