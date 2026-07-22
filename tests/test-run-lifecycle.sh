#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

javac_cmd="${JAVAC_CMD:-javac}"
java_cmd="${JAVA_CMD:-java}"

"$javac_cmd" --release 11 -d "$work_dir" \
    src/com/ibm/ecm/migration/ShutdownCoordinator.java \
    src/com/ibm/ecm/migration/WebGuiRunSlot.java \
    src/com/ibm/ecm/migration/CliShutdownLifecycle.java \
    tests/java/com/ibm/ecm/migration/WebGuiRunSlotTest.java \
    tests/java/com/ibm/ecm/migration/CliShutdownLifecycleTest.java

"$java_cmd" -cp "$work_dir" com.ibm.ecm.migration.WebGuiRunSlotTest
"$java_cmd" -cp "$work_dir" com.ibm.ecm.migration.CliShutdownLifecycleTest

python3 - <<'PY'
from pathlib import Path

web = Path("src/com/ibm/ecm/migration/WebServer.java").read_text()
verifier = Path("src/com/ibm/ecm/migration/Verifier.java").read_text()
main = Path("src/com/ibm/ecm/migration/Main.java").read_text()

handler_start = web.find("private class OperationHandler")
handler_end = web.find("private void runOperation", handler_start)
handler = web[handler_start:handler_end]
run_start = handler_end
run_end = web.find("// PROCESS HANDLER", run_start)
run = web[run_start:run_end]

reserve = handler.find("WebGuiRunSlot.reserve(migrationRunning)")
state = handler.find("new ProcessState(")
registry = handler.find("processRegistry.put(")
current = handler.find("currentProcess.set(")
thread_start = handler.find("newThread.start()")
if min(reserve, state, registry, current, thread_start) < 0:
    raise SystemExit("FAIL: could not locate WebGUI reservation/start markers")
if not reserve < state < registry < current < thread_start:
    raise SystemExit("FAIL: run slot must be reserved before state publication and thread start")
if "migrationRunning.set(true)" in run:
    raise SystemExit("FAIL: worker thread must not reserve the WebGUI run slot")
if "ShutdownCoordinator.reset()" in run:
    raise SystemExit("FAIL: runOperation must not reset global shutdown state")
if "rollbackBeforeThreadStart" not in handler:
    raise SystemExit("FAIL: pre-thread-start failures must roll back the reservation")

run_cli = verifier[verifier.find("public static int runCli"):verifier.find("public static void run(")]
embedded = verifier[verifier.find("public static void run("):]
if "CliShutdownLifecycle" not in run_cli:
    raise SystemExit("FAIL: CLI path must own its bounded shutdown lifecycle")
if "CliShutdownLifecycle" in embedded or "addShutdownHook" in embedded:
    raise SystemExit("FAIL: embedded Verifier.run must remain hook-free")
if "ShutdownCoordinator.reset()" in main[main.find("public static void startMigration"):]:
    raise SystemExit("FAIL: embedded Main.startMigration must not reset global shutdown state")

main_cli = main[main.find("public static void main"):main.find("public static void startMigration")]
migration_core = main[main.find("public static void startMigration"):]
if "CliShutdownLifecycle" not in main_cli or "lifecycle.register()" not in main_cli:
    raise SystemExit("FAIL: CLI Main.main must own one bounded shutdown lifecycle")
if "lifecycle.finish(terminationConfirmed)" not in main_cli:
    raise SystemExit("FAIL: CLI Main.main must signal completion and remove its hook")
if "addShutdownHook" in migration_core or "setupShutdownHook" in migration_core:
    raise SystemExit("FAIL: shared Main.startMigration must not register JVM shutdown hooks")

print("RunLifecycleStructureTest: PASS")
PY
