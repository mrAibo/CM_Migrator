/*
 * Projekt: CM Migrator 2.2.1.
 * @Author: Aleksej Voronin, Sven Lindt
 * @Date:   26.01.2026
 */
package com.ibm.ecm.migration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Verbindungspool für CMConnection.
 * Phase 1: konfigurierbare Größen (SOURCE_POOL_SIZE / DEST_POOL_SIZE).
 *
 * P2 Metrics added:
 * - borrow-wait-time (avg) for source/dest
 * - refill attempts/success/failures
 * - reconnect attempts/success/failures
 *
 * Metriken werden über getGlobalMetricsSnapshot() bereitgestellt, um Änderungen an der Connection in Main/Monitor zu vermeiden.
 */
public class CMConnectionPool implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger(CMConnectionPool.class);

    private final BlockingQueue<CMConnection> sourcePool;
    private final BlockingQueue<CMConnection> destPool;

    // REFILL EXECUTOR: Einzelner Thread zur sequenziellen Bearbeitung von Wiederverbindungen.
    // Verhindert eine Thread-Explosion während einer Massenrotation.
    private final java.util.concurrent.ExecutorService refillExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();

    private final MigrationConfig config;
    private final int sourcePoolSize;
    private final int destPoolSize;
    private volatile boolean closed = false; // Graceful shutdown flag

    // --- Globale Metrik-Registrierung (einzelne Pool-Instanz in dieser App) ---
    private static volatile CMConnectionPool GLOBAL_INSTANCE;

    private static final class PoolMetrics {
        // Borrow wait time
        final AtomicLong sourceBorrowCount = new AtomicLong(0);
        final AtomicLong sourceBorrowWaitNanos = new AtomicLong(0);

        final AtomicLong destBorrowCount = new AtomicLong(0);
        final AtomicLong destBorrowWaitNanos = new AtomicLong(0);

        // Refill
        final AtomicLong refillAttempts = new AtomicLong(0);
        final AtomicLong refillSuccess = new AtomicLong(0);
        final AtomicLong refillFailures = new AtomicLong(0);

        // Reconnect
        final AtomicLong reconnectAttempts = new AtomicLong(0);
        final AtomicLong reconnectSuccess = new AtomicLong(0);
        final AtomicLong reconnectFailures = new AtomicLong(0);
    }

    private final PoolMetrics metrics = new PoolMetrics();

    public static final class PoolMetricsSnapshot {
        private final long sourceBorrowCount;
        private final long sourceBorrowWaitNanos;

        private final long destBorrowCount;
        private final long destBorrowWaitNanos;

        private final long refillAttempts;
        private final long refillSuccess;
        private final long refillFailures;

        private final long reconnectAttempts;
        private final long reconnectSuccess;
        private final long reconnectFailures;

        PoolMetricsSnapshot(PoolMetrics m) {
            this.sourceBorrowCount = m.sourceBorrowCount.get();
            this.sourceBorrowWaitNanos = m.sourceBorrowWaitNanos.get();

            this.destBorrowCount = m.destBorrowCount.get();
            this.destBorrowWaitNanos = m.destBorrowWaitNanos.get();

            this.refillAttempts = m.refillAttempts.get();
            this.refillSuccess = m.refillSuccess.get();
            this.refillFailures = m.refillFailures.get();

            this.reconnectAttempts = m.reconnectAttempts.get();
            this.reconnectSuccess = m.reconnectSuccess.get();
            this.reconnectFailures = m.reconnectFailures.get();
        }

        public double getAvgBorrowWaitMsSource() {
            if (sourceBorrowCount <= 0) return 0.0;
            return (sourceBorrowWaitNanos / 1_000_000.0) / sourceBorrowCount;
        }

        public double getAvgBorrowWaitMsDest() {
            if (destBorrowCount <= 0) return 0.0;
            return (destBorrowWaitNanos / 1_000_000.0) / destBorrowCount;
        }

        public long getRefillAttempts() { return refillAttempts; }
        public long getRefillSuccess() { return refillSuccess; }
        public long getRefillFailures() { return refillFailures; }

        public long getReconnectAttempts() { return reconnectAttempts; }
        public long getReconnectSuccess() { return reconnectSuccess; }
        public long getReconnectFailures() { return reconnectFailures; }

        public long getSourceBorrowCount() { return sourceBorrowCount; }
        public long getDestBorrowCount() { return destBorrowCount; }
    }

    public static PoolMetricsSnapshot getGlobalMetricsSnapshot() {
        CMConnectionPool p = GLOBAL_INSTANCE;
        if (p == null) return null;
        return p.getMetricsSnapshot();
    }

    public PoolMetricsSnapshot getMetricsSnapshot() {
        return new PoolMetricsSnapshot(metrics);
    }

    public CMConnectionPool(MigrationConfig config) {
        this.config = config;

        boolean isDeleteMode = "DELETE".equals(config.getOperationMode());

        this.sourcePoolSize = config.getSourcePoolSize();
        this.destPoolSize = isDeleteMode ? 0 : config.getDestPoolSize();

        this.sourcePool = new LinkedBlockingQueue<>(Math.max(1, sourcePoolSize));
        this.destPool = new LinkedBlockingQueue<>(Math.max(1, destPoolSize));

        GLOBAL_INSTANCE = this;
    }

    // --- Factory-Methoden für die sichere Erstellung ---

    private CMConnection createSourceConnection() {
        return new CMConnection(config.getSourceSSID(), config.getSourceUser(), config.getSourcePassword(), CMConnection.Role.SOURCE);
    }

    private CMConnection createDestConnection() {
        return new CMConnection(config.getDestSSID(), config.getDestUser(), config.getDestPassword(), CMConnection.Role.DEST);
    }

    public void init() throws Exception {
        boolean isDeleteMode = "DELETE".equals(config.getOperationMode());

        logger.info("Initializing Connection Pools: sourcePoolSize={}, destPoolSize={}, mode={}",
                sourcePoolSize, destPoolSize, config.getOperationMode());

        // Source pool
        for (int i = 0; i < sourcePoolSize; i++) {
            CMConnection c = createSourceConnection();
            c.connect();
            sourcePool.offer(c);
        }

        // Dest pool
        if (!isDeleteMode) {
            for (int i = 0; i < destPoolSize; i++) {
                CMConnection c = createDestConnection();
                c.connect();
                destPool.offer(c);
            }
        }

        logger.info("Connection Pools initialized.");
    }

    public CMConnection borrowSource() throws InterruptedException, Exception {
        long t0 = System.nanoTime();
        CMConnection conn = sourcePool.poll(config.getPoolBorrowTimeoutMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
        long waited = System.nanoTime() - t0;

        metrics.sourceBorrowCount.incrementAndGet();
        metrics.sourceBorrowWaitNanos.addAndGet(waited);

        // Round 2: Schutz gegen NPE wenn Pool im Borrow-Timeout leer war.
        // Emergency-Verbindung erzeugen, markieren und zurückliefern.
        if (conn == null) {
            logger.warn("Source pool empty within borrow timeout ({} ms). Creating emergency connection.",
                    config.getPoolBorrowTimeoutMs());
            asyncRefill(sourcePool, this::createSourceConnection, "source");
            CMConnection emergency = createSourceConnection();
            emergency.connect();
            emergency.markEmergency();
            return emergency;
        }

        // VALIDIERUNG: Sicherstellen, dass wir eine echte SOURCE-Verbindung und die richtige SSID haben.
        String expectedSsid = config.getSourceSSID();
        if (conn.getRole() != CMConnection.Role.SOURCE || !expectedSsid.equals(conn.getSSID())) {
            logger.error("CRITICAL: Pool contamination! Got connection with Role={} SSID={} (Expected SOURCE SSID={}). Discarding.",
                    conn.getRole(), conn.getSSID(), expectedSsid);
            safeClose(conn);

            // DEADLOCK-BEHEBUNG: Asynchrone Nachfüllung, um das Blockieren des Worker-Threads zu vermeiden
            asyncRefill(sourcePool, this::createSourceConnection, "source");

            // Retry: Nicht blockierende Abfrage mit Zeitüberschreitung und Fallback
            t0 = System.nanoTime();
            conn = sourcePool.poll(config.getPoolMaxWaitTimeMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
            waited = System.nanoTime() - t0;

            if (conn == null) {
                logger.warn("Source pool empty after retry, creating emergency fallback connection");
                conn = createSourceConnection();
                conn.connect();
                conn.markEmergency();
            }

            metrics.sourceBorrowCount.incrementAndGet();
            metrics.sourceBorrowWaitNanos.addAndGet(waited);
        }
        return conn;
    }

    public CMConnection borrowDest() throws InterruptedException, Exception {
        long t0 = System.nanoTime();
        CMConnection conn = destPool.poll(config.getPoolBorrowTimeoutMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
        long waited = System.nanoTime() - t0;

        metrics.destBorrowCount.incrementAndGet();
        metrics.destBorrowWaitNanos.addAndGet(waited);

        // Round 2: Schutz gegen NPE wenn Pool im Borrow-Timeout leer war.
        if (conn == null) {
            logger.warn("Dest pool empty within borrow timeout ({} ms). Creating emergency connection.",
                    config.getPoolBorrowTimeoutMs());
            asyncRefill(destPool, this::createDestConnection, "dest");
            CMConnection emergency = createDestConnection();
            emergency.connect();
            emergency.markEmergency();
            return emergency;
        }

        // VALIDATION: Sicherstellen, dass wir eine echte DEST-Verbindung und die richtige SSID haben
        String expectedSsid = config.getDestSSID();
        if (conn.getRole() != CMConnection.Role.DEST || !expectedSsid.equals(conn.getSSID())) {
            logger.error("CRITICAL: Pool contamination! Got connection with Role={} SSID={} (Expected DEST SSID={}). Discarding.",
                    conn.getRole(), conn.getSSID(), expectedSsid);
            safeClose(conn);

            // DEADLOCK FIX: Asynchrone Nachfüllung, um eine Blockierung des Worker-Threads zu vermeiden
            asyncRefill(destPool, this::createDestConnection, "dest");

            // Retry: nicht blockierende Abfrage mit zeitlicher Begrenzung und Fallback
            t0 = System.nanoTime();
            conn = destPool.poll(config.getPoolMaxWaitTimeMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
            waited = System.nanoTime() - t0;

            if (conn == null) {
                logger.warn("Dest pool empty after retry, creating emergency fallback connection");
                conn = createDestConnection();
                conn.connect();
                conn.markEmergency();
            }

            metrics.destBorrowCount.incrementAndGet();
            metrics.destBorrowWaitNanos.addAndGet(waited);
        }
        return conn;
    }

    public void returnSource(CMConnection conn) {
        returnConnection(conn, sourcePool, CMConnection.Role.SOURCE, this::createSourceConnection, "source");
    }

    public void returnDest(CMConnection conn) {
        returnConnection(conn, destPool, CMConnection.Role.DEST, this::createDestConnection, "dest");
    }

    /**
     * Generische Verbindungsrückgabelogik zur Vermeidung von Code-Duplikaten.
     * Behandelt Rollenvalidierung, Verfallsprüfungen, Wiederverbindungen und asynchrone Nachfüllungen.
     */
    private void returnConnection(
            CMConnection conn,
            BlockingQueue<CMConnection> pool,
            CMConnection.Role expectedRole,
            java.util.function.Supplier<CMConnection> connectionFactory,
            String poolName
    ) {
        if (conn == null) return;

        // Round 2: Emergency-Verbindungen werden NIE in den Pool zurückgegeben.
        // Sie wurden außerhalb der konfigurierten Pool-Kapazität erzeugt; ein offer()
        // würde entweder die Kapazität überschreiten (falls unbounded) oder fehlschlagen.
        if (conn.isEmergency()) {
            logger.debug("Closing emergency {} connection on return (not pooled).", poolName);
            safeClose(conn);
            return;
        }

        // Sicherheitsüberprüfung: Rollen- und SSID-Validierung
        String expectedSsid = (expectedRole == CMConnection.Role.SOURCE) ? config.getSourceSSID() : config.getDestSSID();
        if (conn.getRole() != expectedRole || !expectedSsid.equals(conn.getSSID())) {
            logger.error("CRITICAL: Attempted to return invalid connection to {} pool! Role={} SSID={} (Expected SSID={}). Discarding.",
                    poolName.toUpperCase(), conn.getRole(), conn.getSSID(), expectedSsid);
            safeClose(conn);
            asyncRefill(pool, connectionFactory, poolName);
            return;
        }

        // AGING: Aktive Rotation für träge Verbindungen
        if (conn.isStale()) {
            logger.info("Rotating stale {} connection (Age/Usage limit reached)", poolName);
            safeClose(conn);
            asyncRefill(pool, connectionFactory, poolName);
            return;
        }

        // Bei Unterbrechung der Verbindung erneut verbinden
        if (!conn.isConnected()) {
            metrics.reconnectAttempts.incrementAndGet();
            logger.warn("Returned {} connection is closed. Reconnecting...", poolName);
            try {
                conn.connect();
                metrics.reconnectSuccess.incrementAndGet();
            } catch (Exception e) {
                metrics.reconnectFailures.incrementAndGet();
                logger.error("Reconnect {} failed. Discarding connection.", poolName, e);
                safeClose(conn);
                asyncRefill(pool, connectionFactory, poolName);
                return;
            }
        }

        // Zurück zum Pool. Round 2: offer()-Returnwert auswerten.
        // Wenn der Pool unerwartet voll ist (z.B. nach Refill-Race), Verbindung schließen
        // statt sie stillschweigend zu verwerfen — sonst leakt der SDK-Datastore.
        try {
            if (!pool.offer(conn)) {
                logger.warn("{} pool full on return — closing connection to avoid SDK leak.", poolName);
                safeClose(conn);
            }
        } catch (Exception e) {
            safeClose(conn);
        }
    }

    /**
     * Füllt den Pool asynchron mit einer frischen Verbindung wieder auf.
     * GUARD: Wird nicht übermittelt, wenn der Pool bereits geschlossen ist (verhindert Fehler nach Abschluss).
     */
    private void asyncRefill(BlockingQueue<CMConnection> pool,
                             java.util.function.Supplier<CMConnection> connectionFactory,
                             String poolName) {
        if (closed) {
            logger.debug("Skipping async refill for {} pool - pool is closed", poolName);
            return;
        }
        metrics.refillAttempts.incrementAndGet();

        refillExecutor.submit(() -> {
            if (closed || Thread.currentThread().isInterrupted()) return;

            try {
                CMConnection newConn = connectionFactory.get();
                if (closed || Thread.currentThread().isInterrupted()) {
                    newConn.close();
                    return;
                }

                newConn.connect();

                if (!closed && !Thread.currentThread().isInterrupted()) {
                    pool.put(newConn);
                    metrics.refillSuccess.incrementAndGet();
                } else {
                    newConn.close();
                }
            } catch (Exception e) {
                metrics.refillFailures.incrementAndGet();
                if (!closed) logger.error("Async refill for {} pool failed", poolName, e);
            }
        });
    }

    private void safeClose(CMConnection conn) {
        try {
            conn.close();
        } catch (Exception ignore) {
        }
    }

    /**
     * Signal shutdown intent vor close().
     * Verhindert, dass neue asynchrone Nachfüllungen geplant werden.
     * Call aus Main.java vor der endgültigen Berichterstellung auf.
     */
    public void signalShutdown() {
        closed = true;
        logger.debug("Connection pool shutdown signaled");
    }

    @Override
    public void close() {
        closed = true; // Redundant but safe
        logger.info("Closing Connection Pools...");

        refillExecutor.shutdownNow(); // Stop refills

        try {
            refillExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        closeQueue(sourcePool);
        closeQueue(destPool);
    }

    private void closeQueue(BlockingQueue<CMConnection> queue) {
        while (!queue.isEmpty()) {
            CMConnection conn = queue.poll();
            if (conn != null) safeClose(conn);
        }
    }
}
