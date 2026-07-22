#!/bin/bash

# =============================================================================
# CM Migrator - Run Script
# =============================================================================

# --- Configuration ---
if [ -z "$JAVA_HOME" ]; then
    JAVA_HOME=/usr/lib64/jvm/java-11-openjdk
fi

CM_SDK_HOME=/opt/IBM/db2cmv8/lib
APP_HOME=$(pwd)
LIB_DIR=$APP_HOME/lib
CP="$APP_HOME/cm-migrator.jar:$APP_HOME/bin"

# --- Build Classpath ---
# Add IBM CM JARs
if [ -d "$CM_SDK_HOME" ]; then
    for jar in $CM_SDK_HOME/*.jar; do
        CP=$CP:$jar
    done
fi

# Add Local Lib JARs
for jar in $LIB_DIR/*.jar; do
    CP=$CP:$jar
done

# --- Run ---
echo "Starting FastBatchItemMigrator..."
"$JAVA_HOME/bin/java" -cp "$CP" com.example.migrator.FastBatchItemMigrator "$@"
