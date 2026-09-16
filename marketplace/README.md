# 知弦 · Satori QQ

在 Android QQ 进程内提供本机 Satori v1 服务，供 Koishi `adapter-satori` 等客户端连接，仅监听 `127.0.0.1`。

已核验 QQ 9.3.55 与 9.3.60.40970（NT），要求 Android 8.0 及以上。

## 知弦应用

普通版提供 Material 3 Expressive 管理界面，支持深浅色、系统动态配色、大字体与按压形状反馈：

- **状态**：QQ、本机服务、客户端数量、在线时长；一键复制实际运行端口的连接地址。
- **设置**：端口、令牌、状态通知、前台保活、自动唤醒与 Wi-Fi 保持。区分未保存、已保存和已生效；重建界面保留草稿。
- **诊断**：应用与运行版本、接口检查、离线和请求错误计数；复制或保存不含令牌、账号、消息内容的报告。

图标用企鹅和珊瑚红围巾呼应 QQ，白色腹部化作对话气泡；启动器、主题图标、通知与市场使用同源矢量。

<details>
<summary>查看应用界面</summary>

<img src="https://raw.githubusercontent.com/araea/satori-qq/master/artwork/screenshots/status-light.png" width="260" alt="知弦状态页" /> <img src="https://raw.githubusercontent.com/araea/satori-qq/master/artwork/screenshots/settings-dark.png" width="260" alt="知弦深色设置页" /> <img src="https://raw.githubusercontent.com/araea/satori-qq/master/artwork/screenshots/diagnostics-light.png" width="260" alt="知弦诊断页" />

原生界面测试截图，状态使用演示数据；实际配色随系统主题变化。

</details>

## 安装

1. 安装 `SatoriQQ.apk`
2. 在 Xposed 兼容框架中启用模块，把 QQ 加入作用域
3. 重启 QQ
4. 打开「知弦」，在状态页检查 QQ、本机服务与客户端连接

需要隐藏模块清单标记时，先用普通 APK 完成启用与作用域设置，再覆盖安装同版本的 `SatoriQQ.stealth.apk`。stealth 版没有桌面界面，但保留已保存的配置；需要修改设置或作用域时，覆盖安装同版本普通包。两个版本的包名、签名及数据保持一致。

## 连接

```yaml
plugins:
  adapter-satori:
    endpoint: 'http://127.0.0.1:3001'
    token: ''
```

默认端口 `3001`，默认不校验令牌。可在知弦设置页配置，保存后需重新启动 QQ；高级配置文件放在：

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

### 系统限制关联启动时

部分 ColorOS 设备会拦截 QQ 在后台启动知弦的配置提供程序。请在**系统设置 → 应用 → 关联启动**里允许知弦；也可先打开知弦，再重启 QQ。设置未读取时页面会明确提示，QQ 暂时继续使用原文件/默认配置。stealth 版同样需要允许关联启动，建议在普通版完成设置后再切换。
