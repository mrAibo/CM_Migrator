package com.ibm.ecm.migration;

/**
 * Tests the exit/cleanup contract: finish() must run before exit code is
 * returned, and generic exceptions must be treated as unconfirmed termination.
 * No IBM CM connections, no JVM exit — pure deterministic lifecycle checks.
 */
public final class CliExitCleanupTest {

    /** Minimal operation abstraction — no interface, just a package-private hook. */
    @FunctionalInterface
    interface CliOperation {
        void run() throws Exception;
    }

    /**
     * Package-private helper shared by Main.runCli and Verifier.runCli tests.
     * Returns exit code after guaranteed finish().
     */
    static int executeCli(CliOperation operation,
                          CliShutdownLifecycle lifecycle) {
        boolean terminationConfirmed = true;
        int exitCode = 0;
        try {
            lifecycle.register();
            operation.run();
        } catch (RunTerminationException e) {
            terminationConfirmed = e.isTerminationConfirmed();
            exitCode = e.getExitCode();
        } catch (Exception e) {
            terminationConfirmed = false;
            exitCode = 1;
        } finally {
            lifecycle.finish(terminationConfirmed);
        }
        return exitCode;
    }

    public static void main(String[] args) {
        testRunTerminationExceptionUnconfirmed();
        testGenericExceptionUnconfirmed();
        testSuccessConfirmed();
        testFinishBeforeExit();
        testRepeatedTwentyTimes();
        System.out.println("CliExitCleanupTest: PASS");
    }

    private static void testRunTerminationExceptionUnconfirmed() {
        ShutdownCoordinator.reset();
        CliShutdownLifecycle lifecycle = new CliShutdownLifecycle(1);

        int exitCode = executeCli(() -> {
            throw new RunTerminationException(
                    RunTerminationException.Reason.TIMEOUT,
                    "simulated timeout",
                    false,     // terminationConfirmed = false
                    null);
        }, lifecycle);

        assertEquals(124, exitCode, "exit code must be 124 (TIMEOUT)");
        assertFalse(lifecycle.isRegistered(),
                "hook must be removed after finish(false)");
        assertFalse(ShutdownCoordinator.isShuttingDown() &&
                lifecycle.requestShutdownAndAwait(),
                "unconfirmed finish must not signal confirmed");
    }

    private static void testGenericExceptionUnconfirmed() {
        ShutdownCoordinator.reset();
        CliShutdownLifecycle lifecycle = new CliShutdownLifecycle(1);

        int exitCode = executeCli(() -> {
            throw new RuntimeException("simulated crash");
        }, lifecycle);

        assertEquals(1, exitCode, "generic exception must return exit code 1");
        assertFalse(lifecycle.isRegistered(),
                "hook must be removed after generic exception finish(false)");
    }

    private static void testSuccessConfirmed() {
        ShutdownCoordinator.reset();
        CliShutdownLifecycle lifecycle = new CliShutdownLifecycle(1);

        int exitCode = executeCli(() -> {
            // success — no exception
        }, lifecycle);

        assertEquals(0, exitCode, "success must return exit code 0");
        assertFalse(lifecycle.isRegistered(),
                "hook must be removed after successful finish(true)");
    }

    private static void testFinishBeforeExit() {
        ShutdownCoordinator.reset();

        CliShutdownLifecycle lifecycle = new CliShutdownLifecycle(1);
        lifecycle.register();
        assertTrue(lifecycle.isRegistered(),
                "hook must be registered before operation");

        int exitCode = executeCli(() -> {
            throw new RunTerminationException(
                    RunTerminationException.Reason.FAILED,
                    "simulated failure",
                    true,
                    null);
        }, lifecycle);

        assertFalse(lifecycle.isRegistered(),
                "finish() must deregister hook before exit code is returned");
        assertEquals(1, exitCode, "FAILED reason must return exit code 1");
    }

    private static void testRepeatedTwentyTimes() {
        for (int i = 0; i < 20; i++) {
            ShutdownCoordinator.reset();
            CliShutdownLifecycle lifecycle = new CliShutdownLifecycle(1);
            final int iteration = i;
            int exitCode;
            switch (iteration % 3) {
                case 0:
                    exitCode = executeCli(() -> {}, lifecycle);
                    assertEquals(0, exitCode, "success exit at iteration " + iteration);
                    break;
                case 1:
                    exitCode = executeCli(() -> {
                        throw new RuntimeException("boom " + iteration);
                    }, lifecycle);
                    assertEquals(1, exitCode, "crash exit at iteration " + iteration);
                    break;
                default:
                    exitCode = executeCli(() -> {
                        throw new RunTerminationException(
                                RunTerminationException.Reason.TIMEOUT,
                                "timeout " + iteration, false, null);
                    }, lifecycle);
                    assertEquals(124, exitCode, "timeout exit at iteration " + iteration);
                    break;
            }
            assertFalse(lifecycle.isRegistered(),
                    "hook leaked at iteration " + iteration);
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertFalse(boolean value, String message) {
        assertTrue(!value, message);
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
