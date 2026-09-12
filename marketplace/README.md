# satori-qq

在 Android QQ 进程内提供本机 Satori v1 服务，供 Koishi `adapter-satori` 等客户端连接，仅监听 `127.0.0.1`。

已核验 QQ 9.3.55 与 9.3.60.40970（NT），要求 Android 8.0 及以上。

## 安装

1. 安装 `SatoriQQ.apk`
2. 在 Xposed 兼容框架中启用模块，把 QQ 加入作用域
3. 重启 QQ
4. 访问 `http://127.0.0.1:3001/healthz`，`online` 为 `true` 即就绪

需要隐藏模块清单标记时，先用普通 APK 完成启用与作用域设置，再覆盖安装同版本的 `SatoriQQ.stealth.apk`。调整作用域前先装回普通 APK。

## 连接

```yaml
plugins:
  adapter-satori:
    endpoint: 'http://127.0.0.1:3001'
    token: ''
```

默认端口 `3001`，默认不校验令牌。配置文件放在：

```text
/sdcard/Android/data/com.tencent.mobileqq/files/satori-qq.json
```

## 能做什么

- 消息收发、撤回、历史记录与事件推送
- 群、成员、好友、表态、群文件与合并转发操作
- 个性签名、昵称、头像的读写，群荣誉、群禁言与最近联系人查询
- 好友备注、置顶、消息提醒与拉黑开关
- 图片、语音、视频与文件处理
- 前台保活、状态通知、唤醒锁与富媒体重试
- 默认启用的 Java 与 Native 环境检测处理

接口清单与完整配置见[源码文档](https://github.com/araea/satori-qq)。

## 排障

强停或划掉 QQ 会停止服务。锁屏后文字能发而图片、合并转发失败，是网络问题，应关闭系统的「睡眠待机优化」或「深度睡眠」，并把 QQ 及所用代理或 VPN 加入电池优化白名单。

过检测实现参考 [QQEnhancedBypass](https://github.com/Xalsace/QQEnhancedBypass)。
