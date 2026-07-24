/*
 * Projekt: CM Migrator 2.2.1.
 *
 * Thread-safe rate / ETA / stall tracker.
 * Console thread is the sole writer (calls update() every 1s).
 * All state consumed by readers lives inside the immutable Sample,
 * published via volatile write — no cross-field reads.
 */
package com.ibm.ecm.migration;

public final class RateTracker {

    public static final class Sample {
        public final long    processed;
        public final long    total;
        public final long    elapsedMs;
        public final double  currentRate;
        public final double  averageRate;
        public final String  eta;
        public final long    lastProgressMs;  // wall-clock age since last change (stale in getLatest snapshots)
        public final boolean isStreaming;
        public final long    lastChangedAtMs; // when processed last advanced

        private Sample(long processed, long total, long elapsedMs,
                       double currentRate, double averageRate, String eta,
                       long lastProgressMs, boolean isStreaming,
                       long lastChangedAtMs) {
            this.processed       = processed;
            this.total           = total;
            this.elapsedMs       = elapsedMs;
            this.currentRate     = currentRate;
            this.averageRate     = averageRate;
            this.eta             = eta;
            this.lastProgressMs  = lastProgressMs;
            this.isStreaming     = isStreaming;
            this.lastChangedAtMs = lastChangedAtMs;
        }
    }

    // ── Sole writer state (only update() touches these) ──
    private final long startTimeMs;
    private long prevProcessed  = -1L;
    private long prevTimeMs     = 0L;
    private long lastProcessed  = -1L;
    private long lastChangedMs;         // written by update(), published in Sample

    // ── Thread-safe publication ──
    private volatile Sample latest;

    public RateTracker(long startTimeMs) {
        this.startTimeMs   = startTimeMs;
        this.lastChangedMs = startTimeMs;
    }

    // ── Reader API ──

    /**
     * Return a self-consistent snapshot.  All fields are derived from the
     * single volatile read of {@link #latest} — no cross-field reads.
     * @param nowMs  current wall-clock time; used to compute a fresh
     *               lastProgressMs even when no new update() has occurred.
     */
    public Sample getLatest(long nowMs) {
        Sample s = latest;
        if (s == null) {
            long elapsed = Math.max(0, nowMs - startTimeMs);
            long stall   = Math.max(0, nowMs - startTimeMs);
            return new Sample(0, 0, elapsed, 0.0, 0.0, "--:--", stall, true, startTimeMs);
        }
        long elapsed = Math.max(0, nowMs - startTimeMs);
        long stall   = Math.max(0, nowMs - s.lastChangedAtMs);
        return new Sample(s.processed, s.total, elapsed,
                          s.currentRate, s.averageRate, s.eta,
                          stall, s.isStreaming, s.lastChangedAtMs);
    }

    // ── Writer API (console thread only) ──

    public Sample update(long processed, long total, long nowMs) {
        long elapsed = Math.max(0, nowMs - startTimeMs);

        double avgRate = 0.0;
        if (elapsed > 0) avgRate = (double) processed / (elapsed / 1000.0);

        double curRate = avgRate;
        if (prevProcessed >= 0L && nowMs > prevTimeMs) {
            long delta   = Math.max(0L, processed - prevProcessed);
            long deltaMs = nowMs - prevTimeMs;
            if (deltaMs > 0) curRate = (delta * 1000.0) / deltaMs;
        }

        String eta = "--:--";
        if (total > 0 && processed > 0 && avgRate > 0) {
            long remainingSec = (long) ((total - processed) / avgRate);
            eta = formatDuration(remainingSec * 1000L);
        }

        if (processed != lastProcessed) {
            lastProcessed  = processed;
            lastChangedMs = nowMs;
        }
        long stall     = nowMs - lastChangedMs;
        boolean streaming = total <= 0;

        prevProcessed = processed;
        prevTimeMs    = nowMs;

        Sample s = new Sample(processed, total, elapsed,
                              curRate, avgRate, eta,
                              stall, streaming, lastChangedMs);
        latest = s;
        return s;
    }

    public static String formatDuration(long ms) {
        if (ms < 0) return "--:--";
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", h, m, sec);
    }
}
