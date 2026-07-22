package com.ibm.ecm.migration;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ShutdownCoordinator {
    private static final AtomicBoolean SHUTTING_DOWN = new AtomicBoolean(false);

    private ShutdownCoordinator() {
    }

    public static void requestShutdown() {
        SHUTTING_DOWN.set(true);
    }
    
    public static void reset() {
        SHUTTING_DOWN.set(false);
    }
    
    public static boolean isShuttingDown() {
        return SHUTTING_DOWN.get() || Thread.currentThread().isInterrupted();
    }
}