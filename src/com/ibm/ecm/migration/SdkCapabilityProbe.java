/*
 * Round 13A — SDK Capability Probe.
 * Detects which DKLobICM upload methods are available at runtime so the
 * migrator can fail fast on >2 GB items if no safe path exists.
 */
package com.ibm.ecm.migration;

import com.ibm.mm.sdk.common.DKLobICM;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;

/**
 * Static capability probe; results are computed once at class-load time.
 * No SDK calls are made at probe time, only reflection on method signatures.
 */
public final class SdkCapabilityProbe {
    private static final Logger logger = LogManager.getLogger(SdkCapabilityProbe.class);

    public static final boolean STREAM_UPLOAD_WITH_LENGTH;
    public static final boolean STREAM_UPLOAD_WITHOUT_LENGTH;
    public static final boolean DIRECT_ADD_WITH_LENGTH;
    public static final boolean DIRECT_ADD_WITH_LENGTH_AND_OPTIONS;
    /**
     * Return type of DKLobICM.getSize() ("long" or "int" or "<unknown>").
     * "int" means values >= 2 GiB will overflow into negative numbers.
     */
    public static final String GET_SIZE_RETURN_TYPE;
    /** True iff DKLobICM exposes a long-typed size getter (e.g. getSize64). */
    public static final boolean HAS_LONG_SIZE_GETTER;

    static {
        STREAM_UPLOAD_WITH_LENGTH    = hasMethod(DKLobICM.class, "setContentFromClientStream", InputStream.class, long.class);
        STREAM_UPLOAD_WITHOUT_LENGTH = hasMethod(DKLobICM.class, "setContentFromClientStream", InputStream.class);
        DIRECT_ADD_WITH_LENGTH       = hasMethod(DKLobICM.class, "add", InputStream.class, long.class);
        DIRECT_ADD_WITH_LENGTH_AND_OPTIONS = hasMethod(DKLobICM.class, "add", InputStream.class, long.class, int.class);
        GET_SIZE_RETURN_TYPE = sizeReturnType(DKLobICM.class);
        HAS_LONG_SIZE_GETTER = hasMethod(DKLobICM.class, "getSize64")
                            || "long".equals(GET_SIZE_RETURN_TYPE);
    }

    private SdkCapabilityProbe() {}

    private static boolean hasMethod(Class<?> c, String name, Class<?>... args) {
        try { c.getMethod(name, args); return true; }
        catch (NoSuchMethodException e) { return false; }
        catch (Throwable t) { return false; }
    }

    private static String sizeReturnType(Class<?> c) {
        try { return c.getMethod("getSize").getReturnType().getName(); }
        catch (Throwable t) { return "<unknown>"; }
    }

    /**
     * A safe large-file path requires the long-length stream-upload method.
     * The no-length variant is NOT considered safe for >2 GB because the
     * IBM RM may use 32-bit internal counters when the size is unknown.
     */
    public static boolean hasSafeLargeFilePath() {
        return STREAM_UPLOAD_WITH_LENGTH;
    }

    public static void logCapabilities() {
        logger.info("SDK capability probe: streamUploadWithLength={} streamUploadWithoutLength={} "
                + "directAddWithLength={} directAddWithLengthAndOptions={} "
                + "getSizeReturnType={} hasLongSizeGetter={}",
                STREAM_UPLOAD_WITH_LENGTH, STREAM_UPLOAD_WITHOUT_LENGTH,
                DIRECT_ADD_WITH_LENGTH, DIRECT_ADD_WITH_LENGTH_AND_OPTIONS,
                GET_SIZE_RETURN_TYPE, HAS_LONG_SIZE_GETTER);
    }

    /**
     * Round 14a: WARN-only by default; abort only in explicit strict mode.
     *
     * Two distinct knobs:
     *   - cm.migrator.largeFile.failFast (default true): per-item safety.
     *     Honoured by ItemMigrator: any item whose part actually exceeds 2 GiB
     *     and lacks a safe upload path throws PermanentMigrationException
     *     (handled item-level, no batch retry, journaled FAILED).
     *
     *   - cm.migrator.largeFile.failFastAtStartup (default false): strict
     *     startup mode. When true AND the SDK lacks setContentFromClientStream(
     *     InputStream, long), abort the entire migration at startup before any
     *     item is processed. Useful for scheduled jobs that MUST not silently
     *     skip large items.
     *
     * Default behaviour for a missing safe path: log a strong WARN but let the
     * migration start. Small-only workloads then run unaffected; an actual
     * >2 GiB item still fails safely via PermanentMigrationException.
     */
    public static void enforceFailFast() {
        if (hasSafeLargeFilePath()) return;

        boolean failFastAtStartup = Boolean.parseBoolean(
                System.getProperty("cm.migrator.largeFile.failFastAtStartup", "false"));

        String msg = "SDK lacks DKLobICM.setContentFromClientStream(InputStream,long). "
                + "Small-only migrations continue; content parts >2 GiB will fail per-item as FAILED. "
                + "Use -Dcm.migrator.largeFile.failFastAtStartup=true to abort at startup.";

        logger.debug("Large-file safety details: DirectAdd remains disabled by default; "
                + "enable with -Dcm.migrator.directAdd.enable=true only if the Resource Manager permits it. "
                + "Recommended fix: install an IBM CM SDK/FixPack exposing "
                + "setContentFromClientStream(InputStream,long).");

        if (failFastAtStartup) {
            logger.error(msg);
            throw new IllegalStateException(msg);
        }

        logger.warn(msg);
            
        }
    }
