#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -n "${JAVAC_CMD:-}" ]; then
    javac_cmd="$JAVAC_CMD"
elif [ -n "${JAVA_HOME:-}" ]; then
    javac_cmd="$JAVA_HOME/bin/javac"
elif [ -f "java_env/jdk-11.0.2/bin/javac" ]; then
    javac_cmd="java_env/jdk-11.0.2/bin/javac"
else
    javac_cmd="javac"
fi

if [ -n "${JAVA_CMD:-}" ]; then
    java_cmd="$JAVA_CMD"
elif [ -n "${JAVA_HOME:-}" ]; then
    java_cmd="$JAVA_HOME/bin/java"
elif [ -f "java_env/jdk-11.0.2/bin/java" ]; then
    java_cmd="java_env/jdk-11.0.2/bin/java"
else
    java_cmd="java"
fi

"$javac_cmd" -version >/dev/null 2>&1 || { echo "ERROR: javac not found: $javac_cmd"; exit 1; }
"$java_cmd" -version >/dev/null 2>&1 || { echo "ERROR: java not found: $java_cmd"; exit 1; }

bash bin/compile.sh >/dev/null
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
"$javac_cmd" --release 11 -cp "bin/cm-migrator.jar:lib/*" -d "$work_dir" \
    tests/java/com/ibm/ecm/migration/ProducerDeleteScopeTest.java
"$java_cmd" -cp "$work_dir:bin/cm-migrator.jar:lib/*" \
    com.ibm.ecm.migration.ProducerDeleteScopeTest

python3 - <<'PY'
from pathlib import Path

producer = Path("src/com/ibm/ecm/migration/Producer.java").read_text()
main = Path("src/com/ibm/ecm/migration/Main.java").read_text()

checks = {
    "discovery task is guarded": "discoveryExecutor.submit(workerFailureState.guard(" in producer,
    "producer top-level is guarded": "workerExecutor.submit(workerFailureState.guard(\n                producer" in main,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("FAIL: " + ", ".join(failed))
print(f"ProducerWorkerGuardContract: {len(checks)} passed, 0 failed")
PY
