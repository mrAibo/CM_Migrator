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

"$javac_cmd" -d "$work_dir" \
    src/com/ibm/ecm/migration/SourceLookupStatus.java \
    src/com/ibm/ecm/migration/SourceLookupClassifier.java \
    tests/java/com/ibm/ecm/migration/SourceLookupClassifierTest.java

java -cp "$work_dir" com.ibm.ecm.migration.SourceLookupClassifierTest
