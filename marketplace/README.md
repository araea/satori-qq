# satori-qq

本机 QQ 的 Satori v1 实现端，供 Koishi `adapter-satori` 连接。

当前按 QQ 9.3.60.40970（NT）核验。

## 使用

1. 在 vector 启用本模块，作用域勾选 QQ（`com.tencent.mobileqq`）。
2. 安装后重启 QQ。
3. Koishi 配置 `adapter-satori`，`endpoint` 指向 `http://127.0.0.1:3001`。

## 源码

https://github.com/araea/satori-qq
