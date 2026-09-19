## 0.8.9.25

- 补齐 Root、Xposed、调试器、模拟器、设备标识与 Pandora 检测路径
- 拦截 Turing 风控、QQ 环境上报、遥测与强制下线处理
- Native 层增加检测库专用的命令、符号与风险数据过滤，保留原有 GOT、`/proc` 与直接系统调用防护
- 所有 Java 返回值按实际类型生成，Native hook 仅作用于检测库，减少兼容性影响

实现参考 [QQEnhancedBypass](https://github.com/Xalsace/QQEnhancedBypass)，感谢其公开研究。
