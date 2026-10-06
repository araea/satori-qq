package com.satori.qq.core;

import com.satori.qq.qq.Ref;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 把 QQ 内核回调式的写接口同步化：代理一个回调接口，等它的 {@code onResult(code, message)}
 * 一类的答复，码不是 0 或超时就按 {@link ApiError#failed} 抛。
 */
final class KernelAck {
    private final CountDownLatch answered = new CountDownLatch(1);
    private volatile int code = -1;
    private volatile String message = "";

    /** 回调接口的代理；只认名为 {@code method} 的那一个回调，其余调用一概放过。 */
    Object proxy(Ref ref, String callbackClass, String method) {
        return Proxy.newProxyInstance(ref.cl, new Class<?>[]{ref.cls(callbackClass)}, (proxy, m, args) -> {
            if (method.equals(m.getName()) && args != null && args.length >= 1) {
                code = Ref.asInt(args[0]);
                if (args.length >= 2) message = Ref.asStr(args[1]);
                answered.countDown();
            }
            return null;
        });
    }

    void await(String what) throws InterruptedException {
        if (!answered.await(15, TimeUnit.SECONDS)) throw ApiError.failed(what + " timeout");
        if (code != 0) throw ApiError.failed(what + " failed: code=" + code + " " + message);
    }
}
