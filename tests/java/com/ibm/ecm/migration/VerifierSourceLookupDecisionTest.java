package com.ibm.ecm.migration;

public final class VerifierSourceLookupDecisionTest {
    public static void main(String[] args) {
        assertDelete(false, SourceLookupStatus.EXISTS, true);
        assertDelete(false, SourceLookupStatus.EXISTS, false);
        assertDelete(true, SourceLookupStatus.NOT_FOUND, true);
        assertDelete(false, SourceLookupStatus.NOT_FOUND, false);
        assertDelete(false, SourceLookupStatus.ERROR, true);
        assertDelete(false, SourceLookupStatus.ERROR, false);
        assertDelete(false, null, true);

        System.out.println("VerifierSourceLookupDecisionTest: PASS");
    }

    private static void assertDelete(boolean expected, SourceLookupStatus status, boolean enabled) {
        boolean actual = Verifier.shouldCascadeDelete(status, enabled);
        if (actual != expected) {
            throw new AssertionError("Expected delete=" + expected + " but got " + actual
                    + " for status=" + status + ", enabled=" + enabled);
        }
    }
}
