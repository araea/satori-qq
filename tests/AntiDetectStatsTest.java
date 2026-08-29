import com.onebot.qq.qq.AntiDetect;
import org.json.JSONObject;

/** Offline checks for count-only getFeKitAttach observation. */
public final class AntiDetectStatsTest {
    public static void main(String[] args) throws Exception {
        long before = AntiDetect.fekitAttachStats(true).getLong("total");
        AntiDetect.recordFekitAttach("0x810", "0x11", 32, false);
        AntiDetect.recordFekitAttach("account-data", "0x11", -1, true);
        JSONObject stats = AntiDetect.fekitAttachStats(true);
        check(stats.getBoolean("enabled"), "enabled");
        eq(before + 2, stats.getLong("total"), "total");
        check(stats.getLong("errors") >= 1, "errors");
        eq(-1, stats.getLong("last_length"), "last length");
        JSONObject commands = stats.getJSONObject("commands");
        check(commands.getLong("0x810/0x11") >= 1, "known command");
        check(commands.getLong("other/0x11") >= 1, "sensitive command redacted");
        System.out.println("AntiDetectStatsTest OK");
    }

    private static void eq(long expected, long actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": " + expected + " != " + actual);
        }
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
