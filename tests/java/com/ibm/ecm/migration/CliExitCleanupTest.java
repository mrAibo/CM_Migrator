package com.ibm.ecm.migration;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tests the exit/cleanup contract using the real production CliLifecycleRunner.executeCli path.
 * No copied logic, no double hook registration, no IBM CM connections.
 */
public final class CliExitCleanupTest {

    public static void main(String[] args) {
        testRunTerminationExceptionUnconfirmed();
        testRunTerminationExceptionConfirmed();
        testGenericExceptionUnconfirmed();
        testSuccessConfirmed();
        testFinishBeforeExit();
        testRepeatedTwentyTimes();
        System.out.println("CliExitCleanupTest: PASS");
    }

    private static void testRunTerminationExceptionUnconfirmed() {
        ShutdownCoordinator.reset();
        CliShutdownLifecycle lifecycle = new CliShutdownLifecycle(1);
        AtomicBoolean operationCalled = new AtomicBoolean(false);

        int exitCode = CliLifecycleRunner.executeCli(lifecycle, () -> {
            operationCalled.set(true);
            throw new RunTerminationException(
                    RunTerminationException.Reason.TIMEOUT,
                    "simulated timeout",
                    false,     // terminationConfirmed = false
                    null);
        });

        assertTrue(operationCalled.get(), "operation must be called (unconfirmed)");
        assertEquals(124, exitCode, "exit code must be 124 (TIMEOUT)");
        assertFalse(lifecycle.isRegistered(),
                "hook must be removed after finish(false)");
    }

    private static void testRunTerminationExceptionConfirmed() {
        ShutdownCoordinator.reset();
        CliShutdownLifecycle lifecycle = new CliShutdownLifecycle(1);
        AtomicBoolean operationCalled = new AtomicBoolean(false);

        int exitCode = CliLifecycleRunner.executeCli(lifecycle, () -> {
            operationCalled.set(true);
            throw new RunTerminationException(
                    RunTerminationException.Reason.FAILED,
                    "simulated failure",
                    true,      // terminationConfirmed = true
                    null);
        });

        assertTrue(operationCalled.get(), "operation must be called (confirmed)");
        assertEquals(1, exitCode, "exit code must be 1 (FAILED)");
        assertFalse(lifecycle.isRegistered(),
                "hook must be removed after finish(true)");
    }

    private static void testGenericExceptionUnconfirmed() {
        ShutdownCoordinator.reset();
        CliShutdownLifecycle lifecycle = new CliShutdownLifecycle(1);
        AtomicBoolean operationCalled = new AtomicBoolean(false);

        int exitCode = CliLifecycleRunner.executeCli(lifecycle, () -> {
            operationCalled.set(true);
            throw new RuntimeException("simulated crash");
        });

        assertTrue(operationCalled.get(), "operation must be called (generic crash)");
        assertEquals(1, exitCode, "generic exception must return exit code 1");
        assertFalse(lifecycle.isRegistered(),
                "hook must be removed after generic exception finish(false)");
    }

    private static void testSuccessConfirmed() {
        ShutdownCoordinator.reset();
        CliShutdownLifecycle lifecycle = new CliShutdownLifecycle(1);
        AtomicBoolean operationCalled = new AtomicBoolean(false);

        int exitCode = CliLifecycleRunner.executeCli(lifecycle, () -> {
            operationCalled.set(true);
            // success — no exception
        });

        assertTrue(operationCalled.get(), "operation must be called (success)");
        assertEquals(0, exitCode, "success must return exit code 0");
        assertFalse(lifecycle.isRegistered(),
                "hook must be removed after successful finish(true)");
    }

    private static void testFinishBeforeExit() {
        ShutdownCoordinator.reset();
        CliShutdownLifecycle lifecycle = new CliShutdownLifecycle(1);
        AtomicBoolean operationCalled = new AtomicBoolean(false);

        // No manual lifecycle.register() — CliLifecycleRunner.executeCli handles registration.
        int exitCode = CliLifecycleRunner.executeCli(lifecycle, () -> {
            operationCalled.set(true);
            throw new RunTerminationException(
                    RunTerminationException.Reason.FAILED,
                    "simulated failure",
                    true,
                    null);
        });

        assertTrue(operationCalled.get(), "operation must be called (finish-before-exit)");
        assertFalse(lifecycle.isRegistered(),
                "finish() must deregister hook before exit code is returned");
        assertEquals(1, exitCode, "FAILED reason must return exit code 1");
    }

    private static void testRepeatedTwentyTimes() {
        for (int i = 0; i < 20; i++) {
            ShutdownCoordinator.reset();
            CliShutdownLifecycle lifecycle = new CliShutdownLifecycle(1);
            final int iteration = i;
            AtomicBoolean operationCalled = new AtomicBoolean(false);
            int exitCode;
            switch (iteration % 3) {
                case 0:
                    exitCode = CliLifecycleRunner.executeCli(lifecycle, () -> {
                        operationCalled.set(true);
                    });
                    assertEquals(0, exitCode, "success exit at iteration " + iteration);
                    break;
                case 1:
                    exitCode = CliLifecycleRunner.executeCli(lifecycle, () -> {
                        operationCalled.set(true);
                        throw new RuntimeException("boom " + iteration);
                    });
                    assertEquals(1, exitCode, "crash exit at iteration " + iteration);
                    break;
                default:
                    exitCode = CliLifecycleRunner.executeCli(lifecycle, () -> {
                        operationCalled.set(true);
                        throw new RunTerminationException(
                                RunTerminationException.Reason.TIMEOUT,
                                "timeout " + iteration, false, null);
                    });
                    assertEquals(124, exitCode, "timeout exit at iteration " + iteration);
                    break;
            }
            assertTrue(operationCalled.get(),
                    "operation must be called at iteration " + iteration);
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
