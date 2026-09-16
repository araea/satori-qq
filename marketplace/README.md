# 知弦 · Satori QQ

在 Android QQ 进程内提供 Satori v1 HTTP 与 WebSocket 服务。服务监听 `127.0.0.1:3001`，供 Koishi `adapter-satori` 等客户端连接。

已核验 QQ 9.3.55 与 9.3.60.40970（NT），要求 Android 8.0 及以上。

## 知弦应用

普通版带 Material 3 Expressive 管理界面，分状态、设置、诊断三页。状态页显示 QQ、本机服务、客户端数与在线时长，并给出一键复制的连接地址。设置页管端口、令牌、状态通知、前台保活、自动唤醒与 Wi-Fi 保持，区分未保存、已保存与已生效。诊断页给应用与运行版本、接口检查和错误计数，报告不含令牌、账号与消息内容。

图标用企鹅和珊瑚红围巾呼应 QQ。白色腹部构成对话气泡。

<details>
<summary>查看应用界面</summary>

<img src="https://raw.githubusercontent.com/araea/satori-qq/master/artwork/screenshots/status-light.png" width="260" alt="知弦状态页" /> <img src="https://raw.githubusercontent.com/araea/satori-qq/master/artwork/screenshots/settings-dark.png" width="260" alt="知弦深色设置页" /> <img src="https://raw.githubusercontent.com/araea/satori-qq/master/artwork/screenshots/diagnostics-light.png" width="260" alt="知弦诊断页" />

原生界面测试截图，状态使用演示数据；实际配色随系统主题变化。

</details>

## 运行条件

- Android 8.0 及以上
- QQ `com.tencent.mobileqq`
- 可为 QQ 启用模块作用域的 Xposed 兼容框架

## 安装

1. 安装 `SatoriQQ.apk`
2. 在框架中启用模块，把 QQ 加入作用域
3. 重启 QQ
4. 打开「知弦」，在状态页确认 QQ、本机服务与客户端已连上

需要隐藏模块清单标记时，先用普通 APK 完成启用与作用域设置，再覆盖安装同版本的 `SatoriQQ.stealth.apk`。stealth 版没有桌面界面，保留已保存的配置。要改设置或作用域时，覆盖装回同版本普通包。两个版本的包名、签名与数据一致。

## 连接

```yaml
plugins:
  server:
    port: 5140
    selfUrl: 'http://127.0.0.1:5140'
  assets-local: {}
  adapter-satori:
    endpoint: 'http://127.0.0.1:3001'
    token: ''
```

`selfUrl` 与 `server.port` 保持一致。模块默认不校验令牌。设置 `token` 后，客户端必须使用相同值。

## 配置

端口、令牌、状态通知、前台保活、自动唤醒与 Wi-Fi 保持在知弦设置页里改，保存后重启 QQ 生效。其余高级选项写在：

```text
/sdcard/Android/data/com.tencent.mobileqq/files/satori-qq.json
```

| 设置 | 默认值 | 说明 |
| --- | --- | --- |
| `port` | `3001` | 本地服务端口 |
| `token` | 空 | HTTP 与 WebSocket 鉴权令牌 |
| `media_retry_attempts` | `2` | 富媒体上传失败后的额外尝试次数 |
| `verbose_logs` | `false` | 输出调试日志 |
| `anti_detect` | `true` | Java 层环境检测处理 |
| `maps_hide` | `true` | Native 层进程信息过滤 |
| `block_turing_risk` | `true` | 停止 Turing 风控入口 |
| `block_server_kick` | `true` | 停止本地强制下线处理 |
| `fake_imei` / `fake_android_id` / `fake_serial` | 空 | 设备标识；留空用真实值，设置时应保持一致 |

过检测、限频与排队的其余开关见源码仓库里的示例文件。修改配置后重启 QQ。

## 排障

强停或划掉 QQ 会停止服务。锁屏后文字能发而图片、合并转发失败，是网络问题。应关闭系统的「睡眠待机优化」或「深度睡眠」，并把 QQ 及所用代理或 VPN 加入电池优化白名单。

模块自报在线、消息却一条收不到，多半是被服务端踢线。模块在多条处理链上拦截，并保住盘上的登录态。

### 系统拦截关联启动时

部分 ColorOS 设备会拦截知弦的配置提供程序。该程序由 QQ 在后台拉起。在**系统设置 → 应用 → 关联启动**里允许知弦，再重启 QQ。设置读不到时页面会明确提示，QQ 继续使用文件或默认配置。stealth 版同样需要放行，建议在普通版完成设置后再切换。

## 源码

[araea/satori-qq](https://github.com/araea/satori-qq)

过检测实现参考 [QQEnhancedBypass](https://github.com/Xalsace/QQEnhancedBypass)。
