#!/usr/bin/env bash
# cm-password.sh — encode/decode passwords for CM Migrator _CRYPT properties
#
# Algorithm (compatible with MigrationConfig.decodePW()):
#   encode:  plaintext → reverse string → base64
#   decode:  _CRYPT value → base64 decode → reverse string → base64 decode
#
# Security: This is OBFUSCATION, not encryption. _CRYPT values are reversible.
#           Always chmod 600 your migration.properties file.

set -euo pipefail

usage() {
    echo "Usage: $0 encode|decode"
    echo ""
    echo "  encode  Read plaintext password from stdin (use 'read -s' or pipe),"
    echo "          write _CRYPT value to stdout."
    echo "  decode  Read _CRYPT value from stdin, write plaintext to stdout."
    echo ""
    echo "Examples:"
    echo "  $0 encode    # prompts for password, prints _CRYPT"
    echo "  echo 'xyz' | $0 encode"
    echo "  $0 decode    # prompts for _CRYPT, prints plaintext"
    echo ""
    echo "Security: _CRYPT is obfuscation, not encryption. Treat as plaintext."
    exit 64
}

if [ $# -ne 1 ]; then
    usage
fi

MODE="$1"

# --- encode: base64 → reverse → base64 ---
encode() {
    local input
    input="$(cat)"

    if [ -z "$input" ]; then
        echo "ERROR: empty password" >&2
        exit 2
    fi

    # Step 1: base64-encode the plaintext
    local step1
    step1="$(printf '%s' "$input" | base64 -w0)"

    # Step 2: reverse that base64 string
    local reversed
    reversed="$(printf '%s' "$step1" | rev)"

    # Step 3: base64-encode the reversed string
    printf '%s' "$reversed" | base64 -w0
    echo
}

# --- decode: base64 → reverse → base64 ---
decode() {
    local input
    input="$(cat)"

    if [ -z "$input" ]; then
        echo "ERROR: empty _CRYPT value" >&2
        exit 2
    fi

    # Step 1: base64 decode
    local step1
    if ! step1="$(printf '%s' "$input" | base64 -d 2>/dev/null)"; then
        echo "ERROR: invalid base64 input" >&2
        exit 3
    fi

    # Step 2: reverse
    local reversed
    reversed="$(printf '%s' "$step1" | rev)"

    # Step 3: base64 decode again
    local step2
    if ! step2="$(printf '%s' "$reversed" | base64 -d 2>/dev/null)"; then
        # Fallback: first base64 was already plaintext (matches Java behavior)
        printf '%s' "$step1"
        echo
        exit 0
    fi

    printf '%s' "$step2"
    echo
}

case "$MODE" in
    encode)
        # If stdin is a terminal, prompt with read -s
        if [ -t 0 ]; then
            printf 'Password: ' >&2
            IFS= read -rs PASSWORD
            echo >&2  # newline after hidden input
            printf '%s' "$PASSWORD" | encode
        else
            encode
        fi
        ;;
    decode)
        if [ -t 0 ]; then
            printf '_CRYPT: ' >&2
            IFS= read -rs CRYPT_VALUE
            echo >&2
            printf '%s' "$CRYPT_VALUE" | decode
        else
            decode
        fi
        ;;
    -h|--help|help)
        usage
        ;;
    *)
        echo "ERROR: unknown mode '$MODE'. Use encode or decode." >&2
        usage
        ;;
esac
