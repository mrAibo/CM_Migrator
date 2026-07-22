/*
 * Projekt: CM Migrator 2.2.1.
 * @Author: Aleksej Voronin, Sven Lindt
 * @Date:   26.01.2026
 * 
 * JMX-Schnittstelle für die Migrations-Engine.
 */
package com.ibm.ecm.migration;

public interface MigrationMetricsMBean {
    long getProcessedItems();
    long getSuccessItems();
    long getErrorItems();
    long getSkippedItems();
    long getDeletedItems();
    long getTotalItems();
    int getQueueDepth();
    String getAvgRetrieveMs();
    String getAvgCopyMs();
    String getAvgAddMs();
    double getMemoryUsagePercent();
}
