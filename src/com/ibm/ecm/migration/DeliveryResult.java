package com.ibm.ecm.migration;

/**
 * Result of a ReportDeliveryService.deliver() call.
 */
public final class DeliveryResult {
    public final boolean sent;
    public final boolean attachmentsIncluded;
    public final String transport;   // "mutt", "mailx", "none"
    public final String errorMessage; // null if ok
    public final String reportPath;   // absolute path to report.html

    public DeliveryResult(boolean sent, boolean attachmentsIncluded,
            String transport, String errorMessage, String reportPath) {
        this.sent = sent;
        this.attachmentsIncluded = attachmentsIncluded;
        this.transport = transport;
        this.errorMessage = errorMessage;
        this.reportPath = reportPath;
    }
}
