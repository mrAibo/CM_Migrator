package com.ibm.ecm.migration;

/**
 * Result of a ReportDeliveryService.deliver() call.
 */
public record DeliveryResult(
    boolean sent,
    boolean attachmentsIncluded,
    String transport,   // "mutt", "mailx", "none"
    String errorMessage, // null if ok
    String reportPath    // absolute path to report.html
) {}
