package com.onebot.qq.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Maps between OneBot's int32 message_id and QQ NT's (chatType, peer, msgId long, msgSeq).
 *  Also caches uin<->uid learned from received messages (needed to send C2C messages). */
public final class MsgStore {
    public static final class Rec {
        public int id;            // onebot int32 message_id
        public int chatType;      // 1=c2c 2=group
        public String peerUid;    // contact peerUid (group code string OR c2c uid)
        public long peerUin;      // group code OR peer uin
        public long msgId;        // QQ NT msgId (long)
        public long msgSeq;       // QQ NT msgSeq
        public long senderUin;
        public String senderUid;
        public Object msgRecord;  // original MsgRecord (for get_msg / reply resolution)
    }

    private final AtomicInteger seq = new AtomicInteger(1);
    private final int CAP = 4000;
    // ring of ids -> Rec
    private final Map<Integer, Rec> byId = new ConcurrentHashMap<>();
    private final java.util.ArrayDeque<Integer> order = new java.util.ArrayDeque<>();
    // dedupe: qq msgId -> onebot id (so the same message isn't stored twice)
    private final Map<Long, Integer> byMsgId = new ConcurrentHashMap<>();

    // uin <-> uid caches
    private final Map<Long, String> uin2uid = new ConcurrentHashMap<>();
    private final Map<String, Long> uid2uin = new ConcurrentHashMap<>();

    public synchronized int put(Rec r) {
        Integer existing = byMsgId.get(r.msgId);
        if (existing != null && r.msgId != 0) { return existing; }
        int id = seq.getAndIncrement();
        if (id == Integer.MAX_VALUE) seq.set(1);
        r.id = id;
        byId.put(id, r);
        if (r.msgId != 0) byMsgId.put(r.msgId, id);
        order.addLast(id);
        while (order.size() > CAP) {
            Integer old = order.pollFirst();
            if (old != null) { Rec rr = byId.remove(old); if (rr != null) byMsgId.remove(rr.msgId); }
        }
        return id;
    }

    public Rec get(int id) { return byId.get(id); }

    public void learnUid(long uin, String uid) {
        if (uin > 0 && uid != null && !uid.isEmpty()) {
            uin2uid.put(uin, uid);
            uid2uin.put(uid, uin);
        }
    }
    public String uidOf(long uin) { return uin2uid.get(uin); }
    public long uinOf(String uid) { Long v = uid2uin.get(uid); return v == null ? 0 : v; }
}
