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
        // Start-Banner in der Konsole ausgeben
        System.out.println(ConsoleUI.banner("2.2.1"));
       
        logger.info("Starting IBM CM Migrator V8.7...");
         
        String configPath = "conf/migration.properties";
        if (args.length > 0) {
            configPath = args[0];
        }

        try {
            startMigration(configPath);
        } catch (Exception e) {
            logger.error("Migration failed", e);
            System.exit(1);
        }
    }
    
    /**
     * Startet den Migrationsprozess basierend auf der angegebenen Konfigurationsdatei.
     * Wird auch von der WebGUI aufgerufen, um Ressourcenkonflikte zu vermeiden.
     */
    public static void startMigration(String configPath) throws Exception {
        ShutdownCoordinator.reset();
        WorkerFailureState workerFailureState = new WorkerFailureState();

        // 1. Load Config
        MigrationConfig config = new MigrationConfig(configPath);

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

        // Setup Shutdown Hook
        setupShutdownHook(workerExecutor, pool, journal);

        // 6. Start Producer
        Producer producer = new Producer(queue, config, journal, stats, workerFailureState);
        workerExecutor.submit(producer);

        // 7. Start Consumers
        for (int i = 0; i < threadCount; i++) {
            ItemMigrator migrator = new ItemMigrator(pool);
            Consumer consumer = new Consumer(queue, migrator, journal, stats, config);
            workerExecutor.submit(consumer);
        }

        // 8. Wait for completion
        // Wir fahren den Worker-Pool herunter. Das blockiert NICHT wegen dem Monitor.
        workerExecutor.shutdown();
            
        boolean aborted = false;
        boolean restoreInterrupt = false;

        try {
            // Wir warten, bis alle Worker fertig sind (Producer + Consumers).
            // Da Producer Poison-Pills sendet, beenden sich die Consumer von selbst.
            if (!workerExecutor.awaitTermination(24, TimeUnit.HOURS)) {
                logger.warn("Worker executor did not finish within timeout. Requesting shutdown without interrupting SDK calls.");
                ShutdownCoordinator.requestShutdown();
                workerExecutor.shutdown();
                aborted = true;
            }
        } catch (InterruptedException e) {
            logger.warn("Main thread interrupted. Requesting graceful shutdown.");
            ShutdownCoordinator.requestShutdown();
            workerExecutor.shutdown();
            aborted = true;
            restoreInterrupt = true;
        }

        while (!workerExecutor.isTerminated()) {
            try {
                workerExecutor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException repeatedInterrupt) {
                restoreInterrupt = true;
            }
        }

        if (ShutdownCoordinator.isShuttingDown() || workerFailureState.hasFailure()) {
            aborted = true;
        }

        if (!aborted) {
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
            logger.warn("Migration was aborted. Skipping final reports and email notification.");
        }

        // KRITISCH: Pool sofort schließen, nachdem die Worker fertig sind.
        // Verhindert, dass asynchrone Refill-Prozesse während des Beendens Verbindungen öffnen.
        pool.close();
        journal.close();
            
        // Stop monitor
        monitorThread.interrupt();
        try {
            monitorThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
            
        // Finale Statistik-Ausgabe in der Konsole
        System.out.println("\n" + ConsoleUI.separator());
        if (aborted) {
            System.out.println("Migration aborted by shutdown request.");
        } else {
            System.out.println("Migration completed!");
        }
        System.out.println("Total: " + stats.getTotalItems()
                + " | Success: " + stats.getSuccessItems()
                + " | Failed: " + stats.getFailedItems());
        System.out.println(ConsoleUI.separator());

        if (restoreInterrupt) {
            Thread.currentThread().interrupt();
        }
        workerFailureState.throwIfPresent("Migration worker failed");
    }

    /**
     * Shutdown-Hook zur sauberen Freigabe von Ressourcen bei SIGTERM/INT (z.B. Strg+C)
     */
    private static void setupShutdownHook(final ExecutorService executor,
                                          final CMConnectionPool pool,
                                          final MigrationJournal journal) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown signal received. Requesting graceful stop...");
            ShutdownCoordinator.requestShutdown();

            boolean terminated = false;

            if (executor != null) {
                executor.shutdown();

                long waitSeconds = Long.getLong("cm.migrator.shutdown.graceSeconds", 60L);
                long deadline = System.currentTimeMillis() + (waitSeconds * 1000L);

                while (!terminated && System.currentTimeMillis() < deadline) {
                    try {
                        terminated = executor.awaitTermination(1, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                if (!terminated) {
                    logger.warn("Workers did not stop within {} seconds. JVM shutdown continues without shutdownNow().",
                            waitSeconds);
                }
            }

            if (terminated) {
                if (pool != null) pool.close();
                if (journal != null) journal.close();
                logger.info("Cleanup complete. Goodbye.");
            } else {
                // Nicht den Pool unter laufenden IBM-SDK-Deletes schließen.
                // JVM/OS räumt beim Prozessende auf; wichtiger ist, keine Fehlerflut
                // durch aktiv getrennte Verbindungen zu erzeugen.
                logger.warn("Shutdown continues with workers still active; leaving CM pool/journal unclosed to avoid disconnecting in-flight SDK operations.");
            }
        }, "shutdown-hook"));
    }

    /**
     * Adaptives Ressourcen-Management.
     * Überwacht die Speicherauslastung und warnt bei kritischen Werten.
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