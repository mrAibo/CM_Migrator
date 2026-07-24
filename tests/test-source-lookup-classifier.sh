#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

javac -d "$work_dir" \
    src/com/ibm/ecm/migration/SourceLookupStatus.java \
    src/com/ibm/ecm/migration/SourceLookupClassifier.java \
    tests/java/com/ibm/ecm/migration/SourceLookupClassifierTest.java

java -cp "$work_dir" com.ibm.ecm.migration.SourceLookupClassifierTest
