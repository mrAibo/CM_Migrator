/*
 * Projekt: CM Migrator 2.2.1.
 * @Author: Aleksej Voronin, Sven Lindt
 * @Date:   26.01.2026
 */
package com.ibm.ecm.migration;

import java.io.BufferedReader;
import java.io.FileReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.HashSet;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Tool zum Nachmigrieren von Objekten, die auf Fehler gelaufen sind
 * Usage: java -cp ... com.ibm.ecm.migration.RemigrationTool <error-log-file>
 */
public class RemigrationTool {
    private static final Logger logger = LogManager.getLogger(RemigrationTool.class);

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: RemigrationTool <verification-error-log-file>");
            System.err.println("Example: RemigrationTool verification_errors.log");
            System.exit(1);
        }

        String errorLogFile = args[0];
        String dbBaseDir = "./data/migration_journal";

        try {
            Class.forName("org.h2.Driver");
            
            // Parse error log für exakte PIDs
            Set<String> failedPids = parseErrorLog(errorLogFile);
            
            if (failedPids.isEmpty()) {
                System.out.println("No failed items found in error log.");
                return;
            }
            
            System.out.println("Found " + failedPids.size() + " failed items.");
            System.out.println("Marking them for re-migration...");
            
            // Group by ItemType and update journal
            int updatedCount = markForRemigration(failedPids, dbBaseDir);
            
            System.out.println("\n✅ SUCCESS: " + updatedCount + " items marked for re-migration.");
            System.out.println("You can now restart the migration to re-process these items.");
            
        } catch (Exception e) {
            logger.fatal("Re-migration tool failed", e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static Set<String> parseErrorLog(String logFile) throws Exception {
        Set<String> pids = new HashSet<>();
        
        try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                // Extract PID from MISMATCH line
                // Format: "MISMATCH: ... A1001001A25J28B20536H4587518 ..."
                if (line.contains("MISMATCH:")) {
                    // PID format: A1001001A...
                    String[] parts = line.split("\\s+");
                    for (String part : parts) {
                        if (part.startsWith("A1001001A") && part.length() > 20) {
                            pids.add(part);
                            break;
                        }
                    }
                }
            }
        }
        
        return pids;
    }

    private static int markForRemigration(Set<String> pids, String dbBaseDir) throws Exception {
        int count = 0;
        
        // Try all possible ItemType journals
        java.io.File dataDir = new java.io.File(dbBaseDir);
        java.io.File[] dbFiles = dataDir.listFiles((dir, name) -> name.startsWith("journal_") && name.endsWith(".mv.db"));
        
        if (dbFiles == null || dbFiles.length == 0) {
            System.err.println("No journal databases found in " + dbBaseDir);
            return 0;
        }
        
        for (java.io.File dbFile : dbFiles) {
            String dbName = dbFile.getName().replace(".mv.db", "");
            String itemType = dbName.replace("journal_", "");
            String dbPath = dbBaseDir + "/" + dbName;
            String jdbcUrl = "jdbc:h2:" + dbPath;
            
            try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "")) {
                String sql = "UPDATE AUDIT_LOG SET STATUS = 'FAILED', MESSAGE = 'Marked for re-migration due to checksum mismatch' WHERE ITEM_ID = ?";
                
                conn.setAutoCommit(false);
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    int batchCount = 0;
                    for (String pid : pids) {
                        pstmt.setString(1, pid);
                        pstmt.addBatch();
                        batchCount++;
                        
                        if (batchCount % 1000 == 0) {
                            int[] results = pstmt.executeBatch();
                            for (int r : results) if (r > 0) count++;
                        }
                    }
                    int[] results = pstmt.executeBatch();
                    for (int r : results) if (r > 0) count++;
                    
                    conn.commit();
                    if (count > 0) {
                        System.out.println("  [" + itemType + "] Marked " + count + " items.");
                    }
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                }
            } catch (Exception e) {
                System.err.println("Warning: Could not update " + itemType + ": " + e.getMessage());
            }
        }
        
        return count;
    }
}
