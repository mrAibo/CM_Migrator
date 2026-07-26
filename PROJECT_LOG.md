# Project Log

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
