import com.onebot.qq.core.OutboundGuard;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class OutboundGuardTest {
    public static void main(String[] args) throws Exception {
        check(OutboundGuard.isMutation("send_msg"), "send_msg is guarded");
        check(OutboundGuard.isMutation("set_group_ban"), "moderation is guarded");
        check(!OutboundGuard.isMutation("get_status"), "status stays responsive");
        check(!OutboundGuard.isMutation("get_group_list"), "lookups stay responsive");

        OutboundGuard guard = new OutboundGuard(40, 1000, 2);
        long started = System.currentTimeMillis();
        try (OutboundGuard.Lease ignored = guard.acquire("send_msg")) {}
        try (OutboundGuard.Lease ignored = guard.acquire("send_msg")) {}
        long elapsed = System.currentTimeMillis() - started;
        check(elapsed >= 35, "minimum interval is enforced");
        check(guard.stats().getLong("admitted") == 2, "admission count");
        check("send_msg".equals(guard.stats().getString("last_action")), "last action");

        OutboundGuard concurrent = new OutboundGuard(0, 1000, 2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        Runnable work = () -> {
            try {
                start.await();
                try (OutboundGuard.Lease ignored = concurrent.acquire("send_msg")) {
                    int now = active.incrementAndGet();
                    maxActive.updateAndGet(old -> Math.max(old, now));
                    Thread.sleep(30);
                    active.decrementAndGet();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        Thread first = new Thread(work);
        Thread second = new Thread(work);
        first.start(); second.start(); start.countDown();
        first.join(); second.join();
        check(maxActive.get() == 1, "mutations are serialized");

        System.out.println("OutboundGuardTest OK");
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
