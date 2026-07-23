#!/bin/bash
# =============================================================================
# IBM CM Migrator - Operator Wrapper (Round 10)
#
# Single entry point that selects safe defaults and prevents foot-guns:
#   - normal migration is refused if duplicate-risk rows exist
#   - verification defaults to sortMode=migrator (matches ItemMigrator hashing)
#   - autoMarkForRemigration is not enabled by default
#   - safe workflow runs migration -> verify -> optional non-OK reverify
#     and never starts a remigration automatically
#
# Usage:
#   ./bin/cm-run.sh <mode> <config>
#   ./bin/cm-run.sh --help
#
# See README_SAFE_WORKFLOW.md for the full operator guide.
# =============================================================================

set -euo pipefail
# Prevent bash job-control termination notices for managed background services.
set +m 2>/dev/null || true
# -----------------------------------------------------------------------------
# Background Process Management
# -----------------------------------------------------------------------------
declare -a BG_PIDS=()

cleanup_bg_procs() {
    if [ ${#BG_PIDS[@]} -gt 0 ]; then
        printf "\nINFO:  Cleaning up background services...\n" >&2

        for pid in "${BG_PIDS[@]}"; do
            if kill -0 "$pid" 2>/dev/null; then
                kill -TERM "$pid" 2>/dev/null || true
            fi
        done

        for pid in "${BG_PIDS[@]}"; do
            local stopped=0

            for i in 1 2 3 4 5; do
                if ! kill -0 "$pid" 2>/dev/null; then
                    stopped=1
                    break
                fi
                sleep 1
            done

            if [ "$stopped" -eq 0 ] && kill -0 "$pid" 2>/dev/null; then
                printf "WARN:  Background service PID=%s did not stop after TERM; sending KILL\n" "$pid" >&2
                kill -KILL "$pid" 2>/dev/null || true
            fi

            # Wichtig:
            # Background-Prozess einsammeln, damit Bash keine
            # "Terminated ..." Job-Meldung mehr ausgibt.
            wait "$pid" 2>/dev/null || true
        done

        BG_PIDS=()
    fi
}
trap cleanup_bg_procs EXIT

# Always anchor at project root.
cd "$(dirname "$0")/.."

# -----------------------------------------------------------------------------
# Help
# -----------------------------------------------------------------------------

print_help() {
    cat <<'EOF'
IBM CM Migrator - cm-run.sh
===========================

Usage:
  ./bin/cm-run.sh <mode> <config-file> [options]
  ./bin/cm-run.sh --help

Modes:
  migration              Run migration only. Refuses to start when AUDIT_LOG
                         contains FAILED rows that already have DEST_ITEM_ID
                         (would create duplicate destination items).
  delete                 Run delete only. Skips destination duplicate checks but
                         requires OPERATION_MODE=DELETE in the config file.
  verification           Run verifier with safe defaults
                         (sortMode=migrator, worklistMode=default,
                          autoMark=false). Does NOT mutate AUDIT_LOG.
  verification-nonok     Re-verify ONLY existing VERIFICATION_LOG rows with
                         STATUS != 'OK'. No migration, no remigration.
  safe                   Recommended: migration -> verification -> optional
                         non-OK reverify -> repair of false-positive FAILED
                         rows (only when nonOk reverify confirmed OK).
  status                 Read-only summary per ItemType from AUDIT_LOG and
                         VERIFICATION_LOG, plus duplicate-risk count.

Aliases:
  migrate         -> migration
  verify          -> verification
  verify-nonok    -> verification-nonok
  nonok           -> verification-nonok
  check           -> status

Examples:
  # Recommended end-to-end run:
  ./bin/cm-run.sh safe          conf/migration.properties

  # Pretty migration on an interactive terminal:
  ./bin/cm-run.sh migration     conf/migration.properties

  # Plain verification (tee-friendly):
  ./bin/cm-run.sh verification  conf/migration.properties \
      2>&1 | tee verify.log

  # Re-verify only existing non-OK rows (after a verifier false-positive run):
  ./bin/cm-run.sh verification-nonok conf/migration.properties

  # Read-only status:
  ./bin/cm-run.sh status conf/migration.properties

Environment overrides (optional, passed through to start.sh / verify.sh):
  CM_CONSOLE_MODE=pretty|plain
       migration mode default: pretty
       verification modes default: plain
  CM_VERIFY_SORT_MODE=migrator|verifier
       verification default: migrator (matches ItemMigrator part ordering)
  CM_VERIFY_WORKLIST_MODE=default|nonOk
       verification default: default (filters AUDIT_LOG.STATUS='SUCCESS')
       verification-nonok forces: nonOk
  CM_VERIFY_AUTO_MARK=true|false
       verification default: false (do not mark FAILED on mismatch)
  CM_JAVA_OPTS="..."
       additional JVM flags, appended (not replacing) the script defaults
  CM_H2_LOCK_TIMEOUT_MS=<int>
       wrapper H2 lock timeout in ms (default: 5000). Applied to status/safe
       helper queries so a locked journal returns an error quickly instead
       of hanging. Has NO effect on the migrator/verifier JVM connections.

Options:
  --webgui                  Start the WebGUI server in the background
  --monitor                 Start the Dashboard Monitor in the background
  --webgui-port <port>      Specify WebGUI HTTP port (default: 8080)
  --monitor-port <port>     Specify Monitor HTTP port (default: 8000)
EOF
}

# -----------------------------------------------------------------------------
# Helpers
# -----------------------------------------------------------------------------

PROJECT_ROOT="$(pwd)"

err()  { printf "ERROR: %s\n" "$*" >&2; }
warn() { printf "WARN:  %s\n" "$*" >&2; }
info() { printf "INFO:  %s\n" "$*"; }

print_service_url() {
    local label="$1"
    local port="$2"
    local host
    local user_name

    host="$(hostname -f 2>/dev/null || hostname 2>/dev/null || printf "localhost")"
    user_name="${USER:-$(whoami 2>/dev/null || printf "user")}"

    info "$label URL local:  http://127.0.0.1:${port}/"

    if [ "$host" != "localhost" ] && [ "$host" != "127.0.0.1" ]; then
        info "$label URL server: http://${host}:${port}/"
    fi

    info "$label SSH tunnel: ssh -L ${port}:127.0.0.1:${port} ${user_name}@${host}"
    info "$label tunnel URL: http://127.0.0.1:${port}/"
}

get_primary_ip() {
    local ip_addr=""

    if command -v ip >/dev/null 2>&1; then
        ip_addr="$(ip -o -4 route get 1.1.1.1 2>/dev/null \
            | awk '{for (i=1;i<=NF;i++) if ($i=="src") {print $(i+1); exit}}')"
    fi

    if [ -z "$ip_addr" ] && command -v hostname >/dev/null 2>&1; then
        ip_addr="$(hostname -I 2>/dev/null | awk '{print $1}')"
    fi

    if [ -z "$ip_addr" ]; then
        ip_addr="$(hostname -f 2>/dev/null || hostname 2>/dev/null || printf "127.0.0.1")"
    fi

    printf "%s" "$ip_addr"
}

print_monitor_access_urls() {
    local port="$1"
    local host
    local base

    host="$(get_primary_ip)"
    base="http://${host}:${port}"

    echo "Access URLs:"
    printf "  Dashboard:    %s/status.html\n" "$base"
    printf "  Reports:      %s/migration_report.html\n" "$base"
    printf "  Verification: %s/verification_report.html\n" "$base"
}

port_in_use() {
    local port="$1"

    if command -v ss >/dev/null 2>&1; then
        ss -ltn "sport = :${port}" | awk 'NR > 1 { found=1 } END { exit found ? 0 : 1 }'
        return $?
    fi

    if command -v lsof >/dev/null 2>&1; then
        lsof -iTCP:"$port" -sTCP:LISTEN -n -P >/dev/null 2>&1
        return $?
    fi

    if command -v fuser >/dev/null 2>&1; then
        fuser "${port}/tcp" >/dev/null 2>&1
        return $?
    fi

    return 1
}

print_port_owner_hint() {
    local port="$1"
    local option_name="${2:---monitor-port}"

    warn "Port $port is already in use."

    if command -v ss >/dev/null 2>&1; then
        ss -ltnp "sport = :${port}" >&2 || true
    elif command -v lsof >/dev/null 2>&1; then
        lsof -iTCP:"$port" -sTCP:LISTEN -n -P >&2 || true
    elif command -v fuser >/dev/null 2>&1; then
        fuser -v "${port}/tcp" >&2 || true
    fi

    warn "Stop the old process or use another port, e.g.: ${option_name} $((port + 1))"
}

kill_port_owner() {
    local port="$1"
    local label="${2:-service}"

    warn "$label port $port is already in use. Trying to stop old process..."

    if command -v fuser >/dev/null 2>&1; then
        fuser -TERM "${port}/tcp" >/dev/null 2>&1 || true
        sleep 2

        if port_in_use "$port"; then
            warn "$label port $port still in use after TERM; sending KILL."
            fuser -k "${port}/tcp" >/dev/null 2>&1 || true
            sleep 1
        fi
    else
        warn "fuser not available; cannot auto-kill process on port $port."
        return 1
    fi

    if port_in_use "$port"; then
        warn "$label port $port is still in use after kill attempt."
        print_port_owner_hint "$port" "--monitor-port"
        return 1
    fi

    info "$label port $port is free now."
    return 0
}

# Parse a property value from a Java-properties file.
# Last occurrence wins (consistent with java.util.Properties behaviour).
# Round 11 (I5): supports both '=' and ':' as the key/value separator.
get_property() {
    local config_file="$1"; local key="$2"
    awk -v k="$key" '
        /^[[:space:]]*[#!]/ { next }
        /^[[:space:]]*$/ { next }
        {
            line=$0
            sub(/\r$/, "", line)
            sub(/^[[:space:]]+/, "", line)

            ne=index(line, "=")
            nc=index(line, ":")

            if (ne==0 && nc==0) next
            if (ne==0) { n=nc }
            else if (nc==0) { n=ne }
            else { n=(ne<nc ? ne : nc) }

            kk=substr(line,1,n-1)
            vv=substr(line,n+1)

            sub(/[[:space:]]+$/, "", kk)
            sub(/^[[:space:]]+/, "", vv)
            sub(/[[:space:]]+$/, "", vv)
            sub(/\r$/, "", vv)

            if (kk==k) val=vv
        }
        END { if (val) print val }
    ' "$config_file"
}

# Resolve DB_PATH (default: ./data/migration_journal).
get_db_path() {
    local config_file="$1"
    local v
    v="$(get_property "$config_file" DB_PATH)"
    if [ -z "$v" ]; then v="$(get_property "$config_file" JOURNAL_DIR)"; fi
    if [ -z "$v" ]; then v="./data/migration_journal"; fi
    # Trim quotes if any.
    v="${v%\"}"; v="${v#\"}"
    printf "%s\n" "$v"
}

# Extract source ItemTypes from MIGRATEITEMTYPES / MIGRATE_ITEMTYPES.
# Supports SOURCE:DEST, SOURCE->DEST, plain SOURCE, comma-separated.
get_source_itemtypes() {
    local config_file="$1"
    local raw
    raw="$(get_property "$config_file" MIGRATEITEMTYPES)"
    if [ -z "$raw" ]; then raw="$(get_property "$config_file" MIGRATE_ITEMTYPES)"; fi
    if [ -z "$raw" ]; then return 0; fi

    # Split by ',', then cut at the EARLIEST of ':' or '->'.
    # Important:
    #   - printf MUST add a newline, otherwise a single entry without trailing
    #     newline may not be processed by `while read`.
    #   - strip CR for Windows/CRLF properties files.
    printf "%s\n" "$raw" | tr ',' '\n' | while IFS= read -r entry; do
        local e
        e="$(printf "%s" "$entry" | sed -e 's/\r$//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
        [ -z "$e" ] && continue

        local i_colon i_arrow cut
        i_colon="$(awk -v s="$e" 'BEGIN{ n=index(s,":");  print (n ? n : 99999) }')"
        i_arrow="$(awk -v s="$e" 'BEGIN{ n=index(s,"->"); print (n ? n : 99999) }')"

        if [ "$i_colon" -lt "$i_arrow" ] && [ "$i_colon" -lt 99999 ]; then
            cut="${e%%:*}"
        elif [ "$i_arrow" -lt 99999 ]; then
            cut="${e%%->*}"
        else
            cut="$e"
        fi

        printf "%s\n" "$cut" | sed -e 's/\r$//' -e 's/[[:space:]]*$//'
    done
}

# JDBC URLs.
h2_url_for_type_ro() {
    local db_path="$1"; local type="$2"
    printf "jdbc:h2:%s/journal_%s;IFEXISTS=TRUE;ACCESS_MODE_DATA=r\n" "$db_path" "$type"
}
h2_url_for_type_rw() {
    local db_path="$1"; local type="$2"
    printf "jdbc:h2:%s/journal_%s;IFEXISTS=TRUE\n" "$db_path" "$type"
}

# Round 12 (M3): SQL string literal helper. Doubles single quotes per
# SQL-92 escaping rules and wraps the result in single quotes. Used for any
# filesystem path embedded into SQL (notably CSVWRITE) so spaces, quotes
# and exotic chars do not break statements.
sql_string_literal() {
    local s="${1:-}"
    s="${s//\'/\'\'}"
    printf "'%s'" "$s"
}

# Round 12 (M6): bounded H2 lock timeout for wrapper helpers. Prevents
# `cm-run.sh status`/`safe` from hanging indefinitely when another process
# holds the journal. Operator may override via env.
CM_H2_LOCK_TIMEOUT_MS="${CM_H2_LOCK_TIMEOUT_MS:-5000}"

# Append ;LOCK_TIMEOUT=<ms> to a JDBC URL if not already present.
# Preserves any existing parameters (IFEXISTS, ACCESS_MODE_DATA, …).
with_h2_lock_timeout() {
    local url="$1"
    if printf "%s" "$url" | grep -qi 'LOCK_TIMEOUT='; then
        printf "%s" "$url"
    else
        printf "%s;LOCK_TIMEOUT=%s" "$url" "$CM_H2_LOCK_TIMEOUT_MS"
    fi
}

# Locate H2 jar via glob (avoids `ls | head` pipefail hazards).
locate_h2_jar() {
    local jar
    for j in lib/h2-*.jar; do
        if [ -f "$j" ]; then jar="$j"; break; fi
    done
    if [ -z "${jar:-}" ]; then
        err "H2 jar not found under lib/h2-*.jar"
        return 1
    fi
    printf "%s\n" "$jar"
}

# Round 11: Numeric guard. Echoes the input only if it parses as an integer,
# otherwise echoes 0 and warns. Used after every SQL count to keep `set -e`
# arithmetic safe.
to_int() {
    local v="${1:-}"
    if [[ "$v" =~ ^-?[0-9]+$ ]]; then
        printf "%s" "$v"
    else
        if [ -n "$v" ]; then warn "expected integer, got '$v' — coercing to 0"; fi
        printf "0"
    fi
}

# Run a single SQL statement via H2 Shell.
# Args: <jdbc-url> <sql>
# Round 11: stdout is returned; stderr is captured and surfaced as a warning
# when non-empty so locked DBs / SQL errors stop looking like clean zeros.
# Returns the H2 Shell exit code (0 on success).
h2_sql() {
    local url="$1"; local sql="$2"
    # Round 12 (M6): all wrapper H2 calls go through a bounded LOCK_TIMEOUT
    # so a locked DB returns an error within a few seconds instead of hanging.
    url="$(with_h2_lock_timeout "$url")"
    local jar; jar="$(locate_h2_jar)" || return 1
    local err_file
    err_file="$(mktemp 2>/dev/null || printf "/tmp/h2_sql_err.%s" "$$")"
    local rc=0
    "$JAVA_CMD" -cp "$jar" org.h2.tools.Shell \
        -url "$url" -user sa -password '' -sql "$sql" 2>"$err_file" || rc=$?
    if [ -s "$err_file" ]; then
        # Show first 5 stderr lines so the operator sees the real reason.
        warn "h2_sql stderr: $(head -n 5 "$err_file" | tr '\n' ' | ')"
    fi
    rm -f "$err_file"
    return $rc
}

# Run SQL that returns a single integer (first non-header numeric line).
# Round 11: returns "0" on no match (was empty before, which broke arithmetic).
h2_count() {
    local url="$1"; local sql="$2"
    local out rc=0
    out="$(h2_sql "$url" "$sql" || rc=$?)"
    if [ "$rc" -ne 0 ]; then
        printf "0\n"
        return 0
    fi
    # Pick the first pure-number line; "0" if none.
    local n
    n="$(printf "%s\n" "$out" | awk '/^[[:space:]]*-?[0-9]+[[:space:]]*$/ { print $1; exit }')"
    to_int "${n:-0}"
}

# Check whether the H2 db file exists for a given ItemType.
journal_exists() {
    local db_path="$1"; local type="$2"
    [ -f "$db_path/journal_${type}.mv.db" ]
}

# Return the audit/verify table names that actually exist in this DB.
# Round 11 (C3): prefer the schema the current Java writes (AUDIT_LOG /
# VERIFICATION_LOG); fall back to legacy AUDITLOG / VERIFICATIONLOG only if
# the active table is missing. This protects mixed-schema journals from
# being scanned via the wrong table.
# Sets globals: AUDIT_TABLE, VERIFY_TABLE, AUDIT_PK, AUDIT_DEST, AUDIT_CHK, VERIFY_PK.
detect_schema() {
    local url="$1"
    local tables rc=0
    tables="$(h2_sql "$url" "SHOW TABLES" || rc=$?)"
    if [ "$rc" -ne 0 ]; then tables=""; fi

    if printf "%s" "$tables" | grep -qiE '^[[:space:]]*AUDIT_LOG([[:space:]]|$)'; then
        AUDIT_TABLE="AUDIT_LOG";  AUDIT_PK="ITEM_ID"; AUDIT_DEST="DEST_ITEM_ID"; AUDIT_CHK="CHECKSUM"
    elif printf "%s" "$tables" | grep -qiE '^[[:space:]]*AUDITLOG([[:space:]]|$)'; then
        AUDIT_TABLE="AUDITLOG";   AUDIT_PK="ITEMID";  AUDIT_DEST="DESTITEMID";   AUDIT_CHK="CHECKSUM"
    else
        # Default to the active schema; subsequent SQL will surface "table not found"
        AUDIT_TABLE="AUDIT_LOG";  AUDIT_PK="ITEM_ID"; AUDIT_DEST="DEST_ITEM_ID"; AUDIT_CHK="CHECKSUM"
    fi
    if printf "%s" "$tables" | grep -qiE '^[[:space:]]*VERIFICATION_LOG([[:space:]]|$)'; then
        VERIFY_TABLE="VERIFICATION_LOG"; VERIFY_PK="ITEM_ID"
    elif printf "%s" "$tables" | grep -qiE '^[[:space:]]*VERIFICATIONLOG([[:space:]]|$)'; then
        VERIFY_TABLE="VERIFICATIONLOG"; VERIFY_PK="ITEMID"
    else
        VERIFY_TABLE="VERIFICATION_LOG"; VERIFY_PK="ITEM_ID"
    fi
}

# Java for h2 helpers.
detect_java() {
    if [ -f "java_env/jdk-11.0.2/bin/java" ]; then
        JAVA_CMD="java_env/jdk-11.0.2/bin/java"
    else
        JAVA_CMD="java"
    fi
    if ! "$JAVA_CMD" -version >/dev/null 2>&1; then
        err "Java not found or not executable. Install JDK 11+ or place it in java_env/jdk-11.0.2/."
        return 1
    fi
}

# -----------------------------------------------------------------------------
# Status / safety helpers
# -----------------------------------------------------------------------------

# Print AUDIT_LOG and VERIFICATION_LOG status counts plus duplicate-risk count.
show_status() {
    local config_file="$1"
    local db_path; db_path="$(get_db_path "$config_file")"
    local types_count=0
    info "Status from DB_PATH=$db_path"

    while IFS= read -r t; do
        [ -z "$t" ] && continue
        types_count=$((types_count+1))
        printf "\n--- ItemType: %s ---\n" "$t"
        if ! journal_exists "$db_path" "$t"; then
            printf "  No H2 DB found yet (%s).\n" "$db_path/journal_${t}.mv.db"
            continue
        fi
        local url; url="$(h2_url_for_type_ro "$db_path" "$t")"
        detect_schema "$url"

        printf "  AUDIT (%s):\n" "$AUDIT_TABLE"
        # Round 11 (I4): tolerate H2 errors during status — warn, don't abort.
        { h2_sql "$url" "SELECT STATUS, COUNT(*) AS N FROM $AUDIT_TABLE GROUP BY STATUS ORDER BY N DESC" \
            | sed 's/^/    /'; } || warn "audit status query failed for $t"

        printf "  VERIFY (%s):\n" "$VERIFY_TABLE"
        { h2_sql "$url" "SELECT STATUS, COUNT(*) AS N FROM $VERIFY_TABLE GROUP BY STATUS ORDER BY N DESC" \
            | sed 's/^/    /'; } || warn "verify status query failed for $t"

        local risk
        risk="$(h2_count "$url" \
            "SELECT COUNT(*) FROM $AUDIT_TABLE WHERE STATUS='FAILED' AND $AUDIT_DEST IS NOT NULL AND $AUDIT_DEST <> ''")"
        printf "  duplicate-risk rows (FAILED with %s set): %s\n" "$AUDIT_DEST" "${risk:-0}"
    done < <(get_source_itemtypes "$config_file")

    if [ "$types_count" -eq 0 ]; then
        warn "No MIGRATEITEMTYPES / MIGRATE_ITEMTYPES configured in $config_file"
    fi
}

# Globals filled by has_failed_with_dest:
RISK_TYPES=()
RISK_COUNTS=()
RISK_TOTAL=0

has_failed_with_dest() {
    local config_file="$1"
    local db_path; db_path="$(get_db_path "$config_file")"
    RISK_TYPES=()
    RISK_COUNTS=()
    RISK_TOTAL=0
    while IFS= read -r t; do
        [ -z "$t" ] && continue
        if ! journal_exists "$db_path" "$t"; then
            continue
        fi
        local url; url="$(h2_url_for_type_ro "$db_path" "$t")"
        detect_schema "$url"
        local n
        n="$(h2_count "$url" \
            "SELECT COUNT(*) FROM $AUDIT_TABLE WHERE STATUS='FAILED' AND $AUDIT_DEST IS NOT NULL AND $AUDIT_DEST <> ''")"
        n="${n:-0}"
        if [ "$n" -gt 0 ]; then
            RISK_TYPES+=("$t")
            RISK_COUNTS+=("$n")
            RISK_TOTAL=$((RISK_TOTAL + n))
        fi
    done < <(get_source_itemtypes "$config_file")
}

# Returns 0 if any VERIFICATION_LOG row with STATUS<>'OK' exists across types.
NONOK_TYPES=()
NONOK_COUNTS=()
NONOK_TOTAL=0
any_verification_nonok() {
    local config_file="$1"
    local db_path; db_path="$(get_db_path "$config_file")"
    NONOK_TYPES=()
    NONOK_COUNTS=()
    NONOK_TOTAL=0
    while IFS= read -r t; do
        [ -z "$t" ] && continue
        if ! journal_exists "$db_path" "$t"; then
            continue
        fi
        local url; url="$(h2_url_for_type_ro "$db_path" "$t")"
        detect_schema "$url"
        local n
        n="$(h2_count "$url" \
            "SELECT COUNT(*) FROM $VERIFY_TABLE WHERE STATUS <> 'OK'")"
        n="${n:-0}"
        if [ "$n" -gt 0 ]; then
            NONOK_TYPES+=("$t")
            NONOK_COUNTS+=("$n")
            NONOK_TOTAL=$((NONOK_TOTAL + n))
        fi
    done < <(get_source_itemtypes "$config_file")
    [ "$NONOK_TOTAL" -gt 0 ]
}

# Export non-OK item ids per type to reports/non_ok_ids_<type>_<ts>.csv
export_nonok_ids() {
    local config_file="$1"
    local db_path; db_path="$(get_db_path "$config_file")"
    local ts; ts="$(date +%Y%m%d_%H%M%S)"
    mkdir -p reports
    while IFS= read -r t; do
        [ -z "$t" ] && continue
        journal_exists "$db_path" "$t" || continue
        local url; url="$(h2_url_for_type_ro "$db_path" "$t")"
        detect_schema "$url"
        local out="reports/non_ok_ids_${t}_${ts}.csv"
        local out_abs="$PROJECT_ROOT/$out"
        # Round 11 (C1): schema-aware primary-key column ($VERIFY_PK).
        # Round 12 (M3): SQL-literal-quote both the file path and the inner
        # SELECT so spaces / single quotes in the project root cannot break SQL.
        local lit_path lit_select
        lit_path="$(sql_string_literal "$out_abs")"
        lit_select="$(sql_string_literal "SELECT $VERIFY_PK FROM $VERIFY_TABLE WHERE STATUS <> 'OK'")"
        local sql
        sql="CALL CSVWRITE($lit_path, $lit_select);"
        # CSVWRITE requires write access to the file path AND to the DB
        # (H2 refuses CSVWRITE under ACCESS_MODE_DATA=r).
        local rw_url; rw_url="$(h2_url_for_type_rw "$db_path" "$t")"
        local rc=0
        h2_sql "$rw_url" "$sql" >/dev/null || rc=$?
        if [ "$rc" -ne 0 ]; then
            warn "CSVWRITE failed for $t (rc=$rc) — see h2_sql warnings above for the actual error"
        fi
        if [ -f "$out" ]; then
            # CSVWRITE includes a header row; subtract 1 for the visible count (M2).
            local total visible
            total="$(wc -l < "$out" | tr -d ' ')"
            visible=$(( total > 0 ? total - 1 : 0 ))
            info "Exported non-OK IDs for $t -> $out ($visible row(s))"
        fi
    done < <(get_source_itemtypes "$config_file")
}

# Repair AUDIT_LOG.STATUS = SUCCESS for items that are now VERIFICATION_LOG.STATUS='OK'
# and were previously marked FAILED with DEST_ITEM_ID present.
repair_false_positive_audit_status() {
    local config_file="$1"
    local db_path; db_path="$(get_db_path "$config_file")"
    local total_repaired=0
    while IFS= read -r t; do
        [ -z "$t" ] && continue
        journal_exists "$db_path" "$t" || continue
        local url; url="$(h2_url_for_type_rw "$db_path" "$t")"
        detect_schema "$url"
        local upd_sql
        upd_sql="UPDATE $AUDIT_TABLE
                 SET STATUS='SUCCESS',
                     MESSAGE='Verified OK after automatic nonOk reverify; previous FAILED was verifier false-positive'
                 WHERE STATUS='FAILED'
                   AND $AUDIT_DEST IS NOT NULL AND $AUDIT_DEST <> ''
                   AND $AUDIT_CHK  IS NOT NULL AND $AUDIT_CHK  <> ''
                   AND $AUDIT_PK IN (SELECT $VERIFY_PK FROM $VERIFY_TABLE WHERE STATUS='OK')"
        # Run UPDATE. H2 Shell formats vary across versions:
        #   "(Update count: 354, 1405 ms)"
        #   "Update count: 354"
        #   "(N row(s) affected)"
        # Round 11 (C2): extract the FIRST integer from any line that contains
        # update-count vocabulary, regardless of surrounding punctuation.
        local out rc=0
        out="$(h2_sql "$url" "$upd_sql" || rc=$?)"
        local n
        if [ "$rc" -ne 0 ]; then
            n="unknown"
        else
            n="$(printf "%s" "$out" | awk '
                /[Uu]pdate count|row\(s\) affected|rows affected/ {
                    if (match($0, /[0-9]+/)) {
                        print substr($0, RSTART, RLENGTH)
                        exit
                    }
                }
            ')"
            if [ -z "${n:-}" ]; then n="unknown"; fi
        fi
        printf "  %s: repaired %s row(s)\n" "$t" "$n"
        if [[ "$n" =~ ^[0-9]+$ ]]; then
            total_repaired=$((total_repaired + n))
        fi
    done < <(get_source_itemtypes "$config_file")
    info "Total repaired AUDIT_LOG rows: $total_repaired (entries reported as 'unknown' are NOT counted)"
}

# -----------------------------------------------------------------------------
# Mode runners
# -----------------------------------------------------------------------------

run_migration() {
    local config_file="$1"
    # Round 11 (I6): allow caller (run_safe) to skip the duplicate-risk preflight
    # because it has already been performed once. Default = perform it.
    local skip_preflight="${2:-no}"
    info "Mode: migration  config: $config_file"

    if [ "$skip_preflight" != "skip-preflight" ]; then
        has_failed_with_dest "$config_file"
        if [ "$RISK_TOTAL" -gt 0 ]; then
            err "Unsafe to run normal migration: FAILED rows with existing DEST_ITEM_ID found."
            err "This may create duplicate destination items. Run verification-nonok or safe workflow first."
            for i in "${!RISK_TYPES[@]}"; do
                err "  ${RISK_TYPES[$i]}: ${RISK_COUNTS[$i]} duplicate-risk row(s)"
            done
            exit 2
        fi
    fi

    export CM_CONSOLE_MODE="${CM_CONSOLE_MODE:-pretty}"
    info "CM_CONSOLE_MODE=$CM_CONSOLE_MODE"
    bash ./bin/start.sh "$config_file"
}

run_delete() {
    local config_file="$1"
    info "Mode: delete  config: $config_file"

    local op_mode
    op_mode="$(get_property "$config_file" OPERATION_MODE | tr '[:lower:]' '[:upper:]')"
    if [ "$op_mode" != "DELETE" ]; then
        err "Aborted: OPERATION_MODE in $config_file is set to '$op_mode', not 'DELETE'."
        err "Please use a configuration file explicitly set to DELETE for this mode."
        exit 1
    fi

    # Wir überspringen den has_failed_with_dest Preflight-Check
    export CM_CONSOLE_MODE="${CM_CONSOLE_MODE:-pretty}"
    info "CM_CONSOLE_MODE=$CM_CONSOLE_MODE"
    bash ./bin/start.sh "$config_file"
}

run_verification() {
    local config_file="$1"
    info "Mode: verification  config: $config_file"

    # Round 11 (I7): wrapper modes set the safe defaults explicitly (incl.
    # autoMark=false), so the README defaults table is true to behaviour.
    export CM_CONSOLE_MODE="${CM_CONSOLE_MODE:-plain}"
    export CM_VERIFY_SORT_MODE="${CM_VERIFY_SORT_MODE:-migrator}"
    export CM_VERIFY_WORKLIST_MODE="${CM_VERIFY_WORKLIST_MODE:-default}"
    export CM_VERIFY_AUTO_MARK="${CM_VERIFY_AUTO_MARK:-false}"

    info "  console=$CM_CONSOLE_MODE sort=$CM_VERIFY_SORT_MODE worklist=$CM_VERIFY_WORKLIST_MODE autoMark=$CM_VERIFY_AUTO_MARK"
    bash ./bin/verify.sh "$config_file"
}

run_verification_nonok() {
    local config_file="$1"
    info "Mode: verification-nonok  config: $config_file"

    # Forced safe values — independent of any caller-set CM_VERIFY_* env.
    export CM_CONSOLE_MODE="${CM_CONSOLE_MODE:-plain}"
    export CM_VERIFY_SORT_MODE="migrator"
    export CM_VERIFY_WORKLIST_MODE="nonOk"
    export CM_VERIFY_AUTO_MARK="false"

    info "  console=$CM_CONSOLE_MODE sort=$CM_VERIFY_SORT_MODE worklist=$CM_VERIFY_WORKLIST_MODE autoMark=$CM_VERIFY_AUTO_MARK"
    bash ./bin/verify.sh "$config_file"
}

run_safe() {
    local config_file="$1"
    info "===================================================================="
    info "SAFE workflow: migration -> verification -> optional non-OK reverify"
    info "  config: $config_file"
    info "===================================================================="

    # Preflight 1: duplicate-risk.
    has_failed_with_dest "$config_file"
    if [ "$RISK_TOTAL" -gt 0 ]; then
        err "Refusing to run migration: duplicate-risk rows present."
        for i in "${!RISK_TYPES[@]}"; do
            err "  ${RISK_TYPES[$i]}: ${RISK_COUNTS[$i]} FAILED row(s) with DEST_ITEM_ID"
        done
        err ""
        err "Recommended next steps:"
        err "  ./bin/cm-run.sh verification-nonok $config_file"
        err "  ./bin/cm-run.sh status            $config_file"
        exit 2
    fi

    info ""
    info "Step 1/5: migration"
    # Round 11 (I6): preflight already run above — skip the duplicate scan inside run_migration.
    run_migration "$config_file" "skip-preflight"

    info ""
    info "Step 2/5: verification (defaults: sort=migrator, autoMark=false)"
    run_verification "$config_file"

    info ""
    info "Step 3/5: status"
    show_status "$config_file"

    info ""
    info "Step 4/5: scan VERIFICATION_LOG for non-OK rows"
    if ! any_verification_nonok "$config_file"; then
        info "All verifications OK. No non-OK reverify needed. SUCCESS."
        exit 0
    fi
    info "Non-OK rows found across types:"
    for i in "${!NONOK_TYPES[@]}"; do
        info "  ${NONOK_TYPES[$i]}: ${NONOK_COUNTS[$i]} non-OK row(s)"
    done

    info "Step 5/5: export non-OK IDs and run nonOk reverify"
    export_nonok_ids "$config_file"
    run_verification_nonok "$config_file"

    info ""
    info "Post-reverify status:"
    show_status "$config_file"

    if any_verification_nonok "$config_file"; then
        warn "Non-OK rows remain after nonOk reverify — these are real mismatches."
        warn "  Inspect: reports/verification_non_ok_<itemtype>.csv (written by Verifier)"
        warn "  Inspect: reports/non_ok_ids_<itemtype>_<ts>.csv (written by safe workflow)"
        warn "No remigration was started automatically."
        exit 2
    fi

    # Round 11 (M4): clearer wording — VERIFICATION_LOG keeps the historical
    # rows, the second pass updated their STATUS to OK; the repair step now
    # fixes the AUDIT_LOG side that was set to FAILED by autoMarkForRemigration
    # on the first verifier pass.
    info "Second-pass nonOk verification is now OK; repairing AUDIT_LOG false-positive FAILED statuses..."
    repair_false_positive_audit_status "$config_file"
    info "SAFE workflow finished. SUCCESS."
    exit 0
}

# -----------------------------------------------------------------------------
# Argument dispatch
# -----------------------------------------------------------------------------

if [ "$#" -eq 0 ] || [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ] || [ "${1:-}" = "help" ]; then
    print_help
    exit 0
fi

if [ "$#" -lt 2 ]; then
    err "Missing config-file argument."
    err ""
    print_help
    exit 1
fi

MODE="$1"
CONFIG_FILE="$2"
shift 2

# Parse additional arguments for webgui and monitor
START_WEBGUI=0
START_MONITOR=0
WEBGUI_PORT=8080
MONITOR_PORT=8000
KILL_EXISTING_MONITOR=1

while [[ $# -gt 0 ]]; do
    case $1 in
        --webgui)
            START_WEBGUI=1
            shift
            ;;
        --monitor)
            START_MONITOR=1
            shift
            ;;
        --webgui-port)
            WEBGUI_PORT="$2"
            shift 2
            ;;
        --monitor-port)
            MONITOR_PORT="$2"
            shift 2
            ;;
        *)
            err "Unknown option: $1"
            print_help
            exit 1
            ;;
    esac
done

validate_port() {
    local name="$1"
    local port="$2"

    if ! [[ "$port" =~ ^[0-9]+$ ]] || [ "$port" -lt 1 ] || [ "$port" -gt 65535 ]; then
        err "Invalid $name port: $port"
        exit 1
    fi
}

validate_port "WebGUI" "$WEBGUI_PORT"
validate_port "Monitor" "$MONITOR_PORT"

# -----------------------------------------------------------------------------
# Aliases  (resolve before preflight so mode checks work correctly)
# -----------------------------------------------------------------------------
case "$MODE" in
    migrate)            MODE="migration" ;;
    verify)             MODE="verification" ;;
    verify-nonok|nonok) MODE="verification-nonok" ;;
    check)              MODE="status" ;;
esac

# -----------------------------------------------------------------------------
# Common preconditions  (all checks before any service is started — Fix 1)
# -----------------------------------------------------------------------------
if [ ! -f "bin/cm-migrator.jar" ]; then
    err "bin/cm-migrator.jar not found. Run ./bin/compile.sh first."
    exit 1
fi
if [ ! -f "$CONFIG_FILE" ]; then
    err "Config file not found: $CONFIG_FILE"
    exit 1
fi
detect_java

# Need source ItemTypes for safety/preflight checks; only an explicit
# requirement for migration / safe / status / delete — verification modes can run
# without it (Verifier reads the property itself).
case "$MODE" in
    migration|safe|status|delete)
        if [ -z "$(get_source_itemtypes "$CONFIG_FILE")" ]; then
            err "No MIGRATEITEMTYPES / MIGRATE_ITEMTYPES key in $CONFIG_FILE."
            exit 1
        fi
        ;;
esac

# Validate mode is known before starting any services
case "$MODE" in
    migration|delete|verification|verification-nonok|safe|status) ;;
    *)
        err "Unknown mode: $MODE"
        err ""
        print_help
        exit 1
        ;;
esac

# -----------------------------------------------------------------------------
# Background service starter — Fix 2: liveness check after launch
# -----------------------------------------------------------------------------

# start_bg_service <label> <log-file> <cmd...>
# Launches a command in background, waits 1 s, verifies the process is still
# alive. If not, shows the last 30 lines of the log and aborts.
start_bg_service() {
    local label="$1"; local logfile="$2"; shift 2
    info "Starting $label in background..."
    mkdir -p "$(dirname "$logfile")"
    "$@" > "$logfile" 2>&1 &
    local pid=$!
    BG_PIDS+=("$pid")
    # Remove from bash job table so Bash does not print:
    # "Terminated \"$@\" > \"$logfile\" 2>&1"
    # Cleanup still tracks and kills the process by PID via BG_PIDS.
    disown "$pid" 2>/dev/null || disown %+ 2>/dev/null || true

    sleep 1
    if ! kill -0 "$pid" 2>/dev/null; then
        err "$label failed to start (process $pid exited immediately)."
        err "Last log output ($logfile):"
        tail -n 30 "$logfile" >&2 || true
        exit 1
    fi
    info "$label started (PID=$pid, log=$logfile)"
}

# Fix 4: Guard against --webgui / --monitor with 'status' mode.
# status completes in seconds; the EXIT trap would kill services before the
# operator can use them. Instead, we keep them alive and wait for Enter.
if [ "$MODE" = "status" ] && { [ "$START_WEBGUI" -eq 1 ] || [ "$START_MONITOR" -eq 1 ]; }; then
    warn "--webgui / --monitor with 'status' mode: services will be kept alive after"
    warn "the status output is shown. Press Enter to stop them."
fi

# Start services AFTER all preflight checks have passed
if [ "$START_WEBGUI" -eq 1 ]; then
    if port_in_use "$WEBGUI_PORT"; then
        print_port_owner_hint "$WEBGUI_PORT" "--webgui-port"
        exit 1
    fi

    start_bg_service "WebGUI" "logs/webgui.log" bash ./bin/webgui.sh --port "$WEBGUI_PORT"
    print_service_url "WebGUI" "$WEBGUI_PORT"
fi

if [ "$START_MONITOR" -eq 1 ]; then
    if port_in_use "$MONITOR_PORT"; then
        if [ "$KILL_EXISTING_MONITOR" -eq 1 ]; then
            kill_port_owner "$MONITOR_PORT" "Monitor" || exit 1
        else
            print_port_owner_hint "$MONITOR_PORT" "--monitor-port"
            exit 1
        fi
    fi

    start_bg_service "Monitor" "logs/monitor.log" bash ./bin/monitor.sh --port "$MONITOR_PORT"
    print_service_url "Monitor" "$MONITOR_PORT"
    print_monitor_access_urls "$MONITOR_PORT"
fi

# -----------------------------------------------------------------------------
# Mode dispatch
# -----------------------------------------------------------------------------

case "$MODE" in
    migration)
        run_migration "$CONFIG_FILE"
        ;;
    delete)
        run_delete "$CONFIG_FILE"
        ;;
    verification)
        run_verification "$CONFIG_FILE"
        ;;
    verification-nonok)
        run_verification_nonok "$CONFIG_FILE"
        ;;
    safe)
        run_safe "$CONFIG_FILE"
        ;;
    status)
        show_status "$CONFIG_FILE"
        # Fix 4: If background services are running, keep them alive until Enter
        if [ ${#BG_PIDS[@]} -gt 0 ]; then
            echo ""
            info "Background services are running. Press Enter to stop them."
            read -r _ignored || true
        fi
        ;;
esac
