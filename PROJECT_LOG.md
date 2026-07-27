# Project Log

## 2026-07-26 — Source Delete scope, fail-closed result and Single-Pass regression

### Task
Restore and lock the Source Delete contract: purge all objects of explicitly configured `MIGRATE_ITEMTYPES` when the filter is empty, or only objects matching `FILTER_PREDICATE`.

### Decisions
- Source Delete remains a standalone purge and is not restricted to migrated or verified journal rows.
- Discovery erfolgt ausschließlich im Zwei-Pass-Verfahren: Zählen (Pass 1), Verarbeitung (Pass 2).
- `FILTER_PREDICATE` may only narrow the configured Source ItemType; absolute query paths fail closed.
- Real deletes count as both successful and deleted; Dry-Run does not increment the deleted counter.
- A `DELETED` journal update preserves existing destination PID and checksum; standalone deletes still create a journal row.
- Unhandled Producer, Discovery and Consumer failures request shutdown and end as `FAILED`; completed runs with failed items generate their report and then return nonzero.
- DELETE checks shutdown before borrowing a connection, before every destructive `del()` and before committing the batch.

### Files changed
- `src/com/ibm/ecm/migration/{Producer,Consumer,ItemMigrator,MigrationStats,MigrationJournal,Main,WorkerFailureState}.java`
- `tests/java/com/ibm/ecm/migration/{ProducerDeleteScopeTest,ConsumerDeleteAccountingTest,MigrationJournalFailClosedTest,WorkerFailureStateTest}.java`
- `tests/test-{producer-delete-scope,consumer-delete-accounting}.sh`
- `BETRIEBSHANDBUCH.md`, `ARCHITEKTUR.md`, `PROJECT_LOG.md`

Pre-existing `README.md`, `assets/` and `sketches/` worktree changes were not modified as part of this task.

### Verification
- `bash bin/compile.sh`: PASS, 107 classes and `bin/cm-migrator.jar` created.
- Full test matrix: PASS, all 17 `tests/test-*.sh` scripts.
- Focused contracts: Producer scope/strategy 7/7 plus guarded discovery/top-level callsites 2/2; Consumer delete accounting/shutdown 9/9; journal fail-closed 21/21; worker failure guard for `RuntimeException` and `Error` PASS.
- `git diff --check`: PASS.

### Open issues
- No live IBM CM delete was run. Cursor behavior under concurrent source deletion and a fresh post-delete residual query still require an IBM staging run.
- Attribute/child-copy partial-success behavior in the migration path remains a separate fix.

### Next action
Review and commit this scoped branch, then run Dry-Run plus a restorable IBM staging delete before production use.

## 2026-07-26 — Operator surfaces: reporting, audit protocol and WebGUI

### Task
Bring the operator-facing report, email, audit protocol and WebGUI onto one consistent, decision-oriented workflow without adding dependencies or changing the IBM CM migration core.

### Decisions
- `UnifiedReport` remains the single reporting model; the disconnected legacy `ProtocolData` path was not revived.
- Report and email decisions come from `UnifiedReport.status()`, including `WARNING` even when the failed-item count is zero.
- `AuditProtocolGenerator` now renders an A4 overall audit protocol directly from `UnifiedReport`.
- `mutt` delivery attaches `report.html`, `pruefprotokoll.html` and `errors.csv` when present; `mailx` remains body-only.
- WebGUI stays native HTML/CSS/JavaScript. All operations use `/api/operation/start`; disabled legacy start endpoints are not called.
- JSON and console numeric output use locale-stable formatting.
- The pre-existing asynchronous journal test now waits for committed writes instead of the earlier queue-empty race.

### Files changed
- `src/com/ibm/ecm/migration/AuditProtocolGenerator.java`
- `src/com/ibm/ecm/migration/ReportRenderer.java`
- `src/com/ibm/ecm/migration/ReportDeliveryService.java`
- `src/com/ibm/ecm/migration/WebServer.java`
- `src/com/ibm/ecm/migration/OperatorConsole.java`
- `webapp/index.html`
- `webapp/process.html`
- `tests/java/com/ibm/ecm/migration/UnifiedReportingTest.java`
- `tests/java/com/ibm/ecm/migration/JournalCMETest.java`
- `tests/java/com/ibm/ecm/migration/SinglePassTransitionTest.java`
- `tests/test-unified-reporting.sh`
- `tests/test-webgui-ui-contract.sh`

Pre-existing `README.md`, `assets/` and `sketches/` worktree changes were not modified as part of this task.

### Verification
- `bash bin/compile.sh`: PASS, 107 classes and `bin/cm-migrator.jar` created.
- Full test matrix: PASS, all 15 `tests/test-*.sh` scripts.
- Unified reporting: PASS, 102 checks.
- Isolated HTTP E2E: health, profiles, config roundtrip, safe credential rejection, process status, HTTP 410 legacy endpoints and quick benchmark JSON.
- Browser E2E: save, controlled start rejection, benchmark completion and process idle state; no JavaScript errors or duplicate IDs.
- Desktop and 390 px mobile layouts: no horizontal page overflow or overlapping actions.
- `git diff --check` and shell syntax checks: PASS.

### Open issues
- No live IBM CM migration, verification or deletion was run because no IBM CM test system/credentials were available.
- No real email was sent; command construction and generated artifacts were tested without external delivery.

### Next action
Review the scoped diff, then commit the operator-surface changes separately from the pre-existing README/design artifacts.
