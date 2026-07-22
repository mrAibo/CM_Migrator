/*
 * Round 13A — Marker for deterministic, non-transient migration errors.
 * Thrown for cases where retrying the same item would always fail:
 *   - safe stream upload method missing for a >2 GB part
 *   - negative/overflowed expectedSize
 *   - partial stream upload detected (consumed bytes != expectedSize)
 *   - large temp dir unavailable
 * Consumer's transient-classifier explicitly recognises this type and
 * skips the batch-splitter / single-item retry loop for these errors.
 */
package com.ibm.ecm.migration;

public class PermanentMigrationException extends Exception {
    private static final long serialVersionUID = 1L;
    public PermanentMigrationException(String message) { super(message); }
    public PermanentMigrationException(String message, Throwable cause) { super(message, cause); }
}
