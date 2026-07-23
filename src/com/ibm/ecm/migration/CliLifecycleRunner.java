package com.ibm.ecm.migration;

/**
 * Shared CLI exit/cleanup contract &mdash; dependency-free so tests and the
 * real Main can exercise the identical production path even when lib/ is
 * absent in CI.
 */
final class CliLifecycleRunner {

    /** ponytail: same functional shape as Main.CliOperation so no duplication. */
    @FunctionalInterface
    interface CliOperation {
        void run() throws Exception;
    }

    private CliLifecycleRunner() {
    }

    /**
     * Registers the lifecycle exactly once, runs the operation, and
     * guarantees {@link CliShutdownLifecycle#finish(boolean)} runs before
     * the exit code is returned.  Does not terminate the VM.
     */
    static int executeCli(
            CliShutdownLifecycle lifecycle,
            CliOperation operation) {
        boolean terminationConfirmed = true;
        int exitCode = 0;

        try {
            lifecycle.register();
            operation.run();
        } catch (RunTerminationException e) {
            terminationConfirmed = e.isTerminationConfirmed();
            exitCode = e.getExitCode();
        } catch (Exception e) {
            terminationConfirmed = false;
            exitCode = 1;
        } finally {
            lifecycle.finish(terminationConfirmed);
        }

        return exitCode;
    }
}
