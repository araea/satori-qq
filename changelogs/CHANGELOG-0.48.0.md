# 知弦 0.48.0：清理未成立的假头衔实验，补群主改等级名

## 普通成员伪造头衔：当前 QQ 版本不可行

0.46/0.47 的本地实验逐项验证后，所有已知入口都被服务端或内核挡住：

- `setIdentityTitleInfo` 上报 `300/301`：普通成员被服务器回 `20000 medal not available`。
- `0x8FC_2` 专属头衔：普通成员给自己写返回 `oidb=1013`，只有群主可用。
- `setGroupIdentityLevelInfo` 改群等级名：普通成员被服务器回 `code=11 no permission`。
- 本地成员缓存 `updateMemberInfoByMqq`：能改成员列表里的专属文字，但发出的消息仍是真实等级 `GroupHonor(10315)`。
- 直接给消息塞 `MsgAttributeInfo` key 2 的 `GroupHonor`：内核会用自己的真实成员数据覆盖。
- 应用标识、互动标签：只有预定义目录，不能承载「群主/管理员」角色文本。

因此不再保留这些会误导人的实验动作：`title_honor`、`member_cache_honor`、`msg_attrs`、`identity_app_tag`、`identity_interaction_tag`、`identity_spec` 已移除。`title_list` / `title_wear` 保持原样，仍只做服务器允许的真实身份头衔与群主专属头衔。

## 新增：群主批量改等级名

`identity_level_names` 现在按官方 `GIMLevelName {level, name}` 形状构造请求，并在群主群实测通过：

- 请求体：`level_names: [{level:1,name:"青铜"}, …]`，`level_flag=1`、`level_new_flag=1`、`use_new_level=0`。
- 群主调用返回 `code=0`，回读 `identity_manage` 能看到新名字。
- 普通成员调用返回 `code=11 no permission`，不会误报成功。
- `identity_manage` 保留为只读目录，方便查看等级名、展示开关与可佩戴标识。

## 说明

假头衔插件已在知微 1.44.0 中移除。想显示「群主/管理员」字样，目前只有两条真实路径：机器人确实是该群群主，或群主给机器人写专属头衔。普通成员凭空伪造身份在当前 QQ 9.3.80 上没有可用的服务端通道。
