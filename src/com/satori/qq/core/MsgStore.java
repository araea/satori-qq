package com.satori.qq.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Maps between int32 message_id and QQ NT's (chatType, peer, msgId long, msgSeq).
 *  Also caches uin<->uid learned from received messages (needed to send C2C messages). */
public final class MsgStore {
    public static final class Rec {
        public int id;            // int32 message_id
        public int chatType;      // 1=c2c 2=group
        public String peerUid;    // contact peerUid (group code string OR c2c uid)
        public long peerUin;      // group code OR peer uin
        public long msgId;        // QQ NT msgId (long)
        public long msgSeq;       // QQ NT msgSeq
        public long msgTime;      // QQ NT msgTime (unix seconds)
        public long senderUin;
        public String senderUid;
        public Object msgRecord;  // original MsgRecord (for get_msg / reply resolution)
        public String content;    // last outbound Satori content, for get after send
        /** true when {@link #content} is a Satori element string (this end sent it), not CQ text. */
        public boolean contentIsElements;
    }

    /** Opaque resource id -> the best local/remote representation learned from QQ. */
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
    // dedupe: qq msgId -> local id (so the same message isn't stored twice)
    private final Map<Long, Integer> byMsgId = new ConcurrentHashMap<>();

    // uin <-> uid caches
    private final Map<Long, String> uin2uid = new ConcurrentHashMap<>();
    private final Map<String, Long> uid2uin = new ConcurrentHashMap<>();
    private final Map<String, String> roles = new ConcurrentHashMap<>();

    private final AtomicInteger resourceSeq = new AtomicInteger(1);
    private final Map<String, Resource> resources = new ConcurrentHashMap<>();
    private final java.util.ArrayDeque<String> resourceOrder = new java.util.ArrayDeque<>();
    private static final int RESOURCE_CAP = 4000;

    public synchronized int put(Rec r) {
        Integer existing = byMsgId.get(r.msgId);
        if (existing != null && r.msgId != 0) {
            Rec old = byId.get(existing);
            if (old != null) {
                if (r.msgRecord != null) old.msgRecord = r.msgRecord;
                if (r.content != null && !r.content.isEmpty()) old.content = r.content;
                if (r.senderUin != 0) old.senderUin = r.senderUin;
                if (r.msgSeq != 0) old.msgSeq = r.msgSeq;
                if (r.msgTime != 0) old.msgTime = r.msgTime;
            }
            return existing;
        }
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

    public Rec getByMsgId(long msgId) {
        if (msgId == 0) return null;
        Integer id = byMsgId.get(msgId);
        return id == null ? null : byId.get(id);
    }

    public Rec findByPeerSeq(int chatType, long peerUin, String peerUid, long msgSeq) {
        if (msgSeq == 0) return null;
        for (Rec r : byId.values()) {
            if (r == null || r.msgSeq != msgSeq) continue;
            if (chatType != 0 && r.chatType != chatType) continue;
            if (peerUin != 0 && r.peerUin == peerUin) return r;
            if (peerUid != null && !peerUid.isEmpty() && peerUid.equals(r.peerUid)) return r;
        }
        return null;
    }

    /** In-process records for one peer, oldest first, capped at the newest {@code limit}. */
    public java.util.List<Rec> listPeer(int chatType, long peerUin, String peerUid, int limit) {
        java.util.ArrayList<Rec> out = new java.util.ArrayList<>();
        for (Rec r : byId.values()) {
            if (r == null) continue;
            if (chatType != 0 && r.chatType != chatType) continue;
            boolean match = (peerUin != 0 && r.peerUin == peerUin)
                    || (peerUid != null && !peerUid.isEmpty() && peerUid.equals(r.peerUid));
            if (!match) continue;
            out.add(r);
        }
        out.sort((a, b) -> {
            int bySeq = Long.compare(a.msgSeq, b.msgSeq);
            return bySeq != 0 ? bySeq : Long.compare(a.msgId, b.msgId);
        });
        int cap = Math.max(1, limit);
        if (out.size() > cap) return new java.util.ArrayList<>(out.subList(out.size() - cap, out.size()));
        return out;
    }

    public int idOfMsgId(long msgId) {
        Rec r = getByMsgId(msgId);
        if (r != null) return r.id;
        return 0;
    }

    /** Resolve a public Satori message.id (QQ msgId) or a legacy in-process store id. */
    public Rec resolve(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        long n;
        try { n = Long.parseLong(s); } catch (Exception e) { return null; }
        if (n == 0) return null;
        Rec byMid = getByMsgId(n);
        if (byMid != null) return byMid;
        if (n > 0 && n <= Integer.MAX_VALUE) return get((int) n);
        return null;
    }

    public void learnRole(long groupId, long uin, String role) {
        if (groupId == 0 || uin == 0 || role == null || role.isEmpty()) return;
        roles.put(groupId + ":" + uin, role);
    }

    public String roleOf(long groupId, long uin) {
        if (groupId == 0 || uin == 0) return "";
        String role = roles.get(groupId + ":" + uin);
        return role == null ? "" : role;
    }

    public void learnUid(long uin, String uid) {
        if (uin > 0 && uid != null && !uid.isEmpty()) {
            uin2uid.put(uin, uid);
            uid2uin.put(uid, uin);
        }
    }
    public String uidOf(long uin) { return uin2uid.get(uin); }
    public long uinOf(String uid) { Long v = uid2uin.get(uid); return v == null ? 0 : v; }

    /**
     * Resource ids travel through JSON. Binary NT fileUuid values (video protobuf blobs)
     * must not become the public id or later get_file lookups miss the registry.
     */
    public static boolean jsonSafeResourceId(String id) {
        if (id == null || id.isEmpty() || id.length() > 180) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c < 0x20 || c > 0x7e) return false;
        }
        return true;
    }

    /** Register a resource and return the exact opaque id exposed in the message segment. */
    public synchronized String putResource(String type, String preferredId, String path,
                                           String url, String name, long size) {
        String id = jsonSafeResourceId(preferredId) ? preferredId.trim() : "";
        if (id.isEmpty()) id = "satori-res:" + type + ":" + resourceSeq.getAndIncrement();
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
