package com.ibm.ecm.migration;

public final class SourceLookupDecisionTest {
    public static void main(String[] args) {
        assertAction(SourceLookupAction.CONTINUE_VERIFICATION,
                SourceLookupDecision.decide(SourceLookupStatus.EXISTS, false));
        assertAction(SourceLookupAction.CONTINUE_VERIFICATION,
                SourceLookupDecision.decide(SourceLookupStatus.EXISTS, true));
        assertAction(SourceLookupAction.MARK_ORPHANED,
                SourceLookupDecision.decide(SourceLookupStatus.NOT_FOUND, false));
        assertAction(SourceLookupAction.CASCADE_DELETE,
                SourceLookupDecision.decide(SourceLookupStatus.NOT_FOUND, true));
        assertAction(SourceLookupAction.FAIL_WITHOUT_DELETE,
                SourceLookupDecision.decide(SourceLookupStatus.ERROR, false));
        assertAction(SourceLookupAction.FAIL_WITHOUT_DELETE,
                SourceLookupDecision.decide(SourceLookupStatus.ERROR, true));
        assertAction(SourceLookupAction.FAIL_WITHOUT_DELETE,
                SourceLookupDecision.decide(null, true));

        System.out.println("SourceLookupDecisionTest: PASS");
    }

    private static void assertAction(SourceLookupAction expected, SourceLookupAction actual) {
        if (expected != actual) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }
}
