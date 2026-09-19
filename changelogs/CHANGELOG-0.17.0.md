## 0.17.0 · 知弦

### 收掉 QQ 内部接口的暴露面

这一版把模块对外开放的 QQ 内核接口整批撤掉，只留下 satori 协议必需的那部分。

- `POST /v1/internal/{name}` 只剩这几个：`poke`、`like`、`invite`、`card`、`special_title`、
  `title_display`、`honor_display`、`sign`、`essence`、`dice`、`rps`、`get_forward`，以及模块
  自己的 `capabilities`、`compat`、`status`、`version`、`restart`、`clean_cache`。
- 撤掉的是那一大批「按号去问 QQ 要资料」的内核接口：群详情、群统计、成员等级、成员搜索、
  群荣誉、群公告、精华列表、群容量与发言限制、随机抽人、资料卡、会员信息、亲密关系、
  好友分组与备注、收藏表情、未读汇总、群文件读写、消息上下文与搜索、会话状态（已读、置顶、
  隐藏、草稿）、QQ 空间。叫这些名字一律 404。
- `get_forward` 保留：合并转发在入站只有 `<message forward id="…"/>` 一个元素，没有正文，
  也没有第二条能取回它的路。
- `POST /v1/internal/offline` 一并撤掉（它本来就是「凭据已经没救」时才用的手动口）。
- 连带删掉 `QzoneSvc` 与 `QQClient` 里那套 skey / p_skey 票券获取代码。

Satori 标准方法（`message.*`、`channel.*`、`guild.*`、`guild.member.*`、`reaction.*`、
`user.*`、`friend.*`、`upload.create`、`login.get`）与全部事件、消息元素不受影响。

升级提示：沿用包名与签名，可直接覆盖升级。依赖扩展方法的客户端升级后那些调用会收到 404，
请改用上面的清单；`get_forward` 的写法没变。

验证：真机 QQ 9.3.65 上 `capabilities` 返回的就是上面那张表，`internal/compat` 与
`/healthz` 照常，踢线拦截、过检测与保活逻辑未改动。
