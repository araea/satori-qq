import com.satori.qq.core.MessageFreshness;
import com.satori.qq.core.OutboundGuard;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

public final class MessageFreshnessTest {
    private static JSONObject event(String channel, String id) throws Exception {
        return new JSONObject().put("type", "message-created")
                .put("channel", new JSONObject().put("id", channel))
                .put("message", new JSONObject().put("id", id));
    }
    private static MessageFreshness.Condition condition(String channel, String id, long deadline) throws Exception {
        return MessageFreshness.condition(new JSONObject().put("channel_id", channel)
                .put("satori_qq", new JSONObject().put("if_latest_message_id", id).put("expires_at", deadline)));
    }
    private static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }
    public static void main(String[] args) throws Exception {
        MessageFreshness freshness = new MessageFreshness();
        freshness.observe(event("123", "a"));
        MessageFreshness.Condition first = condition("123", "a", 1000);
        check(freshness.isCurrent(first, 999), "current message allowed");
        check(!freshness.isCurrent(first, 1000), "deadline inclusive");
        freshness.observe(event("private:123", "b"));
        check(freshness.isCurrent(first, 999), "private channel isolated");
        freshness.observe(event("123", "c").put("type", "message-updated"));
        check(freshness.isCurrent(first, 999), "updates do not interrupt");
        freshness.observe(event("123", "c"));
        check(!freshness.isCurrent(first, 999), "new message cancels old reply");
        check(!freshness.isCurrent(condition("unknown", "a", 1000), 999), "unknown channel fails closed");
        check(MessageFreshness.condition(new JSONObject()) == null, "ordinary sends unchanged");
        check(freshness.isCurrent(null, Long.MAX_VALUE), "ordinary send never expires");
        try {
            MessageFreshness.condition(new JSONObject().put("satori_qq", new JSONObject().put("expires_at", 1000)));
            throw new AssertionError("malformed condition accepted");
        } catch (IllegalArgumentException expected) {}

        // Two users repeat, the bot waits behind another write, then a third user changes topic.
        OutboundGuard queue = new OutboundGuard(0, 5000, 8, 20, 3, 1000);
        OutboundGuard.Lease busy = queue.acquire("message.create");
        freshness.observe(event("123", "haha-2"));
        MessageFreshness.Condition repeat = condition("123", "haha-2", System.currentTimeMillis() + 5000);
        AtomicBoolean sent = new AtomicBoolean();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch waiting = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            waiting.countDown();
            try (OutboundGuard.Lease lease = queue.acquire("message.create")) {
                if (freshness.isCurrent(repeat, System.currentTimeMillis())) sent.set(true);
            } catch (Throwable t) { error.set(t); }
        });
        worker.start();
        waiting.await();
        freshness.observe(event("123", "new-topic"));
        busy.close();
        worker.join(6000);
        check(!worker.isAlive() && error.get() == null, "queue worker completed");
        check(!sent.get(), "queued stale repeat must not reach QQ");
        check(queue.stats().getLong("failed") == 0, "skip does not trip circuit breaker");
        try { freshness.check(repeat); throw new AssertionError("late conversion check missed change"); }
        catch (MessageFreshness.Stale expected) {}
        freshness.check(null);
        // Bounded state must not accidentally approve forgotten channels.
        for (int i = 0; i < 4097; i++) freshness.observe(event("channel-" + i, "x"));
        check(!freshness.isCurrent(condition("123", "new-topic", Long.MAX_VALUE), 0), "evicted channel fails closed");
    }
}
