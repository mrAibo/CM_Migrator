/*
 * Projekt: CM Migrator 2.2.1.
 *
 * Unified reporting data model - replaces ProtocolData + Builder.
 * Single flat model covering migration, verification, and delete operations.
 * No framework dependencies; plain JDK 11 compatible classes + enums.
 */
package com.ibm.ecm.migration;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

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
final class ReportError {
    private final String itemType;
    private final String itemId;
    private final String status;
    private final String message;
    private final String timestamp;

    ReportError(String itemType, String itemId, String status,
                String message, String timestamp) {
        this.itemType = itemType;
        this.itemId = itemId;
        this.status = status;
        this.message = message;
        this.timestamp = timestamp;
    }

    public String itemType()  { return itemType; }
    public String itemId()    { return itemId; }
    public String status()    { return status; }
    public String message()   { return message; }
    public String timestamp() { return timestamp; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReportError)) return false;
        ReportError that = (ReportError) o;
        return Objects.equals(itemType, that.itemType)
            && Objects.equals(itemId, that.itemId)
            && Objects.equals(status, that.status)
            && Objects.equals(message, that.message)
            && Objects.equals(timestamp, that.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(itemType, itemId, status, message, timestamp);
    }

    @Override
    public String toString() {
        return "ReportError[itemType=" + itemType
            + ", itemId=" + itemId
            + ", status=" + status
            + ", message=" + message
            + ", timestamp=" + timestamp + "]";
    }
}

/** Per-item-type breakdown with optional verification stats. */
final class ItemTypeResult {
    private final String sourceType;
    private final String destType;
    private final long total;
    private final long success;
    private final long failed;
    private final long skipped;
    private final long deleted;
    private final long verified;    // -1 if verification not run
    private final long mismatches;  // -1 if verification not run
    private final long orphaned;    // -1 if verification not run
    private final List<ReportError> errors;  // capped at 5

    ItemTypeResult(String sourceType, String destType,
                   long total, long success, long failed, long skipped, long deleted,
                   long verified, long mismatches, long orphaned, List<ReportError> errors) {
        this.sourceType = sourceType;
        this.destType = destType;
        this.total = total;
        this.success = success;
        this.failed = failed;
        this.skipped = skipped;
        this.deleted = deleted;
        this.verified = verified;
        this.mismatches = mismatches;
        this.orphaned = orphaned;
        this.errors = Collections.unmodifiableList(errors);
    }

    public String sourceType()  { return sourceType; }
    public String destType()    { return destType; }
    public long total()         { return total; }
    public long success()       { return success; }
    public long failed()        { return failed; }
    public long skipped()       { return skipped; }
    public long deleted()       { return deleted; }
    public long verified()      { return verified; }
    public long mismatches()    { return mismatches; }
    public long orphaned()      { return orphaned; }
    public List<ReportError> errors() { return errors; }

    /** Factory for item-types whose journal could not be read. */
    static ItemTypeResult unreachable(String sourceType, String destType) {
        return new ItemTypeResult(sourceType, destType,
            -1, -1, -1, -1, -1, -1, -1, -1, Collections.emptyList());
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ItemTypeResult)) return false;
        ItemTypeResult that = (ItemTypeResult) o;
        return total == that.total
            && success == that.success
            && failed == that.failed
            && skipped == that.skipped
            && deleted == that.deleted
            && verified == that.verified
            && mismatches == that.mismatches
            && orphaned == that.orphaned
            && Objects.equals(sourceType, that.sourceType)
            && Objects.equals(destType, that.destType)
            && Objects.equals(errors, that.errors);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceType, destType, total, success, failed, skipped, deleted,
            verified, mismatches, orphaned, errors);
    }

    @Override
    public String toString() {
        return "ItemTypeResult[sourceType=" + sourceType
            + ", destType=" + destType
            + ", total=" + total
            + ", success=" + success
            + ", failed=" + failed
            + ", skipped=" + skipped
            + ", deleted=" + deleted
            + ", verified=" + verified
            + ", mismatches=" + mismatches
            + ", orphaned=" + orphaned
            + ", errors=" + errors + "]";
    }
}

/**
 * Unified report for any operation (migrate / verify / delete).
 * Populated by {@link ReportDataCollector} - no Builder needed.
 */
public final class UnifiedReport {
    private final String operationId;
    private final OperationType operationType;
    private final OverallStatus status;
    private final long startTimeMs;
    private final long endTimeMs;
    private final String sourceSSID;
    private final String destSSID;
    private final long total;
    private final long processed;
    private final long success;
    private final long failed;
    private final long skipped;
    private final long deleted;
    private final double throughputPerSec;
    private final double successRate;
    private final List<ItemTypeResult> itemTypes;
    private final List<ReportError> errors;

    public UnifiedReport(String operationId, OperationType operationType, OverallStatus status,
                         long startTimeMs, long endTimeMs, String sourceSSID, String destSSID,
                         long total, long processed, long success, long failed, long skipped, long deleted,
                         double throughputPerSec, double successRate,
                         List<ItemTypeResult> itemTypes, List<ReportError> errors) {
        this.operationId = operationId;
        this.operationType = operationType;
        this.status = status;
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
        this.sourceSSID = sourceSSID;
        this.destSSID = destSSID;
        this.total = total;
        this.processed = processed;
        this.success = success;
        this.failed = failed;
        this.skipped = skipped;
        this.deleted = deleted;
        this.throughputPerSec = throughputPerSec;
        this.successRate = successRate;
        this.itemTypes = Collections.unmodifiableList(itemTypes);
        this.errors = Collections.unmodifiableList(errors);
    }

    public String operationId()       { return operationId; }
    public OperationType operationType() { return operationType; }
    public OverallStatus status()     { return status; }
    public long startTimeMs()         { return startTimeMs; }
    public long endTimeMs()           { return endTimeMs; }
    public String sourceSSID()        { return sourceSSID; }
    public String destSSID()          { return destSSID; }
    public long total()               { return total; }
    public long processed()           { return processed; }
    public long success()             { return success; }
    public long failed()              { return failed; }
    public long skipped()             { return skipped; }
    public long deleted()             { return deleted; }
    public double throughputPerSec()  { return throughputPerSec; }
    public double successRate()       { return successRate; }
    public List<ItemTypeResult> itemTypes() { return itemTypes; }
    public List<ReportError> errors()      { return errors; }

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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UnifiedReport)) return false;
        UnifiedReport that = (UnifiedReport) o;
        return startTimeMs == that.startTimeMs
            && endTimeMs == that.endTimeMs
            && total == that.total
            && processed == that.processed
            && success == that.success
            && failed == that.failed
            && skipped == that.skipped
            && deleted == that.deleted
            && Double.compare(that.throughputPerSec, throughputPerSec) == 0
            && Double.compare(that.successRate, successRate) == 0
            && Objects.equals(operationId, that.operationId)
            && operationType == that.operationType
            && status == that.status
            && Objects.equals(sourceSSID, that.sourceSSID)
            && Objects.equals(destSSID, that.destSSID)
            && Objects.equals(itemTypes, that.itemTypes)
            && Objects.equals(errors, that.errors);
    }

    @Override
    public int hashCode() {
        return Objects.hash(operationId, operationType, status, startTimeMs, endTimeMs,
            sourceSSID, destSSID, total, processed, success, failed, skipped, deleted,
            throughputPerSec, successRate, itemTypes, errors);
    }

    @Override
    public String toString() {
        return "UnifiedReport[operationId=" + operationId
            + ", operationType=" + operationType
            + ", status=" + status
            + ", total=" + total
            + ", success=" + success
            + ", failed=" + failed
            + ", skipped=" + skipped
            + ", deleted=" + deleted + "]";
    }
}
