#!/bin/bash
# =============================================================================
# CM Migrator v2.2 - WebGUI Starter
# =============================================================================
# Startet den eingebetteten HTTP-Server für das Web-Dashboard.
#
# Usage:
#   ./bin/webgui.sh              # Standard: Port 8080
#   ./bin/webgui.sh --port 9000  # Alternativer Port
#
# Environment:
#   CM_JAVA_OPTS   additional JVM flags (e.g. -Dcm.migrator.webgui.bindAll=true)
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR"

echo "=============================================="
echo " CM Migrator v2.2 - WebGUI Launcher"
echo "=============================================="

# Java finden
if [ -d "java_env/jdk-11.0.2" ]; then
    JAVA_HOME="$PROJECT_DIR/java_env/jdk-11.0.2"
    JAVA_CMD="$JAVA_HOME/bin/java"
    echo "[✓] Using local Java: $JAVA_CMD"
elif command -v java &> /dev/null; then
    JAVA_CMD="java"
    echo "[✓] Using system Java: $(which java)"
else
    echo "[ERROR] Java not found!"
    exit 1
fi

# JAR prüfen
JAR_FILE="bin/cm-migrator.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo "[ERROR] $JAR_FILE not found! Run ./bin/compile.sh first."
    exit 1
fi

# P0 containment: protect the standard WebGUI configuration path from the
# verifier's unsafe boolean source-existence check. The definitive Java fix
# will replace this temporary launcher barrier.
DEFAULT_MIGRATION_CONFIG="conf/migration.properties"
if [ -f "$DEFAULT_MIGRATION_CONFIG" ]; then
    # shellcheck source=bin/cascade-delete-guard.sh
    source "bin/cascade-delete-guard.sh"
    assert_cascade_delete_disabled "$DEFAULT_MIGRATION_CONFIG"
fi

# Port aus Argumenten
PORT=8080
BIND_ALL=0
BIND_ADDRESS=""
while [[ $# -gt 0 ]]; do
    case $1 in
        --port)
            PORT="$2"
            shift 2
            ;;
        --bind-all)
            BIND_ALL=1
            shift
            ;;
        --bind-address)
            BIND_ADDRESS="$2"
            shift 2
            ;;
        --help|-h)
            echo ""
            echo "Usage: $0 [--port PORT]"
            echo ""
            echo "Options:"
            echo "  --port PORT          Specify HTTP port (default: 8080)"
            echo "  --bind-all           Bind WebGUI to all interfaces"
            echo "  --bind-address IP    Bind WebGUI to a specific IP address"
            echo ""
            exit 0
            ;;
        *)
            shift
            ;;
    esac
done

# Classpath aufbauen
CLASSPATH="$JAR_FILE:lib/*:conf"

# Log4j2 Konfiguration
LOG4J_OPTS="-Dlog4j.configurationFile=conf/log4j2.xml"

# JVM Optionen — callers may append extra flags via CM_JAVA_OPTS
JVM_OPTS="-Xms256m -Xmx1g ${CM_JAVA_OPTS:-}"

if [ "$BIND_ALL" -eq 1 ]; then
    JVM_OPTS="$JVM_OPTS -Dcm.migrator.webgui.bindAll=true"
fi

if [ -n "$BIND_ADDRESS" ]; then
    JVM_OPTS="$JVM_OPTS -Dcm.migrator.webgui.bindAddress=$BIND_ADDRESS"
fi

echo "----------------------------------------------"
echo "Port:      $PORT"
echo "Classpath: $CLASSPATH"
echo "----------------------------------------------"

# Prüfen ob Port frei ist
if command -v lsof &> /dev/null; then
    if lsof -i:$PORT > /dev/null 2>&1; then
        echo "[WARNING] Port $PORT is already in use!"
        echo "          Stop the other process or use --port to specify a different port."
        exit 1
    fi
fi

echo ""
echo "Starting WebGUI server..."
echo "Press Ctrl+C to stop."
echo ""

# Server starten
# shellcheck disable=SC2086  # word-splitting of JVM_OPTS is intentional
exec "$JAVA_CMD" $JVM_OPTS $LOG4J_OPTS \
    -cp "$CLASSPATH" \
    com.ibm.ecm.migration.WebServer \
    --port $PORT
