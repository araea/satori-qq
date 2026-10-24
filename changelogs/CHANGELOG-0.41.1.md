# 0.41.1 — 公告与精华的读改走网页 CGI（修段错误）

0.41.0 的两条读真机即崩：`getGroupBulletinList` / `fetchGroupEssenceList` 在本机 QQ 9.3.75 上调用直接触发内核段错误（CrashDefend 吞掉 signal 11，进程重启、连接断），拿不到任何回包。这版不再碰这两条内核调用：

- **读群公告**改走客户端公告页自己的数据接口 `https://web.qun.qq.com/cgi-bin/announce/get_t_list`（dex 里有原样字符串），p_skey + bkn，返回 `bulletins[]` 原样透传。
- **读群精华**改走精华 H5 页的数据接口 `https://qun.qq.com/cgi-bin/essence/get_essence_list`，返回 `essences[]` 原样透传。
- **pskey 的来源修了**：9.3.75 上会话的 TipOff 服务取不到了（`getTipOffService` 回 null，0.37 的发公告与 0.40 的发说说因此一起失效），现在退到登录服务的 `guestA2ToPskey(uin, domains, cb)` 签发，同域同票；TipOff 可用时仍优先。
- 0.41.0 引入的监听器等待基建随内核路线撤下。
