/*
 * Projekt: CM Migrator 2.2.1.
 * @Author: Aleksej Voronin, Sven Lindt
 * @Date:   26.01.2026
 * 
 * Datencontainer für Protokoll-Platzhalter.
 * Verwendet Builder-Pattern für Java 11 Kompatibilität.
 * 
 * Diese Klasse enthält alle Daten, die für das Befüllen der HTML-Templates
 * für Migration-, Verification- und Summary-Protokolle benötigt werden.
 */

package com.ibm.ecm.migration;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ProtocolData {

    // ========== Allgemeine Felder ==========
    private final String companyLogo;
    private final String companyName;
    private final String protocolId;
    private final String itemTypeSource;
    private final String itemTypeDest;
    private final String sourceSsid;
    private final String destSsid;
    private final String generatedDate;
    private final String generatedTime;
    private final long durationSeconds;

    // ========== Migration-Statistiken ==========
    private final long migTotal;
    private final long migSuccess;
    private final long migFailed;
    private final long migSkipped;
    private final double migSuccessRate;
    private final String migSuccessRateClass;
    private final List<ErrorItem> errorItems;

    // ========== Verifikation-Statistiken ==========
    private final long verTotal;
    private final long verOk;
    private final long verMismatch;
    private final long verOrphaned;
    private final long verDeleted;
    private final double verSuccessRate;
    private final String verSuccessRateClass;
    private final List<MismatchItem> mismatchItems;
    private final List<ChecksumSample> checksumSamples;

    // ========== Gesamtstatus (für Summary) ==========
    private final String overallStatusClass;
    private final String overallStatusIcon;
    private final String overallStatusText;

    private ProtocolData(Builder builder) {
        this.companyLogo = builder.companyLogo;
        this.companyName = builder.companyName;
        this.protocolId = builder.protocolId;
        this.itemTypeSource = builder.itemTypeSource;
        this.itemTypeDest = builder.itemTypeDest;
        this.sourceSsid = builder.sourceSsid;
        this.destSsid = builder.destSsid;
        this.generatedDate = builder.generatedDate;
        this.generatedTime = builder.generatedTime;
        this.durationSeconds = builder.durationSeconds;
        this.migTotal = builder.migTotal;
        this.migSuccess = builder.migSuccess;
        this.migFailed = builder.migFailed;
        this.migSkipped = builder.migSkipped;
        this.migSuccessRate = builder.migSuccessRate;
        this.migSuccessRateClass = builder.migSuccessRateClass;
        this.errorItems = builder.errorItems;
        this.verTotal = builder.verTotal;
        this.verOk = builder.verOk;
        this.verMismatch = builder.verMismatch;
        this.verOrphaned = builder.verOrphaned;
        this.verDeleted = builder.verDeleted;
        this.verSuccessRate = builder.verSuccessRate;
        this.verSuccessRateClass = builder.verSuccessRateClass;
        this.mismatchItems = builder.mismatchItems;
        this.checksumSamples = builder.checksumSamples;
        this.overallStatusClass = builder.overallStatusClass;
        this.overallStatusIcon = builder.overallStatusIcon;
        this.overallStatusText = builder.overallStatusText;
    }

    // ========== Getter ==========
    public String getCompanyLogo() { return companyLogo; }
    public String getCompanyName() { return companyName; }
    public String getProtocolId() { return protocolId; }
    public String getItemTypeSource() { return itemTypeSource; }
    public String getItemTypeDest() { return itemTypeDest; }
    public String getSourceSsid() { return sourceSsid; }
    public String getDestSsid() { return destSsid; }
    public String getGeneratedDate() { return generatedDate; }
    public String getGeneratedTime() { return generatedTime; }
    public long getDurationSeconds() { return durationSeconds; }
    public long getMigTotal() { return migTotal; }
    public long getMigSuccess() { return migSuccess; }
    public long getMigFailed() { return migFailed; }
    public long getMigSkipped() { return migSkipped; }
    public double getMigSuccessRate() { return migSuccessRate; }
    public String getMigSuccessRateClass() { return migSuccessRateClass; }
    public List<ErrorItem> getErrorItems() { return errorItems; }
    public long getVerTotal() { return verTotal; }
    public long getVerOk() { return verOk; }
    public long getVerMismatch() { return verMismatch; }
    public long getVerOrphaned() { return verOrphaned; }
    public long getVerDeleted() { return verDeleted; }
    public double getVerSuccessRate() { return verSuccessRate; }
    public String getVerSuccessRateClass() { return verSuccessRateClass; }
    public List<MismatchItem> getMismatchItems() { return mismatchItems; }
    public List<ChecksumSample> getChecksumSamples() { return checksumSamples; }
    public String getOverallStatusClass() { return overallStatusClass; }
    public String getOverallStatusIcon() { return overallStatusIcon; }
    public String getOverallStatusText() { return overallStatusText; }

    /**
     * Formatiert die Dauer in ein lesbares Format (z.B. "2h 15m 30s").
     */
    public String getFormattedDuration() {
        long hours = durationSeconds / 3600;
        long minutes = (durationSeconds % 3600) / 60;
        long seconds = durationSeconds % 60;
        
        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }

    // ========== Inner Classes für Listen-Elemente ==========

    /**
     * Repräsentiert einen Fehler-Eintrag für die Fehler-Tabelle.
     */
    public static class ErrorItem {
        private final String itemId;
        private final String errorMessage;
        private final String timestamp;

        public ErrorItem(String itemId, String errorMessage, String timestamp) {
            this.itemId = itemId;
            this.errorMessage = errorMessage;
            this.timestamp = timestamp;
        }

        public String getItemId() { return itemId; }
        public String getErrorMessage() { return errorMessage; }
        public String getTimestamp() { return timestamp; }
        
        public String toHtmlRow() {
            return String.format("<tr><td>%s</td><td>%s</td><td>%s</td></tr>",
                escapeHtml(itemId), escapeHtml(errorMessage), escapeHtml(timestamp));
        }
    }

    /**
     * Repräsentiert einen Mismatch-Eintrag für die Verifikation.
     */
    public static class MismatchItem {
        private final String itemId;
        private final String sourceHash;
        private final String destHash;
        private final String message;

        public MismatchItem(String itemId, String sourceHash, String destHash, String message) {
            this.itemId = itemId;
            this.sourceHash = sourceHash;
            this.destHash = destHash;
            this.message = message;
        }

        public String getItemId() { return itemId; }
        public String getSourceHash() { return sourceHash; }
        public String getDestHash() { return destHash; }
        public String getMessage() { return message; }
        
        public String toHtmlRow() {
            return String.format("<tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td></tr>",
                escapeHtml(itemId), escapeHtml(truncateHash(sourceHash)), 
                escapeHtml(truncateHash(destHash)), escapeHtml(message));
        }
        
        private String truncateHash(String hash) {
            if (hash == null) return "N/A";
            return hash.length() > 16 ? hash.substring(0, 16) + "..." : hash;
        }
    }

    /**
     * Repräsentiert eine Checksummen-Stichprobe für die Verifikation.
     */
    public static class ChecksumSample {
        private final String itemId;
        private final String sourceHash;
        private final String destHash;
        private final String status;

        public ChecksumSample(String itemId, String sourceHash, String destHash, String status) {
            this.itemId = itemId;
            this.sourceHash = sourceHash;
            this.destHash = destHash;
            this.status = status;
        }

        public String getItemId() { return itemId; }
        public String getSourceHash() { return sourceHash; }
        public String getDestHash() { return destHash; }
        public String getStatus() { return status; }
        
        public String toHtmlRow() {
            String statusClass = "OK".equalsIgnoreCase(status) ? "success" : "error";
            return String.format("<tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td class=\"%s\">%s</td></tr>",
                escapeHtml(itemId), escapeHtml(sourceHash), escapeHtml(destHash), statusClass, escapeHtml(status));
        }
    }

    // ========== Hilfsmethoden ==========

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    /**
     * Berechnet die CSS-Klasse basierend auf der Erfolgsrate.
     */
    public static String calculateSuccessRateClass(double rate) {
        if (rate >= 98.0) return "success";
        if (rate >= 90.0) return "warning";
        return "error";
    }

    /**
     * Berechnet die Erfolgsrate aus Erfolgen und Gesamtzahl.
     */
    public static double calculateSuccessRate(long success, long total) {
        if (total == 0) return 100.0;
        return (success * 100.0) / total;
    }

    // ========== Hier kommt der Builder ==========

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String companyLogo = "";
        private String companyName = "Unbekannt";
        private String protocolId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        private String itemTypeSource = "";
        private String itemTypeDest = "";
        private String sourceSsid = "";
        private String destSsid = "";
        private String generatedDate = LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        private String generatedTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        private long durationSeconds = 0;
        private long migTotal = 0;
        private long migSuccess = 0;
        private long migFailed = 0;
        private long migSkipped = 0;
        private double migSuccessRate = 100.0;
        private String migSuccessRateClass = "success";
        private List<ErrorItem> errorItems = new ArrayList<>();
        private long verTotal = 0;
        private long verOk = 0;
        private long verMismatch = 0;
        private long verOrphaned = 0;
        private long verDeleted = 0;
        private double verSuccessRate = 100.0;
        private String verSuccessRateClass = "success";
        private List<MismatchItem> mismatchItems = new ArrayList<>();
        private List<ChecksumSample> checksumSamples = new ArrayList<>();
        private String overallStatusClass = "success";
        private String overallStatusIcon = "✓";
        private String overallStatusText = "Erfolgreich";

        public Builder companyLogo(String companyLogo) {
            this.companyLogo = companyLogo != null ? companyLogo : "";
            return this;
        }

        public Builder companyName(String companyName) {
            this.companyName = companyName != null ? companyName : "Unbekannt";
            return this;
        }

        public Builder protocolId(String protocolId) {
            this.protocolId = protocolId != null ? protocolId : UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            return this;
        }

        public Builder itemTypeSource(String itemTypeSource) {
            this.itemTypeSource = itemTypeSource != null ? itemTypeSource : "";
            return this;
        }

        public Builder itemTypeDest(String itemTypeDest) {
            this.itemTypeDest = itemTypeDest != null ? itemTypeDest : "";
            return this;
        }

        public Builder sourceSsid(String sourceSsid) {
            this.sourceSsid = sourceSsid != null ? sourceSsid : "";
            return this;
        }

        public Builder destSsid(String destSsid) {
            this.destSsid = destSsid != null ? destSsid : "";
            return this;
        }

        public Builder generatedDate(String generatedDate) {
            this.generatedDate = generatedDate != null ? generatedDate : LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
            return this;
        }

        public Builder generatedTime(String generatedTime) {
            this.generatedTime = generatedTime != null ? generatedTime : LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            return this;
        }

        public Builder durationSeconds(long durationSeconds) {
            this.durationSeconds = durationSeconds;
            return this;
        }

        public Builder migTotal(long migTotal) {
            this.migTotal = migTotal;
            return this;
        }

        public Builder migSuccess(long migSuccess) {
            this.migSuccess = migSuccess;
            return this;
        }

        public Builder migFailed(long migFailed) {
            this.migFailed = migFailed;
            return this;
        }

        public Builder migSkipped(long migSkipped) {
            this.migSkipped = migSkipped;
            return this;
        }

        public Builder migSuccessRate(double migSuccessRate) {
            this.migSuccessRate = migSuccessRate;
            this.migSuccessRateClass = calculateSuccessRateClass(migSuccessRate);
            return this;
        }

        public Builder errorItems(List<ErrorItem> errorItems) {
            this.errorItems = errorItems != null ? new ArrayList<>(errorItems) : new ArrayList<>();
            return this;
        }

        public Builder addErrorItem(ErrorItem errorItem) {
            if (errorItem != null) {
                this.errorItems.add(errorItem);
            }
            return this;
        }

        public Builder verTotal(long verTotal) {
            this.verTotal = verTotal;
            return this;
        }

        public Builder verOk(long verOk) {
            this.verOk = verOk;
            return this;
        }

        public Builder verMismatch(long verMismatch) {
            this.verMismatch = verMismatch;
            return this;
        }

        public Builder verOrphaned(long verOrphaned) {
            this.verOrphaned = verOrphaned;
            return this;
        }

        public Builder verDeleted(long verDeleted) {
            this.verDeleted = verDeleted;
            return this;
        }

        public Builder verSuccessRate(double verSuccessRate) {
            this.verSuccessRate = verSuccessRate;
            this.verSuccessRateClass = calculateSuccessRateClass(verSuccessRate);
            return this;
        }

        public Builder mismatchItems(List<MismatchItem> mismatchItems) {
            this.mismatchItems = mismatchItems != null ? new ArrayList<>(mismatchItems) : new ArrayList<>();
            return this;
        }

        public Builder addMismatchItem(MismatchItem mismatchItem) {
            if (mismatchItem != null) {
                this.mismatchItems.add(mismatchItem);
            }
            return this;
        }

        public Builder checksumSamples(List<ChecksumSample> checksumSamples) {
            this.checksumSamples = checksumSamples != null ? new ArrayList<>(checksumSamples) : new ArrayList<>();
            return this;
        }

        public Builder addChecksumSample(ChecksumSample checksumSample) {
            if (checksumSample != null) {
                this.checksumSamples.add(checksumSample);
            }
            return this;
        }

        public Builder overallStatusClass(String overallStatusClass) {
            this.overallStatusClass = overallStatusClass != null ? overallStatusClass : "success";
            return this;
        }

        public Builder overallStatusIcon(String overallStatusIcon) {
            this.overallStatusIcon = overallStatusIcon != null ? overallStatusIcon : "✓";
            return this;
        }

        public Builder overallStatusText(String overallStatusText) {
            this.overallStatusText = overallStatusText != null ? overallStatusText : "Erfolgreich";
            return this;
        }

        /**
         * Berechnet automatisch die Migrations-Erfolgsrate aus den gesetzten Werten.
         */
        public Builder calculateMigrationRate() {
            this.migSuccessRate = calculateSuccessRate(this.migSuccess, this.migTotal);
            this.migSuccessRateClass = calculateSuccessRateClass(this.migSuccessRate);
            return this;
        }

        /**
         * Berechnet automatisch die Verifikations-Erfolgsrate aus den gesetzten Werten.
         */
        public Builder calculateVerificationRate() {
            this.verSuccessRate = calculateSuccessRate(this.verOk, this.verTotal);
            this.verSuccessRateClass = calculateSuccessRateClass(this.verSuccessRate);
            return this;
        }

        /**
         * Berechnet automatisch den Gesamtstatus basierend auf Migration und Verifikation.
         */
        public Builder calculateOverallStatus() {
            double worstRate = Math.min(this.migSuccessRate, this.verSuccessRate);
            this.overallStatusClass = calculateSuccessRateClass(worstRate);
            
            if (worstRate >= 98.0) {
                this.overallStatusIcon = "✓";
                this.overallStatusText = "Erfolgreich";
            } else if (worstRate >= 90.0) {
                this.overallStatusIcon = "⚠";
                this.overallStatusText = "Mit Warnungen";
            } else {
                this.overallStatusIcon = "✗";
                this.overallStatusText = "Fehlgeschlagen";
            }
            return this;
        }

        public ProtocolData build() {
            return new ProtocolData(this);
        }
    }
}
