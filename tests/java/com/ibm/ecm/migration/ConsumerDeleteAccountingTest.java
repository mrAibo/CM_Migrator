package com.ibm.ecm.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public final class ConsumerDeleteAccountingTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        testRealDeleteCountsAsSuccessfulDeletion();
        testDryRunDoesNotCountAsDeletion();
        testShutdownStopsBeforeDeleteBatch();
        System.out.printf("ConsumerDeleteAccountingTest: %d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    private static void testShutdownStopsBeforeDeleteBatch() {
        ShutdownCoordinator.reset();
        ItemMigrator migrator = new ItemMigrator(null);
        ShutdownCoordinator.requestShutdown();
        try {
            check("shutdown rejects delete batch",
                    !migrator.deleteBatch(List.of(
                            new MigrationItem("pid-stop", "TYPE_A", "TYPE_A")), false));
            check("shutdown is reported before source connection access",
                    migrator.getLastError() instanceof InterruptedException);
        } finally {
            ShutdownCoordinator.reset();
        }
    }

    private static void testRealDeleteCountsAsSuccessfulDeletion() throws Exception {
        MigrationStats stats = runDelete(false);
        check("real delete processed once", stats.getProcessedItems() == 1);
        check("real delete is successful", stats.getSuccessItems() == 1);
        check("real delete increments deleted", stats.getDeletedItems() == 1);
        check("real delete has no failure", stats.getFailedItems() == 0);
    }

    private static void testDryRunDoesNotCountAsDeletion() throws Exception {
        MigrationStats stats = runDelete(true);
        check("dry-run processed once", stats.getProcessedItems() == 1);
        check("dry-run is successful", stats.getSuccessItems() == 1);
        check("dry-run does not increment deleted", stats.getDeletedItems() == 0);
    }

    private static MigrationStats runDelete(boolean dryRun) throws Exception {
        ShutdownCoordinator.reset();
        Path tmp = Files.createTempDirectory("consumer-delete-accounting-");
        Path configFile = tmp.resolve("migration.properties");
        Files.writeString(configFile,
                "OPERATION_MODE=DELETE\n"
                        + "DRY_RUN=" + dryRun + "\n"
                        + "BATCH_SIZE=1\n"
                        + "CONSUMER_DOUBLECHECK=false\n");

        MigrationConfig config = new MigrationConfig(configFile.toString());
        MigrationJournal journal = new MigrationJournal(tmp.resolve("journal").toString());
        journal.init();
        MigrationStats stats = new MigrationStats();
        BlockingQueue<MigrationItem> queue = new LinkedBlockingQueue<>();
        queue.add(new MigrationItem("pid-1", "TYPE_A", "TYPE_A"));
        queue.add(MigrationItem.POISON_PILL);

        ItemMigrator migrator = new ItemMigrator(null) {
            @Override
            public boolean deleteBatch(List<MigrationItem> batch, boolean ignoredDryRun) {
                return true;
            }
        };

        try {
            new Consumer(queue, migrator, journal, stats, config).run();
            return stats;
        } finally {
            journal.close();
            deleteTree(tmp);
            ShutdownCoordinator.reset();
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + name);
        }
    }
}
