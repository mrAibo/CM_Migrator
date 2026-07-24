/*
 * OperatorConsole terminal-width + scroll + final-rate tests.
 * Runs via tests/test-console-dashboard.sh.
 */
package com.ibm.ecm.migration;

public final class OperatorConsoleTest {

    private static int passed, failed;

    public static void main(String[] args) {
        testTerminalWidth();
        testLayoutSelection();
        testOuterWidthSafety();
        testTruncateAnsiClose();
        testRepeatedDraw();
        testFinalRate();
        testVisibleLength();
        testPlainModeNoAnsi();
        testRateTracker();

        System.out.println("\n=== Results: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) System.exit(1);
    }

    // ── Terminal width: property > env > fallback ──

    static void testTerminalWidth() {
        System.out.println("\n--- terminalWidth ---");
        // default fallback = 79
        check("tw-default", OperatorConsole.terminalWidth() == 79);

        // System property wins
        System.setProperty("cm.migrator.console.columns", "120");
        check("tw-prop-120", OperatorConsole.terminalWidth() == 120);
        System.clearProperty("cm.migrator.console.columns");

        // Invalid property → fallback to env or 79
        System.setProperty("cm.migrator.console.columns", "broken");
        int tw = OperatorConsole.terminalWidth();
        check("tw-prop-invalid-fallback", tw == 79 || tw > 0);
        System.clearProperty("cm.migrator.console.columns");
    }

    // ── Layout selection (render / compact / stacked) ──

    static void testLayoutSelection() {
        System.out.println("\n--- layoutSelection ---");
        for (int cols : new int[]{50, 53, 54, 65, 66, 79, 100, 120}) {
            System.setProperty("cm.migrator.console.columns", String.valueOf(cols));
            int tw = OperatorConsole.terminalWidth();
            int ow = OperatorConsole.outerWidth();
            String layout;
            if (tw >= 66) layout = "full(64)";
            else if (tw >= 54) layout = "compact(52)";
            else layout = "stacked";
            // Verify outerWidth constraint
            check("ow≤tw-2 @" + cols, ow <= tw - 2);
            System.out.println("  cols=" + cols + " → " + layout + " (outer=" + ow + ")");
        }
        System.clearProperty("cm.migrator.console.columns");

        // Specific: 64 cols → compact (not full), 65 → compact, 66 → full
        for (int[] pair : new int[][]{{64, 52}, {65, 52}, {66, 64}, {67, 64}}) {
            System.setProperty("cm.migrator.console.columns", String.valueOf(pair[0]));
            check("cols=" + pair[0] + " outer=" + pair[1],
                  OperatorConsole.outerWidth() == pair[1]);
        }
        System.clearProperty("cm.migrator.console.columns");
    }

    // ── outerWidth safety: no layout uses last terminal column ──

    static void testOuterWidthSafety() {
        System.out.println("\n--- outerWidthSafety ---");
        for (int cols : new int[]{40, 50, 54, 64, 66, 79, 80, 100}) {
            System.setProperty("cm.migrator.console.columns", String.valueOf(cols));
            int ow = OperatorConsole.outerWidth();
            check("no-wrap cols=" + cols, ow <= cols - 2);
        }
        System.clearProperty("cm.migrator.console.columns");

        // Defensive: extreme width values
        for (int cols : new int[]{1, 2, 3, 9999}) {
            System.setProperty("cm.migrator.console.columns", String.valueOf(cols));
            int ow = OperatorConsole.outerWidth();
            check("def-cols=" + cols + " ow>0", ow > 0);
            check("def-cols=" + cols + " ow≤tw-2", ow <= Math.max(1, cols - 2));
        }
        // Invalid property → fallback 79
        System.setProperty("cm.migrator.console.columns", "invalid");
        check("tw-invalid-fallback-79", OperatorConsole.terminalWidth() == 79);
        System.clearProperty("cm.migrator.console.columns");
    }

    // ── truncateVisible closes ANSI after truncation ──

    static void testTruncateAnsiClose() {
        System.out.println("\n--- truncateAnsiClose ---");
        // Truncation while escape is NOT yet closed (CSI incomplete)
        String colored = "\u001B[31;1mRED_TEXT_NO_CLOSE\u001B[0m";
        // visible: RED_TEXT_NO_CLOSE = 19 chars.
        // Truncate at 0 visible — escape is incomplete → RESET appended
        String t0 = OperatorConsole.truncateVisible(colored, 0);
        check("trunc-ansi-0-visible-closed", !t0.endsWith("\u001B[31") && (t0.isEmpty() || t0.contains(ConsoleUI.RESET)));

        // Full string: escape was closed by 'm' at input. No extra RESET.
        String full = OperatorConsole.truncateVisible(colored, 19);
        int firstReset = full.indexOf(ConsoleUI.RESET);
        int lastReset  = full.lastIndexOf(ConsoleUI.RESET);
        check("trunc-ansi-no-dupe-reset", firstReset >= 0 && firstReset == lastReset);
    }

    // ── Repeated draw at various widths: no header leftovers ──

    static void testRepeatedDraw() {
        System.out.println("\n--- repeatedDraw ---");
        OperatorConsole.Snapshot s = snapshot();

        for (int cols : new int[]{50, 53, 54, 64, 65, 66, 79, 80, 100}) {
            System.setProperty("cm.migrator.console.columns", String.valueOf(cols));

            // 10 sequential draws
            StringBuilder capturer = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                StringBuilder buf = new StringBuilder();
                int tw = OperatorConsole.terminalWidth();
                if (tw >= 66)      OperatorConsole.render(s, buf);
                else if (tw >= 54) OperatorConsole.renderCompact(s, buf);
                else               OperatorConsole.renderStacked(s, buf, tw - 2);
                capturer.append(buf.toString());
            }

            // Every render must start with header (╔ or ──)
            int renders = 0;
            for (String line : capturer.toString().split("\n")) {
                if (line.contains("╔") || line.contains("── CM Migrator")) renders++;
            }
            check("10-renders @" + cols, renders >= 10);
        }
        System.clearProperty("cm.migrator.console.columns");
    }

    // ── Final rate: shows "stopped" not 0.0 for non-RUNNING ──

    static void testFinalRate() {
        System.out.println("\n--- finalRate ---");
        OperatorConsole.Snapshot s = snapshot();
        s.state = OperatorConsole.RunState.COMPLETED;
        s.currentRate = 0.0;
        s.averageRate = 210.9;

        // Full
        StringBuilder out = new StringBuilder();
        OperatorConsole.render(s, out);
        check("final-full-no-zero-rate", !out.toString().contains("0.0 it/s"));
        check("final-full-stopped", out.toString().contains("stopped"));
        check("final-full-avg-visible", out.toString().contains("210.9"));

        // Compact
        out.setLength(0);
        OperatorConsole.renderCompact(s, out);
        check("final-compact-no-zero-rate", !out.toString().contains("0.0/s"));
        check("final-compact-stopped", out.toString().contains("stopped"));

        // Stacked
        out.setLength(0);
        OperatorConsole.renderStacked(s, out, 79);
        check("final-stacked-no-zero-rate", !out.toString().contains("speed=0.0"));
        check("final-stacked-stopped", out.toString().contains("stopped"));

        // RUNNING → uses delta rate
        s.state = OperatorConsole.RunState.RUNNING;
        s.currentRate = 45.2;
        out.setLength(0);
        OperatorConsole.render(s, out);
        check("running-shows-rate", out.toString().contains("45.2 it/s"));
    }

    // ── visibleLength ──

    static void testVisibleLength() {
        System.out.println("\n--- visibleLength ---");
        check("vl-plain", OperatorConsole.visibleLength("hello") == 5);
        check("vl-null", OperatorConsole.visibleLength(null) == 0);
        check("vl-ansi-red", OperatorConsole.visibleLength("\u001B[31mRED\u001B[0m") == 3);
        check("vl-box", OperatorConsole.visibleLength("\u001B[96m╔══╗\u001B[0m") == 4);
    }

    // ── Plain mode no ANSI cursor sequences ──

    static void testPlainModeNoAnsi() {
        System.out.println("\n--- plain mode ---");
        OperatorConsole.Snapshot s = snapshot();
        StringBuilder buf = new StringBuilder();
        OperatorConsole.renderPlain(s, buf);
        String out = buf.toString();
        check("plain-no-esc", !out.contains("\u001B"));
    }

    // ── RateTracker basics ──

    static void testRateTracker() {
        System.out.println("\n--- RateTracker ---");
        long t0 = 1_000_000L;
        RateTracker rt = new RateTracker(t0);

        RateTracker.Sample s1 = rt.update(100, 1000, t0 + 10_000L);
        check("rt-rate-positive", s1.currentRate > 0);
        check("rt-eta-computed", !"--:--".equals(s1.eta));

        // Thread safety: getLatest snapshot matches after same update
        RateTracker.Sample snap = rt.getLatest(t0 + 10_001L);
        check("rt-snapshot-processed", snap.processed == 100);

        // Division by zero: separate tracker
        RateTracker rt2 = new RateTracker(t0);
        RateTracker.Sample sz = rt2.update(0, 0, t0 + 500L);
        check("rt-div0", sz.currentRate == 0.0);
        check("rt-lastChangedAtMs", snap.lastChangedAtMs > 0);
    }

    // ── helpers ──

    static OperatorConsole.Snapshot snapshot() {
        OperatorConsole.Snapshot s = new OperatorConsole.Snapshot();
        s.sourceSSID = "FHIR_SRC"; s.destSSID = "FHIR_DST";
        s.sourceItemType = "Document"; s.destItemType = "Document";
        s.mode = "MIGRATE"; s.strategy = "SDK_COUNT";
        s.total = 10000; s.discovered = 5000; s.processed = 4231;
        s.success = 4100; s.failed = 131;
        s.currentRate = 45.2; s.averageRate = 38.7;
        s.eta = "00:02:07"; s.elapsedMs = 120000;
        s.queueDepth = 12; s.queueCapacity = 200;
        s.journalQueueDepth = 0; s.journalQueueCapacity = 50;
        s.journalPersisted = 4000;
        s.journalHealth = OperatorConsole.JournalHealth.HEALTHY;
        s.configuredWorkers = 8;
        s.poolSourceLatencyMs = 12L; s.poolDestLatencyMs = 8L;
        s.poolErrors = 0;
        s.state = OperatorConsole.RunState.RUNNING;
        s.phase = OperatorConsole.Phase.MIGRATING;
        return s;
    }

    static void check(String label, boolean condition) {
        if (condition) { passed++; System.out.println("  PASS: " + label); }
        else { failed++; System.out.println("  FAIL: " + label); }
    }
}
