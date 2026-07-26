package com.ibm.ecm.migration;

/**
 * Integration test for SINGLE_PASS cursor-discovery lifecycle rendering.
 *
 * Verifies that the OperatorConsole dashboard correctly handles:
 *  - unknown total with streaming indicator (no spurious 0% / no ETA)
 *  - multiple discovery phases where discovered < processed while streaming
 *  - cursor end → streaming stops, total becomes known, progress bar appears
 *  - queue drain → COMPLETED
 *  - processed never exceeds 100%
 *
 * Run: java -cp "tests/build:target" com.ibm.ecm.migration.SinglePassTransitionTest
 */
public final class SinglePassTransitionTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== SinglePassTransitionTest ===\n");

        testStreamingShowsActivityNotPercent();
        testStreamingHidesEta();
        testStreamingToKnownTransition();
        testPercentClampedAt100();
        testDiscoveryPhasesStreaming();
        testCompletedAfterDrain();

        System.out.println("\n=== Results: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) System.exit(1);
    }

    // ── Helpers ───────────────────────────────────────────────────────

    static OperatorConsole.Snapshot streamingSnap() {
        OperatorConsole.Snapshot s = new OperatorConsole.Snapshot();
        s.state = OperatorConsole.RunState.RUNNING;
        s.phase = OperatorConsole.Phase.MIGRATING;
        s.mode = "MIGRATE";
        s.strategy = "SINGLE_PASS";
        s.sourceSSID = "LSDB";
        s.destSSID = "AOKHB-ITU";
        s.total = 0;          // unknown during streaming
        s.streaming = true;
        s.discovered = 5000;
        s.processed = 4200;
        s.success = 4150;
        s.failed = 12;
        s.skipped = 38;
        s.deleted = 0;
        s.currentRate = 45.2;
        s.averageRate = 40.1;
        s.elapsedMs = 60000;
        s.eta = "--:--";
        s.queueDepth = 800;
        s.queueCapacity = 10000;
        s.journalQueueDepth = 0;
        s.journalQueueCapacity = 100000;
        s.journalPersisted = -1;
        s.journalHealth = OperatorConsole.JournalHealth.HEALTHY;
        s.configuredWorkers = 4;
        return s;
    }

    static String renderFull(OperatorConsole.Snapshot s) {
        StringBuilder buf = new StringBuilder();
        OperatorConsole.render(s, buf);
        return buf.toString();
    }

    static void pass(String name) { System.out.println("  PASS: " + name); passed++; }
    static void fail(String name, String detail) {
        System.out.println("  FAIL: " + name + " — " + detail);
        failed++;
    }
    static void check(String name, boolean cond, String detail) {
        if (cond) pass(name); else fail(name, detail);
    }

    // ── Tests ─────────────────────────────────────────────────────────

    /** Streaming must NOT show a 0.0% (we don't know the total). */
    static void testStreamingShowsActivityNotPercent() {
        OperatorConsole.Snapshot s = streamingSnap();
        s.total = 0;
        s.streaming = true;
        s.processed = 0;
        String out = renderFull(s);
        check("streaming: no 0.0% when total unknown",
            !out.contains("0.0%"),
            "got percent when total is unknown");
        check("streaming: contains 'streaming' indicator",
            out.contains("streaming"),
            "missing streaming indicator");
    }

    /** When streaming, ETA should not be shown (or shown as --:--). */
    static void testStreamingHidesEta() {
        OperatorConsole.Snapshot s = streamingSnap();
        s.total = 0;
        s.streaming = true;
        s.eta = "--:--";
        String out = renderFull(s);
        // "ETA:" appears in the speed line, but should not show a real time
        check("streaming: ETA is --:--",
            out.contains("--:--"),
            "ETA must be placeholder during streaming");
    }

    /** When cursor ends and total becomes known, switch from streaming to progress bar. */
    static void testStreamingToKnownTransition() {
        OperatorConsole.Snapshot s = streamingSnap();
        s.total = 0;
        s.streaming = true;
        String unknown = renderFull(s);

        // Cursor exhausts → total known, streaming stops
        s.total = 10000;
        s.streaming = false;
        s.processed = 8000;
        String known = renderFull(s);

        check("transition: unknown shows streaming",
            unknown.contains("streaming"),
            "should show 'streaming'");
        check("transition: known shows progress bar",
            known.contains("80.0%"),
            "should show percent after total known");
    }

    /** processed > total → percent clamped at 100%. */
    static void testPercentClampedAt100() {
        OperatorConsole.Snapshot s = streamingSnap();
        s.total = 5000;
        s.streaming = false;
        s.processed = 7500; // exceeds total during final drain
        String out = renderFull(s);
        check("processed > total: clamped at 100.0%",
            out.contains("100.0%"),
            "must not exceed 100% display");
    }

    /** Simulate multiple discovery phases: discovered grows but processed stays ahead. */
    static void testDiscoveryPhasesStreaming() {
        OperatorConsole.Snapshot s = streamingSnap();
        s.total = 0;
        s.streaming = true;

        // Phase 1: just started, few discovered
        s.discovered = 100;
        s.processed = 0;
        String phase1 = renderFull(s);
        check("phase1: streaming indicator present",
            phase1.contains("streaming"),
            "phase1");

        // Phase 2: more discovered, processing ongoing
        s.discovered = 5000;
        s.processed = 4200;
        String phase2 = renderFull(s);
        check("phase2: streaming indicator present",
            phase2.contains("streaming"),
            "phase2");
        check("phase2: processed count visible",
            phase2.contains("4.200"),
            "phase2 should show processed=4.200");

        // Phase 3: discovery nearly done
        s.discovered = 9800;
        s.processed = 8500;
        String phase3 = renderFull(s);
        check("phase3: still streaming before cursor exhausts",
            phase3.contains("streaming"),
            "phase3 should still show streaming");
    }

    /** After drain, COMPLETED state is shown. */
    static void testCompletedAfterDrain() {
        OperatorConsole.Snapshot s = new OperatorConsole.Snapshot();
        s.state = OperatorConsole.RunState.COMPLETED;
        s.phase = OperatorConsole.Phase.FINALIZING;
        s.mode = "MIGRATE";
        s.strategy = "SINGLE_PASS";
        s.total = 10000;
        s.discovered = 10000;
        s.processed = 10000;
        s.success = 9980;
        s.failed = 20;
        s.skipped = 0;
        s.deleted = 0;
        s.streaming = false;
        s.elapsedMs = 300000;
        s.configuredWorkers = 4;

        String out = renderFull(s);
        check("completed: shows COMPLETED badge",
            out.contains("COMPLETED"),
            "COMPLETED not found in output");
        check("completed: shows 100.0%",
            out.contains("100.0%"),
            "100.0% not found");
    }
}
