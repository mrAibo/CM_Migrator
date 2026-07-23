#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
javac_cmd="${JAVAC_CMD:-javac}"
java_cmd="${JAVA_CMD:-java}"

echo "=== SinglePassTransitionTest ==="
"$javac_cmd" -d "$work_dir" \
    src/com/ibm/ecm/migration/OperatorConsole.java \
    src/com/ibm/ecm/migration/ConsoleUI.java \
    tests/java/com/ibm/ecm/migration/SinglePassTransitionTest.java
"$java_cmd" -cp "$work_dir" com.ibm.ecm.migration.SinglePassTransitionTest
exit_code=$?
echo ""
if [ $exit_code -eq 0 ]; then
    echo "PASS: SinglePassTransitionTest"
else
    echo "FAIL: SinglePassTransitionTest (exit $exit_code)"
fi
exit $exit_code