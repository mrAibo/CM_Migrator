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
        testFailureIdentityPreserved();
        testFinishBeforeExit();
        testRepeatedTwentyTimes();
        System.out.println("CliExitCleanupTest: PASS");
    }

    private static void testRunTerminationExceptionUnconfirmed() {
        ShutdownCoordinator.reset();
        CliShutdownLifecycle lifecycle = new CliShutdownLifecycle(1);
        AtomicBoolean operationCalled = new AtomicBoolean(false);

        RunTerminationException expected = new RunTerminationException(
                RunTerminationException.Reason.TIMEOUT,
                "simulated timeout",
                false,     // terminationConfirmed = false
                null);
        CliLifecycleRunner.CliRunResult result = CliLifecycleRunner.executeCli(lifecycle, () -> {
            operationCalled.set(true);
            throw expected;
        });

        assertTrue(operationCalled.get(), "operation must be called (unconfirmed)");
        assertEquals(124, result.exitCode(), "exit code must be 124 (TIMEOUT)");
        assertSame(expected, result.failure(), "exception reference must be preserved");
        assertTrue("simulated timeout".equals(expected.getMessage()), "message must be preserved");
        assertFalse(result.terminationConfirmed(), "must be unconfirmed");
        assertFalse(lifecycle.isRegistered(),
                "hook must be removed after finish(false)");
    }

    private static void testRunTerminationExceptionConfirmed() {
        ShutdownCoordinator.reset();
        CliShutdownLifecycle lifecycle = new CliShutdownLifecycle(1);
        AtomicBoolean operationCalled = new AtomicBoolean(false);

        RunTerminationException expected = new RunTerminationException(
                RunTerminationException.Reason.FAILED,
                "simulated failure",
                true,      // terminationConfirmed = true
                new IllegalStateException("root cause"));
        CliLifecycleRunner.CliRunResult result = CliLifecycleRunner.executeCli(lifecycle, () -> {
            operationCalled.set(true);
            throw expected;
        });

        assertTrue(operationCalled.get(), "operation must be called (confirmed)");
        assertEquals(1, result.exitCode(), "exit code must be 1 (FAILED)");
        assertSame(expected, result.failure(), "exception reference must be preserved");
        assertSame(expected.getCause(), ((RunTerminationException) result.failure()).getCause(),
                "cause reference must be preserved");
        assertTrue(result.terminationConfirmed(), "must be confirmed");
        assertFalse(lifecycle.isRegistered(),
                "hook must be removed after finish(true)");
    }

    private static void testGenericExceptionUnconfirmed() {
        ShutdownCoordinator.reset();
        CliShutdownLifecycle lifecycle = new CliShutdownLifecycle(1);
        AtomicBoolean operationCalled = new AtomicBoolean(false);

        IllegalStateException expected = new IllegalStateException("simulated crash");
        CliLifecycleRunner.CliRunResult result = CliLifecycleRunner.executeCli(lifecycle, () -> {
            operationCalled.set(true);
            throw expected;
        });

        assertTrue(operationCalled.get(), "operation must be called (generic crash)");
        assertEquals(1, result.exitCode(), "generic exception must return exit code 1");
        assertSame(expected, result.failure(), "exception reference must be preserved");
        assertFalse(result.terminationConfirmed(), "generic exception must be unconfirmed");
        assertFalse(lifecycle.isRegistered(),
                "hook must be removed after generic exception finish(false)");
    }

    private static void testSuccessConfirmed() {
        ShutdownCoordinator.reset();
        CliShutdownLifecycle lifecycle = new CliShutdownLifecycle(1);
        AtomicBoolean operationCalled = new AtomicBoolean(false);

        CliLifecycleRunner.CliRunResult result = CliLifecycleRunner.executeCli(lifecycle, () -> {
            operationCalled.set(true);
        });

        assertTrue(operationCalled.get(), "operation must be called (success)");
        assertEquals(0, result.exitCode(), "success must return exit code 0");
        assertTrue(result.terminationConfirmed(), "success must be confirmed");
        assertNull(result.failure(), "success must have no failure");
        assertFalse(lifecycle.isRegistered(),
                "hook must be removed after successful finish(true)");
    }

    /** Failure identity: no new or truncated exception is manufactured. */
    private static void testFailureIdentityPreserved() {
        ShutdownCoordinator.reset();
        CliShutdownLifecycle lifecycle = new CliShutdownLifecycle(1);
        AtomicBoolean operationCalled = new AtomicBoolean(false);

        RunTerminationException expected = new RunTerminationException(
                RunTerminationException.Reason.POLICY,
                "policy refused",
                false,
                new SecurityException("blocked"));
        CliLifecycleRunner.CliRunResult result = CliLifecycleRunner.executeCli(lifecycle, () -> {
            operationCalled.set(true);
            throw expected;
        });

        assertTrue(operationCalled.get(), "operation must be called");
        assertEquals(2, result.exitCode(), "POLICY exit code");
        assertSame(expected, result.failure(), "exact exception instance preserved");
        assertSame(expected.getCause(),
                ((RunTerminationException) result.failure()).getCause(),
                "cause chain preserved");
        assertFalse(lifecycle.isRegistered(), "hook removed");
    }

    private static void testFinishBeforeExit() {
        ShutdownCoordinator.reset();
        CliShutdownLifecycle lifecycle = new CliShutdownLifecycle(1);
        AtomicBoolean operationCalled = new AtomicBoolean(false);

        // No manual lifecycle.register() — CliLifecycleRunner.executeCli handles registration.
        CliLifecycleRunner.CliRunResult result = CliLifecycleRunner.executeCli(lifecycle, () -> {
            operationCalled.set(true);
            throw new RunTerminationException(
                    RunTerminationException.Reason.FAILED,
                    "simulated failure",
                    true,
                    null);
        });

        assertTrue(operationCalled.get(), "operation must be called (finish-before-exit)");
        assertFalse(lifecycle.isRegistered(),
                "finish() must deregister hook before result is returned");
        assertEquals(1, result.exitCode(), "FAILED reason must return exit code 1");
        assertNotNull(result.failure(), "failure must be preserved");
    }

    private static void testRepeatedTwentyTimes() {
        for (int i = 0; i < 20; i++) {
            ShutdownCoordinator.reset();
            CliShutdownLifecycle lifecycle = new CliShutdownLifecycle(1);
            final int iteration = i;
            AtomicBoolean operationCalled = new AtomicBoolean(false);
            CliLifecycleRunner.CliRunResult result;
            switch (iteration % 3) {
                case 0:
                    result = CliLifecycleRunner.executeCli(lifecycle, () -> {
                        operationCalled.set(true);
                    });
                    assertEquals(0, result.exitCode(), "success exit at " + iteration);
                    break;
                case 1:
                    result = CliLifecycleRunner.executeCli(lifecycle, () -> {
                        operationCalled.set(true);
                        throw new RuntimeException("boom " + iteration);
                    });
                    assertEquals(1, result.exitCode(), "crash exit at " + iteration);
                    break;
                default:
                    result = CliLifecycleRunner.executeCli(lifecycle, () -> {
                        operationCalled.set(true);
                        throw new RunTerminationException(
                                RunTerminationException.Reason.TIMEOUT,
                                "timeout " + iteration, false, null);
                    });
                    assertEquals(124, result.exitCode(), "timeout exit at " + iteration);
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

    private static void assertSame(Object expected, Object actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": not same reference. expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertNotNull(Object value, String message) {
        if (value == null) throw new AssertionError(message);
    }

    private static void assertNull(Object value, String message) {
        if (value != null) throw new AssertionError(message + ": expected null, got " + value);
    }
}
