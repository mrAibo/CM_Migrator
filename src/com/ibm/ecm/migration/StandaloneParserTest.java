/*
 * Projekt: CM Migrator 2.2.1.
 * @Author: Aleksej Voronin, Sven Lindt
 * @Date:   26.01.2026
 */
package com.ibm.ecm.migration;

/**
 * Standalone test utility to verify the hardened temporal parsing logic.
 * Run this to ensure that Date, Time, and Timestamp conversions are robust.
 */
public class StandaloneParserTest {

    public static void main(String[] args) {
        System.out.println("=== CM Migrator Parser Test (v1.7) ===");
        
        testDate();
        testTime();
        testTimestamp();
        
        System.out.println("\nAll tests passed successfully.");
    }

    private static void testDate() {
        System.out.println("\nTesting [Date] Parsing:");
        verifyDate("2025-01-01", "2025-01-01");
        verifyDate("2025-01-01T12:00:00", "2025-01-01");
        verifyDate("2025-01-01 12:00:00", "2025-01-01");
    }

    private static void testTime() {
        System.out.println("\nTesting [Time] Parsing:");
        verifyTime("12:34:56", "12:34:56");
        verifyTime("2025-01-01T12:34:56", "12:34:56");
        verifyTime("12:34:56.123", "12:34:56");
        verifyTime("12:34:56.123+01:00", "12:34:56");
    }

    private static void testTimestamp() {
        System.out.println("\nTesting [Timestamp] Parsing:");
        verifyTimestamp("2025-01-01 12:34:56", "2025-01-01 12:34:56.0");
        verifyTimestamp("2025-01-01T12:34:56", "2025-01-01 12:34:56.0");
        verifyTimestamp("2025-01-01T12:34:56.123", "2025-01-01 12:34:56.123");
        verifyTimestamp("2025-01-01T12:34:56.123+01:00", "2025-01-01 12:34:56.123");
        verifyTimestamp("2025-01-01T12:34:56.123-01:00", "2025-01-01 12:34:56.123");
        verifyTimestamp("2025-01-01T12:34:56.123Z", "2025-01-01 12:34:56.123");
    }

    private static void verifyDate(String input, String expected) {
        java.sql.Date result = toSqlDate((short)1, input);
        if (result.toString().equals(expected)) {
            System.out.println("  [OK] '" + input + "' -> " + result);
        } else {
            throw new RuntimeException("FAILED: '" + input + "' expected " + expected + " but got " + result);
        }
    }

    private static void verifyTime(String input, String expected) {
        java.sql.Time result = toSqlTime((short)2, input);
        if (result.toString().equals(expected)) {
            System.out.println("  [OK] '" + input + "' -> " + result);
        } else {
            throw new RuntimeException("FAILED: '" + input + "' expected " + expected + " but got " + result);
        }
    }

    private static void verifyTimestamp(String input, String expected) {
        java.sql.Timestamp result = toSqlTimestamp((short)3, input);
        if (result.toString().equals(expected)) {
            System.out.println("  [OK] '" + input + "' -> " + result);
        } else {
            throw new RuntimeException("FAILED: '" + input + "' expected " + expected + " but got " + result);
        }
    }

    // --- COPIED FROM ItemMigrator.java for isolated verification ---

    private static java.sql.Date toSqlDate(short attrId, Object v) {
        if (v == null) return null;
        if (v instanceof java.sql.Date) return (java.sql.Date) v;
        if (v instanceof java.util.Date) return new java.sql.Date(((java.util.Date) v).getTime());
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        try {
            String datePart = s.replace('T', ' ').split(" ")[0];
            return java.sql.Date.valueOf(datePart);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid DATE format for attribute ID " + attrId + ": '" + s + "'", e);
        }
    }

    private static java.sql.Time toSqlTime(short attrId, Object v) {
        if (v == null) return null;
        if (v instanceof java.sql.Time) return (java.sql.Time) v;
        if (v instanceof java.util.Date) return new java.sql.Time(((java.util.Date) v).getTime());
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        try {
            String processed = s.replace('T', ' ');
            String timePart = processed.contains(" ") ? processed.split(" ")[1] : processed;
            timePart = timePart.split("\\.")[0];
            timePart = timePart.replaceAll("([Zz]|[+-]\\d\\d:?\\d\\d)$", "");
            return java.sql.Time.valueOf(timePart);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid TIME format for attribute ID " + attrId + ": '" + s + "'", e);
        }
    }

    private static java.sql.Timestamp toSqlTimestamp(short attrId, Object v) {
        if (v == null) return null;
        if (v instanceof java.sql.Timestamp) return (java.sql.Timestamp) v;
        if (v instanceof java.util.Date) return new java.sql.Timestamp(((java.util.Date) v).getTime());
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        String processed = s.replace('T', ' ');
        processed = processed.replaceAll("([Zz]|[+-]\\d\\d:?\\d\\d)$", "");
        try {
            return java.sql.Timestamp.valueOf(processed);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid TIMESTAMP format for attribute ID " + attrId + ": '" + s + "'", e);
        }
    }
}
