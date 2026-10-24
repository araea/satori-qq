package com.satori.qq.core;

/**
 * 幂等信封的账本：同一把钥匙只执行一次、回执原样重用、暂时性失败不记。
 *
 * <p>这条规则的意义在真机上：一次上传刚交给 QQ、回执丢了，客户端重发。没有这把钥匙，
 * 第二次就是真的又传一遍；有它，第二次只是把第一次的结果再读一次。
 */
public final class CallLedgerTest {
    public static void main(String[] args) {
        replayIsExact();
        failuresAreReplayed();
        transientFailuresAreNot();
        capacityEvictsOldest();
        System.out.println("CallLedgerTest OK");
    }

    private static void replayIsExact() {
        CallLedger ledger = new CallLedger();
        check(ledger.replay("k1") == null, "空账本里不该有回执");
        ledger.done("k1", "first");
        check("first".equals(ledger.replay("k1")), "同一把钥匙拿回同一份回执");
        // 没有钥匙的调用不记账，也不该命中别人的回执。
        ledger.done("", "unnamed");
        check(ledger.replay("") == null, "没有钥匙就没有回执");
        check(ledger.replay("k2") == null, "别的钥匙互不影响");
    }

    private static void failuresAreReplayed() {
        CallLedger ledger = new CallLedger();
        ApiError failure = ApiError.badRequest("缺参数");
        ledger.failed("bad", failure);
        Object replay = ledger.replay("bad");
        check(replay instanceof ApiError, "参数错误重发时原样还回去");
        check(((ApiError) replay).code == 1400, "错误码不变");
    }

    private static void transientFailuresAreNot() {
        CallLedger ledger = new CallLedger();
        // 限流与内核离线是暂时的：重发就该真的再试一次。
        ledger.failed("rate", ApiError.busy(true, "outbound_rate_limited", "too fast", 3));
        ledger.failed("offline", ApiError.kernelOffline());
        check(ledger.replay("rate") == null, "限流不该被记住");
        check(ledger.replay("offline") == null, "内核离线不该被记住");
    }

    private static void capacityEvictsOldest() {
        CallLedger ledger = new CallLedger();
        for (int i = 0; i < CallLedger.CAPACITY + 8; i++) ledger.done("k" + i, i);
        check(ledger.replay("k0") == null, "最老的被挤出去");
        check(Integer.valueOf(CallLedger.CAPACITY + 7).equals(ledger.replay("k" + (CallLedger.CAPACITY + 7))),
                "最新的还在");
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
