/*
 * Projekt: CM Migrator 2.2.1.
 * Sole console renderer with reliable terminal-width detection
 * and auto-wrap prevention (outerWidth ≤ terminalWidth - 2).
 */
package com.ibm.ecm.migration;

public final class OperatorConsole {

    public enum RunState { STARTING, RUNNING, STOPPING, COMPLETED, FAILED, INTERRUPTED }

    public enum Phase {
        INITIALIZING, CONNECTING, COUNTING, DISCOVERING, MIGRATING,
        VERIFYING, DELETING, DRAINING_WORKERS, DRAINING_JOURNAL,
        GENERATING_REPORTS, FINALIZING
    }

    public enum JournalHealth { HEALTHY, BACKPRESSURE, DRAINING, FAILED, CLOSED, UNKNOWN }

    public static final String VERSION = "2.2.1";

    static final boolean NO_COLOR = System.getenv("NO_COLOR") != null;
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

    // ── Terminal width resolution ─────────────────────────────────────

    /**
     * Priority:
     * 1. System property cm.migrator.console.columns
     * 2. Environment COLUMNS
     * 3. Conservative fallback: 79 (safe for default terminals)
     */
    static int terminalWidth() {
        // 1. System property
        String prop = System.getProperty("cm.migrator.console.columns");
        if (prop != null) {
            try { int w = Integer.parseInt(prop.trim()); if (w > 0) return w; }
            catch (NumberFormatException ignored) {}
        }
        // 2. Environment
        String cols = System.getenv("COLUMNS");
        if (cols != null) {
            try { int w = Integer.parseInt(cols); if (w > 0) return w; }
            catch (NumberFormatException ignored) {}
        }
        // 3. Conservative default (safe for most SSH sessions)
        return 79;
    }

    /** Outer width of current layout (including borders). */
    static int outerWidth() {
        int tw = terminalWidth();
        if (tw >= 66) return 64;
        if (tw >= 54) return 52;
        return Math.max(1, tw - 2);  // ponytail: never 0 or negative
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
        long journalPersisted = -1;
        JournalHealth journalHealth = JournalHealth.HEALTHY;
        String journalError;
        Long poolSourceLatencyMs;
        Long poolDestLatencyMs;
        Integer poolErrors;
        int configuredWorkers = 0;
        String lastWarning;
        long lastProgressMs = 0;
        boolean streaming = false;
    }

    // ── String helpers ────────────────────────────────────────────────

    public static int visibleLength(String s) {
        if (s == null) return 0;
        int len = 0;
        boolean inEscape = false, inCsi = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inEscape) {
                if (c == '[') { inCsi = true; continue; }
                inEscape = false; continue;
            }
            if (inCsi) {
                if ((c >= '0' && c <= '9') || c == ';' || c == '?' || c == ' ' || (c >= 0x20 && c <= 0x2F))
                    continue;
                inCsi = false; continue;
            }
            if (c == 0x1B) { inEscape = true; continue; }
            if (c == 0x200D || c == 0xFE0F) continue;
            if (Character.isHighSurrogate(c)) { len++; i++; continue; }
            len++;
        }
        return len;
    }

    // ── Box-drawing: appendRow / truncateVisible ──────────────────────

    static void appendRow(StringBuilder out, String leftBorder, String content,
                          int innerWidth, String rightBorder) {
        String visible = truncateVisible(content, innerWidth);
        int pads = innerWidth - visibleLength(visible);
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(leftBorder).append(r());
        out.append(visible);
        for (int i = 0; i < pads; i++) out.append(' ');
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(rightBorder).append(r()).append('\n');
    }

    /**
     * Truncate to maxLen visible characters, preserving ANSI structure.
     * If truncation leaves an unclosed ANSI escape, appends RESET.
     */
    static String truncateVisible(String s, int maxLen) {
        if (s == null || maxLen < 0) return "";
        StringBuilder sb = new StringBuilder();
        int visible = 0;
        boolean inEsc = false, inCsi = false;
        for (int i = 0; i < s.length() && visible < maxLen; i++) {
            char c = s.charAt(i);
            if (inEsc) { sb.append(c); if (c == '[') inCsi = true; else inEsc = false; continue; }
            if (inCsi) { sb.append(c); if ((c < '0' || c > '9') && c != ';' && c != '?' && c != ' ' && !(c >= 0x20 && c <= 0x2F)) inCsi = false; continue; }
            if (c == 0x1B) { inEsc = true; sb.append(c); continue; }
            if (c == 0x200D || c == 0xFE0F) { sb.append(c); continue; }
            if (Character.isHighSurrogate(c)) {
                sb.append(c);
                char next = (i + 1 < s.length()) ? s.charAt(i + 1) : 0;
                if (Character.isLowSurrogate(next)) { sb.append(next); i++; }
                visible++; continue;
            }
            sb.append(c); visible++;
        }
        // Close any unclosed ANSI sequence
        if (inEsc || inCsi) sb.append(ConsoleUI.RESET);
        return sb.toString();
    }

    // ── Terminal control ──────────────────────────────────────────────

    private static int lastLineCount = 0;

    public static String clearScreen() { return "\u001B[2J\u001B[H"; }
    public static String hideCursor()   { return "\u001B[?25l"; }
    public static String showCursor()   { return "\u001B[?25h"; }

    // ── Public API ────────────────────────────────────────────────────

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

        if (lastLineCount > 0) {
            buf.append('\r');
            for (int i = 0; i < lastLineCount; i++) buf.append("\u001B[1A\u001B[2K");
            buf.append('\r');
        }

        int tw = terminalWidth();
        if (tw >= 66)      render(s, buf);
        else if (tw >= 54) renderCompact(s, buf);
        else               renderStacked(s, buf, Math.max(1, tw - 2));

        if (buf.length() == 0 || buf.charAt(buf.length() - 1) != '\n') buf.append('\n');

        System.out.print(buf.toString());
        System.out.flush();

        lastLineCount = 0;
        for (int i = 0; i < buf.length(); i++)
            if (buf.charAt(i) == '\n') lastLineCount++;
    }

    public static void finalRender(Snapshot s) {
        draw(s);
        System.out.print(showCursor());
        System.out.println();
        System.out.flush();
        lastLineCount = 0;
    }

    // ── Pretty mode: full width (≥66 cols, outer 64, inner 62) ────────

    static void render(Snapshot s, StringBuilder out) {
        final int IW = 62;
        String hSep = repeat(box("═", "-"), IW);
        String boxV = box("║", "|");
        boolean knownTotal = s.total > 0;
        double pct = knownTotal ? Math.min(100.0, Math.max(0.0, (double) s.processed / s.total * 100.0)) : 0.0;

        // Header
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(box("╔","+")).append(hSep).append(box("╗","+")).append(r()).append('\n');
        {
            StringBuilder hdr = new StringBuilder();
            hdr.append(c(ConsoleUI.BOLD)).append(" CM Migrator v").append(VERSION).append(r());
            hdr.append(' ').append(modeBadge(s)).append(' ').append(stateBadge(s));
            hdr.append(' ').append(c(ConsoleUI.DIM)).append(timestamp()).append(r());
            appendRow(out, boxV, hdr.toString(), IW, boxV);
        }
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(box("╠","+")).append(hSep).append(box("╣","+")).append(r()).append('\n');

        // Source/Dest/Phase
        {
            StringBuilder line = new StringBuilder();
            line.append(c(ConsoleUI.DIM)).append(trunc(s.sourceSSID, 16)).append(r());
            line.append(c(ConsoleUI.CYAN)).append(" → ").append(r());
            line.append(c(ConsoleUI.DIM)).append(trunc(s.destSSID, 16)).append(r());
            line.append("    ");
            line.append(trunc(s.sourceItemType,14)).append(c(ConsoleUI.CYAN)).append(" → ").append(r()).append(trunc(s.destItemType,14));
            line.append("   ").append(phaseStr(s.phase)).append(" | ").append(trunc(s.strategy,14));
            appendRow(out, boxV, line.toString(), IW, boxV);
        }
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(box("╠","+")).append(hSep).append(box("╣","+")).append(r()).append('\n');

        // Progress
        {
            StringBuilder line = new StringBuilder();
            line.append(" Progress: ");
            if (knownTotal) {
                line.append(ConsoleUI.progressBar(pct, 30));
                line.append(' ').append(c(ConsoleUI.BOLD)).append(fmtPct(pct)).append(r());
            } else if (s.streaming) {
                line.append(c(ConsoleUI.BRIGHT_CYAN)).append(activityIndicator(s.elapsedMs)).append(r());
                line.append(" streaming");
            } else {
                line.append(c(ConsoleUI.DIM)).append("(waiting for total)").append(r());
            }
            appendRow(out, boxV, line.toString(), IW, boxV);
        }

        // Counters line
        {
            StringBuilder line = new StringBuilder();
            line.append("           processed=").append(c(ConsoleUI.BRIGHT_CYAN)).append(fmt(s.processed)).append(r());
            line.append(" / ").append(knownTotal ? fmt(s.total) : c(ConsoleUI.DIM) + "unknown" + r());
            line.append("    discovered=").append(fmt(s.discovered));
            String stall = stallLabel(s.lastProgressMs);
            if (!stall.isEmpty()) { line.append("    ").append(stallColor(s.lastProgressMs)).append(stall).append(r()); }
            appendRow(out, boxV, line.toString(), IW, boxV);
        }

        // Results
        {
            StringBuilder line = new StringBuilder();
            line.append(c(ConsoleUI.GREEN)).append(ConsoleUI.ICON_SUCCESS).append(r()).append(' ').append(fmt(s.success));
            line.append("  ");
            if (s.failed > 0) line.append(c(ConsoleUI.RED)).append(ConsoleUI.ICON_ERROR).append(r()).append(' ').append(c(ConsoleUI.RED)).append(fmt(s.failed)).append(r());
            else             line.append(c(ConsoleUI.DIM)).append(ConsoleUI.ICON_ERROR).append(" 0").append(r());
            if (s.skipped > 0) line.append("  ").append(c(ConsoleUI.YELLOW)).append(ConsoleUI.ICON_WARNING).append(r()).append(' ').append(fmt(s.skipped));
            if (s.deleted > 0 || "DELETE".equalsIgnoreCase(s.mode)) line.append("  ").append(c(ConsoleUI.MAGENTA)).append(ConsoleUI.ICON_TRASH).append(r()).append(' ').append(fmt(s.deleted));
            appendRow(out, boxV, line.toString(), IW, boxV);
        }

        // Speed / ETA / Elapsed
        {
            StringBuilder line = new StringBuilder();
            boolean running = s.state == RunState.RUNNING;
            line.append(c(ConsoleUI.YELLOW)).append(ConsoleUI.ICON_SPEED).append(r());
            if (running) {
                line.append(' ').append(String.format(java.util.Locale.ROOT, "%.1f", s.currentRate)).append(" it/s");
            } else {
                line.append(" stopped");
            }
            line.append(" (avg ").append(String.format(java.util.Locale.ROOT, "%.1f", s.averageRate)).append(')');
            line.append("  ").append(c(ConsoleUI.CYAN)).append(ConsoleUI.ICON_CLOCK).append(r());
            line.append(" ETA: ").append(s.eta);
            line.append("  ").append(c(ConsoleUI.DIM)).append("Elapsed: ").append(fmtDuration(s.elapsedMs)).append(r());
            appendRow(out, boxV, line.toString(), IW, boxV);
        }
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(box("╠","+")).append(hSep).append(box("╣","+")).append(r()).append('\n');

        // Pipeline
        {
            StringBuilder line = new StringBuilder();
            line.append(" Pipeline: queue=").append(fmt(s.queueDepth)).append('/').append(fmt(s.queueCapacity));
            if (s.queueCapacity > 0 && s.queueDepth >= s.queueCapacity) line.append(' ').append(c(ConsoleUI.RED)).append("FULL").append(r());
            line.append("  workers=").append(s.configuredWorkers).append(" configured");
            appendRow(out, boxV, line.toString(), IW, boxV);
        }

        // Journal
        {
            StringBuilder line = new StringBuilder();
            line.append(" Journal: ").append(journalHealthStr(s.journalHealth));
            line.append("  queue=").append(fmt(s.journalQueueDepth)).append('/').append(fmt(s.journalQueueCapacity));
            line.append("  committed=").append(s.journalPersisted >= 0 ? fmt(s.journalPersisted) : c(ConsoleUI.DIM) + "n/a" + r());
            if (s.journalError != null && !s.journalError.isEmpty())
                line.append(' ').append(c(ConsoleUI.RED)).append(trunc(s.journalError, 20)).append(r());
            appendRow(out, boxV, line.toString(), IW, boxV);
        }

        // CM Pools
        {
            StringBuilder line = new StringBuilder();
            line.append(" CM Pools: src=").append(s.poolSourceLatencyMs != null ? s.poolSourceLatencyMs + "ms" : c(ConsoleUI.DIM) + "n/a" + r());
            line.append("  dst=").append(s.poolDestLatencyMs != null ? s.poolDestLatencyMs + "ms" : c(ConsoleUI.DIM) + "n/a" + r());
            line.append("  errors=");
            if (s.poolErrors != null && s.poolErrors > 0) line.append(c(ConsoleUI.RED)).append(s.poolErrors).append(r());
            else if (s.poolErrors != null) line.append(c(ConsoleUI.GREEN)).append('0').append(r());
            else line.append(c(ConsoleUI.DIM)).append("n/a").append(r());
            appendRow(out, boxV, line.toString(), IW, boxV);
        }

        // Optional warning
        if (s.lastWarning != null && !s.lastWarning.isEmpty()) {
            StringBuilder line = new StringBuilder();
            line.append(c(ConsoleUI.YELLOW)).append(ConsoleUI.ICON_WARNING).append(' ').append(trunc(s.lastWarning, 56)).append(r());
            appendRow(out, boxV, line.toString(), IW, boxV);
        }

        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(box("╠","+")).append(hSep).append(box("╣","+")).append(r()).append('\n');

        // Footer
        appendRow(out, boxV, c(ConsoleUI.DIM) + " Ctrl+C to stop gracefully" + r(), IW, boxV);

        // Bottom
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(box("╚","+")).append(hSep).append(box("╝","+")).append(r()).append('\n');
    }

    // ── Compact mode (54-65 cols, outer 52, inner 50) ─────────────────

    static void renderCompact(Snapshot s, StringBuilder out) {
        final int IW = 50;
        String hSep = repeat(box("═", "-"), IW);
        String boxV = box("║", "|");
        boolean knownTotal = s.total > 0;
        double pct = knownTotal ? Math.min(100.0, Math.max(0.0, (double) s.processed / s.total * 100.0)) : 0.0;

        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(box("╔","+")).append(hSep).append(box("╗","+")).append('\n');
        {
            StringBuilder hdr = new StringBuilder();
            hdr.append(c(ConsoleUI.BOLD)).append(" CM Migrator v").append(VERSION).append(' ').append(stateBadge(s));
            appendRow(out, boxV, hdr.toString(), IW, boxV);
        }
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(box("╠","+")).append(hSep).append(box("╣","+")).append('\n');

        {
            StringBuilder line = new StringBuilder();
            line.append(c(ConsoleUI.DIM)).append(trunc(s.sourceSSID, 10)).append(r());
            line.append(c(ConsoleUI.CYAN)).append("→").append(r());
            line.append(c(ConsoleUI.DIM)).append(trunc(s.destSSID, 10)).append(r());
            line.append("  ").append(phaseStr(s.phase));
            appendRow(out, boxV, line.toString(), IW, boxV);
        }

        {
            StringBuilder line = new StringBuilder();
            line.append(' ');
            if (knownTotal) { line.append(ConsoleUI.progressBar(pct, 20)).append(' ').append(fmtPct(pct)); }
            else if (s.streaming) line.append(activityIndicator(s.elapsedMs)).append(" streaming");
            else line.append(c(ConsoleUI.DIM)).append("waiting...").append(r());
            appendRow(out, boxV, line.toString(), IW, boxV);
        }

        {
            StringBuilder line = new StringBuilder();
            line.append(' ').append(c(ConsoleUI.GREEN)).append(ConsoleUI.ICON_SUCCESS).append(r()).append(fmt(s.success));
            line.append(' ').append(c(ConsoleUI.RED)).append(ConsoleUI.ICON_ERROR).append(r());
            line.append(s.failed > 0 ? c(ConsoleUI.RED) + fmt(s.failed) + r() : c(ConsoleUI.DIM) + "0" + r());
            line.append("  ").append(fmt(s.processed)).append('/').append(knownTotal ? fmt(s.total) : "?");
            appendRow(out, boxV, line.toString(), IW, boxV);
        }

        {
            StringBuilder line = new StringBuilder();
            boolean running = s.state == RunState.RUNNING;
            line.append(' ').append(c(ConsoleUI.YELLOW)).append(ConsoleUI.ICON_SPEED).append(r());
            if (running) {
                line.append(' ').append(String.format(java.util.Locale.ROOT, "%.1f", s.currentRate)).append("/s");
            } else {
                line.append(" stopped");
            }
            line.append(" ETA:").append(s.eta);
            appendRow(out, boxV, line.toString(), IW, boxV);
        }
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(box("╠","+")).append(hSep).append(box("╣","+")).append('\n');

        {
            StringBuilder line = new StringBuilder();
            line.append(" q:").append(fmt(s.queueDepth)).append('/').append(fmt(s.queueCapacity)).append(" w:").append(s.configuredWorkers);
            appendRow(out, boxV, line.toString(), IW, boxV);
        }
        {
            StringBuilder line = new StringBuilder();
            line.append(" jnl:").append(journalHealthStr(s.journalHealth)).append(" q:").append(fmt(s.journalQueueDepth)).append('/').append(fmt(s.journalQueueCapacity));
            appendRow(out, boxV, line.toString(), IW, boxV);
        }
        {
            StringBuilder line = new StringBuilder();
            line.append(" pool src=").append(s.poolSourceLatencyMs != null ? s.poolSourceLatencyMs + "ms" : "n/a");
            line.append(" dst=").append(s.poolDestLatencyMs != null ? s.poolDestLatencyMs + "ms" : "n/a");
            line.append(" err=").append(s.poolErrors != null ? String.valueOf(s.poolErrors) : "n/a");
            appendRow(out, boxV, line.toString(), IW, boxV);
        }
        out.append(c(ConsoleUI.BRIGHT_CYAN)).append(box("╚","+")).append(hSep).append(box("╝","+")).append('\n');
    }

    // ── Stacked mode (<54 cols, no box, width ≤ terminal - 2) ─────────

    static void renderStacked(Snapshot s, StringBuilder out, int maxWidth) {
        boolean knownTotal = s.total > 0;
        double pct = knownTotal ? Math.min(100.0, Math.max(0.0, (double) s.processed / s.total * 100.0)) : 0.0;
        boolean running = s.state == RunState.RUNNING;

        out.append(c(ConsoleUI.BOLD)).append("── CM Migrator v").append(VERSION).append(' ');
        out.append(stateBadge(s)).append(' ').append(c(ConsoleUI.DIM)).append(timestamp()).append(r()).append('\n');

        out.append(c(ConsoleUI.DIM)).append(trunc(s.sourceSSID, 30)).append(r());
        out.append(c(ConsoleUI.CYAN)).append(" → ").append(r());
        out.append(c(ConsoleUI.DIM)).append(trunc(s.destSSID, 30)).append(r()).append('\n');

        out.append("phase=").append(phaseStr(s.phase)).append(" mode=").append(s.mode).append(" strategy=").append(s.strategy).append('\n');

        if (knownTotal) {
            out.append("progress=").append(ConsoleUI.progressBar(pct, 20)).append(' ').append(fmtPct(pct)).append('\n');
        } else if (s.streaming) {
            out.append("progress=").append(activityIndicator(s.elapsedMs)).append(" streaming\n");
        } else {
            out.append("progress=(waiting for total)\n");
        }

        out.append("processed=").append(fmt(s.processed));
        out.append(" total=").append(knownTotal ? fmt(s.total) : "unknown");
        out.append(" discovered=").append(fmt(s.discovered)).append('\n');

        out.append("success=").append(fmt(s.success)).append(" failed=").append(fmt(s.failed));
        out.append(" skipped=").append(fmt(s.skipped)).append(" deleted=").append(fmt(s.deleted)).append('\n');

        out.append("speed=");
        if (running) out.append(String.format(java.util.Locale.ROOT, "%.1f", s.currentRate)).append("/s");
        else out.append("stopped");
        out.append(" (avg ").append(String.format(java.util.Locale.ROOT, "%.1f", s.averageRate)).append(")");
        out.append(" ETA=").append(s.eta).append(" elapsed=").append(fmtDuration(s.elapsedMs)).append('\n');

        out.append("queue=").append(fmt(s.queueDepth)).append('/').append(fmt(s.queueCapacity));
        out.append(" workers=").append(s.configuredWorkers).append('\n');

        out.append("journal=").append(journalHealthStrPlain(s.journalHealth));
        out.append(" jq=").append(fmt(s.journalQueueDepth)).append('/').append(fmt(s.journalQueueCapacity));
        out.append(" committed=").append(s.journalPersisted >= 0 ? fmt(s.journalPersisted) : "n/a").append('\n');

        out.append("pool_src=").append(s.poolSourceLatencyMs != null ? s.poolSourceLatencyMs + "ms" : "n/a");
        out.append(" pool_dst=").append(s.poolDestLatencyMs != null ? s.poolDestLatencyMs + "ms" : "n/a");
        out.append(" pool_errors=").append(s.poolErrors != null ? String.valueOf(s.poolErrors) : "n/a").append('\n');

        String stall = stallLabel(s.lastProgressMs);
        if (!stall.isEmpty()) out.append("stall=").append(stall).append('\n');
        if (s.lastWarning != null && !s.lastWarning.isEmpty()) out.append("warning=").append(trunc(sanitize(s.lastWarning), 60)).append('\n');
    }

    // ── Plain mode ────────────────────────────────────────────────────

    static void renderPlain(Snapshot s, StringBuilder out) {
        boolean knownTotal = s.total > 0;
        out.append("state=").append(s.state);
        out.append(" phase=").append(s.phase);
        out.append(" mode=").append(s.mode);
        out.append(" strategy=").append(s.strategy);
        out.append(" source=").append(sanitize(s.sourceSSID));
        out.append(" destination=").append(sanitize(s.destSSID));
        out.append(" processed=").append(s.processed);
        out.append(" total=").append(knownTotal ? String.valueOf(s.total) : "unknown");
        out.append(" discovered=").append(s.discovered);
        out.append(" success=").append(s.success);
        out.append(" failed=").append(s.failed);
        out.append(" skipped=").append(s.skipped);
        out.append(" deleted=").append(s.deleted);
        out.append(" currentRate=").append(String.format(java.util.Locale.ROOT, "%.1f", s.currentRate));
        out.append(" averageRate=").append(String.format(java.util.Locale.ROOT, "%.1f", s.averageRate));
        out.append(" eta=").append(s.eta);
        out.append(" elapsedMs=").append(s.elapsedMs);
        out.append(" queueDepth=").append(s.queueDepth);
        out.append(" queueCapacity=").append(s.queueCapacity);
        out.append(" journalQ=").append(s.journalQueueDepth).append('/').append(s.journalQueueCapacity);
        out.append(" committed=").append(s.journalPersisted >= 0 ? s.journalPersisted : -1);
        out.append(" jHealth=").append(journalHealthStrPlain(s.journalHealth));
        out.append(" workers=").append(s.configuredWorkers);
        out.append(" lastProgressMs=").append(s.lastProgressMs);
        out.append(" streaming=").append(s.streaming);
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private static String c(String code)   { return ConsoleUI.c(code); }
    private static String r()              { return ConsoleUI.RESET; }
    private static String box(String u, String a) { return ConsoleUI.c(ConsoleUI.BRIGHT_CYAN) + (NO_COLOR ? a : u) + ConsoleUI.RESET; }
    private static String repeat(String s, int n) { return ConsoleUI.repeat(s, n); }
    private static String fmt(long n)      { return String.format(java.util.Locale.ROOT, "%,d", n).replace(',', '.'); }
    private static String fmtPct(double p) { return String.format(java.util.Locale.ROOT, "%5.1f%%", p); }
    private static String trunc(String s, int len) { return s != null && s.length() > len ? s.substring(0, len) : (s != null ? s : ""); }
    private static String sanitize(String s){ return s != null ? s.replace("\n", " ").replace("\r", "") : ""; }
    private static String timestamp() { return java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")); }
    private static String fmtDuration(long ms) {
        if (ms < 0) return "--:--";
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                     : String.format(java.util.Locale.ROOT, "%d:%02d", m, sec);
    }

    private static String modeBadge(Snapshot s) {
        String m = s.mode != null ? s.mode : "MIGRATE";
        String color = "DELETE".equalsIgnoreCase(m) ? ConsoleUI.BRIGHT_RED
                     : "VERIFY".equalsIgnoreCase(m) ? ConsoleUI.BRIGHT_CYAN
                     : ConsoleUI.BRIGHT_GREEN;
        return c(color) + m + r();
    }
    private static String stateBadge(Snapshot s) {
        String m = s.state == null ? RunState.RUNNING.toString() : s.state.toString();
        return c(ConsoleUI.CYAN) + m + r();
    }
    private static String phaseStr(Phase p) {
        if (p == null) return "?";
        String n = p.toString().replace('_', ' ').toLowerCase();
        return n.length() > 16 ? n.substring(0, 16) : n;
    }

    private static String activityIndicator(long elapsedMs) {
        long sec = elapsedMs / 1000;
        String[] frames = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
        return frames[(int) (sec % frames.length)];
    }

    private static String stallColor(long ms) {
        if (ms > STALL_WARN_SECONDS * 1000L) return c(ConsoleUI.RED);
        if (ms > WAIT_WARN_SECONDS * 1000L) return c(ConsoleUI.YELLOW);
        return "";
    }
    private static String stallLabel(long ms) {
        long s = ms / 1000;
        if (s > STALL_WARN_SECONDS) return "STALL " + s + "s";
        if (s > WAIT_WARN_SECONDS) return "wait " + s + "s";
        return "";
    }

    private static String journalHealthStr(JournalHealth h) {
        if (h == null) return c(ConsoleUI.DIM) + "unknown" + r();
        switch (h) {
            case HEALTHY:   return c(ConsoleUI.GREEN)   + "HEALTHY"   + r();
            case BACKPRESSURE: return c(ConsoleUI.YELLOW) + "BACKPRESSURE" + r();
            case DRAINING:  return c(ConsoleUI.YELLOW)  + "DRAINING"  + r();
            case FAILED:    return c(ConsoleUI.RED)     + "FAILED"    + r();
            case CLOSED:    return c(ConsoleUI.DIM)     + "CLOSED"    + r();
            default:        return c(ConsoleUI.DIM)     + "UNKNOWN"   + r();
        }
    }
    private static String journalHealthStrPlain(JournalHealth h) {
        return h != null ? h.toString().toLowerCase() : "unknown";
    }
}
