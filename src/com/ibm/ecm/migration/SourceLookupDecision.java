package com.ibm.ecm.migration;

/**
 * Converts a source lookup result into the only allowed verifier action.
 */
public final class SourceLookupDecision {
    private SourceLookupDecision() {
    }

    public static SourceLookupAction decide(SourceLookupStatus status, boolean cascadeDeleteEnabled) {
        if (status == null || status == SourceLookupStatus.ERROR) {
            return SourceLookupAction.FAIL_WITHOUT_DELETE;
        }

        if (status == SourceLookupStatus.EXISTS) {
            return SourceLookupAction.CONTINUE_VERIFICATION;
        }

        return cascadeDeleteEnabled
                ? SourceLookupAction.CASCADE_DELETE
                : SourceLookupAction.MARK_ORPHANED;
    }
}
