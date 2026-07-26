package com.ibm.ecm.migration;

public final class ProducerDeleteScopeTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        check("empty filter deletes all configured type items",
                "/TYPE_A".equals(Producer.buildQuery("TYPE_A", "")));
        check("blank filter deletes all configured type items",
                "/TYPE_A".equals(Producer.buildQuery("TYPE_A", "   ")));
        check("predicate limits configured type items",
                "/TYPE_A[CREATETS > \"2026-01-01\"]".equals(
                        Producer.buildQuery("TYPE_A", "CREATETS > \"2026-01-01\"")));
        checkThrows("absolute query cannot escape configured item type",
                () -> Producer.buildQuery("TYPE_A", "/TYPE_B"));
        check("SINGLE_PASS selects streaming discovery",
                Producer.usesSinglePass("SINGLE_PASS"));
        check("SDK_CURSOR selects counted discovery",
                !Producer.usesSinglePass("SDK_CURSOR"));
        checkThrows("unknown strategy fails closed",
                () -> Producer.usesSinglePass("UNKNOWN"));

        System.out.println("ProducerDeleteScopeTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + name);
        }
    }

    private static void checkThrows(String name, Runnable action) {
        try {
            action.run();
            failed++;
            System.err.println("FAIL: " + name);
        } catch (IllegalArgumentException expected) {
            passed++;
        }
    }
}
