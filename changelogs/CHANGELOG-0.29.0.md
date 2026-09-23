# 0.29.0 · 群头衔显示开关改走纯 JNI，扩展动作批量回归

- 修复 `title_display`：原实现靠发原始 OIDB `0x8FC_0` 封包探测显示开关，封包形状没核验过，
  真机实测发现开关关着时设头衔的调用仍然报「成功」——写头衔本身（`0x8FC_2`）和显示开关是两回事，
  写头衔的返回值不能代表开关状态。现在只走反射内核服务通道（`setIdentityTitleInfo` /
  `setGroupIdentityLevelInfo`）加本地 DB 补写（`updateLocalRankSwitch`），写完读回
  `getMemberExtInfo` / `troopExtRankFlags` 校验，不再发未核验的封包。
- `title_display` 不带 `show`/`enable` 参数时变成纯读，不再默认当写处理（旧版本会在「只读」语义下
  悄悄把开关设成开）。
- `special_title` 收窄成单一职责：只设头衔，不再顺带切换显示开关，那部分交给 `title_display` 单独管。
- 新增 `ExtraSvc` 的动作注册表（名字、别名、读写、参数说明、调用体各登记一次），`capabilities` 的
  目录、参数说明、读写分类从这份注册表生成，不再手抄第二份；写动作复用 `guarded` 的限频与熔断。
- 借着这份注册表新增约 50 个扩展动作，覆盖消息（输入状态、已读、行内键盘、重新编辑撤回消息、
  图片 OCR）、群管理（公告、精华列表、成员等级与身份、群文件增删改、加群/退群/转让、群临时会话）、
  资料与好友（长昵称、用户详情、好友备注与分组）等域，均走反射内核服务这一条 JNI 通道，不发新的
  原始封包。其中 `group_remark`、`group_shut_up_list`、`user_detail`、`mark_read` 是 0.17.0
  撤下后以扩展动作形式回归，`REMOVED_0_17` 名单同步收窄。
- `identity_title_info` / `identity_level_info` 是 `setIdentityTitleInfo` /
  `setGroupIdentityLevelInfo` 的单次调用原语，供调试或需要绕开 `title_display` 那套读回校验时用；
  日常切换显示开关仍然优先用 `title_display`。

## 风险提示

新增的批量查询与管理类动作（群文件、加群/退群/转让、好友分组等）技术上都走通，但没有全部逐条
device-verified，参数结构体字段名可能随 QQ 版本变化而静默失效——调用前建议先用 `capabilities`
或 `help` 核对参数，出问题看 `observed` 而不是只看 `compat` 的静态自检。是否要把这批动作接进
搭话或日常调用，按「客户端真的会调」取舍，见 `docs/JNI_CAPABILITIES.md` 的「搭话场景的取舍」一节。

## 迁移

`title_display` 原来「不带 `show` 就默认当写、且顺带切换」的隐式行为不再成立：现在不带参数是纯读，
要写必须显式带 `show` 或 `enable`。`special_title` 也不再接受 `show` 参数，改用 `title_display`。

安装新 APK 和模块后重启手机（Zygisk 模块只在系统启动时注册，纯 dex 变化也要等下一次重启才会
被 `modules_update` 换进 `modules`）；Acumen 侧无需改动即可继续工作。
