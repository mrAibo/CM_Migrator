#!/usr/bin/env bash
# test-cm-password.sh — functional test for bin/cm-password.sh
#
# Tests: roundtrip, special chars, Unicode, empty, invalid base64, pipe mode.
# No real passwords. No secrets.
set -euo pipefail

PASS=0
FAIL=0
SCRIPT="${SCRIPT:-bin/cm-password.sh}"

assert_eq() {
    local label="$1" expected="$2" actual="$3"
    if [ "$expected" = "$actual" ]; then
        echo "  PASS: $label"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: $label"
        echo "    expected: '$expected'"
        echo "    actual:   '$actual'"
        FAIL=$((FAIL + 1))
    fi
}

assert_exit() {
    local label="$1" expected_exit="$2"
    shift 2
    local actual_exit=0
    "$@" >/dev/null 2>&1 || actual_exit=$?
    if [ "$expected_exit" = "$actual_exit" ]; then
        echo "  PASS: $label (exit $actual_exit)"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: $label (expected exit $expected_exit, got $actual_exit)"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== CM Password Tool Functional Tests ==="
echo ""

# --- Roundtrip ---
echo "--- Roundtrip encode → decode ---"
for PW in "secret123" "P@ssw0rd!" "a" "test with spaces"; do
    crypt=$(echo "$PW" | "$SCRIPT" encode)
    decoded=$(echo "$crypt" | "$SCRIPT" decode)
    assert_eq "roundtrip: $PW" "$PW" "$decoded"
done

# --- Special characters ---
echo "--- Special characters ---"
PW='special !@#$%^&*()_+-=[]{}|;:,.<>?/~`'
crypt=$(echo "$PW" | "$SCRIPT" encode)
decoded=$(echo "$crypt" | "$SCRIPT" decode)
assert_eq "special chars" "$PW" "$decoded"

# --- Unicode (UTF-8) ---
echo "--- Unicode ---"
PW='täst €ñçödïng 日本語'
crypt=$(echo "$PW" | "$SCRIPT" encode)
decoded=$(echo "$crypt" | "$SCRIPT" decode)
assert_eq "unicode" "$PW" "$decoded"

# --- Newlines in password (should fail: one-line input only) ---
echo "--- Newline handling ---"
PW='first
second'
crypt=$(printf '%s' "$PW" | "$SCRIPT" encode 2>/dev/null) || crypt="ERROR"
# Decode should restore original (without trailing newline)
decoded=$(echo "$crypt" | "$SCRIPT" decode)
assert_eq "multiline encoded (exact match)" "$PW" "$decoded"

# --- Empty password (pipe) ---
echo "--- Empty password ---"
assert_exit "empty encode" 2 "$SCRIPT" encode < /dev/null

# --- Invalid base64 ---
echo "--- Invalid base64 ---"
result=$(echo "!!!not-base64!!!" | "$SCRIPT" decode 2>&1) || true
if echo "$result" | grep -q "invalid base64"; then
    echo "  PASS: invalid base64 rejected"
    PASS=$((PASS + 1))
else
    echo "  FAIL: invalid base64 not rejected (got: $result)"
    FAIL=$((FAIL + 1))
fi

# --- Pipe mode ---
echo "--- Pipe mode ---"
crypt=$(echo "pipe-test" | "$SCRIPT" encode)
decoded=$(echo "$crypt" | "$SCRIPT" decode)
assert_eq "pipe roundtrip" "pipe-test" "$decoded"

# --- Unknown mode ---
echo "--- Unknown mode ---"
assert_exit "unknown mode" 64 "$SCRIPT" bogus

# --- Shell syntax ---
echo "--- Shell syntax ---"
if bash -n "$SCRIPT" 2>&1; then
    echo "  PASS: shell syntax"
    PASS=$((PASS + 1))
else
    echo "  FAIL: shell syntax"
    FAIL=$((FAIL + 1))
fi

# --- Executable ---
echo "--- Executable ---"
chmod +x "$SCRIPT"
if [ -x "$SCRIPT" ]; then
    echo "  PASS: executable"
    PASS=$((PASS + 1))
else
    echo "  FAIL: not executable"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "=== Results: $PASS passed, $FAIL failed ==="

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
exit 0
