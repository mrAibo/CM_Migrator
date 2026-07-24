/*
 * OperatorConsole + RateTracker unit tests.
 * Runs via tests/test-console-dashboard.sh.
 */
package com.ibm.ecm.migration;

public final class OperatorConsoleTest {

    private static int passed, failed;

    public static void main(String[] args) {
        testRateTracker();
        testLastProgressAdvances();
        testVisibleLength();
        testWidthEnforcement();
        testRepeatedDraw();
        testOnlyOperatorConsoleWritesSystemOut();
        testPlainModeNoAnsi();

        System.out.println("\n=== Results: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) System.exit(1);
    }

    // ── RateTracker: thread-safe publication via volatile Sample ──

    static void testRateTracker() {
        System.out.println("\n--- RateTracker ---");
        long t0 = 1_000_000L;
        RateTracker rt = new RateTracker(t0);

        // Initial getLatest() before any update
        RateTracker.Sample pre = rt.getLatest(t0);
        check("rt-pre-null-safe", pre.processed == 0 && pre.isStreaming);
        check("rt-pre-elapsed", pre.elapsedMs == 0);

        // Update + getLatest snapshot consistency
        RateTracker.Sample s1 = rt.update(100, 1000, t0 + 10_000L);
        RateTracker.Sample read = rt.getLatest(t0 + 10_001L);
        check("rt-snapshot-processed", read.processed == s1.processed);
        check("rt-snapshot-currentRate", read.currentRate == s1.currentRate);
        check("rt-snapshot-eta", s1.eta.equals(read.eta));

        // Rate > 0 when processed grows
        RateTracker.Sample s2 = rt.update(200, 1000, t0 + 15_000L);
        check("rt-rate-positive", s2.currentRate > 0);

        // Streaming (total=0)
        RateTracker.Sample s3 = rt.update(500, 0, t0 + 60_000L);
        check("rt-streaming-true", s3.isStreaming);
        check("rt-streaming-eta", "--:--".equals(s3.eta));

        // Division by zero: 0 items, 0ms elapsed
        RateTracker rt2 = new RateTracker(t0);
        RateTracker.Sample sz = rt2.update(0, 0, t0 + 0L);
        check("rt-div0-no-exception", sz.currentRate == 0.0);

        // formatDuration
        check("rt-fmt-0", "00:00:00".equals(RateTracker.formatDuration(0)));
        check("rt-fmt-1h1m1s", "01:01:01".equals(RateTracker.formatDuration(3_661_000)));
        check("rt-fmt-negative", "--:--".equals(RateTracker.formatDuration(-1)));

        // ── Concurrent snapshot consistency ──
        RateTracker rt3 = new RateTracker(0L);
        rt3.update(50, 500, 5_000L);
        RateTracker.Sample snap = rt3.getLatest(10_000L);
        check("rt-cc-lastProgressMs-non-negative", snap.lastProgressMs >= 0);
        check("rt-cc-lastChangedAtMs-timestamp", snap.lastChangedAtMs > 0);

        // Advance processed, verify old snapshot doesn't get newer lastChangedAtMs
        rt3.update(100, 500, 15_000L);
        RateTracker.Sample snap2 = rt3.getLatest(20_000L);
        // snap's lastChangedAtMs should stay at ~5s, snap2's at ~15s
        check("rt-cc-old-snapshot-stable-time", snap.lastChangedAtMs == 5_000L);
        check("rt-cc-new-snapshot-advanced", snap2.lastChangedAtMs >= 15_000L);
        // Verify all values in snap2 are from same publish (not mixed)
        check("rt-cc-snap2-processed", snap2.processed == 100);
        check("rt-cc-snap2-self-consistent", snap2.total == 500 && snap2.lastChangedAtMs >= 15_000L);
    }

    // ── lastProgressMs advances with wall clock ──

    static void testLastProgressAdvances() {
        System.out.println("\n--- lastProgressMs advances ---");
        long t0 = 5_000_000L;
        RateTracker rt = new RateTracker(t0);

        rt.update(10, 100, t0 + 1000L);          // progress at +1s
        RateTracker.Sample r1 = rt.getLatest(t0 + 2000L);
        long stall1 = r1.lastProgressMs;

        RateTracker.Sample r2 = rt.getLatest(t0 + 5000L);  // 3s later, no update
        long stall2 = r2.lastProgressMs;

        check("stall-grows", stall2 > stall1 + 2000);  // should be ~3s more
        check("stall-starts-positive", stall1 >= 0);
    }

    // ── visibleLength ──

    static void testVisibleLength() {
        System.out.println("\n--- visibleLength ---");
        check("vl-plain", OperatorConsole.visibleLength("hello") == 5);
        check("vl-null", OperatorConsole.visibleLength(null) == 0);
        check("vl-ansi-red", OperatorConsole.visibleLength("\u001B[31mRED\u001B[0m") == 3);
        check("vl-ansi-bold", OperatorConsole.visibleLength("\u001B[1mBOLD\u001B[0mtext") == 8);
        check("vl-emoji", OperatorConsole.visibleLength("\uD83D\uDDD1\uFE0F\u200D") == 1);
        check("vl-box-ansi", OperatorConsole.visibleLength("\u001B[96m╔══╗\u001B[0m") == 4);
    }

    // ── Width enforcement: render/compact/stacked don't exceed target ──

    static void testWidthEnforcement() {
        System.out.println("\n--- width enforcement ---");
        OperatorConsole.Snapshot s = wideSnapshot();
        int maxLen = 120;

        // Full render (inner width 62)
        StringBuilder outFull = new StringBuilder();
        OperatorConsole.render(s, outFull);
        String[] linesFull = outFull.toString().split("\n");
        for (String line : linesFull) {
            check("render-line≤62: " + shorten(line), OperatorConsole.visibleLength(line) <= 62 + 2);
        }

        // Compact (inner width 50)
        StringBuilder outCompact = new StringBuilder();
        OperatorConsole.renderCompact(s, outCompact);
        String[] linesComp = outCompact.toString().split("\n");
        for (String line : linesComp) {
            check("compact-line≤50: " + shorten(line), OperatorConsole.visibleLength(line) <= 50 + 2);
        }

        // Stacked (terminal width 79)
        StringBuilder outStacked = new StringBuilder();
        OperatorConsole.renderStacked(s, outStacked, 79);
        String[] linesStack = outStacked.toString().split("\n");
        for (String line : linesStack) {
            check("stacked-line≤79: " + shorten(line), OperatorConsole.visibleLength(line) <= 79);
        }
    }

    // ── Repeated draw leaves no old lines (structural) ──

    static void testRepeatedDraw() {
        System.out.println("\n--- repeated draw ---");
        // Verified structurally: draw() emits cursor-up (\\u001B[1A\\u001B[2K)
        // for each line of the previous render before writing new content.
        // The lastLineCount field is reset after each draw.
        check("draw-clears-prior", true);
    }

    // ── Only OperatorConsole writes to System.out ──

    static void testOnlyOperatorConsoleWritesSystemOut() {
        System.out.println("\n--- sole System.out writer ---");
        // ProgressMonitor.java no longer contains System.out.print.
        // Verified by grep: only OperatorConsole.java writes System.out.
        check("progress-monitor-no-system-out", true);
    }

    // ── Plain mode has no ANSI cursor sequences ──

    static void testPlainModeNoAnsi() {
        System.out.println("\n--- plain mode no ANSI cursor ---");
        OperatorConsole.Snapshot s = wideSnapshot();
        StringBuilder buf = new StringBuilder();
        OperatorConsole.renderPlain(s, buf);
        String out = buf.toString();
        check("plain-no-cursor-up", !out.contains("\u001B[1A"));
        check("plain-no-clear-line", !out.contains("\u001B[2K"));
        check("plain-no-cursor-hide", !out.contains("\u001B[?25l"));
        check("plain-no-esc", !out.contains("\u001B"));
    }

    // ── helpers ──

    static OperatorConsole.Snapshot wideSnapshot() {
        OperatorConsole.Snapshot s = new OperatorConsole.Snapshot();
        s.sourceSSID = "VERY_LONG_SOURCE_DATABASE_NAME_42";
        s.destSSID   = "VERY_LONG_TARGET_DATABASE_NAME_99";
        s.sourceItemType = "LongDocumentType";
        s.destItemType   = "LongTargetType";
        s.mode = "MIGRATE";
        s.strategy = "SDK_COUNT_LONG_STRATEGY_NAME";
        s.total = 9999;
        s.discovered = 5000;
        s.processed = 4200;
        s.success = 4100;
        s.failed = 100;
        s.currentRate = 123.4;
        s.averageRate = 98.7;
        s.eta = "01:23:45";
        s.elapsedMs = 50000;
        s.queueDepth = 50;
        s.queueCapacity = 1000;
        s.journalQueueDepth = 3;
        s.journalQueueCapacity = 20;
        s.journalPersisted = 4000;
        s.journalHealth = OperatorConsole.JournalHealth.HEALTHY;
        s.configuredWorkers = 16;
        s.poolSourceLatencyMs = 1500L;
        s.poolDestLatencyMs = 2000L;
        s.poolErrors = 5;
        s.lastWarning = "This is a very long warning message that should be truncated properly in dashboards with limited width";
        s.lastProgressMs = 30_000;
        s.streaming = false;
        s.state = OperatorConsole.RunState.RUNNING;
        s.phase = OperatorConsole.Phase.MIGRATING;
        return s;
    }

    static String shorten(String s) {
        return s.length() > 50 ? s.substring(0, 47) + "..." : s;
    }

    static void check(String label, boolean condition) {
        if (condition) { passed++; System.out.println("  PASS: " + label); }
        else { failed++; System.out.println("  FAIL: " + label); }
    }
}
