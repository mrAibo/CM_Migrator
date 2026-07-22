package com.example.migrator.journal;

import java.sql.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Verwaltet das Migrations-Journal in einer lokalen H2-Datenbank.
 * Diese Klasse protokolliert den Status jedes migrierten Objekts (Source PID),
 * um Wiederaufsetzen bei Fehlern zu ermöglichen und Duplikate zu vermeiden.
 */
public class MigrationJournal implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger(MigrationJournal.class);
    
    // Verbindungsinformationen für die eingebettete H2-Datenbank
    private static final String DB_URL = "jdbc:h2:./migration_journal;DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=TRUE";
    private static final String DB_USER = "sa";
    private static final String DB_PASSWORD = "";

    private Connection connection;

    /**
     * Konstruktor: Stellt die Verbindung zur Datenbank her und initialisiert die Tabelle.
     * @throws SQLException Wenn ein Datenbankfehler auftritt.
     */
    public MigrationJournal() throws SQLException {
        this.connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
        initTable();
    }

    /**
     * Erstellt die Journal-Tabelle, falls sie noch nicht existiert.
     * Speichert Status, Zeitstempel, Fehler und Versuche pro Objekt.
     */
    private void initTable() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Tabelle für das Migrationsprotokoll definieren
            String sql = "CREATE TABLE IF NOT EXISTS MIGRATION_JOURNAL (" +
                         "SOURCE_PID VARCHAR(255) PRIMARY KEY, " + // Eindeutige ID des Quellobjekts
                         "TARGET_PID VARCHAR(255), " +             // ID des erstellten Zielobjekts
                         "STATUS VARCHAR(20), " +                  // Aktueller Status (z.B. COMPLETED, FAILED)
                         "CHECKSUM VARCHAR(64), " +                // Prüfsumme zur Verifizierung
                         "ATTEMPTS INT DEFAULT 0, " +              // Anzahl der Versuche
                         "ERROR_MSG CLOB, " +                      // Fehlermeldung bei Problemen
                         "UPDATED_AT TIMESTAMP DEFAULT CURRENT_TIMESTAMP)"; // Zeitstempel der letzten Änderung
            stmt.execute(sql);
        }
    }

    /**
     * Prüft den aktuellen Status eines Objekts anhand seiner Source PID.
     * @param sourcePid Die ID des Quellobjekts.
     * @return Der Status (MigrationStatus) oder null, wenn das Objekt noch nicht bearbeitet wurde.
     */
    public synchronized MigrationStatus getStatus(String sourcePid) throws SQLException {
        String sql = "SELECT STATUS FROM MIGRATION_JOURNAL WHERE SOURCE_PID = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, sourcePid);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return MigrationStatus.valueOf(rs.getString("STATUS"));
                }
            }
        }
        return null; // Nicht gefunden -> Das Objekt wurde noch nicht bearbeitet
    }

    /**
     * Markiert ein Objekt als "in Bearbeitung" (IN_PROGRESS).
     * Verwendet MERGE, um entweder einen neuen Eintrag zu erstellen oder einen bestehenden zu aktualisieren.
     * Erhöht dabei automatisch den Zähler für die Versuche (ATTEMPTS).
     * @param sourcePid Die ID des Quellobjekts.
     */
    public synchronized void markInProgress(String sourcePid) throws SQLException {
        // MERGE INTO ist eine Kombination aus INSERT und UPDATE (Upsert)
        String sql = "MERGE INTO MIGRATION_JOURNAL (SOURCE_PID, STATUS, UPDATED_AT, ATTEMPTS) " +
                     "KEY(SOURCE_PID) " +
                     "VALUES (?, ?, CURRENT_TIMESTAMP, " +
                     // Wenn Eintrag existiert: Versuche + 1, sonst: 1
                     "COALESCE((SELECT ATTEMPTS FROM MIGRATION_JOURNAL WHERE SOURCE_PID = ?) + 1, 1))";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, sourcePid);
            pstmt.setString(2, MigrationStatus.IN_PROGRESS.name());
            pstmt.setString(3, sourcePid); // Für das Sub-Select (COALESCE)
            pstmt.executeUpdate();
        }
    }

    /**
     * Markiert ein Objekt als erfolgreich migriert (COMPLETED).
     * Speichert zusätzlich die ID des neuen Zielobjekts und eine Prüfsumme.
     * @param sourcePid Die ID des Quellobjekts.
     * @param targetPid Die ID des erstellten Zielobjekts.
     * @param checksum Eine Prüfsumme zur Validierung des Inhalts.
     */
    public synchronized void markCompleted(String sourcePid, String targetPid, String checksum) throws SQLException {
        String sql = "UPDATE MIGRATION_JOURNAL SET STATUS = ?, TARGET_PID = ?, CHECKSUM = ?, UPDATED_AT = CURRENT_TIMESTAMP WHERE SOURCE_PID = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, MigrationStatus.COMPLETED.name());
            pstmt.setString(2, targetPid);
            pstmt.setString(3, checksum);
            pstmt.setString(4, sourcePid);
            pstmt.executeUpdate();
        }
    }

    /**
     * Markiert ein Objekt als fehlgeschlagen (FAILED) und speichert den Fehlergrund.
     * @param sourcePid Die ID des Quellobjekts.
     * @param errorMsg Die Fehlermeldung oder der Stacktrace.
     */
    public synchronized void markFailed(String sourcePid, String errorMsg) throws SQLException {
        String sql = "UPDATE MIGRATION_JOURNAL SET STATUS = ?, ERROR_MSG = ?, UPDATED_AT = CURRENT_TIMESTAMP WHERE SOURCE_PID = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, MigrationStatus.FAILED.name());
            pstmt.setString(2, errorMsg);
            pstmt.setString(3, sourcePid);
            pstmt.executeUpdate();
        }
    }

    /**
     * Schließt die Datenbankverbindung ordnungsgemäß.
     * Wird automatisch aufgerufen, wenn try-with-resources verwendet wird.
     */
    @Override
    public void close() throws Exception {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}
