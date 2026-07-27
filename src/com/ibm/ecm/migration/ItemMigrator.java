/*
 * Projekt: CM Migrator 2.3.0.
 * @Author: Aleksej Voronin, Sven Lindt
 * @Date:   26.01.2026
 */
package com.ibm.ecm.migration;

import com.ibm.mm.sdk.common.DKConstant;
import com.ibm.mm.sdk.common.DKDDO;
import com.ibm.mm.sdk.common.DKChildCollection;
import com.ibm.mm.sdk.common.DKLobICM;
import com.ibm.mm.sdk.common.DKNVPair;
import com.ibm.mm.sdk.common.DKParts;
import com.ibm.mm.sdk.common.DKPidICM;
import com.ibm.mm.sdk.common.DKRetrieveOptionsICM;
import com.ibm.mm.sdk.common.dkIterator;
import com.ibm.mm.sdk.server.DKDatastoreICM;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ItemMigrator {
    private static final Logger logger = LogManager.getLogger(ItemMigrator.class);

    private static final long SLOW_ITEM_WARN_MS = Long.getLong("cm.migrator.slowItemWarnMs", 60000L);
    private static final long SLOW_PHASE_WARN_MS = Long.getLong("cm.migrator.slowPhaseWarnMs", 30000L);
    private static final long SLOW_PART_WARN_MS = Long.getLong("cm.migrator.slowPartWarnMs", 30000L);

    private final CMConnectionPool pool;
    private final Set<String> ignoredAttributes;

    private final ThreadLocal<Exception> lastError = new ThreadLocal<>();

    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, AttrInfo>> ATTR_CACHE = new ConcurrentHashMap<>();

    // Round 13B: ThreadLocal SHA-256 to avoid per-item allocation + JCE provider lookup.
    // Mirrors Verifier.SHA256_DIGEST. Each migration item starts with digest.reset().
    private static final ThreadLocal<MessageDigest> SHA256_DIGEST = ThreadLocal.withInitial(() -> {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Exception e) { throw new RuntimeException("SHA-256 unavailable", e); }
    });

    private static final class AttrInfo {
        final short id;
        final short type;

        AttrInfo(short id, short type) {
            this.id = id;
            this.type = type;
        }
    }

    public ItemMigrator(CMConnectionPool pool) {
        this(pool, Collections.emptySet());
    }

    ItemMigrator(CMConnectionPool pool, Set<String> ignoredAttributes) {
        this.pool = pool;
        this.ignoredAttributes = Set.copyOf(ignoredAttributes);
    }

    static void clearRunCache() {
        ATTR_CACHE.clear();
    }

    public Exception getLastError() {
        return lastError.get();
    }

    public boolean migrateBatch(List<MigrationItem> batch) {
        lastError.remove();

        CMConnection sourceConn = null;
        CMConnection destConn = null;

        try {
            sourceConn = pool.borrowSource();
            destConn = pool.borrowDest();

            DKDatastoreICM destDs = destConn.getDatastore();
            destDs.startTransaction();

            for (MigrationItem item : batch) {
                if (ShutdownCoordinator.isShuttingDown()) {
                    logger.warn("Migration batch stopped before next item because shutdown was requested.");
                    throw new InterruptedException("Shutdown requested during migration batch");
                }

                ThreadContext.put("itemId", item.getItemId());
                ThreadContext.put("itemType", item.getSourceItemType());
                ThreadContext.put("destType", item.getDestItemType());

                if (!migrateItemInternal(item, sourceConn, destConn)) {
                    throw new Exception("Failed to migrate item " + item.getItemId());
                }
            }

            destDs.commit();
            return true;

        } catch (Exception e) {
            lastError.set(e);

            if (ShutdownCoordinator.isShuttingDown() || e instanceof InterruptedException) {
                logger.warn("Migration batch interrupted by shutdown: {}", e.getMessage());
            } else {
                logger.error("Batch failed: {}", e.getMessage(), e);
            }

            if (destConn != null) {
                try {
                    destConn.getDatastore().rollback();
                } catch (Exception re) {
                    if (ShutdownCoordinator.isShuttingDown()) {
                        logger.warn("Rollback during shutdown failed: {}", re.getMessage());
                    } else {
                        logger.error("Rollback failed", re);
                    }
                }
            }
            return false;

        } finally {
            ThreadContext.clearAll();
            pool.returnSource(sourceConn);
            pool.returnDest(destConn);
        }
    }

    // DIAGNOSTICS: Rolling counters
    private static final AtomicLong totalRetrieveMs = new AtomicLong(0);
    private static final AtomicLong totalCopyMs = new AtomicLong(0);
    private static final AtomicLong totalAddMs = new AtomicLong(0);
    private static final AtomicLong totalItemCount = new AtomicLong(0);
    private static final AtomicLong successfulAttrCopies = new AtomicLong(0);
    private static final AtomicLong failedAttrCopies = new AtomicLong(0);

    public static class PerformanceSnapshot {
        public final long totalItems;
        public final String avgRetrieve;
        public final String avgCopy;
        public final String avgAdd;
        public final long attrSuccess;
        public final long attrFailed;

        public PerformanceSnapshot(long items, String ar, String ac, String aa, long as, long af) {
            this.totalItems = items;
            this.avgRetrieve = ar;
            this.avgCopy = ac;
            this.avgAdd = aa;
            this.attrSuccess = as;
            this.attrFailed = af;
        }
    }

    public static PerformanceSnapshot getPerformanceSnapshot() {
        long count = Math.max(1, totalItemCount.get());
        return new PerformanceSnapshot(
            totalItemCount.get(),
            String.format("%.1f", totalRetrieveMs.get() / (double) count),
            String.format("%.1f", totalCopyMs.get() / (double) count),
            String.format("%.1f", totalAddMs.get() / (double) count),
            successfulAttrCopies.get(),
            failedAttrCopies.get()
        );
    }

    private boolean migrateItemInternal(MigrationItem item, CMConnection sourceConn, CMConnection destConn) throws Exception {
        String pidString = item.getItemId();
        List<File> tempFiles = new ArrayList<>();
        DKDDO sourceItem = null;
        DKDDO destItem = null;

        long tRetrieve = 0, tCopy = 0, tAdd = 0;
        long itemStartMs = System.currentTimeMillis();

        try {
            // PHASE 1: Retrieve from Source
            long t1 = System.currentTimeMillis();
            DKDatastoreICM sourceDs = sourceConn.getDatastore();

            ThreadContext.put("sourcePid", pidString);

            sourceItem = sourceDs.createDDOFromPID(pidString);

            // Parent Retrieve Options: Metadata ONLY (Resources NOT included here)
            DKRetrieveOptionsICM dkOpt = DKRetrieveOptionsICM.createInstance(sourceDs);
            dkOpt.functionVersionLatest(true);
            dkOpt.baseAttributes(true);
            dkOpt.childListOneLevel(true);
            dkOpt.partsList(true);
            dkOpt.partsAttributes(true);
            dkOpt.resourceContent(false); // Important: defer content retrieval

            sourceItem.retrieve(dkOpt.dkNVPair());
            tRetrieve = System.currentTimeMillis() - t1;

            Object sp = sourceItem.getPidObject();
            if (sp instanceof DKPidICM) {
                ThreadContext.put("sourcePid", ((DKPidICM) sp).pidString());
            }

            // PHASE 2: Copy
            long t2 = System.currentTimeMillis();
            DKDatastoreICM destDs = destConn.getDatastore();
            destItem = destDs.createDDO(item.getDestItemType(), (short) DKConstant.DK_CM_DOCUMENT);

            copyAttributes(sourceItem, destItem, item.getDestItemType());
            copyChildComponents(sourceItem, destItem, item.getDestItemType());
            copyParts(sourceItem, destItem, dkOpt, item, tempFiles);

            tCopy = System.currentTimeMillis() - t2;

            if (tCopy >= SLOW_PHASE_WARN_MS) {
                logger.warn("Slow copy: itemId={} sourceType={} destType={} durationMs={}",
                        item.getItemId(), item.getSourceItemType(), item.getDestItemType(), tCopy);
            }

            // PHASE 3: Add
            long t3 = System.currentTimeMillis();
            destItem.add();
            tAdd = System.currentTimeMillis() - t3;

            if (tAdd >= SLOW_PHASE_WARN_MS) {
                logger.warn("Slow add: itemId={} sourceType={} destType={} durationMs={}",
                        item.getItemId(), item.getSourceItemType(), item.getDestItemType(), tAdd);
            }

            Object dp = destItem.getPidObject();
            if (dp instanceof DKPidICM) {
                String pidStr = ((DKPidICM) dp).pidString();
                item.setDestItemId(pidStr);
                ThreadContext.put("destPid", pidStr);
            }

            totalRetrieveMs.addAndGet(tRetrieve);
            totalCopyMs.addAndGet(tCopy);
            totalAddMs.addAndGet(tAdd);
            totalItemCount.incrementAndGet();

            if (sourceConn != null) sourceConn.markUsed();
            if (destConn != null) destConn.markUsed();

            long itemMs = System.currentTimeMillis() - itemStartMs;
            if (itemMs >= SLOW_ITEM_WARN_MS) {
                logger.warn("Slow migration item finished: itemId={} sourceType={} destType={} totalMs={} retrieveMs={} copyMs={} addMs={}",
                        item.getItemId(),
                        item.getSourceItemType(),
                        item.getDestItemType(),
                        itemMs,
                        tRetrieve,
                        tCopy,
                        tAdd);
            }

            return true;
        } finally {
            for (File f : tempFiles) safeDeleteTempFile(f);
            ThreadContext.remove("sourcePid");
            ThreadContext.remove("destPid");
            sourceItem = null;
            destItem = null;
        }
    }

    public boolean migrate(MigrationItem item) {
        return migrateBatch(List.of(item));
    }

    // Delete functionality
    private static final AtomicLong totalDeleteMs = new AtomicLong(0);
    private static final AtomicLong totalDeleteCount = new AtomicLong(0);
    
    private static final AtomicLong deleteAttemptCount = new AtomicLong(0);

    public static class DeletePerformanceSnapshot {
        public final long totalDeleted;
        public final String avgDeleteMs;
        public DeletePerformanceSnapshot(long count, String avg) { this.totalDeleted = count; this.avgDeleteMs = avg; }
    }

    public static DeletePerformanceSnapshot getDeletePerformanceSnapshot() {
        long count = Math.max(1, totalDeleteCount.get());
        return new DeletePerformanceSnapshot(totalDeleteCount.get(), String.format("%.1f", totalDeleteMs.get() / (double) count));
    }

    public boolean deleteBatch(List<MigrationItem> batch, boolean dryRun) {
        lastError.remove();
        try {
            ensureDeleteMayContinue();
        } catch (InterruptedException stopped) {
            lastError.set(stopped);
            return false;
        }
        CMConnection sourceConn = null;
        long batchStartMs = System.currentTimeMillis();
    
        try {
            sourceConn = pool.borrowSource();
            DKDatastoreICM sourceDs = sourceConn.getDatastore();
            if (!dryRun) sourceDs.startTransaction();
    
            for (MigrationItem item : batch) {
                ensureDeleteMayContinue();
                ThreadContext.put("itemId", item.getItemId());
    
                if (!deleteItemInternal(item, sourceConn, dryRun)) {
                    throw new Exception("Failed to delete item " + item.getItemId());
                }
            }
    
            if (!dryRun) {
                ensureDeleteMayContinue();
                sourceDs.commit();
            }
    
            long elapsed = System.currentTimeMillis() - batchStartMs;
            totalDeleteMs.addAndGet(elapsed);
            totalDeleteCount.addAndGet(batch.size());
            return true;
    
        } catch (Exception e) {
            lastError.set(e);
    
            if (ShutdownCoordinator.isShuttingDown() || e instanceof InterruptedException) {
                logger.warn("Delete batch interrupted by shutdown: {}", e.getMessage());
            } else {
                logger.error("Delete Batch failed: {}", e.getMessage(), e);
            }
    
            if (sourceConn != null && !dryRun) {
                try {
                    sourceConn.getDatastore().rollback();
                } catch (Exception re) {
                    if (ShutdownCoordinator.isShuttingDown()) {
                        logger.warn("Rollback during shutdown failed: {}", re.getMessage());
                    } else {
                        logger.error("Rollback failed", re);
                    }
                }
            }
            return false;
    
        } finally {
            ThreadContext.clearAll();
            pool.returnSource(sourceConn);
        }
    }

    private boolean deleteItemInternal(MigrationItem item, CMConnection sourceConn, boolean dryRun) throws Exception {
        String pidString = item.getItemId();
        DKDatastoreICM sourceDs = sourceConn.getDatastore();
        DKDDO sourceItem = sourceDs.createDDOFromPID(pidString);

        long n = deleteAttemptCount.incrementAndGet();

        if (dryRun) {
            if (n % 10000 == 0) {
                logger.info("[DRY-RUN] DELETE progress: attempted={} latestItem={}", n, pidString);
            } else {
                logger.debug("[DRY-RUN] Would DELETE item: {}", pidString);
            }
            sourceConn.markUsed();
            return true;
        }

        if (n % 10000 == 0) {
            logger.info("DELETE progress: attempted={} latestItem={}", n, pidString);
        } else {
            logger.debug("DELETING item: {}", pidString);
        }

        ensureDeleteMayContinue();
        sourceItem.del();
        sourceConn.markUsed();
        sourceItem = null;
        return true;
    }

    public boolean delete(MigrationItem item, boolean dryRun) {
        return deleteBatch(List.of(item), dryRun);
    }

    private static void ensureDeleteMayContinue() throws InterruptedException {
        if (ShutdownCoordinator.isShuttingDown() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Delete stopped by shutdown request");
        }
    }

    private static AttrInfo getAttrInfo(DKDDO dest, String destItemType, String name) throws Exception {
        if (destItemType == null) destItemType = "<null>";
        ConcurrentHashMap<String, AttrInfo> byName = ATTR_CACHE.computeIfAbsent(destItemType, k -> new ConcurrentHashMap<>());
        AttrInfo cached = byName.get(name);
        if (cached != null) return cached;

        short destAttrId = dest.dataId(DKConstant.DK_CM_NAMESPACE_ATTR, name);
        if (destAttrId <= 0) {
            AttrInfo miss = new AttrInfo((short) -1, (short) -1);
            byName.put(name, miss);
            return miss;
        }
        Object typeObj = dest.getDataPropertyByName(destAttrId, "type");
        short type = (typeObj instanceof Number) ? ((Number) typeObj).shortValue() : (short) -1;
        AttrInfo info = new AttrInfo(destAttrId, type);
        byName.put(name, info);
        return info;
    }

    private void copyAttributes(DKDDO source, DKDDO dest, String destItemType) throws Exception {
        short dataCount = source.dataCount();
        for (short i = 1; i <= dataCount; i++) {
            String name = source.getDataName(i);
            Object value = source.getData(i);
            if (name == null || name.startsWith("SYS") || name.equals(DKConstant.DK_CM_DKPARTS) || value instanceof DKChildCollection || value == null) continue;
            if (ignoredAttributes.contains(name)) continue;

            AttrInfo info = getAttrInfo(dest, destItemType, name);
            if (info.id <= 0) {
                throw new PermanentMigrationException(
                        "Destination attribute is missing: " + destItemType + "." + name);
            }

            try {
                setDestAttrTyped(dest, info.id, info.type, value);
                successfulAttrCopies.incrementAndGet();
            } catch (Exception ex) {
                failedAttrCopies.incrementAndGet();
                throw new PermanentMigrationException(
                        "Attribute copy failed: " + destItemType + "." + name, ex);
            }
        }
    }

    private void setDestAttrTyped(DKDDO dest, short dataId, short type, Object srcVal) throws Exception {
        if (srcVal == null) { dest.setData(dataId, null); return; }
        switch (type) {
            case 1: case 2: case 3: case 4: case 5: case 6: case 10: case 11:
                dest.setData(dataId, srcVal.toString().trim()); return;
            case 7: dest.setData(dataId, toSqlDate(dataId, srcVal)); return;
            case 8: dest.setData(dataId, toSqlTime(dataId, srcVal)); return;
            case 9: dest.setData(dataId, toSqlTimestamp(dataId, srcVal)); return;
            default: dest.setData(dataId, srcVal);
        }
    }

    private static java.sql.Date toSqlDate(short attrId, Object v) {
        if (v == null) return null;
        if (v instanceof java.sql.Date) return (java.sql.Date) v;
        if (v instanceof java.util.Date) return new java.sql.Date(((java.util.Date) v).getTime());
        try { return java.sql.Date.valueOf(v.toString().trim().replace('T', ' ').split(" ")[0]); } catch (Exception e) { throw new IllegalArgumentException("Invalid DATE", e); }
    }

    private static java.sql.Time toSqlTime(short attrId, Object v) {
        if (v == null) return null;
        if (v instanceof java.sql.Time) return (java.sql.Time) v;
        if (v instanceof java.util.Date) return new java.sql.Time(((java.util.Date) v).getTime());
        try {
            String s = v.toString().trim().replace('T', ' ');
            String timePart = s.contains(" ") ? s.split(" ")[1] : s;
            return java.sql.Time.valueOf(timePart.split("\\.")[0].replaceAll("([Zz]|[+-]\\d\\d:?\\d\\d)$", ""));
        } catch (Exception e) { throw new IllegalArgumentException("Invalid TIME", e); }
    }

    private static java.sql.Timestamp toSqlTimestamp(short attrId, Object v) {
        if (v == null) return null;
        if (v instanceof java.sql.Timestamp) return (java.sql.Timestamp) v;
        if (v instanceof java.util.Date) return new java.sql.Timestamp(((java.util.Date) v).getTime());
        try {
            return java.sql.Timestamp.valueOf(v.toString().trim().replace('T', ' ').replaceAll("([Zz]|[+-]\\d\\d:?\\d\\d)$", ""));
        } catch (Exception e) { throw new IllegalArgumentException("Invalid TIMESTAMP", e); }
    }

    private void copyChildComponents(DKDDO source, DKDDO dest, String destItemType) throws Exception {
        short dataCount = source.dataCount();
        for (short i = 1; i <= dataCount; i++) {
            Object value = source.getData(i);
            if (value instanceof DKChildCollection) {
                DKChildCollection sourceChildren = (DKChildCollection) value;
                if (sourceChildren.cardinality() == 0) continue;
                String childEntityName = sourceChildren.getName();
                dkIterator iter = sourceChildren.createIterator();
                while (iter.more()) {
                    DKDDO sourceChild = (DKDDO) iter.next();
                    DKDDO destChild = dest.getDatastore().createDDO(sourceChild.getObjectType(), (short) DKConstant.DK_CM_ITEM);
                    String childType = destItemType + "/" + childEntityName;
                    copyAttributes(sourceChild, destChild, childType);
                    copyChildComponents(sourceChild, destChild, childType);
                    short collId = dest.dataId(DKConstant.DK_CM_NAMESPACE_ATTR, childEntityName);
                    if (collId <= 0) {
                        throw new PermanentMigrationException(
                                "Destination child collection is missing: " + childEntityName);
                    }
                    Object destValue = dest.getData(collId);
                    if (!(destValue instanceof DKChildCollection)) {
                        throw new PermanentMigrationException(
                                "Destination child collection is unavailable: " + childEntityName);
                    }
                    ((DKChildCollection) destValue).addElement(destChild);
                }
            }
        }
    }

    private static final class CountingDigestInputStream extends java.io.FilterInputStream {
        private final MessageDigest digest;
        private long count = 0;
        protected CountingDigestInputStream(InputStream in, MessageDigest digest) { super(in); this.digest = digest; }
        public int read() throws java.io.IOException { int b = super.read(); if (b >= 0) { if (digest != null) digest.update((byte) b); count++; } return b; }
        public int read(byte[] b, int off, int len) throws java.io.IOException { int n = super.read(b, off, len); if (n > 0) { if (digest != null) digest.update(b, off, n); count += n; } return n; }
        long getCount() { return count; }
    }

    // Round 13A: clone the running per-item digest before each upload attempt.
    // Each attempt feeds bytes into its own scratch digest; the caller commits
    // the scratch back to itemDigest only on a successful, byte-count-verified
    // upload. Prevents digest contamination from partial reads in failed paths.
    private static MessageDigest cloneDigest(MessageDigest d) throws PermanentMigrationException {
        try {
            return (MessageDigest) d.clone();
        } catch (CloneNotSupportedException e) {
            throw new PermanentMigrationException(
                    "SHA-256 MessageDigest provider does not support clone(); cannot safely checkpoint digest for stream fallback", e);
        }
    }

    // 2GB Limit Konstante - setContentFromClientFile() kann keine Dateien > 2GB verarbeiten
    private static final long MAX_FILE_UPLOAD_SIZE = Integer.MAX_VALUE; // 2147483647 bytes

    // Round 13A: Tempdir split. /dev/shm (RAM disk, set by start.sh) is fine for
    // small items; large fallback temp files MUST go to a real filesystem to
    // avoid OOM-killer / "tmpfs: write failed" on multi-GB items × N threads.
    private static final long LARGE_TMP_THRESHOLD = Long.parseLong(
            System.getProperty("cm.migrator.tmpdir.largeThresholdBytes", "104857600")); // 100 MiB
    private static final String LARGE_TMP_DIR = System.getProperty(
            "cm.migrator.tmpdir.large", "/var/tmp/cm-migrator");
    private static final String SMALL_TMP_DIR = System.getProperty(
            "cm.migrator.tmpdir.small", System.getProperty("java.io.tmpdir"));

    private static File createSizedTempFile(String prefix, long expectedSize) throws Exception {
        String dirPath = (expectedSize >= LARGE_TMP_THRESHOLD) ? LARGE_TMP_DIR : SMALL_TMP_DIR;
        File dir = new File(dirPath);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new PermanentMigrationException(
                "Temp dir cannot be created: " + dir.getAbsolutePath()
                + " (cm.migrator.tmpdir." + ((expectedSize >= LARGE_TMP_THRESHOLD) ? "large" : "small") + ")");
        }
        if (!dir.canWrite()) {
            throw new PermanentMigrationException(
                "Temp dir not writable: " + dir.getAbsolutePath());
        }
        return File.createTempFile(prefix, ".dat", dir);
    }

    // Flag um Methoden-Check nur einmal zu loggen
    private static volatile boolean streamMethodsChecked = false;
    private static volatile boolean addMethodsLogged = false;

    // Round 13B: cached reflective Methods. Computed lazily on first use,
    // null-sentinel for "method does not exist" so we don't pay
    // NoSuchMethodException per part. Cache is keyed by method signature
    // (the class is always DKLobICM at runtime).
    private static volatile Method M_ADD_IS_LONG;          private static volatile boolean M_ADD_IS_LONG_PROBED;
    private static volatile Method M_ADD_IS_LONG_INT;      private static volatile boolean M_ADD_IS_LONG_INT_PROBED;
    private static volatile Method M_SETSTREAM_IS_LONG;    private static volatile boolean M_SETSTREAM_IS_LONG_PROBED;
    private static volatile Method M_SETSTREAM_IS;         private static volatile boolean M_SETSTREAM_IS_PROBED;

    private static Method lookupAddIsLong(Class<?> c) {
        if (!M_ADD_IS_LONG_PROBED) {
            try { M_ADD_IS_LONG = c.getMethod("add", InputStream.class, long.class); }
            catch (NoSuchMethodException e) { M_ADD_IS_LONG = null;
                logger.debug("DKLobICM.add(InputStream, long) absent — caching negative result"); }
            M_ADD_IS_LONG_PROBED = true;
        }
        return M_ADD_IS_LONG;
    }
    private static Method lookupAddIsLongInt(Class<?> c) {
        if (!M_ADD_IS_LONG_INT_PROBED) {
            try { M_ADD_IS_LONG_INT = c.getMethod("add", InputStream.class, long.class, int.class); }
            catch (NoSuchMethodException e) { M_ADD_IS_LONG_INT = null;
                logger.debug("DKLobICM.add(InputStream, long, int) absent — caching negative result"); }
            M_ADD_IS_LONG_INT_PROBED = true;
        }
        return M_ADD_IS_LONG_INT;
    }
    private static Method lookupSetStreamIsLong(Class<?> c) {
        if (!M_SETSTREAM_IS_LONG_PROBED) {
            try { M_SETSTREAM_IS_LONG = c.getMethod("setContentFromClientStream", InputStream.class, long.class); }
            catch (NoSuchMethodException e) { M_SETSTREAM_IS_LONG = null;
                logger.debug("DKLobICM.setContentFromClientStream(InputStream, long) absent — caching negative result"); }
            M_SETSTREAM_IS_LONG_PROBED = true;
        }
        return M_SETSTREAM_IS_LONG;
    }
    private static Method lookupSetStreamIs(Class<?> c) {
        if (!M_SETSTREAM_IS_PROBED) {
            try { M_SETSTREAM_IS = c.getMethod("setContentFromClientStream", InputStream.class); }
            catch (NoSuchMethodException e) { M_SETSTREAM_IS = null;
                logger.debug("DKLobICM.setContentFromClientStream(InputStream) absent — caching negative result"); }
            M_SETSTREAM_IS_PROBED = true;
        }
        return M_SETSTREAM_IS;
    }

    /**
     * Loggt alle verfügbaren add() Methoden in DKLobICM (für Debugging).
     * Laut IBM Doku 8.7.00.500 gibt es: add(InputStream, long), add(InputStream, long, int), add(String), add(String, int)
     */
    private static void logAvailableAddMethods() {
        if (addMethodsLogged) return;
        addMethodsLogged = true;

        try {
            StringBuilder sb = new StringBuilder("Available DKLobICM.add() methods: ");
            boolean found = false;
            for (java.lang.reflect.Method m : DKLobICM.class.getMethods()) {
                if (m.getName().equals("add")) {
                    found = true;
                    sb.append("\n  - add(");
                    Class<?>[] params = m.getParameterTypes();
                    for (int i = 0; i < params.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(params[i].getSimpleName());
                    }
                    sb.append(")");
                }
            }
            if (found) {
                logger.info(sb.toString());
            } else {
                logger.warn("No add() methods found in DKLobICM - this is unexpected!");
            }
        } catch (Exception e) {
            logger.debug("Failed to enumerate add() methods: {}", e.getMessage());
        }
    }

    /**
     * Versucht DKLobICM.add(InputStream, long) - diese Methode verwendet 'long' für die Größe
     * und hat daher KEIN 2GB Limit (laut IBM Doku 8.7.00.500).
     * 
     * WICHTIG: Der Workflow bei add() ist anders als bei setContentFromClientFile():
     * - setContentFromClientFile() setzt Content, der bei parent.add() gespeichert wird
     * - DKLobICM.add(InputStream, long) streamt DIREKT zum Resource Manager
     */
    private static boolean tryDirectAdd(DKLobICM destPart, DKLobICM sourcePart,
            DKNVPair[] retrieveOpts, MessageDigest itemDigest, long expectedSize,
            long[] outConsumed) {

        // Round 6: DirectAdd ist standardmäßig deaktiviert, weil IBM CM 8.7 ohne RM-Tuning
        // mit DGL7180A reagiert. Aktivierung explizit per -Dcm.migrator.directAdd.enable=true.
        // Hard-Disable per -Dcm.migrator.directAdd.disable=true gewinnt weiterhin.
        if (Boolean.getBoolean("cm.migrator.directAdd.disable")) {
            logger.debug("Direct add disabled via cm.migrator.directAdd.disable");
            return false;
        }
        if (!Boolean.getBoolean("cm.migrator.directAdd.enable")) {
            logger.debug("Direct add disabled by default (set cm.migrator.directAdd.enable=true to opt in)");
            return false;
        }

        if (expectedSize < 0) {
            return false;
        }

        // Beim ersten Aufruf die verfügbaren add() Methoden loggen
        logAvailableAddMethods();

        InputStream raw = null;
        CountingDigestInputStream in = null;
        try {
            raw = sourcePart.getContentInputStream(retrieveOpts, 0L, -1L);
            if (raw == null) {
                logger.debug("Source InputStream is null, cannot use direct add");
                return false;
            }

            in = new CountingDigestInputStream(raw, itemDigest);
            
            // Round 13B: cached method lookups — no NoSuchMethodException per part.
            Method m = lookupAddIsLong(destPart.getClass());
            if (m != null) {
                m.invoke(destPart, in, expectedSize);
                if (outConsumed != null) outConsumed[0] = in.getCount();
                if (logger.isDebugEnabled()) logger.debug("DIRECT ADD via DKLobICM.add(InputStream, long) for {} bytes", expectedSize);
                return true;
            }
            m = lookupAddIsLongInt(destPart.getClass());
            if (m != null) {
                m.invoke(destPart, in, expectedSize, 0);
                if (outConsumed != null) outConsumed[0] = in.getCount();
                if (logger.isDebugEnabled()) logger.debug("DIRECT ADD via DKLobICM.add(InputStream, long, int) for {} bytes", expectedSize);
                return true;
            }
            return false;
            
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            String msg = cause != null ? cause.getMessage() : ite.getMessage();
            logger.error("DIRECT ADD FAILED: {} - SDK may not support this method or RM issue", msg);
            if (logger.isDebugEnabled()) {
                logger.debug("Direct add exception details", ite);
            }
            return false;
        } catch (Throwable t) {
            logger.debug("Direct add failed: {}", t.getMessage());
            return false;
        } finally {
            if (raw != null) try { raw.close(); } catch (Exception e) { /* ignore */ }
        }
    }

    private static boolean tryStreamUpload(DKLobICM destPart, DKLobICM sourcePart, DKNVPair[] retrieveOpts,
            MessageDigest itemDigest, long expectedSize, long[] outConsumed) {
        if (Boolean.getBoolean("cm.migrator.streamUpload.disable")) {
            return false;
        }
        if (expectedSize < 0) {
            return false; // negative size is invalid, but 0 is allowed
        }

        InputStream raw = null;
        CountingDigestInputStream in = null;
        try {
            raw = sourcePart.getContentInputStream(retrieveOpts, 0L, -1L);
            if (raw == null) {
                return false;
            }

            in = new CountingDigestInputStream(raw, itemDigest);

            // Round 13B: cached method lookups — long-typed variant first.
            Method m = lookupSetStreamIsLong(destPart.getClass());
            if (m != null) {
                m.invoke(destPart, in, expectedSize);
                if (outConsumed != null) outConsumed[0] = in.getCount();
                if (logger.isDebugEnabled()) logger.debug("Stream upload via setContentFromClientStream(InputStream, long) for {} bytes", expectedSize);
                return true;
            }
            m = lookupSetStreamIs(destPart.getClass());
            if (m != null) {
                m.invoke(destPart, in);
                if (outConsumed != null) outConsumed[0] = in.getCount();
                if (logger.isDebugEnabled()) logger.debug("Stream upload via setContentFromClientStream(InputStream) for {} bytes", expectedSize);
                return true;
            }
            // Beide Methoden absent — einmal warnen.
            if (!streamMethodsChecked) {
                streamMethodsChecked = true;
                logger.warn("IBM CM SDK does NOT support setContentFromClientStream(). Large files (>2GB) cannot be migrated directly.");
                logger.warn("Available content methods in DKLobICM: setContentFromClientFile(String), setContent(byte[])");
            }
            return false;
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            logger.error("Stream upload FAILED: {}", cause != null ? cause.getMessage() : ite.getMessage());
            return false;
        } catch (Throwable t) {
            logger.debug("Stream upload failed: {}", t.getMessage());
            return false;
        } finally {
            if (raw != null) try { raw.close(); } catch (Exception e) { /* ignore */ }
        }
    }

    // Alternative für große Dateien: setContent() mit Byte-Array (funktioniert aber nur für Dateien < ~1GB wegen Memory)
    private static boolean trySetContentFromBytes(DKLobICM destPart, DKLobICM sourcePart, DKNVPair[] retrieveOpts, MessageDigest itemDigest, long expectedSize) {
        // Nur für Dateien < 500MB versuchen (Memory-Schutz)
        final long MAX_BYTES_IN_MEMORY = 500 * 1024 * 1024; // 500MB
        if (expectedSize > MAX_BYTES_IN_MEMORY || expectedSize < 0) {
            return false;
        }
        
        try (InputStream raw = sourcePart.getContentInputStream(retrieveOpts, 0L, -1L)) {
            if (raw == null) return false;
            
            // In Byte-Array lesen
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[65536];
            int n;
            while ((n = raw.read(buffer)) != -1) {
                baos.write(buffer, 0, n);
                if (itemDigest != null) itemDigest.update(buffer, 0, n);
            }
            byte[] content = baos.toByteArray();
            
            // setContent(byte[]) aufrufen
            destPart.setContent(content);
            if (logger.isDebugEnabled()) logger.debug("setContent(byte[]) for {} bytes", content.length);
            return true;
        } catch (Throwable t) {
            logger.debug("setContent(byte[]) failed: {}", t.getMessage());
            return false;
        }
    }

    private void copyParts(DKDDO source, DKDDO dest, DKRetrieveOptionsICM parentOpts, MigrationItem item, List<File> tempFiles) throws Exception {
        short partsId = source.dataId(DKConstant.DK_CM_NAMESPACE_ATTR, DKConstant.DK_CM_DKPARTS);
        if (partsId == 0) partsId = source.dataId(DKConstant.DK_CM_NAMESPACE_ATTR, "DKParts");
        if (partsId == 0) return;

        Object rawParts = source.getData(partsId);
        if (rawParts == null || !(rawParts instanceof DKParts)) return;
        DKParts parts = (DKParts) rawParts;

        // Round 13B: per-thread digest, reset for this item. The clone-and-commit
        // pattern in the upload attempts (Round 13A) keeps isolation correct
        // even with a shared ThreadLocal.
        MessageDigest itemDigest = SHA256_DIGEST.get();
        itemDigest.reset();
        boolean hasAnyPart = false;

        class PartWrapper {
            DKLobICM part;
            int index;
            String name;
            long size;
            String mime;
            PartWrapper(DKLobICM p, int i) {
                part = p; index = i;
                try { name = p.getOrgFileName(); } catch (Exception e) { name = ""; }
                if (name != null) name = name.trim(); else name = "";
                try { size = p.getSize(); } catch (Exception e) { size = -1; }
                try { mime = p.getMimeType(); } catch (Exception e) { mime = ""; }
                if (mime != null) mime = mime.trim(); else mime = "";
            }
        }

        List<PartWrapper> sortedParts = new ArrayList<>();
        dkIterator iter = parts.createIterator();
        int idx = 0;
        while (iter.more()) {
            Object obj = iter.next();
            if (obj instanceof DKLobICM) sortedParts.add(new PartWrapper((DKLobICM) obj, idx++));
        }

        if (sortedParts.size() > 1) {
            sortedParts.sort((pw1, pw2) -> {
                int cmp = String.CASE_INSENSITIVE_ORDER.compare(pw1.name, pw2.name);
                if (cmp != 0) return cmp;
                return Integer.compare(pw1.index, pw2.index);
            });
        }

        DKDatastoreICM sourceDs = (DKDatastoreICM) source.getDatastore();
        
        // FIX 1: Retrieve Options NUR für Metadaten (Größe, etc.) - KEIN Inhalt!
        DKRetrieveOptionsICM checkOpt = DKRetrieveOptionsICM.createInstance(sourceDs);
        checkOpt.resourceContent(false); 
        checkOpt.baseAttributes(true);
        DKNVPair[] checkOptsArr = checkOpt.dkNVPair();

        // Retrieve Options für den Stream-Download
        DKRetrieveOptionsICM contentOpt = DKRetrieveOptionsICM.createInstance(sourceDs);
        contentOpt.resourceContent(true);
        contentOpt.baseAttributes(true);
        DKNVPair[] contentRetrieveOpts = contentOpt.dkNVPair();

        for (PartWrapper wrapper : sortedParts) {
            if (ShutdownCoordinator.isShuttingDown()) {
                throw new InterruptedException("Shutdown requested before copying next part for item " + item.getItemId());
            }
        
            long partStartMs = System.currentTimeMillis();
        
            hasAnyPart = true;
            DKLobICM sourcePart = wrapper.part;
            long expectedSize = wrapper.size;
            String originalName = wrapper.name.isEmpty() ? "migrated_file.bin" : wrapper.name;

            logger.debug("Processing Part: {} (expectedSize={} bytes)", originalName, expectedSize);

            // FIX 2: Metadaten-Retrieve erzwingen (prüft RM-Verbindung ohne Memory-Crash)
            try { 
                sourcePart.retrieve(checkOptsArr); 
            } catch (Exception e) { 
                logger.warn("Explicit metadata retrieve failed for part '{}' (RM issue?): {}", originalName, e.getMessage()); 
            }

            DKDatastoreICM destDs = (DKDatastoreICM) dest.getDatastore();
            DKLobICM destPart = (DKLobICM) destDs.createDDO(sourcePart.getObjectType(), DKConstant.DK_CM_RESOURCE);
            if (!wrapper.mime.isEmpty()) try { destPart.setMimeType(wrapper.mime); } catch (Exception e) {}
            destPart.setOrgFileName(originalName);

            // Round 13A: hard-fail on negative/overflowed expectedSize. Older SDKs
            // expose getSize() as int; values >= 2 GiB sign-extend to negative long.
            // Without this check the negative size would silently fall through to the
            // tempfile path and crash with DGL0303A.
            if (expectedSize < 0) {
                throw new PermanentMigrationException(
                    "Content size is negative/overflowed for item " + item.getItemId()
                    + " sourceItemType=" + item.getSourceItemType()
                    + " part='" + originalName + "' rawSize=" + expectedSize
                    + ". SDK long-size support or reliable size lookup required.");
            }

            // 0-Byte-Dateien gesondert behandeln
            if (expectedSize == 0) {
                // Round 13A: 0-byte temp goes to small tmpdir.
                String prefix = "empty_" + Integer.toHexString(item.getItemId().hashCode()) + "_";
                File tempFile = createSizedTempFile(prefix, 0L);
                tempFiles.add(tempFile);
                destPart.setContentFromClientFile(tempFile.getAbsolutePath());

                short destPartsId = dest.dataId(DKConstant.DK_CM_NAMESPACE_ATTR, DKConstant.DK_CM_DKPARTS);
                if (destPartsId == 0) destPartsId = dest.dataId(DKConstant.DK_CM_NAMESPACE_ATTR, "DKParts");
                DKParts destParts = (DKParts) dest.getData(destPartsId);
                if (destParts == null) { destParts = new DKParts(); dest.setData(destPartsId, destParts); }
                destParts.addElement(destPart);
                continue;
            }

            boolean uploadedByStream = false;
            boolean requiresStreamUpload = (expectedSize > MAX_FILE_UPLOAD_SIZE);
            if (requiresStreamUpload) {
                logger.info("Part '{}' exceeds 2GB limit ({} bytes), stream upload REQUIRED", originalName, expectedSize);
            }

            // Round 13A: digest isolation per attempt — clone the running per-item
            // digest, give the clone to each attempt; commit only on a successful,
            // byte-count-verified upload. Prevents partial-stream contamination.
            long[] outConsumed = new long[1];

            MessageDigest scratch = cloneDigest(itemDigest);
            uploadedByStream = tryDirectAdd(destPart, sourcePart, contentRetrieveOpts, scratch, expectedSize, outConsumed);
            if (uploadedByStream) {
                if (outConsumed[0] != expectedSize) {
                    throw new PermanentMigrationException(
                        "Partial stream upload detected (DirectAdd) for part '" + originalName
                        + "': expected " + expectedSize + " bytes, SDK consumed " + outConsumed[0]);
                }
                itemDigest = scratch; // commit
            } else {
                outConsumed[0] = 0L;
                scratch = cloneDigest(itemDigest);
                uploadedByStream = tryStreamUpload(destPart, sourcePart, contentRetrieveOpts, scratch, expectedSize, outConsumed);
                if (uploadedByStream) {
                    if (outConsumed[0] != expectedSize) {
                        throw new PermanentMigrationException(
                            "Partial stream upload detected (StreamUpload) for part '" + originalName
                            + "': expected " + expectedSize + " bytes, SDK consumed " + outConsumed[0]);
                    }
                    itemDigest = scratch; // commit
                }
            }

            // Wenn Stream-Upload fehlschlägt und Datei > 2GB ist, versuche trotzdem
            // tempfile + setContentFromClientFile(). Neuere IBM CM SDKs können
            // >2GB-Dateien über diesen Pfad verarbeiten.
            if (!uploadedByStream && requiresStreamUpload) {
                logger.warn("Stream upload FAILED for large file '{}' ({} bytes)."
                        + " Trying tempfile fallback — setContentFromClientFile() may"
                        + " support large files on newer SDK versions.", originalName, expectedSize);
            }

            if (!uploadedByStream) {
                if (ShutdownCoordinator.isShuttingDown()) {
                    throw new InterruptedException("Shutdown requested before tempfile fallback for item "
                            + item.getItemId() + " part '" + originalName + "'");
                }

                String prefix = "mig_" + Integer.toHexString(item.getItemId().hashCode()) + "_";
                // Round 13A: pick small/large tmpdir based on expectedSize.
                File tempFile = createSizedTempFile(prefix, expectedSize);
                tempFiles.add(tempFile);
                try { ResourceGuardian.register(tempFile); } catch (Throwable t) {}

                InputStream is = sourcePart.getContentInputStream(contentRetrieveOpts, 0L, -1L);
                if (is == null) {
                    DKRetrieveOptionsICM retryOpt = DKRetrieveOptionsICM.createInstance(sourceDs);
                    retryOpt.resourceContent(true);
                    is = sourcePart.getContentInputStream(retryOpt.dkNVPair(), 0L, -1L);
                }
                if (is == null) {
                    String rmInfo = "";
                    try { rmInfo = "RM_NAME=" + sourcePart.getRMName(); } catch(Exception e) {}
                    throw new Exception("Source InputStream is null for part: " + originalName + ". [" + rmInfo + "]. Resource Manager offline or file missing?");
                }

                // Round 13A: write+digest to scratch; commit only after full size matches.
                MessageDigest fbScratch = cloneDigest(itemDigest);
                long totalRead = 0;
                try (OutputStream os = new FileOutputStream(tempFile, false)) {
                    byte[] buffer = new byte[65536];
                    int n;
                    while ((n = is.read(buffer)) != -1) {
                        if (ShutdownCoordinator.isShuttingDown()) {
                            throw new InterruptedIOException("Shutdown requested during tempfile fallback for item "
                                    + item.getItemId() + " part '" + originalName + "'");
                        }

                        os.write(buffer, 0, n);
                        fbScratch.update(buffer, 0, n);
                        totalRead += n;
                    }
                    os.flush();
                } finally {
                    try { is.close(); } catch (Exception e) {}
                }

                if (expectedSize >= 0 && totalRead < expectedSize) {
                    throw new PermanentMigrationException(
                        "Partial source read in tempfile fallback for part '" + originalName
                        + "': expected " + expectedSize + " bytes, read " + totalRead);
                }

                if (expectedSize >= 0 && totalRead > expectedSize) {
                    logger.warn("Source size metadata differs from actual stream bytes for part '{}': expectedSize={} actualRead={} itemId={}",
                            originalName, expectedSize, totalRead, item.getItemId());
                }

                itemDigest = fbScratch; // commit actual bytes read from source stream
                destPart.setContentFromClientFile(tempFile.getAbsolutePath());
            }

            short destPartsId = dest.dataId(DKConstant.DK_CM_NAMESPACE_ATTR, DKConstant.DK_CM_DKPARTS);
            if (destPartsId == 0) destPartsId = dest.dataId(DKConstant.DK_CM_NAMESPACE_ATTR, "DKParts");
            DKParts destParts = (DKParts) dest.getData(destPartsId);
            if (destParts == null) { destParts = new DKParts(); dest.setData(destPartsId, destParts); }
            destParts.addElement(destPart);
            long partMs = System.currentTimeMillis() - partStartMs;
            if (partMs >= SLOW_PART_WARN_MS) {
                logger.warn("Slow part copied: itemId={} part='{}' size={} durationMs={}",
                        item.getItemId(), originalName, expectedSize, partMs);
            }            
        }

        if (hasAnyPart && item.getChecksum() == null && itemDigest != null) {
            item.setChecksum(bytesToHex(itemDigest.digest()));
        }
    }


    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private void safeDeleteTempFile(File f) {
        if (f == null) return;
        try { if (f.exists() && !f.delete()) f.deleteOnExit(); } catch (Exception ex) { try { f.deleteOnExit(); } catch (Exception ignore) {} }
    }
}

