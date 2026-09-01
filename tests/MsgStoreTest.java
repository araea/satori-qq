import com.satori.qq.core.MsgStore;

public final class MsgStoreTest {
    public static void main(String[] args) {
        MsgStore store = new MsgStore();
        String generated = store.putResource("image", "", "/tmp/a.jpg",
                "https://example.invalid/a.jpg", "a.jpg", 12);
        check(generated.startsWith("satori-res:image:"), "generated resource id");

        MsgStore.Resource image = store.getResource(generated);
        check(image != null && "image".equals(image.type), "resource lookup/type");
        check(image.size == 12 && "/tmp/a.jpg".equals(image.path), "resource metadata");

        store.attachResourceContext(generated, 2, "12345", 99, 7, 8);
        check(image.chatType == 2 && image.msgId == 99 && image.elementId == 7,
                "download context");
        check(image.fileModelId == 8 && "12345".equals(image.peerUid), "download model/peer");

        String stable = store.putResource("record", "voice.amr", "", "", "voice.amr", 3);
        check("voice.amr".equals(stable), "preferred id remains stable");
        store.putResource("record", "voice.amr", "/tmp/voice.amr", "", "", 4);
        MsgStore.Resource voice = store.getResource("voice.amr");
        check(voice != null && voice.size == 4 && "/tmp/voice.amr".equals(voice.path),
                "existing resource refresh");

        String unsafe = store.putResource("video", "Eh\u0001not-json", "", "", "a.mp4", 9);
        check(unsafe.startsWith("satori-res:video:"), "binary uuid becomes generated id");
        check(store.getResource(unsafe) != null, "generated video id lookup");
        check(!MsgStore.jsonSafeResourceId("Eh\u0001x"), "control char rejected");
        check(MsgStore.jsonSafeResourceId("satori-res:file:2"), "ascii id accepted");

        MsgStore.Rec rec = new MsgStore.Rec();
        rec.msgId = 7753807298269865192L;
        rec.senderUin = 5;
        int storeId = store.put(rec);
        check(store.resolve("7753807298269865192") != null
                && store.resolve("7753807298269865192").id == storeId, "resolve public qq msgId");
        check(store.resolve(String.valueOf(storeId)) != null
                && store.resolve(String.valueOf(storeId)).msgId == rec.msgId, "resolve legacy store id");
        check(store.resolve("0") == null, "resolve zero");
        store.learnRole(928613831L, 5, "admin");
        check("admin".equals(store.roleOf(928613831L, 5)), "learnRole");
        check(store.roleOf(1, 5).isEmpty(), "role miss");

        MsgStore.Rec a = new MsgStore.Rec();
        a.chatType = 2; a.peerUin = 928613831L; a.peerUid = "928613831"; a.msgId = 11; a.msgSeq = 1;
        MsgStore.Rec b = new MsgStore.Rec();
        b.chatType = 2; b.peerUin = 928613831L; b.peerUid = "928613831"; b.msgId = 12; b.msgSeq = 2;
        MsgStore.Rec other = new MsgStore.Rec();
        other.chatType = 2; other.peerUin = 1; other.peerUid = "1"; other.msgId = 13; other.msgSeq = 3;
        store.put(a); store.put(b); store.put(other);
        java.util.List<MsgStore.Rec> listed = store.listPeer(2, 928613831L, "928613831", 10);
        check(listed.size() == 2 && listed.get(0).msgId == 11 && listed.get(1).msgId == 12, "listPeer");

        // 引用元素兜底靠 seq 反查 msgId：同频道命中，跨频道和零 seq 必须落空。
        check(store.findByPeerSeq(2, 928613831L, null, 2).msgId == 12, "findByPeerSeq by uin");
        check(store.findByPeerSeq(2, 0, "928613831", 1).msgId == 11, "findByPeerSeq by uid");
        check(store.findByPeerSeq(2, 928613831L, null, 3) == null, "seq from another peer");
        check(store.findByPeerSeq(2, 928613831L, null, 0) == null, "zero seq");

        System.out.println("MsgStoreTest: ok");
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
