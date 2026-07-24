/*
 * Projekt: CM Migrator 2.2.1.
 * @Author: Aleksej Voronin, Sven Lindt
 * @Date:   26.01.2026
 */
package com.ibm.ecm.migration;

import com.ibm.mm.sdk.common.DKDDO;
import com.ibm.mm.sdk.common.DKLobICM;
import com.ibm.mm.sdk.common.DKParts;
import com.ibm.mm.sdk.common.DKRetrieveOptionsICM;
import com.ibm.mm.sdk.server.DKDatastoreICM;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifiziert die Integritär der migrierten Objekte anhand der SHA-256 Checksumme.
 *
 */
public class Verifier {
    private static final Logger logger = LogManager.getLogger(Verifier.class);
    private static final Logger consoleLogger = LogManager.getLogger("ConsoleProgress");

    // Round 9A: Verifier-only Diagnose-Schalter.
    // sortMode: "migrator" (default) = wie ItemMigrator.copyParts; "verifier" = alter Multi-Key-Sort.
    private static final String VERIFY_SORT_MODE =
            System.getProperty("cm.migrator.verify.sortMode", "migrator").trim().toLowerCase();
    // autoMarkForRemigration-Override: wenn gesetzt, überschreibt den Wert aus migration.properties.
    // Erlaubt Diagnose-Verifier-Läufe ohne AUDIT_LOG-Mutation.
    private static final String AUTO_REMIGRATE_OVERRIDE_PROP = "cm.migrator.verify.autoMarkForRemigration";

    // Round 9B: worklistMode steuert die Quelle der zu prüfenden Items.
    // - "default" (Default): bisheriger Pfad — nur AUDIT_LOG.STATUS='SUCCESS' minus bereits OK-verifizierte.
    // - "nonOk":             diagnostische Re-Verifikation existierender VERIFICATION_LOG-Zeilen mit STATUS<>'OK'.
    private static final String VERIFY_WORKLIST_MODE =
            System.getProperty("cm.migrator.verify.worklistMode", "default").trim().toLowerCase();
    // Larger buffers reduce SDK/RM read-call overhead for big content streams.
    // Minimum guard prevents accidental tiny buffer values.
    private static final int VERIFY_BUFFER_SIZE = Math.max(65536, Integer.getInteger("cm.migrator.verify.bufferSize", 1048576));
    
    // Warn only for very slow single-item hash operations.
    // 0 disables the warning.
    private static final long VERIFY_SLOW_HASH_WARN_MS =
            Long.getLong("cm.migrator.verify.slowHashWarnMs", 10000L);

    private static boolean isNonOkWorklistMode() {
        return "nonok".equals(VERIFY_WORKLIST_MODE) || "non-ok".equals(VERIFY_WORKLIST_MODE);
    }

    // Counter
    private static final AtomicInteger totalVerified = new AtomicInteger(0);
    private static final AtomicInteger totalErrors = new AtomicInteger(0);
    private static final AtomicInteger totalProcessed = new AtomicInteger(0);
    private static final AtomicInteger hashSampleCounter = new AtomicInteger(0);
    private static final AtomicInteger totalSkipped = new AtomicInteger(0);
    private static final AtomicInteger totalCascadeDeleted = new AtomicInteger(0);  // v1.25: Neue Option: Cascade Delete
    private static final AtomicInteger totalSourceDeleted = new AtomicInteger(0);   // v1.25: Source not found

    private static final ThreadLocal<java.security.MessageDigest> SHA256_DIGEST = 
        ThreadLocal.withInitial(() -> {
            try { return java.security.MessageDigest.getInstance("SHA-256"); } 
            catch (Exception e) { throw new RuntimeException(e); }
        });

    // Nur für Kompatibilität mit älteren Einträgen
    private enum JournalSchema {
        NEW_AUDITLOG,     // AUDITLOG(ITEMID, DESTITEMID, CHECKSUM, STATUS, ...)
        OLD_AUDITLOG     // AUDIT_LOG(ITEM_ID, DEST_ITEM_ID, CHECKSUM, STATUS, ...)
    }

    private enum VerifySchema {
        NEW_VERIFICATIONLOG,  // VERIFICATIONLOG(ITEMID, STATUS, SOURCEHASH, DESTHASH, VERIFICATIONTIME, MESSAGE)
        OLD_VERIFICATION_LOG  // VERIFICATION_LOG(ITEM_ID, STATUS, SOURCE_HASH, DEST_HASH, VERIFIED_AT, MESSAGE)
    }

    public static void main(String[] args) {
        System.exit(runCli(args));
    }

    public static int runCli(String[] args) {
        ShutdownCoordinator.reset();
        String configPath = args.length > 0 ? args[0] : "conf/migration.properties";
        long graceSeconds = 60L;
        try {
            graceSeconds = new MigrationConfig(configPath).getShutdownGraceSeconds();
        } catch (Exception e) {
            logger.warn("Could not read CLI shutdown grace; using 60 seconds: {}", e.getMessage());
        }

        CliShutdownLifecycle lifecycle = new CliShutdownLifecycle(graceSeconds);
        boolean terminationConfirmed = true;
        try {
            lifecycle.register();
            run(configPath);
            return 0;
        } catch (RunTerminationException e) {
            terminationConfirmed = e.isTerminationConfirmed();
            logger.error("Verification terminated: {}", e.getMessage(), e.getCause());
            return e.getExitCode();
        } catch (Exception e) {
            logger.error("Verification crashed", e);
            return 1;
        } finally {
            lifecycle.finish(terminationConfirmed);
        }
    }

    /** Shared throwing verifier core used by CLI, WebGUI, and internal callers. */
    public static void run(String configPath) throws Exception {
        // Zeige den Start Banner
        System.out.println(ConsoleUI.banner("2.2.1"));
        System.out.println(ConsoleUI.info("Verification Tool (Robust Mode)"));

        consoleLogger.info("Starting Verification Tool (Robust Mode)...");
        // Round 9B: erweitertes Diagnose-Banner (umfasst worklistMode).
        String autoMarkOverrideRaw = System.getProperty(AUTO_REMIGRATE_OVERRIDE_PROP);
        consoleLogger.info("Round 9B diagnostics: worklistMode={}, sortMode={}, autoMarkForRemigration override={}",
                VERIFY_WORKLIST_MODE, VERIFY_SORT_MODE,
                (autoMarkOverrideRaw == null ? "<unset>" : autoMarkOverrideRaw));
        if (isNonOkWorklistMode()) {
            consoleLogger.warn("Diagnostic re-verification of existing non-OK verification rows; no migration/remigration is performed.");
        }

        CMConnectionPool pool = null;
        ExecutorService executor = null;
        VerificationLogger verificationLogger = null;
        Thread monitorThread = null;
        int shutdownGraceSeconds = 60;
        boolean terminationConfirmed = true;

        try {
            MigrationConfig config = new MigrationConfig(configPath);
            OperationalPolicy.enforceCascadeDeleteDisabled(config);
            shutdownGraceSeconds = config.getShutdownGraceSeconds();
            int threadCount = config.getThreadCount();
            // Journal base directory (muss absolut sein für H2 2.x)
            String journalDir = config.getDbPath(); // lese DB_PATH 
            if (journalDir == null || journalDir.trim().isEmpty()) {
                journalDir = "./data";
            }
            
            // Kompatibilität: altes Verzeichnis ".data" (muss absolut sein für H2 2.x)
            if (journalDir.equals(".data")) {
                journalDir = "./.data";
            }
            
            final String baseDir = new File(journalDir).getAbsolutePath();
            
            // 1. Initialisiere den Connection Pool
            pool = new CMConnectionPool(config);
            pool.init();
            consoleLogger.info("Connection Pools initialized.");

            // 1b. Initialisiere den Verification Logger (Batch)
            verificationLogger = new VerificationLogger();

            // 2. Initialisiere den Thread Pool
            final int queueCapacity = Math.max(2000, threadCount * 200);
            ThreadFactory tf = new ThreadFactory() {
                private final AtomicInteger n = new AtomicInteger(1);

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "verifier-" + n.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            };

            executor = new ThreadPoolExecutor(
                    threadCount,
                    threadCount,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(queueCapacity),
                    tf,
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );

            consoleLogger.info("Initialized Thread Pool with " + threadCount + " threads (queueCapacity=" + queueCapacity + ").");

            MigrationStats stats = new MigrationStats() {
                @Override public long getProcessedItems() { return totalProcessed.get(); }
                @Override public long getSuccessItems() { return totalVerified.get(); }
                @Override public long getFailedItems() { return totalErrors.get(); }
                @Override public long getSkippedItems() { return totalSkipped.get(); }
                @Override public long getDeletedItems() { return totalCascadeDeleted.get(); } // v1.25
                @Override public long getTotalItems() { return super.getTotalItems(); } // Will be set later
            };
            
            WebServer.attachCurrentStats(stats);
            
            // Formattiere ItemTypes für bessere Darstellung
            Map<String, String> mapping = config.getItemTypeMapping();
            java.util.List<String> mapLines = new java.util.ArrayList<>();
            mapping.forEach((s, d) -> {
                if (s.equals(d)) mapLines.add(s);
                else mapLines.add(s + " -> " + d);
            });
            String mappingStr = String.join(", ", mapLines);
            if (mappingStr.length() > 40) mappingStr = mappingStr.substring(0, 37) + "...";

            // 3. Lese Journal Einträge per ItemType
            if (mapping == null || mapping.isEmpty()) {
                throw new IllegalStateException(
                        "No MIGRATE_ITEMTYPES configured; verification cannot start.");
            }

            java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicIntegerArray> typeResultsAtomic = new java.util.concurrent.ConcurrentHashMap<>();

            long totalWorkload = 0L;

            // PASS 1: Workload vollständig zählen, bevor der ProgressMonitor startet.
            // Wichtig: stats.totalItems darf während der Verifikation nicht wachsen.
            for (Map.Entry<String, String> entry : mapping.entrySet()) {
                String sourceItemType = entry.getKey();
                String jdbcUrl = "jdbc:h2:" + baseDir + File.separator + "journal_" + sourceItemType
                        + ";IFEXISTS=TRUE" + config.getDbUrlAppend();

                try (Connection journalConn = DriverManager.getConnection(jdbcUrl, "sa", "")) {
                    createVerificationTableOld(journalConn);

                    JournalSchema jSchema = detectJournalSchema(journalConn);
                    VerifySchema vSchema = detectVerifySchema(journalConn);

                    String sql = buildWorklistSql(jSchema, vSchema);
                    int countForType = countWorklistRowsWithDest(journalConn, sql, jSchema);

                    totalWorkload += countForType;
                    consoleLogger.info("Verifier PASS1 workload {}: {} row(s)", sourceItemType, countForType);
                } catch (Exception e) {
                    throw new IllegalStateException(
                            "Could not read verifier workload for item type " + sourceItemType, e);
                }
            }

            stats.setTotalItems(totalWorkload);
            consoleLogger.info("Verifier workload total fixed: {}", totalWorkload);

            ProgressMonitor monitor = new ProgressMonitor(
                    stats,
                    5000,
                    config.getSourceSSID(),
                    config.getDestSSID(),
                    mappingStr,
                    "",
                    "VERIFY");

            monitorThread = new Thread(monitor, "verifier-progress-monitor");
            monitorThread.start();
            
            for (Map.Entry<String, String> entry : mapping.entrySet()) {
                String sourceItemType = entry.getKey();
                // [ok, errors, skipped, total, deleted]
                typeResultsAtomic.put(sourceItemType, new java.util.concurrent.atomic.AtomicIntegerArray(5)); 

                // Same baseDir convention as Main/MigrationJournal
                String jdbcUrl = "jdbc:h2:" + baseDir + File.separator + "journal_" + sourceItemType + ";IFEXISTS=TRUE" + config.getDbUrlAppend();
                
                // We don't log to console here anymore to not break the progress bar visual
                // consoleLogger.info("Verifying ItemType: " + sourceItemType + " using journal " + jdbcUrl);

                try (Connection journalConn = DriverManager.getConnection(jdbcUrl, "sa", "")) {
                    // Ensure verification table exists (both schemas supported)
                    // Ensure verification table exists (only legacy/active schema supported by VerificationLogger)
                    // createVerificationTableNew(journalConn); // Removed: VerificationLogger writes to VERIFICATION_LOG
                    createVerificationTableOld(journalConn);

                    JournalSchema jSchema = detectJournalSchema(journalConn);
                    VerifySchema vSchema = detectVerifySchema(journalConn);

                    String sql = buildWorklistSql(jSchema, vSchema);
                    try (PreparedStatement pstmt = journalConn.prepareStatement(sql)) {
                        
                        try {
                            pstmt.setFetchSize(1000);
                        } catch (Exception ignore) {
                           
                        }

                        // Round 9B: Anzahl in dieser Iteration eingereihter Items pro ItemType.
                        int queuedThisItemType = 0;
                        try (ResultSet rs = pstmt.executeQuery()) {
                            while (rs.next()) {
                                String sourceItemId;
                                String destItemId;
                                String sourceChecksum;

                                if (jSchema == JournalSchema.NEW_AUDITLOG) {
                                    sourceItemId = rs.getString("ITEMID");
                                    destItemId = rs.getString("DESTITEMID");
                                    sourceChecksum = rs.getString("CHECKSUM");
                                } else {
                                    sourceItemId = rs.getString("ITEM_ID");
                                    destItemId = rs.getString("DEST_ITEM_ID");
                                    sourceChecksum = rs.getString("CHECKSUM");
                                }

                                if (destItemId == null || destItemId.isEmpty()) {
                                    totalSkipped.incrementAndGet();
                                    continue;
                                }

                                final CMConnectionPool finalPool = pool;
                                final VerificationLogger finalLogger = verificationLogger;
                                final String finalJdbcUrl = jdbcUrl;
                                final String finalSourceType = sourceItemType;
                                final boolean cascadeDelete = config.isCascadeDeleteOnMissing();
                                // Round 9A: System-Property überschreibt config-Wert für reine Diagnose-Läufe.
                                boolean autoRemigrateBase = config.isAutoMarkForRemigration();
                                String autoMarkOverride = System.getProperty(AUTO_REMIGRATE_OVERRIDE_PROP);
                                if (autoMarkOverride != null) {
                                    autoRemigrateBase = Boolean.parseBoolean(autoMarkOverride.trim());
                                }
                                final boolean autoRemigrate = autoRemigrateBase;
                                
                                // Increment total items for stats
                                queuedThisItemType++;

                                // Backpressure happens inside executor when queue is full
                                executor.submit(() -> {
                                    // Custom wrapper to update per-type stats
                                    boolean success = verifyTask(finalPool, finalLogger, sourceItemId, destItemId, finalJdbcUrl, finalSourceType, sourceChecksum, cascadeDelete, autoRemigrate);

                                    // Update per-type results [ok, errors, skipped, total, deleted]
                                    java.util.concurrent.atomic.AtomicIntegerArray res = typeResultsAtomic.get(finalSourceType);
                                    if (res != null) {
                                        if (success) res.incrementAndGet(0); else res.incrementAndGet(1);
                                        res.incrementAndGet(3);
                                    }
                                });
                            }
                        }
                        // Round 9B: Per-ItemType-Logzeile nur im nonOk-Modus (Default-Pfad bleibt unverändert).
                        if (isNonOkWorklistMode()) {
                            if (queuedThisItemType == 0) {
                                consoleLogger.info("No non-OK verification rows found for itemType {}", sourceItemType);
                            } else {
                                consoleLogger.info("Queued {} existing non-OK verification rows for itemType {}",
                                        queuedThisItemType, sourceItemType);
                            }
                        } else {
                            consoleLogger.info("Queued {} verification rows for itemType {}",
                                    queuedThisItemType, sourceItemType);
                        }
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(
                            "Could not queue verifier work for item type " + sourceItemType, e);
                }
            }

            // 4. Bounded two-stage shutdown and wait.
            WorkerTermination.Outcome termination;
            try {
                termination = WorkerTermination.await(
                        executor,
                        config.getWorkerTimeoutSeconds(),
                        shutdownGraceSeconds,
                        ShutdownCoordinator::requestShutdown);
            } catch (InterruptedException e) {
                terminationConfirmed = WorkerTermination.awaitGraceAfterInterrupt(
                        executor, shutdownGraceSeconds, ShutdownCoordinator::requestShutdown);
                throw new RunTerminationException(
                        RunTerminationException.Reason.INTERRUPTED,
                        "Verification interrupted by operator request.",
                        terminationConfirmed,
                        e);
            }

            terminationConfirmed = termination.terminated();
            if (termination.timedOut()) {
                throw new RunTerminationException(
                        RunTerminationException.Reason.TIMEOUT,
                        terminationConfirmed
                                ? "Verification timed out; workers stopped during the grace period."
                                : "Verification timed out; worker termination is not confirmed.",
                        terminationConfirmed,
                        null);
            }

            // Signal shutdown to pool
            // pool.signalShutdown(); 
            
            // Stoppe Monitor
            if (monitorThread != null) {
                monitorThread.interrupt();
                monitorThread.join(1000);
            }

            consoleLogger.info("Verification Finished.");
            
            logger.info("Generating Verification Report via unified pipeline...");

            try {
                ReportDataCollector collector = new ReportDataCollector(stats, config);
                UnifiedReport report = collector.collect();
                ReportDeliveryService.deliver(report, config);
            } catch (Exception e) {
                logger.error("Verification report delivery failed: {}", e.getMessage(), e);
            }

            // Round 9A: optionaler Non-OK-CSV-Export pro ItemType — read-only, keine Datenmutation.
            try {
                exportNonOkCsv(baseDir, mapping, config);
            } catch (Exception e) {
                logger.warn("Round 9A non-OK CSV export failed: {}", e.getMessage());
            }
            
            // --- v1.25: AUDIT PROTOCOL --- (stripped — unified pipeline handles reports)
            // -------------------------------

        } catch (RunTerminationException e) {
            terminationConfirmed = e.isTerminationConfirmed();
            throw e;
        } catch (InterruptedException e) {
            if (executor != null && !executor.isTerminated()) {
                terminationConfirmed = WorkerTermination.awaitGraceAfterInterrupt(
                        executor, shutdownGraceSeconds, ShutdownCoordinator::requestShutdown);
            } else {
                terminationConfirmed = true;
                Thread.currentThread().interrupt();
            }
            throw new RunTerminationException(
                    RunTerminationException.Reason.INTERRUPTED,
                    "Verification interrupted by operator request.",
                    terminationConfirmed,
                    e);
        } catch (Exception e) {
            if (executor != null && !executor.isTerminated()) {
                try {
                    terminationConfirmed = WorkerTermination.awaitGrace(
                            executor, shutdownGraceSeconds, ShutdownCoordinator::requestShutdown);
                } catch (InterruptedException interrupted) {
                    terminationConfirmed = WorkerTermination.awaitGraceAfterInterrupt(
                            executor, shutdownGraceSeconds, ShutdownCoordinator::requestShutdown);
                    throw new RunTerminationException(
                            RunTerminationException.Reason.INTERRUPTED,
                            "Verification interrupted while stopping failed workers.",
                            terminationConfirmed,
                            interrupted);
                }
            }
            if (!terminationConfirmed) {
                throw new RunTerminationException(
                        RunTerminationException.Reason.FAILED,
                        "Verification failed; worker termination is not confirmed.",
                        false,
                        e);
            }
            throw e;
        } finally {
            if (monitorThread != null) {
                monitorThread.interrupt();
            }
            try {
                if (executor != null) executor.shutdown();
            } catch (Exception ignore) {
            }
            if (terminationConfirmed) {
                try {
                    if (verificationLogger != null) verificationLogger.close();
                } catch (Exception ignore) {
                }
                try {
                    if (pool != null) pool.close();
                } catch (Exception ignore) {
                }
            } else {
                logger.warn("Verifier workers may still be active; leaving CM pool and verification logger open.");
            }
        }
    }

    private static JournalSchema detectJournalSchema(Connection conn) {
        if (MigrationJournal.isTablePresent(conn, "AUDIT_LOG")) return JournalSchema.OLD_AUDITLOG;
        if (MigrationJournal.isTablePresent(conn, "AUDITLOG")) return JournalSchema.NEW_AUDITLOG;
        return JournalSchema.OLD_AUDITLOG; // Default to active schema
    }

    private static VerifySchema detectVerifySchema(Connection conn) {
        // Prioritize OLD_VERIFICATION_LOG because VerificationLogger writes to "VERIFICATION_LOG" hardcoded
        if (MigrationJournal.isTablePresent(conn, "VERIFICATION_LOG")) return VerifySchema.OLD_VERIFICATION_LOG;
        if (MigrationJournal.isTablePresent(conn, "VERIFICATIONLOG")) return VerifySchema.NEW_VERIFICATIONLOG;
        return VerifySchema.OLD_VERIFICATION_LOG; // Default to the one VerificationLogger uses
    }

    private static String buildWorklistSql(JournalSchema jSchema, VerifySchema vSchema) {
        // Round 9B: Diagnose-Re-Verifikation existierender Non-OK-Zeilen statt Default-Worklist.
        if (isNonOkWorklistMode()) {
            return buildNonOkWorklistSql(jSchema, vSchema);
        }
        // Default: Items mit AUDIT_LOG.STATUS='SUCCESS' minus bereits OK-verifizierte.
        // LEFT JOIN statt In-Memory-Cache, um Speicher zu schonen.
        if (jSchema == JournalSchema.NEW_AUDITLOG) {
            if (vSchema == VerifySchema.NEW_VERIFICATIONLOG) {
                return "SELECT a.ITEMID, a.DESTITEMID, a.CHECKSUM " +
                       "FROM AUDITLOG a " +
                       "LEFT JOIN VERIFICATIONLOG v ON a.ITEMID = v.ITEMID AND v.STATUS = 'OK' " +
                       "WHERE a.STATUS = 'SUCCESS' AND v.ITEMID IS NULL";
            } else {
                return "SELECT a.ITEMID, a.DESTITEMID, a.CHECKSUM " +
                       "FROM AUDITLOG a " +
                       "LEFT JOIN VERIFICATION_LOG v ON a.ITEMID = v.ITEM_ID AND v.STATUS = 'OK' " +
                       "WHERE a.STATUS = 'SUCCESS' AND v.ITEM_ID IS NULL";
            }
        } else {
            if (vSchema == VerifySchema.NEW_VERIFICATIONLOG) {
                return "SELECT a.ITEM_ID, a.DEST_ITEM_ID, a.CHECKSUM " +
                       "FROM AUDIT_LOG a " +
                       "LEFT JOIN VERIFICATIONLOG v ON a.ITEM_ID = v.ITEMID AND v.STATUS = 'OK' " +
                       "WHERE a.STATUS = 'SUCCESS' AND v.ITEMID IS NULL";
            } else {
                return "SELECT a.ITEM_ID, a.DEST_ITEM_ID, a.CHECKSUM " +
                       "FROM AUDIT_LOG a " +
                       "LEFT JOIN VERIFICATION_LOG v ON a.ITEM_ID = v.ITEM_ID AND v.STATUS = 'OK' " +
                       "WHERE a.STATUS = 'SUCCESS' AND v.ITEM_ID IS NULL";
            }
        }
    }

    private static int countWorklistRowsWithDest(Connection journalConn, String sql, JournalSchema jSchema) throws Exception {
        int count = 0;

        try (PreparedStatement pstmt = journalConn.prepareStatement(sql)) {
            try {
                pstmt.setFetchSize(1000);
            } catch (Exception ignore) {
            }

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String destItemId;

                    if (jSchema == JournalSchema.NEW_AUDITLOG) {
                        destItemId = rs.getString("DESTITEMID");
                    } else {
                        destItemId = rs.getString("DEST_ITEM_ID");
                    }

                    if (destItemId != null && !destItemId.isEmpty()) {
                        count++;
                    }
                }
            }
        }

        return count;
    }

    /**
     * Round 9B: Worklist für Diagnose-Re-Verifikation existierender Non-OK-Verifikationszeilen.
     * Selektiert exakt die Items, die laut VERIFICATION_LOG.STATUS != 'OK' als problematisch gemeldet wurden,
     * mappt sie über AUDIT_LOG zu DEST_ITEM_ID und CHECKSUM. AUDIT_LOG.STATUS wird hier bewusst NICHT gefiltert,
     * damit auch durch autoMarkForRemigration gesetzte FAILED-Zeilen wieder verarbeitbar sind.
     * DEST_ITEM_ID und CHECKSUM müssen vorhanden sein, sonst wäre die Re-Verifikation sinnlos.
     */
    private static String buildNonOkWorklistSql(JournalSchema jSchema, VerifySchema vSchema) {
        if (jSchema == JournalSchema.NEW_AUDITLOG) {
            if (vSchema == VerifySchema.NEW_VERIFICATIONLOG) {
                return "SELECT a.ITEMID, a.DESTITEMID, a.CHECKSUM " +
                       "FROM VERIFICATIONLOG v " +
                       "JOIN AUDITLOG a ON a.ITEMID = v.ITEMID " +
                       "WHERE v.STATUS <> 'OK' " +
                       "AND a.DESTITEMID IS NOT NULL AND a.DESTITEMID <> '' " +
                       "AND a.CHECKSUM   IS NOT NULL AND a.CHECKSUM   <> ''";
            } else {
                return "SELECT a.ITEMID, a.DESTITEMID, a.CHECKSUM " +
                       "FROM VERIFICATION_LOG v " +
                       "JOIN AUDITLOG a ON a.ITEMID = v.ITEM_ID " +
                       "WHERE v.STATUS <> 'OK' " +
                       "AND a.DESTITEMID IS NOT NULL AND a.DESTITEMID <> '' " +
                       "AND a.CHECKSUM   IS NOT NULL AND a.CHECKSUM   <> ''";
            }
        } else {
            if (vSchema == VerifySchema.NEW_VERIFICATIONLOG) {
                return "SELECT a.ITEM_ID, a.DEST_ITEM_ID, a.CHECKSUM " +
                       "FROM VERIFICATIONLOG v " +
                       "JOIN AUDIT_LOG a ON a.ITEM_ID = v.ITEMID " +
                       "WHERE v.STATUS <> 'OK' " +
                       "AND a.DEST_ITEM_ID IS NOT NULL AND a.DEST_ITEM_ID <> '' " +
                       "AND a.CHECKSUM     IS NOT NULL AND a.CHECKSUM     <> ''";
            } else {
                return "SELECT a.ITEM_ID, a.DEST_ITEM_ID, a.CHECKSUM " +
                       "FROM VERIFICATION_LOG v " +
                       "JOIN AUDIT_LOG a ON a.ITEM_ID = v.ITEM_ID " +
                       "WHERE v.STATUS <> 'OK' " +
                       "AND a.DEST_ITEM_ID IS NOT NULL AND a.DEST_ITEM_ID <> '' " +
                       "AND a.CHECKSUM     IS NOT NULL AND a.CHECKSUM     <> ''";
            }
        }
    }

    private static void createVerificationTableNew(Connection conn) throws java.sql.SQLException {
        // Schema used by VerificationLogger.java
        String sql = "CREATE TABLE IF NOT EXISTS VERIFICATIONLOG (" +
                     "ITEMID VARCHAR(255) PRIMARY KEY, " +
                     "STATUS VARCHAR(20), " +
                     "SOURCEHASH VARCHAR(64), " +
                     "DESTHASH VARCHAR(64), " +
                     "VERIFICATIONTIME TIMESTAMP, " +
                     "MESSAGE VARCHAR(4000))";
        try (java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private static void createVerificationTableOld(Connection conn) throws java.sql.SQLException {
        // Legacy schema from older verifier versions
        String sql = "CREATE TABLE IF NOT EXISTS VERIFICATION_LOG (" +
                     "ITEM_ID VARCHAR(255) PRIMARY KEY, " +
                     "STATUS VARCHAR(50), " +
                     "SOURCE_HASH VARCHAR(64), " +
                     "DEST_HASH VARCHAR(64), " +
                     "VERIFIED_AT TIMESTAMP, " +
                     "MESSAGE VARCHAR(1000))";
        try (java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private static void logVerificationResult(VerificationLogger verificationLogger,
                                             String jdbcUrl,
                                             String itemId,
                                             String status,
                                             String sourceHash,
                                             String destHash,
                                             String message) {
        try {
            if (verificationLogger != null) {
                verificationLogger.log(jdbcUrl, itemId, status, sourceHash, destHash, message);
            }
        } catch (Exception e) {
            logger.warn("Failed to write verification log for " + itemId + ": " + e.getMessage());
        }
    }

    private static boolean verifyTask(CMConnectionPool pool,
                                   VerificationLogger verificationLogger,
                                   String sourcePid,
                                   String destPid,
                                   String jdbcUrl,
                                   String itemType,
                                   String storedChecksum,
                                   boolean cascadeDeleteEnabled,
                                   boolean autoMarkForRemigration) {
        CMConnection sourceConn = null;
        CMConnection destConn = null;

        int p = totalProcessed.get();
        if ((p % 1000) == 0) {
            logger.info("MODE destOnly={} storedChecksumLen={} cascadeDelete={}",
                (storedChecksum != null && !storedChecksum.isEmpty()),
                (storedChecksum == null ? -1 : storedChecksum.length()),
                cascadeDeleteEnabled);
        }

        try {
            if (storedChecksum != null && !storedChecksum.isEmpty()) {
                // Dest-only: no source connection, and size-check is skipped in verifyItem when checksum is present.
                destConn = pool.borrowDest();
                VerificationResult result = verifyItem(null, sourcePid, destConn.getDatastore(), destPid, storedChecksum);

                if (result.match) {
                    logger.debug("OK: " + sourcePid + " (" + result.sourceHash + ") -> " + destPid + " (" + result.destHash + ")");
                    logVerificationResult(verificationLogger, jdbcUrl, sourcePid, "OK", result.sourceHash, result.destHash, "Verified Match");
                    totalVerified.incrementAndGet();
                    return true;
                } else {
                    String msg = "MISMATCH: " + sourcePid + " (" + result.sourceHash + ") -> " + destPid + " (" + result.destHash + ")";
                    logger.error(msg);
                    logVerificationResult(verificationLogger, jdbcUrl, sourcePid, "MISMATCH", result.sourceHash, result.destHash, msg);
                    
                    // v2.1.31: Auto-mark for re-migration
                    if (autoMarkForRemigration) {
                        markForRemigration(jdbcUrl, sourcePid, "Checksum mismatch during verification");
                    }
                    
                    totalErrors.incrementAndGet();
                    return false;
                }
            } else {
                // Fallback: Full check (requires source connection)
                sourceConn = pool.borrowSource();
                destConn = pool.borrowDest();
                
                // v1.25: Check if source still exists BEFORE attempting hash compare
                SourceLookupStatus sourceStatus = checkSourceStatus(sourceConn.getDatastore(), sourcePid);

                switch (sourceStatus) {
                    case EXISTS:
                        break;

                    case NOT_FOUND:
                        totalSourceDeleted.incrementAndGet();
                        logger.warn("SOURCE_DELETED: {} no longer exists in source system", sourcePid);

                        if (shouldCascadeDelete(sourceStatus, cascadeDeleteEnabled)) {
                            // Cascade delete: remove from destination as well
                            boolean deleteSuccess = cascadeDeleteDest(destConn.getDatastore(), destPid);
                            if (deleteSuccess) {
                                logVerificationResult(verificationLogger, jdbcUrl, sourcePid, "CASCADE_DELETED", null, null,
                                    "Source deleted, destination item " + destPid + " also deleted");
                                totalCascadeDeleted.incrementAndGet();
                            } else {
                                logVerificationResult(verificationLogger, jdbcUrl, sourcePid, "CASCADE_DELETE_FAILED", null, null,
                                    "Source deleted, but failed to delete destination item " + destPid);
                                totalErrors.incrementAndGet();
                            }
                        } else {
                            // Just mark as orphaned (source deleted, dest still exists)
                            logVerificationResult(verificationLogger, jdbcUrl, sourcePid, "ORPHANED", null, null,
                                "Source deleted, destination item " + destPid + " still exists (cascade delete disabled)");
                        }
                        return false; // Not a successful verification

                    case ERROR:
                    default:
                        String lookupMessage = "Source lookup failed for " + sourcePid
                                + "; cascade delete refused (status=" + sourceStatus + ")";
                        logger.error(lookupMessage);
                        logVerificationResult(verificationLogger, jdbcUrl, sourcePid, "ERROR", null, null, lookupMessage);
                        totalErrors.incrementAndGet();
                        return false;
                }

                // Source exists - proceed with normal hash verification
                VerificationResult result = verifyItem(sourceConn.getDatastore(), sourcePid, destConn.getDatastore(), destPid, null);

                if (result.match) {
                    logger.debug("OK: " + sourcePid + " (" + result.sourceHash + ") -> " + destPid + " (" + result.destHash + ")");
                    logVerificationResult(verificationLogger, jdbcUrl, sourcePid, "OK", result.sourceHash, result.destHash, "Verified Match");
                    totalVerified.incrementAndGet();
                    return true;
                } else {
                    String msg = "MISMATCH: " + sourcePid + " (" + result.sourceHash + ") -> " + destPid + " (" + result.destHash + ")";
                    logger.error(msg);
                    logVerificationResult(verificationLogger, jdbcUrl, sourcePid, "MISMATCH", result.sourceHash, result.destHash, msg);
                    
                    // v2.1.31: Auto-mark for re-migration
                    if (autoMarkForRemigration) {
                        markForRemigration(jdbcUrl, sourcePid, "Checksum mismatch during verification (full-check)");
                    }
                    
                    totalErrors.incrementAndGet();
                    return false;
                }
            }

        } catch (Exception e) {
            logger.error("Error verifying " + sourcePid, e);
            logVerificationResult(verificationLogger, jdbcUrl, sourcePid, "ERROR", null, null, e.getMessage());
            totalErrors.incrementAndGet();
            return false;
        } finally {
            totalProcessed.incrementAndGet();
            if (pool != null) {
                if (sourceConn != null) pool.returnSource(sourceConn);
                if (destConn != null) pool.returnDest(destConn);
            }
        }
    }

    private static final class VerificationResult {
        final boolean match;
        final String sourceHash;
        final String destHash;
    
        VerificationResult(boolean match, String sourceHash, String destHash) {
            this.match = match;
            this.sourceHash = sourceHash;
            this.destHash = destHash;
        }
    }

    private static VerificationResult verifyItem(DKDatastoreICM sourceDs,
                                                 String sourcePid,
                                                 DKDatastoreICM destDs,
                                                 String destPid,
                                                 String storedChecksum) {
        try {
            String sourceHash;

            if (storedChecksum == null || storedChecksum.isEmpty()) {
                long sourceSize = -1;
                long destSize = -1;

                if (sourceDs != null) sourceSize = getSize(sourceDs, sourcePid);
                destSize = getSize(destDs, destPid);

                boolean sizeMismatch = false;
                if (sourceSize != -1 && destSize != -1 && sourceSize != destSize) {
                    sizeMismatch = true;
                    logger.warn("SIZE MISMATCH: {} ({}) != {} ({}) (continuing with hash compare)",
                            sourcePid, sourceSize, destPid, destSize);
                }
            }

            // 1. Lese Source Hash
            if (storedChecksum != null && !storedChecksum.isEmpty()) {
                sourceHash = storedChecksum;
            } else {
                if (sourceDs == null) return new VerificationResult(false, "no-conn", "unknown");
                sourceHash = getHash(sourceDs, sourcePid, "source");
            }

            if (sourceHash == null) return new VerificationResult(false, "null", "unknown");

            // 2. Lese Dest Hash
            String destHash = getHash(destDs, destPid, "dest");
            if (destHash == null) return new VerificationResult(false, sourceHash, "null");

            // 3. Vergleiche
            return new VerificationResult(sourceHash.equals(destHash), sourceHash, destHash);

        } catch (Exception e) {
            logger.error("Error verifying item " + sourcePid, e);
            return new VerificationResult(false, "error", "error");
        }
    }

    @SuppressWarnings("deprecation")
    private static String getHash(DKDatastoreICM ds, String pid, String label) throws Exception {
        java.security.MessageDigest digest = SHA256_DIGEST.get();
        digest.reset();
        boolean hasParts = false;
        DKDDO item = null;
        DKRetrieveOptionsICM dkOpt = null;
    
        // --- TIMING PATCH ---
        long retrieveMs = -1;
        long streamMsTotal = 0;
        long totalBytesRead = 0;
        // --------------------
    
        try {
            item = ds.createDDOFromPID(pid);
            dkOpt = DKRetrieveOptionsICM.createInstance(ds);
            dkOpt.partsList(true);
            dkOpt.partsAttributes(true);
    
            // PERF FIX: do NOT retrieve resource content in metadata retrieve
            dkOpt.resourceContent(false);
    
            // --- TIMING PATCH: measure metadata retrieve time ---
            long tRetrieve0 = System.nanoTime();
            item.retrieve(dkOpt.dkNVPair());
            retrieveMs = (System.nanoTime() - tRetrieve0) / 1_000_000;
            // ---------------------------------------------------
    
            // short partsId = item.dataId(com.ibm.mm.sdk.common.DKConstant.DKCMNAMESPACEATTR, com.ibm.mm.sdk.common.DKConstant.DKCMDKPARTS);
            short partsId = item.dataId("ATTR", "DKParts");
            if (partsId == 0) return null; // No parts
    
            DKParts parts = (DKParts) item.getData(partsId);
            if (parts == null || parts.cardinality() == 0) return null;
    
            class PartWrapper {
                DKLobICM part;
                int index;
                String name;
                long size;
                String mime;
    
                PartWrapper(DKLobICM p, int i) {
                    part = p;
                    index = i;
                    try {
                        name = p.getOrgFileName();
                    } catch (Exception e) {
                        name = "";
                    }
                    name = (name != null ? name.trim() : "");
                    try {
                        size = p.getSize();
                    } catch (Exception e) {
                        size = -1;
                    }
                    try {
                        mime = p.getMimeType();
                    } catch (Exception e) {
                        mime = "";
                    }
                    mime = (mime != null ? mime.trim() : "");
                }
            }
    
            java.util.List<PartWrapper> sortedParts = new java.util.ArrayList<>();
            com.ibm.mm.sdk.common.dkIterator iter = parts.createIterator();
            int idx = 0;
            while (iter.more()) {
                sortedParts.add(new PartWrapper((DKLobICM) iter.next(), idx));
                idx++;
            }
    
            // PERF FIX: sort only if >1 part
            // Round 9A: Sort-Mode wählbar.
            // - "migrator": wie ItemMigrator.copyParts (case-insensitive name, dann insertion-index).
            // - "verifier": alter 4-Schlüssel-Sort.
            if (sortedParts.size() > 1) {
                if ("migrator".equals(VERIFY_SORT_MODE)) {
                    sortedParts.sort((pw1, pw2) -> {
                        int cmp = String.CASE_INSENSITIVE_ORDER.compare(pw1.name, pw2.name);
                        if (cmp != 0) return cmp;
                        return Integer.compare(pw1.index, pw2.index);
                    });
                } else {
                    // Total Order Sort: Filename - Size - MimeType - OriginalIndex
                    sortedParts.sort((pw1, pw2) -> {
                        int cmp = String.CASE_INSENSITIVE_ORDER.compare(pw1.name, pw2.name);
                        if (cmp != 0) return cmp;
                        cmp = pw1.name.compareTo(pw2.name);
                        if (cmp != 0) return cmp;

                        int sizeCmp = Long.compare(pw1.size, pw2.size);
                        if (sizeCmp != 0) return sizeCmp;

                        int mimeCmp = String.CASE_INSENSITIVE_ORDER.compare(pw1.mime, pw2.mime);
                        if (mimeCmp != 0) return mimeCmp;
                        mimeCmp = pw1.mime.compareTo(pw2.mime);
                        if (mimeCmp != 0) return mimeCmp;

                        return Integer.compare(pw1.index, pw2.index);
                    });
                }
            }
    
            for (PartWrapper wrapper : sortedParts) {
                hasParts = true;
                DKLobICM part = wrapper.part;
    
                // Stream directly to digest (No Temp File!)
                // --- TIMING PATCH: time stream open+read per part, sum over all parts ---
                long tStream0 = System.nanoTime();
                // Round 13A: SDK on this build host only exposes
                // getInputStream(DKNVPair[], int, int). length=-1 is the SDK
                // sentinel for "stream until EOF", not a 2 GiB-bounded byte
                // count, so int args are safe even for >2 GiB content.
                // Capability probe at startup will catch SDKs that diverge.
                try (java.io.InputStream is = part.getInputStream(new com.ibm.mm.sdk.common.DKNVPair[0], 0, -1)) {
                    byte[] buffer = new byte[VERIFY_BUFFER_SIZE];
                    int n;
                    while ((n = is.read(buffer)) != -1) {
                        digest.update(buffer, 0, n);
                        totalBytesRead += n;
                    }
                }
                streamMsTotal += (System.nanoTime() - tStream0) / 1_000_000;
                // ---------------------------------------------------------------------
            }
    
            if (!hasParts) return null;
    
            // --- TIMING PATCH: sample log every 500 processed items ---
            int sampleNo = hashSampleCounter.incrementAndGet();
            if ((sampleNo % 500) == 0) {
                logger.info("PERF getHash sample={} label={} retrieveMs={} streamMs={} bytes={} parts={}",
                        sampleNo, label, retrieveMs, streamMsTotal, totalBytesRead, sortedParts.size());
            }
            if (VERIFY_SLOW_HASH_WARN_MS > 0 && streamMsTotal > VERIFY_SLOW_HASH_WARN_MS) {
                    logger.warn("Slow verify hash: label={} pid={} streamMs={} bytes={} parts={}",
                        label, pid, streamMsTotal, totalBytesRead, sortedParts.size());
            }
            // ---------------------------------------------------------
    
            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
    
        } catch (Exception e) {
            logger.error("Failed to calculate SHA-256 for {}", pid, e);
            throw e;
        } finally {
            // Best-effort help GC native cleanup in older SDKs
            item = null;
            dkOpt = null;
        }
    }

    private static long getSize(DKDatastoreICM ds, String pid) {
        DKDDO item = null;
        DKRetrieveOptionsICM dkOpt = null;
        try {
            item = ds.createDDOFromPID(pid);
            dkOpt = DKRetrieveOptionsICM.createInstance(ds);

            dkOpt.partsList(true);
            dkOpt.partsAttributes(true);
            dkOpt.resourceContent(false); // METADATA ONLY
            item.retrieve(dkOpt.dkNVPair());

            short partsId = item.dataId(com.ibm.mm.sdk.common.DKConstant.DK_CM_NAMESPACE_ATTR, com.ibm.mm.sdk.common.DKConstant.DK_CM_DKPARTS);
            if (partsId == 0) return 0;

            DKParts parts = (DKParts) item.getData(partsId);
            if (parts == null) return 0;

            long totalSize = 0;
            com.ibm.mm.sdk.common.dkIterator iter = parts.createIterator();
            while (iter.more()) {
                DKLobICM part = (DKLobICM) iter.next();
                totalSize += part.getSize();
            }
            return totalSize;
        } catch (Exception e) {
            logger.warn("Failed to get size for " + pid + ": " + e.getMessage());
            return -1;
        } finally {
            item = null;
            dkOpt = null;
        }
    }

    private static String calculateSha256(File file) {
        // Helper kept for compatibility or unused - Logic moved inside getHash for multi-part
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[262144];
            int n;
            while ((n = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, n);
            }
            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            logger.error("Failed to calculate SHA-256", e);
            return null;
        }
    }

    // ========================================================================
    // v1.25: Cascade Delete Support
    // ========================================================================

    /**
     * Checks whether the source item can be retrieved.
     * Only a classifier-confirmed missing object returns NOT_FOUND; all uncertain
     * or technical failures return ERROR and therefore fail closed.
     */
    private static SourceLookupStatus checkSourceStatus(DKDatastoreICM sourceDs, String sourcePid) {
        if (sourceDs == null || sourcePid == null || sourcePid.isEmpty()) {
            logger.error("Source lookup cannot run with missing datastore or PID; cascade delete refused");
            return SourceLookupStatus.ERROR;
        }

        DKDDO item = null;
        DKRetrieveOptionsICM dkOpt = null;
        try {
            item = sourceDs.createDDOFromPID(sourcePid);
            // Minimal retrieve - just check if object metadata is accessible
            dkOpt = DKRetrieveOptionsICM.createInstance(sourceDs);
            dkOpt.baseAttributes(true);
            dkOpt.partsList(false);
            dkOpt.resourceContent(false);
            item.retrieve(dkOpt.dkNVPair());
            return SourceLookupStatus.EXISTS;
        } catch (Exception e) {
            SourceLookupStatus status = SourceLookupClassifier.fromFailure(e, sourcePid);
            if (status == SourceLookupStatus.NOT_FOUND) {
                logger.warn("Source item {} confirmed not found", sourcePid, e);
            } else {
                logger.error("Source item {} lookup failed; cascade delete refused", sourcePid, e);
            }
            return status;
        } finally {
            item = null;
            dkOpt = null;
        }
    }

    static boolean shouldCascadeDelete(SourceLookupStatus sourceStatus, boolean cascadeDeleteEnabled) {
        return cascadeDeleteEnabled && sourceStatus == SourceLookupStatus.NOT_FOUND;
    }

    /**
     * Deletes the destination item (cascade delete).
     * Returns true on success, false on failure.
     */
    private static boolean cascadeDeleteDest(DKDatastoreICM destDs, String destPid) {
        if (destDs == null || destPid == null || destPid.isEmpty()) return false;
        
        DKDDO destItem = null;
        try {
            destItem = destDs.createDDOFromPID(destPid);
            destItem.del();
            logger.info("CASCADE DELETE: Deleted destination item {}", destPid);
            return true;
        } catch (Exception e) {
            logger.error("CASCADE DELETE FAILED for {}: {}", destPid, e.getMessage());
            return false;
        } finally {
            destItem = null;
        }
    }

    private static void generateReport(Map<String, String> mapping, String baseDir) {
        // Keep existing report logic minimal here; if you want, I can align the whole report section to the NEW schema
        // and add per-itemtype join counts without scanning large tables.
        try {
            for (Map.Entry<String, String> entry : mapping.entrySet()) {
                String sourceItemType = entry.getKey();
                String jdbcUrl = "jdbc:h2:" + baseDir + File.separator + "journal_" + sourceItemType + ";IFEXISTS=TRUE";

                int typeVerified = 0;
                int typeErrors = 0;

                try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "")) {
                    // Prefer new schema
                    try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM VERIFICATIONLOG WHERE STATUS='OK'")) {
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) typeVerified = rs.getInt(1);
                    } catch (Exception e) {
                        // fallback old schema
                        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM VERIFICATION_LOG WHERE STATUS='OK'")) {
                            ResultSet rs = ps.executeQuery();
                            if (rs.next()) typeVerified = rs.getInt(1);
                        }
                    }

                    try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM VERIFICATIONLOG WHERE STATUS<>'OK'")) {
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) typeErrors = rs.getInt(1);
                    } catch (Exception e) {
                        // fallback old schema
                        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM VERIFICATION_LOG WHERE STATUS<>'OK'")) {
                            ResultSet rs = ps.executeQuery();
                            if (rs.next()) typeErrors = rs.getInt(1);
                        }
                    }
                }

                consoleLogger.info("REPORT " + sourceItemType + ": ok=" + typeVerified + " errors=" + typeErrors);
            }
        } catch (Exception e) {
            logger.warn("Report generation failed: " + e.getMessage(), e);
        }
    }


    private static void generateHtmlReport(String baseDir, MigrationConfig config, Map<String, String> mapping) {
        consoleLogger.info("Generating Detailed HTML Report...");
        File reportFile = new File("verification_report.html");
        
        try (java.io.PrintWriter writer = new java.io.PrintWriter(reportFile)) {
            writer.println("<!DOCTYPE html>");
            writer.println("<html lang='en'>");
            writer.println("<head>");
            writer.println("<meta charset='UTF-8'>");
            writer.println("<title>Migration Verification Report</title>");
            writer.println("<style>");
            writer.println("body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background-color: #f4f7f6; color: #333; margin: 0; padding: 20px; }");
            writer.println("h1 { color: #2c3e50; text-align: center; margin-bottom: 20px; }");
            writer.println(".container { max-width: 1400px; margin: 0 auto; background: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }");
            writer.println(".section-title { border-bottom: 2px solid #eee; padding-bottom: 10px; margin-top: 40px; color: #34495e; font-size: 1.5em; display: flex; justify-content: space-between; align-items: center; }");
            
            // Stats Header
            writer.println(".stats { display: flex; justify-content: space-between; margin-bottom: 30px; gap: 20px; }");
            writer.println(".stat-box { flex: 1; text-align: center; padding: 25px; border-radius: 8px; color: white; box-shadow: 0 4px 6px rgba(0,0,0,0.1); }");
            writer.println(".bg-green { background: linear-gradient(135deg, #27ae60, #2ecc71); }");
            writer.println(".bg-red { background: linear-gradient(135deg, #c0392b, #e74c3c); }");
            writer.println(".bg-blue { background: linear-gradient(135deg, #2980b9, #3498db); }");
            writer.println(".bg-gray { background: linear-gradient(135deg, #7f8c8d, #95a5a6); }");
            writer.println(".stat-value { font-size: 3em; font-weight: bold; margin-bottom: 5px; }");
            writer.println(".stat-label { font-size: 1.1em; opacity: 0.9; }");

            // Tables
            writer.println("table { width: 100%; border-collapse: collapse; margin-top: 20px; font-size: 0.95em; }");
            writer.println("th, td { padding: 15px; text-align: left; border-bottom: 1px solid #ddd; }");
            writer.println("th { background-color: #f8f9fa; font-weight: 600; color: #555; }");
            writer.println("tr:hover { background-color: #f1f2f6; }");
            
            // Progress Bar
            writer.println(".progress-wrapper { width: 100%; height: 20px; background-color: #ecf0f1; border-radius: 8px; overflow: hidden; position: relative; }");
            writer.println(".progress-bar { height: 100%; background-color: #27ae60; text-align: center; color: white; line-height: 20px; font-size: 0.8em; transition: width 0.5s ease; }");
            writer.println(".progress-bar.warning { background-color: #f39c12; }");
            writer.println(".progress-bar.danger { background-color: #e74c3c; }");

            // Badges & Buttons
            writer.println(".badge { padding: 5px 10px; border-radius: 15px; font-size: 0.85em; font-weight: bold; display: inline-block; }");
            writer.println(".badge-ok { background-color: #d4edda; color: #155724; }");
            writer.println(".badge-error { background-color: #f8d7da; color: #721c24; }");
            writer.println(".btn { background-color: #3498db; color: white; padding: 10px 20px; text-decoration: none; border-radius: 5px; font-weight: bold; cursor: pointer; border: none; font-size: 0.9em; transition: background 0.2s; }");
            writer.println(".btn:hover { background-color: #2980b9; }");
            writer.println("select { padding: 8px; border-radius: 4px; border: 1px solid #ddd; font-size: 0.9em; min-width: 200px; }");

            writer.println("</style>");
            writer.println("<script>");
            writer.println("function exportToCsv() {");
            writer.println("  let csv = [];");
            writer.println("  let rows = document.querySelectorAll('table#errorTable tr');");
            writer.println("  for (let i = 0; i < rows.length; i++) {");
            writer.println("    let row = [], cols = rows[i].querySelectorAll('td, th');");
            writer.println("    for (let j = 0; j < cols.length; j++) row.push('\"' + cols[j].innerText + '\"');");
            writer.println("    csv.push(row.join(','));");
            writer.println("  }");
            writer.println("  let csvFile = new Blob([csv.join('\\n')], {type: 'text/csv'});");
            writer.println("  let downloadLink = document.createElement('a');");
            writer.println("  downloadLink.download = 'verification_errors.csv';");
            writer.println("  downloadLink.href = window.URL.createObjectURL(csvFile);");
            writer.println("  downloadLink.style.display = 'none';");
            writer.println("  document.body.appendChild(downloadLink);");
            writer.println("  downloadLink.click();");
            writer.println("}");
            writer.println("function filterErrors() {");
            writer.println("  let input = document.getElementById('typeFilter');");
            writer.println("  let filter = input.value.toUpperCase();");
            writer.println("  let table = document.getElementById('errorTable');");
            writer.println("  let tr = table.getElementsByTagName('tr');");
            writer.println("  for (let i = 1; i < tr.length; i++) {");
            writer.println("    let td = tr[i].getElementsByTagName('td')[0];");
            writer.println("    if (td) {");
            writer.println("      let txtValue = td.textContent || td.innerText;");
            writer.println("      if (filter === 'ALL' || txtValue.toUpperCase().indexOf(filter) > -1) { tr[i].style.display = ''; }");
            writer.println("      else { tr[i].style.display = 'none'; }");
            writer.println("    }");
            writer.println("  }");
            writer.println("}");
            writer.println("</script>");
            writer.println("</head>");
            writer.println("<body>");
            writer.println("<div class='container'>");
            
            writer.println("<h1>Migration Verification Report</h1>");
            writer.println("<p style='text-align:center; color:#7f8c8d; margin-bottom: 40px;'>Generated at: " + new java.util.Date() + "</p>");

            // --- DATA GATHERING ---
            int totalChecked = 0;
            int totalOk = 0;
            int totalFail = 0;
            java.util.List<String[]> breakdownList = new java.util.ArrayList<>();
            java.util.List<String[]> errorList = new java.util.ArrayList<>();

            for (String sourceItemType : mapping.keySet()) {
                String dbPath = baseDir + File.separator + "journal_" + sourceItemType;
                String jdbcUrl = "jdbc:h2:" + dbPath + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r";
                
                int typeVerified = 0;
                int typeErrors = 0;
                
                try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "")) {
                    // Count OK
                    try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM VERIFICATION_LOG WHERE STATUS='OK'")) {
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) typeVerified = rs.getInt(1);
                    }
                    // Count Errors
                    try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM VERIFICATION_LOG WHERE STATUS<>'OK'")) {
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) typeErrors = rs.getInt(1);
                    }
                } catch (Exception e) {
                    consoleLogger.error("Error stats for " + sourceItemType, e);
                }
                
                int typeTotal = typeVerified + typeErrors;
                double successRate = (typeTotal == 0) ? 0 : ((double) typeVerified / typeTotal) * 100;
                
                breakdownList.add(new String[]{
                    sourceItemType, 
                    String.valueOf(typeTotal), 
                    String.valueOf(typeVerified), 
                    String.valueOf(typeErrors), 
                    String.format("%.1f", successRate)
                });
                
                totalChecked += typeTotal;
                totalOk += typeVerified;
                totalFail += typeErrors;
            }

            // --- SECTION 1: GLOBAL SUMMARY ---
            writer.println("<div class='stats'>");
            writer.println("<div class='stat-box bg-blue'><div class='stat-value'>" + totalChecked + "</div><div class='stat-label'>Total Verified</div></div>");
            writer.println("<div class='stat-box bg-green'><div class='stat-value'>" + totalOk + "</div><div class='stat-label'>Consistent (OK)</div></div>");
            writer.println("<div class='stat-box bg-red'><div class='stat-value'>" + totalFail + "</div><div class='stat-label'>Mismatches</div></div>");
            
            double globalRate = (totalChecked == 0) ? 0 : ((double) totalOk / totalChecked) * 100;
            String globalRateStr = String.format("%.1f%%", globalRate);
            writer.println("<div class='stat-box bg-gray'><div class='stat-value'>" + globalRateStr + "</div><div class='stat-label'>Health Score</div></div>");
            writer.println("</div>");

            // --- SECTION 2: ITEM TYPE BREAKDOWN ---
            writer.println("<div class='section-title'>");
            writer.println("<span>ItemType Breakdown</span>");
            writer.println("</div>");
            writer.println("<table>");
            writer.println("<thead><tr><th style='width:20%'>ItemType</th><th style='width:10%'>Total</th><th style='width:10%'>OK</th><th style='width:10%'>Errors</th><th style='width:50%'>Success Rate</th></tr></thead>");
            writer.println("<tbody>");
            
            for (String[] row : breakdownList) {
                writer.println("<tr>");
                writer.println("<td><b>" + row[0] + "</b></td>");
                writer.println("<td>" + row[1] + "</td>");
                writer.println("<td>" + row[2] + "</td>");
                writer.println("<td>" + row[3] + "</td>");
                
                double rate = Double.parseDouble(row[4].replace(",", "."));
                String barClass = "progress-bar";
                if (rate < 95) barClass += " warning";
                if (rate < 80) barClass += " danger";
                
                writer.println("<td>");
                writer.println("<div class='progress-wrapper'><div class='" + barClass + "' style='width:" + rate + "%'>" + row[4] + "%</div></div>");
                writer.println("</td>");
                writer.println("</tr>");
            }
            writer.println("</tbody></table>");

            // --- SECTION 3: ERROR DETAILS ---
            writer.println("<div class='section-title'>");
            writer.println("<span>Error Details (" + errorList.size() + " shown)</span>");
            writer.println("<div>");
            writer.println("<select id='typeFilter' onchange='filterErrors()'><option value='ALL'>All ItemTypes</option>");
            for (String[] row : breakdownList) {
                if (!row[3].equals("0")) { // Only add types with errors
                    writer.println("<option value='" + row[0] + "'>" + row[0] + "</option>");
                }
            }
            writer.println("</select>");
            writer.println("<button class='btn' onclick='exportToCsv()' style='margin-left:10px;'>Export CSV</button>");
            writer.println("</div></div>");

            if (!errorList.isEmpty()) {
                writer.println("<table id='errorTable'>");
                writer.println("<thead><tr><th>ItemType</th><th>Item ID</th><th>Status</th><th>Message</th></tr></thead>");
                writer.println("<tbody>");
                for (String[] err : errorList) {
                    writer.println("<tr>");
                    writer.println("<td>" + err[0] + "</td>");
                    writer.println("<td>" + err[1] + "</td>");
                    writer.println("<td><span class='badge badge-error'>" + err[2] + "</span></td>");
                    writer.println("<td>" + err[3] + "</td>");
                    writer.println("</tr>");
                }
                writer.println("</tbody>");
                writer.println("</table>");
            } else {
                writer.println("<div style='text-align:center; padding: 40px; margin-top:20px; background:#f8f9fa; border-radius:8px;'>");
                writer.println("<h3 style='color:#27ae60;'>No Errors Found</h3>");
                writer.println("<p>All verification checks passed successfully.</p>");
                writer.println("</div>");
            }

            writer.println("</div>"); // container
            writer.println("</body></html>");
            
            consoleLogger.info("Detailed HTML report generated: " + reportFile.getAbsolutePath());
            
        } catch (Exception e) {
            consoleLogger.error("Failed to generate HTML report", e);
        }
    }

    // ========================================================================
    // v2.1.31: Auto-Remigration Support
    // ========================================================================

    /**
     * Marks an item for re-migration by setting its AUDIT_LOG status to 'FAILED'.
     * This allows the Producer to pick it up again on the next migration run.
     * 
     * Replaces the need for the separate remigrate.sh script.
     * 
     * @param jdbcUrl The JDBC URL for the H2 journal database
     * @param itemId The item ID (PID) to mark for re-migration
     * @param reason The reason for marking (e.g., "Checksum mismatch")
     */
    /**
     * Round 9A: Schreibt VERIFICATION_LOG-Zeilen mit STATUS != 'OK' nach reports/verification_non_ok_<itemtype>.csv.
     * Read-only Pfad — kein UPDATE/DELETE auf der DB. Best-effort: per ItemType, Fehler werden geloggt und übersprungen.
     */
    private static void exportNonOkCsv(String baseDir, Map<String, String> mapping, MigrationConfig config) {
        if (mapping == null || mapping.isEmpty()) return;
        java.io.File reportsDir = new java.io.File("reports");
        if (!reportsDir.exists()) reportsDir.mkdirs();

        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String sourceItemType = entry.getKey();
            String jdbcUrl = "jdbc:h2:" + baseDir + java.io.File.separator + "journal_" + sourceItemType
                    + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r" + safeDbUrlAppend(config);
            java.io.File csv = new java.io.File(reportsDir, "verification_non_ok_" + sourceItemType + ".csv");

            int written = 0;
            try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "");
                 java.io.PrintWriter w = new java.io.PrintWriter(
                         new java.io.OutputStreamWriter(new java.io.FileOutputStream(csv), java.nio.charset.StandardCharsets.UTF_8))) {

                VerifySchema vSchema = detectVerifySchema(conn);
                JournalSchema jSchema = detectJournalSchema(conn);

                // Spaltennamen je Schema auflösen
                String vItem, vStatus, vSrc, vDst, vMsg, vTab;
                if (vSchema == VerifySchema.NEW_VERIFICATIONLOG) {
                    vTab = "VERIFICATIONLOG"; vItem = "ITEMID"; vStatus = "STATUS";
                    vSrc = "SOURCEHASH"; vDst = "DESTHASH"; vMsg = "MESSAGE";
                } else {
                    vTab = "VERIFICATION_LOG"; vItem = "ITEM_ID"; vStatus = "STATUS";
                    vSrc = "SOURCE_HASH"; vDst = "DEST_HASH"; vMsg = "MESSAGE";
                }
                String aTab, aItem, aDest;
                if (jSchema == JournalSchema.NEW_AUDITLOG) {
                    aTab = "AUDITLOG"; aItem = "ITEMID"; aDest = "DESTITEMID";
                } else {
                    aTab = "AUDIT_LOG"; aItem = "ITEM_ID"; aDest = "DEST_ITEM_ID";
                }

                String sql = "SELECT v." + vItem + ", a." + aDest + ", v." + vStatus + ", v." + vSrc + ", v." + vDst + ", v." + vMsg
                           + " FROM " + vTab + " v LEFT JOIN " + aTab + " a ON v." + vItem + " = a." + aItem
                           + " WHERE v." + vStatus + " <> 'OK' ORDER BY v." + vStatus + ", v." + vItem;

                w.println("ITEM_ID,DEST_ITEM_ID,STATUS,SOURCE_HASH,DEST_HASH,MESSAGE");
                try (PreparedStatement ps = conn.prepareStatement(sql);
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        w.println(csvField(rs.getString(1)) + "," + csvField(rs.getString(2)) + ","
                                + csvField(rs.getString(3)) + "," + csvField(rs.getString(4)) + ","
                                + csvField(rs.getString(5)) + "," + csvField(rs.getString(6)));
                        written++;
                    }
                }
                logger.info("Round 9A: wrote {} non-OK rows to {}", written, csv.getAbsolutePath());
            } catch (Exception e) {
                logger.warn("Round 9A non-OK CSV export skipped for {}: {}", sourceItemType, e.getMessage());
            }
        }
    }

    private static String safeDbUrlAppend(MigrationConfig config) {
        try {
            String s = config.getDbUrlAppend();
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String csvField(String s) {
        if (s == null) return "";
        boolean needsQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        String escaped = s.replace("\"", "\"\"");
        return needsQuote ? "\"" + escaped + "\"" : escaped;
    }

    private static void markForRemigration(String jdbcUrl, String itemId, String reason) {
        // Use the same JDBC URL but without IFEXISTS=TRUE to ensure we can write
        String writeUrl = jdbcUrl.replace(";IFEXISTS=TRUE", "");
        
        try (Connection conn = DriverManager.getConnection(writeUrl, "sa", "")) {
            // Detect schema (AUDIT_LOG vs AUDITLOG)
            JournalSchema schema = detectJournalSchema(conn);
            
            String sql;
            if (schema == JournalSchema.NEW_AUDITLOG) {
                sql = "UPDATE AUDITLOG SET STATUS = 'FAILED', MESSAGE = ? WHERE ITEMID = ?";
            } else {
                sql = "UPDATE AUDIT_LOG SET STATUS = 'FAILED', MESSAGE = ? WHERE ITEM_ID = ?";
            }
            
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                String msg = "Marked for re-migration: " + reason;
                if (msg.length() > 4000) msg = msg.substring(0, 3997) + "...";
                
                pstmt.setString(1, msg);
                pstmt.setString(2, itemId);
                
                int updated = pstmt.executeUpdate();
                if (updated > 0) {
                    logger.info("AUTO_REMIGRATE: Marked {} for re-migration ({})", itemId, reason);
                } else {
                    logger.warn("AUTO_REMIGRATE: Failed to mark {} - item not found in AUDIT_LOG", itemId);
                }
            }
        } catch (Exception e) {
            logger.error("AUTO_REMIGRATE: Error marking {} for re-migration: {}", itemId, e.getMessage());
        }
    }
}
