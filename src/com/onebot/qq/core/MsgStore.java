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

    /** Opaque OneBot resource id -> the best local/remote representation learned from QQ. */
    public static final class Resource {
        public String id;
        public String type;
        public String path;
        public String url;
        public String name;
        public long size;
        public long learnedAt;
        public int chatType;
        public String peerUid;
        public long msgId;
        public long elementId;
        public long fileModelId;
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

    private final AtomicInteger resourceSeq = new AtomicInteger(1);
    private final Map<String, Resource> resources = new ConcurrentHashMap<>();
    private final java.util.ArrayDeque<String> resourceOrder = new java.util.ArrayDeque<>();
    private static final int RESOURCE_CAP = 4000;

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

    /** Register a resource and return the exact opaque id exposed in the OneBot segment. */
    public synchronized String putResource(String type, String preferredId, String path,
                                           String url, String name, long size) {
        String id = preferredId == null ? "" : preferredId.trim();
        if (id.isEmpty()) id = "obres:" + type + ":" + resourceSeq.getAndIncrement();
        Resource r = resources.get(id);
        if (r == null) {
            r = new Resource();
            r.id = id;
            resources.put(id, r);
            resourceOrder.addLast(id);
        }
        r.type = type == null ? "file" : type;
        if (path != null && !path.isEmpty()) r.path = path;
        if (url != null && !url.isEmpty()) r.url = url;
        if (name != null && !name.isEmpty()) r.name = name;
        if (size > 0) r.size = size;
        r.learnedAt = System.currentTimeMillis();
        while (resourceOrder.size() > RESOURCE_CAP) {
            String old = resourceOrder.pollFirst();
            if (old != null) resources.remove(old);
        }
        return id;
    }

    public Resource getResource(String id) {
        return id == null ? null : resources.get(id.trim());
    }

    public synchronized void attachResourceContext(String id, int chatType, String peerUid,
                                                   long msgId, long elementId, long fileModelId) {
        Resource r = getResource(id);
        if (r == null) return;
        r.chatType = chatType;
        r.peerUid = peerUid == null ? "" : peerUid;
        r.msgId = msgId;
        r.elementId = elementId;
        r.fileModelId = fileModelId;
    }
}
