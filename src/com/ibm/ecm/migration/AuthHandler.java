/*
 * Projekt: CM Migrator 2.2.1.
 * @Author: Aleksej Voronin, Sven Lindt
 * @Date:   26.01.2026
 */
package com.ibm.ecm.migration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AuthHandler - HTTP Basic Authentication Handler für WebGUI
 * 
 * Schützt Admin-Endpunkte vor unbefugtem Zugriff.
 * Monitoring-Endpunkte bleiben öffentlich zugänglich.
 * 
 * Features (todo):
 * - HTTP Basic Authentication
 * - Rate-Limiting bei fehlgeschlagenen Versuchen
 * - SHA-256 Passwort-Hashing
 * - Audit-Logging
 * 
 */
public class AuthHandler implements HttpHandler {
    private static final Logger logger = LogManager.getLogger(AuthHandler.class);
    
    private final HttpHandler wrappedHandler;
    private final String adminUsername;
    private final String adminPasswordHash;
    private final boolean authEnabled;
    
    // Rate limiting: IP -> failed attempts count
    private static final Map<String, FailedLoginInfo> failedAttempts = new ConcurrentHashMap<>();
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION_MS = 5 * 60 * 1000; // 5 Minuten
    
    /**
     * Constructor mit Konfiguration aus Properties.
     */
    public AuthHandler(HttpHandler wrappedHandler, Properties config) {
        this.wrappedHandler = wrappedHandler;
        
        // Lade Authentifizierungskonfiguration
        String username = config.getProperty("webgui.admin.user", 
                                             System.getenv("WEBGUI_ADMIN_USER"));
        String password = config.getProperty("webgui.admin.password",
                                             System.getenv("WEBGUI_ADMIN_PASSWORD"));
        String passwordHash = config.getProperty("webgui.admin.password.hash");
        String enabled = config.getProperty("webgui.auth.enabled", "true");
        
        this.authEnabled = "true".equalsIgnoreCase(enabled) && 
                          (username != null && !username.isEmpty());
        
        if (authEnabled) {
            this.adminUsername = username;
            
            // Verwende Hash wenn vorhanden, sonst hashe das Passwort
            if (passwordHash != null && !passwordHash.isEmpty()) {
                this.adminPasswordHash = passwordHash;
            } else if (password != null && !password.isEmpty()) {
                this.adminPasswordHash = hashPassword(password);
                logger.info("WebGUI Admin-Authentifizierung aktiviert für Benutzer: {}", username);
            } else {
                // Generiere zufälliges Passwort wenn keins gesetzt
                String randomPassword = generateRandomPassword();
                this.adminPasswordHash = hashPassword(randomPassword);
                logger.warn("======================================================");
                logger.warn(" WEBGUI ADMIN-PASSWORT NICHT KONFIGURIERT!");
                logger.warn(" Generiertes temporäres Passwort: {}", randomPassword);
                logger.warn(" Bitte in migration.properties setzen:");
                logger.warn("   webgui.admin.user=admin");
                logger.warn("   webgui.admin.password=IhrSicheresPasswort");
                logger.warn("======================================================");
            }
        } else {
            this.adminUsername = null;
            this.adminPasswordHash = null;
            logger.warn("WebGUI Admin-Authentifizierung ist DEAKTIVIERT!");
        }
    }
    
    /**
     * Einfacher Constructor mit direkten Credentials.
     */
    public AuthHandler(HttpHandler wrappedHandler, String username, String password) {
        this.wrappedHandler = wrappedHandler;
        this.adminUsername = username;
        this.adminPasswordHash = hashPassword(password);
        this.authEnabled = username != null && !username.isEmpty();
    }
    
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Wenn Auth deaktiviert, direkt weiterleiten
        if (!authEnabled) {
            wrappedHandler.handle(exchange);
            return;
        }
        
        String clientIP = getClientIP(exchange);
        
        // Rate-Limiting prüfen
        if (isLockedOut(clientIP)) {
            logger.warn("Gesperrte IP versuchte Zugriff: {}", clientIP);
            sendUnauthorized(exchange, "Too many failed attempts. Please try again later.");
            return;
        }
        
        // Authorization Header prüfen
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            sendUnauthorized(exchange, "Authentication required");
            return;
        }
        
        // Basic Auth dekodieren
        try {
            String base64Credentials = authHeader.substring(6);
            String credentials = new String(Base64.getDecoder().decode(base64Credentials), StandardCharsets.UTF_8);
            String[] parts = credentials.split(":", 2);
            
            if (parts.length != 2) {
                recordFailedAttempt(clientIP);
                sendUnauthorized(exchange, "Invalid credentials format");
                return;
            }
            
            String username = parts[0];
            String password = parts[1];
            
            // Credentials validieren
            if (validateCredentials(username, password)) {
                // Erfolgreicher Login
                clearFailedAttempts(clientIP);
                logger.info("Erfolgreicher WebGUI-Login von IP: {} als {}", clientIP, username);
                wrappedHandler.handle(exchange);
            } else {
                // Fehlgeschlagener Login
                recordFailedAttempt(clientIP);
                logger.warn("Fehlgeschlagener WebGUI-Login von IP: {} für Benutzer: {}", clientIP, username);
                sendUnauthorized(exchange, "Invalid username or password");
            }
            
        } catch (Exception e) {
            logger.error("Auth-Fehler: {}", e.getMessage());
            recordFailedAttempt(clientIP);
            sendUnauthorized(exchange, "Authentication error");
        }
    }
    
    /**
     * Validiert Benutzername und Passwort.
     */
    private boolean validateCredentials(String username, String password) {
        if (username == null || password == null) return false;
        
        // Timing-Attack-sicherer Vergleich
        boolean usernameMatch = MessageDigest.isEqual(
            username.getBytes(StandardCharsets.UTF_8),
            adminUsername.getBytes(StandardCharsets.UTF_8)
        );
        
        String providedHash = hashPassword(password);
        boolean passwordMatch = MessageDigest.isEqual(
            providedHash.getBytes(StandardCharsets.UTF_8),
            adminPasswordHash.getBytes(StandardCharsets.UTF_8)
        );
        
        return usernameMatch && passwordMatch;
    }
    
    /**
     * Hash ein Passwort mit SHA-256.
     */
    public static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
    
    /**
     * Generiert ein zufälliges Passwort.
     */
    private String generateRandomPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789!@#$%";
        StringBuilder sb = new StringBuilder();
        java.util.Random random = new java.security.SecureRandom();
        for (int i = 0; i < 12; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
    
    /**
     * Holt die Client-IP-Adresse.
     */
    private String getClientIP(HttpExchange exchange) {
        // Prüfe X-Forwarded-For Header (für Reverse-Proxy)
        String xff = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }
    
    /**
     * Prüft ob eine IP gesperrt ist.
     */
    private boolean isLockedOut(String ip) {
        FailedLoginInfo info = failedAttempts.get(ip);
        if (info == null) return false;
        
        // Prüfe ob Sperrzeit abgelaufen
        if (System.currentTimeMillis() - info.lastAttempt > LOCKOUT_DURATION_MS) {
            failedAttempts.remove(ip);
            return false;
        }
        
        return info.count >= MAX_FAILED_ATTEMPTS;
    }
    
    /**
     * Zeichnet einen fehlgeschlagenen Versuch auf.
     */
    private void recordFailedAttempt(String ip) {
        failedAttempts.compute(ip, (k, v) -> {
            if (v == null) return new FailedLoginInfo(1, System.currentTimeMillis());
            return new FailedLoginInfo(v.count + 1, System.currentTimeMillis());
        });
        
        FailedLoginInfo info = failedAttempts.get(ip);
        if (info != null && info.count >= MAX_FAILED_ATTEMPTS) {
            logger.warn("IP {} wurde nach {} Fehlversuchen für {} Minuten gesperrt", 
                       ip, MAX_FAILED_ATTEMPTS, LOCKOUT_DURATION_MS / 60000);
        }
    }
    
    /**
     * Löscht fehlgeschlagene Versuche für eine IP.
     */
    private void clearFailedAttempts(String ip) {
        failedAttempts.remove(ip);
    }
    
    /**
     * Sendet eine 401 Unauthorized Antwort.
     */
    private void sendUnauthorized(HttpExchange exchange, String message) throws IOException {
        exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"CM Migrator WebGUI\"");
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        
        String json = "{\"error\":\"" + message + "\",\"code\":401}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        
        exchange.sendResponseHeaders(401, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
    
    /**
     * Info-Klasse für fehlgeschlagene Login-Versuche.
     */
    private static class FailedLoginInfo {
        final int count;
        final long lastAttempt;
        
        FailedLoginInfo(int count, long lastAttempt) {
            this.count = count;
            this.lastAttempt = lastAttempt;
        }
    }
    
    /**
     * Utility: Generiert einen Passwort-Hash für Konfiguration.
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java AuthHandler <password>");
            System.out.println("Generiert einen SHA-256 Hash für das Passwort.");
            return;
        }
        
        String password = args[0];
        String hash = hashPassword(password);
        System.out.println("Password Hash (SHA-256):");
        System.out.println(hash);
        System.out.println();
        System.out.println("Fügen Sie folgende Zeile in migration.properties ein:");
        System.out.println("webgui.admin.password.hash=" + hash);
    }
}
