package com.onebot.qq.packet;

import java.util.ArrayList;
import java.util.List;

/** QQ group-file protobuf codecs for OIDB 0x6D8 (view) and 0x6D6_2 (download URL). */
public final class GroupFiles {
    public static final int VIEW_CMD = 0x6D8;
    public static final int LIST_SUB = 1;
    public static final int COUNT_SUB = 2;
    public static final int SPACE_SUB = 3;
    public static final int DOWNLOAD_CMD = 0x6D6;
    public static final int DOWNLOAD_SUB = 2;
    public static final int APP_ID = 7;
    public static final int DEFAULT_BUS_ID = 102;

    private GroupFiles() {}

    public static final class CountResult {
        public int code = -1;
        public String message = "missing group-file count response";
        public long fileCount;
        public long limitCount;
        public boolean full;
    }

    public static final class SpaceResult {
        public int code = -1;
        public String message = "missing group-file space response";
        public long totalSpace;
        public long usedSpace;
        public boolean allUpload;
    }

    public static final class Entry {
        public boolean folder;
        public String id = "";
        public String parentId = "";
        public String name = "";
        public int busId;
        public long size;
        public long uploadTime;
        public long deadTime;
        public long modifyTime;
        public long downloadTimes;
        public long uploaderUin;
        public String uploaderName = "";
        public long creatorUin;
        public String creatorName = "";
        public long totalFileCount;
    }

    public static final class ListResult {
        public int code = -1;
        public String message = "missing group-file list response";
        public boolean end;
        public int nextIndex;
        public long allFileCount;
        public final List<Entry> entries = new ArrayList<>();
    }

    public static final class UrlResult {
        public int code = -1;
        public String message = "missing group-file URL response";
        public String url = "";
    }

    /** D6D8ReqBody.groupFileCntReq. Bus id 6 is QQ's count-service selector. */
    public static byte[] countRequest(long groupId) {
        byte[] req = Pb.w().varint(1, groupId).varint(2, APP_ID).varint(3, 6)
                .toByteArray();
        return Pb.w().message(3, req).toByteArray();
    }

    /** D6D8ReqBody.groupSpaceReq. */
    public static byte[] spaceRequest(long groupId) {
        byte[] req = Pb.w().varint(1, groupId).varint(2, APP_ID).toByteArray();
        return Pb.w().message(4, req).toByteArray();
    }

    /** D6D8ReqBody.fileListInfoReq, sorted newest-first like current QQNT. */
    public static byte[] listRequest(long groupId, String folderId, int startIndex, int count) {
        if (folderId == null || folderId.isEmpty()) folderId = "/";
        count = Math.max(1, Math.min(100, count));
        startIndex = Math.max(0, startIndex);
        byte[] req = Pb.w()
                .varint(1, groupId)
                .varint(2, APP_ID)
                .string(3, folderId)
                .varint(5, count)
                .varint(9, 1)          // sort by timestamp
                .varint(12, 0xFFFFFF)  // request all known fields
                .varint(13, startIndex)
                .varint(17, 2)         // descending/newest first
                .varint(18, 0)         // omit online-doc virtual folder
                .toByteArray();
        return Pb.w().message(2, req).toByteArray();
    }

    /** D6D6ReqBody.downloadFileReq. */
    public static byte[] urlRequest(long groupId, String fileId, int busId) {
        if (busId <= 0) busId = DEFAULT_BUS_ID;
        byte[] req = Pb.w().varint(1, groupId).varint(2, APP_ID)
                .varint(3, busId).string(4, fileId == null ? "" : fileId)
                .toByteArray();
        return Pb.w().message(3, req).toByteArray();
    }

    public static CountResult parseCount(byte[] body) {
        CountResult out = new CountResult();
        try {
            Pb.Reader result = msg(body, 3);
            if (result == null) return out;
            out.code = (int) result.num(1);
            out.message = wording(result);
            out.fileCount = result.num(4);
            out.limitCount = result.num(6);
            out.full = result.num(7) != 0;
        } catch (Throwable t) {
            out.message = "invalid group-file count response: " + t;
        }
        return out;
    }

    public static SpaceResult parseSpace(byte[] body) {
        SpaceResult out = new SpaceResult();
        try {
            Pb.Reader result = msg(body, 4);
            if (result == null) return out;
            out.code = (int) result.num(1);
            out.message = wording(result);
            out.totalSpace = result.num(4);
            out.usedSpace = result.num(5);
            out.allUpload = result.num(6) != 0;
        } catch (Throwable t) {
            out.message = "invalid group-file space response: " + t;
        }
        return out;
    }

    public static ListResult parseList(byte[] body) {
        ListResult out = new ListResult();
        try {
            Pb.Reader result = msg(body, 2);
            if (result == null) return out;
            out.code = (int) result.num(1);
            out.message = wording(result);
            out.end = result.num(4) != 0;
            out.allFileCount = result.num(7);
            out.nextIndex = (int) result.num(13);
            List<Object> items = result.all(5);
            if (items == null) return out;
            for (Object raw : items) {
                if (!(raw instanceof byte[])) continue;
                Pb.Reader item = new Pb.Reader((byte[]) raw);
                int type = (int) item.num(1);
                Pb.Reader info = item.msg(type == 2 ? 2 : 3);
                if (info == null || (type != 1 && type != 2)) continue;
                out.entries.add(type == 2 ? parseFolder(info) : parseFile(info));
            }
        } catch (Throwable t) {
            out.message = "invalid group-file list response: " + t;
        }
        return out;
    }

    public static UrlResult parseUrl(byte[] body) {
        UrlResult out = new UrlResult();
        try {
            Pb.Reader result = msg(body, 3);
            if (result == null) return out;
            out.code = (int) result.num(1);
            out.message = wording(result);
            String host = str(result, 13);
            if (host.isEmpty()) host = str(result, 5);
            byte[] token = result.bytes(6);
            if (out.code == 0 && !host.isEmpty() && token != null && token.length > 0) {
                if (host.startsWith("http://")) host = host.substring(7);
                else if (host.startsWith("https://")) host = host.substring(8);
                out.url = "https://" + host + "/ftn_handler/" + hex(token) + "/?fname=";
            }
        } catch (Throwable t) {
            out.message = "invalid group-file URL response: " + t;
        }
        return out;
    }

    private static Entry parseFile(Pb.Reader info) {
        Entry e = new Entry();
        e.folder = false;
        e.id = str(info, 1);
        e.name = str(info, 2);
        e.size = info.num(3);
        e.busId = (int) info.num(4);
        e.uploadTime = info.num(6);
        e.deadTime = info.num(7);
        e.modifyTime = info.num(8);
        e.downloadTimes = info.num(9);
        e.uploaderName = str(info, 14);
        e.uploaderUin = info.num(15);
        e.parentId = str(info, 16);
        return e;
    }

    private static Entry parseFolder(Pb.Reader info) {
        Entry e = new Entry();
        e.folder = true;
        e.id = str(info, 1);
        e.parentId = str(info, 2);
        e.name = str(info, 3);
        e.uploadTime = info.num(4); // OneBot calls this create_time.
        e.modifyTime = info.num(5);
        e.creatorUin = info.num(6);
        e.creatorName = str(info, 7);
        e.totalFileCount = info.num(8);
        return e;
    }

    private static Pb.Reader msg(byte[] body, int field) {
        return body == null || body.length == 0 ? null : new Pb.Reader(body).msg(field);
    }

    private static String wording(Pb.Reader r) {
        String client = str(r, 3);
        return client.isEmpty() ? str(r, 2) : client;
    }

    private static String str(Pb.Reader r, int field) {
        String value = r.str(field);
        return value == null ? "" : value;
    }

    private static String hex(byte[] data) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int value = data[i] & 0xFF;
            out[i * 2] = alphabet[value >>> 4];
            out[i * 2 + 1] = alphabet[value & 0x0F];
        }
        return new String(out);
    }
}
