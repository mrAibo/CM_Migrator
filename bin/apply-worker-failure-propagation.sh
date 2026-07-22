#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
PRODUCER="$ROOT/src/com/ibm/ecm/migration/Producer.java"
MAIN="$ROOT/src/com/ibm/ecm/migration/Main.java"
APPLIER="$ROOT/bin/apply-worker-failure-propagation.py"

producer_applied=false
main_applied=false

grep -Fq 'private final WorkerFailureState workerFailureState;' "$PRODUCER" && producer_applied=true
grep -Fq 'workerFailureState.throwIfPresent("Migration worker failed");' "$MAIN" && main_applied=true

if [[ $producer_applied == true && $main_applied == true ]]; then
    printf 'Worker failure propagation already applied.\n'
    exit 0
fi

if [[ $producer_applied != $main_applied ]]; then
    printf 'ERROR: partial worker failure propagation detected; refusing to continue.\n' >&2
    exit 1
fi

exec python3 "$APPLIER"
