#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

python3 - <<'PY'
from pathlib import Path
import re

web = Path("src/com/ibm/ecm/migration/WebServer.java").read_text()
main = Path("src/com/ibm/ecm/migration/Main.java").read_text()

start = web.find("private void runOperation(")
end = web.find("// PROCESS HANDLER", start)
if start < 0 or end < 0:
    raise SystemExit("FAIL: could not isolate WebServer.runOperation")
run_operation = web[start:end]

delete_match = re.search(
    r'else if \("delete"\.equals\(mode\)\) \{(?P<body>.*?)\n\s*\} else \{',
    run_operation,
    re.DOTALL,
)
if not delete_match:
    raise SystemExit("FAIL: could not isolate WebGUI delete branch")
delete_body = delete_match.group("body")

if "Main.main(" in delete_body:
    raise SystemExit("FAIL: WebGUI delete path must not call Main.main")
if "Main.startMigration(runConfigFile);" not in delete_body:
    raise SystemExit("FAIL: WebGUI delete path must use exception-based Main.startMigration")

typed_catch = re.search(
    r'catch \(RunTerminationException e\) \{(?P<body>.*?)\n\s*\} catch \(InterruptedException e\)',
    run_operation,
    re.DOTALL,
)
if not typed_catch:
    raise SystemExit("FAIL: runOperation must catch typed terminal outcomes")
typed_body = typed_catch.group("body")
for required in (
    "state.status = e.getWebStatus();",
    "state.message = webMessageFor(e.getReason());",
    "releaseRunSlot = e.isTerminationConfirmed();",
):
    if required not in typed_body:
        raise SystemExit("FAIL: WebGUI typed outcome is not fully recorded")

catch_match = re.search(r'catch \(Exception e\) \{(?P<body>.*?)\n\s*\} finally', run_operation, re.DOTALL)
if not catch_match:
    raise SystemExit("FAIL: runOperation must catch operation failures")
catch_body = catch_match.group("body")
for required in (
    'state.status = "FAILED";',
    'state.message = "Operation failed; see server logs";',
    "state.appendLog(state.message);",
    'logger.error("WebGUI operation failed", e);',
):
    if required not in catch_body:
        raise SystemExit("FAIL: WebGUI operation failure is not safely recorded")
if "e.getMessage()" in catch_body:
    raise SystemExit("FAIL: technical exception details must not reach WebGUI state/log JSON")

if "Verifier.main(" in run_operation or run_operation.count("Verifier.run(runConfigFile);") < 2:
    raise SystemExit("FAIL: WebGUI verification must use the shared throwing core")

main_method = main[main.find("public static void main"):main.find("public static void startMigration")]
main_cli = main[main.find("static int runCli"):main.find("public static void startMigration")]
if "startMigration" not in main_cli:
    raise SystemExit("FAIL: CLI must call the throwing migration core")
if "runCli(args)" not in main_method or "System.exit" not in main_method:
    raise SystemExit("FAIL: Main.main must be a thin System.exit(runCli(args)) adapter")
if "System.exit" in main_cli:
    raise SystemExit("FAIL: Main.runCli must not call System.exit — finish() must run before exit")
if "terminationConfirmed = false" not in main_cli and "terminationConfirmed = false" not in Path("src/com/ibm/ecm/migration/CliLifecycleRunner.java").read_text():
    raise SystemExit("FAIL: generic Exception catch must set terminationConfirmed=false")
if "lifecycle.finish(terminationConfirmed)" not in main_cli and "lifecycle.finish(terminationConfirmed)" not in Path("src/com/ibm/ecm/migration/CliLifecycleRunner.java").read_text():
    raise SystemExit("FAIL: lifecycle.finish must be called before returning exit code")

print("WebGuiDeleteLifecycleTest: PASS")
PY
