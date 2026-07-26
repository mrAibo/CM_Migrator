package com.ibm.ecm.migration;

/** Enforces operational safety policy after the effective configuration is loaded. */
final class OperationalPolicy {
    private OperationalPolicy() {
    }

    static void enforceCascadeDeleteDisabled(MigrationConfig config)
            throws RunTerminationException {
        if (config.isCascadeDeleteOnMissing()) {
            throw new RunTerminationException(
                    RunTerminationException.Reason.POLICY,
                    "Security policy refused the run: CASCADE_DELETE_ON_MISSING must be disabled.",
                    true,
                    null);
        }
    }

    static void validateRunConfiguration(MigrationConfig config)
            throws RunTerminationException {
        requireNonBlank("SOURCE_SSID", config.getSourceSSID());
        if (!"DELETE".equalsIgnoreCase(config.getOperationMode())) {
            requireNonBlank("DEST_SSID", config.getDestSSID());
        }
        if (config.getItemTypeMapping().isEmpty()) {
            throw policyFailure("MIGRATE_ITEMTYPES must contain at least one explicit mapping.");
        }
        for (java.util.Map.Entry<String, String> mapping : config.getItemTypeMapping().entrySet()) {
            requireNonBlank("MIGRATE_ITEMTYPES source", mapping.getKey());
            requireNonBlank("MIGRATE_ITEMTYPES destination", mapping.getValue());
        }
    }

    private static void requireNonBlank(String name, String value)
            throws RunTerminationException {
        if (value == null || value.trim().isEmpty()) {
            throw policyFailure(name + " must not be blank.");
        }
    }

    private static RunTerminationException policyFailure(String message) {
        return new RunTerminationException(
                RunTerminationException.Reason.POLICY,
                "Security policy refused the run: " + message,
                true,
                null);
    }
}
