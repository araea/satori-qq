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

        System.out.println("MsgStoreTest: ok");
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
