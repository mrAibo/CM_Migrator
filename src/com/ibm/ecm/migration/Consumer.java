/*
 * Projekt: CM Migrator 2.2.1.
 * @Author: Aleksej Voronin, Sven Lindt
 * @Date:   26.01.2026
 */
package com.ibm.ecm.migration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class Consumer implements Runnable {
    private static final Logger logger = LogManager.getLogger(Consumer.class);

    private final BlockingQueue<MigrationItem> queue;
    private final ItemMigrator migrator;
    private final MigrationJournal journal;
    private final MigrationStats stats;
    private final MigrationConfig config;

    public Consumer(BlockingQueue<MigrationItem> queue,
                    ItemMigrator migrator,
                    MigrationJournal journal,
                    MigrationStats stats,
                    MigrationConfig config) {
        this.queue = queue;
        this.migrator = migrator;
        this.journal = journal;
        this.stats = stats;
        this.config = config;
    }

    @Override
    public void run() {
        logger.info("Consumer thread started: {}", Thread.currentThread().getName());

        List<MigrationItem> batch = new ArrayList<>();
        int batchSize = config.getBatchSize();

        boolean isDeleteMode = "DELETE".equals(config.getOperationMode());
        boolean doubleCheck = config.isConsumerDoubleCheck();

        try {
            while (true) {
                if (ShutdownCoordinator.isShuttingDown() && batch.isEmpty()) {
                    logger.info("Consumer {} stopping because shutdown was requested",
                            Thread.currentThread().getName());
                    break;
                }

                MigrationItem item = queue.poll(1, TimeUnit.SECONDS);

                if (item == null) {
                    if (ShutdownCoordinator.isShuttingDown()) {
                        if (!batch.isEmpty()) {
                            logger.warn("Consumer {} dropping in-memory batch of {} items due to shutdown. Items will be rediscovered on next run.",
                                    Thread.currentThread().getName(), batch.size());
                            batch.clear();
                        }
                        break;
                    }
                } else {
                    // Each consumer receives its own poison-pill from Producer.
                    // No need to forward it back to the queue.
                    if (item.isPoisonPill()) {
                        if (!batch.isEmpty()) {
                            if (ShutdownCoordinator.isShuttingDown()) {
                                logger.warn("Consumer {} received poison pill during shutdown. Dropping in-memory batch of {} items.",
                                        Thread.currentThread().getName(), batch.size());
                                batch.clear();
                            } else {
                                processBatch(batch);
                                batch.clear();
                            }
                        }

                        logger.debug("Consumer {} received poison pill, terminating",
                                Thread.currentThread().getName());
                        break;
                    }

                    if (ShutdownCoordinator.isShuttingDown()) {
                        logger.info("Consumer {} stopping before accepting new item because shutdown was requested",
                                Thread.currentThread().getName());
                        break;
                    }

                    if (doubleCheck) {
                        boolean alreadyProcessed = isDeleteMode
                                ? journal.isDeleted(item.getItemId(), item.getSourceItemType())
                                : journal.isMigrated(item.getItemId(), item.getSourceItemType());

                        if (alreadyProcessed) {
                            stats.incrementSkipped();
                        } else {
                            batch.add(item);
                        }
                    } else {
                        batch.add(item);
                    }
                }

                if (ShutdownCoordinator.isShuttingDown()) {
                    if (!batch.isEmpty()) {
                        logger.warn("Consumer {} stopping before processing batch of {} items due to shutdown. Items will be rediscovered on next run.",
                                Thread.currentThread().getName(), batch.size());
                        batch.clear();
                    }
                    break;
                }

                if (batch.size() >= batchSize || (item == null && !batch.isEmpty())) {
                    int currentBatchSize = batch.size();
                    processBatch(batch);
                    batch.clear();
                    logSparse(currentBatchSize);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        logger.info("Consumer thread finished: {}", Thread.currentThread().getName());
    }

    private void logSparse(int lastBatchSize) {
        if (config.isLogItemsBatched()) {
            long total = stats.getProcessedItems();
            int interval = config.getLogBatchInterval();
            // Nur einmal protokollieren, wenn die Intervallgrenze überschritten wird (thread-safe-ish check)
            if (total > 0 && (total / interval > (total - lastBatchSize) / interval)) {
                logger.info("CONSUMER PROGRESS: Processed={} | Success={} | Errors={} | Rate={}%", 
                    total, stats.getSuccessItems(), stats.getErrorItems(), 
                    String.format("%.1f", (stats.getSuccessItems() / (double) Math.max(1, total)) * 100));
            }
        }
    }

    private void processBatch(List<MigrationItem> batch) {
        if (batch == null || batch.isEmpty()) return;
    
        if (ShutdownCoordinator.isShuttingDown()) {
            logger.warn("Batch of {} items skipped because shutdown was requested. Items will be rediscovered on next run.",
                    batch.size());
            return;
        }
    
        boolean isDeleteMode = "DELETE".equals(config.getOperationMode());
        boolean isDryRun = config.isDryRun();
    
        processBatchIterative(batch, isDeleteMode, isDryRun);
    }

    /**
     * Iterative Batch Splitting:
     * Ersetzt den rekursiven Prozess „processBatchWithSplit“, um StackOverflowError 
     * in Enterprise-Szenarien mit großen Batches und häufigen Fehlern zu vermeiden.
     */
    private void processBatchIterative(List<MigrationItem> initialBatch, boolean isDeleteMode, boolean isDryRun) {
        if (initialBatch == null || initialBatch.isEmpty()) return;

        Deque<List<MigrationItem>> stack = new ArrayDeque<>();
        stack.push(initialBatch);

        try {
            while (!stack.isEmpty()) {
                if (ShutdownCoordinator.isShuttingDown()) {
                    logger.warn("Batch processing stopped because shutdown was requested. Remaining batch groups={}",
                            stack.size());
                    break;
                }

                List<MigrationItem> currentBatch = stack.pop();
                if (currentBatch.isEmpty()) continue;

                final int maxRetries = 3;
                int attempts = 0;
                boolean batchSuccess = false;

                while (attempts < maxRetries) {
                    if (ShutdownCoordinator.isShuttingDown()) {
                        logger.warn("Batch retry loop stopped before attempt because shutdown was requested. BatchSize={}",
                                currentBatch.size());
                        return;
                    }

                    attempts++;

                    boolean ok = isDeleteMode
                            ? migrator.deleteBatch(currentBatch, isDryRun)
                            : migrator.migrateBatch(currentBatch);

                    if (ok) {
                        if (isDeleteMode) {
                            for (MigrationItem item : currentBatch) {
                                if (!isDryRun) {
                                    journal.logDeletion(item.getItemId(), item.getSourceItemType(), "Deleted successfully");
                                    stats.incrementDeleted();
                                } else {
                                    stats.incrementSuccess();
                                }
                            }
                        } else {
                            journal.logBatchSuccess(currentBatch);
                            for (int i = 0; i < currentBatch.size(); i++) {
                                stats.incrementSuccess();
                            }
                        }
                        batchSuccess = true;
                        break;
                    }

                    Exception last = migrator.getLastError();

                    if (ShutdownCoordinator.isShuttingDown() || last instanceof InterruptedException) {
                        logger.warn("Batch processing interrupted by shutdown. BatchSize={} msg={}",
                                currentBatch.size(), last == null ? "<null>" : last.getMessage());
                        return;
                    }

                    boolean transientErr = isTransient(last);

                    if (transientErr && attempts < maxRetries) {
                        try {
                            backoffSleep(attempts);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();

                            if (ShutdownCoordinator.isShuttingDown()) {
                                logger.warn("Batch backoff interrupted by shutdown.");
                                return;
                            }

                            break;
                        }
                    } else {
                        // Permanent oder Wiederholungsversuche erschöpft -> split
                        break;
                    }
                }

                if (ShutdownCoordinator.isShuttingDown()) {
                    logger.warn("Skipping batch split because shutdown was requested. BatchSize={}",
                            currentBatch.size());
                    return;
                }

                if (!batchSuccess) {
                    if (currentBatch.size() > 1) {
                        int mid = currentBatch.size() / 2;
                        List<MigrationItem> left = new ArrayList<>(currentBatch.subList(0, mid));
                        List<MigrationItem> right = new ArrayList<>(currentBatch.subList(mid, currentBatch.size()));
                        // Reihenfolge: zuerst rechts, dann links, sodass links zuerst angezeigt wird.
                        stack.push(right);
                        stack.push(left);
                    } else {
                        // size == 1 -> final single item fallback
                        processSingleItem(currentBatch.get(0), isDeleteMode, isDryRun);
                    }
                }
            }
        } finally {
            // Garantierte Bereinigung sämtlicher temporärer Dateien, die in diesem Thread/Batch registriert sind
            ResourceGuardian.cleanup();
        }
    }

    private void processSingleItem(MigrationItem item, boolean isDeleteMode, boolean isDryRun) {
        final int maxRetries = 3;
        int attempts = 0;

        while (true) {
            if (ShutdownCoordinator.isShuttingDown()) {
                logger.warn("Single item fallback skipped because shutdown was requested. itemId={}",
                        item.getItemId());
                return;
            }

            attempts++;

            boolean success = isDeleteMode
                    ? migrator.delete(item, isDryRun)
                    : migrator.migrate(item);

            if (success) {
                if (isDeleteMode) {
                    if (!isDryRun) {
                        journal.logDeletion(item.getItemId(), item.getSourceItemType(), "Deleted (Single Fallback)");
                        stats.incrementDeleted();
                    } else {
                        stats.incrementSuccess();
                    }
                } else {
                    journal.logSuccess(item.getItemId(), item.getSourceItemType(), item.getChecksum(), item.getDestItemId());
                    stats.incrementSuccess();
                }
                return;
            }

            Exception last = migrator.getLastError();

            if (ShutdownCoordinator.isShuttingDown() || last instanceof InterruptedException) {
                logger.warn("Single item fallback stopped due to shutdown. itemId={} msg={}",
                        item.getItemId(), last == null ? "<null>" : last.getMessage());
                return;
            }

            boolean transientErr = isTransient(last);
            boolean canRetry = attempts < maxRetries && transientErr;

            logger.warn("Single failed (attempt {}). transient={} itemId={} msg={}",
                    attempts, transientErr, item.getItemId(), (last == null ? "<null>" : last.getMessage()), last);

            if (!canRetry) {
                stats.incrementFailed();
                String reason = (last != null && last.getMessage() != null)
                        ? last.getMessage()
                        : "Failed in Single Item Fallback";
                journal.logFailure(item.getItemId(), item.getSourceItemType(), reason);
                return;
            }

            try {
                backoffSleep(attempts);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();

                if (ShutdownCoordinator.isShuttingDown()) {
                    logger.warn("Single item fallback backoff interrupted by shutdown. itemId={}",
                            item.getItemId());
                    return;
                }

                stats.incrementFailed();
                journal.logFailure(item.getItemId(), item.getSourceItemType(), "Failed in Single Item Fallback (Interrupted)");
                return;
            }
        }
    }

    private static void backoffSleep(int attempt) throws InterruptedException {
        long base = 250L;
        long max = 4000L;

        long exp = Math.min(max, base * (1L << Math.max(0, attempt - 1)));
        long jitter = ThreadLocalRandom.current().nextLong(0, 250L);

        Thread.sleep(exp + jitter);
    }

    private static boolean isTransient(Throwable t) {
        if (t == null) return false;

        // Round 13A: deterministic non-transient errors must never enter the
        // batch-splitter retry loop (would re-download the same large file 6×).
        if (t instanceof PermanentMigrationException) return false;
        if (t instanceof InterruptedException) return false;
        if (t instanceof SocketTimeoutException) return true;
        if (t instanceof ConnectException) return true;
        if (t instanceof InterruptedIOException) return true;
        if (t instanceof SQLTransientException) return true;
        if (t instanceof SQLRecoverableException) return true;

        String msg;
        try {
            msg = String.valueOf(t.getMessage()).toLowerCase();
        } catch (Exception e) {
            msg = "";
        }

        if (msg.contains("timeout")
                || msg.contains("timed out")
                || msg.contains("connection reset")
                || msg.contains("broken pipe")
                || msg.contains("temporarily unavailable")) {
            return true;
        }

        return isTransient(t.getCause());
    }
}
