#!/bin/bash
# Shared preflight guard for verifier entry points.
#
# The Java verifier now distinguishes EXISTS / NOT_FOUND / ERROR. This launcher
# guard remains deliberate operational containment: every enabled cascade delete
# is blocked until the IBM live acceptance and explicit operational approval.

read_java_property_normalized() {
    local config_file="$1"
    local normalized_key="$2"

    awk -v wanted="$normalized_key" '
        /^[[:space:]]*[#!]/ { next }
        /^[[:space:]]*$/ { next }
        {
            line=$0
            sub(/\r$/, "", line)
            sub(/^[[:space:]]+/, "", line)

            equals=index(line, "=")
            colon=index(line, ":")

            if (equals==0 && colon==0) next
            if (equals==0) separator=colon
            else if (colon==0) separator=equals
            else separator=(equals < colon ? equals : colon)

            key=substr(line, 1, separator - 1)
            value=substr(line, separator + 1)

            gsub(/[[:space:]_]/, "", key)
            key=toupper(key)
            sub(/^[[:space:]]+/, "", value)
            sub(/[[:space:]]+$/, "", value)

            if (key==wanted) found=tolower(value)
        }
        END { if (found != "") print found }
    ' "$config_file"
}

assert_cascade_delete_disabled() {
    local config_file="$1"
    local value

    if [ ! -f "$config_file" ]; then
        printf 'ERROR: Configuration file not found: %s\n' "$config_file" >&2
        return 2
    fi

    value="$(read_java_property_normalized "$config_file" CASCADEDELETEONMISSING)"

    case "$value" in
        true|yes|1|on)
            cat >&2 <<EOF
ERROR: Unsafe configuration refused.

CASCADE_DELETE_ON_MISSING is enabled in:
  $config_file

The Java verifier implements fail-closed EXISTS / NOT_FOUND / ERROR handling,
but cascade deletion has not yet passed the required IBM live acceptance and
explicit operational approval. The launcher therefore blocks every activation.

Set CASCADE_DELETE_ON_MISSING=false and run verification again.
EOF
            return 2
            ;;
    esac

    return 0
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    if [ "$#" -ne 1 ]; then
        printf 'Usage: %s <migration.properties>\n' "$0" >&2
        exit 64
    fi

    assert_cascade_delete_disabled "$1"
fi
