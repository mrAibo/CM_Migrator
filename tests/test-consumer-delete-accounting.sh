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

bash bin/compile.sh >/dev/null
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
"$javac_cmd" --release 11 -cp "bin/cm-migrator.jar:lib/*" -d "$work_dir" \
    tests/java/com/ibm/ecm/migration/ConsumerDeleteAccountingTest.java
"$java_cmd" -cp "$work_dir:bin/cm-migrator.jar:lib/*" \
    com.ibm.ecm.migration.ConsumerDeleteAccountingTest
