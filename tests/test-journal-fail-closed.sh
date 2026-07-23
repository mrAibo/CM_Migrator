#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

JAVA_HOME="${JAVA_HOME:-/tmp/cm-migrator-temurin-17.0.19+10}"

"$JAVA_HOME/bin/javac" -cp "$ROOT/lib/h2-2.2.224.jar" -d "$OUT" \
  "$ROOT/src/com/ibm/ecm/migration/MigrationJournal.java" \
  "$ROOT/src/com/ibm/ecm/migration/MigrationItem.java" \
  "$ROOT/tests/stubs/org/apache/logging/log4j/Logger.java" \
  "$ROOT/tests/stubs/org/apache/logging/log4j/LogManager.java" \
  "$ROOT/tests/java/com/ibm/ecm/migration/MigrationJournalFailClosedTest.java"

"$JAVA_HOME/bin/java" -cp "$OUT:$ROOT/lib/h2-2.2.224.jar" \
  com.ibm.ecm.migration.MigrationJournalFailClosedTest
