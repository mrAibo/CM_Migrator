package com.ibm.ecm.migration;

/**
 * Shared CLI exit/cleanup contract &mdash; dependency-free so tests and the
 * real Main can exercise the identical production path even when lib/ is
 * absent in CI.
 */
final class CliLifecycleRunner {

    @FunctionalInterface
    interface CliOperation {
        void run() throws Exception;
    }

    /** Immutable result carrying exit code and the preserved failure (if any). */
    static final class CliRunResult {
        final int exitCode;
        final Exception failure;
        final boolean terminationConfirmed;

        CliRunResult(int exitCode, Exception failure, boolean terminationConfirmed) {
            this.exitCode = exitCode;
            this.failure = failure;
            this.terminationConfirmed = terminationConfirmed;
        }

        int exitCode()   { return exitCode; }
        Exception failure() { return failure; }
        boolean terminationConfirmed() { return terminationConfirmed; }
    }

    private CliLifecycleRunner() {
    }

    /**
     * Registers the lifecycle exactly once, runs the operation, and
     * guarantees {@link CliShutdownLifecycle#finish(boolean)} runs before
     * the result is returned.  Does not terminate the VM, does not log.
     */
    static CliRunResult executeCli(
            CliShutdownLifecycle lifecycle,
            CliOperation operation) {
        boolean terminationConfirmed = true;
        int exitCode = 0;
        Exception failure = null;

        try {
            lifecycle.register();
            operation.run();
        } catch (RunTerminationException e) {
            failure = e;
            terminationConfirmed = e.isTerminationConfirmed();
            exitCode = e.getExitCode();
        } catch (Exception e) {
            failure = e;
            terminationConfirmed = false;
            exitCode = 1;
        } finally {
            lifecycle.finish(terminationConfirmed);
        }

        return new CliRunResult(exitCode, failure, terminationConfirmed);
    }
}
