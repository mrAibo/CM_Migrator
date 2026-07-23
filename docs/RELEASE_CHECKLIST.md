# CM Migrator v2.2.1 — Release Readiness Checklist

## 1. Build and Test Status

- [ ] `bash -n bin/*.sh tests/*.sh` — no syntax errors
- [ ] All dependency-free tests pass:
  - [ ] `tests/test_config.sh`
  - [ ] `tests/test_migrate.sh`
  - [ ] `tests/test_rollback.sh`
  - [ ] `tests/test_validate.sh`
- [ ] `bin/compile.sh` succeeds
- [ ] CI workflow green on `main`
- [ ] `git diff --check` clean (no whitespace errors)

## 2. IBM Live Acceptance

> Reference: `docs/IBM_CM_LIVE_ACCEPTANCE.md`

- [ ] All 12 E2E test cases executed and **PASS**
- [ ] Evidence collected (logs, journals, reports)
- [ ] Sign-off by test lead

## 3. Credential Rotation

- [ ] Inventory all credentials in git history
- [ ] All live credentials rotated
- [ ] Old credential values invalidated / revoked

## 4. History Purge Decision

- [ ] Decision made — **purge** or **keep**
- [ ] If purge:
  - [ ] `git filter-repo` plan reviewed
  - [ ] Dry run executed and verified
  - [ ] Live run executed
  - [ ] Verification: no credentials in rewritten history
- [ ] All users re-cloned from clean repository

## 5. Backup

- [ ] Current `main` backup created
- [ ] Journal / config backup procedure documented
- [ ] Restore tested successfully

## 6. Rollback Plan

- [ ] Rollback procedure documented
- [ ] Known-good previous release identified (tag / commit hash)
- [ ] Downtime window communicated to stakeholders

## 7. Supervisor / Timeout Policy

- [ ] `SIGTERM` behavior confirmed (graceful shutdown)
- [ ] Grace period documented (default: 60s)
- [ ] systemd unit or supervisor config reviewed
- [ ] Hard kill timeout defined (`TimeoutStopSec` / equivalent)

## 8. Performance Thresholds

- [ ] Representative load test completed
- [ ] Memory / CPU / disk thresholds documented
- [ ] Queue overflow behavior understood and documented
- [ ] Large file (>2 GiB) handling confirmed

## 9. Owner Sign-off

- [ ] Code owner approves release
- [ ] Operations / SRE accepts runbook

## 10. Tag and Release Package

- [ ] Git tag created (e.g. `v2.2.1-rc1`)
- [ ] `bin/build-release.sh` executed successfully
- [ ] Release package integrity verified (checksums)
- [ ] Release notes published
