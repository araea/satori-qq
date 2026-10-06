package com.satori.qq.core;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模块自己起的线程。
 *
 * <p>线程名是外部可见的：`/proc/<pid>/task/comm` 里能读到。带上模块名字的线程等于把模块写在
 * 自己的进程里（Duck Detector 一类检测器就在扫线程名），所以一律按 JVM 线程池的默认风格
 * 命名，QQ 自己也有若干 `pool-N-thread-M`。
 */
final class Workers {
    static final String BOOTSTRAP = "pool-4-bootstrap";
    static final String STATUS_MONITOR = "pool-5-thread-1";
    static final String RESTART = "pool-5-thread-2";
    static final String IDENTIFY_TIMEOUT = "pool-5-thread-3";
    static final String CHANNEL_UNMUTE = "pool-9-thread-1";

    private static final AtomicInteger SCHEDULER_THREADS = new AtomicInteger();
    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(4, task -> {
        Thread thread = new Thread(task, "pool-8-thread-" + (SCHEDULER_THREADS.incrementAndGet() % 100));
        thread.setDaemon(true);
        return thread;
    });

    private Workers() {}

    static Thread daemon(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        return thread;
    }

    static Thread start(String name, Runnable body) {
        Thread thread = daemon(name, body);
        thread.start();
        return thread;
    }

    /** 共用的延时任务池：回声 TTL、自发消息的补偿轮询。 */
    static ScheduledExecutorService scheduler() { return SCHEDULER; }
}
