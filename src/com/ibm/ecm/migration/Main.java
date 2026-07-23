package com.ibm.ecm.migration;

import java.util.concurrent.*;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Main {
    private static final Logger logger = LogManager.getLogger(Main.class);

    public static void main(String[] args) {
        System.exit(runCli(args));
    }

    static int runCli(String[] args) {
        System.out.println(ConsoleUI.banner("2.2.1"));
        logger.info("Starting IBM CM Migrator V8.7...");

        String configPath = "conf/migration.properties";
        if (args.length > 0) configPath = args[0];

        ShutdownCoordinator.reset();
        long graceSeconds = 60L;
        try { graceSeconds = new MigrationConfig(configPath).getShutdownGraceSeconds(); }
        catch (Exception e) { logger.warn("Could not read CLI shutdown grace; using 60s: {}", e.getMessage()); }

        CliShutdownLifecycle lifecycle = new CliShutdownLifecycle(graceSeconds);
        final String finalConfigPath = configPath;
        CliLifecycleRunner.CliRunResult result = CliLifecycleRunner.executeCli(
                lifecycle, () -> startMigration(finalConfigPath));

        Exception failure = result.failure();
        if (failure instanceof RunTerminationException) {
            RunTerminationException rte = (RunTerminationException) failure;
            logger.error("Migration terminated: {}", rte.getMessage(), rte.getCause());
        } else if (failure != null) {
            logger.error("Migration failed", failure);
        }
        return result.exitCode();
    }

    public static void startMigration(String configPath) throws Exception {
        WorkerFailureState workerFailureState = new WorkerFailureState();

        MigrationConfig config = new MigrationConfig(configPath);
        OperationalPolicy.enforceCascadeDeleteDisabled(config);

        SdkCapabilityProbe.logCapabilities();
        SdkCapabilityProbe.enforceFailFast();

        MigrationJournal journal = new MigrationJournal(config.getDbPath(), config.getDbUrlAppend());
        journal.init();

        BlockingQueue<MigrationItem> queue = new LinkedBlockingQueue<>(config.getQueueSize());
        int threadCount = config.getThreadCount();
        ExecutorService workerExecutor = Executors.newFixedThreadPool(threadCount + 1);

        MigrationStats stats = new MigrationStats();
        WebServer.attachCurrentStats(stats);

        java.util.Map<String, String> mapping = config.getItemTypeMapping();
        java.util.List<String> mapLines = new java.util.ArrayList<>();
        mapping.forEach((s, d) -> mapLines.add(s.equals(d) ? s : s + " -> " + d));
        String mappingStr = String.join(", ", mapLines);
        if (mappingStr.length() > 40) mappingStr = mappingStr.substring(0, 37) + "...";

        // HTML dashboard (ProgressMonitor) — keep for status.html
        ProgressMonitor monitor = new ProgressMonitor(stats, 5000,
                config.getSourceSSID(), config.getDestSSID(),
                mappingStr, "", config.getOperationMode());
        Thread monitorThread = new Thread(monitor);
        monitorThread.start();

        // ─── Console dashboard thread ───
        final OperatorConsole.Snapshot current = new OperatorConsole.Snapshot();
        current.state = OperatorConsole.RunState.RUNNING;
        current.phase = OperatorConsole.Phase.INITIALIZING;
        current.mode = config.getOperationMode();
        current.strategy = config.getProducerCountStrategy();
        current.sourceSSID = config.getSourceSSID();
        current.destSSID = config.getDestSSID();

        // Extract first item-type pair for display (ponytail: first mapping entry)
        String firstSrc = "", firstDst = "";
        if (!mapping.isEmpty()) {
            Map.Entry<String, String> e = mapping.entrySet().iterator().next();
            firstSrc = e.getKey(); firstDst = e.getValue();
        }
        current.sourceItemType = firstSrc;
        current.destItemType = firstDst;
        current.queueCapacity = config.getQueueSize();

        Thread consoleThread = new Thread(() -> {
            while (true) {
                OperatorConsole.RunState st = current.state;
                if (st == OperatorConsole.RunState.COMPLETED
                        || st == OperatorConsole.RunState.FAILED
                        || st == OperatorConsole.RunState.INTERRUPTED)
                    break;
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                synchronized (current) {
                    current.elapsedMs = System.currentTimeMillis() - stats.getStartTime();
                    current.total = stats.getTotalItems();
                    current.discovered = stats.getDiscoveredItems();
                    current.processed = stats.getProcessedItems();
                    current.success = stats.getSuccessItems();
                    current.failed = stats.getFailedItems();
                    current.skipped = stats.getSkippedItems();
                    current.deleted = stats.getDeletedItems();
                    current.queueDepth = queue.size();
                    current.journalQueueDepth = journal.getJournalQueueSize();
                    current.journalQueueCapacity = journal.getJournalQueueCapacity();
                    current.journalPersisted = journal.getPersistedCount();
                    current.journalHealth = journalHealthFromString(journal.getJournalHealth());
                    current.journalError = journal.getJournalError();
                    current.activeWorkers = threadCount; // ponytail: fixed pool size
                }
                OperatorConsole.draw(current);
            }
        }, "ConsoleDashboard");
        consoleThread.setDaemon(true);
        consoleThread.start();

        // ─── Connection Pool ───
        CMConnectionPool pool = new CMConnectionPool(config);
        pool.init();
        MigrationMetrics.register(stats, queue, config.getSourceSSID(), config.getDestSSID());
        startResourceMonitor();

        // Start Producer & Consumers
        synchronized (current) { current.phase = OperatorConsole.Phase.DISCOVERING; }
        Producer producer = new Producer(queue, config, journal, stats, workerFailureState);
        workerExecutor.submit(producer);

        for (int i = 0; i < threadCount; i++) {
            Consumer consumer = new Consumer(queue, new ItemMigrator(pool), journal, stats, config);
            workerExecutor.submit(consumer);
        }

        // ─── Bounded two-stage wait ───
        RunTerminationException terminalOutcome = null;
        WorkerTermination.Outcome termination;
        try {
            termination = WorkerTermination.await(workerExecutor,
                    config.getWorkerTimeoutSeconds(),
                    config.getShutdownGraceSeconds(),
                    ShutdownCoordinator::requestShutdown);
        } catch (InterruptedException e) {
            boolean t = WorkerTermination.awaitGraceAfterInterrupt(workerExecutor,
                    config.getShutdownGraceSeconds(),
                    ShutdownCoordinator::requestShutdown);
            termination = new WorkerTermination.Outcome(false, t);
            terminalOutcome = new RunTerminationException(
                    RunTerminationException.Reason.INTERRUPTED,
                    "Migration interrupted by operator request.", t, e);
        }

        boolean workersTerminated = termination.terminated();
        synchronized (current) { current.phase = OperatorConsole.Phase.DRAINING_WORKERS; }
        if (termination.timedOut()) {
            terminalOutcome = new RunTerminationException(
                    RunTerminationException.Reason.TIMEOUT,
                    workersTerminated
                            ? "Migration timed out; workers stopped during grace period."
                            : "Migration timed out; worker termination not confirmed.",
                    workersTerminated, null);
        }

        boolean aborted = terminalOutcome != null
                || ShutdownCoordinator.isShuttingDown()
                || workerFailureState.hasFailure();
        if (terminalOutcome == null && ShutdownCoordinator.isShuttingDown()) {
            terminalOutcome = new RunTerminationException(
                    RunTerminationException.Reason.INTERRUPTED,
                    "Migration stopped by shutdown request.", workersTerminated, null);
        }

        // ─── Journal close before reports (PR #13 invariant) ───
        boolean journalClosed = false;
        if (!aborted && workersTerminated) {
            synchronized (current) { current.phase = OperatorConsole.Phase.DRAINING_JOURNAL; }
            try {
                journal.close();
                journalClosed = true;
                synchronized (current) {
                    current.journalHealth = OperatorConsole.JournalHealth.HEALTHY;
                    current.journalQueueDepth = 0;
                }
            } catch (Exception e) {
                logger.error("Journal close failed — marking migration as aborted.", e);
                aborted = true;
                synchronized (current) {
                    current.journalHealth = OperatorConsole.JournalHealth.FAILED;
                    current.journalError = e.getMessage();
                }
                if (terminalOutcome == null) {
                    terminalOutcome = new RunTerminationException(
                            RunTerminationException.Reason.FAILED,
                            "Journal persistence failure: " + e.getMessage(), true, e);
                }
            }

            if (!aborted && workersTerminated) {
                synchronized (current) { current.phase = OperatorConsole.Phase.GENERATING_REPORTS; }
                if (config.isGenerateAuditProtocol()) {
                    try {
                        logger.info("Generating migration protocol reports...");
                        new ProtocolReportGenerator(config).generateAllMigrationReports();
                        logger.info("Migration protocol reports generated in reports/");
                    } catch (Exception e) {
                        logger.error("Failed to generate protocol reports: {}", e.getMessage(), e);
                    }
                }
                try {
                    ReportGenerator.generateMigrationReport(config, stats, config.getOperationMode());
                    if (config.getEmailTo() != null && !config.getEmailTo().isEmpty()) {
                        logger.info("Sending migration status email to: {}", config.getEmailTo());
                        EmailNotifier.sendReport(config, "migration_report.html", config.getOperationMode(), stats);
                    }
                } catch (Exception e) {
                    logger.error("Failed to generate report or send email: {}", e.getMessage());
                }
            }
        } else {
            logger.warn("Migration did not complete normally. Skipping reports and email.");
        }

        if (workersTerminated) {
            if (!journalClosed) {
                try { journal.close(); } catch (Exception ignored) {}
            }
            pool.close();
            monitorThread.interrupt();
            try { monitorThread.join(5000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (terminalOutcome == null)
                    terminalOutcome = new RunTerminationException(
                            RunTerminationException.Reason.INTERRUPTED,
                            "Migration interrupted during confirmed cleanup.", true, e);
            }
        } else {
            logger.warn("Workers may still be active; leaving pool, journal, monitor open.");
        }

        // ─── Final state ───
        synchronized (current) {
            current.phase = OperatorConsole.Phase.FINALIZING;
            if (terminalOutcome != null) {
                current.state = (terminalOutcome.getReason() == RunTerminationException.Reason.INTERRUPTED)
                        ? OperatorConsole.RunState.INTERRUPTED
                        : OperatorConsole.RunState.FAILED;
            } else if (aborted) {
                current.state = OperatorConsole.RunState.FAILED;
            } else {
                current.state = OperatorConsole.RunState.COMPLETED;
            }
        }
        OperatorConsole.finalRender(current);

        consoleThread.interrupt();
        try { consoleThread.join(1000); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        // ─── Console summary (after dashboard is done) ───
        System.out.println("\n" + ConsoleUI.separator());
        if (aborted) System.out.println("Migration did not complete normally.");
        else System.out.println("Migration completed!");
        System.out.println("Total: " + stats.getTotalItems()
                + " | Success: " + stats.getSuccessItems()
                + " | Failed: " + stats.getFailedItems());
        System.out.println(ConsoleUI.separator());

        if (terminalOutcome != null) throw terminalOutcome;
        workerFailureState.throwIfPresent("Migration worker failed");
    }

    private static OperatorConsole.JournalHealth journalHealthFromString(String s) {
        if (s == null) return OperatorConsole.JournalHealth.UNKNOWN;
        try { return OperatorConsole.JournalHealth.valueOf(s); }
        catch (IllegalArgumentException e) { return OperatorConsole.JournalHealth.UNKNOWN; }
    }

    private static void startResourceMonitor() {
        Thread t = new Thread(() -> {
            while (!ShutdownCoordinator.isShuttingDown()) {
                try { Thread.sleep(10000); } catch (InterruptedException e) { break; }
                try {
                    Runtime rt = Runtime.getRuntime();
                    double pct = (rt.totalMemory() - rt.freeMemory()) / (double) rt.maxMemory() * 100.0;
                    if (pct > 90.0) logger.warn("CRITICAL MEMORY: {}% of {}MB",
                            String.format("%.1f", pct), rt.maxMemory() / 1024 / 1024);
                } catch (Exception e) { logger.debug("Resource monitor error: {}", e.getMessage()); }
            }
        }, "ResourceMonitor");
        t.setDaemon(true);
        t.start();
    }
}