/*
 * Projekt: CM Migrator 2.2.1.
 * @Author: Aleksej Voronin, Sven Lindt
 * @Date:   26.01.2026
 */
package com.ibm.ecm.migration;

/**
 * Konsolen-UI-Dienstprogramme für die Darstellung des Migrationsfortschritts.
 * Verwendet ANSI-Escape-Codes für Farben und Formatierungen.
 */
public class ConsoleUI {
    
    // ANSI Color Codes
    public static final String RESET = "\u001B[0m";
    public static final String BOLD = "\u001B[1m";
    public static final String DIM = "\u001B[2m";
    
    // Foreground Colors
    public static final String BLACK = "\u001B[30m";
    public static final String RED = "\u001B[31m";
    public static final String GREEN = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String BLUE = "\u001B[34m";
    public static final String MAGENTA = "\u001B[35m";
    public static final String CYAN = "\u001B[36m";
    public static final String WHITE = "\u001B[37m";
    
    // Bright Colors
    public static final String BRIGHT_GREEN = "\u001B[92m";
    public static final String BRIGHT_YELLOW = "\u001B[93m";
    public static final String BRIGHT_BLUE = "\u001B[94m";
    public static final String BRIGHT_CYAN = "\u001B[96m";
    public static final String BRIGHT_RED = "\u001B[91m";
    
    // Background Colors
    public static final String BG_GREEN = "\u001B[42m";
    public static final String BG_RED = "\u001B[41m";
    public static final String BG_BLUE = "\u001B[44m";
    public static final String BG_YELLOW = "\u001B[43m";
    
    // Unicode Box Drawing Characters
    public static final String BOX_TL = "╔";
    public static final String BOX_TR = "╗";
    public static final String BOX_BL = "╚";
    public static final String BOX_BR = "╝";
    public static final String BOX_H = "═";
    public static final String BOX_V = "║";
    public static final String BOX_T = "╦";
    public static final String BOX_B = "╩";
    public static final String BOX_L = "╠";
    public static final String BOX_R = "╣";
    public static final String BOX_X = "╬";
    
    // Progress Bar Characters
    public static final String PROGRESS_FULL = "█";
    public static final String PROGRESS_EMPTY = "░";
    public static final String PROGRESS_HALF = "▓";
    
    // Status Icons
    public static final String ICON_SUCCESS = "✓";
    public static final String ICON_ERROR = "✗";
    public static final String ICON_WARNING = "⚠";
    public static final String ICON_INFO = "ℹ";
    public static final String ICON_ARROW = "→";
    public static final String ICON_CLOCK = "⏱";
    public static final String ICON_SPEED = "⚡";
    public static final String ICON_DOC = "📄";
    public static final String ICON_TRASH = "🗑";
    public static final String ICON_CHECK = "✔";
    
    private static boolean colorsEnabled = true;
    
    static {
        // Farben deaktivieren, wenn einem Terminal ohne ANSI-Unterstützung ausgeführt wird
        String term = System.getenv("TERM");
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win") && term == null) {
            colorsEnabled = false;
        }
        // Also check for NO_COLOR environment variable (standard)
        if (System.getenv("NO_COLOR") != null) {
            colorsEnabled = false;
        }
    }
    
    /**
     * Farbausgabe aktivieren oder deaktivieren.
     */
    public static void setColorsEnabled(boolean enabled) {
        colorsEnabled = enabled;
    }
    
    /**
     * Gibt den Farbcode zurück, wenn Farben aktiviert sind, andernfalls eine leere Zeichenfolge.
     */
    public static String c(String colorCode) {
        return colorsEnabled ? colorCode : "";
    }
    
    /**
     * Colorize text with the given color.
     */
    public static String colorize(String text, String color) {
        if (!colorsEnabled) return text;
        return color + text + RESET;
    }
    
    /**
     * Eine Fortschrittsbalkenkette erstellen.
     * @param percent Fortschrittsprozentsatz (0-100)
     * @param width Breite des Fortschrittsbalkens in Zeichen
     * @return Formatierte Fortschrittsbalkenkette
     */
    public static String progressBar(double percent, int width) {
        int safeWidth = Math.max(0, width);
        double safePercent = Math.max(0.0, Math.min(100.0, percent));
        int filled = (int) Math.round(safePercent / 100.0 * safeWidth);
        int empty = safeWidth - filled;
        
        StringBuilder sb = new StringBuilder();
        sb.append(c(BRIGHT_GREEN));
        for (int i = 0; i < filled; i++) {
            sb.append(PROGRESS_FULL);
        }
        sb.append(c(DIM));
        for (int i = 0; i < empty; i++) {
            sb.append(PROGRESS_EMPTY);
        }
        sb.append(c(RESET));
        
        return sb.toString();
    }
    
    /**
     * Formattiert status line für migration/verification/deletion.
     */
    public static String formatStatusLine(
            String mode,
            String sourceSSID,
            String destSSID,
            String sourceItemType,
            String destItemType,
            double percent,
            long processed,
            long total,
            double speed,
            String eta,
            String elapsed,
            long success,
            long failed,
            long skipped,
            long deleted) {
        
        StringBuilder sb = new StringBuilder();
        
        // Modus-Abzeichen mit Farbe
        String modeColor = getModeColor(mode);
        String modeIcon = getModeIcon(mode);
        sb.append(c(modeColor)).append(c(BOLD)).append(modeIcon).append(" ").append(mode).append(c(RESET));
        
        // Source -> Dest
        sb.append(" ").append(c(DIM)).append(sourceSSID).append(c(RESET));
        sb.append(c(CYAN)).append(" → ").append(c(RESET));
        sb.append(c(DIM)).append(destSSID).append(c(RESET));
        
        sb.append("\n");
        
        // ItemTypes
        sb.append("  ").append(c(DIM)).append(sourceItemType).append(c(RESET));
        sb.append(c(CYAN)).append(" → ").append(c(RESET));
        sb.append(c(DIM)).append(destItemType).append(c(RESET));
        
        sb.append("\n");
        
        // Progress bar
        sb.append("  ").append(progressBar(percent, 30));
        sb.append(" ").append(c(BOLD)).append(String.format("%5.1f%%", percent)).append(c(RESET));
        
        // Items count
        sb.append("  ").append(c(BRIGHT_CYAN)).append(String.format("%,d", processed)).append(c(RESET));
        sb.append(c(DIM)).append("/").append(c(RESET));
        sb.append(String.format("%,d", total));
        
        sb.append("\n");
        
        // Speed and ETA
        sb.append("  ").append(c(YELLOW)).append(ICON_SPEED).append(c(RESET));
        sb.append(" ").append(String.format("%.1f", speed)).append(" it/s");
        
        sb.append("  ").append(c(CYAN)).append(ICON_CLOCK).append(c(RESET));
        sb.append(" ETA: ").append(eta);
        
        sb.append("  ").append(c(DIM)).append("Elapsed: ").append(elapsed).append(c(RESET));
        
        sb.append("\n");
        
        // Status counters
        sb.append("  ");
        
        // Success
        sb.append(c(GREEN)).append(ICON_SUCCESS).append(c(RESET));
        sb.append(" ").append(String.format("%,d", success));
        
        // Failed
        if (failed > 0) {
            sb.append("  ").append(c(RED)).append(ICON_ERROR).append(c(RESET));
            sb.append(" ").append(c(RED)).append(String.format("%,d", failed)).append(c(RESET));
        } else {
            sb.append("  ").append(c(DIM)).append(ICON_ERROR).append(" 0").append(c(RESET));
        }
        
        // Skipped
        if (skipped > 0) {
            sb.append("  ").append(c(YELLOW)).append(ICON_WARNING).append(c(RESET));
            sb.append(" ").append(String.format("%,d", skipped));
        }
        
        // Deleted
        if (deleted > 0 || "DELETE".equalsIgnoreCase(mode)) {
            sb.append("  ").append(c(MAGENTA)).append(ICON_TRASH).append(c(RESET));
            sb.append(" ").append(String.format("%,d", deleted));
        }
        
        return sb.toString();
    }
    
    /**
     * Get color for operation mode.
     */
    private static String getModeColor(String mode) {
        if (mode == null) return BRIGHT_BLUE;
        switch (mode.toUpperCase()) {
            case "MIGRATE": return BRIGHT_GREEN;
            case "VERIFY": return BRIGHT_CYAN;
            case "DELETE": return BRIGHT_RED;
            default: return BRIGHT_BLUE;
        }
    }
    
    /**
     * Get icon for operation mode.
     */
    private static String getModeIcon(String mode) {
        if (mode == null) return ICON_DOC;
        switch (mode.toUpperCase()) {
            case "MIGRATE": return "📦";
            case "VERIFY": return "🔍";
            case "DELETE": return "🗑";
            default: return "📄";
        }
    }
    
    /**
     * Print a banner at startup.
     */
    public static String banner(String version) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(c(BRIGHT_CYAN)).append(BOX_TL).append(repeat(BOX_H, 50)).append(BOX_TR).append(c(RESET)).append("\n");
        sb.append(c(BRIGHT_CYAN)).append(BOX_V).append(c(RESET));
        sb.append(c(BOLD)).append("  IBM Content Manager Migrator").append(c(RESET));
        sb.append(c(DIM)).append(" v").append(version).append(c(RESET));
        sb.append(repeat(" ", 50 - 32 - version.length()));
        sb.append(c(BRIGHT_CYAN)).append(BOX_V).append(c(RESET)).append("\n");
        sb.append(c(BRIGHT_CYAN)).append(BOX_BL).append(repeat(BOX_H, 50)).append(BOX_BR).append(c(RESET)).append("\n");
        return sb.toString();
    }
    
    /**
     * Print a separator line.
     */
    public static String separator() {
        return c(DIM) + repeat("─", 60) + c(RESET);
    }
    
    /**
     * Repeat a string n times.
     */

    public static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.max(0, n); i++) {
            sb.append(s);
        }
        return sb.toString();
    }
    
    /**
     * Format a key-value pair for display.
     */
    public static String keyValue(String key, String value) {
        return c(DIM) + key + ": " + c(RESET) + c(BOLD) + value + c(RESET);
    }
    
    /**
     * Format a success message.
     */
    public static String success(String message) {
        return c(GREEN) + ICON_SUCCESS + " " + message + c(RESET);
    }
    
    /**
     * Format an error message.
     */
    public static String error(String message) {
        return c(RED) + ICON_ERROR + " " + message + c(RESET);
    }
    
    /**
     * Format a warning message.
     */
    public static String warning(String message) {
        return c(YELLOW) + ICON_WARNING + " " + message + c(RESET);
    }
    
    /**
     * Format an info message.
     */
    public static String info(String message) {
        return c(CYAN) + ICON_INFO + " " + message + c(RESET);
    }
}
