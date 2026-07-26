#!/usr/bin/env bash
#===============================================================================
# CM Migrator - Release Build Script v2.2
# Erstellt ein deploy-fertiges Paket ohne Quellcode (JAR, optional obfuskiert/signiert)
#===============================================================================

#===============================================================================
# Projekt: CM Migrator 2.2.1.
# @Author: Aleksej Voronin, Sven Lindt
# @Date:   26.01.2026
#===============================================================================

set -Eeuo pipefail
IFS=$'\n\t'
shopt -s nullglob

# -----------------------------
# Defaults / Konfiguration
# -----------------------------
VERSION_DEFAULT="2.2.0"
VERSION="$VERSION_DEFAULT"

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$PROJECT_DIR/build"

OBFUSCATE=false
SIGN_JAR=false

PROGUARD_JAR="$PROJECT_DIR/tools/proguard.jar"
PROGUARD_CONF="$PROJECT_DIR/tools/proguard.conf"

INPUT_JAR="$PROJECT_DIR/bin/cm-migrator.jar"

KEYSTORE="$PROJECT_DIR/tools/cm-migrator.keystore"
KEYSTORE_ALIAS="cm-migrator"

# Passwörter idealerweise per Env setzen (CI-freundlich):
KEYSTORE_STOREPASS="${KEYSTORE_STOREPASS:-}"
KEYSTORE_KEYPASS="${KEYSTORE_KEYPASS:-}"

# Lokales JDK (Fallback: System-Tools)
LOCAL_JDK="$PROJECT_DIR/java_env/jdk-11.0.2"

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

require_file() {
  [[ -f "$1" ]] || die "Datei nicht gefunden: $1"
}

tool_or_fallback() {
  local local_path="$1"
  local fallback_cmd="$2"
  if [[ -x "$local_path" ]]; then
    printf '%s' "$local_path"
  else
    printf '%s' "$fallback_cmd"
  fi
}

read_secret_if_empty() {
  local var_name="$1"
  local prompt="$2"
  local current_value="${!var_name:-}"
  if [[ -z "$current_value" ]]; then
    read -rsp "$prompt" current_value
    printf '\n'
    [[ -n "$current_value" ]] || die "Leeres Passwort nicht erlaubt."
    printf -v "$var_name" '%s' "$current_value"
  fi
}

usage() {
  cat <<EOF
CM Migrator Release Builder v${VERSION_DEFAULT}

Usage: $(basename "$0") [OPTIONS]

Options:
  --obfuscate         Obfuskiere JAR mit ProGuard
  --sign              Signiere JAR (Keystore erforderlich)
  --all               Aktiviere: obfuscate + sign
  --version VER       Setze Version (default: ${VERSION_DEFAULT})
  --help|-h           Diese Hilfe anzeigen

Env (optional, für non-interactive builds):
  KEYSTORE_STOREPASS  Keystore-Passwort
  KEYSTORE_KEYPASS    Key-Passwort

Beispiele:
  ./bin/build-release.sh                    # Standard-Release
  ./bin/build-release.sh --obfuscate        # Mit Code-Obfuskierung
  ./bin/build-release.sh --all              # Maximaler Schutz
  ./bin/build-release.sh --version 2.3.0    # Andere Version

EOF
}

# -----------------------------
# Pre-Check: System & Abhängigkeiten
# -----------------------------
check_dependencies() {
  log "Prüfe Abhängigkeiten..."
  if ! command -v gcc &>/dev/null; then
    warn "gcc nicht gefunden."
  fi
  log "✅ Basis-Abhängigkeiten geprüft"
}

# -----------------------------
# Argumente parsen
# -----------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --obfuscate) OBFUSCATE=true; shift ;;
    --sign)      SIGN_JAR=true; shift ;;
    --all)       OBFUSCATE=true; SIGN_JAR=true; shift ;;
    --version)
      [[ $# -ge 2 ]] || die "--version benötigt ein Argument"
      VERSION="$2"
      shift 2
      ;;
    --help|-h) usage; exit 0 ;;
    *) die "Unbekannte Option: $1 (nutze --help)" ;;
  esac
done

RELEASE_NAME="cm-migrator-v${VERSION}"
RELEASE_DIR="$BUILD_DIR/$RELEASE_NAME"

log "============================================="
log " CM Migrator Release Builder"
log " Version: $VERSION"
log "============================================="
log "Optionen:"
log "  - Obfuscation: $OBFUSCATE"
log "  - JAR-Signierung: $SIGN_JAR"
log "---------------------------------------------"

# -----------------------------
# [1/6] Aufräumen
# -----------------------------
log "[1/6] Aufräumen..."
rm -rf "$BUILD_DIR"
mkdir -p "$RELEASE_DIR"

# -----------------------------
# [2/6] Kompilieren
# -----------------------------
log "[2/6] Kompiliere Quellcode..."
cd "$PROJECT_DIR"
./bin/compile.sh
log "✅ Kompilierung erfolgreich"

require_file "$INPUT_JAR"
BUILD_JAR="$BUILD_DIR/cm-migrator.jar"
cp "$INPUT_JAR" "$BUILD_JAR"

# -----------------------------
# [3/6] Obfuskieren (optional)
# -----------------------------
if [[ "$OBFUSCATE" == true ]]; then
  log "[3/6] Obfuskiere mit ProGuard..."
  require_file "$PROGUARD_JAR"
  require_file "$PROGUARD_CONF"

  JMODS_PATH=""
  if [[ -d "$LOCAL_JDK/jmods" ]]; then
    JMODS_PATH="$LOCAL_JDK/jmods"
  elif [[ -n "${JAVA_HOME:-}" && -d "$JAVA_HOME/jmods" ]]; then
    JMODS_PATH="$JAVA_HOME/jmods"
  fi

  if [[ -z "$JMODS_PATH" ]]; then
    warn "Kein jmods-Verzeichnis gefunden -> Obfuscation übersprungen."
  else
    OBF_JAR="$BUILD_DIR/cm-migrator-obf.jar"
    MAPPING_FILE="$BUILD_DIR/proguard-mapping.txt"

    java -jar "$PROGUARD_JAR" \
      @"${PROGUARD_CONF}" \
      -injars "$BUILD_JAR" \
      -outjars "$OBF_JAR" \
      -printmapping "$MAPPING_FILE" \
      -libraryjars "$JMODS_PATH/java.base.jmod(!**.jar;!module-info.class)" \
      -libraryjars "$JMODS_PATH/java.sql.jmod(!**.jar;!module-info.class)" \
      -libraryjars "$PROJECT_DIR/lib"

    mv -f "$OBF_JAR" "$BUILD_JAR"
    log "✅ Obfuscation erfolgreich"
  fi
else
  log "[3/6] Obfuscation übersprungen"
fi

# -----------------------------
# [4/6] Signieren (optional)
# -----------------------------
if [[ "$SIGN_JAR" == true ]]; then
  log "[4/6] Signiere JAR..."
  JARSIGNER="$(tool_or_fallback "$LOCAL_JDK/bin/jarsigner" "jarsigner")"
  
  if [[ ! -f "$KEYSTORE" ]]; then
    die "Signierung angefordert, aber Keystore fehlt: $KEYSTORE"
  fi

  read_secret_if_empty KEYSTORE_STOREPASS "Keystore Storepass: "
  read_secret_if_empty KEYSTORE_KEYPASS "Keystore Keypass: "

  "$JARSIGNER" \
    -keystore "$KEYSTORE" \
    -storepass "$KEYSTORE_STOREPASS" \
    -keypass "$KEYSTORE_KEYPASS" \
    "$BUILD_JAR" \
    "$KEYSTORE_ALIAS" && {
    log "✅ JAR signiert"
  } || {
    die "Signierung fehlgeschlagen"
  }
else
  log "[4/6] Signierung übersprungen"
fi

# -----------------------------
# [5/6] Release-Paket zusammenstellen
# -----------------------------
log "[5/6] Erstelle Release-Paket..."
mkdir -p "$RELEASE_DIR"/{bin,lib,conf,webapp,data,reports}
cp "$BUILD_JAR" "$RELEASE_DIR/bin/"

for script in start.sh verify.sh monitor.sh webgui.sh remigrate.sh compile.sh cm-run.sh cascade-delete-guard.sh; do
  if [[ -f "$PROJECT_DIR/bin/$script" ]]; then
    cp "$PROJECT_DIR/bin/$script" "$RELEASE_DIR/bin/"
    chmod +x "$RELEASE_DIR/bin/$script"
  fi
done

# NOTICE: No vendor JARs are packaged — partners must supply IBM CM SDK,
# DB2 and Oracle JDBC drivers under their own license agreements.
# Only the project-built JAR and H2 database JAR are included.
cp "$PROJECT_DIR/lib/h2-"*.jar "$RELEASE_DIR/lib/" 2>/dev/null || true
for conf in migration.properties.example ibmcmconfig.properties.example \
            webgui.properties.example cmbcmenv.properties.example \
            cmbicmsrvs.ini.example log4j2.xml log4j2-pretty.xml; do
  cp "$PROJECT_DIR/conf/$conf" "$RELEASE_DIR/conf/" 2>/dev/null || true
done
for asset in webapp/index.html webapp/process.html; do
  cp "$PROJECT_DIR/$asset" "$RELEASE_DIR/webapp/" 2>/dev/null || true
done

# README erstellen
cat > "$RELEASE_DIR/README.txt" <<'EOF'
IBM CM Migrator — Partner Edition
==================================
This package does NOT include IBM CM SDK, DB2, or Oracle JDBC drivers.
Partners must supply those JARs under their own license agreements.

Schnellstart:
1. Place vendor JARs into lib/
2. cp conf/migration.properties.example conf/migration.properties
3. Configure source and destination CM connections
4. ./bin/compile.sh
5. ./bin/start.sh

⚠️  OPERATION_MODE=DELETE with empty FILTER_PREDICATE deletes ALL
   configured source ItemTypes. Always verify the predicate scope.

Vendor dependencies (partner-supplied):
- IBM CM API JARs (cmb81.jar, cmbicmsrv81.jar, cmbsdk81.jar, etc.)
- DB2 JCC driver (db2jcc.jar, db2jcc_license_cu.jar)
- Oracle JDBC driver (ojdbc8.jar or newer)

Supported launchers: start.sh, verify.sh, monitor.sh, webgui.sh,
remigrate.sh, compile.sh, cm-run.sh
EOF

# -----------------------------
# [6/6] Archiv erstellen
# -----------------------------
log "[6/6] Erstelle Archive..."
cd "$BUILD_DIR"
tar -czf "${RELEASE_NAME}.tar.gz" "$RELEASE_NAME"
log "✅ ${RELEASE_NAME}.tar.gz erstellt"

log ""
log "============================================="
log " Release Build abgeschlossen!"
log "============================================="
