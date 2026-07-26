/*
 * Projekt: CM Migrator 2.2.1.
 * @Author: Aleksej Voronin, Sven Lindt
 * @Date:   26.01.2026
 * 
 * Thread-sicherer Container für Migrationsstatistiken.
 */
package com.ibm.ecm.migration;

import java.util.concurrent.atomic.AtomicLong;

public class MigrationStats {
    private final AtomicLong totalItems = new AtomicLong(0);
    private final AtomicLong discoveredItems = new AtomicLong(0);
    private final AtomicLong processedItems = new AtomicLong(0);
    private final AtomicLong successItems = new AtomicLong(0);
    private final AtomicLong failedItems = new AtomicLong(0);
    private final AtomicLong skippedItems = new AtomicLong(0);
    private final AtomicLong deletedItems = new AtomicLong(0); // v1.25
    private final long startTime;

    public MigrationStats() {
        this.startTime = System.currentTimeMillis();
    }

    public void setTotalItems(long total) {
        this.totalItems.set(total);
    }
    
    public void addTotalItems(long count) {
        this.totalItems.addAndGet(count);
    }

    public void incrementDiscovered() {
        discoveredItems.incrementAndGet();
    }

    public void incrementSuccess() {
        processedItems.incrementAndGet();
        successItems.incrementAndGet();
    }

    public void incrementFailed() {
        processedItems.incrementAndGet();
        failedItems.incrementAndGet();
    }

    public void recordResidualFailures(long count) {
        if (count > 0) failedItems.addAndGet(count);
    }
    
    public void incrementSkipped() {
        processedItems.incrementAndGet();
        skippedItems.incrementAndGet();
    }

    public void incrementDeleted() {
        processedItems.incrementAndGet();
        successItems.incrementAndGet();
        deletedItems.incrementAndGet();
    }

    public long getTotalItems() {
        return totalItems.get();
    }

    public long getProcessedItems() {
        return processedItems.get();
    }

    public long getSuccessItems() {
        return successItems.get();
    }

    public long getFailedItems() {
        return failedItems.get();
    }

    public long getDiscoveredItems() {
        return discoveredItems.get();
    }

    public long getErrorItems() {
        return failedItems.get(); // Alias für getFailedItems()
    }
    
    public long getSkippedItems() {
        return skippedItems.get();
    }

    public long getDeletedItems() {
        return deletedItems.get();
    }

    public long getStartTime() {
        return startTime;
    }
}
