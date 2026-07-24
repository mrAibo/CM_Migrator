#!/bin/bash

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

# shellcheck source=bin/cascade-delete-guard.sh
source "$PROJECT_ROOT/bin/cascade-delete-guard.sh"

assert_allowed() {
    local name="$1"
    local content="$2"
    local file="$TMP_DIR/$name.properties"

    printf '%s\n' "$content" > "$file"
    assert_cascade_delete_disabled "$file"
}

assert_blocked() {
    local name="$1"
    local content="$2"
    local file="$TMP_DIR/$name.properties"
    local rc

    printf '%s\n' "$content" > "$file"

    set +e
    assert_cascade_delete_disabled "$file" >/dev/null 2>&1
    rc=$?
    set -e

    if [ "$rc" -ne 2 ]; then
        printf 'FAIL: %s expected exit 2, got %s\n' "$name" "$rc" >&2
        exit 1
    fi
}

assert_allowed "missing" "THREAD_COUNT=5"
assert_allowed "explicit-false" "CASCADE_DELETE_ON_MISSING=false"
assert_allowed "commented" $'# CASCADE_DELETE_ON_MISSING=true\nCASCADE_DELETE_ON_MISSING=false'

assert_blocked "explicit-true" "CASCADE_DELETE_ON_MISSING=true"
assert_blocked "fuzzy-key" "CASCADEDELETEONMISSING : YES"
assert_blocked "last-value-wins" $'CASCADE_DELETE_ON_MISSING=false\nCASCADE_DELETE_ON_MISSING=on'
assert_blocked "case-insensitive" "cascade_delete_on_missing=TrUe"

printf 'PASS: cascade delete guard\n'
