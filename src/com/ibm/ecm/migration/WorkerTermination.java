package com.ibm.ecm.migration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Bounded two-stage executor termination; never claims native calls were stopped. */
final class WorkerTermination {
    static final class Outcome {
        private final boolean timedOut;
        private final boolean terminated;

        Outcome(boolean timedOut, boolean terminated) {
            this.timedOut = timedOut;
            this.terminated = terminated;
        }

        boolean timedOut() {
            return timedOut;
        }

        boolean terminated() {
            return terminated;
        }
    }

    private WorkerTermination() {
    }

    static Outcome await(ExecutorService executor, long waitSeconds,
                         long graceSeconds, Runnable shutdownRequest)
            throws InterruptedException {
        executor.shutdown();
        if (executor.awaitTermination(waitSeconds, TimeUnit.SECONDS)) {
            return new Outcome(false, true);
        }

        shutdownRequest.run();
        executor.shutdown();
        return new Outcome(true,
                executor.awaitTermination(graceSeconds, TimeUnit.SECONDS));
    }

    static boolean awaitGrace(ExecutorService executor, long graceSeconds,
                              Runnable shutdownRequest) throws InterruptedException {
        shutdownRequest.run();
        executor.shutdown();
        return executor.awaitTermination(graceSeconds, TimeUnit.SECONDS);
    }

    static boolean awaitGraceAfterInterrupt(ExecutorService executor,
                                            long graceSeconds,
                                            Runnable shutdownRequest) {
        boolean terminated = false;
        try {
            terminated = awaitGrace(executor, graceSeconds, shutdownRequest);
        } catch (InterruptedException repeatedInterrupt) {
            // The flag is restored below; cleanup remains unconfirmed.
        } finally {
            Thread.currentThread().interrupt();
        }
        return terminated;
    }
}
