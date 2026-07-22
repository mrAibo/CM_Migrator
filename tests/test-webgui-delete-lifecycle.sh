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

catch_match = re.search(r'catch \(Exception e\) \{(?P<body>.*?)\n\s*\} finally', run_operation, re.DOTALL)
if not catch_match:
    raise SystemExit("FAIL: runOperation must catch operation failures")
catch_body = catch_match.group("body")
for required in (
    'state.status = "FAILED";',
    "state.message = e.getMessage();",
    'state.appendLog("Operation failed: " + e.getMessage());',
    'logger.error("WebGUI operation failed", e);',
):
    if required not in catch_body:
        raise SystemExit("FAIL: WebGUI operation failure is not fully recorded")

main_method = main[main.find("public static void main"):main.find("public static void startMigration")]
if "startMigration(configPath);" not in main_method or "System.exit(1);" not in main_method:
    raise SystemExit("FAIL: CLI error exit behavior changed")

print("WebGuiDeleteLifecycleTest: PASS")
PY
