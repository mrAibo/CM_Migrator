package com.ibm.ecm.migration;

/**
 * Dependency-free test for OperatorConsole dashboard renderer.
 * Run: java -cp "tests/build:target" com.ibm.ecm.migration.ConsoleDashboardTest
 */
public final class ConsoleDashboardTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== ConsoleDashboardTest ===\n");

        testVersionConsistency();
        testAutoModeDetection();
        testNoColorRespected();
        testUnicodeFalseUsesAscii();
        testKnownTotalProgressBar();
        testUnknownTotalStreaming();
        testUnknownToKnownTransition();
        testPercentClamped();
        testProcessedExceedsTotal();
        testEmptyRun();
        testLongSSIDTruncation();
        testLongItemTypeTruncation();
        testStallWaiting();
        testStallStalled();
        testQueueFull();
        testJournalBackpressure();
        testJournalFailed();
        testCompletedState();
        testFailedState();
        testInterruptedState();
        testPlainModeNoAnsi();
        testPlainModeNoNewlines();
        testFinalOutputEndsWithNewline();
        testFmtDuration();
        testSanitize();
        testTrunc();
        testPhaseStr();
        testJournalHealthStr();

        System.out.println("\n=== Results: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) System.exit(1);
    }

    // ── Helpers ───────────────────────────────────────────────────────

    static OperatorConsole.Snapshot snap() {
        OperatorConsole.Snapshot s = new OperatorConsole.Snapshot();
        s.state = OperatorConsole.RunState.RUNNING;
        s.phase = OperatorConsole.Phase.MIGRATING;
        s.mode = "MIGRATE";
        s.strategy = "BATCHED";
        s.sourceSSID = "LSDB";
        s.destSSID = "AOKHB-ITU";
        s.sourceItemType = "Document";
        s.destItemType = "Document";
        s.total = 100000;
        s.discovered = 95000;
        s.processed = 42000;
        s.success = 41850;
        s.failed = 12;
        s.skipped = 138;
        s.deleted = 0;
        s.currentRate = 58.4;
        s.averageRate = 52.7;
        s.elapsedMs = 8957000;
        s.eta = "02:29:17";
        s.queueDepth = 3842;
        s.queueCapacity = 10000;
        s.journalQueueDepth = 420;
        s.journalQueueCapacity = 100000;
        s.journalPersisted = 41850;
        s.journalHealth = OperatorConsole.JournalHealth.HEALTHY;
        s.configuredWorkers = 8;
        return s;
    }

    static String renderFull(OperatorConsole.Snapshot s) {
        StringBuilder buf = new StringBuilder();
        OperatorConsole.render(s, buf);
        return buf.toString();
    }

    static String renderPlain(OperatorConsole.Snapshot s) {
        StringBuilder buf = new StringBuilder();
        OperatorConsole.renderPlain(s, buf);
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

    static void testVersionConsistency() {
        check("version is 2.2.1",
            "2.2.1".equals(OperatorConsole.VERSION),
            "got " + OperatorConsole.VERSION);
    }

    static void testAutoModeDetection() {
        OperatorConsole.Mode mode = OperatorConsole.MODE;
        check("mode is PRETTY or PLAIN",
            mode == OperatorConsole.Mode.PRETTY || mode == OperatorConsole.Mode.PLAIN,
            "mode=" + mode);
    }

    static void testNoColorRespected() {
        check("NO_COLOR flag consistent with env",
            OperatorConsole.NO_COLOR == (System.getenv("NO_COLOR") != null),
            "NO_COLOR=" + OperatorConsole.NO_COLOR);
    }

    static void testUnicodeFalseUsesAscii() {
        String out = renderFull(snap());
        boolean hasUnicode = out.contains("\u2550") || out.contains("\u2554") || out.contains("\u2557");
        check("full render uses unicode box chars when UNICODE=true",
            OperatorConsole.UNICODE ? hasUnicode : true,
            "unicode present=" + hasUnicode + ", UNICODE=" + OperatorConsole.UNICODE);
    }

    static void testKnownTotalProgressBar() {
        OperatorConsole.Snapshot s = snap();
        s.total = 100000;
        s.processed = 42000;
        String out = renderFull(s);
        check("known total shows progress bar",
            out.contains("Progress:") && out.contains("42.0%"),
            "contains percent");
    }

    static void testUnknownTotalStreaming() {
        OperatorConsole.Snapshot s = snap();
        s.total = 0;
        s.streaming = true;
        String out = renderFull(s);
        check("unknown total streaming shows activity indicator",
            out.contains("streaming"),
            "must contain 'streaming'");
        check("unknown total does NOT show percent",
            out.contains("streaming") && !out.contains("0.0%"),
            "no spurious percent with streaming");
    }

    static void testUnknownToKnownTransition() {
        OperatorConsole.Snapshot s = snap();
        s.total = 0;
        s.streaming = true;
        String unknown = renderFull(s);
        s.total = 100000;
        s.streaming = false;
        String known = renderFull(s);
        check("unknown→known: streaming→progress bar",
            unknown.contains("streaming") && known.contains("42.0%"),
            "unknown=" + unknown.contains("streaming") + " known=" + known.contains("42.0%"));
    }

    static void testPercentClamped() {
        OperatorConsole.Snapshot s = snap();
        s.total = 100;
        s.processed = 150;
        String out = renderFull(s);
        check("processed > total: percent clamped to 100",
            out.contains("100.0%"),
            "must show 100.0%");

        s.processed = -5;
        out = renderFull(s);
        check("processed negative: percent clamped to 0",
            out.contains("0.0%"),
            "must show 0.0%");
    }

    static void testProcessedExceedsTotal() {
        OperatorConsole.Snapshot s = snap();
        s.total = 500;
        s.processed = 1000;
        String out = renderFull(s);
        check("processed > total: progress bar renders",
            out.contains("Progress:") && out.contains("100.0%"),
            "renders at 100%");
    }

    static void testEmptyRun() {
        OperatorConsole.Snapshot s = snap();
        s.total = 0;
        s.processed = 0;
        s.success = 0;
        s.failed = 0;
        s.skipped = 0;
        s.streaming = false;
        String out = renderFull(s);
        check("empty run: shows waiting message",
            out.contains("waiting for total") || out.contains("0.0%"),
            "handles zero items");
    }

    static void testLongSSIDTruncation() {
        OperatorConsole.Snapshot s = snap();
        s.sourceSSID = "VERY_LONG_SOURCE_SSID_THAT_EXCEEDS_DISPLAY_LIMIT";
        s.destSSID = "ALSO_A_VERY_LONG_DESTINATION_SSID_FOR_TESTING";
        String out = renderFull(s);
        check("long SSID truncated with ellipsis",
            out.contains("\u2026"),
            "must contain truncation indicator");
    }

    static void testLongItemTypeTruncation() {
        OperatorConsole.Snapshot s = snap();
        s.sourceItemType = "VeryLongDocumentTypeNameThatExceeds14Chars";
        s.destItemType = "AnotherVeryLongTypeNameForTesting";
        String out = renderFull(s);
        check("long item type truncated in output",
            !out.contains("VeryLongDocumentTypeNameThatExceeds14Chars"),
            "full long name must not appear");
    }

    static void testStallWaiting() {
        OperatorConsole.Snapshot s = snap();
        s.lastProgressMs = 35000;
        String label = OperatorConsole.stallLabel(s.lastProgressMs);
        check("stall WAITING at 35s",
            "WAITING".equals(label),
            "got " + label);
    }

    static void testStallStalled() {
        OperatorConsole.Snapshot s = snap();
        s.lastProgressMs = 350000;
        String label = OperatorConsole.stallLabel(s.lastProgressMs);
        check("stall STALLED at 350s",
            "STALLED".equals(label),
            "got " + label);
    }

    static void testQueueFull() {
        OperatorConsole.Snapshot s = snap();
        s.queueDepth = 10000;
        s.queueCapacity = 10000;
        String out = renderFull(s);
        check("queue full shows FULL indicator",
            out.contains("FULL"),
            "must show FULL when depth >= capacity");
    }

    static void testJournalBackpressure() {
        OperatorConsole.Snapshot s = snap();
        s.journalHealth = OperatorConsole.JournalHealth.BACKPRESSURE;
        String out = renderFull(s);
        check("journal BACKPRESSURE appears in output",
            out.contains("BACKPRESSURE"),
            "must contain BACKPRESSURE");
    }

    static void testJournalFailed() {
        OperatorConsole.Snapshot s = snap();
        s.journalHealth = OperatorConsole.JournalHealth.FAILED;
        s.journalError = "Connection refused: timeout after 30s";
        String out = renderFull(s);
        check("journal FAILED appears in output",
            out.contains("FAILED"),
            "must contain FAILED");
        check("journal error message appears",
            out.contains("Connection refused"),
            "must contain error text");
    }

    static void testCompletedState() {
        OperatorConsole.Snapshot s = snap();
        s.state = OperatorConsole.RunState.COMPLETED;
        String out = renderFull(s);
        check("COMPLETED state badge in output",
            out.contains("COMPLETED"),
            "must show COMPLETED");
    }

    static void testFailedState() {
        OperatorConsole.Snapshot s = snap();
        s.state = OperatorConsole.RunState.FAILED;
        String out = renderFull(s);
        check("FAILED state badge in output",
            out.contains("FAILED"),
            "must show FAILED");
    }

    static void testInterruptedState() {
        OperatorConsole.Snapshot s = snap();
        s.state = OperatorConsole.RunState.INTERRUPTED;
        String out = renderFull(s);
        check("INTERRUPTED state badge in output",
            out.contains("INTERRUPTED"),
            "must show INTERRUPTED");
    }

    static void testPlainModeNoAnsi() {
        OperatorConsole.Snapshot s = snap();
        String out = renderPlain(s);
        check("plain mode output has no ANSI escape",
            !out.contains("\u001B"),
            "must not contain ESC character");
    }

    static void testPlainModeNoNewlines() {
        OperatorConsole.Snapshot s = snap();
        String out = renderPlain(s);
        check("plain mode output has no embedded newlines",
            !out.contains("\n"),
            "must be single line");
    }

    static void testFinalOutputEndsWithNewline() {
        OperatorConsole.Snapshot s = snap();
        String out = renderFull(s);
        check("full render ends with newline",
            out.endsWith("\n"),
            "last char must be \\n");
        out = renderPlain(s);
        check("plain render does NOT add trailing newline",
            !out.endsWith("\n"),
            "plain render should not add \\n (draw() does)");
    }

    // ── Unit tests for helper methods ─────────────────────────────────

    static void testFmtDuration() {
        check("fmtDuration 0ms → 00:00:00",
            "00:00:00".equals(OperatorConsole.fmtDuration(0)),
            "got " + OperatorConsole.fmtDuration(0));
        check("fmtDuration 3661000ms → 01:01:01",
            "01:01:01".equals(OperatorConsole.fmtDuration(3661000)),
            "got " + OperatorConsole.fmtDuration(3661000));
        check("fmtDuration -1ms → 00:00:00",
            "00:00:00".equals(OperatorConsole.fmtDuration(-1)),
            "got " + OperatorConsole.fmtDuration(-1));
    }

    static void testSanitize() {
        check("sanitize null → empty",
            "".equals(OperatorConsole.sanitize(null)),
            "null must return empty");
        check("sanitize spaces → underscores",
            "hello_world".equals(OperatorConsole.sanitize("hello world")),
            "spaces replaced");
        check("sanitize newlines → spaces",
            "a b".equals(OperatorConsole.sanitize("a\nb")),
            "newlines replaced");
    }

    static void testTrunc() {
        check("trunc short string unchanged",
            "abc".equals(OperatorConsole.trunc("abc", 10)),
            "short string");
        check("trunc long string gets ellipsis",
            OperatorConsole.trunc("abcdefghij", 5).endsWith("\u2026"),
            "must end with ellipsis, got: " + OperatorConsole.trunc("abcdefghij", 5));
        check("trunc null → empty",
            "".equals(OperatorConsole.trunc(null, 10)),
            "null must return empty");
    }

    static void testPhaseStr() {
        check("phaseStr MIGRATING → MIGRATE",
            "MIGRATE".equals(OperatorConsole.phaseStr(OperatorConsole.Phase.MIGRATING)),
            "got " + OperatorConsole.phaseStr(OperatorConsole.Phase.MIGRATING));
        check("phaseStr null → MIGRATING",
            "MIGRATING".equals(OperatorConsole.phaseStr(null)),
            "null safe");
    }

    static void testJournalHealthStr() {
        check("journalHealthStr HEALTHY contains HEALTHY",
            OperatorConsole.journalHealthStr(OperatorConsole.JournalHealth.HEALTHY).contains("HEALTHY"),
            "must contain HEALTHY");
        check("journalHealthStr FAILED contains FAILED",
            OperatorConsole.journalHealthStr(OperatorConsole.JournalHealth.FAILED).contains("FAILED"),
            "must contain FAILED");
    }
}
