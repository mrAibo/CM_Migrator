package com.ibm.ecm.migration;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded JVM-shutdown hook owned by one CLI invocation. */
final class CliShutdownLifecycle {
    private final long graceSeconds;
    private final CountDownLatch runFinished = new CountDownLatch(1);
    private final AtomicBoolean terminationConfirmed = new AtomicBoolean(false);
    private final AtomicBoolean registered = new AtomicBoolean(false);
    private final Thread hook;

    CliShutdownLifecycle(long graceSeconds) {
        this.graceSeconds = Math.max(0L, graceSeconds);
        this.hook = new Thread(this::requestShutdownAndAwait,
                "verifier-cli-shutdown-hook");
    }

    void register() {
        Runtime.getRuntime().addShutdownHook(hook);
        registered.set(true);
    }

    boolean requestShutdownAndAwait() {
        ShutdownCoordinator.requestShutdown();
        try {
            return runFinished.await(graceSeconds, TimeUnit.SECONDS)
                    && terminationConfirmed.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    void finish(boolean confirmed) {
        terminationConfirmed.set(confirmed);
        runFinished.countDown();
        if (registered.compareAndSet(true, false)) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // JVM shutdown is already running; the bounded hook owns completion.
            }
        }
    }

    boolean isRegistered() {
        return registered.get();
    }
}
