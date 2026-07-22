#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/.." && pwd)"
out_dir="$(mktemp -d)"
trap 'rm -rf "$out_dir"' EXIT

javac -d "$out_dir" \
    "$root_dir/src/com/ibm/ecm/migration/SourceLookupStatus.java" \
    "$root_dir/src/com/ibm/ecm/migration/SourceLookupAction.java" \
    "$root_dir/src/com/ibm/ecm/migration/SourceLookupDecision.java" \
    "$root_dir/tests/java/com/ibm/ecm/migration/SourceLookupDecisionTest.java"

java -cp "$out_dir" com.ibm.ecm.migration.SourceLookupDecisionTest
