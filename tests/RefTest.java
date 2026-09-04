import com.satori.qq.qq.Ref;

/** Regression checks for optional QQ nativeinterface field probes. */
public final class RefTest {
    private static class BaseRecord {
        public String inheritedRole = "ADMIN";
    }

    private static final class NewRecord extends BaseRecord {
        public int roleType = 2;
        public String nullableRole = null;
    }

    public static void main(String[] args) {
        Ref ref = new Ref(RefTest.class.getClassLoader());
        NewRecord record = new NewRecord();
        eq(2, ref.getOrNull(record, "roleType"), "primitive field");
        eq("ADMIN", ref.getOrNull(record, "inheritedRole"), "inherited field");
        eq(null, ref.getOrNull(record, "nullableRole"), "null field");
        eq(null, ref.getOrNull(record, "senderRoleType"), "removed QQ field");
        eq(null, ref.getOrNull(null, "roleType"), "null receiver");
        System.out.println("RefTest OK");
    }

    private static void eq(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + " got " + actual);
        }
    }
}
