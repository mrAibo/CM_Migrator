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
    src/com/ibm/ecm/migration/CliLifecycleRunner.java \
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

# Main.runCli must delegate to production CliLifecycleRunner
main_cli = main[main.find("static int runCli"):main.find("public static void startMigration")]
main_main = main[main.find("public static void main"):main.find("static int runCli")]
if "CliLifecycleRunner.executeCli" not in main_cli:
    raise SystemExit("FAIL: Main.runCli must delegate to CliLifecycleRunner.executeCli")
# CliLifecycleRunner must contain finish(), must not call System.exit
runner_src = Path("src/com/ibm/ecm/migration/CliLifecycleRunner.java").read_text()
if "lifecycle.finish(terminationConfirmed)" not in runner_src:
    raise SystemExit("FAIL: CliLifecycleRunner must call lifecycle.finish in finally")
if "System.exit" in runner_src:
    raise SystemExit("FAIL: CliLifecycleRunner must not call System.exit")
if "CliRunResult" not in runner_src:
    raise SystemExit("FAIL: CliLifecycleRunner must return a CliRunResult carrying the failure")
if "log4j" in runner_src or "Logger" in runner_src:
    raise SystemExit("FAIL: CliLifecycleRunner must remain dependency-free")
# Main.runCli must log from the result
if "Migration terminated" not in main_cli:
    raise SystemExit("FAIL: Main.runCli must log RunTerminationException")
if "Migration failed" not in main_cli:
    raise SystemExit("FAIL: Main.runCli must log generic exceptions")
if "result.exitCode()" not in main_cli:
    raise SystemExit("FAIL: Main.runCli must return the exit code from the result")
if "main" not in main or "runCli(args)" not in main_main or "System.exit" not in main_main:
    raise SystemExit("FAIL: Main.main must be a thin adapter delegating to runCli")

# CliExitCleanupTest must use CliLifecycleRunner, not its own copy
exit_test = Path("tests/java/com/ibm/ecm/migration/CliExitCleanupTest.java").read_text()
if "CliLifecycleRunner.executeCli" not in exit_test:
    raise SystemExit("FAIL: CliExitCleanupTest must call the production CliLifecycleRunner.executeCli")
if "static int executeCli" in exit_test:
    raise SystemExit("FAIL: CliExitCleanupTest must not define its own executeCli copy")
exit_test_no_comments = '\n'.join(
    line for line in exit_test.split('\n') if not line.strip().startswith('//'))
if "lifecycle.register()" in exit_test_no_comments:
    raise SystemExit("FAIL: CliExitCleanupTest must not manually register the lifecycle")
if "assertSame" not in exit_test:
    raise SystemExit("FAIL: CliExitCleanupTest must use assertSame to verify exception identity")
if "result.exitCode()" not in exit_test:
    raise SystemExit("FAIL: CliExitCleanupTest must read exit code from CliRunResult")


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
