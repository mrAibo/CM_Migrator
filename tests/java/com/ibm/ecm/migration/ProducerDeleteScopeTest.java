package com.ibm.ecm.migration;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public final class ProducerDeleteScopeTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        check("empty filter deletes all configured type items",
                "/TYPE_A".equals(Producer.buildQuery("TYPE_A", "")));
        check("blank filter deletes all configured type items",
                "/TYPE_A".equals(Producer.buildQuery("TYPE_A", "   ")));
        check("predicate limits configured type items",
                "/TYPE_A[CREATETS > \"2026-01-01\"]".equals(
                        Producer.buildQuery("TYPE_A", "CREATETS > \"2026-01-01\"")));
        checkThrows("absolute query cannot escape configured item type",
                () -> Producer.buildQuery("TYPE_A", "/TYPE_B"));
        // ponytail: PRODUCER_COUNT_STRATEGY removed — always two-pass SDK_CURSOR
        testIgnoredAttributes();
        testRunCacheReset();
        OperationalPolicy.requireNoDeleteResiduals(Map.of("TYPE_A", 0L));
        checkPolicyThrows("delete residuals fail closed",
                () -> OperationalPolicy.requireNoDeleteResiduals(Map.of("TYPE_A", 2L)));

        System.out.println("ProducerDeleteScopeTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void testIgnoredAttributes() throws Exception {
        Path configFile = Files.createTempFile("cm-migrator-completeness-", ".properties");
        try {
            Files.writeString(configFile,
                    "SOURCE_SSID=SRC\nDEST_SSID=DST\nMIGRATE_ITEMTYPES=A:B\n"
                            + "MIGRATION_IGNORED_ATTRIBUTES=LegacyFlag, LegacyCode,LegacyFlag\n");
            Set<String> ignored = new MigrationConfig(configFile.toString())
                    .getIgnoredMigrationAttributes();
            check("ignored attributes are trimmed and deduplicated",
                    ignored.equals(Set.of("LegacyFlag", "LegacyCode")));
        } finally {
            Files.deleteIfExists(configFile);
        }
    }

    @SuppressWarnings("unchecked")
    private static void testRunCacheReset() throws Exception {
        Field field = ItemMigrator.class.getDeclaredField("ATTR_CACHE");
        field.setAccessible(true);
        Map<String, Object> cache = (Map<String, Object>) field.get(null);
        cache.put("stale-type", new java.util.concurrent.ConcurrentHashMap<>());
        ItemMigrator.clearRunCache();
        check("attribute cache is isolated per run", cache.isEmpty());
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

    private static void checkPolicyThrows(String name, PolicyAction action) {
        try {
            action.run();
            failed++;
            System.err.println("FAIL: " + name);
        } catch (RunTerminationException expected) {
            passed++;
        }
    }

    @FunctionalInterface
    private interface PolicyAction {
        void run() throws RunTerminationException;
    }
}
