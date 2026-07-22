/*
 * Projekt: CM Migrator 2.2.1.
 * @Author: Aleksej Voronin, Sven Lindt
 * @Date:   26.01.2026
 * 
 * Gibt den Migrationsfortschritt auf der Konsole aus und generiert ein status.html-Dashboard.
 * Browser-Benutzeroberfläche: Von „Migration Control" Dashboard (helles Design).
 */
package com.ibm.ecm.migration;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ProgressMonitor implements Runnable {
    private static final Logger logger = LogManager.getLogger(ProgressMonitor.class);

    private final MigrationStats stats;
    private final long intervalMillis;
    private final String sourceSSID;
    private final String destSSID;

    /**
     * Abwärtskompatible einzelne Zuordnungsfelder.
     */
    private final String sourceItemType;
    private final String destItemType;

    private final String operationMode;
    private final LocalDateTime startTimestamp;

    private long lastProcessed = -1;

    private int lastLineCount = 0;

    // Round 5 (Diagnostics): Sliding-Window-Sample für currentDocPerSec.
    // Wird bei jedem printProgress()-Tick aktualisiert. Erstes Sample setzt Anker; weitere
    // Samples berechnen Rate aus delta(processed)/delta(time) seit dem letzten Tick.
    private long prevSampleProcessed = -1L;
    private long prevSampleTimeMs = 0L;

    // Round 6: Console-Mode-Resolution. Pretty rendert den Multi-Line-Dashboard mit ANSI-Clear,
    // Plain liefert genau eine Logzeile pro Tick (für tee/pipe-Umgebungen).
    private enum ConsoleMode { PRETTY, PLAIN }
    private final ConsoleMode consoleMode = resolveConsoleMode();

    private static ConsoleMode resolveConsoleMode() {
        String prop = System.getProperty("cm.migrator.console.mode", "auto").trim().toLowerCase();
        if ("pretty".equals(prop)) return ConsoleMode.PRETTY;
        if ("plain".equals(prop))  return ConsoleMode.PLAIN;
        // auto: pretty nur wenn echtes interaktives Terminal verfügbar ist
        return (System.console() != null) ? ConsoleMode.PRETTY : ConsoleMode.PLAIN;
    }

    public ProgressMonitor(MigrationStats stats,
                           long intervalMillis,
                           String sourceSSID,
                           String destSSID,
                           String sourceItemType,
                           String destItemType) {
        this(stats, intervalMillis, sourceSSID, destSSID, sourceItemType, destItemType, "MIGRATE");
    }

    public ProgressMonitor(MigrationStats stats,
                           long intervalMillis,
                           String sourceSSID,
                           String destSSID,
                           String sourceItemType,
                           String destItemType,
                           String operationMode) {
        this.stats = stats;
        this.intervalMillis = intervalMillis;
        this.sourceSSID = sourceSSID;
        this.destSSID = destSSID;
        this.sourceItemType = sourceItemType;
        this.destItemType = destItemType;
        this.operationMode = operationMode != null ? operationMode : "MIGRATE";
        this.startTimestamp = LocalDateTime.now();
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(intervalMillis);
                printProgress();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        printProgress();
    }

    private void printProgress() {
        long total = stats.getTotalItems();
        long processed = stats.getProcessedItems();
        long success = stats.getSuccessItems();
        long failed = stats.getFailedItems();
        long skipped = stats.getSkippedItems();
        long deleted = stats.getDeletedItems(); // v1.25

        long nowMs = System.currentTimeMillis();
        long elapsedMillis = nowMs - stats.getStartTime();
        double speed = 0.0;
        if (elapsedMillis > 0) {
            double elapsedSeconds = elapsedMillis / 1000.0;
            if (elapsedSeconds > 0) {
                speed = (double) processed / elapsedSeconds;
            }
        }

        // Round 5: aktuelles Sliding-Window-Tempo (delta processed / delta time seit letztem Tick).
        // Bis zum zweiten Sample fällt currentSpeed auf den Average zurück, damit die Anzeige nicht "0" springt.
        double currentSpeed = speed;
        if (prevSampleProcessed >= 0L && nowMs > prevSampleTimeMs) {
            long deltaProcessed = Math.max(0L, processed - prevSampleProcessed);
            long deltaMs = nowMs - prevSampleTimeMs;
            currentSpeed = (deltaProcessed * 1000.0) / deltaMs;
        }
        prevSampleProcessed = processed;
        prevSampleTimeMs = nowMs;

        String eta = "--:--";
        String percentStr = "0.0%";
        double percentVal = 0.0;
        String elapsedStr = formatDuration(Duration.ofMillis(elapsedMillis));

        if (total > 0) {
            percentVal = ((double) processed / total) * 100.0;
            percentStr = String.format("%5.1f%%", percentVal);
            if (processed > 0 && speed > 0) {
                long remainingItems = total - processed;
                long remainingSeconds = (long) (remainingItems / speed);
                eta = formatDuration(Duration.ofSeconds(remainingSeconds));
            }
        }

        // Pool metrics
        CMConnectionPool.PoolMetricsSnapshot pm = CMConnectionPool.getGlobalMetricsSnapshot();
        double srcWaitMs = (pm == null) ? 0.0 : pm.getAvgBorrowWaitMsSource();
        double dstWaitMs = (pm == null) ? 0.0 : pm.getAvgBorrowWaitMsDest();
        long refillAtt   = (pm == null) ? 0L : pm.getRefillAttempts();
        long refillOk    = (pm == null) ? 0L : pm.getRefillSuccess();
        long refillFail  = (pm == null) ? 0L : pm.getRefillFailures();
        long reconnAtt   = (pm == null) ? 0L : pm.getReconnectAttempts();
        long reconnOk    = (pm == null) ? 0L : pm.getReconnectSuccess();
        long reconnFail  = (pm == null) ? 0L : pm.getReconnectFailures();
        long srcBorrow   = (pm == null) ? 0L : pm.getSourceBorrowCount();
        long dstBorrow   = (pm == null) ? 0L : pm.getDestBorrowCount();

        ItemMigrator.PerformanceSnapshot perf = ItemMigrator.getPerformanceSnapshot();
        
        if (processed == lastProcessed && processed < total && total > 0) {
            return;
        }
        lastProcessed = processed;

        // Round 8A: Pretty-Mode malt einen Multi-Line-Dashboard und aktualisiert ihn in-place.
        // Plain-Mode bleibt unverändert: genau eine kompakte logger.info-Zeile pro Tick.
        // Beide Modi schließen sich gegenseitig aus — kein Per-Tick-INFO-Log in Pretty-Mode mehr,
        // damit Logger-Ausgaben nicht in den Dashboard-Text hineingeschrieben werden.
        if (consoleMode == ConsoleMode.PRETTY) {
            String dashboard = renderPrettyDashboard(
                    processed, total, percentVal,
                    currentSpeed, speed, eta, elapsedStr,
                    success, failed, skipped, deleted,
                    srcWaitMs, dstWaitMs,
                    refillAtt, refillOk, refillFail,
                    reconnAtt, reconnOk, reconnFail,
                    perf);

            // Round 8A: Dashboard MUSS mit '\n' enden, damit der Cursor am Ende auf Spalte 0
            // unterhalb der letzten sichtbaren Zeile steht. Anzahl der gerenderten Zeilen
            // entspricht damit exakt der Anzahl '\n' im String.
            if (!dashboard.endsWith("\n")) dashboard = dashboard + "\n";

            StringBuilder buf = new StringBuilder(dashboard.length() + lastLineCount * 8 + 4);
            if (lastLineCount > 0) {
                buf.append('\r'); // sicherstellen, dass wir an Spalte 0 starten
                for (int i = 0; i < lastLineCount; i++) buf.append("\u001B[1A\u001B[2K");
                buf.append('\r'); // nach den Up-/Clear-Sequenzen erneut Spalte 0 erzwingen
            }
            buf.append(dashboard);
            System.out.print(buf.toString());
            System.out.flush(); // ANSI-Sequenzen sofort schreiben, sonst kann der Druck stocken
            lastLineCount = (int) dashboard.chars().filter(ch -> ch == '\n').count();
        } else {
            // Plain-Mode: unverändert — eine Logzeile pro Tick, kein ANSI, kein Dashboard.
            // Round 5: cur=aktuelle Rate (Sliding-Window), avg=Durchschnitt seit Start.
            String logLine = String.format("%s | %s -> %s | %s | %d/%d | cur=%.1f avg=%.1f it/s | ETA %s | ok=%d fail=%d skip=%d del=%d | pool srcWait=%.1fms dstWait=%.1fms refill=%d/%d/%d reconn=%d/%d/%d borrow S=%d D=%d",
                    safe(operationMode),
                    safe(sourceSSID), safe(destSSID),
                    percentStr,
                    processed, total,
                    currentSpeed, speed, eta, success, failed, skipped, deleted,
                    srcWaitMs, dstWaitMs,
                    refillOk, refillAtt, refillFail,
                    reconnOk, reconnAtt, reconnFail,
                    srcBorrow, dstBorrow);
            logger.info(logLine);
        }
        
        // PERF-Zeile nur für Debug-Logging (Console-Ausgabe ist bereits in 'output' enthalten)
        if (perf.totalItems > 0) {
            String perfLine = String.format("PERF [%d items]: Retrieve=%sms | Copy=%sms | Add=%sms | AttrOK=%d | AttrFail=%d",
                    perf.totalItems, perf.avgRetrieve, perf.avgCopy, perf.avgAdd, perf.attrSuccess, perf.attrFailed);
            logger.debug(perfLine);
        }

        writeHtmlStatus(percentVal, percentStr, processed, total, speed, currentSpeed, eta, elapsedStr,
                success, failed, skipped, deleted,
                srcWaitMs, dstWaitMs,
                refillAtt, refillOk, refillFail,
                reconnAtt, reconnOk, reconnFail,
                srcBorrow, dstBorrow,
                perf);
    }

    private void writeHtmlStatus(double percentVal,
                                 String percentStr,
                                 long processed,
                                 long total,
                                 double speed,
                                 double currentSpeed,
                                 String eta,
                                 String elapsed,
                                 long success,
                                 long failed,
                                 long skipped,
                                 long deleted,
                                 double srcWaitMs,
                                 double dstWaitMs,
                                 long refillAtt,
                                 long refillOk,
                                 long refillFail,
                                 long reconnAtt,
                                 long reconnOk,
                                 long reconnFail,
                                 long srcBorrow,
                                 long dstBorrow,
                                 ItemMigrator.PerformanceSnapshot perf) {
        // Round 13B: atomic write — write to .tmp first, then rename so the
        // browser never reads a half-written file. ATOMIC_MOVE preferred,
        // falls back to REPLACE_EXISTING if the FS does not support atomic.
        java.nio.file.Path target = java.nio.file.Paths.get("status.html");
        java.nio.file.Path tmp    = java.nio.file.Paths.get("status.html.tmp");
        try (PrintWriter w = new PrintWriter(new FileWriter(tmp.toFile(), StandardCharsets.UTF_8))) {
            String mode = normalizeMode(operationMode);
            String accentColor = accentForMode(operationMode);
            String accentSoft = accentSoftForMode(operationMode);
            String badgeText = badgeForMode(operationMode);

            long pid = ProcessHandle.current().pid();
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            String speedStr = String.format("%.1f", speed);

            // Build mapping strings safely
            String systemMapping = escapeHtml(safe(sourceSSID)) + " → " + escapeHtml(safe(destSSID));
            String itemTypeMapping = buildItemTypeMappingDisplay(sourceItemType, destItemType);

            // Bei Mehrfachzuordnungen zeigen wir auch eine kompakte einzeilige Variante (bereits maskierte Segmente) an
            String itemTypeMappingOneLine = itemTypeMapping.replace("<br>", " | ");

            // Gauge stroke
            double circumference = 2.0 * Math.PI * 120.0;
            double dashOffset = circumference - (circumference * percentVal / 100.0);

            w.println("<!DOCTYPE html><html lang='de'><head><meta charset='UTF-8'>");
            w.println("<meta name='viewport' content='width=device-width, initial-scale=1'>");
            w.println("<title>Migration Control</title>");

            // Light theme CSS (inspired by your mock, but brighter)
            w.println("<style>@import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;600;700&family=JetBrains+Mono:wght@400;700&display=swap');");
            w.println(":root{" +
                    "--bg:#f6f7fb;" +
                    "--panel:#ffffff;" +
                    "--panel2:#fbfbfe;" +
                    "--border:#e6e8f0;" +
                    "--text:#0f172a;" +
                    "--muted:#667085;" +
                    "--shadow:0 12px 30px rgba(15,23,42,.08);" +
                    "--accent:" + accentColor + ";" +
                    "--accentSoft:" + accentSoft + ";" +
                    "--ok:#16a34a;" +
                    "--warn:#f97316;" +
                    "--bad:#ef4444;" +
                    "}");
            w.println(".text-success {color:var(--ok)} .text-error {color:var(--bad)} .text-warning {color:var(--warn)} .text-muted {color:var(--muted)}");

            w.println("*{box-sizing:border-box} body{margin:0;background:var(--bg);color:var(--text);font-family:Inter,system-ui,Segoe UI,Arial,sans-serif;}");
            w.println(".wrap{max-width:1200px;margin:28px auto;padding:0 18px;}");
            w.println(".top{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;margin-bottom:18px;}");
            w.println(".title{display:flex;gap:12px;align-items:flex-start}");
            w.println(".icon{width:34px;height:34px;border-radius:10px;background:var(--accentSoft);display:flex;align-items:center;justify-content:center;border:1px solid var(--border)}");
            w.println(".icon span{font-size:18px;color:var(--accent)}");
            w.println("h1{font-size:18px;margin:0;font-weight:700;letter-spacing:.2px}");
            w.println(".subtitle{margin-top:4px;font-size:12px;color:var(--muted)}");
            w.println(".badgeRow{display:flex;align-items:center;gap:10px}");
            w.println(".badge{background:var(--accent);color:#fff;padding:6px 10px;border-radius:999px;font-size:12px;font-weight:700;letter-spacing:.3px}");
            w.println(".clock{font-family:JetBrains Mono,monospace;color:var(--muted);font-size:12px;margin-top:6px;text-align:right}");

            w.println(".stack{display:flex;flex-direction:column;gap:14px}");
            w.println(".card{background:var(--panel);border:1px solid var(--border);border-radius:16px;box-shadow:var(--shadow);padding:16px}");
            w.println(".card h3{margin:0 0 10px 0;font-size:12px;color:var(--muted);text-transform:uppercase;letter-spacing:.12em}");
            w.println(".stat-card{background:var(--panel);border:1px solid var(--border);border-radius:16px;box-shadow:var(--shadow);padding:12px 16px;margin-bottom:18px;display:flex;justify-content:space-between;align-items:center;gap:12px}");
            w.println(".stat-label{font-size:12px;color:var(--muted);text-transform:uppercase;letter-spacing:.12em;font-weight:700}");
            w.println(".stat-value{font-family:JetBrains Mono,monospace;font-weight:800;color:var(--text);text-align:right}");
            w.println(".stat-value small{font-size:11px;color:var(--muted);font-weight:600}");

            w.println(".metric{display:flex;justify-content:space-between;align-items:flex-end;gap:10px}");
            w.println(".metric .val{font-size:28px;font-weight:800;line-height:1}");
            w.println(".metric .sub{font-size:12px;color:var(--muted);margin-top:6px}");
            w.println(".bar{height:8px;background:#eef2ff;border-radius:999px;overflow:hidden;margin-top:12px;border:1px solid var(--border)}");
            w.println(".bar > div{height:100%;background:var(--accent);width:" + Math.min(100.0, Math.max(0.0, percentVal)) + "%;}");

            w.println(".gauge{display:flex;align-items:center;justify-content:center;min-height:380px}");
            w.println(".gWrap{position:relative;width:340px;height:340px;background:linear-gradient(180deg,#ffffff 0%, #fbfbfe 100%);border:1px solid var(--border);border-radius:22px;box-shadow:var(--shadow);display:flex;align-items:center;justify-content:center}");
            w.println(".gSvg{width:320px;height:320px;transform:rotate(-90deg)}");
            w.println(".gBg{fill:none;stroke:#e9edf7;stroke-width:16}");
            w.println(".gFill{fill:none;stroke:var(--accent);stroke-width:16;stroke-linecap:round;stroke-dasharray:" + circumference + ";stroke-dashoffset:" + dashOffset + ";transition:stroke-dashoffset 1s ease;filter:drop-shadow(0 6px 14px rgba(249,115,22,.25));}");
            w.println(".gText{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center}");
            w.println(".gVal{font-size:54px;font-weight:900;letter-spacing:-.02em}");
            w.println(".gLab{font-size:12px;color:var(--muted);text-transform:uppercase;letter-spacing:.16em;margin-top:6px}");
            w.println(".gSmall{display:flex;gap:18px;margin-top:16px;color:var(--muted);font-size:12px}");
            w.println(".pill{background:var(--panel);border:1px solid var(--border);padding:8px 10px;border-radius:12px;box-shadow:0 8px 18px rgba(15,23,42,.06)}");
            w.println(".pill b{color:var(--text)}");

            w.println(".kv{display:grid;grid-template-columns:1fr;gap:10px}");
            w.println(".kvRow{display:flex;justify-content:space-between;gap:12px;padding:10px 12px;border:1px solid var(--border);border-radius:14px;background:var(--panel2)}");
            w.println(".kvRow .k{color:var(--muted);font-size:12px}");
            w.println(".kvRow .v{font-weight:700;font-size:12px;text-align:right;max-width:220px;word-break:break-word}");
            w.println(".kvRow .v.mono{font-family:JetBrains Mono,monospace;font-weight:700}");

            w.println(".logs{margin-top:18px;background:var(--panel);border:1px solid var(--border);border-radius:16px;box-shadow:var(--shadow);padding:14px}");
            w.println(".logsHeader{display:flex;justify-content:space-between;align-items:center;margin-bottom:10px}");
            w.println(".logsHeader span{font-size:12px;color:var(--muted);text-transform:uppercase;letter-spacing:.12em}");
            w.println(".logsBody{font-family:JetBrains Mono,monospace;font-size:12px;line-height:1.55;background:#0b1220;color:#e5e7eb;border-radius:12px;padding:12px;height:170px;overflow:auto;border:1px solid rgba(255,255,255,.06)}");
            w.println(".tagP{display:inline-block;padding:2px 8px;border-radius:999px;font-size:11px;font-weight:700;margin-right:8px;background:rgba(255,255,255,.08);color:#fff}");
            w.println(".tNet{background:rgba(34,197,94,.18);color:#86efac}");
            w.println(".tPool{background:rgba(59,130,246,.18);color:#93c5fd}");
            w.println(".tSys{background:rgba(249,115,22,.18);color:#fdba74}");
            w.println(".tData{background:rgba(168,85,247,.18);color:#e9d5ff}");

            w.println(".stats-grid{display:grid;grid-template-columns:320px 1fr 320px;gap:18px;align-items:start}");

            w.println("@media (max-width: 1100px){.stats-grid{grid-template-columns:1fr}.gauge{order:-1}}");
            w.println("</style></head><body>");

            // Header
            w.println("<div class='wrap'>");
            w.println("  <div class='top'>");
            w.println("    <div class='title'>");
            w.println("      <div class='icon'><span>⛁</span></div>");
            w.println("      <div>");
            w.println("        <h1>Migration Control</h1>");
            w.println("        <div class='subtitle'>" + systemMapping + "</div>");
            w.println("      </div>");
            w.println("    </div>");
            w.println("    <div>");
            w.println("      <div class='badgeRow'><div class='badge'>" + escapeHtml(badgeText) + "</div></div>");
            w.println("      <div class='clock'>" + escapeHtml(timestamp) + "</div>");
            w.println("    </div>");
            w.println("  </div>");
            w.println("  <div class='stat-card'>");
            w.println("    <div class='stat-label'>Avg Timing (Retrieve/Copy/Add)</div>");
            w.println("    <div class='stat-value' style='font-size: 1.2rem;'>" + perf.avgRetrieve + " / " + perf.avgCopy + " / " + perf.avgAdd + " <small>ms</small></div>");
            w.println("  </div>");
            w.println("");
            w.println("<div class='stats-grid'>");

            // Left stack
            w.println("    <div class='stack'>");
            // processed card
            w.println("      <div class='card'>");
            w.println("        <h3>Processed</h3>");
            w.println("        <div class='metric'><div><div class='val'>" + fmtInt(processed) + "</div><div class='sub'>of " + fmtInt(total) + " items</div></div></div>");
            w.println("        <div class='bar'><div style='width:" 
                    + pctCss(percentVal) 
                    + ";background:" + accentColor + ";height:100%;border-radius:999px'></div></div>");
            w.println("      </div>");
            // success
            w.println("      <div class='card'>");
            w.println("        <h3>Success</h3>");
            w.println("        <div class='metric'><div><div class='val text-success'>" + fmtInt(success) + "</div><div class='sub'>" + formatRate(success, Math.max(1, processed)) + " success rate</div></div></div>");
            w.println("        <div class='bar'><div style='width:" 
                    + pctCss(processed > 0 ? (100.0 * success / processed) : 0.0)
                    + ";background:var(--ok);height:100%;border-radius:999px'></div></div>");
            w.println("      </div>");
            // failed
            w.println("      <div class='card'>");
            w.println("        <h3>Failed</h3>");
            w.println("        <div class='metric'><div><div class='val text-error'>" + fmtInt(failed) + "</div><div class='sub'>Review required</div></div></div>");
            w.println("        <div class='bar'><div style='width:" 
                    + pctCss(processed > 0 ? (100.0 * failed / processed) : 0.0)
                    + ";background:var(--bad);height:100%;border-radius:999px'></div></div>");
            w.println("      </div>");
            w.println("      <div class='card'>");
            w.println("        <h3>Deleted</h3>");
            w.println("        <div class='metric'><div><div class='val text-muted'>" + fmtInt(deleted) + "</div><div class='sub'>Cascade deletes</div></div></div>");
            w.println("        <div class='bar'><div style='width:" 
                    + pctCss(total > 0 ? (100.0 * deleted / total) : 0.0)
                    + ";background:var(--muted);height:100%;border-radius:999px'></div></div>");
            w.println("      </div>");
            // speed (Round 5: current und avg)
            String currentSpeedStr = String.format("%.1f", currentSpeed);
            w.println("      <div class='card'>");
            w.println("        <h3>Speed</h3>");
            w.println("        <div class='metric'><div><div class='val'>" + escapeHtml(currentSpeedStr) + "</div><div class='sub'>items/s current · avg " + escapeHtml(speedStr) + "</div></div></div>");
            w.println("      </div>");
            w.println("    </div>");

            // Center gauge
            w.println("    <div class='gauge'>");
            w.println("      <div class='gWrap'>");
            w.println("        <svg class='gSvg' viewBox='0 0 300 300'>" +
                    "<circle class='gBg' cx='150' cy='150' r='120' />" +
                    "<circle class='gFill' cx='150' cy='150' r='120' " +
                    "style='stroke:" + accentColor +
                    ";stroke-dasharray:" + cssNumber(circumference) +
                    ";stroke-dashoffset:" + cssNumber(dashOffset) +
                    ";' />" +
                    "</svg>");
            w.println("        <div class='gText'>");
            w.println("          <div class='gVal'>" + escapeHtml(percentStr.trim()) + "</div>");
            w.println("          <div class='gLab'>Complete</div>");
            w.println("          <div class='gSmall'>" +
                    "<div class='pill'>Elapsed<br><b>" + escapeHtml(elapsed) + "</b></div>" +
                    "<div class='pill'>Remaining<br><b>" + escapeHtml(eta) + "</b></div>" +
                    "</div>");
            w.println("        </div>");
            w.println("      </div>");
            w.println("    </div>");

            // Right stack
            w.println("    <div class='stack'>");
            // system connection
            w.println("      <div class='card'>");
            w.println("        <h3>System connection</h3>");
            w.println("        <div class='kv'>");
            w.println("          <div class='kvRow'><div class='k'>Source</div><div class='v mono'>" + escapeHtml(safe(sourceSSID)) + "</div></div>");
            w.println("          <div class='kvRow'><div class='k'>Target</div><div class='v mono'>" + escapeHtml(safe(destSSID)) + "</div></div>");
            w.println("          <div class='kvRow'><div class='k'>Item type</div><div class='v mono'>" + itemTypeMapping + "</div></div>");
            w.println("        </div>");
            w.println("      </div>");

            // pool metrics with alert system
            boolean poolWarning = srcWaitMs > 1000 || dstWaitMs > 1000;
            boolean poolCritical = refillFail > 5 || reconnFail > 5;
            String poolBorderColor = poolCritical ? "var(--bad)" : (poolWarning ? "var(--warn)" : "var(--border)");
            
            w.println("      <div class='card' style='border-color:" + poolBorderColor + ";border-width:2px'>");
            w.println("        <h3 style='display:flex;justify-content:space-between;align-items:center'>Pool metrics");
            if (poolCritical) {
                w.println("<span style='background:var(--bad);color:#fff;padding:2px 8px;border-radius:99px;font-size:10px'>⚠ CRITICAL</span>");
            } else if (poolWarning) {
                w.println("<span style='background:var(--warn);color:#fff;padding:2px 8px;border-radius:99px;font-size:10px'>⚠ WARNING</span>");
            } else {
                w.println("<span style='background:var(--ok);color:#fff;padding:2px 8px;border-radius:99px;font-size:10px'>● HEALTHY</span>");
            }
            w.println("</h3>");
            w.println("        <div class='kv'>");
            w.println("          <div class='kvRow'><div class='k'>Source wait</div><div class='v mono " + (srcWaitMs > 1000 ? "text-error" : srcWaitMs > 500 ? "text-warning" : "text-success") + "'>" + String.format("%.1f ms", srcWaitMs) + "</div></div>");
            w.println("          <div class='kvRow'><div class='k'>Dest wait</div><div class='v mono " + (dstWaitMs > 1000 ? "text-error" : dstWaitMs > 500 ? "text-warning" : "text-success") + "'>" + String.format("%.1f ms", dstWaitMs) + "</div></div>");
            w.println("          <div class='kvRow'><div class='k'>Refill failures</div><div class='v mono " + (refillFail > 5 ? "text-error" : refillFail > 0 ? "text-warning" : "text-success") + "'>" + refillFail + "</div></div>");
            w.println("          <div class='kvRow'><div class='k'>Reconnect failures</div><div class='v mono " + (reconnFail > 5 ? "text-error" : reconnFail > 0 ? "text-warning" : "text-success") + "'>" + reconnFail + "</div></div>");
            // Round 5: zusätzliche Pool-Counter (read-only, keine Verhaltensänderung).
            w.println("          <div class='kvRow'><div class='k'>Refill ok / attempts</div><div class='v mono'>" + refillOk + " / " + refillAtt + "</div></div>");
            w.println("          <div class='kvRow'><div class='k'>Reconnect ok / attempts</div><div class='v mono'>" + reconnOk + " / " + reconnAtt + "</div></div>");
            w.println("          <div class='kvRow'><div class='k'>Borrow count S / D</div><div class='v mono'>" + srcBorrow + " / " + dstBorrow + "</div></div>");
            w.println("        </div>");
            w.println("      </div>");

            w.println("    </div>");
            w.println("  </div>");

            // Logs
            w.println("  <div class='logs'>");
            w.println("    <div class='logsHeader'><span>System status stream</span><span>" + fmtInt(processed) + " / " + fmtInt(total) + "</span></div>");
            w.println("    <div class='logsBody'>" +
                    "<div><span class='tagP tNet'>NET</span>Link stable: " + systemMapping + "</div>" +
                    "<div><span class='tagP tSys'>SYSTEM</span>Mode: " + escapeHtml(mode) + " (PID " + pid + ")</div>" +
                    "<div><span class='tagP tPool'>POOL</span>avgWait(ms) S=" + String.format("%.1f", srcWaitMs) + " D=" + String.format("%.1f", dstWaitMs) + ", refillFail=" + refillFail + ", reconnFail=" + reconnFail + "</div>" +
                    "<div><span class='tagP tData'>DATA</span>Item type mapping: " + itemTypeMappingOneLine + "</div>" +
                    "<div><span class='tagP tSys'>SYSTEM</span>Elapsed: " + escapeHtml(elapsed) + ", Remaining: " + escapeHtml(eta) + "</div>" +
                    "</div>");
            w.println("  </div>");
            w.println("</div>");
            w.println("<script>");
            w.println("setInterval(() => {");
            w.println("  window.location.reload();");
            w.println("}, 2000);");
            w.println("</script>");
            w.println("</body></html>");
        } catch (IOException e) {
            logger.error("Failed to write status.html", e);
            return;
        }
        // After the try-with-resources flushes/closes the tmp file, move into place.
        try {
            java.nio.file.Files.move(tmp, target,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException amnse) {
            try {
                java.nio.file.Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                if (!atomicMoveFallbackLogged) {
                    atomicMoveFallbackLogged = true;
                    logger.debug("Filesystem does not support ATOMIC_MOVE for status.html; using REPLACE_EXISTING fallback.");
                }
            } catch (IOException e) {
                logger.error("Failed to rename status.html.tmp -> status.html", e);
            }
        } catch (IOException e) {
            logger.error("Failed to rename status.html.tmp -> status.html", e);
        }
    }
    private static volatile boolean atomicMoveFallbackLogged = false;

    private static String normalizeMode(String op) {
        if (op == null) return "MIGRATION";
        String u = op.toUpperCase();
        if (u.contains("DELETE")) return "DELETION";
        if (u.contains("VERIFY")) return "VERIFICATION";
        return "MIGRATION";
    }

    private static String badgeForMode(String op) {
        if (op == null) return "MIGRATION";
        String u = op.toUpperCase();
        if (u.contains("DELETE")) return "DELETE";
        if (u.contains("VERIFY")) return "VERIFY";
        return "MIGRATION";
    }

    private static String accentForMode(String op) {
        if (op == null) return "#f97316"; // orange
        String u = op.toUpperCase();
        if (u.contains("DELETE")) return "#ef4444"; // red
        if (u.contains("VERIFY")) return "#2563eb"; // blue
        return "#f97316"; // orange
    }

    private static String accentSoftForMode(String op) {
        if (op == null) return "#ffedd5";
        String u = op.toUpperCase();
        if (u.contains("DELETE")) return "#fee2e2";
        if (u.contains("VERIFY")) return "#dbeafe";
        return "#ffedd5";
    }

    /**
     * Erstellt eine Anzeigestring für die Zuordnung von Elementtypen.
     * 
     * Unterstützte Eingaben:
     * - Wenn sourceItemType eine Zuordnungszeichenfolge wie „A:B, C:D" enthält => wird angezeigt als A → B, C → D.
     * - Andernfalls wird sourceItemType + destItemType verwendet => „A → B".
     * - Wenn keine Angabe => „ALL".
     *
     * Die zurückgegebene Zeichenfolge kann ein Linebreak für die Anzeige über mehrere Zeilen enthalten.
     */
    private static String buildItemTypeMappingDisplay(String sourceItemType, String destItemType) {
        String s = (sourceItemType == null) ? "" : sourceItemType.trim();
        String d = (destItemType == null) ? "" : destItemType.trim();

        if (!s.isEmpty() && (s.contains(":" ) || s.contains(","))) {
            // Parse MIGRATE_ITEMTYPES style: A:B, C:D, X
            String[] pairs = s.split(",");
            List<String> lines = new ArrayList<>();
            for (String pair : pairs) {
                String p = pair.trim();
                if (p.isEmpty()) continue;
                String[] parts = p.split(":");
                if (parts.length == 2) {
                    String a = parts[0].trim();
                    String b = parts[1].trim();
                    if (!a.isEmpty() && !b.isEmpty()) {
                        lines.add(escapeHtml(a) + " → " + escapeHtml(b));
                    }
                } else if (parts.length == 1) {
                    String a = parts[0].trim();
                    if (!a.isEmpty()) {
                        lines.add(escapeHtml(a) + " → " + escapeHtml(a));
                    }
                }
            }
            if (lines.isEmpty()) return "ALL";
            // cap output
            if (lines.size() > 4) {
                List<String> first = lines.subList(0, 4);
                return String.join("<br>", first) + "<br>… (" + (lines.size() - 4) + " more)";
            }
            return String.join("<br>", lines);
        }

        if (!s.isEmpty() && !d.isEmpty()) {
            return escapeHtml(s) + " → " + escapeHtml(d);
        }

        if (!s.isEmpty()) {
            return escapeHtml(s);
        }

        return "ALL";
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String fmtInt(long n) {
        return String.format("%,d", n).replace(',', '.');
    }

    private static String formatRate(long ok, long denom) {
        if (denom <= 0) return "0.0%";
        return String.format("%.2f%%", (100.0 * ok / denom));
    }

    private static String cssNumber(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "0";
        }
        return String.format(java.util.Locale.US, "%.4f", value);
    }

    private static String pctCss(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            value = 0.0;
        }
        value = Math.max(0.0, Math.min(100.0, value));
        return cssNumber(value) + "%";
    }

    private String formatDuration(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        if (hours > 0) return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        if (minutes > 0) return String.format("%02d:%02d", minutes, seconds);
        return String.format("%02ds", seconds);
    }

    /**
     * Round 6: Operator-freundlicher Multi-Line-Dashboard (Pretty-Mode).
     * Header, Bar, Speed, Counts, Perf, Pool, optionale Warnungen.
     */
    private String renderPrettyDashboard(long processed, long total, double percentVal,
                                         double currentSpeed, double avgSpeed,
                                         String eta, String elapsed,
                                         long success, long failed, long skipped, long deleted,
                                         double srcWaitMs, double dstWaitMs,
                                         long refillAtt, long refillOk, long refillFail,
                                         long reconnAtt, long reconnOk, long reconnFail,
                                         ItemMigrator.PerformanceSnapshot perf) {
        final int barWidth = 30;
        int filled = (int) Math.round(Math.max(0.0, Math.min(100.0, percentVal)) / 100.0 * barWidth);
        StringBuilder bar = new StringBuilder(barWidth + 2);
        bar.append('[');
        for (int i = 0; i < barWidth; i++) bar.append(i < filled ? '=' : ' ');
        bar.append(']');

        StringBuilder out = new StringBuilder(512);
        out.append(safe(operationMode)).append("  ").append(safe(sourceSSID)).append(" -> ").append(safe(destSSID)).append('\n');
        out.append(safe(sourceItemType)).append(" -> ").append(safe(destItemType)).append('\n');
        out.append(bar).append(' ').append(String.format("%5.1f%%", percentVal))
           .append("  ").append(fmtInt(processed)).append('/').append(fmtInt(total)).append('\n');
        out.append(String.format("cur: %.1f it/s | avg: %.1f it/s | ETA: %s | elapsed: %s",
                currentSpeed, avgSpeed, eta, elapsed)).append('\n');
        out.append(String.format("ok: %d | fail: %d | skip: %d | del: %d",
                success, failed, skipped, deleted)).append('\n');
        if (perf.totalItems > 0) {
            out.append(String.format("retrieve: %s ms | copy: %s ms | add: %s ms",
                    perf.avgRetrieve, perf.avgCopy, perf.avgAdd)).append('\n');
        }
        out.append(String.format("srcWait: %.1f ms | dstWait: %.1f ms | refill: %d/%d/%d | reconn: %d/%d/%d",
                srcWaitMs, dstWaitMs, refillOk, refillAtt, refillFail, reconnOk, reconnAtt, reconnFail));

        boolean anyWarn = (failed > 0) || (refillFail > 0) || (reconnFail > 0);
        if (anyWarn) {
            out.append('\n').append("WARN:");
            if (failed > 0)     out.append(" failed=").append(failed);
            if (refillFail > 0) out.append(" refillFailures=").append(refillFail);
            if (reconnFail > 0) out.append(" reconnFailures=").append(reconnFail);
        }
        return out.toString();
    }
}

