#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
root="$(mktemp -d)"
trap 'rm -rf "$root"' EXIT

fixture="$root/project"
package_dir="$root/packages"
mkdir -p "$fixture/bin" "$fixture/conf" "$fixture/src/com/ibm/ecm/migration" "$fixture/tools" "$fixture/webapp" "$package_dir"
cp bin/deploy-source.sh "$fixture/bin/"
printf 'PROFILE=test\n' > "$fixture/conf/migration.properties"
printf '# fixture\n' > "$fixture/README.md"
for script in start.sh verify.sh monitor.sh webgui.sh remigrate.sh compile.sh; do
    printf '#!/usr/bin/env bash\n' > "$fixture/bin/$script"
done

release="$package_dir/cm-migrator-source-vtest"
mkdir -p "$release"
printf 'keep-me\n' > "$release/sentinel"

if PACKAGE_DIR="$package_dir" bash "$fixture/bin/deploy-source.sh" >/dev/null 2>&1; then
    printf 'FAIL: incomplete staged package unexpectedly succeeded\n' >&2
    exit 1
fi
test "$(cat "$release/sentinel")" = "keep-me"

printf 'package com.ibm.ecm.migration; final class Main {}\n' \
    > "$fixture/src/com/ibm/ecm/migration/Main.java"
PACKAGE_DIR="$package_dir" bash "$fixture/bin/deploy-source.sh" >/dev/null
test -f "$release/src/com/ibm/ecm/migration/Main.java"
test -f "$package_dir/cm-migrator-source-vtest.tar.gz"
test ! -e "$release/sentinel"

printf 'SourceDeploymentAtomicTest: PASS\n'