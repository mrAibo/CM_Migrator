package com.ibm.ecm.migration;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Conservative classifier for IBM CM source-item lookup failures.
 *
 * <p>False negatives are acceptable here: an unrecognized not-found response is
 * treated as {@link SourceLookupStatus#ERROR}. False positives are not acceptable,
 * because NOT_FOUND may authorize deletion at the destination.</p>
 */
public final class SourceLookupClassifier {
    private SourceLookupClassifier() {
    }

    public static SourceLookupStatus fromFailure(Throwable failure, String sourcePid) {
        if (failure == null) {
            return SourceLookupStatus.ERROR;
        }

        String normalizedPid = normalize(sourcePid);
        Throwable current = failure;
        boolean confirmedNotFound = false;
        int depth = 0;

        while (current != null && depth < 16) {
            String message = normalize(current.getMessage());
            if (!message.isEmpty()) {
                if (!isConfirmedObjectNotFound(message, normalizedPid)) {
                    return SourceLookupStatus.ERROR;
                }
                confirmedNotFound = true;
            }
            current = current.getCause();
            depth++;
        }

        if (current != null) {
            return SourceLookupStatus.ERROR;
        }
        return confirmedNotFound ? SourceLookupStatus.NOT_FOUND : SourceLookupStatus.ERROR;
    }

    static boolean isConfirmedObjectNotFound(String normalizedMessage, String normalizedPid) {
        if (normalizedMessage.isEmpty() || normalizedPid.isEmpty()) {
            return false;
        }

        String pid = Pattern.quote(normalizedPid);
        String separator = "(?:\\s*[:=-]\\s*|\\s+)";
        String objectNotFound = "(?:item|object|document)\\s+"
                + "(?:not\\s+found|does\\s+not\\s+exist|no\\s+longer\\s+exists)"
                + separator + pid;
        String dkcUnknown = "dkc_unknown\\s+while\\s+retrieving\\s+" + pid;
        return Pattern.matches("(?:" + objectNotFound + "|" + dkcUnknown + ")\\s*[.!]?",
                normalizedMessage);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
