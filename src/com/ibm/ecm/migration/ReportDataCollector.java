/*
 * Projekt: CM Migrator 2.2.1.
 *
 * Collects report data from MigrationStats (live totals) and H2 journals
 * (per-item-type detail + errors).  Reads each journal ONCE per itemType -
 * no duplicate connections, no template rendering, no file I/O.
 */
package com.ibm.ecm.migration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ReportDataCollector {

    private static final Logger logger = LogManager.getLogger(ReportDataCollector.class);

    private static final int MAX_ERRORS_PER_TYPE = 5;
    private static final int MAX_ERRORS_GLOBAL  = 50;

    private final MigrationStats stats;
    private final MigrationConfig config;
    private final String dbPath;

    public ReportDataCollector(MigrationStats stats, MigrationConfig config) {
        this.stats  = stats;
        this.config = config;
        this.dbPath = config.getDbPath();
    }

    // ---- single public entry point -----------------------------------------

    /** Read live stats + H2 journals once and return a complete UnifiedReport. */
    public UnifiedReport collect() {
        long endTimeMs = System.currentTimeMillis();
        long processed = stats.getProcessedItems();
        long durationMs = endTimeMs - stats.getStartTime();
        double throughput = durationMs > 0
            ? (double) processed / (durationMs / 1000.0)
            : 0.0;
        double successRate = processed > 0
            ? (double) stats.getSuccessItems() / processed * 100.0
            : 100.0;

        OperationType opType = OperationType.fromMode(config.getOperationMode());

        List<ItemTypeResult> itemTypes = new ArrayList<>();
        List<ReportError>    allErrors = new ArrayList<>();

        for (Map.Entry<String, String> entry : config.getItemTypeMapping().entrySet()) {
            String sourceType = entry.getKey();
            String destType   = entry.getValue();
            try {
                itemTypes.add(collectItemType(sourceType, destType, allErrors));
            } catch (Exception e) {
                logger.warn("Cannot collect data for itemType {}: {}", sourceType, e.getMessage());
                itemTypes.add(ItemTypeResult.unreachable(sourceType, destType));
            }
        }

        // Compute status AFTER collecting item types (needs verification data)
        OverallStatus status = computeStatus(stats, itemTypes);

        // Stable operation ID based on start time (not UUID)
        String operationId = generateOperationId(opType, stats.getStartTime());

        return new UnifiedReport(
            operationId,
            opType, status,
            stats.getStartTime(), endTimeMs,
            config.getSourceSSID(), config.getDestSSID(),
            stats.getTotalItems(), processed,
            stats.getSuccessItems(), stats.getFailedItems(),
            stats.getSkippedItems(), stats.getDeletedItems(),
            throughput, successRate,
            List.copyOf(itemTypes),
            List.copyOf(allErrors)
        );
    }

    // ---- per-item-type journal read (ONE connection) -----------------------

    private ItemTypeResult collectItemType(
        String sourceType, String destType, List<ReportError> globalErrors
    ) throws SQLException {

        String jdbcUrl = "jdbc:h2:" + dbPath + "/journal_" + sourceType + ";IFEXISTS=TRUE";

        try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "")) {

            // -- AUDIT_LOG counts --------------------------------------------
            long itemTotal = 0, itemSuccess = 0, itemFailed = 0,
                 itemSkipped = 0, itemDeleted = 0;

            String countSql =
                "SELECT STATUS, COUNT(*) AS CNT FROM AUDIT_LOG" +
                " WHERE ITEM_TYPE = ? GROUP BY STATUS";
            try (PreparedStatement ps = conn.prepareStatement(countSql)) {
                ps.setString(1, sourceType);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String st = rs.getString("STATUS");
                        long   cnt = rs.getLong("CNT");
                        itemTotal += cnt;
                        if ("SUCCESS".equalsIgnoreCase(st) || "MATCH".equalsIgnoreCase(st))
                            itemSuccess += cnt;
                        else if ("FAILED".equalsIgnoreCase(st) || "ERROR".equalsIgnoreCase(st))
                            itemFailed += cnt;
                        else if ("SKIPPED".equalsIgnoreCase(st))
                            itemSkipped += cnt;
                        else if ("DELETED".equalsIgnoreCase(st))
                            itemDeleted += cnt;
                    }
                }
            }

            // -- error details from AUDIT_LOG (capped) ----------------------
            List<ReportError> itemErrors = new ArrayList<>();
            String errSql =
                "SELECT ITEM_ID, STATUS, MESSAGE, MIGRATION_TIME FROM AUDIT_LOG" +
                " WHERE ITEM_TYPE = ? AND STATUS IN ('FAILED', 'ERROR')" +
                " ORDER BY MIGRATION_TIME DESC LIMIT " + MAX_ERRORS_PER_TYPE;
            try (PreparedStatement ps = conn.prepareStatement(errSql)) {
                ps.setString(1, sourceType);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        itemErrors.add(new ReportError(
                            sourceType,
                            rs.getString("ITEM_ID"),
                            rs.getString("STATUS"),
                            rs.getString("MESSAGE"),
                            rs.getString("MIGRATION_TIME")
                        ));
                    }
                }
            }

            appendGlobalErrors(itemErrors, globalErrors);

            // -- VERIFICATION_LOG (optional) ---------------------------------
            long verified = -1, mismatches = -1, orphaned = -1;
            if (MigrationJournal.isTablePresent(conn, "VERIFICATION_LOG")) {
                String verSql =
                    "SELECT STATUS, COUNT(*) AS CNT FROM VERIFICATION_LOG GROUP BY STATUS";
                try (PreparedStatement ps = conn.prepareStatement(verSql)) {
                    try (ResultSet rs = ps.executeQuery()) {
                        long vTotal = 0;
                        verified = 0;
                        mismatches = 0;
                        orphaned = 0;
                        while (rs.next()) {
                            String st = rs.getString("STATUS");
                            long   cnt = rs.getLong("CNT");
                            vTotal += cnt;
                            if ("OK".equalsIgnoreCase(st) || "MATCH".equalsIgnoreCase(st))
                                verified += cnt;
                            else if ("MISMATCH".equalsIgnoreCase(st))
                                mismatches += cnt;
                            else if ("ORPHANED".equalsIgnoreCase(st))
                                orphaned += cnt;
                        }
                        if (vTotal == 0) { verified = -1; mismatches = -1; orphaned = -1; }
                    }
                } catch (SQLException e) {
                    logger.debug("VERIFICATION_LOG query failed for {}: {}", sourceType, e.getMessage());
                }

                // -- verification error details (issue 3) -------------------
                if (verified >= 0 && (mismatches > 0 || orphaned > 0)) {
                    String detailSql =
                        "SELECT ITEM_ID, STATUS, MESSAGE, VERIFIED_AT FROM VERIFICATION_LOG" +
                        " WHERE STATUS IN ('MISMATCH', 'ORPHANED')" +
                        " ORDER BY VERIFIED_AT DESC LIMIT " + MAX_ERRORS_PER_TYPE;
                    try (PreparedStatement ps = conn.prepareStatement(detailSql)) {
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                ReportError verErr = new ReportError(
                                    sourceType,
                                    rs.getString("ITEM_ID"),
                                    rs.getString("STATUS"),
                                    rs.getString("MESSAGE"),
                                    rs.getString("VERIFIED_AT")
                                );
                                if (itemErrors.size() < MAX_ERRORS_PER_TYPE) {
                                    itemErrors.add(verErr);
                                }
                                if (globalErrors.size() < MAX_ERRORS_GLOBAL) {
                                    globalErrors.add(verErr);
                                }
                            }
                        }
                    } catch (SQLException e) {
                        logger.debug("VERIFICATION_LOG detail query failed for {}: {}",
                            sourceType, e.getMessage());
                    }
                }
            }

            return new ItemTypeResult(
                sourceType, destType,
                itemTotal, itemSuccess, itemFailed, itemSkipped, itemDeleted,
                verified, mismatches, orphaned,
                List.copyOf(itemErrors)
            );
        }
    }

    // ---- helpers -----------------------------------------------------------

    private static void appendGlobalErrors(List<ReportError> perType, List<ReportError> global) {
        for (ReportError e : perType) {
            if (global.size() >= MAX_ERRORS_GLOBAL) break;
            global.add(e);
        }
    }

    /** Computes overall status using both live stats and verification data. */
    private static OverallStatus computeStatus(MigrationStats stats, List<ItemTypeResult> itemTypes) {
        if (stats.getFailedItems() > 0) return OverallStatus.FAILED;
        for (ItemTypeResult it : itemTypes) {
            if (it.mismatches() > 0) return OverallStatus.FAILED;
        }
        // ponytail: orphaned items are WARNING only when nothing else failed
        for (ItemTypeResult it : itemTypes) {
            if (it.orphaned() > 0) return OverallStatus.WARNING;
        }
        return OverallStatus.SUCCESS;
    }

    /** Stable, sortable operation ID: PREFIX_yyyyMMdd_HHmmss. */
    private static String generateOperationId(OperationType opType, long startTimeMs) {
        String prefix;
        switch (opType) {
            case VERIFICATION: prefix = "VER_"; break;
            case DELETE:       prefix = "DEL_"; break;
            default:           prefix = "MIG_"; break;  // MIGRATION, RESUME, SINGLE_PASS
        }
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss");
        return prefix + fmt.format(new java.util.Date(startTimeMs));
    }
}
