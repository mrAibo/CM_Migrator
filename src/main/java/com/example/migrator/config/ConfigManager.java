package com.example.migrator.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Verwaltet die Konfiguration der Anwendung.
 * Lädt Eigenschaften aus einer Datei (Standard: migrator.properties).
 */
public class ConfigManager {
    private static final Logger logger = LogManager.getLogger(ConfigManager.class);
    private static final Properties properties = new Properties();
    private static boolean initialized = false;

    /**
     * Lädt die Konfiguration aus der angegebenen Datei.
     * @param configFilePath Pfad zur Properties-Datei.
     * @throws IOException Wenn die Datei nicht gefunden oder gelesen werden kann.
     */
    public static synchronized void load(String configFilePath) throws IOException {
        if (initialized) return;
        
        logger.info("Lade Konfiguration von: " + configFilePath);
        try (InputStream input = new FileInputStream(configFilePath)) {
            properties.load(input);
        }
        initialized = true;
    }

    public static String get(String key) {
        return properties.getProperty(key);
    }

    public static String get(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    public static int getInt(String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            logger.warn("Konnte Konfigurationswert für '" + key + "' nicht als Zahl parsen: " + value + ". Verwende Standardwert: " + defaultValue);
            return defaultValue;
        }
    }
    
    public static boolean getBoolean(String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) return defaultValue;
        return Boolean.parseBoolean(value);
    }
}
