package com.ibm.ecm.migration;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Stores the first asynchronous worker failure for later propagation to the
 * coordinating thread. Later failures are intentionally retained only in logs.
 */
final class WorkerFailureState {
    private final AtomicReference<Throwable> firstFailure = new AtomicReference<>();

    boolean record(Throwable failure) {
        if (failure == null) {
            return false;
        }
        return firstFailure.compareAndSet(null, failure);
    }

    Throwable get() {
        return firstFailure.get();
    }

    boolean hasFailure() {
        return firstFailure.get() != null;
    }

    void clear() {
        firstFailure.set(null);
    }

    void throwIfPresent(String message) {
        Throwable failure = firstFailure.get();
        if (failure != null) {
            throw new IllegalStateException(message, failure);
        }
    }

    Runnable guard(Runnable worker, Runnable onFailure) {
        java.util.Objects.requireNonNull(worker, "worker");
        java.util.Objects.requireNonNull(onFailure, "onFailure");
        return () -> {
            try {
                worker.run();
            } catch (RuntimeException | Error failure) {
                record(failure);
                try {
                    onFailure.run();
                } catch (RuntimeException callbackFailure) {
                    failure.addSuppressed(callbackFailure);
                }
                throw failure;
            }
        };
    }
}
