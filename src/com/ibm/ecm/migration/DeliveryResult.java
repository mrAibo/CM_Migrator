package com.ibm.ecm.migration;

import java.util.Objects;

/**
 * Result of a ReportDeliveryService.deliver() call.
 */
public final class DeliveryResult {
    private final boolean sent;
    private final boolean attachmentsIncluded;
    private final String transport;   // "mutt", "mailx", "none"
    private final String errorMessage; // null if ok
    private final String reportPath;   // absolute path to report.html
    private final boolean localArtifactWritten; // report.html exists on disk

    public DeliveryResult(boolean sent, boolean attachmentsIncluded,
                          String transport, String errorMessage, String reportPath,
                          boolean localArtifactWritten) {
        this.sent = sent;
        this.attachmentsIncluded = attachmentsIncluded;
        this.transport = transport;
        this.errorMessage = errorMessage;
        this.reportPath = reportPath;
        this.localArtifactWritten = localArtifactWritten;
    }

    public boolean sent()               { return sent; }
    public boolean attachmentsIncluded() { return attachmentsIncluded; }
    public String transport()            { return transport; }
    public String errorMessage()         { return errorMessage; }
    public String reportPath()           { return reportPath; }
    public boolean localArtifactWritten(){ return localArtifactWritten; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeliveryResult)) return false;
        DeliveryResult that = (DeliveryResult) o;
        return sent == that.sent
            && attachmentsIncluded == that.attachmentsIncluded
            && Objects.equals(transport, that.transport)
            && Objects.equals(errorMessage, that.errorMessage)
            && Objects.equals(reportPath, that.reportPath)
            && localArtifactWritten == that.localArtifactWritten;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sent, attachmentsIncluded, transport, errorMessage, reportPath, localArtifactWritten);
    }

    @Override
    public String toString() {
        return "DeliveryResult[sent=" + sent
            + ", attachmentsIncluded=" + attachmentsIncluded
            + ", transport=" + transport
            + ", errorMessage=" + errorMessage
            + ", localArtifactWritten=" + localArtifactWritten
            + ", reportPath=" + reportPath + "]";
    }
}
