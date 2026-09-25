package com.satori.qq.qq;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A kernel submission is not a delivery receipt. Safe to update from QQ's listener threads. */
final class SendReceipt {
    private final CountDownLatch done = new CountDownLatch(1);
    private int code = -2;
    private String message = "send outcome unknown; confirmation timeout; do not retry";

    synchronized void callback(int value, String wording) {
        if (done.getCount() == 0) return;
        code = value;
        message = wording;
        done.countDown();
    }

    void record(int status) {
        // QQ 9.3.65 AIOVideoSendingManager: 1 = sending, 2/3 = success.
        // Status 0 may arrive before the more useful upload error callback; wait for that.
        if (status == 2 || status == 3) callback(0, "confirmed by message update");
    }

    boolean await(long millis) throws InterruptedException {
        return done.await(Math.max(0, millis), TimeUnit.MILLISECONDS);
    }

    synchronized void copyTo(QQClient.SendResult result) {
        result.code = code;
        result.msg = message;
    }
}
