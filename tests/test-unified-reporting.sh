#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

# Portable Java detection — independent resolution per command.
# Priority: explicit JAVAC_CMD/JAVA_CMD → JAVA_HOME → local java_env → PATH
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

# Verify both commands are executable
"$javac_cmd" -version >/dev/null 2>&1 || { echo "ERROR: javac not found or not executable: $javac_cmd"; exit 1; }
"$java_cmd" -version >/dev/null 2>&1 || { echo "ERROR: java not found or not executable: $java_cmd"; exit 1; }

echo "=== UnifiedReportingTest ==="

# --- Create fake mail transport executables ---
# These are placed in a dir prepended to PATH so detectMailCommand() finds them.
mail_bin="$work_dir/mail-bin"
mkdir -p "$mail_bin"

# Fake mutt: records received -a arguments, exits 0
cat > "$mail_bin/mutt" << 'MFEOF'
#!/bin/sh
echo "$@" >> "$(dirname "$0")/mutt_args.log"
exit 0
MFEOF
chmod +x "$mail_bin/mutt"

# Fake mailx: exits 0 (no attachment support)
cat > "$mail_bin/mailx" << 'MXEOF'
#!/bin/sh
echo "$@" >> "$(dirname "$0")/mailx_args.log"
exit 0
MXEOF
chmod +x "$mail_bin/mailx"

# Export path to fake scripts for the Java test
export FAKE_MAIL_BIN="$mail_bin"

# Prepend fake bin to PATH so Runtime.exec("which mutt") finds it
export PATH="$mail_bin:$PATH"

# Source files needed (dependency chain for the test)
srcs=(
    # Core model
    src/com/ibm/ecm/migration/UnifiedReport.java
    src/com/ibm/ecm/migration/DeliveryResult.java
    # Console (needed by ReportRenderer for VERSION)
    src/com/ibm/ecm/migration/ConsoleUI.java
    src/com/ibm/ecm/migration/OperatorConsole.java
    # Renderer + delivery
    src/com/ibm/ecm/migration/ReportRenderer.java
    src/com/ibm/ecm/migration/AuditProtocolGenerator.java
    src/com/ibm/ecm/migration/ReportDeliveryService.java
    # Collector
    src/com/ibm/ecm/migration/ReportDataCollector.java
    # MigrationStats (needed by collector + compat)
    src/com/ibm/ecm/migration/MigrationStats.java
    # Config (needed by collector + compat)
    src/com/ibm/ecm/migration/MigrationConfig.java
    # Old compat classes
    src/com/ibm/ecm/migration/ReportGenerator.java
    src/com/ibm/ecm/migration/ProtocolReportGenerator.java
    src/com/ibm/ecm/migration/EmailNotifier.java
    # Journal + item (needed by ProtocolReportGenerator)
    src/com/ibm/ecm/migration/MigrationJournal.java
    src/com/ibm/ecm/migration/MigrationItem.java
    # Log4j stubs
    tests/stubs/org/apache/logging/log4j/Logger.java
    tests/stubs/org/apache/logging/log4j/LogManager.java
    # The test itself
    tests/java/com/ibm/ecm/migration/UnifiedReportingTest.java
)

cp_args=()
for s in "${srcs[@]}"; do
    cp_args+=("$s")
done

# Include H2 jar for compilation (needed by MigrationJournal imports)
h2_jar="lib/h2-2.2.224.jar"
h2_cp="$work_dir"
if [ -f "$h2_jar" ]; then
    h2_cp="$work_dir:$h2_jar"
fi

"$javac_cmd" -d "$work_dir" -cp "$h2_cp" "${cp_args[@]}"

# Run test; pass/fail based on exit code
"$java_cmd" -cp "$h2_cp" com.ibm.ecm.migration.UnifiedReportingTest
exit_code=$?

echo ""
if [ $exit_code -eq 0 ]; then
    echo "PASS: Unified Reporting test"
else
    echo "FAIL: Unified Reporting test (exit $exit_code)"
fi
exit $exit_code
