## 0.8.9.13

- QQ 客户端手发消息以 `qq-client:{QQ号}` 虚拟身份投递到 Koishi，避免 Core 忽略 self 消息
- 改进 API 发送回声去重与 `onAddSendMsg` 入站处理
- 修复 C2C 私聊 `peer_id` 路由
