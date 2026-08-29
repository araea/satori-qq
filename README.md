# onebot-qq

[<img alt="github" src="https://img.shields.io/badge/github-araea/onebot--qq-8da0cb?style=for-the-badge&labelColor=555555&logo=github" height="20">](https://github.com/araea/onebot-qq)

手机上的 QQ，可以变成一个 OneBot 机器人后端。

不是再写一个假 QQ，而是让**正在运行的那只官方 QQ** 开口说话：本仓库是一个 Xposed 模块，钻进 QQ 进程，在本机打开正向 WebSocket。像 [ayjx](https://github.com/araea/ayjx) 这样的框架连上来，就能收发消息。

我们核过的版本是 **QQ 9.3.55**（NT）。换版本等于换一套考卷，不能抄上一届的答案。

## 先认识这些词

读下去之前，请你自己能用一句话解释下面每个词。解释不清的，先去查，再回来。这篇 README 不会替你补课。

| 词 | 你要抓住的那一层意思 |
| --- | --- |
| **OneBot 11** | 机器人和 QQ 之间的那份「动作 / 事件」合同 |
| **正向 WebSocket** | 实现端在本地开端口，机器人当客户端连进来 |
| **QQNT** | 现在手机 QQ 的内核，不是十年前的协议号 |
| **root** | 这台安卓已经把最高权限交出来了 |
| **Zygisk** | 在 App 启动的极早阶段往进程里塞代码 |
| **Xposed / LSPosed / vector** | 用 Java hook 改正在跑的 App；vector 是本仓库实际用的那一家 |
| **作用域 (scope)** | 注入进哪一个 App——这里必须是 QQ，而且最好只有本模块 |
| **冷启动** | 杀掉 QQ 再打开，模块才进得了新进程 |
| **QSec** | 腾讯在 native 里做的环境检查；踢号往往是**服务器**下的决定 |

如果你已经知道「注入会留下指纹、指纹会被签名带走」，后面的风险不用我再渲染一遍。

## 它是什么，不是什么

它是官方 QQ 的一层外壳。机器人看见的是 OneBot，手机看见的还是那只 QQ。

它不是桌面协议栈，不是无 root 方案，也不是「装上就再也不会掉线」。把代码放进 QQ，腾讯可以随时请你重新登录。我们能做的是把痕迹藏得深一点，不能替服务器收回那张罚单。

协议面已经按 ayjx 实际会用的动作冻住。想知道能发什么、不能发什么，打开 [支持矩阵](docs/ONEBOT11_SUPPORT.md)——那是合同正文，这里只告诉你合同存在。

## 你的环境够不够

三件事同时成立，再谈安装：

1. 手机已 root，并有 Zygisk。
2. 有一个兼容 LSPosed API 的框架，QQ 在它的作用域里。
3. 你愿意用**正在登录的 QQ** 做实验，包括被踢、被验证。

构建机还要能编出 Android 模块（JDK、`android.jar`、aapt、能跑 D8 的 r8）。具体路径因人而异，本仓库的 `build.sh` 是一份参考实现，不是真理。

## 第一条路

自己走通这一遍。卡住的时候再去翻文档，不要一开始就对照着抄。

1. 编出 APK，装到手机上。
2. 在框架里启用模块，把作用域划给 `com.tencent.mobileqq`。
3. **冷启动** QQ。模块只在主进程里干活，子进程不用管。
4. 本机 `127.0.0.1:3001` 应当开始监听。机器人用 Bearer 连上来。

配置文件放在 QQ 自己读得到的地方，例如外部 files 目录里的 `onebot-qq.json`。端口、token、反检测开关都在那里。token 一旦写上，两端必须一致；机器人那边若是空 token，有的框架会直接不连。

想立刻停手：从作用域里拿掉 QQ。进程会变回一只普通 QQ。能登录的时候，不要慌着卸。

## 接下来你自己去问的问题

- 换一台机、换一套 root、QQ 升级了，层要怎么对齐？→ [`docs/STACK.md`](docs/STACK.md)
- 这份合同覆盖哪些动作和消息段？→ [`docs/ONEBOT11_SUPPORT.md`](docs/ONEBOT11_SUPPORT.md)
- 在这台设备上怎么编、会踩哪些坑？→ [`docs/HANDOFF.md`](docs/HANDOFF.md)
- hook 的是 QQ 里哪几扇门？→ [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)

最后一件事，留给你自己验证：端口开着、机器人却不说话，不一定是被踢了。先分清「进程被系统冻住」和「账号被服务器请出去」——这两个词，值得你在 log 里各找一次。
