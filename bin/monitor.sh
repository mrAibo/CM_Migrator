#!/bin/bash
# =============================================================================
# IBM CM Migrator - Dashboard Monitor v2.1.31
# Starts a web server to serve the live status dashboard
# =============================================================================

# Ensure we are in the project root where status.html is generated
cd "$(dirname "$0")/.."

# Default port
PORT=8000
while [[ $# -gt 0 ]]; do
    case $1 in
        --port)
            PORT="$2"
            shift 2
            ;;
        --help|-h)
            echo "Usage: $0 [--port PORT] | [PORT]"
            exit 0
            ;;
        *)
            if [[ "$1" =~ ^[0-9]+$ ]]; then
                PORT="$1"
                shift
            else
                shift
            fi
            ;;
    esac
done

# Display banner
echo "============================================="
echo " IBM CM Migrator - Live Dashboard"
echo "============================================="

# Get server IP
SERVER_IP=$(hostname -I 2>/dev/null | awk '{print $1}')
if [ -z "$SERVER_IP" ]; then
    SERVER_IP="localhost"
fi

echo "Starting Web Monitor on port $PORT..."
echo ""
echo "Access URLs:"
echo "  Dashboard:    http://$SERVER_IP:$PORT/status.html"
echo "  Reports:      http://$SERVER_IP:$PORT/migration_report.html"
echo "  Verification: http://$SERVER_IP:$PORT/verification_report.html"
echo ""
echo "Press Ctrl+C to stop the monitor."
echo "---------------------------------------------"

# Check if status.html exists
if [ ! -f "status.html" ]; then
    echo "WARNING: status.html not found. It will be created when migration starts."
fi

# Python detection
if command -v python3 &>/dev/null; then
    PYTHON_CMD="python3"
elif command -v python &>/dev/null; then
    PYTHON_CMD="python"
else
    echo "ERROR: Python not found! Please install Python 3."
    exit 1
fi

# Check if port is already in use
if command -v lsof &>/dev/null; then
    if lsof -i :$PORT &>/dev/null; then
        echo "WARNING: Port $PORT may already be in use."
        echo "         Use: $0 <port> to specify a different port."
    fi
fi

# Start HTTP server
$PYTHON_CMD -m http.server $PORT 2>&1

echo ""
echo "Monitor stopped."
