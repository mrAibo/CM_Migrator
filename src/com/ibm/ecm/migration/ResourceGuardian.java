/*
 * Projekt: CM Migrator 2.2.1.
 * @Author: Aleksej Voronin, Sven Lindt
 * @Date:   26.01.2026
 */
package com.ibm.ecm.migration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Bereinigungsroutine um Zumüllen der Festplatte zu verhindern
 */
public class ResourceGuardian {
    private static final Logger logger = LogManager.getLogger(ResourceGuardian.class);

    private static final ThreadLocal<List<File>> REGISTRY = ThreadLocal.withInitial(ArrayList::new);

    /**
     * Registers a file for automatic cleanup.
     */
    public static void register(File file) {
        if (file == null) return;
        REGISTRY.get().add(file);
    }

    /**
     * Unregisters a file (e.g. if it was already deleted manually).
     */
    public static void unregister(File file) {
        if (file == null) return;
        REGISTRY.get().remove(file);
    }

    /**
     * Deletes all registered files for the current thread.
     * Call this in the final catch/finally block of a worker thread or batch loop.
     */
    public static void cleanup() {
        List<File> files = REGISTRY.get();
        if (files.isEmpty()) return;

        int deletedCount = 0;
        for (File file : files) {
            try {
                if (file.exists()) {
                    if (file.delete()) {
                        deletedCount++;
                    } else {
                        logger.warn("ResourceGuardian: Failed to delete temp file: {}", file.getAbsolutePath());
                        file.deleteOnExit();
                    }
                }
            } catch (Exception e) {
                logger.error("ResourceGuardian: Error during cleanup of {}: {}", file.getAbsolutePath(), e.getMessage());
            }
        }

        if (deletedCount > 0) {
            logger.debug("ResourceGuardian: Cleaned up {} files in thread {}", deletedCount, Thread.currentThread().getName());
        }
        
        files.clear();
        REGISTRY.remove(); 
    }
}
