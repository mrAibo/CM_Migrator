package com.ibm.ecm.migration;

/** A terminal run outcome that callers map without terminating library/WebGUI code. */
public final class RunTerminationException extends Exception {
    public enum Reason {
        FAILED(1, "FAILED"),
        POLICY(2, "POLICY_REFUSED"),
        TIMEOUT(124, "TIMED_OUT"),
        INTERRUPTED(130, "INTERRUPTED");

        private final int exitCode;
        private final String webStatus;

        Reason(int exitCode, String webStatus) {
            this.exitCode = exitCode;
            this.webStatus = webStatus;
        }
    }

    private final Reason reason;
    private final boolean terminationConfirmed;

    public RunTerminationException(Reason reason, String message,
                                   boolean terminationConfirmed, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.terminationConfirmed = terminationConfirmed;
    }

    public Reason getReason() {
        return reason;
    }

    public int getExitCode() {
        return reason.exitCode;
    }

    public String getWebStatus() {
        return reason.webStatus;
    }

    public boolean isTerminationConfirmed() {
        return terminationConfirmed;
    }
}
