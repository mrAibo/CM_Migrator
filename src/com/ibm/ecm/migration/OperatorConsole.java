/*
 * Projekt: CM Migrator 2.2.1.
 * @Author: Aleksej Voronin, Sven Lindt
 * @Date:   23.07.2026
 *
 * Console dashboard renderer for migration progress.
 * Replaces ProgressMonitor console output with a structured box-drawing
 * dashboard (pretty mode) or single-line key=value output (plain mode).
 * Dependency-free — no Log4j, no IBM JARs. Uses ConsoleUI ANSI helpers.
 */
package com.ibm.ecm.migration;

public final class OperatorConsole {

    // ── Status model ──────────────────────────────────────────────────

    public enum RunState { STARTING, RUNNING, STOPPING, COMPLETED, FAILED, INTERRUPTED }

    public enum Phase {
        INITIALIZING, CONNECTING, COUNTING, DISCOVERING, MIGRATING,
        VERIFYING, DELETING, DRAINING_WORKERS, DRAINING_JOURNAL,
        GENERATING_REPORTS, FINALIZING
    }

    public enum JournalHealth { HEALTHY, BACKPRESSURE, DRAINING, FAILED, CLOSED, UNKNOWN }

    public static final String VERSION = "2.2.1";

    // ── ConsoleConfig (static, read from system properties) ──────────

    static final boolean NO_COLOR = System.getenv("NO_COLOR") != null;
    static final boolean UNICODE = !"false".equals(System.getProperty("cm.migrator.console.unicode", "true"));
    static final int WAIT_WARN_SECONDS = Integer.getInteger("cm.migrator.console.waitWarnSeconds", 30);
    static final int STALL_WARN_SECONDS = Integer.getInteger("cm.migrator.console.stallWarnSeconds", 300);

    enum Mode { PRETTY, PLAIN }

    static final Mode MODE;

    static {
        String prop = System.getProperty("cm.migrator.console.mode", "auto").trim().toLowerCase();
        if ("pretty".equals(prop)) MODE = Mode.PRETTY;
        else if ("plain".equals(prop)) MODE = Mode.PLAIN;
        else MODE = (System.console() != null) ? Mode.PRETTY : Mode.PLAIN;
        if (NO_COLOR) ConsoleUI.setColorsEnabled(false);
    }

    static int terminalWidth() {
        String cols = System.getenv("COLUMNS");
        if (cols != null) {
            try { return Integer.parseInt(cols); } catch (NumberFormatException ignored) {}
        }
        return 100; // fallback
    }

    // ── Data snapshot ─────────────────────────────────────────────────

    static class Snapshot {
        RunState state = RunState.RUNNING;
        Phase phase = Phase.MIGRATING;
        String mode = "MIGRATE";
        String strategy = "BATCHED";
        String sourceSSID = "";
        String destSSID = "";
        String sourceItemType = "";
        String destItemType = "";
        long total = 0;
        long discovered = 0;
        long processed = 0;
        long success = 0;
        long failed = 0;
        long skipped = 0;
        long deleted = 0;
        double currentRate = 0.0;
        double averageRate = 0.0;
        long elapsedMs = 0;
        String eta = "--:--";
        int queueDepth = 0;
        int queueCapacity = 0;
        int journalQueueDepth = 0;
        int journalQueueCapacity = 0;
        long journalPersisted = 0;
        JournalHealth journalHealth = JournalHealth.HEALTHY;
        String journalError;
        long poolSourceLatencyMs = 0;
        long poolDestLatencyMs = 0;
        int poolErrors = 0;
        int activeWorkers = 0;
        String lastWarning;
        long lastProgressMs = 0;
        boolean streaming = false;
    }

    // ── Terminal control ──────────────────────────────────────────────

    private static int lastLineCount = 0;

    /** ANSI clear screen + cursor home. */
    public static String clearScreen() {
        return "\u001B[2J\u001B[H";
    }

    /** ANSI cursor home (column 0, line 0). */
    public static String moveToTop() {
        return "\u001B[H";
    }

    /** ANSI hide cursor. */
    public static String hideCursor() {
        return "\u001B[?25l";
    }

    /** ANSI show cursor. */
    public static String showCursor() {
        return "\u001B[?25h";
    }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Render the dashboard for the given snapshot.
     * In pretty mode: overwrites previous output via ANSI cursor-up.
     * In plain mode: prints a single line.
     */
    public static void draw(Snapshot s) {
        if (MODE == Mode.PLAIN) {
            StringBuilder buf = new StringBuilder();
            renderPlain(s, buf);
            System.out.println(buf.toString());
            System.out.flush();
            lastLineCount = 1;
            return;
        }

        StringBuilder buf = new StringBuilder();

        // Move cursor up over previous render, clear each line
        if (lastLineCount > 0) {
            buf.append('\r');
            for (int i = 0; i < lastLineCount; i++) {
                buf.append("\u001B[1A\u001B[2K");
            }
            buf.append('\r');
        }

        int w = terminalWidth();
        if (w < 80) {
            renderStacked(s, buf);
        } else if (w < 100) {
            renderCompact(s, buf);
        } else {
            render(s, buf);
        }

        // ponytail: ensure trailing newline so cursor lands below dashboard
        if (buf.length() == 0 || buf.charAt(buf.length() - 1) != '\n') {
            buf.append('\n');
        }

        System.out.print(buf.toString());
        System.out.flush();

        // Count newlines for next cursor-up pass
        lastLineCount = 0;
        for (int i = 0; i < buf.length(); i++) {
            if (buf.charAt(i) == '\n') lastLineCount++;
        }
    }

    /** Final render: draw once more, show cursor, print trailing newline. */
    public static void finalRender(Snapshot s) {
        draw(s);
        System.out.print(showCursor());
        System.out.println();
        System.out.flush();
        lastLineCount = 0;
    }

    // ── Pretty mode (box-drawing dashboard) ───────────────────────────

    static void render(Snapshot s, StringBuilder out) {
        String boxTL = box("╔", "+"); String boxTR = box("╗", "+");
        String boxBL = box("╚", "+"); String boxBR = box("╝", "+");
        String boxH  = box("═", "-"); String boxV  = box("║", "|");
        String boxL  = box("╠", "+"); String boxR  = box("╣", "+");

        String hSep = repeat(boxH, 62);

        // ── Top header ──
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxTL).append(hSep).append(boxTR).append(r());
        out.append(boxV);
        out.append(c(ConsoleUI.BOLD)).append(" CM Migrator v").append(VERSION).append(r());
        out.append(pad(1));
        out.append(modeBadge(s)).append(pad(1));
        out.append(stateBadge(s)).append(pad(1));
        out.append(c(ConsoleUI.DIM)).append(timestamp()).append(r());
        out.append(pad(62 - 16 - VERSION.length() - 1 - badgeLen(s) - 1 - stateBadgeLen(s) - 1 - 8));
        // Actually just right-align timestamp
        // ponytail: simpler: fixed layout with padding computed once
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append('\n');

        // ── Separator ──
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxL).append(hSep).append(boxR).append('\n');

        // ── Source/Dest/Phase/Strategy ──
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append(r());
        out.append(c(ConsoleUI.DIM)).append(trunc(s.sourceSSID, 16)).append(r());
        out.append(c(ConsoleUI.CYAN)).append(" \u2192 ").append(r());
        out.append(c(ConsoleUI.DIM)).append(trunc(s.destSSID, 16)).append(r());
        out.append(pad(4));
        out.append(trunc(s.sourceItemType, 14));
        out.append(c(ConsoleUI.CYAN)).append(" \u2192 ").append(r());
        out.append(trunc(s.destItemType, 14));
        out.append(pad(3));
        out.append(phaseStr(s.phase)).append(" | ").append(trunc(s.strategy, 14));
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append('\n');

        // ── Separator ──
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxL).append(hSep).append(boxR).append('\n');

        // ── Progress section ──
        boolean knownTotal = s.total > 0;
        double pct = knownTotal ? Math.min(100.0, Math.max(0.0, (double) s.processed / s.total * 100.0)) : 0.0;

        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append(r());
        out.append(" Progress: ");

        if (knownTotal) {
            out.append(ConsoleUI.progressBar(pct, 30));
            out.append(' ');
            out.append(c(ConsoleUI.BOLD)).append(fmtPct(pct)).append(r());
        } else if (s.streaming) {
            out.append(c(ConsoleUI.BRIGHT_CYAN)).append(activityIndicator(s.elapsedMs)).append(r());
            out.append(" streaming");
        } else {
            out.append(c(ConsoleUI.DIM)).append("(waiting for total)").append(r());
        }
        // pad to box width
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append('\n');

        // Sub-line: processed / total, discovered
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append(r());
        out.append("           processed=");
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(fmt(s.processed)).append(r());
        out.append(" / ");
        out.append(knownTotal ? fmt(s.total) : c(ConsoleUI.DIM) + "unknown" + r());
        out.append(pad(4));
        out.append("discovered=").append(fmt(s.discovered));

        // Stall detection
        String stall = stallLabel(s.lastProgressMs);
        if (!stall.isEmpty()) {
            out.append(pad(4));
            out.append(stallColor(s.lastProgressMs)).append(stall).append(r());
        }
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append('\n');

        // ── Results counters ──
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append(r());
        out.append(c(ConsoleUI.GREEN)).append(ConsoleUI.ICON_SUCCESS).append(r());
        out.append(' ').append(fmt(s.success));
        out.append(pad(2));
        out.append(c(ConsoleUI.RED)).append(ConsoleUI.ICON_ERROR).append(r());
        out.append(' ').append(s.failed > 0 ? c(ConsoleUI.RED) + fmt(s.failed) + r() : c(ConsoleUI.DIM) + "0" + r());
        out.append(pad(2));
        if (s.skipped > 0) {
            out.append(c(ConsoleUI.YELLOW)).append(ConsoleUI.ICON_WARNING).append(r());
            out.append(' ').append(fmt(s.skipped));
        }
        out.append(pad(2));
        if (s.deleted > 0 || "DELETE".equalsIgnoreCase(s.mode)) {
            out.append(c(ConsoleUI.MAGENTA)).append(ConsoleUI.ICON_TRASH).append(ConsoleUI.RESET);
            out.append(' ').append(fmt(s.deleted));
        }
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append('\n');

        // ── Speed / ETA / Elapsed ──
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append(r());
        out.append(c(ConsoleUI.YELLOW)).append(ConsoleUI.ICON_SPEED).append(r());
        out.append(' ').append(String.format(java.util.Locale.ROOT, "%.1f", s.currentRate)).append(" it/s");
        out.append(" (avg ").append(String.format(java.util.Locale.ROOT, "%.1f", s.averageRate)).append(')');
        out.append(pad(2));
        out.append(c(ConsoleUI.CYAN)).append(ConsoleUI.ICON_CLOCK).append(r());
        out.append(" ETA: ").append(s.eta);
        out.append(pad(2));
        out.append(c(ConsoleUI.DIM)).append("Elapsed: ").append(fmtDuration(s.elapsedMs)).append(r());
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append('\n');

        // ── Separator ──
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxL).append(hSep).append(boxR).append('\n');

        // ── Pipeline section ──
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append(r());
        out.append(" Pipeline: queue=");
        out.append(fmt(s.queueDepth)).append('/').append(fmt(s.queueCapacity));
        if (s.queueCapacity > 0 && s.queueDepth >= s.queueCapacity) {
            out.append(' ').append(c(ConsoleUI.RED)).append("FULL").append(r());
        }
        out.append(pad(2)).append("workers=").append(s.activeWorkers).append(" active");
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append('\n');

        // ── Journal section ──
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append(r());
        out.append(" Journal: ");
        out.append(journalHealthStr(s.journalHealth));
        out.append(pad(2));
        out.append("queue=").append(fmt(s.journalQueueDepth)).append('/').append(fmt(s.journalQueueCapacity));
        out.append(pad(2));
        out.append("persisted=").append(fmt(s.journalPersisted));
        if (s.journalError != null && !s.journalError.isEmpty()) {
            out.append(' ').append(c(ConsoleUI.RED)).append(trunc(s.journalError, 20)).append(r());
        }
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append('\n');

        // ── CM Pools ──
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append(r());
        out.append(" CM Pools: src=").append(fmt(s.poolSourceLatencyMs)).append("ms");
        out.append(pad(2)).append("dst=").append(fmt(s.poolDestLatencyMs)).append("ms");
        out.append(pad(2)).append("errors=");
        if (s.poolErrors > 0) {
            out.append(c(ConsoleUI.RED)).append(s.poolErrors).append(r());
        } else {
            out.append(c(ConsoleUI.GREEN)).append('0').append(r());
        }
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append('\n');

        // ── Optional warning ──
        if (s.lastWarning != null && !s.lastWarning.isEmpty()) {
            out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append(r());
            out.append(c(ConsoleUI.YELLOW)).append(ConsoleUI.ICON_WARNING).append(' ');
            out.append(trunc(s.lastWarning, 56));
            out.append(r());
            out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append('\n');
        }

        // ── Separator ──
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxL).append(hSep).append(boxR).append('\n');

        // ── Footer ──
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append(r());
        out.append(c(ConsoleUI.DIM)).append(" Ctrl+C to stop gracefully").append(r());
        out.append(pad(62 - 25));
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append('\n');

        // ── Bottom ──
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxBL).append(hSep).append(boxBR).append(r()).append('\n');
    }

    // ── Compact mode (80-99 cols) ─────────────────────────────────────

    static void renderCompact(Snapshot s, StringBuilder out) {
        // ponytail: same layout, narrower box (50 chars), shorter fields
        String hSep = repeat(box("═", "-"), 50);
        String boxV = box("║", "|");

        boolean knownTotal = s.total > 0;
        double pct = knownTotal ? Math.min(100.0, Math.max(0.0, (double) s.processed / s.total * 100.0)) : 0.0;

        // Header
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(box("╔","+")).append(hSep).append(box("╗","+")).append('\n');
        out.append(boxV).append(c(ConsoleUI.BOLD)).append(" CM Migrator v").append(VERSION);
        out.append(' ').append(stateBadge(s));
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append('\n');

        // Separator
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(box("╠","+")).append(hSep).append(box("╣","+")).append('\n');

        // Source/Dest
        out.append(boxV).append(c(ConsoleUI.DIM)).append(trunc(s.sourceSSID, 10)).append(r());
        out.append(c(ConsoleUI.CYAN)).append("\u2192").append(r());
        out.append(c(ConsoleUI.DIM)).append(trunc(s.destSSID, 10)).append(r());
        out.append("  ").append(phaseStr(s.phase));
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append('\n');

        // Progress
        out.append(boxV).append(' ');
        if (knownTotal) {
            out.append(ConsoleUI.progressBar(pct, 20));
            out.append(' ').append(fmtPct(pct));
        } else if (s.streaming) {
            out.append(activityIndicator(s.elapsedMs)).append(" streaming");
        } else {
            out.append(c(ConsoleUI.DIM)).append("waiting...").append(r());
        }
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append('\n');

        // Counters inline
        out.append(boxV);
        out.append(' ').append(c(ConsoleUI.GREEN)).append(ConsoleUI.ICON_SUCCESS).append(r());
        out.append(fmt(s.success));
        out.append(' ').append(c(ConsoleUI.RED)).append(ConsoleUI.ICON_ERROR).append(r());
        out.append(s.failed > 0 ? c(ConsoleUI.RED) + fmt(s.failed) + r() : c(ConsoleUI.DIM) + "0" + r());
        out.append("  ").append(fmt(s.processed)).append('/');
        out.append(knownTotal ? fmt(s.total) : "?");
        out.append(' ').append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append('\n');

        // Speed
        out.append(boxV);
        out.append(' ').append(c(ConsoleUI.YELLOW)).append(ConsoleUI.ICON_SPEED).append(r());
        out.append(' ').append(String.format(java.util.Locale.ROOT, "%.1f", s.currentRate)).append("/s");
        out.append(" ETA:").append(s.eta);
        out.append(' ').append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append('\n');

        // Separator
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(box("╠","+")).append(hSep).append(box("╣","+")).append('\n');

        // Pipeline
        out.append(boxV).append(" q:").append(fmt(s.queueDepth)).append('/').append(fmt(s.queueCapacity));
        out.append(" w:").append(s.activeWorkers);
        out.append(' ').append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append('\n');

        // Journal
        out.append(boxV).append(" jnl:").append(journalHealthStr(s.journalHealth));
        out.append(" q:").append(fmt(s.journalQueueDepth)).append('/').append(fmt(s.journalQueueCapacity));
        out.append(' ').append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append('\n');

        // Pools
        out.append(boxV).append(" pool src=").append(fmt(s.poolSourceLatencyMs)).append("ms dst=").append(fmt(s.poolDestLatencyMs)).append("ms err=").append(s.poolErrors);
        out.append(' ').append(c(ConsoleUI.BRIGHT_CYAN)).append(boxV).append('\n');

        // Footer
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(box("╚","+")).append(hSep).append(box("╝","+")).append('\n');
    }

    // ── Stacked mode (<80 cols) ───────────────────────────────────────

    static void renderStacked(Snapshot s, StringBuilder out) {
        boolean knownTotal = s.total > 0;
        double pct = knownTotal ? Math.min(100.0, Math.max(0.0, (double) s.processed / s.total * 100.0)) : 0.0;

        out.append(c(ConsoleUI.BOLD)).append("── CM Migrator v").append(VERSION).append(' ');
        out.append(stateBadge(s)).append(' ').append(c(ConsoleUI.DIM)).append(timestamp()).append(r()).append('\n');

        out.append(c(ConsoleUI.DIM)).append(trunc(s.sourceSSID, 30)).append(r());
        out.append(c(ConsoleUI.CYAN)).append(" \u2192 ").append(r());
        out.append(c(ConsoleUI.DIM)).append(trunc(s.destSSID, 30)).append(r()).append('\n');

        out.append("phase=").append(phaseStr(s.phase));
        out.append(" mode=").append(s.mode);
        out.append(" strategy=").append(s.strategy).append('\n');

        if (knownTotal) {
            out.append("progress=").append(ConsoleUI.progressBar(pct, 20));
            out.append(' ').append(fmtPct(pct)).append('\n');
        } else if (s.streaming) {
            out.append("progress=").append(activityIndicator(s.elapsedMs)).append(" streaming\n");
        } else {
            out.append("progress=(waiting for total)\n");
        }

        out.append("processed=").append(fmt(s.processed));
        out.append(" total=").append(knownTotal ? fmt(s.total) : "unknown");
        out.append(" discovered=").append(fmt(s.discovered)).append('\n');

        out.append("success=").append(fmt(s.success));
        out.append(" failed=").append(fmt(s.failed));
        out.append(" skipped=").append(fmt(s.skipped));
        out.append(" deleted=").append(fmt(s.deleted)).append('\n');

        out.append("speed=").append(String.format(java.util.Locale.ROOT, "%.1f", s.currentRate));
        out.append("/s (avg ").append(String.format(java.util.Locale.ROOT, "%.1f", s.averageRate)).append(")");
        out.append(" ETA=").append(s.eta);
        out.append(" elapsed=").append(fmtDuration(s.elapsedMs)).append('\n');

        out.append("queue=").append(fmt(s.queueDepth)).append('/').append(fmt(s.queueCapacity));
        out.append(" workers=").append(s.activeWorkers).append('\n');

        out.append("journal=").append(journalHealthStrPlain(s.journalHealth));
        out.append(" jq=").append(fmt(s.journalQueueDepth)).append('/').append(fmt(s.journalQueueCapacity));
        out.append(" persisted=").append(fmt(s.journalPersisted)).append('\n');

        out.append("pool_src=").append(fmt(s.poolSourceLatencyMs)).append("ms");
        out.append(" pool_dst=").append(fmt(s.poolDestLatencyMs)).append("ms");
        out.append(" pool_errors=").append(s.poolErrors).append('\n');

        String stall = stallLabel(s.lastProgressMs);
        if (!stall.isEmpty()) {
            out.append("stall=").append(stall).append('\n');
        }
        if (s.lastWarning != null && !s.lastWarning.isEmpty()) {
            out.append("warning=").append(sanitize(s.lastWarning)).append('\n');
        }
    }

    // ── Plain mode render ─────────────────────────────────────────────

    static void renderPlain(Snapshot s, StringBuilder out) {
        boolean knownTotal = s.total > 0;

        out.append("state=").append(s.state);
        out.append(" phase=").append(s.phase);
        out.append(" mode=").append(s.mode);
        out.append(" strategy=").append(s.strategy);
        out.append(" source=").append(sanitize(s.sourceSSID));
        out.append(" destination=").append(sanitize(s.destSSID));
        out.append(" discovered=").append(s.discovered);
        out.append(" processed=").append(s.processed);
        out.append(" total=").append(knownTotal ? s.total : "unknown");
        out.append(" success=").append(s.success);
        out.append(" failed=").append(s.failed);
        out.append(" skipped=").append(s.skipped);
        out.append(" deleted=").append(s.deleted);
        out.append(" current_rate=").append(String.format(java.util.Locale.ROOT, "%.1f", s.currentRate));
        out.append(" average_rate=").append(String.format(java.util.Locale.ROOT, "%.1f", s.averageRate));
        out.append(" eta=").append(s.eta);
        out.append(" elapsed=").append(fmtDuration(s.elapsedMs));
        out.append(" queue=").append(s.queueDepth).append('/').append(s.queueCapacity);
        out.append(" journal=").append(journalHealthStrPlain(s.journalHealth));
        out.append(" journal_queue=").append(s.journalQueueDepth).append('/').append(s.journalQueueCapacity);
        out.append(" last_progress=").append(formatLastProgress(s.lastProgressMs));
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private static String c(String code) { return ConsoleUI.c(code); }
    private static String r() { return ConsoleUI.c(ConsoleUI.RESET); }

    private static String box(String unicode, String ascii) {
        return UNICODE ? unicode : ascii;
    }

    private static String repeat(String s, int n) {
        if (n <= 0) return "";
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    private static String pad(int n) { return repeat(" ", Math.max(0, n)); }

    static String fmt(long n) { return String.format(java.util.Locale.ROOT, "%,d", n); }
    private static String fmtPct(double pct) { return String.format(java.util.Locale.ROOT, "%5.1f%%", pct); }

    static String fmtDuration(long ms) {
        if (ms < 0) return "00:00:00";
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", h, m, sec);
    }

    static String trunc(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "\u2026";
    }

    /** Sanitize: replace spaces with underscores, strip newlines. */
    static String sanitize(String s) {
        if (s == null) return "";
        // ponytail: replace spaces so plain-mode tokens stay parseable
        return s.replace(' ', '_').replace('\n', ' ').replace('\r', ' ');
    }

    private static String timestamp() {
        java.time.LocalTime now = java.time.LocalTime.now();
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", now.getHour(), now.getMinute(), now.getSecond());
    }

    static String stateBadge(Snapshot s) {
        RunState st = s.state;
        if (st == null) st = RunState.RUNNING;
        String color;
        switch (st) {
            case RUNNING:    color = ConsoleUI.BRIGHT_GREEN; break;
            case COMPLETED:  color = ConsoleUI.GREEN; break;
            case FAILED:     color = ConsoleUI.BRIGHT_RED; break;
            case STOPPING:   color = ConsoleUI.BRIGHT_YELLOW; break;
            case INTERRUPTED:color = ConsoleUI.YELLOW; break;
            default:         color = ConsoleUI.BRIGHT_BLUE; break;
        }
        return c(color) + c(ConsoleUI.BOLD) + st.name() + r();
    }

    static int stateBadgeLen(Snapshot s) {
        RunState st = s.state;
        return (st != null ? st.name().length() : 7) + 3; // +3 for padding
    }

    static int badgeLen(Snapshot s) {
        return s.mode != null ? s.mode.length() + 3 : 7;
    }

    private static String modeBadge(Snapshot s) {
        String mode = s.mode != null ? s.mode.toUpperCase() : "MIGRATE";
        String color;
        switch (mode) {
            case "MIGRATE": color = ConsoleUI.BRIGHT_GREEN; break;
            case "VERIFY":  color = ConsoleUI.BRIGHT_CYAN; break;
            case "DELETE":  color = ConsoleUI.BRIGHT_RED; break;
            default:        color = ConsoleUI.BRIGHT_BLUE; break;
        }
        return c(color) + c(ConsoleUI.BOLD) + " " + mode + " " + r();
    }

    static String phaseStr(Phase p) {
        if (p == null) return "MIGRATING";
        switch (p) {
            case INITIALIZING:     return "INIT";
            case CONNECTING:       return "CONN";
            case COUNTING:         return "COUNT";
            case DISCOVERING:      return "DISCOV";
            case MIGRATING:        return "MIGRATE";
            case VERIFYING:        return "VERIFY";
            case DELETING:         return "DELETE";
            case DRAINING_WORKERS: return "DRAIN_W";
            case DRAINING_JOURNAL: return "DRAIN_J";
            case GENERATING_REPORTS: return "REPORTS";
            case FINALIZING:       return "FINAL";
            default: return p.name();
        }
    }

    static String journalHealthStr(JournalHealth h) {
        if (h == null) return c(ConsoleUI.DIM) + "UNKNOWN" + r();
        switch (h) {
            case HEALTHY:      return c(ConsoleUI.GREEN) + "HEALTHY" + r();
            case BACKPRESSURE: return c(ConsoleUI.BRIGHT_YELLOW) + "BACKPRESSURE" + r();
            case DRAINING:     return c(ConsoleUI.CYAN) + "DRAINING" + r();
            case FAILED:       return c(ConsoleUI.BRIGHT_RED) + "FAILED" + r();
            case CLOSED:       return c(ConsoleUI.DIM) + "CLOSED" + r();
            default:           return c(ConsoleUI.DIM) + h.name() + r();
        }
    }

    static String journalHealthStrPlain(JournalHealth h) {
        return h != null ? h.name() : "UNKNOWN";
    }

    static String activityIndicator(long elapsedMs) {
        // ponytail: simple spinner based on elapsed time
        String[] frames = {"\u2592", "\u2593", "\u2588", "\u2593"};
        int idx = (int) ((elapsedMs / 200) % frames.length);
        return c(ConsoleUI.BRIGHT_CYAN) + frames[idx] + frames[(idx + 1) % 4] + frames[(idx + 2) % 4] + r();
    }

    // ── Stall detection ───────────────────────────────────────────────

    static String stallLabel(long lastProgressMs) {
        long sec = lastProgressMs / 1000;
        if (sec < WAIT_WARN_SECONDS) return "";
        if (sec < STALL_WARN_SECONDS) return "WAITING";
        return "STALLED";
    }

    static String stallColor(long lastProgressMs) {
        long sec = lastProgressMs / 1000;
        if (sec < STALL_WARN_SECONDS) return c(ConsoleUI.BRIGHT_YELLOW);
        return c(ConsoleUI.BRIGHT_RED);
    }

    static String formatLastProgress(long lastProgressMs) {
        if (lastProgressMs < 1000) return "0s";
        long sec = lastProgressMs / 1000;
        if (sec < 60) return sec + "s";
        long min = sec / 60;
        sec = sec % 60;
        return min + "m" + sec + "s";
    }
}
