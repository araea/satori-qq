import com.satori.qq.satori.Protocol;

public final class ProtocolTest {
    public static void main(String[] args) {
        check(!Protocol.shouldReplay(false, false), "missing sn starts fresh");
        check(!Protocol.shouldReplay(true, true), "null sn starts fresh");
        check(Protocol.shouldReplay(true, false), "explicit zero/nonzero sn resumes");
        System.out.println("ProtocolTest OK");
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
