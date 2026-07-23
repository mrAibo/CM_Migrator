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
public class ProtocolReportGenerator {

    private static final Logger logger = LogManager.getLogger(ProtocolReportGenerator.class);
    private final MigrationConfig config;

    @Deprecated
    public ProtocolReportGenerator(MigrationConfig config) {
        this.config = config;
    }

    @Deprecated
    public ProtocolReportGenerator(MigrationConfig config, String dbBaseDir) {
        this.config = config;
    }

    @Deprecated
    public static void generateUnifiedReport(MigrationConfig config, MigrationStats stats) {
        logger.warn("ProtocolReportGenerator.generateUnifiedReport() is deprecated — delegating to unified pipeline.");
        ReportDataCollector collector = new ReportDataCollector(stats, config);
        UnifiedReport report = collector.collect();
        ReportDeliveryService.deliver(report, config);
    }

    @Deprecated
    public void generateAllMigrationReports() {
        logger.warn("ProtocolReportGenerator.generateAllMigrationReports() is deprecated — use unified pipeline.");
    }

    @Deprecated
    public void generateAllVerificationReports() {
        logger.warn("ProtocolReportGenerator.generateAllVerificationReports() is deprecated — use unified pipeline.");
    }

    @Deprecated
    public void generateAllCombinedReports() {
        logger.warn("ProtocolReportGenerator.generateAllCombinedReports() is deprecated — use unified pipeline.");
    }
}
