import com.satori.qq.core.OutboundGuard;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class OutboundGuardTest {
    public static void main(String[] args) throws Exception {
        check(OutboundGuard.isMutation("message.create"), "message.create is guarded");
        check(!OutboundGuard.isMutation("upload.create"), "local upload stays available offline");
        check(OutboundGuard.isMutation("guild.member.mute"), "moderation is guarded");
        check(OutboundGuard.isMutation("internal.invite"), "group invite is guarded");
        check(!OutboundGuard.isMutation("message.update"), "unsupported message.update is not guarded");
        check(!OutboundGuard.isMutation("reaction.clear"), "unsupported reaction.clear is not guarded");
        check(!OutboundGuard.isMutation("channel.create"), "unsupported channel.create is not guarded");
        check(!OutboundGuard.isMutation("channel.delete"), "unsupported channel.delete is not guarded");
        check(!OutboundGuard.isMutation("login.get"), "login stays responsive");
        check(!OutboundGuard.isMutation("guild.list"), "lookups stay responsive");

        OutboundGuard guard = new OutboundGuard(40, 1000, 2, 20, 3, 1000);
        long started = System.currentTimeMillis();
        try (OutboundGuard.Lease ignored = guard.acquire("message.create")) {}
        try (OutboundGuard.Lease ignored = guard.acquire("message.create")) {}
        long elapsed = System.currentTimeMillis() - started;
        check(elapsed >= 35, "minimum interval is enforced");
        check(guard.stats().getLong("admitted") == 2, "admission count");
        check("message.create".equals(guard.stats().getString("last_action")), "last action");

        OutboundGuard concurrent = new OutboundGuard(0, 1000, 2, 20, 3, 1000);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        Runnable work = () -> {
            try {
                start.await();
                try (OutboundGuard.Lease ignored = concurrent.acquire("message.create")) {
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

        OutboundGuard budget = new OutboundGuard(0, 1000, 2, 2, 3, 1000);
        try (OutboundGuard.Lease ignored = budget.acquire("message.create")) {}
        try (OutboundGuard.Lease ignored = budget.acquire("message.create")) {}
        expectBusy(() -> budget.acquire("message.create"), "rate budget rejects excess");
        check(budget.stats().getLong("rate_rejected") == 1, "rate rejection count");

        OutboundGuard circuit = new OutboundGuard(0, 1000, 2, 20, 2, 20);
        OutboundGuard.Lease failed = circuit.acquire("message.create");
        failed.complete(false);
        failed = circuit.acquire("message.create");
        failed.complete(false);
        expectBusy(() -> circuit.acquire("message.create"), "open circuit rejects writes");
        check("open".equals(circuit.stats().getString("circuit_state")), "circuit opens");
        Thread.sleep(30);
        try (OutboundGuard.Lease ignored = circuit.acquire("message.create")) {}
        check("closed".equals(circuit.stats().getString("circuit_state")), "half-open success closes");

        System.out.println("OutboundGuardTest OK");
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }

    private static void expectBusy(ThrowingCall call, String label) throws Exception {
        try {
            OutboundGuard.Lease lease = call.run();
            lease.close();
            throw new AssertionError(label);
        } catch (OutboundGuard.BusyException expected) {
            // expected
        }
    }

    private interface ThrowingCall {
        OutboundGuard.Lease run() throws Exception;
    }
}
