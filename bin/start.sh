#!/bin/bash
# =============================================================================
# IBM CM Migrator - Start Script v2.2.1
# Starts the migration process with optimized JVM settings
# =============================================================================

set -e

cd "$(dirname "$0")/.."

echo "============================================="
echo " IBM CM Migrator v2.2.1"
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
    echo "ERROR: Java not found!"
    exit 1
fi

# ── Terminal width for OperatorConsole ──────────────────────────────

if [ -t 1 ]; then  # stdout is a terminal (where dashboard renders)
    COLS="$(tput cols 2>/dev/null)" || true
    if [ -z "$COLS" ] || ! [ "$COLS" -gt 0 ] 2>/dev/null; then
        read -r _ COLS < <(stty size 2>/dev/null) || true
    fi
    if [ -n "$COLS" ] && [ "$COLS" -gt 0 ] 2>/dev/null; then
        JAVA_OPTS="-Dcm.migrator.console.columns=$COLS"
        echo "Terminal columns: $COLS"
    fi
fi

# ── Pretty-mode: file-only log4j2 config ──────────────────────────
if [ "${CM_CONSOLE_MODE:-}" = "pretty" ]; then
    PRETTY_CONFIG="$(pwd)/conf/log4j2-pretty.xml"
    if [ -f "$PRETTY_CONFIG" ]; then
        JAVA_OPTS="$JAVA_OPTS -Dlog4j.configurationFile=$PRETTY_CONFIG"
        echo "Log4j2: pretty mode (file-only)"
    fi
fi

# 2. Library Path
CM_LIB_PATH="/opt/IBM/cm87_api/lib"
if [ -d "$CM_LIB_PATH" ]; then
    export LD_LIBRARY_PATH="${LD_LIBRARY_PATH:+$LD_LIBRARY_PATH:}$CM_LIB_PATH"
    echo "IBM CM Library: $CM_LIB_PATH"
else
    echo "WARNING: CM Library path not found: $CM_LIB_PATH"
fi

# 3. Classpath
CONF_DIR="$(pwd)/conf"
CP="bin/cm-migrator.jar:lib/*:$CONF_DIR:/opt/IBM/cm87_api/cmgmt/connectors/"
if [ ! -f "bin/cm-migrator.jar" ]; then
    echo "ERROR: cm-migrator.jar not found! Run ./bin/compile.sh first."
    exit 1
fi

# 4. Configuration
CONFIG_FILE="conf/migration.properties"
if [ "$1" != "" ]; then
    CONFIG_FILE="$1"
fi
if [ ! -f "$CONFIG_FILE" ]; then
    echo "ERROR: Configuration file not found: $CONFIG_FILE"
    exit 1
fi

echo "---------------------------------------------"
echo "Config file: $CONFIG_FILE"
echo "Classpath: $CP"

# 5. JVM Options
JAVA_OPTS="$JAVA_OPTS -Xms1g -Xmx4g -Djava.io.tmpdir=/dev/shm"
JAVA_OPTS="$JAVA_OPTS -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
JAVA_OPTS="$JAVA_OPTS -XX:+UseStringDeduplication"

if [ -n "${CM_JAVA_OPTS:-}" ]; then
    echo "Appending custom JVM options from CM_JAVA_OPTS"
    JAVA_OPTS="$JAVA_OPTS $CM_JAVA_OPTS"
fi

echo "JVM Options: $JAVA_OPTS"
echo "---------------------------------------------"

# 6. Execute
"$JAVA_CMD" $JAVA_OPTS -Dcm.home="$CONF_DIR" -cp "$CP" com.ibm.ecm.migration.Main "$CONFIG_FILE"

exit_code=$?
if [ $exit_code -eq 0 ]; then
    echo "============================================="
    echo " Migration completed successfully!"
    echo "============================================="
else
    echo "============================================="
    echo " Migration finished with errors (exit: $exit_code)"
    echo "============================================="
fi

exit $exit_code
