package com.ibm.ecm.migration;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class WebGuiRunSlotTest {
    public static void main(String[] args) throws Exception {
        testParallelReservationStartsExactlyOneWorker();
        testRejectedStartDoesNotResetShutdown();
        testConfirmedAndUnconfirmedRelease();
        testRollbackBeforeThreadStart();
        System.out.println("WebGuiRunSlotTest: PASS");
    }

    private static void testParallelReservationStartsExactlyOneWorker() throws Exception {
        AtomicBoolean slot = new AtomicBoolean(false);
        AtomicInteger reservations = new AtomicInteger();
        AtomicInteger workerStarts = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        ShutdownCoordinator.requestShutdown();
        Runnable attempt = () -> {
            ready.countDown();
            await(start);
            if (WebGuiRunSlot.reserve(slot)) {
                reservations.incrementAndGet();
                workerStarts.incrementAndGet();
            }
            done.countDown();
        };

        new Thread(attempt, "reservation-1").start();
        new Thread(attempt, "reservation-2").start();
        ready.await();
        start.countDown();
        done.await();

        assertEquals(1, reservations.get(), "exactly one start reserves the slot");
        assertEquals(1, workerStarts.get(), "exactly one worker starts");
        assertFalse(ShutdownCoordinator.isShuttingDown(), "winning reservation resets shutdown once");
    }

    private static void testRejectedStartDoesNotResetShutdown() {
        AtomicBoolean slot = new AtomicBoolean(true);
        ShutdownCoordinator.requestShutdown();

        assertFalse(WebGuiRunSlot.reserve(slot), "parallel start must be rejected");
        assertTrue(ShutdownCoordinator.isShuttingDown(), "rejected start must not reset shutdown");
    }

    private static void testConfirmedAndUnconfirmedRelease() {
        AtomicBoolean slot = new AtomicBoolean(true);
        WebGuiRunSlot.releaseIfConfirmed(slot, true);
        assertTrue(WebGuiRunSlot.reserve(slot), "confirmed completion allows a later run");

        WebGuiRunSlot.releaseIfConfirmed(slot, false);
        ShutdownCoordinator.requestShutdown();
        assertFalse(WebGuiRunSlot.reserve(slot), "unconfirmed completion keeps later runs blocked");
        assertTrue(ShutdownCoordinator.isShuttingDown(), "blocked later run must not reset shutdown");
    }

    private static void testRollbackBeforeThreadStart() {
        AtomicBoolean slot = new AtomicBoolean(false);
        assertTrue(WebGuiRunSlot.reserve(slot), "pre-start reservation");
        WebGuiRunSlot.rollbackBeforeThreadStart(slot);
        assertFalse(slot.get(), "failed thread start must release reservation");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test interrupted", e);
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
