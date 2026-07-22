#!/usr/bin/env python3
"""Apply the worker-failure propagation changes without changing line endings."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PRODUCER = ROOT / "src/com/ibm/ecm/migration/Producer.java"
MAIN = ROOT / "src/com/ibm/ecm/migration/Main.java"


class PatchError(RuntimeError):
    pass


def detect_newline(data: bytes) -> bytes:
    if b"\r\n" in data:
        return b"\r\n"
    return b"\n"


def replace_once(data: bytes, old: str, new: str, newline: bytes, label: str) -> bytes:
    old_bytes = old.replace("\n", newline.decode()).encode()
    new_bytes = new.replace("\n", newline.decode()).encode()
    count = data.count(old_bytes)
    if count != 1:
        raise PatchError(f"{label}: expected one match, found {count}")
    return data.replace(old_bytes, new_bytes, 1)


def patch_producer(data: bytes) -> bytes:
    newline = detect_newline(data)

    data = replace_once(
        data,
        """    private final MigrationStats stats;
    private final int consumerCount;
""",
        """    private final MigrationStats stats;
    private final WorkerFailureState workerFailureState;
    private final int consumerCount;
""",
        newline,
        "Producer field",
    )

    data = replace_once(
        data,
        """    public Producer(BlockingQueue<MigrationItem> queue, MigrationConfig config, MigrationJournal journal, MigrationStats stats) {
        this.queue = queue;
        this.config = config;
        this.journal = journal;
        this.stats = stats;
        this.consumerCount = config.getThreadCount();
    }
""",
        """    public Producer(BlockingQueue<MigrationItem> queue, MigrationConfig config, MigrationJournal journal,
                    MigrationStats stats, WorkerFailureState workerFailureState) {
        this.queue = queue;
        this.config = config;
        this.journal = journal;
        this.stats = stats;
        this.workerFailureState = workerFailureState;
        this.consumerCount = config.getThreadCount();
    }
""",
        newline,
        "Producer constructor",
    )

    data = replace_once(
        data,
        """                if (!VALID_ITEM_TYPE.matcher(sourceType).matches() || !VALID_ITEM_TYPE.matcher(destType).matches()) {
                    logger.error("SECURITY ALERT: Invalid ItemType format detected! Source={}, Dest={}. Skipping.", sourceType, destType);
                    continue;
                }
""",
        """                if (!VALID_ITEM_TYPE.matcher(sourceType).matches() || !VALID_ITEM_TYPE.matcher(destType).matches()) {
                    IllegalArgumentException failure = new IllegalArgumentException(
                            "Invalid ItemType format: " + sourceType + " -> " + destType);
                    workerFailureState.record(failure);
                    logger.error("SECURITY ALERT: Invalid ItemType format detected! Source={}, Dest={}. Aborting.",
                            sourceType, destType, failure);
                    ShutdownCoordinator.requestShutdown();
                    break;
                }
""",
        newline,
        "invalid item type",
    )

    data = replace_once(
        data,
        """                    } catch (Exception e) {
                        logger.error("Error processing ItemType {}", sourceType, e);
                    } finally {
""",
        """                    } catch (Exception e) {
                        workerFailureState.record(e);
                        logger.error("Error processing ItemType {}", sourceType, e);
                        ShutdownCoordinator.requestShutdown();
                    } finally {
""",
        newline,
        "discovery failure",
    )

    data = replace_once(
        data,
        """        } catch (InterruptedException e) {
            logger.error("Producer interrupted", e);
            discoveryExecutor.shutdownNow();
            Thread.currentThread().interrupt();
""",
        """        } catch (InterruptedException e) {
            workerFailureState.record(e);
            logger.error("Producer interrupted", e);
            discoveryExecutor.shutdownNow();
            Thread.currentThread().interrupt();
""",
        newline,
        "producer interruption",
    )

    data = replace_once(
        data,
        """            if (ShutdownCoordinator.isShuttingDown()) {
                logger.info("Producer stopping due to shutdown. Skipping Poison Pills; consumers stop via shutdown flag.");
""",
        """            if (ShutdownCoordinator.isShuttingDown() || workerFailureState.hasFailure()) {
                logger.info("Producer stopping due to shutdown or failure. Skipping Poison Pills; consumers stop via shutdown flag.");
""",
        newline,
        "poison-pill gate",
    )

    data = replace_once(
        data,
        """    private void processItemType(CMConnection conn, String sourceType, String destType) {
        try {
            DKDatastoreICM ds = conn.getDatastore();
""",
        """    private void processItemType(CMConnection conn, String sourceType, String destType) throws Exception {
        DKDatastoreICM ds = conn.getDatastore();
""",
        newline,
        "processItemType signature",
    )

    data = replace_once(
        data,
        """            logger.info("PRODUCER STATS Type={} Fetched={} Enqueued={} Skipped={} AvgFetchMs={} QueueDepth={}",\x20
                    sourceType, fetched, enqueued, skipped, avgFetch, queue.size());

        } catch (Exception e) {
            logger.error("Error in Producer for type " + sourceType, e);
        }
    }
""",
        """        logger.info("PRODUCER STATS Type={} Fetched={} Enqueued={} Skipped={} AvgFetchMs={} QueueDepth={}",
                sourceType, fetched, enqueued, skipped, avgFetch, queue.size());
    }
""",
        newline,
        "processItemType catch removal",
    )

    return data


def patch_main(data: bytes) -> bytes:
    newline = detect_newline(data)

    data = replace_once(
        data,
        """    public static void startMigration(String configPath) throws Exception {
        // 1. Load Config
""",
        """    public static void startMigration(String configPath) throws Exception {
        ShutdownCoordinator.reset();
        WorkerFailureState workerFailureState = new WorkerFailureState();

        // 1. Load Config
""",
        newline,
        "Main state initialization",
    )

    data = replace_once(
        data,
        """        Producer producer = new Producer(queue, config, journal, stats);
""",
        """        Producer producer = new Producer(queue, config, journal, stats, workerFailureState);
""",
        newline,
        "Producer construction",
    )

    data = replace_once(
        data,
        """        if (ShutdownCoordinator.isShuttingDown()) {
            aborted = true;
        }
""",
        """        if (ShutdownCoordinator.isShuttingDown() || workerFailureState.hasFailure()) {
            aborted = true;
        }
""",
        newline,
        "aborted state",
    )

    data = replace_once(
        data,
        """        System.out.println(ConsoleUI.separator());
    }
""",
        """        System.out.println(ConsoleUI.separator());

        workerFailureState.throwIfPresent("Migration worker failed");
    }
""",
        newline,
        "failure propagation",
    )

    return data


def write_atomic(path: Path, data: bytes) -> None:
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_bytes(data)
    temporary.replace(path)


def main() -> int:
    originals = {PRODUCER: PRODUCER.read_bytes(), MAIN: MAIN.read_bytes()}

    try:
        patched = {
            PRODUCER: patch_producer(originals[PRODUCER]),
            MAIN: patch_main(originals[MAIN]),
        }
    except PatchError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1

    if all(patched[path] == originals[path] for path in originals):
        print("Worker failure propagation already applied.")
        return 0

    for path, data in patched.items():
        write_atomic(path, data)

    print("Applied worker failure propagation changes.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
