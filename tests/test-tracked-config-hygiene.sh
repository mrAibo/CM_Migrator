#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if tracked_ignored=$(git ls-files -ci --exclude-standard) && [[ -n "$tracked_ignored" ]]; then
    printf 'FAIL: ignored files are still tracked:\n%s\n' "$tracked_ignored" >&2
    exit 1
fi

python3 - <<'PY'
from pathlib import Path
import subprocess

tracked = subprocess.check_output(["git", "ls-files"], text=True).splitlines()
forbidden = []
for name in tracked:
    path = Path(name)
    lower = name.lower()
    if name.startswith("conf/") and path.suffix == ".properties":
        if name != "conf/cmblogconfig.properties":
            forbidden.append(name)
    if name.startswith("conf/") and path.suffix == ".ini":
        forbidden.append(name)
    if any(marker in lower for marker in (".bak", ".bac", ".pre-round")) or lower.endswith(".java.a"):
        forbidden.append(name)
    if path.suffix.lower() in {".keystore", ".jks", ".p12", ".pfx", ".pem", ".key"}:
        forbidden.append(name)

if forbidden:
    raise SystemExit("FAIL: tracked local config/backup/credential artifacts: " + ", ".join(forbidden))

scanned = {"data/source-snapshot-20230611.html", "data/source-snapshot-20240316.html", "data/source-snapshot-20250104.html"}
still_tracked = scanned.intersection(tracked)
if still_tracked:
    raise SystemExit("FAIL: stale source-snapshots are still tracked: " + ", ".join(sorted(still_tracked)))

required = {
    "conf/migration.properties.example",
    "conf/webgui.properties.example",
    "conf/cmbcmenv.properties.example",
    "conf/cmbicmsrvs.ini.example",
    "conf/ibmcmconfig.properties.example",
}
missing = sorted(required.difference(tracked))
if missing:
    raise SystemExit("FAIL: missing neutral templates: " + ", ".join(missing))

print("TrackedConfigHygieneTest: PASS")
PY
