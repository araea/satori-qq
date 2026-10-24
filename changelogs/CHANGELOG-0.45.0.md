# 知弦 0.45.0：群头衔按角色来

0.44.0 的假头衔把一个不成立的假设写进了实现：以为 `setIdentityTitleInfo` 只认
`groupId + titleId`、不校验角色，普通成员硬报 `300` / `301` 就能戴上群主 / 管理员。
9.3.80 的服务器会回 `20000 medal not available`，acumen 那条把任何 `title_wear` 错误
都说成「模块没更新」的提示又把它盖住了。这一版按 QQ 真正的权限重做，并把失败原因
原样交给调用方。

## 修复

- `title_wear` 先读身份目录，只有 `300` / `301` 真的在目录里时才走身份通道；不再对
  注定被拒的编号盲报。
- 群主可以用 `0x8FC_2` 给自己写专属头衔（`title`，最多六字，身份目录里变成 `302`）。
  这是「假头衔」真正能成立的通道：写一次持久显示，不需要自动续报。
- 普通成员 / 管理员得不到群主专属头衔时，回 `accepted=false`、`reason` 与 `hint`，
  不再把预期拒绝算成实现端故障，也不会连着三次把出站熔断打开。
- `role=off` 改为「清专属头衔 + 回到本角色默认身份头衔」，不再发会被拒绝的 `titleId=0`。
- 去掉 `0x8FC_3` 名片兜底：普通成员带 title 字段会被回 `oidb=1007`，还可能在失败时
  动到群名片。
- `title_list` 增加 `role`、`can_set_special`、`special_title`、`default_title_id`、
  `worn_title_id`；每枚头衔带 `kind`（role/custom/level/other）。
- `title_wear` 的读回写调用改用 8 秒时限，不再让 HTTP 线程卡满 15 秒。

## 兼容

- `role` 仍认 `owner` / `admin` / `level` / `off` 与中文；`title_id` 仍可用。
- `strategy` 改为 `auto`（默认）/ `identity` / `special`；`card` 已移除。
- 只有登录号自己的头衔可写；给别人写头衔继续走 `special_title` / `group_title`。
