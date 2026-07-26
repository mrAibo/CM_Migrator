#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

python3 - <<'PY'
from collections import Counter
from html.parser import HTMLParser
from pathlib import Path
import re

class ContractParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = []
        self.external = []
        self.scripts = []
        self._script = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if "id" in attrs:
            self.ids.append(attrs["id"])
        for key in ("src", "href"):
            value = attrs.get(key, "")
            if value.startswith(("http://", "https://")):
                self.external.append(value)
        if tag == "script" and not attrs.get("src"):
            self._script = []

    def handle_data(self, data):
        if self._script is not None:
            self._script.append(data)

    def handle_endtag(self, tag):
        if tag == "script" and self._script is not None:
            self.scripts.append("".join(self._script))
            self._script = None

def parse(path):
    text = Path(path).read_text(encoding="utf-8")
    parser = ContractParser()
    parser.feed(text)
    duplicates = [key for key, count in Counter(parser.ids).items() if count > 1]
    assert not duplicates, f"{path}: duplicate IDs: {duplicates}"
    assert not parser.external, f"{path}: external resources: {parser.external}"
    return text, "\n".join(parser.scripts)

index, js = parse("webapp/index.html")
process, process_js = parse("webapp/process.html")
server = Path("src/com/ibm/ecm/migration/WebServer.java").read_text(encoding="utf-8")
assert 'String.format("%.' not in server, "WebServer JSON numbers must use Locale.ROOT"

for name in ("loadConfig", "saveConfig", "startMigration", "startDelete", "startVerification", "stopMigration", "pollStatus"):
    count = len(re.findall(rf"(?:async\s+)?function\s+{name}\s*\(", js))
    assert count == 1, f"index.html: {name} defined {count} times"

for endpoint in ("/api/migration/start", "/api/migration/stop", "/api/delete/start", "/api/verify/start", "/api/migration/status"):
    assert endpoint not in js, f"index.html still calls disabled endpoint {endpoint}"

for endpoint in ("/api/profiles", "/api/config?configFile=", "/api/operation/start", "/api/process/current", "/api/process/stop"):
    assert endpoint in js, f"index.html missing active endpoint {endpoint}"

for marker in ("Betriebsstatus", "Nächster sicherer Schritt", "Konfiguration &amp; Profile", "Ausführungsparameter", "Destruktive Aktion"):
    assert marker in index, f"index.html missing operator marker: {marker}"

assert "Report &amp; Prüfprotokoll" in process, "process.html missing reports-folder link"
assert "migration_report.html" not in process and "verification_report.html" not in process
assert "/api/process" in process_js and "/api/process/stop" in process_js
print("PASS: WebGUI UI contract")
PY
