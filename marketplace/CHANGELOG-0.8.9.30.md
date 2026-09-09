## 0.8.9.30

- 修复 `<dice/>` 与 `<rps/>` 元素被整段丢弃的问题。QQ 的骰子和猜拳是两个魔法表情
  （358 / 359），Satori 元素表里没有对应标签，这两个空元素原先落进未知元素分支后
  被直接丢掉：`message.create` 返回空列表、不报错，调用方却以为消息发出去了。
  现在 `Codec` 认下这两个标签并映射到对应表情，`internal/dice`、`internal/rps`
  与 `internal/capabilities` 改用同一组常量。
- 修正 `internal/version` 与 `healthz` 上报的版本号：0.8.9.29 只改了清单，
  代码里的 `APP_VERSION` 还停在 0.8.9.28。
- 沿用上一版本的签名密钥，支持直接覆盖升级。
