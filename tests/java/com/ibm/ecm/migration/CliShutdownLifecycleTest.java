package com.ibm.ecm.migration;

public final class CliShutdownLifecycleTest {
    public static void main(String[] args) throws Exception {
        testSimulatedHookRequestsShutdownAndWaitsBoundedly();
        testConfirmedCompletionSignal();
        testHookRemovedAfterNormalCompletion();
        testRepeatedCliLifecyclesDoNotAccumulateHooks();
        System.out.println("CliShutdownLifecycleTest: PASS");
    }

    private static void testSimulatedHookRequestsShutdownAndWaitsBoundedly() {
        ShutdownCoordinator.reset();
        CliShutdownLifecycle lifecycle = new CliShutdownLifecycle(0);
        long started = System.nanoTime();
        boolean confirmed = lifecycle.requestShutdownAndAwait();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertTrue(ShutdownCoordinator.isShuttingDown(), "hook must request shutdown");
        assertFalse(confirmed, "unsignalled run must remain unconfirmed");
        assertTrue(elapsedMs < 1_000L, "hook wait must be bounded");
    }

    private static void testConfirmedCompletionSignal() {
        ShutdownCoordinator.reset();
        CliShutdownLifecycle lifecycle = new CliShutdownLifecycle(1);
        lifecycle.finish(true);
        assertTrue(lifecycle.requestShutdownAndAwait(), "confirmed run completion must reach hook");

        CliShutdownLifecycle unconfirmed = new CliShutdownLifecycle(1);
        unconfirmed.finish(false);
        assertFalse(unconfirmed.requestShutdownAndAwait(), "unconfirmed completion must remain fail-closed");
    }

    private static void testHookRemovedAfterNormalCompletion() {
        CliShutdownLifecycle lifecycle = new CliShutdownLifecycle(1);
        lifecycle.register();
        assertTrue(lifecycle.isRegistered(), "CLI hook must register");
        lifecycle.finish(true);
        assertFalse(lifecycle.isRegistered(), "normal completion must remove CLI hook");
    }

    private static void testRepeatedCliLifecyclesDoNotAccumulateHooks() {
        for (int i = 0; i < 20; i++) {
            CliShutdownLifecycle lifecycle = new CliShutdownLifecycle(1);
            lifecycle.register();
            lifecycle.finish(true);
            assertFalse(lifecycle.isRegistered(), "CLI hook leaked at iteration " + i);
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertFalse(boolean value, String message) {
        assertTrue(!value, message);
    }
}
