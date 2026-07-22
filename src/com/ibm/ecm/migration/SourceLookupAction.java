package com.ibm.ecm.migration;

/**
 * Action selected after checking the source object state.
 */
public enum SourceLookupAction {
    CONTINUE_VERIFICATION,
    MARK_ORPHANED,
    CASCADE_DELETE,
    FAIL_WITHOUT_DELETE
}
