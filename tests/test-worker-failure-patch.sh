#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
patch_file="$repo_root/patches/p0-worker-failure-propagation.patch"

required=(
  'private final WorkerFailureState workerFailures;'
  'workerFailures.record(e);'
  'ShutdownCoordinator.requestShutdown();'
  'throw new IllegalStateException("Producer failed for ItemType " + sourceType, e);'
  'Throwable workerFailure = workerFailures.get();'
  'throw new IllegalStateException("Migration failed in asynchronous worker", workerFailure);'
)

for needle in "${required[@]}"; do
  grep -Fq -- "$needle" "$patch_file" || {
    printf 'missing required patch fragment: %s\n' "$needle" >&2
    exit 1
  }
done

if grep -Fq 'Migration completed!' "$patch_file"; then
  echo 'patch must not add a successful completion message' >&2
  exit 1
fi

echo 'PASS: worker failure propagation patch'
