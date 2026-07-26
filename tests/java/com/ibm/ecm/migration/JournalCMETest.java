/*
 * Real MigrationJournal persistedWrites integration tests.
 * Uses H2 in temp dir — no simulation.
 */
package com.ibm.ecm.migration;

import java.io.File;
import java.nio.file.*;
import java.lang.reflect.Field;

public final class JournalCMETest {

    private static int passed, failed;

    public static void main(String[] args) throws Exception {
        testFreshCounterStartsAtZero();
        testCommittedWritesIncrementAfterDrain();
        testReopenAndPreloadDoesNotTouchCounter();
        testWriterFailureDoesNotIncrementCounter();
        testConcurrentReadersNoCME();
        testCloseConfirmsFinalCounter();
        System.out.println("\n=== Results: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) System.exit(1);
    }

    static void testFreshCounterStartsAtZero() throws Exception {
        System.out.println("\n--- fresh counter starts at 0 ---");
        Path tmp = Files.createTempDirectory("jcme-");
        try {
            MigrationJournal j = new MigrationJournal(tmp.toString());
            j.init();
            check("fresh-zero", j.getPersistedWritesThisRun() == 0);
            j.close();
        } finally { deleteDir(tmp.toFile()); }
    }

    static void testCommittedWritesIncrementAfterDrain() throws Exception {
        System.out.println("\n--- committed writes increment after drain ---");
        Path tmp = Files.createTempDirectory("jcme-");
        try {
            MigrationJournal j = new MigrationJournal(tmp.toString());
            j.init();

            for (int i = 0; i < 10; i++) j.logSuccess("item-" + i, "TYPE", "sha", "dest");
            // The writer is asynchronous; before waiting, any value up to 10 is valid.
            check("before-drain-not-overcounted", j.getPersistedWritesThisRun() <= 10);

            awaitPersisted(j, 10);
            // After drain: writer committed, counter = 10
            check("after-drain-10", j.getPersistedWritesThisRun() == 10);

            // More writes
            for (int i = 10; i < 25; i++) j.logSuccess("item-" + i, "TYPE", "sha", "dest");
            awaitPersisted(j, 25);
            check("cumulative-25", j.getPersistedWritesThisRun() == 25);

            // Failures also go through writeBatchToDb + commit → they DO increment
            j.logFailure("fail-1", "TYPE", "simulated error");
            awaitPersisted(j, 26);
            check("failure-also-increments", j.getPersistedWritesThisRun() == 26);

            // Skipped merge entries also committed
            for (int i = 0; i < 3; i++) j.logSkipped("skip-" + i, "TYPE", "already-processed");
            awaitPersisted(j, 29);
            check("skipped-increment", j.getPersistedWritesThisRun() == 29);

            j.close();
        } finally { deleteDir(tmp.toFile()); }
    }

    static void testReopenAndPreloadDoesNotTouchCounter() throws Exception {
        System.out.println("\n--- reopen + preload leaves counter at 0 ---");
        Path tmp = Files.createTempDirectory("jcme-");
        try {
            // Write 20 entries
            MigrationJournal j1 = new MigrationJournal(tmp.toString());
            j1.init();
            for (int i = 0; i < 20; i++) j1.logSuccess("old-" + i, "TYPE_A", "sha", "dest");
            awaitPersisted(j1, 20);
            j1.close();

            // Reopen: preload the old entries
            MigrationJournal j2 = new MigrationJournal(tmp.toString());
            j2.init();
            j2.preloadCache("TYPE_A");

            // Counter must be 0 — preload does not commit new writes
            check("preload-does-not-increment", j2.getPersistedWritesThisRun() == 0);

            // New writes in this run DO increment
            for (int i = 0; i < 5; i++) j2.logSuccess("new-" + i, "TYPE_A", "sha", "dest");
            awaitPersisted(j2, 5);
            check("new-run-increments-5", j2.getPersistedWritesThisRun() == 5);

            j2.close();
        } finally { deleteDir(tmp.toFile()); }
    }

    static void testWriterFailureDoesNotIncrementCounter() throws Exception {
        System.out.println("\n--- writer failure leaves counter unchanged ---");
        Path tmp = Files.createTempDirectory("jcme-");
        try {
            MigrationJournal j = new MigrationJournal(tmp.toString());
            j.init();

            // 5 successful writes
            for (int i = 0; i < 5; i++) j.logSuccess("ok-" + i, "TYPE", "sha", "dest");
            awaitPersisted(j, 5);
            long beforeFailure = j.getPersistedWritesThisRun();
            check("before-failure-5", beforeFailure == 5);

            // Simulate writer failure via reflection
            Field wf = MigrationJournal.class.getDeclaredField("writerFailure");
            wf.setAccessible(true);
            wf.set(j, new IllegalStateException("simulated"));

            // Now log more — they won't be written because writer is stopped
            try {
                for (int i = 0; i < 10; i++) j.logSuccess("fail-" + i, "TYPE", "sha", "dest");
            } catch (Exception ignored) {}

            // Counter unchanged
            long afterFailure = j.getPersistedWritesThisRun();
            check("after-failure-unchanged", afterFailure == beforeFailure);

            try { j.close(); } catch (Exception ignored) {}
        } finally { deleteDir(tmp.toFile()); }
    }

    static void testConcurrentReadersNoCME() throws Exception {
        System.out.println("\n--- concurrent readers + writer: no CME ---");
        Path tmp = Files.createTempDirectory("jcme-");
        try {
            MigrationJournal j = new MigrationJournal(tmp.toString());
            j.init();

            // Pre-populate with 100 entries
            for (int i = 0; i < 100; i++) j.logSuccess("conv-" + i, "TYPE_C", "sha", "dest");
            awaitPersisted(j, 100);

            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(10);
            java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger(0);

            // Writer thread: keep logging
            Thread writer = new Thread(() -> {
                try {
                    for (int i = 0; i < 500; i++) j.logSuccess("w-" + i, "TYPE_W", "sha", "dest");
                } catch (Exception e) { errors.incrementAndGet(); }
            });

            // 10 reader threads: call getter repeatedly
            for (int t = 0; t < 10; t++) {
                new Thread(() -> {
                    try {
                        long prev = -1;
                        for (int i = 0; i < 5000; i++) {
                            long val = j.getPersistedWritesThisRun();
                            // Value should be monotonic (AtomicLong-based)
                            if (val < 0 || (prev >= 0 && val < prev)) errors.incrementAndGet();
                            prev = val;
                        }
                    } catch (Exception e) { errors.incrementAndGet(); }
                    finally { latch.countDown(); }
                }).start();
            }
            writer.start();
            writer.join();
            awaitPersisted(j, 600);
            latch.await();

            check("cme-zero-errors", errors.get() == 0);
            long finalVal = j.getPersistedWritesThisRun();
            check("cme-plausible", finalVal >= 100 && finalVal <= 700);

            System.out.println("  final committed: " + finalVal);
            j.close();
        } finally { deleteDir(tmp.toFile()); }
    }

    static void testCloseConfirmsFinalCounter() throws Exception {
        System.out.println("\n--- close confirms final counter ---");
        Path tmp = Files.createTempDirectory("jcme-");
        try {
            MigrationJournal j = new MigrationJournal(tmp.toString());
            j.init();
            for (int i = 0; i < 42; i++) j.logSuccess("close-" + i, "TYPE", "sha", "dest");
            awaitPersisted(j, 42);
            long beforeClose = j.getPersistedWritesThisRun();
            check("before-close-42", beforeClose == 42);
            j.close();
            // After close: counter should still be accessible
            long afterClose = j.getPersistedWritesThisRun();
            check("after-close-still-42", afterClose == 42);
        } finally { deleteDir(tmp.toFile()); }
    }

    // ── helpers (same as MigrationJournalFailClosedTest) ──

    private static void awaitPersisted(MigrationJournal journal, long expected) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (journal.getPersistedWritesThisRun() >= expected) return;
            Thread.sleep(50);
        }
    }

    static void check(String label, boolean condition) {
        if (condition) { passed++; System.out.println("  PASS: " + label); }
        else { failed++; System.out.println("  FAIL: " + label); }
    }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDir(f);
                else f.delete();
            }
        }
        dir.delete();
    }
}
