# 0.35.0 — 发 @全体成员 之前先问一句

@全体成员 本来就能收发（`<at type="all"/>` ↔ QQ 的 `atType=1`）；这一版补上「能不能发、还剩几次」。

### 新增

- **`internal/at_all_quota`**（参数 `channel_id` 或 `guild_id`）：返回
  `{allowed, remaining, group_remaining, reason}`——本号此刻能否在该群 @全体成员、本号今天还剩几次、全群还剩几次、
  不能发时 QQ 给的说法。走内核 `IKernelGroupService.getGroupRemainAtTimes`，就是 QQ 自己在输入框里打出 @
  时问的那一条。satori-wx 同步提供同名同形的方法（微信不限次，次数写 `null`），客户端不必分平台写两套。
- `docs/SATORI_SUPPORT.md` 补了 @全体成员 的收发口径与这个方法。

### 其它

- `internal/compat` 多核对一个回调（`IGetGroupRemainAtAllTimesOperateCallback`），现为 84 类型 / 37 回调 / 27 结构体。

改动在 Java 层，代码内嵌在 Zygisk 的 `.so` 里：装机后**重启手机**才生效。
