/*
 * Projekt: CM Migrator 2.2.1.
 * @Author: Aleksej Voronin, Sven Lindt
 * @Date:   26.01.2026
 */

package com.ibm.ecm.migration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Generates audit protocols (Prüfprotokolle) per ItemType.
 * 
 * Dieses Protocoll erstellt A4 Dokument:
 * - Migration Statistic
 * - Verification result
 * - Missing items (if any)
 * - Unterschrift und Bemerkungen
 */
public class AuditProtocolGenerator {
    private static final Logger logger = LogManager.getLogger(AuditProtocolGenerator.class);

    private static final String PRINT_CSS = 
        "@import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;600;700&display=swap');" +
        ":root { --primary: #0f172a; --muted: #64748b; --accent: #f97316; --ok: #16a34a; --bad: #dc2626; }" +
        "@page { size: A4 portrait; margin: 20mm; }" +
        "* { margin: 0; padding: 0; box-sizing: border-box; }" +
        "body { font-family: 'Inter', Arial, sans-serif; font-size: 11pt; line-height: 1.5; color: #1e293b; background: #fff; padding: 0; }" +
        ".protocol { max-width: 800px; margin: 0 auto; padding: 20px; }" +
        ".header { border-bottom: 3px solid var(--primary); padding-bottom: 15px; margin-bottom: 20px; }" +
        ".header h1 { font-size: 18pt; font-weight: 700; text-transform: uppercase; color: var(--primary); margin: 0; }" +
        ".header .subtitle { font-size: 10pt; color: var(--muted); margin-top: 5px; }" +
        ".meta-box { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 6px; padding: 15px; margin-bottom: 20px; }" +
        ".meta-row { display: flex; justify-content: space-between; padding: 5px 0; border-bottom: 1px dotted #e2e8f0; }" +
        ".meta-row:last-child { border-bottom: none; }" +
        ".meta-label { color: var(--muted); font-size: 10pt; }" +
        ".meta-value { font-weight: 600; font-size: 10pt; }" +
        ".section { margin-bottom: 20px; }" +
        ".section h2 { font-size: 12pt; font-weight: 700; color: var(--primary); border-bottom: 2px solid var(--accent); padding-bottom: 5px; margin-bottom: 10px; }" +
        ".stats-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }" +
        ".stat-box { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 6px; padding: 12px; text-align: center; }" +
        ".stat-val { font-size: 20pt; font-weight: 700; color: var(--primary); }" +
        ".stat-val.ok { color: var(--ok); }" +
        ".stat-val.err { color: var(--bad); }" +
        ".stat-label { font-size: 9pt; color: var(--muted); text-transform: uppercase; }" +
        ".verify-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-top: 10px; }" +
        ".missing-list { background: #fef2f2; border: 1px solid #fecaca; border-radius: 6px; padding: 10px; max-height: 150px; overflow-y: auto; }" +
        ".missing-list.empty { background: #f0fdf4; border-color: #bbf7d0; color: var(--ok); text-align: center; font-style: italic; }" +
        ".missing-item { font-family: monospace; font-size: 9pt; color: var(--bad); padding: 2px 0; }" +
        ".signature-box { border: 2px solid var(--primary); border-radius: 6px; padding: 20px; margin-top: 30px; }" +
        ".signature-row { display: flex; gap: 30px; margin-bottom: 15px; }" +
        ".signature-field { flex: 1; }" +
        ".signature-field label { display: block; font-size: 9pt; color: var(--muted); margin-bottom: 5px; }" +
        ".signature-field .line { border-bottom: 1px solid var(--primary); height: 30px; }" +
        ".remarks-field { margin-top: 15px; }" +
        ".remarks-field label { display: block; font-size: 9pt; color: var(--muted); margin-bottom: 5px; }" +
        ".remarks-lines { border: 1px solid #e2e8f0; border-radius: 4px; min-height: 60px; }" +
        ".footer { margin-top: 30px; text-align: center; font-size: 8pt; color: #94a3b8; border-top: 1px solid #e2e8f0; padding-top: 10px; }" +
        ".sample-table { width: 100%; border-collapse: collapse; font-size: 9pt; margin-top: 10px; }" +
        ".sample-table th { padding: 8px; text-align: left; color: var(--muted); border-bottom: 2px solid #e2e8f0; }" +
        ".sample-table td { padding: 8px; border-bottom: 1px solid #e2e8f0; }" +
        ".text-error { color: var(--bad); font-weight: bold; }" +
        "@media print { .protocol { padding: 0; } }";

    /**
     * Generates audit protocols for all configured ItemTypes.
     * 
     * @param config       Migration configuration
     * @param baseDir      Journal database directory
     * @param outputDir    Output directory for protocols
     */
    public static void generateProtocols(MigrationConfig config, String baseDir, String outputDir) {
        Map<String, String> mapping = config.getItemTypeMapping();
        if (mapping == null || mapping.isEmpty()) {
            logger.warn("No item type mapping configured - skipping audit protocol generation");
            return;
        }

        // Ist output directory existiert?
        File outDir = new File(outputDir);
        if (!outDir.exists()) {
            outDir.mkdirs();
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));

        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String sourceType = entry.getKey();
            String destType = entry.getValue();

            try {
                generateProtocolForType(config, baseDir, outputDir, sourceType, destType, timestamp);
            } catch (Exception e) {
                logger.error("Failed to generate audit protocol for {}: {}", sourceType, e.getMessage(), e);
            }
        }

        // Generiere gemeinsame Protocol für alle ItemTypes
        try {
            generateMasterProtocol(config, baseDir, outputDir, timestamp);
        } catch (Exception e) {
            logger.error("Failed to generate master audit protocol: {}", e.getMessage(), e);
        }
    }

    private static void generateProtocolForType(MigrationConfig config, 
                                                 String baseDir, 
                                                 String outputDir,
                                                 String sourceType, 
                                                 String destType,
                                                 String timestamp) throws Exception {
        
        String fileName = String.format("PRUEFPROTOKOLL_%s_%s.html", sourceType, timestamp);
        File outFile = new File(outputDir, fileName);

        // Collect statistics from journal and verification databases
        AuditStats stats = collectStats(baseDir, sourceType);

        try (PrintWriter w = new PrintWriter(new FileWriter(outFile, StandardCharsets.UTF_8))) {
            String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
            String dateGenerated = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd. MMMM yyyy"));

            w.println("<!DOCTYPE html><html lang='de'><head><meta charset='UTF-8'>");
            w.println("<meta name='viewport' content='width=device-width, initial-scale=1.0'>");
            w.println("<title>Prüfprotokoll - " + escapeHtml(sourceType) + "</title>");
            w.println("<style>" + PRINT_CSS + "</style></head><body>");

            w.println("<div class='protocol'>");

            // Header
            w.println("<div class='header'>");
            w.println("<h1>Migrationsprüfprotokoll</h1>");
            w.println("<div class='subtitle'>ItemType: " + escapeHtml(sourceType) + " → " + escapeHtml(destType) + "</div>");
            w.println("</div>");

            // Meta Box
            w.println("<div class='meta-box'>");
            w.println("<div class='meta-row'><span class='meta-label'>Quellsystem (SSID)</span><span class='meta-value'>" + escapeHtml(config.getSourceSSID()) + "</span></div>");
            w.println("<div class='meta-row'><span class='meta-label'>Zielsystem (SSID)</span><span class='meta-value'>" + escapeHtml(config.getDestSSID()) + "</span></div>");
            w.println("<div class='meta-row'><span class='meta-label'>Protokoll erstellt</span><span class='meta-value'>" + escapeHtml(now) + "</span></div>");
            w.println("<div class='meta-row'><span class='meta-label'>Protokoll-ID</span><span class='meta-value'>" + escapeHtml(sourceType + "_" + timestamp) + "</span></div>");
            w.println("</div>");

            // Migration Statistics Section
            w.println("<div class='section'>");
            w.println("<h2>Migrationsstatistik</h2>");
            w.println("<div class='stats-grid'>");
            w.println(createStatBox(String.valueOf(stats.migTotal), "Gesamt", ""));
            w.println(createStatBox(String.valueOf(stats.migSuccess), "Erfolgreich", "ok"));
            w.println(createStatBox(String.valueOf(stats.migFailed), "Fehlgeschlagen", stats.migFailed > 0 ? "err" : ""));
            w.println(createStatBox(String.valueOf(stats.migSkipped), "Übersprungen", ""));
            w.println("</div>");
            w.println("</div>");

            // Verification Statistics Section
            w.println("<div class='section'>");
            w.println("<h2>Verifikationsstatistik</h2>");
            w.println("<div class='verify-grid'>");
            w.println(createStatBox(String.valueOf(stats.verTotal), "Geprüft", ""));
            w.println(createStatBox(String.valueOf(stats.verOk), "Hash OK", "ok"));
            w.println(createStatBox(String.valueOf(stats.verMismatch), "Mismatch", stats.verMismatch > 0 ? "err" : ""));
            w.println(createStatBox(String.valueOf(stats.verOrphaned), "Orphaned", ""));
            w.println(createStatBox(String.valueOf(stats.verCascadeDeleted), "Gelöscht", stats.verCascadeDeleted > 0 ? "ok" : ""));
            w.println("</div>");
            w.println("</div>");

            // Checksummen-Stichproben für Compliance (deaktiviert)
            w.println("<div class='section'>");
            w.println("<h2>Verifizierte Checksummen (Stichprobe)</h2>");
            if (stats.checksumSamples == null || stats.checksumSamples.isEmpty()) {
                w.println("<div class='missing-list empty'>Keine Checksummen-Stichproben verfügbar</div>");
            } else {
                w.println("<table class='sample-table'>");
                w.println("<thead><tr style='background:#f8fafc;'>");
                w.println("<th>Item-ID</th>");
                w.println("<th>SHA-256 Checksum</th>");
                w.println("<th style='text-align:center;'>Status</th>");
                w.println("</tr></thead><tbody>");
                for (ChecksumSample sample : stats.checksumSamples) {
                    String statusClass = "OK".equals(sample.status) ? "stat-val ok" : "text-error";
                    String statusIcon = "OK".equals(sample.status) ? "✓" : "✗";
                    w.println("<tr>");
                    w.println("<td style='font-family:monospace;font-size:8pt'>" + escapeHtml(sample.itemId) + "</td>");
                    w.println("<td style='font-family:monospace;font-size:8pt'>" + escapeHtml(sample.checksum) + "</td>");
                    w.println("<td style='text-align:center;' class='" + statusClass + "'>" + statusIcon + " " + escapeHtml(sample.status) + "</td>");
                    w.println("</tr>");
                }
                w.println("</tbody></table>");
            }
            w.println("</div>");

            // Missing/Error Items Section
            w.println("<div class='section'>");
            w.println("<h2>Fehlende/Problematische Dateien</h2>");
            if (stats.errorItems == null || stats.errorItems.isEmpty()) {
                w.println("<div class='missing-list empty'>Keine fehlenden oder problematischen Dateien</div>");
            } else {
                w.println("<div class='missing-list'>");
                int shown = 0;
                for (String itemId : stats.errorItems) {
                    if (shown++ >= 20) {
                        w.println("<div class='missing-item'>... und " + (stats.errorItems.size() - 20) + " weitere</div>");
                        break;
                    }
                    w.println("<div class='missing-item'>" + escapeHtml(itemId) + "</div>");
                }
                w.println("</div>");
            }
            w.println("</div>");

            // Unterschrift Box
            w.println("<div class='signature-box'>");
            w.println("<h2 style='margin-bottom:15px;border:none;'>Prüfbestätigung</h2>");
            w.println("<div class='signature-row'>");
            w.println("<div class='signature-field'><label>Datum</label><div class='line'></div></div>");
            w.println("<div class='signature-field'><label>Name des Prüfers</label><div class='line'></div></div>");
            w.println("<div class='signature-field'><label>Unterschrift</label><div class='line'></div></div>");
            w.println("</div>");
            w.println("<div class='remarks-field'><label>Bemerkungen</label><div class='remarks-lines'></div></div>");
            w.println("</div>");

            // Footer
            w.println("</div></body></html>");
        }

        logger.info("Generated audit protocol: {}", outFile.getAbsolutePath());
    }

    private static void generateMasterProtocol(MigrationConfig config, String baseDir, String outputDir, String timestamp) throws Exception {
        String fileName = String.format("PRUEFPROTOKOLL_GESAMT_%s.html", timestamp);
        File outFile = new File(outputDir, fileName);

        Map<String, String> mapping = config.getItemTypeMapping();
        java.util.List<Map.Entry<String, AuditStats>> allStats = new java.util.ArrayList<>();
        AuditStats totalStats = new AuditStats();

        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            AuditStats stats = collectStats(baseDir, entry.getKey());
            allStats.add(new java.util.AbstractMap.SimpleEntry<>(entry.getKey(), stats));
            
            totalStats.migTotal += stats.migTotal;
            totalStats.migSuccess += stats.migSuccess;
            totalStats.migFailed += stats.migFailed;
            totalStats.migSkipped += stats.migSkipped;
            totalStats.verTotal += stats.verTotal;
            totalStats.verOk += stats.verOk;
            totalStats.verMismatch += stats.verMismatch;
            totalStats.verOrphaned += stats.verOrphaned;
            totalStats.verCascadeDeleted += stats.verCascadeDeleted;
        }

        try (PrintWriter w = new PrintWriter(new FileWriter(outFile, StandardCharsets.UTF_8))) {
            String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
            String dateGenerated = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd. MMMM yyyy"));

            w.println("<!DOCTYPE html><html lang='de'><head><meta charset='UTF-8'>");
            w.println("<title>Gesamtprüfprotokoll - Alle ItemTypes</title>");
            w.println("<style>" + PRINT_CSS + 
                ".summary-table { width: 100%; border-collapse: collapse; margin-top: 20px; font-size: 9pt; }" +
                ".summary-table th, .summary-table td { border: 1px solid #e2e8f0; padding: 8px; text-align: right; }" +
                ".summary-table th { background: #f8fafc; color: #64748b; font-weight: 600; text-align: left; }" +
                ".summary-table td:first-child { text-align: left; font-weight: 600; font-family: monospace; }" +
                "</style></head><body>");

            w.println("<div class='protocol'>");
            w.println("<div class='header'><h1>Gesamtprüfprotokoll</h1><div class='subtitle'>Zusammenfassung über alle migrierten ItemTypes</div></div>");

            w.println("<div class='meta-box'>");
            w.println("<div class='meta-row'><span class='meta-label'>Quellsystem (SSID)</span><span class='meta-value'>" + escapeHtml(config.getSourceSSID()) + "</span></div>");
            w.println("<div class='meta-row'><span class='meta-label'>Zielsystem (SSID)</span><span class='meta-value'>" + escapeHtml(config.getDestSSID()) + "</span></div>");
            w.println("<div class='meta-row'><span class='meta-label'>Protokoll erstellt</span><span class='meta-value'>" + escapeHtml(now) + "</span></div>");
            w.println("</div>");

            w.println("<div class='section'><h2>Gesamtstatistik</h2><div class='stats-grid'>");
            w.println(createStatBox(String.valueOf(totalStats.migTotal), "MIG Gesamt", ""));
            w.println(createStatBox(String.valueOf(totalStats.migSuccess), "MIG Erfolg", "ok"));
            w.println(createStatBox(String.valueOf(totalStats.verTotal), "VER Geprüft", ""));
            w.println(createStatBox(String.valueOf(totalStats.verOk), "VER OK", "ok"));
            w.println("</div></div>");

            w.println("<div class='section'><h2>Detaillierte Übersicht</h2>");
            w.println("<table class='summary-table'><thead><tr>");
            w.println("<th>ItemType</th><th>Total</th><th>MIG Success</th><th>MIG Fail</th><th>VER OK</th><th>Mismatch</th><th>Del</th>");
            w.println("</tr></thead><tbody>");

            for (Map.Entry<String, AuditStats> entry : allStats) {
                AuditStats s = entry.getValue();
                w.println("<tr>");
                w.println("<td>" + escapeHtml(entry.getKey()) + "</td>");
                w.println("<td>" + s.migTotal + "</td>");
                w.println("<td>" + s.migSuccess + "</td>");
                w.println("<td" + (s.migFailed > 0 ? " class='text-error'" : "") + ">" + s.migFailed + "</td>");
                w.println("<td>" + s.verOk + "</td>");
                w.println("<td" + (s.verMismatch > 0 ? " class='text-error'" : "") + ">" + s.verMismatch + "</td>");
                w.println("<td>" + s.verCascadeDeleted + "</td>");
                w.println("</tr>");
            }
            w.println("</tbody></table></div>");

            w.println("<div class='signature-box'><h2 style='margin-bottom:15px;border:none;'>Gesamtabnahme</h2><div class='signature-row'>");
            w.println("<div class='signature-field'><label>Datum</label><div class='line'></div></div>");
            w.println("<div class='signature-field'><label>Name des Prüfers</label><div class='line'></div></div>");
            w.println("<div class='signature-field'><label>Unterschrift</label><div class='line'></div></div>");
            w.println("</div><div class='remarks-field'><label>Abschlussbemerkungen</label><div class='remarks-lines'></div></div></div>");

            w.println("<div class='footer'>Generiert am " + escapeHtml(dateGenerated) + " | CM Migrator v1.25</div>");
            w.println("</div></body></html>");
        }
        logger.info("Generated master audit protocol: {}", outFile.getAbsolutePath());
    }

    private static String createStatBox(String value, String label, String cssClass) {
        String valClass = cssClass.isEmpty() ? "" : " " + cssClass;
        return "<div class='stat-box'><div class='stat-val" + valClass + "'>" + escapeHtml(value) + "</div><div class='stat-label'>" + escapeHtml(label) + "</div></div>";
    }

    /**
     * Sammelt Statistiken aus Journal- und Verifikationsdatenbanken.
     */
    private static AuditStats collectStats(String baseDir, String itemType) {
        AuditStats stats = new AuditStats();

        // Lese aus journal database
        String journalPath = baseDir + "/journal_" + itemType;
        String journalUrl = "jdbc:h2:" + journalPath + ";IFEXISTS=TRUE";

        try (Connection conn = DriverManager.getConnection(journalUrl, "sa", "")) {
            // Zuerst das neue Schema (AUDITLOG) ausprobieren, dann das alte (AUDIT_LOG)
            String sql = detectAndBuildCountSql(conn);
            if (sql != null) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String status = rs.getString(1);
                            int count = rs.getInt(2);
                            stats.migTotal += count;
                            if ("SUCCESS".equalsIgnoreCase(status)) stats.migSuccess += count;
                            else if ("FAILED".equalsIgnoreCase(status)) stats.migFailed += count;
                            else stats.migSkipped += count;
                        }
                    }
                }
            }

            // Read error item IDs
            String errorSql = detectAndBuildErrorItemsSql(conn);
            if (errorSql != null) {
                try (PreparedStatement ps = conn.prepareStatement(errorSql)) {
                    try (ResultSet rs = ps.executeQuery()) {
                        stats.errorItems = new java.util.ArrayList<>();
                        while (rs.next() && stats.errorItems.size() < 50) {
                            stats.errorItems.add(rs.getString(1));
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not read journal for {}: {}", itemType, e.getMessage());
        }

        //  Aus der Verifizierungsdatenbank lesen (gleicher Pfad per Konvention)
        try (Connection conn = DriverManager.getConnection(journalUrl, "sa", "")) {
            String verifySql = detectAndBuildVerifyCountSql(conn);
            if (verifySql != null) {
                try (PreparedStatement ps = conn.prepareStatement(verifySql)) {
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String status = rs.getString(1);
                            int count = rs.getInt(2);
                            stats.verTotal += count;
                            if ("OK".equalsIgnoreCase(status)) stats.verOk += count;
                            else if ("MISMATCH".equalsIgnoreCase(status)) stats.verMismatch += count;
                            else if ("ORPHANED".equalsIgnoreCase(status)) stats.verOrphaned += count;
                            else if ("CASCADE_DELETED".equalsIgnoreCase(status)) stats.verCascadeDeleted += count;
                        }
                    }
                }
            }
            
            // Prüfsummen-Beispiele für die Compliance-Dokumentation sammeln
            String checksumSql = detectAndBuildChecksumSampleSql(conn);
            if (checksumSql != null) {
                stats.checksumSamples = new java.util.ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(checksumSql)) {
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next() && stats.checksumSamples.size() < 5) {
                            String itemId = rs.getString(1);
                            String checksum = rs.getString(2);
                            String status = rs.getString(3);
                            if (checksum != null && !checksum.isEmpty()) {
                                stats.checksumSamples.add(new ChecksumSample(itemId, checksum, status != null ? status : "OK"));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not read verification log for {}: {}", itemType, e.getMessage());
        }

        return stats;
    }
    
    /**
     * SQL zum Erstellen von Prüfsummen-Stichproben
     */
    private static String detectAndBuildChecksumSampleSql(Connection conn) {
        if (MigrationJournal.isTablePresent(conn, "VERIFICATIONLOG")) {
            return "SELECT ITEMID, DESTCHECKSUM, STATUS FROM VERIFICATIONLOG WHERE STATUS = 'OK' ORDER BY VERIFIEDAT DESC LIMIT 5";
        }
        if (MigrationJournal.isTablePresent(conn, "VERIFICATION_LOG")) {
            return "SELECT ITEM_ID, DEST_CHECKSUM, STATUS FROM VERIFICATION_LOG WHERE STATUS = 'OK' ORDER BY VERIFIED_TIME DESC LIMIT 5";
        }
        // Fallback: Try audit log for checksums
        if (MigrationJournal.isTablePresent(conn, "AUDITLOG")) {
            return "SELECT ITEMID, CHECKSUM, STATUS FROM AUDITLOG WHERE STATUS = 'SUCCESS' AND CHECKSUM IS NOT NULL ORDER BY MIGRATIONTIME DESC LIMIT 5";
        }
        if (MigrationJournal.isTablePresent(conn, "AUDIT_LOG")) {
            return "SELECT ITEM_ID, CHECKSUM, STATUS FROM AUDIT_LOG WHERE STATUS = 'SUCCESS' AND CHECKSUM IS NOT NULL ORDER BY MIGRATION_TIME DESC LIMIT 5";
        }
        return null;
    }

    private static String detectAndBuildCountSql(Connection conn) {
        if (MigrationJournal.isTablePresent(conn, "AUDITLOG")) {
            return "SELECT STATUS, COUNT(*) FROM AUDITLOG GROUP BY STATUS";
        }
        if (MigrationJournal.isTablePresent(conn, "AUDIT_LOG")) {
            return "SELECT STATUS, COUNT(*) FROM AUDIT_LOG GROUP BY STATUS";
        }
        return null;
    }

    private static String detectAndBuildErrorItemsSql(Connection conn) {
        if (MigrationJournal.isTablePresent(conn, "AUDITLOG")) {
            return "SELECT ITEMID FROM AUDITLOG WHERE STATUS = 'FAILED' LIMIT 50";
        }
        if (MigrationJournal.isTablePresent(conn, "AUDIT_LOG")) {
            return "SELECT ITEM_ID FROM AUDIT_LOG WHERE STATUS = 'FAILED' LIMIT 50";
        }
        return null;
    }

    private static String detectAndBuildVerifyCountSql(Connection conn) {
        if (MigrationJournal.isTablePresent(conn, "VERIFICATIONLOG")) {
            return "SELECT STATUS, COUNT(*) FROM VERIFICATIONLOG GROUP BY STATUS";
        }
        if (MigrationJournal.isTablePresent(conn, "VERIFICATION_LOG")) {
            return "SELECT STATUS, COUNT(*) FROM VERIFICATION_LOG GROUP BY STATUS";
        }
        return null;
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * Interner Statistik-Container.
     */
    private static class AuditStats {
        int migTotal = 0, migSuccess = 0, migFailed = 0, migSkipped = 0;
        int verTotal = 0, verOk = 0, verMismatch = 0, verOrphaned = 0, verCascadeDeleted = 0;
        java.util.List<String> errorItems;
        java.util.List<ChecksumSample> checksumSamples;
    }

    /**
     * Prüfsummenbeispiel für die Auditprotokolls.
     */
    private static class ChecksumSample {
        final String itemId;
        final String checksum;
        final String status;
        
        ChecksumSample(String itemId, String checksum, String status) {
            this.itemId = itemId;
            this.checksum = checksum;
            this.status = status;
        }
    }
}
