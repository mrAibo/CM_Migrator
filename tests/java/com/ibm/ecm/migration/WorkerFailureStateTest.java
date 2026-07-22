package com.ibm.ecm.migration;

public final class WorkerFailureStateTest {
    public static void main(String[] args) {
        WorkerFailureState state = new WorkerFailureState();

        assertFalse(state.hasFailure(), "new state must be empty");
        assertFalse(state.record(null), "null failure must be ignored");

        RuntimeException first = new RuntimeException("first");
        IllegalStateException second = new IllegalStateException("second");

        assertTrue(state.record(first), "first failure must be recorded");
        assertFalse(state.record(second), "later failure must not replace first");
        assertSame(first, state.get(), "first failure must be retained");

        try {
            state.throwIfPresent("worker failed");
            throw new AssertionError("throwIfPresent must throw");
        } catch (IllegalStateException e) {
            assertSame(first, e.getCause(), "original failure must be the cause");
        }

        state.clear();
        assertFalse(state.hasFailure(), "clear must reset state");
        state.throwIfPresent("must not throw");

        System.out.println("WorkerFailureStateTest: PASS");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertFalse(boolean value, String message) {
        if (value) throw new AssertionError(message);
    }

    private static void assertSame(Object expected, Object actual, String message) {
        if (expected != actual) throw new AssertionError(message);
    }
}
