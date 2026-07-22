package com.example.migrator.connection;

import com.example.migrator.config.ConfigManager;
import com.ibm.mm.sdk.common.DKDatastorePool;
import com.ibm.mm.sdk.common.dkDatastore;
import com.ibm.mm.sdk.server.DKDatastoreICM;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Verwaltet die Datenbankverbindungen für Quell- und Zielsysteme.
 * Nutzt Connection Pooling (DKDatastorePool), um die Performance zu optimieren
 * und Ressourcen effizient zu nutzen.
 */
public class ConnectionManager {
    private static final Logger logger = LogManager.getLogger(ConnectionManager.class);
    
    // Pools für Quell- und Ziel-Datastores
    private static DKDatastorePool sourcePool;
    private static DKDatastorePool targetPool;

    /**
     * Initialisiert die Verbindungspools basierend auf der Konfiguration.
     * Erstellt separate Pools für Quelle und Ziel, um parallele Zugriffe zu ermöglichen.
     * @throws Exception Wenn die Initialisierung fehlschlägt.
     */
    public static synchronized void init() throws Exception {
        if (sourcePool != null) return; // Bereits initialisiert

        // Initialisierung des Quell-Pools
        logger.info("Initialisiere Quell-Pool...");
        DKDatastoreICM sourceProto = new DKDatastoreICM();
        sourcePool = new DKDatastorePool(sourceProto);
        sourcePool.setDatastoreName(ConfigManager.get("source.cm.database"));
        // Setzt Pool-Größe basierend auf der Anzahl der Writer-Threads
        sourcePool.setMinAndMaxPoolSize(1, ConfigManager.getInt("process.writer.threads", 8));
        
        // Initialisierung des Ziel-Pools
        logger.info("Initialisiere Ziel-Pool...");
        DKDatastoreICM targetProto = new DKDatastoreICM();
        targetPool = new DKDatastorePool(targetProto);
        targetPool.setDatastoreName(ConfigManager.get("dest.cm.database"));
        targetPool.setMinAndMaxPoolSize(1, ConfigManager.getInt("process.writer.threads", 8));
    }

    /**
     * Holt eine Verbindung zum Quellsystem aus dem Pool.
     * @return Eine aktive dkDatastore Verbindung.
     * @throws Exception Wenn keine Verbindung hergestellt werden kann.
     */
    public static dkDatastore getSourceConnection() throws Exception {
        return sourcePool.getConnection(
            ConfigManager.get("source.cm.user"),
            ConfigManager.get("source.cm.password")
        );
    }

    /**
     * Gibt eine Quell-Verbindung zurück in den Pool.
     * @param ds Die zurückzugebende Verbindung.
     */
    public static void returnSourceConnection(dkDatastore ds) {
        if (ds != null) {
            try {
                sourcePool.returnConnection(ds);
            } catch (Exception e) {
                logger.error("Fehler beim Zurückgeben der Quell-Verbindung", e);
            }
        }
    }

    /**
     * Holt eine Verbindung zum Zielsystem aus dem Pool.
     * @return Eine aktive dkDatastore Verbindung.
     * @throws Exception Wenn keine Verbindung hergestellt werden kann.
     */
    public static dkDatastore getTargetConnection() throws Exception {
        return targetPool.getConnection(
            ConfigManager.get("dest.cm.user"),
            ConfigManager.get("dest.cm.password")
        );
    }

    /**
     * Gibt eine Ziel-Verbindung zurück in den Pool.
     * @param ds Die zurückzugebende Verbindung.
     */
    public static void returnTargetConnection(dkDatastore ds) {
        if (ds != null) {
            try {
                targetPool.returnConnection(ds);
            } catch (Exception e) {
                logger.error("Fehler beim Zurückgeben der Ziel-Verbindung", e);
            }
        }
    }
    
    public static void shutdown() {
        // Pools don't have a nice shutdown method in older APIs, usually just let JVM exit or clear cache
        // sourcePool.clearCacheOfConnections();
    }
}
