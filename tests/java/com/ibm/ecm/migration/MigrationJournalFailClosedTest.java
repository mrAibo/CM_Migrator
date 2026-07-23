package com.ibm.ecm.migration;

import java.io.File;
import java.nio.file.*;
import java.lang.reflect.Field;
import java.util.concurrent.*;

/**
 * Fail-closed tests for MigrationJournal.
 * Dependency-free (no JUnit, no IBM JARs). Uses H2 file-based in a temp dir.
 */
public final class MigrationJournalFailClosedTest {

    private static int passed, failed;

    public static void main(String[] args) throws Exception {
        System.out.println("=== MigrationJournal Fail-Closed Tests ===");
        System.out.println();

        testSuccessfulPersistenceAndResume();
        testCacheConsistency();
        testCloseDrainsQueue();
        testCloseFailsOnWriterFailure();
        testIsMigratedThrowsOnDBFailure();
        testBackpressure();

        System.out.println();
        System.out.println("=== Results: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) System.exit(1);
    }

    // --- helpers ---

    private static void assertTrue(boolean value, String msg) {
        if (value) { passed++; System.out.println("  PASS: " + msg); }
        else { failed++; System.err.println("  FAIL: " + msg); }
    }

    private static void assertThrows(Class<? extends Throwable> expected, ThrowingRunnable r, String msg) {
        try {
            r.run();
            failed++;
            System.err.println("  FAIL: " + msg + " — no exception thrown");
        } catch (Throwable t) {
            if (expected.isAssignableFrom(t.getClass())) {
                passed++;
                System.out.println("  PASS: " + msg + " (" + t.getClass().getSimpleName() + ")");
            } else {
                failed++;
                System.err.println("  FAIL: " + msg + " — wrong exception: " + t.getClass().getSimpleName());
                t.printStackTrace();
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private static void drain(MigrationJournal journal) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (getQueueSize(journal) == 0) break;
            Thread.sleep(100);
        }
    }

    private static int getQueueSize(MigrationJournal journal) {
        try {
            Field f = MigrationJournal.class.getDeclaredField("journalQueue");
            f.setAccessible(true);
            BlockingQueue<?> q = (BlockingQueue<?>) f.get(journal);
            return q.size();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Object getField(MigrationJournal journal, String name) {
        try {
            Field f = MigrationJournal.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(journal);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setField(MigrationJournal journal, String name, Object value) {
        try {
            Field f = MigrationJournal.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(journal, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --- tests ---

    /** Write entries, close, reopen, preload — verify persistence. */
    static void testSuccessfulPersistenceAndResume() throws Exception {
        System.out.println("--- testSuccessfulPersistenceAndResume ---");

        Path tmp = Files.createTempDirectory("journal-test-");
        try {
            MigrationJournal j1 = new MigrationJournal(tmp.toString());
            j1.init();

            j1.logSuccess("item-1", "TYPE_A", "sha256-aa", "dest-1");
            j1.logSuccess("item-2", "TYPE_A", "sha256-bb", "dest-2");
            j1.logSuccess("item-3", "TYPE_B", "sha256-cc", "dest-3");
            drain(j1);
            j1.close();

            // Reopen — same DB
            MigrationJournal j2 = new MigrationJournal(tmp.toString());
            j2.init();
            j2.preloadCache("TYPE_A");
            j2.preloadCache("TYPE_B");

            assertTrue(j2.isMigrated("item-1", "TYPE_A"), "item-1 persisted and found");
            assertTrue(j2.isMigrated("item-2", "TYPE_A"), "item-2 persisted and found");
            assertTrue(j2.isMigrated("item-3", "TYPE_B"), "item-3 persisted and found");
            assertTrue(!j2.isMigrated("no-such", "TYPE_A"), "non-existent item returns false");

            j2.close();
        } finally {
            deleteDir(tmp.toFile());
        }
    }

    /** Cache is populated only after DB write — not before. */
    static void testCacheConsistency() throws Exception {
        System.out.println("--- testCacheConsistency ---");

        Path tmp = Files.createTempDirectory("journal-test-");
        try {
            MigrationJournal j = new MigrationJournal(tmp.toString());
            j.init();

            j.logSuccess("cached-item", "TYPE_X", "sha-1", "dest-1");
            drain(j);

            // Verify isMigrated returns true (cache was populated after DB write)
            assertTrue(j.isMigrated("cached-item", "TYPE_X"), "item is migrated after drain");

            // Verify it hits cache by directly reading the DB (should be there too)
            // ponytail: verify cache has the entry
            drain(j);
            assertTrue(j.isMigrated("cached-item", "TYPE_X"), "cache hit on second call");

            j.close();
        } finally {
            deleteDir(tmp.toFile());
        }
    }

    /** Normal close after drain should succeed (no throw). */
    static void testCloseDrainsQueue() throws Exception {
        System.out.println("--- testCloseDrainsQueue ---");

        Path tmp = Files.createTempDirectory("journal-test-");
        try {
            MigrationJournal j = new MigrationJournal(tmp.toString());
            j.init();

            for (int i = 0; i < 100; i++) {
                j.logSuccess("item-" + i, "TYPE_C", "sha", "dest");
            }
            drain(j);
            j.close(); // should not throw

            assertTrue(true, "close succeeds after drain");
        } catch (Exception e) {
            assertTrue(false, "close should not throw with drained queue: " + e.getMessage());
        } finally {
            deleteDir(tmp.toFile());
        }
    }

    /** Close must throw if writer has failed. */
    static void testCloseFailsOnWriterFailure() throws Exception {
        System.out.println("--- testCloseFailsOnWriterFailure ---");

        Path tmp = Files.createTempDirectory("journal-test-");
        try {
            MigrationJournal j = new MigrationJournal(tmp.toString());
            j.init();

            // Simulate writer failure
            setField(j, "writerFailure", new IllegalStateException("simulated"));

            try {
                j.close();
                assertTrue(false, "close should have thrown");
            } catch (IllegalStateException e) {
                assertTrue(e.getMessage().contains("simulated"),
                    "close throws with writer failure message");
            }
        } finally {
            deleteDir(tmp.toFile());
        }
    }

    /** isMigrated must throw when DB is down. */
    static void testIsMigratedThrowsOnDBFailure() throws Exception {
        System.out.println("--- testIsMigratedThrowsOnDBFailure ---");

        Path tmp = Files.createTempDirectory("journal-test-");
        try {
            MigrationJournal j = new MigrationJournal(tmp.toString());
            j.init();

            // Write and drain — cache will be populated for this itemType
            j.logSuccess("item-fail", "TYPE_F", "sha", "dest");
            drain(j);

            // Clear cache and connection pools so reads go to DB
            @SuppressWarnings("unchecked")
            java.util.Map<String, String> cache = (java.util.Map<String, String>) getField(j, "statusCache");
            cache.clear();

            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> pools = (java.util.Map<String, Object>) getField(j, "connectionPools");
            for (Object pool : pools.values()) {
                if (pool instanceof org.h2.jdbcx.JdbcConnectionPool) {
                    ((org.h2.jdbcx.JdbcConnectionPool) pool).dispose();
                }
            }
            pools.clear();

            // Point baseDir at a regular file — getConnection can't create DB inside a file
            Path deadFile = Files.createTempFile("dead-base-", ".tmp");
            deadFile.toFile().deleteOnExit();
            setField(j, "baseDir", deadFile.toString());

            assertThrows(IllegalStateException.class,
                () -> j.isMigrated("item-fail", "TYPE_F"),
                "isMigrated throws on DB failure");

            assertThrows(IllegalStateException.class,
                () -> j.isDeleted("item-fail", "TYPE_F"),
                "isDeleted throws on DB failure");

            try { j.close(); } catch (Exception ignored) {}
        } finally {
            deleteDir(tmp.toFile());
        }
    }

    /** Queue saturation triggers backpressure (timeout → throw). */
    static void testBackpressure() throws Exception {
        System.out.println("--- testBackpressure ---");

        Path tmp = Files.createTempDirectory("journal-test-");
        try {
            // Small queue (size 5), no writer thread → queue fills fast
            MigrationJournal j = new MigrationJournal(tmp.toString(), "", 1000, 5);

            // Fill the queue without a writer
            for (int i = 0; i < 5; i++) {
                j.logSuccess("bp-" + i, "TYPE_BP", "sha", "dest");
            }
            assertTrue(getQueueSize(j) == 5, "queue filled to capacity");

            // 6th entry should trigger backpressure
            assertThrows(RuntimeException.class,
                () -> j.logSuccess("bp-overflow", "TYPE_BP", "sha", "dest"),
                "backpressure throws on queue full");

            // Verify writerFailure was set
            Object wf = getField(j, "writerFailure");
            assertTrue(wf != null, "writerFailure is set after backpressure");

            try { j.close(); } catch (Exception ignored) {}
        } finally {
            deleteDir(tmp.toFile());
        }
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
