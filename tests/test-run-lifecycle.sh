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
    src/com/ibm/ecm/migration/RunTerminationException.java \
    tests/java/com/ibm/ecm/migration/WebGuiRunSlotTest.java \
    tests/java/com/ibm/ecm/migration/CliShutdownLifecycleTest.java \
    tests/java/com/ibm/ecm/migration/CliExitCleanupTest.java

"$java_cmd" -cp "$work_dir" com.ibm.ecm.migration.WebGuiRunSlotTest
"$java_cmd" -cp "$work_dir" com.ibm.ecm.migration.CliShutdownLifecycleTest
"$java_cmd" -cp "$work_dir" com.ibm.ecm.migration.CliExitCleanupTest

python3 - <<'PY'
from pathlib import Path

web = Path("src/com/ibm/ecm/migration/WebServer.java").read_text()
verifier = Path("src/com/ibm/ecm/migration/Verifier.java").read_text()
main = Path("src/com/ibm/ecm/migration/Main.java").read_text()

# --- WebGUI ---
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

# --- CLI exit/cleanup contract ---

# Main.runCli must exist and must not call System.exit
main_cli = main[main.find("static int runCli"):main.find("public static void startMigration")]
main_main = main[main.find("public static void main"):main.find("static int runCli")]

if "static int runCli" not in main:
    raise SystemExit("FAIL: Main.runCli must exist")
if "System.exit" in main_cli:
    raise SystemExit("FAIL: Main.runCli must not call System.exit — finish() must run before exit")
if "System.exit" not in main_main or "runCli(args)" not in main_main:
    raise SystemExit("FAIL: Main.main must be a thin System.exit(runCli(args)) adapter")
if "lifecycle.finish(terminationConfirmed)" not in main_cli:
    raise SystemExit("FAIL: Main.runCli must call lifecycle.finish in finally")
if "terminationConfirmed = false" not in main_cli:
    raise SystemExit("FAIL: generic Exception catch must set terminationConfirmed = false")
if "// ponytail:" not in main and "return exitCode" not in main_cli:
    pass  # allowed: return exitCode is the clean signal

# Verifier.runCli already fine — just verify it still exists and has no System.exit
verifier_cli = verifier[verifier.find("public static int runCli"):verifier.find("public static void run(")]
if "System.exit" in verifier_cli:
    raise SystemExit("FAIL: Verifier.runCli must not call System.exit")
if "lifecycle.finish(terminationConfirmed)" not in verifier_cli:
    raise SystemExit("FAIL: Verifier.runCli must call lifecycle.finish in finally")

# Core must remain hook-free
embedded = verifier[verifier.find("public static void run("):]
if "CliShutdownLifecycle" in embedded or "addShutdownHook" in embedded:
    raise SystemExit("FAIL: embedded Verifier.run must remain hook-free")

migration_core = main[main.find("public static void startMigration"):]
if "addShutdownHook" in migration_core or "setupShutdownHook" in migration_core:
    raise SystemExit("FAIL: shared Main.startMigration must not register JVM shutdown hooks")

print("RunLifecycleStructureTest: PASS")
PY
