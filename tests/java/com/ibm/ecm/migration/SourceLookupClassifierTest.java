package com.ibm.ecm.migration;

public final class SourceLookupClassifierTest {
    public static void main(String[] args) {
        assertStatus(SourceLookupStatus.NOT_FOUND,
                new RuntimeException("Item not found"), "PID-1");
        assertStatus(SourceLookupStatus.NOT_FOUND,
                new RuntimeException("Object does not exist"), "PID-2");
        assertStatus(SourceLookupStatus.NOT_FOUND,
                new RuntimeException("DKC_UNKNOWN while retrieving PID-3"), "PID-3");
        assertStatus(SourceLookupStatus.NOT_FOUND,
                new RuntimeException("outer", new RuntimeException("Document no longer exists")), "PID-4");

        assertStatus(SourceLookupStatus.ERROR,
                new RuntimeException("Host not found"), "PID-5");
        assertStatus(SourceLookupStatus.ERROR,
                new RuntimeException("Connection timed out"), "PID-6");
        assertStatus(SourceLookupStatus.ERROR,
                new RuntimeException("Permission denied"), "PID-7");
        assertStatus(SourceLookupStatus.ERROR,
                new RuntimeException("DKC_UNKNOWN while retrieving another PID"), "PID-8");
        assertStatus(SourceLookupStatus.ERROR, null, "PID-9");

        System.out.println("SourceLookupClassifierTest: PASS");
    }

    private static void assertStatus(SourceLookupStatus expected, Throwable failure, String sourcePid) {
        SourceLookupStatus actual = SourceLookupClassifier.fromFailure(failure, sourcePid);
        if (actual != expected) {
            throw new AssertionError("Expected " + expected + " but got " + actual
                    + " for " + (failure == null ? "<null>" : failure.getMessage()));
        }
    }
}
