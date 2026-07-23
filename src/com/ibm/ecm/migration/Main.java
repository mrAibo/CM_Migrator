/*
 * Projekt: CM Migrator 2.2.1.
 * @Author: Aleksej Voronin, Sven Lindt
 * @Date:   26.01.2026
 */
package com.ibm.ecm.migration;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Main {
    private static final Logger logger = LogManager.getLogger(Main.class);

    public static void main(String[] args) {
        System.exit(runCli(args));
    }

    static int runCli(String[] args) {
        // Start-Banner in der Konsole ausgeben
        System.out.println(ConsoleUI.banner("2.2.1"));

        logger.info("Starting IBM CM Migrator V8.7...");

        String configPath = "conf/migration.properties";
        if (args.length > 0) {
            configPath = args[0];
        }

        ShutdownCoordinator.reset();
        long graceSeconds = 60L;
        try {
            graceSeconds = new MigrationConfig(configPath).getShutdownGraceSeconds();
        } catch (Exception e) {
            logger.warn("Could not read CLI shutdown grace; using 60 seconds: {}", e.getMessage());
        }
        CliShutdownLifecycle lifecycle = new CliShutdownLifecycle(graceSeconds);
        boolean terminationConfirmed = true;
        int exitCode = 0;
        try {
            lifecycle.register();
            startMigration(configPath);
        } catch (RunTerminationException e) {
            terminationConfirmed = e.isTerminationConfirmed();
            exitCode = e.getExitCode();
            logger.error("Migration terminated: {}", e.getMessage(), e.getCause());
        } catch (Exception e) {
            terminationConfirmed = false;
            exitCode = 1;
            logger.error("Migration failed", e);
        } finally {
            lifecycle.finish(terminationConfirmed);
        }
        return exitCode;
    }

    /**
     * Startet den Migrationsprozess basierend auf der angegebenen Konfigurationsdatei.
     * Wird auch von der WebGUI aufgerufen, um Ressourcenkonflikte zu vermeiden.
     */
    public static void startMigration(String configPath) throws Exception {
        WorkerFailureState workerFailureState = new WorkerFailureState();

        // 1. Load Config and enforce the global destructive-operation policy.
        MigrationConfig config = new MigrationConfig(configPath);
        OperationalPolicy.enforceCascadeDeleteDisabled(config);

        // Round 13A: SDK-Capability-Probe + Fail-Fast für >2 GB-sichere Pfade.
        // Verhindert, dass eine 30M-Items-Migration nachts startet und dann
        // einzelne >2 GB-Items entweder mit 32-bit-Truncation korrumpieren
        // oder den Tempfile-Pfad mit DGL0303A killen.
        SdkCapabilityProbe.logCapabilities();
        SdkCapabilityProbe.enforceFailFast();

        // 2. Journal initialisieren
        // Nutzt den Standard-Pfad oder die benutzerdefinierte Basis-Verzeichnis-Einstellung
        // Unterstützung für DB_URL_APPEND zur H2-Optimierung hinzugefügt
        MigrationJournal journal = new MigrationJournal(config.getDbPath(), config.getDbUrlAppend()); 
        journal.init();

        // PERFORMANCE-FIX: Die Queue-Größe ist nun konfigurierbar (Standard: 10.000)
        // Eine zu kleine Queue würde den Producer blockieren und das Discovery verlangsamen.
        BlockingQueue<MigrationItem> queue = new LinkedBlockingQueue<>(config.getQueueSize());
        int threadCount = config.getThreadCount();
            
        // --- KORREKTUR: Getrennte Thread-Pools ---
        // Dieser Pool ist EXKLUSIV für die Worker-Threads (Producer + Consumers) reserviert.
        ExecutorService workerExecutor = Executors.newFixedThreadPool(threadCount + 1); 

        // 4. Statistiken & Monitoring (laufen in einem eigenen Thread außerhalb des Pools)
        MigrationStats stats = new MigrationStats();
        WebServer.attachCurrentStats(stats);
            
        // ItemTypes für die Anzeige aufbereiten (Quell-Typ -> Ziel-Typ)
        java.util.Map<String, String> mapping = config.getItemTypeMapping();
        java.util.List<String> mapLines = new java.util.ArrayList<>();
        mapping.forEach((s, d) -> {
            if (s.equals(d)) mapLines.add(s);
            else mapLines.add(s + " -> " + d);
        });
        String mappingStr = String.join(", ", mapLines);
        if (mappingStr.length() > 40) mappingStr = mappingStr.substring(0, 37) + "...";

        // Formatiertes Mapping an den ProgressMonitor übergeben
        ProgressMonitor monitor = new ProgressMonitor(stats, 5000, config.getSourceSSID(), config.getDestSSID(), mappingStr, "", config.getOperationMode()); 
        Thread monitorThread = new Thread(monitor);
        monitorThread.start();

        // 5. Connection Pool
        CMConnectionPool pool = new CMConnectionPool(config);
        pool.init();

        // 6. JMX & Monitoring
        MigrationMetrics.register(stats, queue, config.getSourceSSID(), config.getDestSSID());
        startResourceMonitor();

        // 6. Start Producer
        Producer producer = new Producer(queue, config, journal, stats, workerFailureState);
        workerExecutor.submit(producer);

        // 7. Start Consumers
        for (int i = 0; i < threadCount; i++) {
            ItemMigrator migrator = new ItemMigrator(pool);
            Consumer consumer = new Consumer(queue, migrator, journal, stats, config);
            workerExecutor.submit(consumer);
        }

        // 8. Bounded two-stage wait. Native SDK calls are never force-stopped.
        RunTerminationException terminalOutcome = null;
        WorkerTermination.Outcome termination;
        try {
            termination = WorkerTermination.await(
                    workerExecutor,
                    config.getWorkerTimeoutSeconds(),
                    config.getShutdownGraceSeconds(),
                    ShutdownCoordinator::requestShutdown);
        } catch (InterruptedException e) {
            boolean terminated = WorkerTermination.awaitGraceAfterInterrupt(
                    workerExecutor,
                    config.getShutdownGraceSeconds(),
                    ShutdownCoordinator::requestShutdown);
            termination = new WorkerTermination.Outcome(false, terminated);
            terminalOutcome = new RunTerminationException(
                    RunTerminationException.Reason.INTERRUPTED,
                    "Migration interrupted by operator request.",
                    terminated,
                    e);
        }

        boolean workersTerminated = termination.terminated();
        if (termination.timedOut()) {
            terminalOutcome = new RunTerminationException(
                    RunTerminationException.Reason.TIMEOUT,
                    workersTerminated
                            ? "Migration timed out; workers stopped during the grace period."
                            : "Migration timed out; worker termination is not confirmed.",
                    workersTerminated,
                    null);
        }

        boolean aborted = terminalOutcome != null
                || ShutdownCoordinator.isShuttingDown()
                || workerFailureState.hasFailure();
        if (terminalOutcome == null && ShutdownCoordinator.isShuttingDown()) {
            terminalOutcome = new RunTerminationException(
                    RunTerminationException.Reason.INTERRUPTED,
                    "Migration stopped by shutdown request.",
                    workersTerminated,
                    null);
        }

        if (!aborted && workersTerminated) {
            // Generate migration protocol reports if enabled
            if (config.isGenerateAuditProtocol()) {
                try {
                    logger.info("Generating migration protocol reports...");
                    var reportGenerator = new ProtocolReportGenerator(config);
                    reportGenerator.generateAllMigrationReports();
                    logger.info("Migration protocol reports generated in reports/");
                } catch (Exception e) {
                    logger.error("Failed to generate protocol reports: {}", e.getMessage(), e);
                }
            }

            // Legacy report and Email notification
            try {
                ReportGenerator.generateMigrationReport(config, stats, config.getOperationMode());
                if (config.getEmailTo() != null && !config.getEmailTo().isEmpty()) {
                    logger.info("Sending migration status email to: {}", config.getEmailTo());
                    EmailNotifier.sendReport(config, "migration_report.html", config.getOperationMode(), stats);
                }
            } catch (Exception e) {
                logger.error("Failed to generate legacy report or send email: {}", e.getMessage());
            }
        } else {
            logger.warn("Migration did not complete normally. Skipping final reports and email notification.");
        }

        if (workersTerminated) {
            // Only disconnect resources after every worker has definitely stopped.
            pool.close();
            journal.close();
            monitorThread.interrupt();
            try {
                monitorThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (terminalOutcome == null) {
                    terminalOutcome = new RunTerminationException(
                            RunTerminationException.Reason.INTERRUPTED,
                            "Migration interrupted during confirmed cleanup.",
                            true,
                            e);
                }
            }
        } else {
            logger.warn("Workers may still be active; leaving CM pool, journal, and monitor open.");
        }

        System.out.println("\n" + ConsoleUI.separator());
        if (aborted) {
            System.out.println("Migration did not complete normally.");
        } else {
            System.out.println("Migration completed!");
        }
        System.out.println("Total: " + stats.getTotalItems()
                + " | Success: " + stats.getSuccessItems()
                + " | Failed: " + stats.getFailedItems());
        System.out.println(ConsoleUI.separator());

        if (terminalOutcome != null) {
            throw terminalOutcome;
        }
        workerFailureState.throwIfPresent("Migration worker failed");
    }

    /**
     * Adaptives Ressourcen-Management.

     */
    private static void startResourceMonitor() {
        Thread t = new Thread(() -> {
            while (true) {
                if (ShutdownCoordinator.isShuttingDown()) {
                    logger.info("Consumer {} stopping due to shutdown request", Thread.currentThread().getName());
                    break;
                }                
                try {
                    Thread.sleep(10000);
                    Runtime runtime = Runtime.getRuntime();
                    long maxMemory = runtime.maxMemory();
                    long allocatedMemory = runtime.totalMemory();
                    long freeMemory = runtime.freeMemory();
                    long usedMemory = allocatedMemory - freeMemory;
                    
                    double usagePercent = (usedMemory / (double) maxMemory) * 100.0;
                    if (usagePercent > 90.0) {
                        logger.warn("CRITICAL MEMORY USAGE: {}% of {}MB used. Consider reducing THREAD_COUNT or increasing -Xmx.", 
                                String.format("%.1f", usagePercent), (maxMemory / 1024 / 1024));
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    logger.debug("Resource monitor error: {}", e.getMessage());
                }
            }
        }, "ResourceMonitor");
        t.setDaemon(true);
        t.start();
    }
}