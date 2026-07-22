#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

javac_cmd="${JAVAC_CMD:-javac}"
java_cmd="${JAVA_CMD:-java}"

"$javac_cmd" -d "$work_dir" \
    tests/stubs/org/apache/logging/log4j/Logger.java \
    tests/stubs/org/apache/logging/log4j/LogManager.java \
    src/com/ibm/ecm/migration/AuthHandler.java \
    tests/java/com/ibm/ecm/migration/AuthHandlerConfigurationTest.java

"$java_cmd" -cp "$work_dir:lib/*" com.ibm.ecm.migration.AuthHandlerConfigurationTest

python3 - <<'PY'
from pathlib import Path

source = Path("src/com/ibm/ecm/migration/WebServer.java").read_text()
validation = source.find("AuthHandler.validateConfiguration(authConfig);")
bind = source.find("HttpServer.create(addr, 0)")
if validation < 0 or bind < 0 or validation > bind:
    raise SystemExit("FAIL: WebGUI auth must be validated before binding the HTTP server")
PY
