# 知弦 0.44.0：假头衔

QQ 的群身份头衔（群主、管理员、群等级）由客户端向服务器上报一枚 `titleId`，
服务器再广播给群成员。这个上报口子只认 `groupId + titleId`，不核对佩戴者是不是
真的群主或管理员，于是普通成员也能把「群主 / 管理员」戴到自己头上——群友说的
假头衔。通常撑不了多久，服务器下一轮同步就会收回，所以适合配合自动续报玩。

## 新增

- `internal/title_list`：读群身份目录——群主（300）、管理员（301）、群等级头衔，
  以及当前戴着哪一枚。参数 `guild_id`，可选 `user_id`。
- `internal/title_wear`：佩戴 / 更换 / 摘下自己头上的群身份头衔。
  - `role`：`owner` / `admin` / `level` / `off`，也认中文「群主」「管理员」「等级」「摘」。
  - `title_id`：直接指定编号。
  - `name`：按群身份目录里的名字挑。
  - `title`：自定义文字，走名片兜底通道（`0x8FC_3` 同时带上头衔字段）。
  - `strategy`：`wear`（内核 `setIdentityTitleInfo`）/ `card`（`0x8FC_3` 兜底）/ `auto`。
  - `verify`：回读身份列表确认是否真的戴上。
- 别名：`group_title_wear`、`fake_title`、`wear_title`、`identity_title_wear`。

## 说明

- 这是「自己给自己戴」的动作，不改别人的头衔，也不碰群管理权限。
- 服务器可能接受上报却不立刻显示；`accepted` 与 `worn` 分开返回。
- 自动续报、轮换与随机切换在 acumen 的「假头衔」插件里实现。
