#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

root="$(mktemp -d)"
trap 'rm -rf "$root"' EXIT

die() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

# ── Simulate the packaging step (5/6) from build-release.sh ──
# Build a synthetic project with expected inputs
project="$root/project"
release="$root/release/cm-migrator-test"
archive="$root/release/cm-migrator-test.tar.gz"
mkdir -p "$project"/{bin,conf,lib,webapp,src/com/ibm/ecm/migration} "$release"

# Project-built JAR
printf 'MOCK\n' > "$project/bin/cm-migrator.jar"

# All launcher scripts + guard
for script in start.sh verify.sh monitor.sh webgui.sh remigrate.sh compile.sh \
              cm-run.sh cascade-delete-guard.sh; do
    printf '#!/usr/bin/env bash\necho ok\n' > "$project/bin/$script"
done

# All config templates
for conf in migration.properties.example ibmcmconfig.properties.example \
            webgui.properties.example cmbcmenv.properties.example \
            cmbicmsrvs.ini.example log4j2.xml log4j2-pretty.xml; do
    printf '# placeholder\n' > "$project/conf/$conf"
done

# Web assets
printf '<html></html>\n' > "$project/webapp/index.html"
printf '<html></html>\n' > "$project/webapp/process.html"

# H2 JAR (allowed)
touch "$project/lib/h2-2.2.224.jar"

# Forbidden vendor JAR (must NOT end up in release)
touch "$project/lib/cmb81.jar"

# ── Execute packaging step exactly as build-release.sh does ──
BUILD_JAR="$project/bin/cm-migrator.jar"
PROJECT_DIR="$project"
RELEASE_DIR="$release"
RELEASE_NAME="cm-migrator-test"
BUILD_DIR="$root/release"

mkdir -p "$RELEASE_DIR"/{bin,lib,conf,webapp,data,reports}
cp "$BUILD_JAR" "$RELEASE_DIR/bin/"

for script in start.sh verify.sh monitor.sh webgui.sh remigrate.sh compile.sh cm-run.sh cascade-delete-guard.sh; do
  if [[ -f "$PROJECT_DIR/bin/$script" ]]; then
    cp "$PROJECT_DIR/bin/$script" "$RELEASE_DIR/bin/"
    chmod +x "$RELEASE_DIR/bin/$script"
  fi
done

cp "$PROJECT_DIR/lib/h2-"*.jar "$RELEASE_DIR/lib/" 2>/dev/null || true
for conf in migration.properties.example ibmcmconfig.properties.example \
            webgui.properties.example cmbcmenv.properties.example \
            cmbicmsrvs.ini.example log4j2.xml log4j2-pretty.xml; do
  cp "$PROJECT_DIR/conf/$conf" "$RELEASE_DIR/conf/" 2>/dev/null || true
done
for asset in webapp/index.html webapp/process.html; do
  cp "$PROJECT_DIR/$asset" "$RELEASE_DIR/webapp/" 2>/dev/null || true
done

# Archive
cd "$BUILD_DIR"
tar -czf "$archive" "$RELEASE_NAME"

# ── Smoke ──
extracted="$root/extracted"
mkdir -p "$extracted"
tar -xzf "$archive" -C "$extracted"
pkg="$extracted/cm-migrator-test"

for script in start.sh verify.sh monitor.sh webgui.sh remigrate.sh compile.sh \
              cm-run.sh cascade-delete-guard.sh; do
    test -f "$pkg/bin/$script" || die "missing script: bin/$script"
    test -x "$pkg/bin/$script" || die "not executable: bin/$script"
done

for conf in migration.properties.example ibmcmconfig.properties.example \
            webgui.properties.example cmbcmenv.properties.example \
            cmbicmsrvs.ini.example log4j2.xml log4j2-pretty.xml; do
    test -f "$pkg/conf/$conf" || die "missing config: conf/$conf"
done

for asset in index.html process.html; do
    test -f "$pkg/webapp/$asset" || die "missing web asset: webapp/$asset"
done

test -f "$pkg/bin/cm-migrator.jar" || die "missing build JAR"
test -f "$pkg/lib/h2-2.2.224.jar" || die "missing H2 JAR"

# MUST NOT include vendor JARs
if [[ -f "$pkg/lib/cmb81.jar" ]]; then
    die "vendor JAR cmb81.jar must not be packaged"
fi
for pattern in "db2jcc*.jar" "ojdbc*.jar" "oracle*.jar"; do
    count=$(find "$pkg/lib" -name "$pattern" 2>/dev/null | wc -l)
    if (( count > 0 )); then
        die "vendor JAR matching $pattern must not be packaged"
    fi
done

printf 'ReleasePackageSmokeTest: PASS\n'
