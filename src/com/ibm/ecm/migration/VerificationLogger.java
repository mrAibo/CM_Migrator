/*
 * Projekt: CM Migrator 2.2.1.
 * @Author: Aleksej Voronin, Sven Lindt
 * @Date:   26.01.2026
 */
package com.ibm.ecm.migration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.h2.jdbcx.JdbcConnectionPool;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class VerificationLogger implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger(VerificationLogger.class);

    private static final String VERIFY_TABLE = "VERIFICATION_LOG";

    // Performance Optimierung
    private static final int QUEUE_CAPACITY = 200_000;
    private static final int BATCH_FLUSH_SIZE = 5000;
    private static final long FLUSH_INTERVAL_MS = 1500;

    private final BlockingQueue<LogEntry> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final ConcurrentHashMap<String, WriterContext> ctxByJdbcUrl = new ConcurrentHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread writerThread;

    private static final class LogEntry {
        final String jdbcUrl;
        final String itemId;
        final String status;
        final String sourceHash;
        final String destHash;
        final String message;

        LogEntry(String jdbcUrl, String itemId, String status, String sourceHash, String destHash, String message) {
            this.jdbcUrl = jdbcUrl;
            this.itemId = itemId;
            this.status = status;
            this.sourceHash = sourceHash;
            this.destHash = destHash;
            this.message = message;
        }
    }

    private static final class WriterContext {
        final String jdbcUrl;
        final JdbcConnectionPool pool;

        Connection conn;
        PreparedStatement ps;

        final ArrayList<LogEntry> batch = new ArrayList<>(BATCH_FLUSH_SIZE);

        WriterContext(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
            this.pool = JdbcConnectionPool.create(jdbcUrl, "sa", "");
            this.pool.setMaxConnections(1); // default ist 10
        }

        void init() throws SQLException {
            if (conn != null && !conn.isClosed()) return;

            conn = pool.getConnection();
            conn.setAutoCommit(false);

            ensureTable(conn);

            String sql = "MERGE INTO " + VERIFY_TABLE + " (ITEM_ID, STATUS, SOURCE_HASH, DEST_HASH, VERIFIED_AT, MESSAGE) " +
                         "KEY(ITEM_ID) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, ?)";
            ps = conn.prepareStatement(sql);
        }

        void closeQuiet() {
            try { if (ps != null) ps.close(); } catch (Exception ignore) {}
            try { if (conn != null) conn.close(); } catch (Exception ignore) {}
            ps = null;
            conn = null;
        }

        void dispose() {
            closeQuiet();
            try { pool.dispose(); } catch (Exception ignore) {}
        }

        private static void ensureTable(Connection conn) throws SQLException {
            if (MigrationJournal.isTablePresent(conn, VERIFY_TABLE)) return;

            String ddl =
                    "CREATE TABLE " + VERIFY_TABLE + " (" +
                    "ITEM_ID VARCHAR(255) PRIMARY KEY, " +
                    "STATUS VARCHAR(50), " +
                    "SOURCE_HASH VARCHAR(64), " +
                    "DEST_HASH VARCHAR(64), " +
                    "VERIFIED_AT TIMESTAMP, " +
                    "MESSAGE VARCHAR(1000)" +
                    ")";
            try (Statement st = conn.createStatement()) {
                st.execute(ddl);
            }
        }
    }

    public VerificationLogger() {
        writerThread = new Thread(this::writerLoop, "verification-log-writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    public void log(String jdbcUrl, String itemId, String status, String sourceHash, String destHash, String message) {
        if (!running.get()) return;

        if (message != null && message.length() > 1000) {
            message = message.substring(0, 997) + "...";
        }

        LogEntry e = new LogEntry(jdbcUrl, itemId, status, sourceHash, destHash, message);

        try {
            if (!queue.offer(e, 200, TimeUnit.MILLISECONDS)) {
                queue.put(e);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void writerLoop() {
        long lastFlush = System.currentTimeMillis();

        while (running.get() || !queue.isEmpty()) {
            try {
                LogEntry e = queue.poll(250, TimeUnit.MILLISECONDS);
                if (e != null) {
                    WriterContext ctx = ctxByJdbcUrl.computeIfAbsent(e.jdbcUrl, WriterContext::new);
                    ctx.batch.add(e);

                    if (ctx.batch.size() >= BATCH_FLUSH_SIZE) {
                        flushContext(ctx);
                    }
                }

                long now = System.currentTimeMillis();
                if ((now - lastFlush) >= FLUSH_INTERVAL_MS) {
                    flushAll();
                    lastFlush = now;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                logger.warn("VerificationLogger loop error: {}", ex.getMessage(), ex);
            }
        }

        try { flushAll(); } catch (Exception ex) { logger.warn("Final flush failed: {}", ex.getMessage(), ex); }
    }

    private void flushAll() {
        for (WriterContext ctx : ctxByJdbcUrl.values()) {
            if (!ctx.batch.isEmpty()) flushContext(ctx);
        }
    }

    private void flushContext(WriterContext ctx) {
        if (ctx.batch.isEmpty()) return;

        List<LogEntry> toWrite = new ArrayList<>(ctx.batch);
        ctx.batch.clear();

        int attempt = 0;
        while (attempt < 3) {
            attempt++;
            try {
                ctx.init();

                for (LogEntry e : toWrite) {
                    ctx.ps.setString(1, e.itemId);
                    ctx.ps.setString(2, e.status);
                    ctx.ps.setString(3, e.sourceHash);
                    ctx.ps.setString(4, e.destHash);
                    ctx.ps.setString(5, e.message);
                    ctx.ps.addBatch();
                }

                ctx.ps.executeBatch();
                ctx.conn.commit();
                return;

            } catch (SQLException sqlEx) {
                logger.warn("Flush failed (attempt {}): {} / {}", attempt, ctx.jdbcUrl, sqlEx.getMessage());
                try { if (ctx.conn != null) ctx.conn.rollback(); } catch (Exception ignore) {}
                ctx.closeQuiet();
                try { Thread.sleep(200L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            } catch (Exception ex) {
                logger.warn("Flush failed (attempt {}): {} / {}", attempt, ctx.jdbcUrl, ex.getMessage(), ex);
                ctx.closeQuiet();
                try { Thread.sleep(200L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }

        for (LogEntry e : toWrite) {
            if (!queue.offer(e)) {
                logger.error("Dropping verification log entry due to full queue: {}", e.itemId);
            }
        }
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) return;

        try { writerThread.join(60_000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        for (WriterContext ctx : ctxByJdbcUrl.values()) {
            try { ctx.dispose(); } catch (Exception ignore) {}
        }
        ctxByJdbcUrl.clear();
    }
}
