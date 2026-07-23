#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

javac_cmd="${JAVAC_CMD:-javac}"
java_cmd="${JAVA_CMD:-java}"

echo "=== ConsoleDashboardTest ==="

# Compile test and its direct (dependency-free) sources only
"$javac_cmd" -d "$work_dir" \
    src/com/ibm/ecm/migration/OperatorConsole.java \
    src/com/ibm/ecm/migration/ConsoleUI.java \
    tests/java/com/ibm/ecm/migration/ConsoleDashboardTest.java

# Run test; pass/fail based on exit code
"$java_cmd" -cp "$work_dir" com.ibm.ecm.migration.ConsoleDashboardTest
exit_code=$?

echo ""
if [ $exit_code -eq 0 ]; then
    echo "PASS: Console Dashboard test"
else
    echo "FAIL: Console Dashboard test (exit $exit_code)"
fi
exit $exit_code
