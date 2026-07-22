#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

javac_cmd="${JAVAC_CMD:-javac}"
java_cmd="${JAVA_CMD:-java}"
secret='SNAPSHOT_SECRET_SENTINEL_9b731'

if [[ -d lib ]]; then
    "$javac_cmd" --release 11 -d "$work_dir" -cp "lib/*" -sourcepath src \
        src/com/ibm/ecm/migration/RunConfigSnapshot.java \
        tests/java/com/ibm/ecm/migration/RunConfigSnapshotTest.java

    output="$($java_cmd -cp "$work_dir:lib/*" com.ibm.ecm.migration.RunConfigSnapshotTest 2>&1)"
    printf '%s\n' "$output"
    if [[ "$output" == *"$secret"* ]]; then
        printf 'FAIL: snapshot secret reached test output/logs\n' >&2
        exit 1
    fi
else
    printf 'SKIP: Java runtime test requires lib/ directory (excluded in CI sparse checkout)\n'
fi

python3 - <<'PY'
from pathlib import Path

web = Path("src/com/ibm/ecm/migration/WebServer.java").read_text()
run_start = web.find("private void runOperation(")
run_end = web.find("// PROCESS HANDLER", run_start)
state_start = web.find("private String processStateJson(")
state_end = web.find("private ProcessState resolveProcess", state_start)
if min(run_start, run_end, state_start, state_end) < 0:
    raise SystemExit("FAIL: could not isolate WebGUI snapshot/status flow")
run = web[run_start:run_end]
state_json = web[state_start:state_end]

if "RunConfigSnapshot.create(" not in run:
    raise SystemExit("FAIL: WebGUI must use the secure snapshot creator")
if "RunConfigSnapshot.cleanupIfSafe(runConfig, releaseRunSlot)" not in run:
    raise SystemExit("FAIL: snapshot cleanup must follow confirmed termination")
if "WEBGUI_RUN_ID" in web or 'props.setProperty("WEBGUI_SOURCE_CONFIG"' in web:
    raise SystemExit("FAIL: unused run/path metadata must not be set as snapshot properties")
if "runConfig" in state_json or "webgui-runs" in state_json:
    raise SystemExit("FAIL: snapshot path must not reach process status JSON")
if "props.store" in web[web.find("private Path createRunConfigSnapshot"):state_start]:
    raise SystemExit("FAIL: legacy default-permission snapshot writer still present")

snapshot = Path("src/com/ibm/ecm/migration/RunConfigSnapshot.java").read_text()
for marker in (
    "OWNER_READ", "OWNER_WRITE", "OWNER_EXECUTE",
    "setReadable(false, false)", "setWritable(false, false)",
    "POSIX file permissions are unavailable",
):
    if marker not in snapshot:
        raise SystemExit(f"FAIL: snapshot permission/fallback marker missing: {marker}")

print("RunConfigSnapshotStructureTest: PASS")
PY
