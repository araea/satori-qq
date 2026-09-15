import com.satori.qq.satori.Protocol;
import org.json.JSONObject;

public final class ProtocolTest {
    public static void main(String[] args) throws Exception {
        check(!Protocol.shouldReplay(false, false), "missing sn starts fresh");
        check(!Protocol.shouldReplay(true, true), "null sn starts fresh");
        check(Protocol.shouldReplay(true, false), "explicit zero/nonzero sn resumes");

        // The compact login a non-login event carries must name the account twice: once as
        // `user.id` (canonical) and once as the deprecated flat `self_id` clients still read.
        JSONObject user = new JSONObject().put("id", "10001").put("name", "bot");
        JSONObject login = Protocol.eventLogin(0, "red", user);
        eq(login.optString("platform"), "red", "login platform");
        eq(login.optString("self_id"), "10001", "login self_id");
        eq(login.optJSONObject("user").optString("id"), "10001", "login user id");
        check(!Protocol.eventLogin(0, "red", null).has("self_id"), "no user, no self_id");
        check(!Protocol.eventLogin(0, "red", new JSONObject()).has("self_id"),
                "empty user, no self_id");

        System.out.println("ProtocolTest OK");
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }

    private static void eq(String actual, String wanted, String label) {
        if (!wanted.equals(actual)) {
            throw new AssertionError(label + ": expected " + wanted + ", got " + actual);
        }
    }
}
