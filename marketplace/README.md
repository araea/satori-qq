# satori-qq

本机 QQ 的 Satori v1 实现端。任何 Satori 协议客户端均可连接（如 Koishi `adapter-satori`）。

当前按 QQ 9.3.60.40970（NT）核验。

## 使用

1. 在 vector 启用本模块，作用域勾选 QQ（`com.tencent.mobileqq`）。
2. 安装后重启 QQ。
3. 客户端连接 `http://127.0.0.1:3001`。Koishi 配置示例：

```yaml
plugins:
  adapter-satori:
    endpoint: 'http://127.0.0.1:3001'
    token: ''
```

## 常驻与通知

- **前台保活**（`foreground_keepalive`，默认开）：服务在线时将 QQ 提升为前台服务，
  降低被系统回收与冻结的概率；强停或划掉 QQ 即断开，不自动复活。首次在线会申请
  一次电池优化豁免。
- **状态通知**（`status_notification`，默认开）：以 QQ 身份显示静默常驻通知，随状态
  切换「运行中 / 等待登录 / 服务异常」。
- **唤醒锁开关**（`wake_lock_control`，默认开）：常驻通知带「获取 / 释放唤醒锁」按钮，
  避免 Doze 下 CPU 与网卡休眠；默认不持有。
- **健康检查**：`GET http://127.0.0.1:3001/healthz`，本地免鉴权，返回在线状态与
  保活诊断。

## 源码

https://github.com/araea/satori-qq
