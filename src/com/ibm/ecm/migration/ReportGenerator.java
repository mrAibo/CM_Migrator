/*
 * Projekt: CM Migrator 2.2.1.
 * @Author: Aleksej Voronin, Sven Lindt
 * @Date:   26.01.2026
 */
package com.ibm.ecm.migration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.PrintWriter;
import java.io.FileWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/**
 * Moderner, performanter und barrierefreier HTML-Migrationsreport Generator
 * Features: Dark Mode, Responsive Design, Sortierbare Tabellen, Optimierte Performance
 */
public class ReportGenerator {
    private static final Logger logger = LogManager.getLogger(ReportGenerator.class);
    
    // AVANT-GARDE INDUSTRIAL CSS (King Mode)
    private static final String MODERN_CSS = 
        "@import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;600;800&family=JetBrains+Mono:wght@400;700&display=swap');" +
        ":root {" +
        "    --bg: #f8fafc;" + // Light slate
        "    --panel: #ffffff;" +
        "    --border: #e2e8f0;" +
        "    --accent: #f97316;" + // Vibrant Orange
        "    --primary: #0f172a;" + // Deep Indigo
        "    --success: #16a34a;" +
        "    --error: #dc2626;" +
        "    --text-main: #1e293b;" +
        "    --text-dim: #64748b;" +
        "}" +
        "* { margin: 0; padding: 0; box-sizing: border-box; -webkit-font-smoothing: antialiased; }" +
        "body {" +
        "    font-family: 'Inter', sans-serif;" +
        "    background-color: var(--bg);" +
        "    color: var(--text-main);" +
        "    line-height: 1.5;" +
        "    min-height: 100vh;" +
        "    padding: 20px;" +
        "}" +
        ".container { max-width: 1200px; margin: 0 auto; background: var(--panel); border: 1px solid var(--border); box-shadow: 0 4px 6px -1px rgba(0,0,0,0.1), 0 2px 4px -1px rgba(0,0,0,0.06); border-radius: 12px; overflow: hidden; }" +
        ".hero-section { background: var(--primary); color: #fff; padding: 40px; position: relative; }" +
        ".hero-section::after { content: ''; position: absolute; top: 0; right: 0; width: 300px; height: 100%; background: linear-gradient(90deg, transparent, rgba(249,115,22,0.1)); skew-x: -20deg; transform: translateX(50px); }" +
        ".dashboard-header { display: flex; justify-content: space-between; align-items: center; position: relative; z-index: 1; }" +
        ".title-group h1 { font-size: 2.5rem; font-weight: 800; text-transform: uppercase; letter-spacing: -1px; margin: 0; }" +
        ".title-group .timestamp { color: var(--accent); font-size: 0.85rem; font-weight: 600; margin-top: 4px; font-family: 'JetBrains Mono', monospace; }" +
        ".status-badge { background: rgba(255,255,255,0.1); border: 1px solid rgba(255,255,255,0.2); padding: 8px 16px; border-radius: 99px; font-size: 0.75rem; font-weight: 700; text-transform: uppercase; letter-spacing: 1px; }" +
        ".status-badge.success { color: #4ade80; border-color: #4ade80; }" +
        ".status-badge.error { color: #f87171; border-color: #f87171; background: rgba(248,113,113,0.1); }" +
        ".stats-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 1px; background: var(--border); border-bottom: 4px solid var(--accent); }" +
        ".kpi-item { background: #fff; padding: 30px; text-align: center; }" +
        ".kpi-val { font-family: 'JetBrains Mono', monospace; font-size: 2.2rem; font-weight: 700; color: var(--primary); display: block; line-height: 1; }" +
        ".kpi-lab { font-size: 0.75rem; color: var(--text-dim); text-transform: uppercase; letter-spacing: 1.5px; margin-top: 10px; font-weight: 600; }" +
        ".content-area { padding: 40px; background: #fff; }" +
        ".grid-layout { display: grid; grid-template-columns: repeat(auto-fill, minmax(350px, 1fr)); gap: 30px; }" +
        ".type-card { border: 1px solid var(--border); border-radius: 8px; overflow: hidden; transition: all 0.2s; }" +
        ".type-card:hover { border-color: var(--accent); box-shadow: 0 10px 15px -3px rgba(0,0,0,0.1); }" +
        ".card-head { background: #f8fafc; padding: 16px 20px; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: center; }" +
        ".type-title { font-size: 0.95rem; font-weight: 700; color: var(--primary); }" +
        ".pct-tag { font-family: 'JetBrains Mono', monospace; font-size: 0.85rem; font-weight: 700; color: var(--accent); }" +
        ".prog-bg { height: 6px; background: #e2e8f0; width: 100%; }" +
        ".prog-fill { height: 100%; background: var(--accent); transition: width 1.5s cubic-bezier(0.4, 0, 0.2, 1); }" +
        ".card-body { padding: 20px; display: grid; grid-template-columns: 1fr 1fr; gap: 15px; }" +
        ".data-box { }" +
        ".db-val { font-family: 'JetBrains Mono', monospace; font-size: 1.25rem; font-weight: 700; display: block; color: var(--text-main); }" +
        ".db-lab { font-size: 0.65rem; color: var(--text-dim); text-transform: uppercase; font-weight: 600; }" +
        ".error-box { background: #fff1f2; border-top: 1px solid #fecdd3; padding: 15px; }" +
        ".err-msg { color: var(--error); font-size: 0.75rem; font-family: 'JetBrains Mono', monospace; display: block; margin-bottom: 6px; border-left: 2px solid var(--error); padding-left: 8px; }" +
        ".text-success { color: var(--success); }" +
        ".text-error { color: var(--error); }" +
        ".text-muted { color: var(--text-dim); }" +
        ".sortable-header { cursor: pointer; user-select: none; }" +
        "@media (max-width: 768px) { .stats-grid { grid-template-columns: 1fr 1fr; } .hero-section { padding: 20px; } .title-group h1 { font-size: 1.8rem; } }";
    
    // JavaScript für Interaktivität (Standard String Concatenation für Java 11)
    private static final String INTERACTIVE_JS = 
        "function initTheme() {" +
        "    const savedTheme = localStorage.getItem('theme') || 'light';" +
        "    document.documentElement.setAttribute('data-theme', savedTheme);" +
        "    updateThemeIcon(savedTheme);" +
        "}" +
        "function toggleTheme() {" +
        "    const currentTheme = document.documentElement.getAttribute('data-theme');" +
        "    const newTheme = currentTheme === 'dark' ? 'light' : 'dark';" +
        "    document.documentElement.setAttribute('data-theme', newTheme);" +
        "    localStorage.setItem('theme', newTheme);" +
        "    updateThemeIcon(newTheme);" +
        "}" +
        "function updateThemeIcon(theme) {" +
        "    const icon = document.querySelector('.theme-toggle .icon');" +
        "    if(icon) icon.textContent = theme === 'dark' ? '☀️' : '🌙';" +
        "}" +
        "function toggleErrorSection(header) {" +
        "    const content = header.nextElementSibling;" +
        "    const isExpanded = content.classList.contains('expanded');" +
        "    content.classList.toggle('expanded');" +
        "    const icon = header.querySelector('.toggle-icon');" +
        "    if(icon) icon.textContent = isExpanded ? '▼' : '▲';" +
        "}" +
        "function sortTable(table, column, direction) {" +
        "    const tbody = table.querySelector('tbody');" +
        "    const rows = Array.from(tbody.querySelectorAll('tr'));" +
        "    rows.sort((a, b) => {" +
        "        const aText = a.cells[column].textContent.trim();" +
        "        const bText = b.cells[column].textContent.trim();" +
        "        if (!isNaN(aText) && !isNaN(bText)) {" +
        "            return direction === 'asc' ? parseFloat(aText) - parseFloat(bText) : parseFloat(bText) - parseFloat(aText);" +
        "        }" +
        "        return direction === 'asc' ? aText.localeCompare(bText) : bText.localeCompare(aText);" +
        "    });" +
        "    rows.forEach(row => tbody.appendChild(row));" +
        "    table.querySelectorAll('th').forEach((th, index) => {" +
        "        th.classList.remove('sorted-asc', 'sorted-desc');" +
        "        if (index === column) th.classList.add(direction === 'asc' ? 'sorted-asc' : 'sorted-desc');" +
        "    });" +
        "}" +
        "document.addEventListener('DOMContentLoaded', function() {" +
        "    initTheme();" +
        "    const toggle = document.querySelector('.theme-toggle');" +
        "    if(toggle) toggle.addEventListener('click', toggleTheme);" +
        "    document.querySelectorAll('.error-header').forEach(header => {" +
        "        header.addEventListener('click', () => toggleErrorSection(header));" +
        "    });" +
        "    document.querySelectorAll('.error-table th').forEach((th, index) => {" +
        "        th.classList.add('sortable-header');" +
        "        th.addEventListener('click', () => {" +
        "            const table = th.closest('table');" +
        "            const currentSort = th.classList.contains('sorted-asc') ? 'desc' : 'asc';" +
        "            sortTable(table, index, currentSort);" +
        "        });" +
        "    });" +
        "});";

    public static void generateMigrationReport(MigrationConfig config, MigrationStats stats, String operationMode) {
        String fileName = operationMode.equals("DELETE") ? "deletion_report.html" : "migration_report.html";
        
        if (stats == null) {
            logger.error("Report konnte nicht generiert werden: MigrationStats ist null");
            return;
        }

        try (PrintWriter w = new PrintWriter(new FileWriter(fileName, StandardCharsets.UTF_8))) {
            long durationMs = System.currentTimeMillis() - stats.getStartTime();
            String durationStr = formatDuration(Duration.ofMillis(durationMs));
            double globalRate = calculateSuccessRate(stats.getProcessedItems(), stats.getSuccessItems());
            boolean hasErrors = stats.getFailedItems() > 0;
            
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd. MMMM yyyy 'um' HH:mm 'Uhr'"));
            String title = operationMode.equals("DELETE") ? "🗑️ Datenlöschung" : "🚀 Datenmigration";
            
            w.println("<!DOCTYPE html><html lang='de'><head><meta charset='UTF-8'>");
            w.println("<meta name='viewport' content='width=device-width, initial-scale=1.0'>");
            w.println("<title>" + title + " - Dashboard</title>");
            w.println("<style>" + MODERN_CSS + "</style></head><body>");
            
            w.println("<div class='container'>");
            w.println("<section class='hero-section'>");
            w.println("<header class='dashboard-header'>");
            w.println("<div class='title-group'>");
            w.println("<h1>" + (operationMode.equals("DELETE") ? "DELETION" : "MIGRATION") + " SYSTEM</h1>");
            w.println("<p class='timestamp'>REPORT GENERATED: " + timestamp + "</p></div>");
            w.println("<div class='status-badge " + (hasErrors ? "error" : "success") + "'>");
            w.println((hasErrors ? "SYSTEM FAULT DETECTED" : "OPERATION SUCCESSFUL") + "</div></header></section>");
            
            // Berechne Durchsatz
            double throughput = 0.0;
            if (durationMs > 0) {
                throughput = (double) stats.getProcessedItems() / (durationMs / 1000.0);
            }
            String throughputStr = String.format("%.1f", throughput);
            
            w.println("<div class='stats-grid'>");
            w.println(createStatCard(String.valueOf(stats.getTotalItems()), "TOTAL ITEMS", ""));
            w.println(createStatCard(String.valueOf(stats.getSuccessItems()), "SUCCESSFUL", ""));
            w.println(createStatCard(String.valueOf(stats.getFailedItems()), "FAILURES", ""));
            if (stats.getDeletedItems() > 0) {
                w.println(createStatCard(String.valueOf(stats.getDeletedItems()), "DELETED", ""));
            }
            w.println(createStatCard(durationStr, "DURATION", ""));
            w.println(createStatCard(throughputStr + " /s", "THROUGHPUT", ""));
            w.println("</div>");
            
            w.println("<div class='content-area'>");
            w.println("<div class='grid-layout'>");
            writeItemTypeSections(w, config, operationMode);
            w.println("</div></div></div>");
            
            w.println("<script>" + INTERACTIVE_JS + "</script></body></html>");
            logger.info("Modernes Report generiert: " + fileName);
        } catch (Exception e) {
            logger.error("Fehler beim Generieren des Reports", e);
        }
    }
    
    private static String createStatCard(String value, String label, String style) {
        StringBuilder card = new StringBuilder();
        card.append("<div class='kpi-item'>")
            .append("<span class='kpi-val'>").append(escapeHtml(value)).append("</span>")
            .append("<span class='kpi-lab'>").append(escapeHtml(label)).append("</span>")
            .append("</div>");
        return card.toString();
    }
    
    private static void writeItemTypeSections(PrintWriter w, MigrationConfig config, String operationMode) {
        Map<String, String> mapping = config.getItemTypeMapping();
        String dbBaseDir = config.getDbPath();
        List<ItemTypeStats> allStats = new ArrayList<>();
        
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String sourceType = entry.getKey();
            String destType = entry.getValue();
            try (Connection conn = DriverManager.getConnection("jdbc:h2:" + dbBaseDir + "/journal_" + sourceType + ";IFEXISTS=TRUE", "sa", "")) {
                String statsSql = "SELECT STATUS, COUNT(*) as CNT FROM AUDIT_LOG WHERE ITEM_TYPE = ? GROUP BY STATUS";
                ItemTypeStats stats = new ItemTypeStats(sourceType, destType);
                try (PreparedStatement ps = conn.prepareStatement(statsSql)) {
                    ps.setString(1, sourceType);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String status = rs.getString("STATUS");
                            int cnt = rs.getInt("CNT");
                            if ("SUCCESS".equals(status) || "DELETED".equals(status)) stats.success += cnt;
                            else if ("FAILED".equals(status)) stats.failed += cnt;
                            else stats.skipped += cnt;
                            stats.total += cnt;
                        }
                    }
                }
                if (stats.failed > 0) {
                    String failSql = "SELECT ITEM_ID, MESSAGE FROM AUDIT_LOG WHERE ITEM_TYPE = ? AND STATUS = 'FAILED' ORDER BY MIGRATION_TIME DESC LIMIT 5";
                    try (PreparedStatement ps = conn.prepareStatement(failSql)) {
                        ps.setString(1, sourceType);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) stats.errors.add(new ErrorInfo(rs.getString("ITEM_ID"), rs.getString("MESSAGE")));
                        }
                    }
                }
                allStats.add(stats);
            } catch (Exception e) {
                logger.warn("Daten für " + sourceType + " konnten nicht geladen werden", e);
                allStats.add(new ItemTypeStats(sourceType, destType, true));
            }
        }
        for (ItemTypeStats stats : allStats) writeItemCard(w, stats, operationMode);
    }
    
    private static void writeItemCard(PrintWriter w, ItemTypeStats stats, String operationMode) {
        w.println("<div class='type-card'>");
        w.println("<div class='card-head'>");
        w.println("<div class='type-title'>" + escapeHtml(stats.sourceType) + " &rarr; " + escapeHtml(stats.destType) + "</div>");
        double successRate = calculateSuccessRate(stats.total, stats.success);
        w.println("<div class='pct-tag'>" + String.format("%.1f%%", successRate) + "</div></div>");
        
        w.println("<div class='prog-bg'><div class='prog-fill' style='width: " + successRate + "%'></div></div>");
        
        w.println("<div class='card-body'>");
        w.println("<div class='data-box'><span class='db-val'>" + stats.total + "</span><span class='db-lab'>TOTAL</span></div>");
        w.println("<div class='data-box'><span class='db-val text-success'>" + stats.success + "</span><span class='db-lab'>SUCCESS</span></div>");
        w.println("<div class='data-box'><span class='db-val text-muted'>" + stats.skipped + "</span><span class='db-lab'>SKIPPED</span></div>");
        w.println("<div class='data-box'><span class='db-val " + (stats.failed > 0 ? "text-error" : "text-muted") + "'>" + stats.failed + "</span><span class='db-lab'>FAILED</span></div>");
        w.println("</div>");
        
        if (!stats.errors.isEmpty()) {
            w.println("<div class='error-box'>");
            for (ErrorInfo error : stats.errors) {
                String msg = error.message != null && error.message.length() > 60 ? error.message.substring(0, 57) + "..." : error.message;
                w.println("<span class='err-msg'>ID:" + escapeHtml(error.itemId) + " | " + escapeHtml(msg) + "</span>");
            }
            w.println("</div>");
        }
        w.println("</div>");
    }
    
    private static double calculateSuccessRate(long total, long success) {
        return total > 0 ? ((double) success / total) * 100 : 0.0;
    }
    
    private static String formatDuration(Duration duration) {
        long h = duration.toHours();
        long m = duration.toMinutesPart();
        long s = duration.toSecondsPart();
        if (h > 0) return String.format("%dh %02dm %02ds", h, m, s);
        if (m > 0) return String.format("%dm %02ds", m, s);
        return String.format("%ds", s);
    }
    
    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#x27;");
    }
    
    private static class ItemTypeStats {
        final String sourceType;
        final String destType;
        int total = 0, success = 0, failed = 0, skipped = 0, deleted = 0;
        List<ErrorInfo> errors = new ArrayList<>();
        ItemTypeStats(String s, String d) { sourceType = s; destType = d; }
        ItemTypeStats(String s, String d, boolean e) { this(s, d); }
    }
    
    private static class ErrorInfo {
        final String itemId, message;
        ErrorInfo(String i, String m) { itemId = i; message = m; }
    }

    // --- VERIFICATION REPORTING ---

    public static void generateVerificationReport(MigrationConfig config, MigrationStats stats, Map<String, int[]> verifierResults) {
        String fileName = "verification_report.html";
        
        try (PrintWriter w = new PrintWriter(new FileWriter(fileName, StandardCharsets.UTF_8))) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd. MMMM yyyy 'um' HH:mm 'Uhr'"));
            long failedTotal = stats.getFailedItems();

            String verificationBaseDir = resolveVerificationBaseDir(config);
            String dbUrlAppend = config.getDbUrlAppend();
            List<ItemTypeStats> typeStatsList = new ArrayList<>();
            long failedFromVerifier = 0;
            boolean hasMismatchLog = false;

            for (Map.Entry<String, int[]> entry : verifierResults.entrySet()) {
                String itemType = entry.getKey();
                int[] counts = entry.getValue();

                ItemTypeStats iStats = new ItemTypeStats(itemType, "SHA-256 CHECK");
                if (counts != null && counts.length >= 4) {
                    iStats.success = counts[0];
                    iStats.failed = counts[1];
                    iStats.skipped = counts[2];
                    iStats.total = counts[3];
                    failedFromVerifier += counts[1];
                }

                iStats.errors.addAll(loadVerificationErrors(verificationBaseDir, dbUrlAppend, itemType, 10));
                if (!iStats.errors.isEmpty()) {
                    hasMismatchLog = true;
                }

                typeStatsList.add(iStats);
            }

            long reportFailedTotal = Math.max(failedTotal, failedFromVerifier);
            boolean hasErrors = reportFailedTotal > 0 || hasMismatchLog;
            
            // Re-use stats objects but interpret them for Verification
            String totalItems = String.valueOf(stats.getTotalItems()); // processed
            String successItems = String.valueOf(stats.getSuccessItems()); // verified OK
            String errorItems = String.valueOf(reportFailedTotal); // mismatches/errors

            w.println("<!DOCTYPE html><html lang='de'><head><meta charset='UTF-8'>");
            w.println("<meta name='viewport' content='width=device-width, initial-scale=1.0'>");
            w.println("<title>Verification Report - Dashboard</title>");
            w.println("<style>" + MODERN_CSS + "</style></head><body>");
            
            w.println("<div class='container'>");
            w.println("<section class='hero-section' style='background:var(--primary)'>");
            w.println("<header class='dashboard-header'>");
            w.println("<div class='title-group'>");
            w.println("<h1>INTEGRITY VERIFICATION</h1>");
            w.println("<p class='timestamp'>REPORT GENERATED: " + timestamp + "</p></div>");
            w.println("<div class='item-stats'>");
            w.println("<span>TOTAL: " + stats.getTotalItems() + "</span>");
            w.println("<span class='success'>SUCCESS: " + stats.getSuccessItems() + "</span>");
            w.println("<span class='error'>FAILED: " + reportFailedTotal + "</span>");
            if (stats.getDeletedItems() > 0) {
                w.println("<span class='deleted text-muted'>DELETED: " + stats.getDeletedItems() + "</span>");
            }
            w.println("<span>SKIPPED: " + stats.getSkippedItems() + "</span>");
            w.println("</div>");
            w.println("<div class='status-badge " + (hasErrors ? "error" : "success") + "'>");
            w.println((hasErrors ? "INTEGRITY ISSUES FOUND" : "DATA INTEGRITY VALIDATED") + "</div></header></section>");
            
            w.println("<div class='stats-grid'>");
            w.println(createStatCard(totalItems, "ITEMS CHECKED", ""));
            w.println(createStatCard(successItems, "VALIDATED OK", ""));
            w.println(createStatCard(errorItems, "MISMATCHES", ""));
            if (stats.getDeletedItems() > 0) {
                w.println(createStatCard(String.valueOf(stats.getDeletedItems()), "CASCADE DELETED", ""));
            }
            w.println(createStatCard(String.valueOf(stats.getSkippedItems()), "SKIPPED", ""));
            w.println("</div>");
            
            w.println("<div class='content-area'>");
            w.println("<div class='grid-layout'>");
            
            // Loop through map entries from Verifier
            for (ItemTypeStats iStats : typeStatsList) {
                // We assume Verifier might populate errors separately or we just show counts here
                writeItemCard(w, iStats, "VERIFY"); 
            }
            
            w.println("</div></div></div>");
            w.println("<script>" + INTERACTIVE_JS + "</script></body></html>");
            logger.info("Verification Report generiert: " + fileName);
            
        } catch (Exception e) {
            logger.error("Fehler beim Generieren des Verification Reports", e);
        }
    }
    
    private static String resolveVerificationBaseDir(MigrationConfig config) {
        String dbBaseDir = config.getDbPath();
        if (dbBaseDir == null || dbBaseDir.trim().isEmpty()) {
            dbBaseDir = "./data";
        }
        if (".data".equals(dbBaseDir)) {
            dbBaseDir = "./.data";
        }
        return new File(dbBaseDir).getAbsolutePath();
    }

    private static List<ErrorInfo> loadVerificationErrors(String baseDir, String dbUrlAppend, String sourceType, int limit) {
        List<ErrorInfo> errors = new ArrayList<>();
        if (baseDir == null || baseDir.trim().isEmpty()) {
            return errors;
        }

        String append = dbUrlAppend == null ? "" : dbUrlAppend;
        String jdbcUrl = "jdbc:h2:" + baseDir + File.separator + "journal_" + sourceType + ";IFEXISTS=TRUE" + append;

        try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "")) {
            String tableName = null;
            String idColumn = null;
            String timeColumn = null;

            if (MigrationJournal.isTablePresent(conn, "VERIFICATIONLOG")) {
                tableName = "VERIFICATIONLOG";
                idColumn = "ITEMID";
                timeColumn = "VERIFICATIONTIME";
            } else if (MigrationJournal.isTablePresent(conn, "VERIFICATION_LOG")) {
                tableName = "VERIFICATION_LOG";
                idColumn = "ITEM_ID";
                timeColumn = "VERIFIED_AT";
            }

            if (tableName == null) {
                return errors;
            }

            String sql = "SELECT " + idColumn + ", MESSAGE FROM " + tableName +
                " WHERE STATUS = 'MISMATCH' ORDER BY " + timeColumn + " DESC LIMIT ?";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, Math.max(1, limit));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        errors.add(new ErrorInfo(rs.getString(1), rs.getString(2)));
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Verification mismatch details not available for {}: {}", sourceType, e.getMessage());
        }

        return errors;
    }
}
