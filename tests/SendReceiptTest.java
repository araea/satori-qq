package com.satori.qq.qq;

public final class SendReceiptTest {
    public static void main(String[] args) throws Exception {
        SendReceipt pending = new SendReceipt();
        QQClient.SendResult result = new QQClient.SendResult();
        pending.record(1);
        check(!pending.await(1), "queued is not delivered");
        pending.copyTo(result);
        check(result.code == -2 && result.msg.contains("do not retry"), "timeout is unknown");
        pending.record(2);
        check(pending.await(0), "message update confirms missing callback");
        pending.copyTo(result);
        check(result.code == 0, "confirmed update succeeds");
        pending.callback(-1, "late error");
        pending.copyTo(result);
        check(result.code == 0, "late callback cannot replace terminal result");
        SendReceipt failed = new SendReceipt();
        failed.record(0);
        check(!failed.await(0), "wait for specific failure callback");
        failed.callback(-1, "rich media transfer failed");
        failed.copyTo(result);
        check(result.code == -1 && result.msg.contains("rich media"), "upload failure preserved");
        SendReceipt another = new SendReceipt();
        another.record(3);
        check(another.await(0), "status 3 also succeeds");
        System.out.println("SendReceiptTest OK");
    }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
