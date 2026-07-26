package com.ibm.ecm.migration;

import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Properties;
import java.util.Set;

public final class RunConfigSnapshotTest {
    private static final String SECRET = "SNAPSHOT_SECRET_SENTINEL_9b731";

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("cm-snapshot-test-");
        Path profileDir = root.resolve("conf/profiles");
        Files.createDirectories(profileDir);
        Files.writeString(root.resolve("conf/migration.properties"),
                "CONNECT_USER=wrong-default\n"
                        + "PRODUCER_COUNT_STRATEGY=SDK_CURSOR\n"
                        + "MIGRATE_ITEMTYPES=WRONG:WRONG\n");
        Path profile = profileDir.resolve("alternate.properties");
        Files.writeString(profile,
                "CONNECT_USER=operator\n"
                        + "CONNECT_PASSWORD=" + SECRET + "\n"
                        + "PROFILE=high-performance\n"
                        + "PRODUCER_COUNT_STRATEGY=SINGLE_PASS\n"
                        + "MIGRATE_ITEMTYPES=SOURCE:DEST\n");

        Path runDir = root.resolve("data/webgui-runs");
        Path snapshot = RunConfigSnapshot.create(
                profile, "verification", "test-run", runDir);

        Properties copied = new Properties();
        try (var input = Files.newInputStream(snapshot)) {
            copied.load(input);
        }
        assertEquals(SECRET, copied.getProperty("CONNECT_PASSWORD"),
                "effective credential must remain available to the run");
        assertEquals("VERIFY", copied.getProperty("OPERATION_MODE"),
                "verification mode");
        assertEquals("high-performance", copied.getProperty("PROFILE"),
                "selected profile must remain complete");
        assertEquals("SINGLE_PASS", copied.getProperty("PRODUCER_COUNT_STRATEGY"),
                "selected strategy must not fall back to migration.properties");
        assertEquals("SOURCE:DEST", copied.getProperty("MIGRATE_ITEMTYPES"),
                "selected mapping must remain complete");
        assertFalse(copied.containsKey("WEBGUI_RUN_ID"),
                "unused run metadata must not be copied");
        assertFalse(copied.containsKey("WEBGUI_SOURCE_CONFIG"),
                "source path must not be materialized in the snapshot");

        FileStore store = Files.getFileStore(runDir);
        if (store.supportsFileAttributeView("posix")) {
            assertEquals(Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE),
                    Files.getPosixFilePermissions(runDir),
                    "snapshot directory permissions");
            assertEquals(Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(snapshot),
                    "snapshot file permissions");
        }

        RunConfigSnapshot.cleanupIfSafe(snapshot, false);
        assertTrue(Files.exists(snapshot),
                "snapshot must remain while worker termination is unconfirmed");
        RunConfigSnapshot.cleanupIfSafe(snapshot, true);
        assertFalse(Files.exists(snapshot),
                "snapshot must be deleted after confirmed terminal completion");

        System.out.println("RunConfigSnapshotTest: PASS");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertFalse(boolean value, String message) {
        assertTrue(!value, message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
