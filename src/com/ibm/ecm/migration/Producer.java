/*
 * Projekt: CM Migrator 2.2.1.
 * @Author: Aleksej Voronin, Sven Lindt
 * @Date:   26.01.2026
 */
package com.ibm.ecm.migration;

import com.ibm.mm.sdk.common.DKConstant;
import com.ibm.mm.sdk.common.DKDDO;
import com.ibm.mm.sdk.common.DKNVPair;
import com.ibm.mm.sdk.common.DKPidICM;
import com.ibm.mm.sdk.common.DKRetrieveOptionsICM;
import com.ibm.mm.sdk.common.dkResultSetCursor;
import com.ibm.mm.sdk.server.DKDatastoreICM;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class Producer implements Runnable {
    private static final Logger logger = LogManager.getLogger(Producer.class);
    
    private final BlockingQueue<MigrationItem> queue;
    private final MigrationConfig config;
    private final MigrationJournal journal;
    private final MigrationStats stats;
    private final WorkerFailureState workerFailureState;
    private final int consumerCount;
    // ponytail: OperatorConsole ref for dashboard phase/strategy updates
    private OperatorConsole operatorConsole;

    private static final Pattern VALID_ITEM_TYPE = Pattern.compile("[a-zA-Z0-9_]+");

    public Producer(BlockingQueue<MigrationItem> queue, MigrationConfig config, MigrationJournal journal,
                    MigrationStats stats, WorkerFailureState workerFailureState, OperatorConsole operatorConsole) {
        this.queue = queue;
        this.config = config;
        this.journal = journal;
        this.stats = stats;
        this.workerFailureState = workerFailureState;
        this.operatorConsole = operatorConsole;
        this.consumerCount = config.getThreadCount();
    }

    @Override
    public void run() {
        logger.info("Producer started.");

        Map<String, String> mapping = config.getItemTypeMapping();
        if (mapping == null || mapping.isEmpty()) {
            logger.warn("No MIGRATE_ITEM_TYPES configured. Producer finishing.");
            enqueuePoisonPill();
            return;
        }

        int discoveryThreads = Math.min(mapping.size(), 10);
        ExecutorService discoveryExecutor = Executors.newFixedThreadPool(discoveryThreads);

        try {
            for (Map.Entry<String, String> entry : mapping.entrySet()) {
                final String sourceType = entry.getKey();
                final String destType = entry.getValue();

                if (!VALID_ITEM_TYPE.matcher(sourceType).matches() || !VALID_ITEM_TYPE.matcher(destType).matches()) {
                    IllegalArgumentException failure = new IllegalArgumentException(
                            "Invalid ItemType format: " + sourceType + " -> " + destType);
                    workerFailureState.record(failure);
                    logger.error("SECURITY ALERT: Invalid ItemType format detected! Source={}, Dest={}. Aborting.",
                            sourceType, destType, failure);
                    ShutdownCoordinator.requestShutdown();
                    break;
                }

                discoveryExecutor.submit(() -> {
                    ThreadContext.put("itemType", sourceType);
                    
                    try (CMConnection localConn = new CMConnection(
                            config.getSourceSSID(), 
                            config.getSourceUser(), 
                            config.getSourcePassword(), 
                            CMConnection.Role.SOURCE)) {
                        
                        localConn.connect(); 
                        
                        processItemType(localConn, sourceType, destType);
                    } catch (Exception e) {
                        workerFailureState.record(e);
                        logger.error("Error processing ItemType {}", sourceType, e);
                        ShutdownCoordinator.requestShutdown();
                    } finally {
                        ThreadContext.clearAll();
                    }
                });
            }

            discoveryExecutor.shutdown();
            
            while (!ShutdownCoordinator.isShuttingDown()) {
                if (discoveryExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    break;
                }
            }
            
            if (ShutdownCoordinator.isShuttingDown()) {
                logger.warn("Shutdown requested. Waiting for discovery executor to stop gracefully.");

                while (!discoveryExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    // Keep in-flight IBM SDK calls alive until they return naturally.
                }
            }

        } catch (InterruptedException e) {
            workerFailureState.record(e);
            logger.error("Producer interrupted", e);
            ShutdownCoordinator.requestShutdown();
            discoveryExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            if (ShutdownCoordinator.isShuttingDown() || workerFailureState.hasFailure()) {
                logger.info("Producer stopping due to shutdown or failure. Skipping Poison Pills; consumers stop via shutdown flag.");
            } else {
                logger.info("All ItemTypes processed. Enqueuing Poison Pills...");
                enqueuePoisonPill();
            }
        }
    }

    private void enqueuePoisonPill() {
        if (ShutdownCoordinator.isShuttingDown()) {
            logger.info("Shutdown active. Not enqueueing Poison Pills; consumers stop via shutdown flag.");
            return;
        }

        int sent = 0;

        for (int i = 0; i < consumerCount; i++) {
            try {
                boolean offered = false;

                for (int attempt = 0; attempt < 20; attempt++) {
                    if (ShutdownCoordinator.isShuttingDown()) {
                        logger.info("Shutdown became active while enqueueing Poison Pills. Sent {}/{} pills.",
                                sent, consumerCount);
                        return;
                    }

                    if (queue.offer(MigrationItem.POISON_PILL, 100, TimeUnit.MILLISECONDS)) {
                        offered = true;
                        sent++;
                        break;
                    }

                    if (Thread.currentThread().isInterrupted()) {
                        Thread.currentThread().interrupt();
                        logger.info("Producer interrupted while enqueueing Poison Pills. Sent {}/{} pills.",
                                sent, consumerCount);
                        return;
                    }
                }

                if (!offered) {
                    logger.warn("Could not enqueue Poison Pill {}/{} within timeout. QueueDepth={}",
                            i + 1, consumerCount, queue.size());
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.info("Producer interrupted while enqueueing Poison Pills. Sent {}/{} pills.",
                        sent, consumerCount);
                break;
            }
        }

        logger.info("Poison Pills enqueued: {}/{}", sent, consumerCount);
    }

    private void processItemType(CMConnection conn, String sourceType, String destType) throws Exception {
        DKDatastoreICM ds = conn.getDatastore();
            logger.info("Processing ItemType: {} -> {}", sourceType, destType);

            int preloadedCount = journal.preloadCache(sourceType);
            logger.info("Cache preloaded with existing entries for {}: {}", sourceType, preloadedCount);

            final String query = buildQuery(sourceType);
            logger.info("CM Query for {}: {}", sourceType, query);

            boolean isDeleteMode = "DELETE".equalsIgnoreCase(config.getOperationMode());
            
            DKRetrieveOptionsICM dkOpt = DKRetrieveOptionsICM.createInstance(ds);
            
            // Producer needs only PIDs. For DELETE, retrieve as minimally as possible.
            // For MIGRATE, keep baseAttributes true to maintain current SDK behavior.
            dkOpt.baseAttributes(!isDeleteMode);
            dkOpt.childListOneLevel(false);
            dkOpt.partsList(false);
            
            logger.info("Producer retrieve options for {}: baseAttributes={}, childListOneLevel=false, partsList=false",
                    sourceType, !isDeleteMode);
            
            final DKNVPair[] options = new DKNVPair[]{
                    new DKNVPair(DKConstant.DK_CM_PARM_MAX_RESULTS, "0"), 
                    new DKNVPair(DKConstant.DK_CM_PARM_RETRIEVE, dkOpt),
                    new DKNVPair(DKConstant.DK_CM_PARM_END, null)
            };

            // PASS 1: Zählen (TOTAL) - immer, auch bei DELETE.
            // Wichtig: Total muss vor PASS2 stabil sein und darf während Enqueue nicht wachsen.
            String strategy = config.getProducerCountStrategy();
            if (operatorConsole != null) {
                operatorConsole.setStrategy(strategy);
                operatorConsole.setPhase(OperatorConsole.Phase.COUNTING);
            }
            long totalMatched = 0;
            
            logger.info("Using SDK-based PASS1 count for {} (Strategy={}, OperationMode={})",
                    sourceType, strategy, config.getOperationMode());
            
            dkResultSetCursor cursor = ds.execute(query, DKConstant.DK_CM_XQPE_QL_TYPE, options);
            
            try {
                DKDDO item;
                while (!ShutdownCoordinator.isShuttingDown()
                        && (item = cursor.fetchNext()) != null) {
                    totalMatched++;
                }
            
                if (!ShutdownCoordinator.isShuttingDown()) {
                    stats.addTotalItems(totalMatched);
                    logger.info("SDK PASS1 matched for {}: {}", sourceType, totalMatched);
                } else {
                    logger.warn("SDK PASS1 interrupted by shutdown for {} after {} matched items",
                            sourceType, totalMatched);
                    return;
                }
            } finally {
                cursor.close();
                cursor.destroy();
            }

            // PASS 2: Enqueue (Verarbeitung)
            if (operatorConsole != null) operatorConsole.setPhase(OperatorConsole.Phase.DISCOVERING);
            dkResultSetCursor cursor2 = ds.execute(query, DKConstant.DK_CM_XQPE_QL_TYPE, options);
            
            long fetched = 0;
            long enqueued = 0;
            long skipped = 0;
            long tStart = System.currentTimeMillis();

            try {
                DKDDO item;
                while (!ShutdownCoordinator.isShuttingDown()
                        && (item = cursor2.fetchNext()) != null) {
                        
                    fetched++;
                    String pidStr = ((DKPidICM) item.getPidObject()).pidString();

                    // Journal prüfen
                    boolean alreadyDone = isDeleteMode 
                            ? journal.isDeleted(pidStr, sourceType) 
                            : journal.isMigrated(pidStr, sourceType);

                    if (alreadyDone) {
                        skipped++;
                        stats.incrementSkipped();
                        continue;
                    }

                    MigrationItem migrationItem = new MigrationItem(pidStr, sourceType, destType);

                    while (!ShutdownCoordinator.isShuttingDown()) {
                        if (queue.offer(migrationItem, 1, TimeUnit.SECONDS)) {
                            enqueued++;
                            break;
                        }
                    }

                    if (ShutdownCoordinator.isShuttingDown()) {
                        logger.warn("Producer stopping enqueue for {} due to shutdown. Fetched={}, Enqueued={}, Skipped={}",
                                sourceType, fetched, enqueued, skipped);
                        break;
                    }

                    if (fetched % 10000 == 0) {
                         logger.info("Producer Progress {}: Fetched={}, Enqueued={}, Skipped={}", sourceType, fetched, enqueued, skipped);
                    }
                }
            } finally {
                cursor2.close();
                cursor2.destroy();
            }

            long duration = System.currentTimeMillis() - tStart;
            long avgFetch = fetched > 0 ? duration / fetched : 0;

        logger.info("PRODUCER STATS Type={} Fetched={} Enqueued={} Skipped={} AvgFetchMs={} QueueDepth={}",
                sourceType, fetched, enqueued, skipped, avgFetch, queue.size());
    }

    private String buildQuery(String sourceType) {
        String predicate = config.getFilterPredicate();
        if (predicate == null) predicate = "";
        predicate = predicate.trim();

        String root = "/" + sourceType;

        if (predicate.isEmpty()) {
            return root;
        }

        if (predicate.startsWith("/")) {
            return predicate;
        }

        String p = predicate;
        if (!p.startsWith("[")) p = "[" + p;
        if (!p.endsWith("]")) p = p + "]";

        return root + p;
    }
}
