package com.example.migrator.reader;

import com.example.migrator.connection.ConnectionManager;
import com.example.migrator.journal.MigrationJournal;
import com.example.migrator.journal.MigrationStatus;
import com.ibm.mm.sdk.common.dkDatastore;
import com.ibm.mm.sdk.server.DKDatastoreICM;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Liest Item-IDs (PIDs) direkt aus der DB2-Datenbank via JDBC.
 * Dies ist wesentlich schneller als die Verwendung der CM API Cursor.
 */
public class JdbcItemReader implements Runnable {
    private static final Logger logger = LogManager.getLogger(JdbcItemReader.class);

    private final String itemTypeName;
    private final BlockingQueue<String> queue;
    private final MigrationJournal journal;
    private final AtomicInteger totalFound = new AtomicInteger(0);
    private final AtomicInteger totalSkipped = new AtomicInteger(0);
    private final AtomicInteger totalQueued = new AtomicInteger(0);

    public JdbcItemReader(String itemTypeName, BlockingQueue<String> queue, MigrationJournal journal) {
        this.itemTypeName = itemTypeName;
        this.queue = queue;
        this.journal = journal;
    }

    @Override
    public void run() {
        logger.info("Starte JDBC Reader für ItemType: " + itemTypeName);
        dkDatastore ds = null;
        try {
            ds = ConnectionManager.getSourceConnection();
            DKDatastoreICM dsICM = (DKDatastoreICM) ds;
            Connection jdbcConn = dsICM.getConnection();

            // 1. Tabellennamen ermitteln
            String tableName = resolveTableName(jdbcConn, itemTypeName);
            if (tableName == null) {
                logger.error("Konnte Tabellennamen für ItemType '" + itemTypeName + "' nicht ermitteln.");
                return;
            }
            logger.info("ItemType '" + itemTypeName + "' entspricht Tabelle: " + tableName);

            // 2. PIDs lesen
            String sql = "SELECT ITEMID FROM " + tableName;
            try (PreparedStatement pstmt = jdbcConn.prepareStatement(sql)) {
                pstmt.setFetchSize(2000); // Performance-Optimierung: Große Batches lesen
                
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        String itemId = rs.getString("ITEMID");
                        totalFound.incrementAndGet();

                        // 3. Status prüfen (Journal)
                        MigrationStatus status = journal.getStatus(itemId);
                        if (status == MigrationStatus.COMPLETED) {
                            totalSkipped.incrementAndGet();
                            continue; // Bereits migriert
                        }

                        // 4. In die Queue stellen
                        queue.put(itemId);
                        totalQueued.incrementAndGet();

                        if (totalFound.get() % 5000 == 0) {
                            logger.info("Reader Status: Gefunden=" + totalFound.get() + 
                                      ", Übersprungen=" + totalSkipped.get() + 
                                      ", In Queue=" + totalQueued.get());
                        }
                    }
                }
            }

        } catch (Exception e) {
            logger.error("Fehler im JdbcItemReader", e);
        } finally {
            ConnectionManager.returnSourceConnection(ds);
            logger.info("JdbcItemReader beendet. Total: " + totalFound.get() + ", Queued: " + totalQueued.get());
        }
    }

    private String resolveTableName(Connection conn, String itemTypeName) throws SQLException {
        // Ermittle die ItemTypeID aus der Definitionstabelle
        // Hinweis: Wir gehen davon aus, dass wir Zugriff auf ICMADMIN Tabellen haben.
        // Falls das Schema anders heißt, muss dies angepasst werden.
        String sql = "SELECT ITEMTYPEID FROM ICMADMIN.ICMSTItemTypeDefs WHERE KEYWORDNAME = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, itemTypeName);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    int id = rs.getInt("ITEMTYPEID");
                    // Konstruiere Tabellennamen: ICMUT0 + 5-stellige ID + 001
                    return String.format("ICMADMIN.ICMUT0%05d001", id);
                }
            }
        }
        return null;
    }
}
