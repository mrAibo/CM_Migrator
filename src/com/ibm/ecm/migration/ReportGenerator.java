/*
 * Projekt: CM Migrator 2.2.1.
 *
 * @deprecated Replaced by the unified {@link ReportDeliveryService} pipeline.
 *             Kept only for backward compatibility — delegates to new pipeline.
 */
package com.ibm.ecm.migration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

@Deprecated
public class ReportGenerator {
    private static final Logger logger = LogManager.getLogger(ReportGenerator.class);

    @Deprecated
    public static void generateMigrationReport(MigrationConfig config, MigrationStats stats,
                                                String operationMode) {
        logger.warn("ReportGenerator.generateMigrationReport() is deprecated — delegating to unified pipeline.");
        try {
            ReportDataCollector collector = new ReportDataCollector(stats, config);
            UnifiedReport report = collector.collect();
            ReportDeliveryService.deliver(report, config);
        } catch (Exception e) {
            logger.error("Delegation failed: {}", e.getMessage(), e);
        }
    }

    @Deprecated
    public static void generateVerificationReport(MigrationConfig config, MigrationStats stats,
                                                   Map<String, int[]> verifierResults) {
        logger.warn("ReportGenerator.generateVerificationReport() is deprecated — delegating to unified pipeline.");
        try {
            ReportDataCollector collector = new ReportDataCollector(stats, config);
            UnifiedReport report = collector.collect();
            ReportDeliveryService.deliver(report, config);
        } catch (Exception e) {
            logger.error("Delegation failed: {}", e.getMessage(), e);
        }
    }
}
