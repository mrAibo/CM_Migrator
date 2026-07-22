package com.example.migrator;

import com.example.migrator.config.ConfigManager;
import com.example.migrator.connection.ConnectionManager;
import com.example.migrator.journal.MigrationJournal;
import com.example.migrator.reader.JdbcItemReader;
import com.example.migrator.worker.ApiItemWorker;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.apache.commons.cli.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class FastBatchItemMigrator {
    private static final Logger logger = LogManager.getLogger(FastBatchItemMigrator.class);

    public static void main(String[] args) {
        // CLI Optionen definieren
        Options options = new Options();
        options.addOption("c", "config", true, "Pfad zur Konfigurationsdatei (Default: migrator.properties)");
        options.addOption("i", "itemType", true, "Name des zu migrierenden ItemTypes");
        options.addOption("h", "help", false, "Zeigt diese Hilfe an");

        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption("h")) {
                printHelp(options);
                return;
            }

            String configPath = cmd.getOptionValue("c", "migrator.properties");
            String itemType = cmd.getOptionValue("i");

            if (itemType == null) {
                System.err.println("Fehler: ItemType muss angegeben werden (-i <Name>)");
                printHelp(options);
                return;
            }

            runMigration(configPath, itemType);

        } catch (ParseException e) {
            System.err.println("Fehler beim Parsen der Argumente: " + e.getMessage());
            printHelp(options);
        } catch (Exception e) {
            logger.fatal("Kritischer Fehler in der Anwendung", e);
            e.printStackTrace();
        }
    }

    private static void runMigration(String configPath, String itemType) throws Exception {
        logger.info("Starte Migration...");
        
        // 1. Konfiguration laden
        ConfigManager.load(configPath);
        
        // 2. Infrastruktur initialisieren
        ConnectionManager.init();
        MigrationJournal journal = new MigrationJournal();
        
        // 3. Queue erstellen (Begrenzte Kapazität um Speicherüberlauf zu verhindern)
        BlockingQueue<String> queue = new LinkedBlockingQueue<>(10000);
        
        // 4. Reader Thread starten
        Thread readerThread = new Thread(new JdbcItemReader(itemType, queue, journal), "JdbcReader");
        readerThread.start();
        
        // 5. Worker Threads starten
        int workerCount = ConfigManager.getInt("process.writer.threads", 4);
        List<ApiItemWorker> workers = new ArrayList<>();
        List<Thread> workerThreads = new ArrayList<>();
        
        String targetItemType = ConfigManager.get("target.itemType", itemType); // Default: Gleicher Name

        for (int i = 0; i < workerCount; i++) {
            ApiItemWorker worker = new ApiItemWorker(queue, journal, targetItemType);
            workers.add(worker);
            Thread t = new Thread(worker, "Worker-" + i);
            workerThreads.add(t);
            t.start();
        }
        
        logger.info("Migration gestartet mit " + workerCount + " Workern.");
        
        // 6. Warten auf Reader
        readerThread.join();
        logger.info("Reader fertig. Warte auf Abarbeitung der Queue...");
        
        // 7. Warten bis Queue leer ist und Worker stoppen
        while (!queue.isEmpty()) {
            Thread.sleep(1000);
        }
        
        logger.info("Queue leer. Stoppe Worker...");
        for (ApiItemWorker worker : workers) {
            worker.stop();
        }
        
        for (Thread t : workerThreads) {
            t.join();
        }
        
        // 8. Cleanup
        ConnectionManager.shutdown();
        journal.close();
        logger.info("Migration erfolgreich beendet.");
    }

    private static void printHelp(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("FastBatchItemMigrator", options);
    }
}
