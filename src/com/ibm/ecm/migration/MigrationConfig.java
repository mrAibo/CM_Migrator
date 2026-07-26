/*
 * Projekt: CM Migrator 2.2.1.
 * @Author: Aleksej Voronin, Sven Lindt
 * @Date:   26.01.2026
 */
package com.ibm.ecm.migration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Konfigurations-Loader für den IBM CM Migrator.
 * Verwendet das neue Key-Format (wie in mig.properties): SOURCE_SSID, THREAD_COUNT, OPERATION_MODE, etc.
 *
 * Unterstützte Funktionen:
 * - Getrennte Zugangsdaten für Quelle und Ziel
 * - Globaler Fallback via CONNECT_USER / CONNECT_PASSWORD(_CRYPT)
 * - Steuerung der Connection-Pool-Größen (SOURCE_POOL_SIZE / DEST_POOL_SIZE)
 * - Optionale Absicherung für Consumer (CONSUMER_DOUBLECHECK)
 */
public class MigrationConfig {
    private static final Logger logger = LogManager.getLogger(MigrationConfig.class);

    private final Properties properties = new Properties();
    private Map<String, String> itemTypeMapping = Collections.emptyMap();
    private String profile = null;

    // Profile Engine - Optimierte Standardwerte für verschiedene Laststufen
    private static final Map<String, Map<String, String>> PROFILES;
    static {
        Map<String, Map<String, String>> p = new HashMap<>();
        p.put("KLEIN", Map.of(
            "THREAD_COUNT", "5", "BATCH_SIZE", "50", "QUEUE_SIZE", "1000",
            "LOG_ITEMS_BATCHED", "false", "PRODUCER_COUNT_STRATEGY", "SINGLE_PASS"
        ));
        p.put("MITTEL", Map.of(
            "THREAD_COUNT", "20", "BATCH_SIZE", "200", "QUEUE_SIZE", "5000",
            "LOG_ITEMS_BATCHED", "true", "LOG_BATCH_INTERVAL", "1000", "PRODUCER_COUNT_STRATEGY", "SINGLE_PASS"
        ));
        p.put("GROSS", Map.of(
            "THREAD_COUNT", "50", "BATCH_SIZE", "500", "QUEUE_SIZE", "10000",
            "LOG_ITEMS_BATCHED", "true", "LOG_BATCH_INTERVAL", "5000", "PRODUCER_COUNT_STRATEGY", "SINGLE_PASS"
        ));
        p.put("EXTREM", Map.of(
            "THREAD_COUNT", "100", "BATCH_SIZE", "1000", "QUEUE_SIZE", "20000",
            "LOG_ITEMS_BATCHED", "true", "LOG_BATCH_INTERVAL", "10000", "PRODUCER_COUNT_STRATEGY", "SDK_CURSOR",
            "DB_URL_APPEND", ";LOG=0;CACHE_SIZE=65536;LOCK_MODE=0"
        ));
        p.put("ULTI", Map.of(
            "THREAD_COUNT", "200", "BATCH_SIZE", "2000", "QUEUE_SIZE", "50000",
            "LOG_ITEMS_BATCHED", "true", "LOG_BATCH_INTERVAL", "10000", "PRODUCER_COUNT_STRATEGY", "SDK_CURSOR",
            "DB_URL_APPEND", ";LOG=0;CACHE_SIZE=131072;LOCK_MODE=0;MAX_OPERATION_MEMORY=256000000"
        ));
        PROFILES = Collections.unmodifiableMap(p);
    }

    public MigrationConfig(String configFilePath) {
        loadProperties(configFilePath);
        this.profile = prop("PROFILE", null);
        if (profile != null) {
            profile = profile.toUpperCase().trim();
            if (PROFILES.containsKey(profile)) {
                logger.info("Diamond Profile Engine: Scaling to '{}' performance levels.", profile);
            } else {
                logger.warn("Diamond Profile Engine: Profile '{}' unknown. Using manual defaults.", profile);
                profile = null;
            }
        }
        parseItemTypeMapping();
        applyRuntimeFlags();
    }

    private void loadProperties(String configFilePath) {
        File configFile = new File(configFilePath);
        
        // Fallback: Wenn Datei nicht existiert, verwende Standard-Konfiguration
        if (!configFile.exists()) {
            logger.warn("Configuration file not found: {}, using standard: migration.properties", configFile.getName());
            configFile = new File("conf/migration.properties");
            if (!configFile.exists()) {
                logger.error("Standard configuration file also not found: conf/migration.properties");
                throw new RuntimeException("Configuration load failed: No config file found");
            }
        }
        
        try (FileInputStream fis = new FileInputStream(configFile)) {
            properties.load(fis);
            logger.info("Loaded configuration from: {}", configFile.getName());
        } catch (IOException e) {
            logger.error("Failed to load configuration file: {}", configFile.getPath(), e);
            throw new RuntimeException("Configuration load failed", e);
        }
    }

    private void parseItemTypeMapping() {
        // "Unscharfe" Suche via prop() erlaubt auch die Variante "MIGRATEITEMTYPES"
        String mappingStr = prop("MIGRATE_ITEMTYPES", "").trim();
        if (mappingStr.isEmpty()) {
            itemTypeMapping = Collections.emptyMap(); // Empty map means "Migrate All"
            logger.warn("No MIGRATE_ITEMTYPES configured. Mapping empty => migrate-all mode.");
            return;
        }

        Map<String, String> map = new HashMap<>();
        String[] pairs = mappingStr.split(",");
        for (String pair : pairs) {
            String[] parts = pair.split(":");
            if (parts.length == 2) {
                map.put(parts[0].trim(), parts[1].trim());
            } else if (parts.length == 1) {
                String type = parts[0].trim();
                map.put(type, type);
            }
        }
        itemTypeMapping = Collections.unmodifiableMap(map);
        logger.info("Configured ItemType Mappings: {}", itemTypeMapping);
    }

    /**
     * Aktiviert Laufzeit-Flags basierend auf der Konfiguration.
     * Existierende -D Systemeigenschaften haben Vorrang und werden nicht überschrieben.
     * 
     * Aktuell genutzt für den Stream-Upload Toggle, der im ItemMigrator via 
     * Boolean.getBoolean("cm.migrator.streamUpload") abgefragt wird.
     */
    private void applyRuntimeFlags() {
        if (isStreamUploadEnabled()) {
            setSystemPropertyIfAbsent("cm.migrator.streamUpload", "true");
        }
    }

    private static void setSystemPropertyIfAbsent(String key, String value) {
        try {
            if (System.getProperty(key) == null) {
                System.setProperty(key, value);
            }
        } catch (SecurityException se) {
            // In restriktiven Umgebungen (SecurityManager) ignorieren wir das und setzen auf JVM-Flags.
        }
    }

    /**
     * Ermöglicht den direkten Stream-Upload von Content-Teilen (ohne temporäre Dateien).
     * 
     * Wenn aktiv, setzt MigrationConfig die System-Property "cm.migrator.streamUpload=true",
     * sofern diese nicht bereits per -D Parameter gesetzt wurde.
     */
    public boolean isStreamUploadEnabled() {
        return propBool("STREAM_UPLOAD", false);
    }

    // ========================================================================
    // Hilfsmethoden zum Auslesen der Properties
    // ========================================================================

    private String prop(String key, String def) {
        // 1. Exakter Treffer in der Datei
        String v = properties.getProperty(key);

        // 2. "Unscharfer" Treffer (ohne Unterstriche)
        if (v == null && key.contains("_")) {
            String fuzzyKey = key.replace("_", "");
            v = properties.getProperty(fuzzyKey);
            if (v != null) {
                logger.debug("Config: Match for '{}' via fuzzy '{}'", key, fuzzyKey);
            }
        }

        // 3. Standardwert aus dem Profil
        if (v == null && profile != null) {
            Map<String, String> defaults = PROFILES.get(profile);
            if (defaults != null) {
                v = defaults.get(key);
                if (v != null) {
                    logger.debug("Config: Match for '{}' via Profile '{}'", key, profile);
                }
            }
        }

        if (v == null) return def;
        v = v.trim();
        return v.isEmpty() ? def : v;
    }

    private String propNullable(String key) {
        return prop(key, null);
    }

    private int propInt(String key, int def, int min, int max) {
        // Use fuzzy lookup
        String v = prop(key, null);
        if (v == null) return def;
        try {
            int n = Integer.parseInt(v);
            if (n < min || n > max) {
                logger.warn("Config {}={} out of range ({}..{}). Using default {}.", key, n, min, max, def);
                return def;
            }
            return n;
        } catch (NumberFormatException e) {
            logger.warn("Config {} is not an integer ({}). Using default {}.", key, v, def);
            return def;
        }
    }

    private boolean propBool(String key, boolean def) {
        // Use fuzzy lookup
        String v = prop(key, null);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    private boolean propEnabled(String key, boolean def) {
        String v = prop(key, null);
        if (v == null) {
            String normalizedKey = key.replace("_", "").toUpperCase();
            for (String candidate : properties.stringPropertyNames()) {
                if (candidate.replace("_", "").toUpperCase().equals(normalizedKey)) {
                    v = properties.getProperty(candidate);
                    break;
                }
            }
        }
        if (v == null) return def;
        switch (v.trim().toLowerCase()) {
            case "true":
            case "yes":
            case "1":
            case "on":
                return true;
            default:
                return false;
        }
    }

    // ========================================================================
    // Passwort Ent- und Verschlüsselung (Legacy-kompatibel)
    // ========================================================================

    /**
     * Logik zur Passwort-Entschlüsselung:
     * Base64 Decode -> String umkehren -> Erneutes Base64 Decode.
     * Fallback: Falls der zweite Schritt scheitert, wird das erste Ergebnis als Klartext gewertet.
     */
    public static String decodePW(String pw) {
        if (pw == null) return null;
        try {
            byte[] firstDecode = Base64.getDecoder().decode(pw);

            try {
                String reversed = new StringBuilder(new String(firstDecode)).reverse().toString();
                byte[] secondDecode = Base64.getDecoder().decode(reversed);
                return new String(secondDecode);
            } catch (IllegalArgumentException e) {
                // fallback: user provided simple base64(password)
                return new String(firstDecode);
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ========================================================================
    // Verbindungen / Zugangsdaten
    // ========================================================================

    public String getSourceSSID() { return propNullable("SOURCE_SSID"); }

    public String getDestSSID() { return propNullable("DEST_SSID"); }

    /**
     * Rangfolge: SOURCE_USER -> CONNECT_USER
     */
    public String getSourceUser() {
        String v = propNullable("SOURCE_USER");
        return v != null ? v : getConnectUser();
    }

    /**
     * Rangfolge:
     * SOURCE_PASSWORD_CRYPT -> SOURCE_PASSWORD -> CONNECT_PASSWORD_CRYPT -> CONNECT_PASSWORD
     */
    public String getSourcePassword() {
        String v = propNullable("SOURCE_PASSWORD_CRYPT");
        if (v != null) {
            String decoded = decodePW(v);
            if (decoded != null) return decoded;
        }

        v = propNullable("SOURCE_PASSWORD");
        if (v != null) return v;

        return getConnectPassword();
    }

    /**
     * Rangfolge: DEST_USER -> CONNECT_USER
     */
    public String getDestUser() {
        String v = propNullable("DEST_USER");
        return v != null ? v : getConnectUser();
    }

    /**
     * Rangfolge:
     * DEST_PASSWORD_CRYPT -> DEST_PASSWORD -> CONNECT_PASSWORD_CRYPT -> CONNECT_PASSWORD
     */
    public String getDestPassword() {
        String v = propNullable("DEST_PASSWORD_CRYPT");
        if (v != null) {
            String decoded = decodePW(v);
            if (decoded != null) return decoded;
        }

        v = propNullable("DEST_PASSWORD");
        if (v != null) return v;

        return getConnectPassword();
    }

    public String getConnectUser() {
        return prop("CONNECT_USER", "");
    }

    public String getConnectPassword() {
        String v = propNullable("CONNECT_PASSWORD_CRYPT");
        if (v != null) {
            String decoded = decodePW(v);
            if (decoded != null) return decoded;
        }
        return prop("CONNECT_PASSWORD", "");
    }

    // ========================================================================
    // Umfang / Modus
    // ========================================================================

    public Map<String, String> getItemTypeMapping() { return itemTypeMapping; }

    public boolean isMigrateAll() { return itemTypeMapping.isEmpty(); }

    public String getOperationMode() {
        return prop("OPERATION_MODE", "MIGRATE").toUpperCase();
    }

    public String getFilterPredicate() {
        return prop("FILTER_PREDICATE", "").trim();
    }

    public Set<String> getIgnoredMigrationAttributes() {
        Set<String> names = new LinkedHashSet<>();
        for (String value : prop("MIGRATION_IGNORED_ATTRIBUTES", "").split(",")) {
            String name = value.trim();
            if (!name.isEmpty()) names.add(name);
        }
        return Collections.unmodifiableSet(names);
    }

    public boolean isDryRun() {
        return propBool("DRY_RUN", false);
    }

    // ========================================================================
    // Performance / Batching
    // ========================================================================

    public int getThreadCount() {
        return propInt("THREAD_COUNT", 5, 1, 200);
    }

    public int getBatchSize() {
        return propInt("BATCH_SIZE", 100, 1, 10_000);
    }

    public int getQueueSize() {
        return propInt("QUEUE_SIZE", 10_000, 100, 1_000_000);
    }

    /**
     * Strategie zur Ermittlung der Item-Anzahl
     * Werte: SDK_CURSOR, SINGLE_PASS (Standard)
     */
    public String getProducerCountStrategy() {
        return prop("PRODUCER_COUNT_STRATEGY", "SINGLE_PASS").toUpperCase();
    }

    /**
     * Phase-1: Falls aktiv, prüft der Consumer das Journal erneut (kostet Performance).
     * Standard: false (empfohlen, wenn nur eine Instanz läuft).
     */
    public boolean isConsumerDoubleCheck() {
        return propBool("CONSUMER_DOUBLECHECK", false);
    }

    /**
     * Phase-1: Getrennte Pool-Größen (optional).
     * Standardwerte:
     * - SOURCE_POOL_SIZE = THREAD_COUNT + 1 (Producer benötigt eigene Verbindung)
     * - DEST_POOL_SIZE   = THREAD_COUNT
     */
    public int getSourcePoolSize() {
        int def = Math.max(1, getThreadCount() + 1);
        return propInt("SOURCE_POOL_SIZE", def, 1, 500);
    }

    public int getDestPoolSize() {
        int def = Math.max(1, getThreadCount());
        return propInt("DEST_POOL_SIZE", def, 1, 500);
    }

    public int getPoolBorrowTimeoutMs() {
        return propInt("POOL_BORROW_TIMEOUT", 5000, 100, 60000);
    }

    public int getPoolMaxWaitTimeMs() {
        return propInt("POOL_MAX_WAIT_TIME", 10000, 100, 300000);
    }

    /** Maximum regular worker runtime before graceful shutdown is requested. */
    public int getWorkerTimeoutSeconds() {
        return propInt("WORKER_TIMEOUT_SECONDS", 86_400, 1, 604_800);
    }

    /** Bounded grace period after timeout, interrupt, or terminal failure. */
    public int getShutdownGraceSeconds() {
        return propInt("SHUTDOWN_GRACE_SECONDS", 60, 1, 3_600);
    }

    // ========================================================================
    // Journal / eMail
    // ========================================================================

    public String getDbPath() {
        return prop("DB_PATH", "./data/migration_journal");
    }
    
    /**
     * Gibt das Datenverzeichnis für die Journal-Datenbanken zurück.
     * Standard: ./data
     */
    public String getDataDir() {
        return prop("DATA_DIR", "./data");
    }

    public String getEmailTo() {
        return prop("EMAIL_TO", "").trim();
    }

    /**
     * Enterprise Scaling - Reduziertes Logging (nur Batches)
     */
    public boolean isLogItemsBatched() {
        return propBool("LOG_ITEMS_BATCHED", false);
    }

    public int getLogBatchInterval() {
        return propInt("LOG_BATCH_INTERVAL", 10_000, 1, 1_000_000);
    }

    public boolean isLogErrorsImmediate() {
        return propBool("LOG_ERRORS_IMMEDIATE", true);
    }

    /**
     * Enterprise Scaling - H2 Datenbank Tuning
     * Beispiel: ;CACHE_SIZE=65536
     */
    public String getDbUrlAppend() {
        return prop("DB_URL_APPEND", "").trim();
    }

    // ========================================================================
    // Cascade Delete & Audit Protokoll
    // ========================================================================

    /**
     * Falls aktiv, werden Items am Ziel gelöscht, wenn sie an der Quelle nicht mehr existieren.
     * ACHTUNG: Destruktive Operation! Standard: false
     */
    public boolean isCascadeDeleteOnMissing() {
        return propEnabled("CASCADE_DELETE_ON_MISSING", false);
    }

    /**
     * Falls aktiv, wird nach der Verifizierung ein formelles Audit-Protokoll pro ItemType erstellt.
     */
    public boolean isGenerateAuditProtocol() {
        return propBool("GENERATE_AUDIT_PROTOCOL", true);
    }

    /**
    * Ausgabeordner für die Audit-Protokolle.
     */
    public String getAuditProtocolOutputDir() {
        return prop("AUDIT_PROTOCOL_OUTPUT_DIR", "./reports");
    }

    // ========================================================================
    // Auto-Remigration
    // ========================================================================

    /**
     * Falls aktiv, markiert der Verifier fehlerhafte Elemente (MISMATCH) automatisch für 
     * eine erneute Migration, indem der Status im AUDIT_LOG auf 'FAILED' gesetzt wird.
     * Dadurch entfällt der manuelle Schritt über remigrate.sh.
     * Standard: true
     */
    public boolean isAutoMarkForRemigration() {
        return propBool("AUTO_MARK_FOR_REMIGRATION", true);
    }

    // ========================================================================
    // v2.2.0: Protokoll-Berichterstellung
    // ========================================================================

    /**
     * Firmenname für die Protokoll-Header.
     * Standard: "Unbekannt"
     */
    public String getProtocolCompanyName() {
        return prop("PROTOCOL_COMPANY_NAME", "Unbekannt");
    }

    /**
     * Firmenlogo als HTML (z. B. img-Tag oder SVG) für Protokoll-Header.
     * Standard: leer
     */
    public String getProtocolCompanyLogo() {
        return prop("PROTOCOL_COMPANY_LOGO", "");
    }

    /**
     * Ausgabeordner für die generierten Protokoll-Berichte.
     * Standard: ./reports
     */
    public String getProtocolOutputDir() {
        return prop("PROTOCOL_OUTPUT_DIR", "./reports");
    }

    /**
     * Alias für SOURCE_SSID (bequemerer Zugriff).
     */
    public String getSourceSsid() {
        return getSourceSSID();
    }

    /**
     * Alias für DEST_SSID (bequemerer Zugriff).
     */
    public String getDestSsid() {
        return getDestSSID();
    }

    /**
     * Universeller Zugriff auf Properties mit Standardwert.
     * Hilfreich für benutzerdefinierte Felder in Protokoll-Templates.
     */
    public String getProperty(String key, String defaultValue) {
        return prop(key, defaultValue);
    }

    /**
     * Universeller Zugriff auf Properties ohne Standardwert (liefert null, falls nicht gesetzt).
     */
    public String getProperty(String key) {
        return propNullable(key);
    }
}
