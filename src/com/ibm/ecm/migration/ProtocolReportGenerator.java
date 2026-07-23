/*
 * Projekt: CM Migrator 2.2.1.
 * @Author: Aleksej Voronin, Sven Lindt
 * @Date:   26.01.2026
 * 
 * Generiert HTML-Protokolle aus Templates.
 * Ersetzt Platzhalter mit Migrationsdaten aus dem H2-Journal.
 * 
 * Diese Klasse ist der zentrale Einstiegspunkt für die automatische
 * Report-Generierung nach Migration und Verifikation.
 */
package com.ibm.ecm.migration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public class ProtocolReportGenerator {
    
    private static final Logger logger = LogManager.getLogger(ProtocolReportGenerator.class);
    
    private static final String TEMPLATE_DIR = "reports/templates/";
    private static final String OUTPUT_DIR = "reports/";
    private static final String MIGRATION_TEMPLATE = "migration_protocol_template.html";
    private static final String VERIFICATION_TEMPLATE = "verification_protocol_template.html";
    private static final String SUMMARY_TEMPLATE = "summary_protocol_template.html";
    
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    
    private final MigrationConfig config;
    private final String dbBaseDir;  // Base directory für Journal-Datenbanken
    
    /**
     * Konstruktor mit MigrationConfig und implizitem Journal-Pfad.
     * Verwendet den DB_PATH aus der Config.
     */
    public ProtocolReportGenerator(MigrationConfig config) {
        this.config = config;
        this.dbBaseDir = config.getDbPath();
    }
    
    /**
     * Konstruktor mit explizitem Journal-Pfad (für Verifier.java).
     */
    public ProtocolReportGenerator(MigrationConfig config, String dbBaseDir) {
        this.config = config;
        this.dbBaseDir = dbBaseDir;
    }

    /**
     * @deprecated Replaced by the unified {@link ReportDeliveryService} pipeline.
     *             Kept for backward compatibility with external callers.
     */
    @Deprecated
    public static void generateUnifiedReport(MigrationConfig config, MigrationStats stats) {
        logger.warn("ProtocolReportGenerator.generateUnifiedReport() is deprecated — use ReportDeliveryService.deliver() directly.");
        ReportDataCollector collector = new ReportDataCollector(stats, config);
        UnifiedReport report = collector.collect();
        ReportDeliveryService.deliver(report, config);
    }
    
    /**
     * Holt eine DB-Verbindung für einen spezifischen ItemType.
     * Journal-DBs werden pro ItemType unter dbBaseDir/journal_{itemType} gespeichert.
     */
    private Connection getConnection(String itemType) throws SQLException {
        // Journal-Pfad: baseDir + "/journal_" + itemType (wie in MigrationJournal)
        String jdbcUrl = "jdbc:h2:file:" + dbBaseDir + "/journal_" + itemType + ";IFEXISTS=TRUE";
        logger.debug("Connecting to journal DB: {}", jdbcUrl);
        return DriverManager.getConnection(jdbcUrl, "sa", "");
    }
    
    // ========== Public API ==========
    
    /**
     * Generiert alle Protokolle nach Abschluss der Migration.
     * Erstellt separate Dateien pro ItemType.
     */
    public void generateAllMigrationReports() {
        logger.info("Starte Generierung der Migrations-Protokolle...");
        
        var itemTypeMapping = config.getItemTypeMapping();
        
        for (Map.Entry<String, String> entry : itemTypeMapping.entrySet()) {
            String sourceType = entry.getKey();
            String destType = entry.getValue();
            
            try {
                ProtocolData data = collectMigrationData(sourceType, destType);
                generateMigrationProtocol(sourceType, data);
                logger.info("Migrations-Protokoll erstellt für ItemType: {}", sourceType);
            } catch (Exception e) {
                logger.error("Fehler bei Protokoll-Generierung für {}: {}", sourceType, e.getMessage(), e);
            }
        }
    }
    
    /**
     * Generiert alle Protokolle nach Abschluss der Verifikation.
     */
    public void generateAllVerificationReports() {
        logger.info("Starte Generierung der Verifikations-Protokolle...");
        
        var itemTypeMapping = config.getItemTypeMapping();
        
        for (Map.Entry<String, String> entry : itemTypeMapping.entrySet()) {
            String sourceType = entry.getKey();
            String destType = entry.getValue();
            
            try {
                ProtocolData data = collectVerificationData(sourceType, destType);
                generateVerificationProtocol(sourceType, data);
                logger.info("Verifikations-Protokoll erstellt für ItemType: {}", sourceType);
            } catch (Exception e) {
                logger.error("Fehler bei Verifikations-Protokoll für {}: {}", sourceType, e.getMessage(), e);
            }
        }
    }

    /**
     * Generiert alle Gesamtprotokolle (Migration + Verifikation) pro ItemType.
     * Wird nach Abschluss der Verifikation aufgerufen.
     */
    public void generateAllCombinedReports() {
        logger.info("Starte Generierung der Gesamtprotokolle...");
        
        var itemTypeMapping = config.getItemTypeMapping();
        
        for (Map.Entry<String, String> entry : itemTypeMapping.entrySet()) {
            String sourceType = entry.getKey();
            String destType = entry.getValue();
            
            try {
                ProtocolData data = collectCombinedData(sourceType, destType);
                generateCombinedProtocol(sourceType, data);
                logger.info("Gesamtprotokoll erstellt für ItemType: {}", sourceType);
            } catch (Exception e) {
                logger.error("Fehler bei Gesamtprotokoll für {}: {}", sourceType, e.getMessage(), e);
            }
        }
    }
    
    /**
     * Generiert ein einzelnes Migrations-Protokoll für einen ItemType.
     */
    public void generateMigrationProtocol(String itemType, ProtocolData data) throws IOException {
        String template = loadTemplate(MIGRATION_TEMPLATE);
        String filled = fillMigrationTemplate(template, data);
        
        String filename = String.format("migration_%s_%s.html", 
            sanitizeFilename(itemType), 
            LocalDate.now().format(DATE_FORMATTER));
        
        writeOutput(filename, filled);
    }
    
    /**
     * Generiert ein einzelnes Verifikations-Protokoll für einen ItemType.
     */
    public void generateVerificationProtocol(String itemType, ProtocolData data) throws IOException {
        String template = loadTemplate(VERIFICATION_TEMPLATE);
        String filled = fillVerificationTemplate(template, data);
        
        String filename = String.format("verification_%s_%s.html", 
            sanitizeFilename(itemType), 
            LocalDate.now().format(DATE_FORMATTER));
        
        writeOutput(filename, filled);
    }

    /**
     * Generiert ein einzelnes Gesamtprotokoll (Migration + Verifikation) für einen ItemType.
     */
    public void generateCombinedProtocol(String itemType, ProtocolData data) throws IOException {
        String template = loadTemplate(SUMMARY_TEMPLATE);
        String filled = fillSummaryTemplate(template, List.of(data), "combined");
        
        String filename = String.format("summary_combined_%s_%s.html", 
            sanitizeFilename(itemType),
            LocalDate.now().format(DATE_FORMATTER));
        
        writeOutput(filename, filled);
    }
    
    // ========== Datensammlung aus H2 Journal ==========
    
    /**
     * Sammelt Migrationsdaten aus dem H2-Journal für einen ItemType.
     */
    public ProtocolData collectMigrationData(String sourceType, String destType) {
        var builder = ProtocolData.builder()
            .companyName(config.getProperty("PROTOCOL_COMPANY_NAME", "Unbekannt"))
            .companyLogo(getFormattedCompanyLogo())
            .itemTypeSource(sourceType)
            .itemTypeDest(destType)
            .sourceSsid(config.getSourceSsid())
            .destSsid(config.getDestSsid());
        
        // Statistiken aus AUDIT_LOG sammeln
        String tableName = "AUDIT_LOG"; // Oder journal-spezifische Tabelle
        
        try (Connection conn = getConnection(sourceType)) {
            // Gesamtzahlen abrufen
            collectMigrationStats(conn, sourceType, builder);
            
            // Fehler-Details abrufen (max 10)
            collectErrorItems(conn, sourceType, builder);
            
        } catch (SQLException e) {
            logger.warn("Fehler beim Sammeln der Migrationsdaten: {}", e.getMessage());
        }
        
        return builder.calculateMigrationRate().build();
    }
    
    /**
     * Sammelt Verifikationsdaten aus dem Journal.
     */
    public ProtocolData collectVerificationData(String sourceType, String destType) {
        var builder = ProtocolData.builder()
            .companyName(config.getProperty("PROTOCOL_COMPANY_NAME", "Unbekannt"))
            .companyLogo(getFormattedCompanyLogo())
            .itemTypeSource(sourceType)
            .itemTypeDest(destType)
            .sourceSsid(config.getSourceSsid())
            .destSsid(config.getDestSsid());
        
        try (Connection conn = getConnection(sourceType)) {
            // Verifikations-Statistiken abrufen
            collectVerificationStats(conn, sourceType, builder);
            
            // Mismatch-Details abrufen
            collectMismatchItems(conn, sourceType, builder);
            
        } catch (SQLException e) {
            logger.warn("Fehler beim Sammeln der Verifikationsdaten: {}", e.getMessage());
        }
        
        return builder.calculateVerificationRate().build();
    }
    
    /**
     * Sammelt kombinierte Daten (Migration + Verifikation) aus dem Journal.
     * Diese Methode wird verwendet, um ein Gesamtprotokoll mit beiden Statistiken zu erstellen.
     */
    public ProtocolData collectCombinedData(String sourceType, String destType) {
        var builder = ProtocolData.builder()
            .companyName(config.getProperty("PROTOCOL_COMPANY_NAME", "Unbekannt"))
            .companyLogo(getFormattedCompanyLogo())
            .itemTypeSource(sourceType)
            .itemTypeDest(destType)
            .sourceSsid(config.getSourceSsid())
            .destSsid(config.getDestSsid());
        
        try (Connection conn = getConnection(sourceType)) {
            // Migration-Statistiken aus AUDIT_LOG
            collectMigrationStats(conn, sourceType, builder);
            collectErrorItems(conn, sourceType, builder);
            
            // Verifikations-Statistiken aus VERIFICATION_LOG
            collectVerificationStats(conn, sourceType, builder);
            collectMismatchItems(conn, sourceType, builder);
            
        } catch (SQLException e) {
            logger.warn("Fehler beim Sammeln der kombinierten Daten: {}", e.getMessage());
        }
        
        return builder
            .calculateMigrationRate()
            .calculateVerificationRate()
            .build();
    }
    
    private void collectMigrationStats(Connection conn, String itemType, ProtocolData.Builder builder) throws SQLException {
        String sql = "SELECT STATUS, COUNT(*) as CNT FROM AUDIT_LOG WHERE ITEM_TYPE = ? GROUP BY STATUS";
        
        long total = 0, success = 0, failed = 0, skipped = 0;
        
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, itemType);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String status = rs.getString("STATUS");
                    long count = rs.getLong("CNT");
                    total += count;
                    
                    if ("SUCCESS".equalsIgnoreCase(status) || "OK".equalsIgnoreCase(status)) {
                        success += count;
                    } else if ("FAILED".equalsIgnoreCase(status) || "ERROR".equalsIgnoreCase(status)) {
                        failed += count;
                    } else if ("SKIPPED".equalsIgnoreCase(status)) {
                        skipped += count;
                    }
                }
            }
        }
        
        builder.migTotal(total).migSuccess(success).migFailed(failed).migSkipped(skipped);
    }
    
    private void collectErrorItems(Connection conn, String itemType, ProtocolData.Builder builder) throws SQLException {
        String sql = "SELECT ITEM_ID, MESSAGE, MIGRATION_TIME FROM AUDIT_LOG " +
                     "WHERE ITEM_TYPE = ? AND STATUS IN ('FAILED', 'ERROR') " +
                     "ORDER BY MIGRATION_TIME DESC LIMIT 10";
        
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, itemType);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String itemId = rs.getString("ITEM_ID");
                    String message = rs.getString("MESSAGE");
                    String timestamp = rs.getString("MIGRATION_TIME");
                    
                    builder.addErrorItem(new ProtocolData.ErrorItem(itemId, message, timestamp));
                }
            }
        }
    }
    
    private void collectVerificationStats(Connection conn, String itemType, ProtocolData.Builder builder) throws SQLException {
        // VERIFICATION_LOG hat keine ITEMTYPE-Spalte, da die DB bereits pro ItemType separiert ist
        String sql = "SELECT STATUS, COUNT(*) as CNT FROM VERIFICATION_LOG GROUP BY STATUS";
        
        long total = 0, ok = 0, mismatch = 0, orphaned = 0, deleted = 0;
        
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String status = rs.getString("STATUS");
                    long count = rs.getLong("CNT");
                    total += count;
                    
                    if ("OK".equalsIgnoreCase(status) || "MATCH".equalsIgnoreCase(status)) {
                        ok += count;
                    } else if ("MISMATCH".equalsIgnoreCase(status)) {
                        mismatch += count;
                    } else if ("ORPHANED".equalsIgnoreCase(status)) {
                        orphaned += count;
                    } else if ("DELETED".equalsIgnoreCase(status)) {
                        deleted += count;
                    }
                }
            }
        } catch (SQLException e) {
            // Tabelle existiert möglicherweise nicht - ignorieren
            logger.debug("VERIFICATION_LOG nicht verfügbar: {}", e.getMessage());
        }
        
        builder.verTotal(total).verOk(ok).verMismatch(mismatch).verOrphaned(orphaned).verDeleted(deleted);
    }
    
    private void collectMismatchItems(Connection conn, String itemType, ProtocolData.Builder builder) throws SQLException {
        // VERIFICATION_LOG: ITEM_ID, SOURCE_HASH, DEST_HASH, MESSAGE (keine ITEMTYPE-Spalte)
        String sql = "SELECT ITEM_ID, SOURCE_HASH, DEST_HASH, MESSAGE FROM VERIFICATION_LOG " +
                     "WHERE STATUS = 'MISMATCH' " +
                     "ORDER BY VERIFIED_AT DESC LIMIT 10";
        
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String itemId = rs.getString("ITEM_ID");
                    String sourceHash = rs.getString("SOURCE_HASH");
                    String destHash = rs.getString("DEST_HASH");
                    String message = rs.getString("MESSAGE");
                    
                    builder.addMismatchItem(new ProtocolData.MismatchItem(itemId, sourceHash, destHash, message));
                }
            }
        } catch (SQLException e) {
            logger.debug("Mismatch-Items nicht verfügbar: {}", e.getMessage());
        }
    }
    
    // ========== Template-Rendering ==========
    
    private String fillMigrationTemplate(String template, ProtocolData data) {
        return template
            .replace("{{COMPANY_LOGO}}", data.getCompanyLogo())
            .replace("{{COMPANY_NAME}}", data.getCompanyName())
            .replace("{{PROTOCOL_ID}}", data.getProtocolId())
            .replace("{{ITEMTYPE_SOURCE}}", data.getItemTypeSource())
            .replace("{{ITEMTYPE_DEST}}", data.getItemTypeDest())
            .replace("{{SOURCE_SSID}}", data.getSourceSsid())
            .replace("{{DEST_SSID}}", data.getDestSsid())
            .replace("{{GENERATED_DATE}}", data.getGeneratedDate())
            .replace("{{GENERATED_TIME}}", data.getGeneratedTime())
            .replace("{{DURATION}}", data.getFormattedDuration())
            .replace("{{MIG_TOTAL}}", String.valueOf(data.getMigTotal()))
            .replace("{{MIG_SUCCESS}}", String.valueOf(data.getMigSuccess()))
            .replace("{{MIG_FAILED}}", String.valueOf(data.getMigFailed()))
            .replace("{{MIG_SKIPPED}}", String.valueOf(data.getMigSkipped()))
            .replace("{{MIG_SUCCESS_RATE}}", String.format("%.2f", data.getMigSuccessRate()))
            .replace("{{MIG_SUCCESS_RATE_CLASS}}", data.getMigSuccessRateClass())
            .replace("{{ERROR_ITEMS}}", renderErrorItems(data.getErrorItems()))
            .replace("{{ERROR_COUNT}}", String.valueOf(data.getErrorItems().size()));
    }
    
    private String fillVerificationTemplate(String template, ProtocolData data) {
        return template
            .replace("{{COMPANY_LOGO}}", data.getCompanyLogo())
            .replace("{{COMPANY_NAME}}", data.getCompanyName())
            .replace("{{PROTOCOL_ID}}", data.getProtocolId())
            .replace("{{ITEMTYPE_SOURCE}}", data.getItemTypeSource())
            .replace("{{ITEMTYPE_DEST}}", data.getItemTypeDest())
            .replace("{{SOURCE_SSID}}", data.getSourceSsid())
            .replace("{{DEST_SSID}}", data.getDestSsid())
            .replace("{{GENERATED_DATE}}", data.getGeneratedDate())
            .replace("{{GENERATED_TIME}}", data.getGeneratedTime())
            .replace("{{DURATION}}", data.getFormattedDuration())
            .replace("{{VER_TOTAL}}", String.valueOf(data.getVerTotal()))
            .replace("{{VER_OK}}", String.valueOf(data.getVerOk()))
            .replace("{{VER_MISMATCH}}", String.valueOf(data.getVerMismatch()))
            .replace("{{VER_ORPHANED}}", String.valueOf(data.getVerOrphaned()))
            .replace("{{VER_DELETED}}", String.valueOf(data.getVerDeleted()))
            .replace("{{VER_SUCCESS_RATE}}", String.format("%.2f", data.getVerSuccessRate()))
            .replace("{{VER_SUCCESS_RATE_CLASS}}", data.getVerSuccessRateClass())
            .replace("{{MISMATCH_ITEMS}}", renderMismatchItems(data.getMismatchItems()))
            .replace("{{MISMATCH_COUNT}}", String.valueOf(data.getMismatchItems().size()));
    }
    
    private String fillSummaryTemplate(String template, List<ProtocolData> allData, String reportType) {
        // Aggregierte Statistiken berechnen
        long totalMig = 0, successMig = 0, failedMig = 0, skippedMig = 0;
        long totalVer = 0, okVer = 0, mismatchVer = 0, orphanedVer = 0;
        
        for (ProtocolData data : allData) {
            totalMig += data.getMigTotal();
            successMig += data.getMigSuccess();
            failedMig += data.getMigFailed();
            skippedMig += data.getMigSkipped();
            totalVer += data.getVerTotal();
            okVer += data.getVerOk();
            mismatchVer += data.getVerMismatch();
            orphanedVer += data.getVerOrphaned();
        }
        
        double migRate = ProtocolData.calculateSuccessRate(successMig, totalMig);
        double verRate = ProtocolData.calculateSuccessRate(okVer, totalVer);
        double overallRate;
        if ("combined".equals(reportType)) {
            overallRate = Math.min(migRate, verRate);
        } else if ("verification".equals(reportType)) {
            overallRate = verRate;
        } else {
            overallRate = migRate;
        }
        
        String migRateClass = ProtocolData.calculateSuccessRateClass(migRate);
        String verRateClass = ProtocolData.calculateSuccessRateClass(verRate);
        String overallClass = ProtocolData.calculateSuccessRateClass(overallRate);
        String overallIcon = overallRate >= 98 ? "✓" : (overallRate >= 90 ? "⚠" : "✗");
        String overallText = overallRate >= 98 ? "Erfolgreich" : (overallRate >= 90 ? "Mit Warnungen" : "Fehlgeschlagen");
        
        // Error Summary als HTML-Tabelle
        StringBuilder errorSummary = new StringBuilder();
        errorSummary.append("<table class=\"error-table\">");
        errorSummary.append("<thead><tr><th>ItemType</th><th>Fehleranzahl</th><th>Hauptfehler</th></tr></thead>");
        errorSummary.append("<tbody>");
        
        for (ProtocolData data : allData) {
            long errors;
            if ("verification".equals(reportType)) {
                errors = data.getVerMismatch();
            } else if ("combined".equals(reportType)) {
                errors = data.getMigFailed() + data.getVerMismatch() + data.getVerOrphaned() + data.getVerDeleted();
            } else {
                errors = data.getMigFailed();
            }
            if (errors > 0) {
                errorSummary.append(String.format(
                    "<tr><td>%s</td><td>%d</td><td>Siehe Detailprotokoll</td></tr>",
                    data.getItemTypeSource(),
                    errors
                ));
            }
        }
        
        if (errorSummary.indexOf("<tr>") == -1) {
            errorSummary.append("<tr><td colspan=\"3\" style=\"text-align:center;\">Keine Fehler aufgetreten</td></tr>");
        }
        
        errorSummary.append("</tbody></table>");
        
        // Verwende Werte vom ersten ItemType oder Default-Werte
        String itemTypeSource = allData.isEmpty() ? "ALLE" : (allData.size() == 1 ? allData.get(0).getItemTypeSource() : "ALLE");
        String itemTypeDest = allData.isEmpty() ? "ALLE" : (allData.size() == 1 ? allData.get(0).getItemTypeDest() : "ALLE");
        
        return template
            .replace("{{COMPANY_LOGO}}", allData.isEmpty() ? "" : allData.get(0).getCompanyLogo())
            .replace("{{COMPANY_NAME}}", allData.isEmpty() ? "Unbekannt" : allData.get(0).getCompanyName())
            .replace("{{PROTOCOL_ID}}", allData.isEmpty() ? "SUM-00000000" : "SUM-" + allData.get(0).getProtocolId())
            .replace("{{ITEMTYPE_SOURCE}}", itemTypeSource)
            .replace("{{ITEMTYPE_DEST}}", itemTypeDest)
            .replace("{{GENERATED_DATE}}", LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")))
            .replace("{{GENERATED_TIME}}", java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")))
            .replace("{{SOURCE_SSID}}", config.getSourceSsid())
            .replace("{{DEST_SSID}}", config.getDestSsid())
            .replace("{{MIG_TOTAL}}", String.valueOf(totalMig))
            .replace("{{MIG_SUCCESS}}", String.valueOf(successMig))
            .replace("{{MIG_FAILED}}", String.valueOf(failedMig))
            .replace("{{MIG_SKIPPED}}", String.valueOf(skippedMig))
            .replace("{{MIG_SUCCESS_RATE}}", String.format("%.2f", migRate))
            .replace("{{MIG_SUCCESS_RATE_CLASS}}", migRateClass)
            .replace("{{VER_TOTAL}}", String.valueOf(totalVer))
            .replace("{{VER_OK}}", String.valueOf(okVer))
            .replace("{{VER_MISMATCH}}", String.valueOf(mismatchVer))
            .replace("{{VER_ORPHANED}}", String.valueOf(orphanedVer))
            .replace("{{VER_SUCCESS_RATE}}", String.format("%.2f", verRate))
            .replace("{{VER_SUCCESS_RATE_CLASS}}", verRateClass)
            .replace("{{OVERALL_SUCCESS_RATE}}", String.format("%.2f", overallRate))
            .replace("{{OVERALL_STATUS_CLASS}}", overallClass)
            .replace("{{OVERALL_STATUS_ICON}}", overallIcon)
            .replace("{{OVERALL_STATUS_TEXT}}", overallText)
            .replace("{{ERROR_SUMMARY}}", errorSummary.toString());
    }
    
    // ========== HTML-Rendering für Listen ==========
    
    private String renderErrorItems(List<ProtocolData.ErrorItem> items) {
        if (items.isEmpty()) {
            return "<tr><td colspan=\"3\" style=\"text-align:center;\">Keine Fehler</td></tr>";
        }
        
        StringBuilder sb = new StringBuilder();
        for (ProtocolData.ErrorItem item : items) {
            sb.append(item.toHtmlRow());
        }
        return sb.toString();
    }
    
    private String renderMismatchItems(List<ProtocolData.MismatchItem> items) {
        if (items.isEmpty()) {
            return "<tr><td colspan=\"4\" style=\"text-align:center;\">Keine Abweichungen</td></tr>";
        }
        
        StringBuilder sb = new StringBuilder();
        for (ProtocolData.MismatchItem item : items) {
            sb.append(item.toHtmlRow());
        }
        return sb.toString();
    }
    
    // ========== Datei-Operationen ==========
    
    private String loadTemplate(String templateName) throws IOException {
        Path templatePath = Paths.get(TEMPLATE_DIR, templateName);
        
        if (!Files.exists(templatePath)) {
            throw new IOException("Template nicht gefunden: " + templatePath);
        }
        
        return Files.readString(templatePath, StandardCharsets.UTF_8);
    }
    
    private void writeOutput(String filename, String content) throws IOException {
        Path outputDir = Paths.get(OUTPUT_DIR);
        
        // Ausgabeverzeichnis erstellen, falls nicht vorhanden
        if (!Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
            logger.info("Ausgabeverzeichnis erstellt: {}", outputDir);
        }
        
        Path outputPath = outputDir.resolve(filename);
        Files.writeString(outputPath, content, StandardCharsets.UTF_8);
        
        logger.info("Protokoll geschrieben: {}", outputPath);
    }
    
    private String sanitizeFilename(String name) {
        if (name == null) return "unknown";
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
    private String getFormattedCompanyLogo() {
        String logoPath = config.getProperty("PROTOCOL_COMPANY_LOGO", "");
        if (logoPath == null || logoPath.isBlank()) {
            return "";
        }

        // Try to read the file and encode as Base64 for ultimate portability
        try {
            Path path = Paths.get(logoPath);
            if (Files.exists(path) && !Files.isDirectory(path)) {
                byte[] bytes = Files.readAllBytes(path);
                String base64 = Base64.getEncoder().encodeToString(bytes);
                String mimeType = guessMimeType(logoPath);
                
                return String.format("<img src=\"data:%s;base64,%s\" alt=\"Company Logo\" style=\"max-width: 100%%; max-height: 100%%; object-fit: contain;\">", 
                    mimeType, base64);
            }
        } catch (Exception e) {
            logger.debug("Could not embed logo as Base64, falling back to path: {}", e.getMessage());
        }

        // Fallback for URLs or files not found at generation time
        String srcPath = logoPath;
        if (srcPath.startsWith("reports/")) {
            srcPath = srcPath.substring("reports/".length());
        } else if (srcPath.startsWith("./reports/")) {
            srcPath = srcPath.substring("./reports/".length());
        }
        srcPath = srcPath.replace("\\", "/");
        
        return String.format("<img src=\"%s\" alt=\"Company Logo\" style=\"max-width: 100%%; max-height: 100%%; object-fit: contain;\">", srcPath);
    }

    private String guessMimeType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/png"; // Default
    }

}
