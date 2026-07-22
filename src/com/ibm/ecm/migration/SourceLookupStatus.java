package com.ibm.ecm.migration;

/**
 * Result of a source-item existence lookup.
 *
 * <p>Only {@link #NOT_FOUND} may authorize an orphan or cascade-delete path.
 * {@link #ERROR} is deliberately distinct and must always fail closed.</p>
 */
public enum SourceLookupStatus {
    EXISTS,
    NOT_FOUND,
    ERROR
}
