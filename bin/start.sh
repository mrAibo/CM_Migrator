#!/bin/bash
# =============================================================================
# IBM CM Migrator - Start Script v2.1.31
# Starts the migration process with optimized JVM settings
# =============================================================================

set -e  # Exit on error

# Ensure we are in the project root
cd "$(dirname "$0")/.."

echo "============================================="
echo " IBM CM Migrator v2.1.31"
echo "============================================="

# 1. Java Detection
# Check for local Java 11 first
if [ -f "java_env/jdk-11.0.2/bin/java" ]; then
    JAVA_CMD="java_env/jdk-11.0.2/bin/java"
    echo "Using local Java: $JAVA_CMD"
else
    JAVA_CMD="java"
    echo "Using system Java: $(which java 2>/dev/null || echo 'not found')"
fi

# Validate Java
if ! "$JAVA_CMD" -version &>/dev/null; then
    echo "ERROR: Java not found or not executable!"
    exit 1
fi

# 2. Library Path for Native IBM CM libs
# This is CRITICAL for IBM CM API to work (JNI)
CM_LIB_PATH="/opt/IBM/cm87_api/lib"
if [ -d "$CM_LIB_PATH" ]; then
    export LD_LIBRARY_PATH="${LD_LIBRARY_PATH:+$LD_LIBRARY_PATH:}$CM_LIB_PATH"
    echo "IBM CM Library: $CM_LIB_PATH"
else
    echo "WARNING: CM Library path not found: $CM_LIB_PATH"
    echo "         Native SDK operations may fail."
fi

# 3. Classpath
# Include the JAR, all libs, and the conf directory (for log4j2.xml etc)
# Use absolute path for conf to help IBM CM API find cmbicmsrvs.ini
CONF_DIR="$(pwd)/conf"
# CP="bin/cm-migrator.jar:lib/*:$CONF_DIR"
CP="bin/cm-migrator.jar:lib/*:$CONF_DIR:/opt/IBM/cm87_api/cmgmt/connectors/"
# Validate JAR exists
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
echo "Config directory: $CONF_DIR"

# 5. JVM Options
# Optimized for high-throughput migration workloads
# -Xms1g:  Start with 1GB RAM
# -Xmx4g:  Allow up to 4GB RAM (adjust for 500M+ items: -Xmx32g)
# -Djava.io.tmpdir=/dev/shm: Use RAM Disk for temporary files
JAVA_OPTS="-Xms1g -Xmx4g -Djava.io.tmpdir=/dev/shm"

# G1GC for better pause times and throughput
JAVA_OPTS="$JAVA_OPTS -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# String deduplication (saves heap for repetitive attribute names)
JAVA_OPTS="$JAVA_OPTS -XX:+UseStringDeduplication"

# Round 10: CM_JAVA_OPTS wird ANGEHÄNGT statt zu ersetzen, damit die hier
# konfigurierten Defaults (Heap, GC, tmpdir, StringDeduplication) erhalten bleiben.
# WICHTIG: Diesen Block genau einmal vorhalten — Doppel-Append ist ein bekannter Bug.
if [ -n "${CM_JAVA_OPTS:-}" ]; then
    echo "Appending custom JVM options from CM_JAVA_OPTS"
    JAVA_OPTS="$JAVA_OPTS $CM_JAVA_OPTS"
fi

echo "JVM Options: $JAVA_OPTS"
echo "---------------------------------------------"

# 6. Execute
# "$JAVA_CMD" $JAVA_OPTS -cp "$CP" com.ibm.ecm.migration.Main "$CONFIG_FILE"
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
