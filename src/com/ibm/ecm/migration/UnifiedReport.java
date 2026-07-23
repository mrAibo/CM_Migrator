/*
 * Projekt: CM Migrator 2.2.1.
 *
 * Unified reporting data model - replaces ProtocolData + Builder.
 * Single flat record covering migration, verification, and delete operations.
 * No framework dependencies; plain JDK 17 records + enums.
 */
package com.ibm.ecm.migration;

import java.util.List;

/** Operation type for the report. */
enum OperationType {
    MIGRATION, VERIFICATION, DELETE;

    /** Resolve from config OPERATION_MODE string. */
    static OperationType fromMode(String mode) {
        if (mode == null) return MIGRATION;
        switch (mode.toUpperCase()) {
            case "VERIFY":  return VERIFICATION;
            case "DELETE":  return DELETE;
            default:        return MIGRATION;
        }
    }
}

/** Overall health of the operation. */
enum OverallStatus {
    SUCCESS, FAILED, WARNING
}

/** Single error entry - capped at 5 per item-type, 50 globally. */
record ReportError(
    String itemType,
    String itemId,
    String status,
    String message,
    String timestamp
) {}

/** Per-item-type breakdown with optional verification stats. */
record ItemTypeResult(
    String sourceType,
    String destType,
    long total,
    long success,
    long failed,
    long skipped,
    long deleted,
    long verified,      // -1 if verification not run
    long mismatches,    // -1 if verification not run
    long orphaned,      // -1 if verification not run
    List<ReportError> errors  // capped at 5
) {
    /** Factory for item-types whose journal could not be read. */
    static ItemTypeResult unreachable(String sourceType, String destType) {
        return new ItemTypeResult(sourceType, destType,
            -1, -1, -1, -1, -1, -1, -1, -1, List.of());
    }

    /** Factory when no verification data exists. */
    static ItemTypeResult withoutVerification(
        String sourceType, String destType,
        long total, long success, long failed, long skipped, long deleted,
        List<ReportError> errors
    ) {
        return new ItemTypeResult(sourceType, destType,
            total, success, failed, skipped, deleted,
            -1, -1, -1, errors);
    }
}

/**
 * Unified report for any operation (migrate / verify / delete).
 * Populated by {@link ReportDataCollector} - no Builder needed.
 */
public record UnifiedReport(
    String operationId,
    OperationType operationType,
    OverallStatus status,
    long startTimeMs,
    long endTimeMs,
    String sourceSSID,
    String destSSID,
    long total,
    long processed,
    long success,
    long failed,
    long skipped,
    long deleted,
    double throughputPerSec,
    double successRate,
    List<ItemTypeResult> itemTypes,
    List<ReportError> errors
) {
    /** Formatted duration string e.g. "2h 15m 30s". */
    public String formattedDuration() {
        long sec = (endTimeMs - startTimeMs) / 1000;
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        if (h > 0) return String.format("%dh %dm %ds", h, m, s);
        if (m > 0) return String.format("%dm %ds", m, s);
        return String.format("%ds", s);
    }
}
