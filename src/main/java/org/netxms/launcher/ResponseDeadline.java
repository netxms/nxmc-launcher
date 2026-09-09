package org.netxms.launcher;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;

final class ResponseDeadline implements AutoCloseable {
    private static final long POLL_NANOS = Duration.ofMillis(100).toNanos();

    private final Thread watchdog;
    private volatile long lastProgress;
    private volatile boolean expired;

    ResponseDeadline(Closeable body, Duration limit) {
        this(body, limit, CancelToken.NONE);
    }

    ResponseDeadline(Closeable body, Duration limit, CancelToken cancel) {
        lastProgress = System.nanoTime();
        watchdog = new Thread(() -> watch(body, limit.toNanos(), cancel), "nxmc-response-deadline");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    void progress() {
        lastProgress = System.nanoTime();
    }

    boolean expired() {
        return expired;
    }

    @Override
    public void close() {
        watchdog.interrupt();
    }

    private void watch(Closeable body, long limitNanos, CancelToken cancel) {
        boolean stalled = false;
        try {
            while (!cancel.cancelled()) {
                long remaining = limitNanos - (System.nanoTime() - lastProgress);
                if (remaining <= 0) {
                    stalled = true;
                    break;
                }
                Thread.sleep((Math.min(remaining, POLL_NANOS) / 1_000_000L) + 1L);
            }
        } catch (InterruptedException e) {
            return;
        }

        expired = stalled;
        try {
            body.close();
        } catch (IOException ignored) {
        }
    }
}
