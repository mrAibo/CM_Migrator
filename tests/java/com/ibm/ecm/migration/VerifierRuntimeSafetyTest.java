package com.ibm.ecm.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class VerifierRuntimeSafetyTest {
    public static void main(String[] args) throws Exception {
        testCascadePolicyVariants();
        testRunConfigurationPolicy();
        testRunCountersAndTerminalOutcome();
        testVerifierCoreAndCliPolicyContract();
        testStaleOkReentersDefaultWorklist();
        testTerminationOutcomes();
        testInterruptRestoration();
        testExitAndWebStatusContract();
        System.out.println("VerifierRuntimeSafetyTest: PASS");
    }

    private static void testCascadePolicyVariants() throws Exception {
        for (String setting : Arrays.asList(
                "CASCADE_DELETE_ON_MISSING=true",
                "CASCADE_DELETE_ON_MISSING=YES",
                "CASCADEDELETEONMISSING=1",
                "cascade_delete_on_missing=on")) {
            MigrationConfig config = config(setting);
            expectReason(RunTerminationException.Reason.POLICY,
                    () -> OperationalPolicy.enforceCascadeDeleteDisabled(config));
        }

        OperationalPolicy.enforceCascadeDeleteDisabled(config("THREAD_COUNT=1"));
        OperationalPolicy.enforceCascadeDeleteDisabled(config("CASCADE_DELETE_ON_MISSING=false"));
        OperationalPolicy.enforceCascadeDeleteDisabled(config("CASCADEDELETEONMISSING=off"));
    }

    private static void testRunConfigurationPolicy() throws Exception {
        OperationalPolicy.validateRunConfiguration(config(
                "SOURCE_SSID=CM\nDEST_SSID=CM"));
        OperationalPolicy.validateRunConfiguration(config(
                "SOURCE_SSID=SOURCE\nDEST_SSID=DEST"));
        OperationalPolicy.validateRunConfiguration(config(
                "SOURCE_SSID=SOURCE\nOPERATION_MODE=DELETE"));

        expectReason(RunTerminationException.Reason.POLICY,
                () -> OperationalPolicy.validateRunConfiguration(config("DEST_SSID=DEST")));
        expectReason(RunTerminationException.Reason.POLICY,
                () -> OperationalPolicy.validateRunConfiguration(config("SOURCE_SSID=SOURCE")));
        expectReason(RunTerminationException.Reason.POLICY,
                () -> OperationalPolicy.validateRunConfiguration(config(
                        "SOURCE_SSID=SOURCE\nDEST_SSID=DEST\nMIGRATE_ITEMTYPES=")));
    }

    private static void testRunCountersAndTerminalOutcome() throws Exception {
        Verifier.RunCounters first = new Verifier.RunCounters();
        first.errors.incrementAndGet();
        Verifier.RunCounters second = new Verifier.RunCounters();
        assertEquals(0, second.errors.get(), "verifier counters must be run-local");

        Verifier.requireCleanVerification(second);
        expectReason(RunTerminationException.Reason.FAILED,
                () -> Verifier.requireCleanVerification(first));

        Verifier.RunCounters sourceMissing = new Verifier.RunCounters();
        sourceMissing.sourceDeleted.incrementAndGet();
        expectReason(RunTerminationException.Reason.FAILED,
                () -> Verifier.requireCleanVerification(sourceMissing));

        Verifier.RunCounters skipped = new Verifier.RunCounters();
        skipped.skipped.incrementAndGet();
        expectReason(RunTerminationException.Reason.FAILED,
                () -> Verifier.requireCleanVerification(skipped));
    }

    private static void testVerifierCoreAndCliPolicyContract() throws Exception {
        Path config = writeConfig("CASCADE_DELETE_ON_MISSING=true");
        expectReason(RunTerminationException.Reason.POLICY,
                () -> Verifier.run(config.toString()));
        assertEquals(2, Verifier.runCli(new String[]{config.toString()}),
                "CLI policy exit code");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void testStaleOkReentersDefaultWorklist() throws Exception {
        Class journalSchema = Class.forName("com.ibm.ecm.migration.Verifier$JournalSchema");
        Class verifySchema = Class.forName("com.ibm.ecm.migration.Verifier$VerifySchema");
        java.lang.reflect.Method builder = Verifier.class.getDeclaredMethod(
                "buildWorklistSql", journalSchema, verifySchema);
        builder.setAccessible(true);
        String sql = (String) builder.invoke(null,
                Enum.valueOf(journalSchema, "OLD_AUDITLOG"),
                Enum.valueOf(verifySchema, "OLD_VERIFICATION_LOG"));

        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                "jdbc:h2:mem:verifier-freshness;DB_CLOSE_DELAY=-1");
             java.sql.Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE AUDIT_LOG (ITEM_ID VARCHAR(255) PRIMARY KEY, "
                    + "DEST_ITEM_ID VARCHAR(255), CHECKSUM VARCHAR(64), STATUS VARCHAR(20), "
                    + "MIGRATION_TIME TIMESTAMP)");
            st.execute("CREATE TABLE VERIFICATION_LOG (ITEM_ID VARCHAR(255) PRIMARY KEY, "
                    + "STATUS VARCHAR(50), VERIFIED_AT TIMESTAMP)");
            st.execute("INSERT INTO AUDIT_LOG VALUES ('item-1', 'dest-1', 'hash-1', 'SUCCESS', "
                    + "TIMESTAMP '2026-07-28 10:00:00')");
            st.execute("INSERT INTO VERIFICATION_LOG VALUES ('item-1', 'OK', "
                    + "TIMESTAMP '2026-07-28 09:00:00')");

            assertEquals(1, countRows(conn, sql),
                    "stale OK must re-enter verifier worklist after re-migration");
            st.execute("UPDATE VERIFICATION_LOG SET VERIFIED_AT = TIMESTAMP '2026-07-28 11:00:00' "
                    + "WHERE ITEM_ID = 'item-1'");
            assertEquals(0, countRows(conn, sql),
                    "fresh OK must stay out of verifier worklist");
        }
    }

    private static int countRows(java.sql.Connection conn, String sql) throws Exception {
        int count = 0;
        try (java.sql.PreparedStatement ps = conn.prepareStatement(sql);
             java.sql.ResultSet rs = ps.executeQuery()) {
            while (rs.next()) count++;
        }
        return count;
    }

    private static void testTerminationOutcomes() throws Exception {
        AtomicInteger shutdownRequests = new AtomicInteger();

        ScriptedExecutor normal = new ScriptedExecutor(true);
        WorkerTermination.Outcome normalOutcome = WorkerTermination.await(
                normal, 5, 2, shutdownRequests::incrementAndGet);
        assertFalse(normalOutcome.timedOut(), "normal completion must not time out");
        assertTrue(normalOutcome.terminated(), "normal completion must be confirmed");
        assertEquals(0, shutdownRequests.get(), "normal completion must not request shutdown");

        ScriptedExecutor grace = new ScriptedExecutor(false, true);
        WorkerTermination.Outcome graceOutcome = WorkerTermination.await(
                grace, 5, 2, shutdownRequests::incrementAndGet);
        assertTrue(graceOutcome.timedOut(), "regular timeout must remain a timeout");
        assertTrue(graceOutcome.terminated(), "grace completion must be confirmed");
        assertEquals(1, shutdownRequests.get(), "timeout must request shutdown once");

        ScriptedExecutor stuck = new ScriptedExecutor(false, false);
        WorkerTermination.Outcome stuckOutcome = WorkerTermination.await(
                stuck, 5, 2, shutdownRequests::incrementAndGet);
        assertTrue(stuckOutcome.timedOut(), "grace expiry must be a timeout");
        assertFalse(stuckOutcome.terminated(), "stuck native call must remain unconfirmed");
        assertEquals(2, shutdownRequests.get(), "each timeout must request shutdown once");
    }

    private static void testInterruptRestoration() throws Exception {
        AtomicBoolean interrupted = new AtomicBoolean(false);
        AtomicBoolean terminated = new AtomicBoolean(false);
        Thread thread = new Thread(() -> {
            ScriptedExecutor executor = new ScriptedExecutor(true);
            Thread.currentThread().interrupt();
            terminated.set(WorkerTermination.awaitGraceAfterInterrupt(executor, 2, () -> { }));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        thread.start();
        thread.join();
        assertFalse(terminated.get(), "an already interrupted wait cannot confirm termination");
        assertTrue(interrupted.get(), "interrupt flag must be restored");
    }

    private static void testExitAndWebStatusContract() {
        assertTermination(RunTerminationException.Reason.POLICY, 2, "POLICY_REFUSED");
        assertTermination(RunTerminationException.Reason.TIMEOUT, 124, "TIMED_OUT");
        assertTermination(RunTerminationException.Reason.INTERRUPTED, 130, "INTERRUPTED");
        assertTermination(RunTerminationException.Reason.FAILED, 1, "FAILED");
    }

    private static void assertTermination(RunTerminationException.Reason reason,
                                          int exitCode,
                                          String webStatus) {
        RunTerminationException failure = new RunTerminationException(
                reason, "safe message", true, null);
        assertEquals(exitCode, failure.getExitCode(), reason + " exit code");
        assertEquals(webStatus, failure.getWebStatus(), reason + " WebGUI status");
        assertTrue(failure.isTerminationConfirmed(), reason + " termination flag");
    }

    private static MigrationConfig config(String line) throws Exception {
        return new MigrationConfig(writeConfig(line).toString());
    }

    private static Path writeConfig(String line) throws Exception {
        Path file = Files.createTempFile("cm-verifier-policy-", ".properties");
        String mapping = line.contains("MIGRATE_ITEMTYPES=")
                ? ""
                : "MIGRATE_ITEMTYPES=SOURCE:DEST\n";
        Files.writeString(file, line + "\n" + mapping + "THREAD_COUNT=1\n");
        file.toFile().deleteOnExit();
        return file;
    }

    private static void expectReason(RunTerminationException.Reason expected,
                                     CheckedRunnable runnable) throws Exception {
        try {
            runnable.run();
            throw new AssertionError("Expected " + expected);
        } catch (RunTerminationException actual) {
            assertEquals(expected, actual.getReason(), "termination reason");
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertFalse(boolean value, String message) {
        assertTrue(!value, message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static final class ScriptedExecutor extends AbstractExecutorService {
        private final Deque<Boolean> terminations;
        private volatile boolean shutdown;

        ScriptedExecutor(Boolean... terminations) {
            this.terminations = new ArrayDeque<>(List.of(terminations));
        }

        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && terminations.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            if (Thread.interrupted()) throw new InterruptedException("test interrupt");
            return terminations.isEmpty() || terminations.removeFirst();
        }
        @Override public void execute(Runnable command) { throw new UnsupportedOperationException(); }
    }
}
