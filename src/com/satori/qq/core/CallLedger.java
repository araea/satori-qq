package com.satori.qq.core;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 幂等调用信封的账本：同一个 {@code key} 只真正执行一次，重发拿回同一份回执。
 *
 * <p>为什么需要它：智能体发一个动作时，回执可能丢在半路上（超时、连接被掐、客户端自己
 * 重启）。这时客户端只知道「结果未知」。重发是最自然的补救，但对 {@code group_file_upload}
 * 这类动作，重发就是再传一份；对 {@code group_bulletin_publish}，就是再发一条公告。
 * 有了这把钥匙，客户端可以放心重发：第二次拿回的是第一次那份结果，带 {@code replayed:true}。
 *
 * <p>只记有限条、有限时间（{@link #TTL_MS}、{@link #CAPACITY}）。账本是给「刚刚那一下」
 * 补的，不是一份长期历史——真过了五分钟还在重试，那多半是别处出了问题，让它真跑一遍
 * 比拿一份陈旧回执更有用。读动作也记：读是幂等的，记下来只是省一次内核往返。
 */
final class CallLedger {
    /** 回执留多久。 */
    static final long TTL_MS = 5 * 60_000L;
    /** 最多记多少条；超了先丢最老的。 */
    static final int CAPACITY = 128;

    private record Entry(long at, Object result, ApiError failure) {}

    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

    /** 已经做过的那一次的结果；没做过返回 null。失败的也记，免得重试把一个已知不行的动作又撞一遍。 */
    synchronized Object replay(String key) {
        if (key == null || key.isEmpty()) return null;
        Entry entry = entries.get(key);
        if (entry == null) return null;
        if (System.currentTimeMillis() - entry.at > TTL_MS) {
            entries.remove(key);
            return null;
        }
        // 重新插入一次，把它挪到「最近用过」那一头。
        entries.remove(key);
        entries.put(key, entry);
        return entry.result != null ? entry.result : entry.failure;
    }

    /** 记下一次成功的结果。 */
    synchronized void done(String key, Object result) {
        if (key == null || key.isEmpty()) return;
        put(key, new Entry(System.currentTimeMillis(), result, null));
    }

    /** 记下一次「调用方该知道的失败」，重发时原样还给它。 */
    synchronized void failed(String key, ApiError failure) {
        if (key == null || key.isEmpty() || failure == null) return;
        // 只记调用方参数/状态类的错误：那类重发结果一样。限流、熔断、内核离线是暂时的，
        // 重发就该真的再试一次，不能被一份旧回执挡住。
        if (failure.code == 1429 || failure.code == 1503) return;
        put(key, new Entry(System.currentTimeMillis(), null, failure));
    }

    private void put(String key, Entry entry) {
        entries.remove(key);
        entries.put(key, entry);
        // LinkedHashMap 的迭代顺序是插入顺序，最前面的就是最老的那条。
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (entries.size() > CAPACITY && it.hasNext()) {
            it.next();
            it.remove();
        }
    }
}
