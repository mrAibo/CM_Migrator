/*
 * Projekt: CM Migrator 2.2.1.
 *
 * @deprecated Replaced by the unified {@link ReportDeliveryService} pipeline.
 *             Kept only for backward compatibility — delegates to new pipeline.
 */
package com.ibm.ecm.migration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Deprecated
public class EmailNotifier {
    private static final Logger logger = LogManager.getLogger(EmailNotifier.class);

    @Deprecated
    public static void sendReport(MigrationConfig config, String reportPath,
                                   String operationMode, MigrationStats stats) {
        logger.warn("EmailNotifier.sendReport() is deprecated — delegating to unified pipeline.");
        try {
            ReportDataCollector collector = new ReportDataCollector(stats, config);
            UnifiedReport report = collector.collect();
            ReportDeliveryService.deliver(report, config);
        } catch (Exception e) {
            logger.error("Delegation failed: {}", e.getMessage(), e);
        }
    }
}
