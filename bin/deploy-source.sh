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
PACKAGE_DIR="${PACKAGE_DIR:-$HOME/Downloads}"
RELEASE_DIR="$PACKAGE_DIR/$PACKAGE_NAME"
ARCHIVE_PATH="$PACKAGE_DIR/${PACKAGE_NAME}.tar.gz"
STAGING_ROOT=""
STAGING_RELEASE=""
ARCHIVE_TMP=""
BACKUP_DIR=""
SWAPPED=0

# -----------------------------
# Helpers
# -----------------------------
log()  { printf '%s\n' "$*"; }
warn() { printf '⚠️  %s\n' "$*" >&2; }
die()  { printf '❌ %s\n' "$*" >&2; exit 1; }

on_err() {
  local exit_code=$?
  local command=$BASH_COMMAND
  warn "Fehler in Zeile ${BASH_LINENO[0]} (Exit-Code: $exit_code)."
  warn "Letztes Kommando: $command"
  exit "$exit_code"
}
cleanup() {
  if (( SWAPPED )); then
    rm -rf "$RELEASE_DIR"
    if [[ -n "$BACKUP_DIR" && -e "$BACKUP_DIR" ]]; then
      mv "$BACKUP_DIR" "$RELEASE_DIR"
    fi
  fi
  if [[ -n "$STAGING_ROOT" ]]; then rm -rf "$STAGING_ROOT"; fi
  if [[ -n "$ARCHIVE_TMP" ]]; then rm -f "$ARCHIVE_TMP"; fi
  return 0
}
trap on_err ERR
trap cleanup EXIT

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
STAGING_ROOT="$(mktemp -d "$PACKAGE_DIR/.cm-migrator-source.XXXXXX")"
STAGING_RELEASE="$STAGING_ROOT/$PACKAGE_NAME"
mkdir -p "$STAGING_RELEASE"

# [2/4] Kopieren
log "[2/4] Kopiere Komponenten..."

# Verzeichnisse kopieren
for dir in src conf lib tools webapp; do
  if [[ -d "$PROJECT_DIR/$dir" ]]; then
    cp -r "$PROJECT_DIR/$dir" "$STAGING_RELEASE/"
    log "  ✅ $dir/ kopiert"
  else
    warn "  - $dir/ nicht gefunden"
  fi
done

# Skripte kopieren
mkdir -p "$STAGING_RELEASE/bin"
scripts=(start.sh verify.sh monitor.sh webgui.sh remigrate.sh compile.sh)
for script in "${scripts[@]}"; do
  if [[ -f "$PROJECT_DIR/bin/$script" ]]; then
    cp "$PROJECT_DIR/bin/$script" "$STAGING_RELEASE/bin/"
    chmod +x "$STAGING_RELEASE/bin/$script"
    log "  ✅ bin/$script kopiert"
  fi
done

# Dokumentation
for md in "$PROJECT_DIR"/*.md; do
  cp "$md" "$STAGING_RELEASE/"
  log "  ✅ $(basename "$md") kopiert"
done

# [3/4] README generieren
log "[3/4] Erstelle README..."
cat > "$STAGING_RELEASE/README.md" <<EOF
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

# [4/4] Validieren, archivieren und atomar austauschen
log "[4/4] Validiere und erstelle Archiv..."
required=(src/com/ibm/ecm/migration/Main.java bin/compile.sh README.md)
for path in "${required[@]}"; do
  [[ -f "$STAGING_RELEASE/$path" ]] || die "Staging unvollständig: $path fehlt"
done
for script in "$STAGING_RELEASE"/bin/*.sh; do
  bash -n "$script"
done

ARCHIVE_TMP="$PACKAGE_DIR/.${PACKAGE_NAME}.tar.gz.$$"
tar -C "$STAGING_ROOT" -czf "$ARCHIVE_TMP" "$PACKAGE_NAME"

if [[ -e "$RELEASE_DIR" ]]; then
  BACKUP_DIR="$PACKAGE_DIR/.${PACKAGE_NAME}.backup.$$"
  mv "$RELEASE_DIR" "$BACKUP_DIR"
fi
mv "$STAGING_RELEASE" "$RELEASE_DIR"
SWAPPED=1
mv "$ARCHIVE_TMP" "$ARCHIVE_PATH"
ARCHIVE_TMP=""
SWAPPED=0
[[ -n "$BACKUP_DIR" ]] && rm -rf "$BACKUP_DIR"
BACKUP_DIR=""

log "---------------------------------------------"
log "✅ Paket erfolgreich erstellt!"
log "📦 Datei: $ARCHIVE_PATH"
log "📋 Größe: $(du -h "$ARCHIVE_PATH" | awk '{print $1}')"
log "============================================="
