#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

javac -d "$OUT" \
  "$ROOT/src/com/ibm/ecm/migration/WorkerFailureState.java" \
  "$ROOT/tests/java/com/ibm/ecm/migration/WorkerFailureStateTest.java"

java -cp "$OUT" com.ibm.ecm.migration.WorkerFailureStateTest
