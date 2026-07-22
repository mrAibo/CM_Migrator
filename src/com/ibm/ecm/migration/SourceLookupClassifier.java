package com.ibm.ecm.migration;

import java.util.Locale;

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
        int depth = 0;

        while (current != null && depth < 16) {
            String message = normalize(current.getMessage());
            if (isConfirmedObjectNotFound(message, normalizedPid)) {
                return SourceLookupStatus.NOT_FOUND;
            }
            current = current.getCause();
            depth++;
        }

        return SourceLookupStatus.ERROR;
    }

    static boolean isConfirmedObjectNotFound(String normalizedMessage, String normalizedPid) {
        if (normalizedMessage.isEmpty()) {
            return false;
        }

        if (containsObjectNotFoundPhrase(normalizedMessage)) {
            return true;
        }

        return normalizedMessage.contains("dkc_unknown")
                && !normalizedPid.isEmpty()
                && normalizedMessage.contains(normalizedPid);
    }

    private static boolean containsObjectNotFoundPhrase(String message) {
        return message.contains("item not found")
                || message.contains("object not found")
                || message.contains("document not found")
                || message.contains("item does not exist")
                || message.contains("object does not exist")
                || message.contains("document does not exist")
                || message.contains("item no longer exists")
                || message.contains("object no longer exists")
                || message.contains("document no longer exists");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
