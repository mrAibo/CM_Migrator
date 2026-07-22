/*
 * Projekt: CM Migrator 2.2.1.
 * @Author: Aleksej Voronin, Sven Lindt
 * @Date:   26.01.2026
 */
package com.ibm.ecm.migration;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.concurrent.BlockingQueue;

/**
 * JMX-Überwachung und Metriken.
 * Bietet Echtzeit-Einblick in die Migrations-Engine.
 */
public class MigrationMetrics implements MigrationMetricsMBean {
    
    private final MigrationStats stats;
    private final BlockingQueue<?> queue;
    private final String sourceSsid;
    private final String destSsid;

    public MigrationMetrics(MigrationStats stats, BlockingQueue<?> queue, String sourceSsid, String destSsid) {
        this.stats = stats;
        this.queue = queue;
        this.sourceSsid = sourceSsid;
        this.destSsid = destSsid;
    }

    public static void register(MigrationStats stats, BlockingQueue<?> queue, String sourceSsid, String destSsid) {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("com.ibm.ecm.migration:type=MigrationEngine");
            MigrationMetrics mbean = new MigrationMetrics(stats, queue, sourceSsid, destSsid);
            mbs.registerMBean(mbean, name);
        } catch (Exception e) {
            // JMX error should not stop the migration
            System.err.println("JMX Registration failed: " + e.getMessage());
        }
    }

    @Override public long getProcessedItems() { return stats.getProcessedItems(); }
    @Override public long getSuccessItems() { return stats.getSuccessItems(); }
    @Override public long getErrorItems() { return stats.getErrorItems(); }
    @Override public long getSkippedItems() { return stats.getSkippedItems(); }
    @Override public long getDeletedItems() { return stats.getDeletedItems(); }
    @Override public long getTotalItems() { return stats.getTotalItems(); }
    @Override public int getQueueDepth() { return queue.size(); }
    
    @Override 
    public String getAvgRetrieveMs() { 
        return ItemMigrator.getPerformanceSnapshot().avgRetrieve; 
    }
    
    @Override 
    public String getAvgCopyMs() { 
        return ItemMigrator.getPerformanceSnapshot().avgCopy; 
    }
    
    @Override 
    public String getAvgAddMs() { 
        return ItemMigrator.getPerformanceSnapshot().avgAdd; 
    }

    @Override
    public double getMemoryUsagePercent() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        return (used / (double) runtime.maxMemory()) * 100.0;
    }

    // Informational
    public String getSourceSSID() { return sourceSsid; }
    public String getDestSSID() { return destSsid; }
}
