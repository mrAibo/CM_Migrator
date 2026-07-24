#!/usr/bin/env bash
#===============================================================================
# CM Migrator - Source Deployment Script v2.2
# Kopiert Quellcode und Ressourcen für Entwicklungsumgebungen
#===============================================================================

set -Eeuo pipefail
IFS=$'\n\t'
shopt -s nullglob

# -----------------------------
# Defaults / Konfiguration
# -----------------------------
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONF_FILE="$PROJECT_DIR/conf/migration.properties"

# Version aus Config extrahieren
VERSION=$(grep "^PROFILE=" "$CONF_FILE" 2>/dev/null | cut -d'=' -f2 || echo "2.2.0")
[ -z "$VERSION" ] && VERSION="2.2.0"

PACKAGE_NAME="cm-migrator-source-v${VERSION}"
PACKAGE_DIR="$HOME/Downloads"
RELEASE_DIR="$PACKAGE_DIR/$PACKAGE_NAME"

# -----------------------------
# Helpers
# -----------------------------
log()  { printf '%s\n' "$*"; }
warn() { printf '⚠️  %s\n' "$*" >&2; }
die()  { printf '❌ %s\n' "$*" >&2; exit 1; }

on_err() {
  local exit_code=$?
  warn "Fehler in Zeile ${BASH_LINENO[0]} (Exit-Code: $exit_code)."
  warn "Letztes Kommando: ${BASH_COMMAND}"
  exit "$exit_code"
}
trap on_err ERR

# -----------------------------
# Start
# -----------------------------
log "============================================="
log " CM Migrator - Source Deployment Script"
log "============================================="
log "Version: $VERSION"
log "Ziel:    $RELEASE_DIR"
log "---------------------------------------------"

# [1/4] Aufräumen & Vorbereiten
log "[1/4] Vorbereiten..."
mkdir -p "$PACKAGE_DIR"
rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR"

# [2/4] Kopieren
log "[2/4] Kopiere Komponenten..."

# Verzeichnisse kopieren
for dir in src conf lib tools webapp; do
  if [[ -d "$PROJECT_DIR/$dir" ]]; then
    cp -r "$PROJECT_DIR/$dir" "$RELEASE_DIR/"
    log "  ✅ $dir/ kopiert"
  else
    warn "  - $dir/ nicht gefunden"
  fi
done

# Skripte kopieren
mkdir -p "$RELEASE_DIR/bin"
scripts=(start.sh verify.sh monitor.sh webgui.sh remigrate.sh compile.sh)
for script in "${scripts[@]}"; do
  if [[ -f "$PROJECT_DIR/bin/$script" ]]; then
    cp "$PROJECT_DIR/bin/$script" "$RELEASE_DIR/bin/"
    chmod +x "$RELEASE_DIR/bin/$script"
    log "  ✅ bin/$script kopiert"
  fi
done

# Dokumentation
for md in "$PROJECT_DIR"/*.md; do
  cp "$md" "$RELEASE_DIR/"
  log "  ✅ $(basename "$md") kopiert"
done

# [3/4] README generieren
log "[3/4] Erstelle README..."
cat > "$RELEASE_DIR/README.md" <<EOF
# CM Migrator - Source Code (v$VERSION)

## Deployment
1. Transfer: \`scp ${PACKAGE_NAME}.tar.gz user@server:/tmp/\`
2. Entpacken: \`tar -xzf ${PACKAGE_NAME}.tar.gz\`
3. Setup:
   - Java 11+ installieren
   - \`cp conf/migration.properties.example conf/migration.properties\`
4. Start:
   - \`./bin/compile.sh\`
   - \`./bin/start.sh\`

---
Erstellt: \$(date '+%d.%m.%Y %H:%M')
EOF

# [4/4] Archivieren
log "[4/4] Erstelle Archiv..."
cd "$PACKAGE_DIR"
tar -czf "${PACKAGE_NAME}.tar.gz" "$PACKAGE_NAME"

log "---------------------------------------------"
log "✅ Paket erfolgreich erstellt!"
log "📦 Datei: $PACKAGE_DIR/${PACKAGE_NAME}.tar.gz"
log "📋 Größe: \$(du -h "${PACKAGE_NAME}.tar.gz" | awk '{print \$1}')"
log "============================================="
