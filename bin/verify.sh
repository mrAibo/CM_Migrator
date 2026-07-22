#!/bin/bash
# =============================================================================
# IBM CM Migrator - Verifier Script (Round 10)
# Verifies migrated items by comparing SHA-256 checksums.
#
# Diagnose-Schalter (System Properties / Env-Variablen):
#   CM_CONSOLE_MODE        -> -Dcm.migrator.console.mode=...      (default: plain)
#   CM_VERIFY_SORT_MODE    -> -Dcm.migrator.verify.sortMode=...   (default: migrator)
#   CM_VERIFY_WORKLIST_MODE-> -Dcm.migrator.verify.worklistMode=..(default: default)
#   CM_VERIFY_AUTO_MARK    -> -Dcm.migrator.verify.autoMarkForRemigration=...
#                              (NUR weitergegeben, wenn explizit gesetzt)
#   CM_JAVA_OPTS           -> zusätzliche JVM-Flags (werden ANGEHÄNGT, nicht ersetzt)
# =============================================================================

set -e  # Exit on error

# Ensure we are in the project root
cd "$(dirname "$0")/.."

echo "============================================="
echo " IBM CM Migrator - Verifier (Round 10)"
echo "============================================="

# 1. Java Detection
if [ -f "java_env/jdk-11.0.2/bin/java" ]; then
    JAVA_CMD="java_env/jdk-11.0.2/bin/java"
    echo "Using local Java: $JAVA_CMD"
else
    JAVA_CMD="java"
    echo "Using system Java: $(which java 2>/dev/null || echo 'not found')"
fi

if ! "$JAVA_CMD" -version &>/dev/null; then
    echo "ERROR: Java not found or not executable!"
    exit 1
fi

# 2. Library Path for Native IBM CM libs
CM_LIB_PATH="/opt/IBM/cm87_api/lib"
if [ -d "$CM_LIB_PATH" ]; then
    export LD_LIBRARY_PATH="${LD_LIBRARY_PATH:+$LD_LIBRARY_PATH:}$CM_LIB_PATH"
else
    echo "WARNING: CM Library path not found: $CM_LIB_PATH"
    echo "         Native SDK operations may fail."
fi

# 3. Classpath
CONF_DIR="$(pwd)/conf"
CP="bin/cm-migrator.jar:lib/*:$CONF_DIR"

if [ ! -f "bin/cm-migrator.jar" ]; then
    echo "ERROR: cm-migrator.jar not found! Run ./bin/compile.sh first."
    exit 1
fi

# 4. Configuration
CONFIG_FILE="conf/migration.properties"
if [ "${1:-}" != "" ]; then
    CONFIG_FILE="$1"
fi

if [ ! -f "$CONFIG_FILE" ]; then
    echo "ERROR: Configuration file not found: $CONFIG_FILE"
    exit 1
fi

# Operational containment: Java already returns explicit EXISTS / NOT_FOUND /
# ERROR states, but every enabled cascade delete remains blocked until IBM live
# acceptance and explicit operational approval.
# shellcheck source=bin/cascade-delete-guard.sh
source "bin/cascade-delete-guard.sh"
assert_cascade_delete_disabled "$CONFIG_FILE"

echo "Starting Verifier with config: $CONFIG_FILE"
echo "Classpath: $CP"

# 5. JVM Options (an start.sh angeglichen)
JAVA_OPTS="-Xms1g -Xmx4g -Djava.io.tmpdir=/dev/shm"
JAVA_OPTS="$JAVA_OPTS -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# 6. Round 10: Diagnose-Schalter mit konservativen Defaults.
#    Default sortMode = migrator (verifier-side false-positives vermeiden).
#    Default worklistMode = default (bisheriges Verhalten).
#    Default consoleMode = plain (verify-Output ist meist getee't / non-tty).
#    autoMarkForRemigration wird NICHT angehängt, wenn die Variable nicht gesetzt ist —
#    so bleibt der Wert aus migration.properties wirksam, ohne ihn versehentlich zu kippen.

CM_CONSOLE_MODE="${CM_CONSOLE_MODE:-plain}"
CM_VERIFY_SORT_MODE="${CM_VERIFY_SORT_MODE:-migrator}"
CM_VERIFY_WORKLIST_MODE="${CM_VERIFY_WORKLIST_MODE:-default}"

JAVA_OPTS="$JAVA_OPTS -Dcm.migrator.console.mode=$CM_CONSOLE_MODE"
JAVA_OPTS="$JAVA_OPTS -Dcm.migrator.verify.sortMode=$CM_VERIFY_SORT_MODE"
JAVA_OPTS="$JAVA_OPTS -Dcm.migrator.verify.worklistMode=$CM_VERIFY_WORKLIST_MODE"

# Nur weitergeben, wenn explizit gesetzt:
if [ -n "${CM_VERIFY_AUTO_MARK+x}" ]; then
    JAVA_OPTS="$JAVA_OPTS -Dcm.migrator.verify.autoMarkForRemigration=$CM_VERIFY_AUTO_MARK"
fi

# CM_JAVA_OPTS ANHÄNGEN (nicht ersetzen).
if [ -n "${CM_JAVA_OPTS:-}" ]; then
    echo "Appending custom JVM options from CM_JAVA_OPTS"
    JAVA_OPTS="$JAVA_OPTS $CM_JAVA_OPTS"
fi

echo "JVM Options: $JAVA_OPTS"
echo "---------------------------------------------"

# 7. Execute
"$JAVA_CMD" $JAVA_OPTS -Dcm.home="$CONF_DIR" -cp "$CP" com.ibm.ecm.migration.Verifier "$CONFIG_FILE"

exit_code=$?
if [ $exit_code -eq 0 ]; then
    echo "============================================="
    echo " Verification process finished (exit 0)."
    echo " NOTE: exit 0 means the verifier ran without"
    echo " crashing — it does NOT confirm zero mismatches."
    echo " Check VERIFICATION_LOG / reports/ for details."
    echo "============================================="
else
    echo "============================================="
    echo " Verification finished with errors (exit: $exit_code)"
    echo "============================================="
fi

exit $exit_code
