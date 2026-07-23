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
}
