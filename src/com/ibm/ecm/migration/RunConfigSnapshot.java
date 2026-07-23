package com.ibm.ecm.migration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;
import java.util.Set;

/** Creates owner-only WebGUI run configuration snapshots. */
final class RunConfigSnapshot {
    private static final Logger logger = LogManager.getLogger(RunConfigSnapshot.class);
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private RunConfigSnapshot() {
    }

    static Path create(Path sourceConfig, String mode, String runId, Path runDir)
            throws IOException {
        if (!Files.isRegularFile(sourceConfig, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Run configuration is not a regular file");
        }
        if (runId == null || !runId.matches("[A-Za-z0-9._-]+")) {
            throw new IOException("Invalid WebGUI run identifier");
        }
        if (Files.isSymbolicLink(runDir)) {
            throw new IOException("WebGUI snapshot directory must not be a symbolic link");
        }

        boolean posix = createOwnerOnlyDirectory(runDir);
        Path snapshot = runDir.resolve(runId + ".properties");
        if (Files.exists(snapshot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("WebGUI run snapshot already exists");
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(sourceConfig)) {
            properties.load(input);
        }
        properties.setProperty("OPERATION_MODE", normalizeMode(mode));

        try {
            if (posix) {
                Files.createFile(snapshot,
                        PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
            } else {
                Files.createFile(snapshot);
                boolean restricted = restrictOwnerOnly(snapshot.toFile(), false);
                logger.warn("POSIX file permissions are unavailable; applied owner-only File fallback (verified={}).",
                        restricted);
            }
            try (OutputStream output = Files.newOutputStream(snapshot)) {
                properties.store(output, "CM Migrator WebGUI run snapshot");
            }
            if (posix) {
                Files.setPosixFilePermissions(snapshot, FILE_PERMISSIONS);
            }
            return snapshot;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(snapshot);
            throw e;
        }
    }

    static void cleanupIfSafe(Path snapshot, boolean terminationConfirmed)
            throws IOException {
        if (terminationConfirmed && snapshot != null) {
            Files.deleteIfExists(snapshot);
        }
    }

    private static boolean createOwnerOnlyDirectory(Path runDir) throws IOException {
        try {
            Files.createDirectories(runDir,
                    PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
            Files.setPosixFilePermissions(runDir, DIRECTORY_PERMISSIONS);
            return true;
        } catch (UnsupportedOperationException e) {
            Files.createDirectories(runDir);
            boolean restricted = restrictOwnerOnly(runDir.toFile(), true);
            logger.warn("POSIX directory permissions are unavailable; applied owner-only File fallback (verified={}).",
                    restricted);
            return false;
        }
    }

    private static boolean restrictOwnerOnly(File file, boolean directory) {
        boolean restricted = file.setReadable(false, false);
        restricted &= file.setWritable(false, false);
        restricted &= file.setExecutable(false, false);
        restricted &= file.setReadable(true, true);
        restricted &= file.setWritable(true, true);
        if (directory) {
            restricted &= file.setExecutable(true, true);
        }
        return restricted;
    }

    private static String normalizeMode(String mode) {
        String normalized = mode == null ? "MIGRATE" : mode.trim().toUpperCase();
        if ("VERIFY".equals(normalized) || "VERIFICATION".equals(normalized)) {
            return "VERIFY";
        }
        if ("DELETE".equals(normalized)) {
            return "DELETE";
        }
        return "MIGRATE";
    }
}
