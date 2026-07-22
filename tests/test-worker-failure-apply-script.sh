#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SCRIPT="$ROOT/bin/apply-worker-failure-propagation.py"

python3 -m py_compile "$SCRIPT"

grep -Fq 'expected one match' "$SCRIPT"
grep -Fq 'ShutdownCoordinator.requestShutdown()' "$SCRIPT"
grep -Fq 'workerFailureState.throwIfPresent("Migration worker failed")' "$SCRIPT"
grep -Fq 'temporary.replace(path)' "$SCRIPT"

printf 'PASS: worker failure apply script\n'
