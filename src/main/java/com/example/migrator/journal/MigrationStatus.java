package com.example.migrator.journal;

/**
 * Definiert die möglichen Zustände eines Migrationsobjekts.
 */
public enum MigrationStatus {
    PENDING,     // Noch nicht bearbeitet
    IN_PROGRESS, // Wird gerade migriert
    COMPLETED,   // Erfolgreich migriert
    FAILED       // Fehler bei der Migration
}
