# IBM-CM Live E2E Acceptance Runbook

**Repository:** mrAibo/CM_Migrator
**Branch:** main
**Target environment:** isolated IBM-CM 8.7 sandbox (no production data)
**Date:** <ACCEPTANCE_DATE>
**Tester:** <TESTER_NAME>

---

## 1. Environment Setup

### 1.1 Prerequisites

- Dedicated sandbox IBM CM 8.7 source and destination instances at `<SANDBOX_SOURCE_HOST>` and `<SANDBOX_DEST_HOST>`
- Source and destination item type pre-populated with test data (see §1.2)
- JDK 11+ on the test host
- Built `bin/cm-migrator.jar` via `bash bin/compile.sh`
- Local `lib/` populated with IBM CM SDK JARs matching the sandbox versions
- Network connectivity from test host to both IBM instances
- Valid credentials for both instances (non-production test accounts)

### 1.2 Configuration Files

Copy and populate from examples:

```bash
cp conf/migration.properties.example conf/migration.properties
cp conf/cmbcmenv.properties.example conf/cmbcmenv.properties
cp conf/cmbicmsrvs.ini.example conf/cmbicmsrvs.ini
chmod 600 conf/migration.properties conf/cmbcmenv.properties conf/cmbicmsrvs.ini
```

Base `conf/migration.properties` for test sessions (override per test case):

```properties
# --- IBM Connection ---
SOURCE_SSID=<SANDBOX_SOURCE_SSID>
DEST_SSID=<SANDBOX_DEST_SSID>
CONNECT_USER=<SANDBOX_CONNECT_USER>
CONNECT_PASSWORD=<SANDBOX_CONNECT_PASSWORD>

# --- Item Types ---
MIGRATE_ITEMTYPES=<TEST_ITEM_TYPE>

# --- Journal ---
DB_PATH=reports/journal_acceptance_<TEST_SESSION>

# --- Safety (NEVER change for acceptance) ---
CASCADE_DELETE_ON_MISSING=false

# --- Pool / Timeout ---
POOL_SIZE=4
POOL_BORROW_TIMEOUT=30000
WORKER_TIMEOUT_SECONDS=3600

# --- Reports ---
REPORT_DIR=reports/acceptance_<TEST_SESSION>
PRODUCE_HTML_REPORT=true
PRODUCE_CSV_REPORT=true

# --- WebGUI ---
WEBGUI_ENABLED=false
WEBGUI_PORT=8080
```

### 1.3 Test Data Preparation

| Item Type | Count | Description |
|-----------|-------|-------------|
| `<TEST_ITEM_TYPE>` | ~50 | Normal documents for migration/verification tests |
| `<TEST_ITEM_TYPE_LARGE>` | 3 | Documents > 2 GiB for large-file tests |
| `<TEST_ITEM_TYPE_READONLY>` | 10 | Documents with restricted source permissions for permission tests |

Each test session uses a clean journal directory to avoid cross-session interference.

---

## 2. Safety Rules

| Rule | Enforcement |
|------|-------------|
| `CASCADE_DELETE_ON_MISSING=false` | Mandatory for ALL acceptance tests. Verify before every run. |
| No production data | Sandbox-only IBM instances. No production SSIDs, no production credentials. |
| Isolated journals | Unique `DB_PATH` per test session. |
| Read-only first | Tests 1–8 (read-only / safety) run before 9–12 (stateful / performance). |
| Rollback after each destructive test | Revert any deletions or state changes before proceeding. |
| Config files `0600` | All `.properties` and `.ini` files must have restricted permissions. |
| No secrets in commands | Use env vars or config files; never pass credentials on CLI. |

---

## 3. Test Execution Order

| Phase | Tests | Type |
|-------|-------|------|
| 1 | T01–T05 | Read-only, safety verification |
| 2 | T06–T08 | Lifecycle / signal handling |
| 3 | T09–T10 | Stateful (resume, large files) |
| 4 | T11–T12 | JNI / performance |

---

## 4. Test Matrix

---

### T01: Source EXISTS — Normal Verification, No Delete

**Prerequisites:**
- Source items exist in `<SANDBOX_SOURCE_HOST>` for `<TEST_ITEM_TYPE>`
- Destination has matching items from a prior migration run
- Journal populated with SUCCESS entries from that run

**Configuration:**

```properties
SOURCE_SSID=<SANDBOX_SOURCE_SSID>
DEST_SSID=<SANDBOX_DEST_SSID>
MIGRATE_ITEMTYPES=<TEST_ITEM_TYPE>
DB_PATH=reports/journal_t01
CASCADE_DELETE_ON_MISSING=false
```

**Command:**

```bash
./bin/cm-run.sh verification conf/migration.properties
```

**Expected Result:**
- Exit code: `0`
- Log pattern: `SourceLookupStatus.EXISTS` for all items
- Log pattern: SHA-256 verification row-by-row
- Journal: all entries remain `SUCCESS` (none transition to `DELETED`)
- No `Cascade delete triggered` log line
- Report: `reports/acceptance_t01/report.html` shows 100% SUCCESS rate

**Evidence to collect:**
- `reports/acceptance_t01/report.html`
- `reports/acceptance_t01/report.csv`
- Full run log
- Journal snapshot: `./bin/cm-run.sh status conf/migration.properties`

**Rollback:** None needed (read-only).

**PASS criteria:**
- Exit code 0
- No deletions in destination
- All verification rows pass
- No cascade-delete log lines

**FAIL criteria:**
- Any destination item deleted
- Exit code ≠ 0
- Unexpected `ERROR` or `NOT_FOUND` classification

---

### T02: Source NOT_FOUND — Cascade-Delete Guard

**Prerequisites:**
- Destination has items with no corresponding source items (create by migrating then deleting source items, or insert directly on destination)
- `CASCADE_DELETE_ON_MISSING=false` (verified before run)

**Configuration:**

```properties
SOURCE_SSID=<SANDBOX_SOURCE_SSID>
DEST_SSID=<SANDBOX_DEST_SSID>
MIGRATE_ITEMTYPES=<TEST_ITEM_TYPE>
DB_PATH=reports/journal_t02
CASCADE_DELETE_ON_MISSING=false
```

**Command:**

```bash
./bin/cm-run.sh verification conf/migration.properties
```

**Expected Result:**
- Exit code: non-zero (verification failure due to missing source items)
- Log pattern: `SourceLookupStatus.NOT_FOUND` classified for orphaned items
- Log pattern: destination items NOT deleted (guard blocks deletion)
- Journal: orphaned items marked `SKIPPED` or `FAILED`, NOT `DELETED`
- No `shouldCascadeDelete` returning true (guard: `CASCADE_DELETE_ON_MISSING=false`)

**Evidence to collect:**
- Full run log (capture NOT_FOUND classifications)
- `reports/acceptance_t02/report.csv` (verify no DELETED entries)
- Journal status output
- Destination item count after run (must match before-run count)

**Rollback:** None needed (guard prevents deletion).

**PASS criteria:**
- No destination items deleted
- NOT_FOUND items NOT cascaded to DELETED
- Guard `CASCADE_DELETE_ON_MISSING=false` blocks deletion

**FAIL criteria:**
- Any destination item deleted
- Guard bypassed
- `shouldCascadeDelete` returned true with `CASCADE_DELETE_ON_MISSING=false`

---

### T03: Auth Failure — ERROR Classification, No Destination Delete

**Prerequisites:**
- Source system reachable but with deliberately wrong credentials

**Configuration:**

```properties
SOURCE_SSID=<SANDBOX_SOURCE_SSID>
DEST_SSID=<SANDBOX_DEST_SSID>
CONNECT_PASSWORD=<WRONG_PASSWORD>
MIGRATE_ITEMTYPES=<TEST_ITEM_TYPE>
DB_PATH=reports/journal_t03
CASCADE_DELETE_ON_MISSING=false
```

**Command:**

```bash
./bin/cm-run.sh safe conf/migration.properties
```

**Expected Result:**
- Exit code: non-zero (migration phase fails during connect/discovery)
- Log pattern: authentication failure exception trace
- Log pattern: `SourceLookupStatus.ERROR` classification (NOT `NOT_FOUND`)
- Destination: no items deleted
- `WorkerFailureState` captures the auth error, propagates to caller
- No SUCCESS entries written to journal for this run

**Evidence to collect:**
- Full exception trace from log
- Exit code
- Destination item count (must be unchanged)
- Journal: empty or only pre-existing entries

**Rollback:**
1. Restore correct `CONNECT_PASSWORD` in `conf/migration.properties`
2. Delete `reports/journal_t03/` directory

**PASS criteria:**
- Auth failure classified as `ERROR`, not `NOT_FOUND`
- No destination deletions
- `WorkerFailureState` propagated correctly
- No misleading SUCCESS report

**FAIL criteria:**
- Auth failure misclassified as `NOT_FOUND`
- Destination items deleted
- Exit code 0 (false success)

---

### T04: Network Timeout — ERROR, No Destination Delete

**Prerequisites:**
- Source host reachable but with a very short connection timeout that triggers before IBM CM responds (use a firewall rule to delay/drop packets, or set `POOL_BORROW_TIMEOUT` extremely low)

**Configuration:**

```properties
SOURCE_SSID=<SANDBOX_SOURCE_SSID>
DEST_SSID=<SANDBOX_DEST_SSID>
MIGRATE_ITEMTYPES=<TEST_ITEM_TYPE>
DB_PATH=reports/journal_t04
CASCADE_DELETE_ON_MISSING=false
POOL_BORROW_TIMEOUT=1
```

**Command:**

```bash
./bin/cm-run.sh safe conf/migration.properties
```

**Expected Result:**
- Exit code: non-zero
- Log pattern: timeout exception / `SocketTimeoutException` or pool exhaustion
- Log pattern: `SourceLookupStatus.ERROR` (NOT `NOT_FOUND`)
- Destination: no items deleted
- Clean shutdown of workers and connection pool

**Evidence to collect:**
- Full run log with timeout trace
- Exit code
- Destination item count (unchanged)
- Journal state

**Rollback:**
1. Restore `POOL_BORROW_TIMEOUT=30000` in `conf/migration.properties`
2. Remove any firewall delay rule
3. Delete `reports/journal_t04/`

**PASS criteria:**
- Timeout classified as `ERROR`, not `NOT_FOUND`
- No destination deletions
- Clean shutdown despite timeout

**FAIL criteria:**
- Timeout misclassified as `NOT_FOUND`
- Destination items deleted
- Process hangs instead of timing out cleanly

---

### T05: Permission Error — ERROR, No Destination Delete

**Prerequisites:**
- Source IBM CM has item types the test user cannot read (use `<TEST_ITEM_TYPE_READONLY>` or restrict permissions on the sandbox test account)

**Configuration:**

```properties
SOURCE_SSID=<SANDBOX_SOURCE_SSID>
DEST_SSID=<SANDBOX_DEST_SSID>
CONNECT_USER=<RESTRICTED_SANDBOX_USER>
MIGRATE_ITEMTYPES=<TEST_ITEM_TYPE_READONLY>
DB_PATH=reports/journal_t05
CASCADE_DELETE_ON_MISSING=false
```

**Command:**

```bash
./bin/cm-run.sh safe conf/migration.properties
```

**Expected Result:**
- Exit code: non-zero
- Log pattern: permission error / access denied from IBM CM SDK
- Log pattern: `SourceLookupStatus.ERROR` (NOT `NOT_FOUND`)
- Destination: no items deleted
- Journal: items marked `FAILED` or `ERROR`, not `DELETED`

**Evidence to collect:**
- Full run log with permission error trace
- Exit code
- Destination item count (unchanged)
- Journal status output

**Rollback:**
1. Restore test user with full permissions (`CONNECT_USER=<SANDBOX_CONNECT_USER>`)
2. Remove `<TEST_ITEM_TYPE_READONLY>` from `MIGRATE_ITEMTYPES` or restore `MIGRATE_ITEMTYPES=<TEST_ITEM_TYPE>`
3. Delete `reports/journal_t05/`

**PASS criteria:**
- Permission error classified as `ERROR`, not `NOT_FOUND`
- No destination deletions
- Journal correctly reflects failed state

**FAIL criteria:**
- Permission error misclassified as `NOT_FOUND`
- Destination items deleted
- Exit code 0 (false success)

---

### T06: Producer/Discovery Failure — Cleanup, Exit Code 1, No Misleading Completion

**Prerequisites:**
- Source system reachable but item type `<TEST_ITEM_TYPE>` discovery fails mid-run (simulate by using an item type that exists but whose discovery query triggers an IBM CM server-side exception, or by corrupting an SDK resource mid-run)

**Configuration:**

```properties
SOURCE_SSID=<SANDBOX_SOURCE_SSID>
DEST_SSID=<SANDBOX_DEST_SSID>
MIGRATE_ITEMTYPES=<TEST_ITEM_TYPE>
DB_PATH=reports/journal_t06
CASCADE_DELETE_ON_MISSING=false
```

**Command:**

```bash
./bin/cm-run.sh safe conf/migration.properties
```

**Expected Result:**
- Exit code: `1` (non-zero)
- Log pattern: producer/discovery error captured by `WorkerFailureState`
- Log pattern: no `Migration completed!` success message
- Log pattern: `finish()` or equivalent cleanup invoked (connection pool drained, journal closed, monitor stopped)
- No misleading completion report generated
- No success email sent (if email configured)
- Original error preserved as root cause in log

**Evidence to collect:**
- Full run log (confirm no "Migration completed!")
- Exit code
- `reports/acceptance_t06/` — confirm no `report.html` with misleading SUCCESS
- Journal: partial entries, not a completed run

**Rollback:**
1. Delete `reports/journal_t06/`
2. Verify `conf/migration.properties` restored to baseline

**PASS criteria:**
- Exit code ≠ 0
- No `Migration completed!` message
- Cleanup completed (journals, pools, monitor stopped)
- No misleading success report

**FAIL criteria:**
- Exit code 0
- `Migration completed!` printed despite failure
- Resources leaked (open journal, stale monitor process)
- Success report generated incorrectly

---

### T07: WebGUI Error — FAILED State, JVM Stays Alive

**Prerequisites:**
- Source system configured with deliberately wrong credentials (or unreachable host) so migration fails
- WebGUI enabled: `WEBGUI_ENABLED=true`

**Configuration:**

```properties
SOURCE_SSID=<SANDBOX_SOURCE_SSID>
DEST_SSID=<SANDBOX_DEST_SSID>
CONNECT_PASSWORD=<WRONG_PASSWORD>
MIGRATE_ITEMTYPES=<TEST_ITEM_TYPE>
DB_PATH=reports/journal_t07
CASCADE_DELETE_ON_MISSING=false
WEBGUI_ENABLED=true
WEBGUI_PORT=8080
```

**Command:**

```bash
./bin/webgui.sh --port 8080 &
# Wait for WebGUI to start, then trigger migration from the WebGUI UI
```

**Expected Result:**
- WebGUI JVM stays alive (does not crash/exit)
- Migration status reflects `FAILED` in the WebGUI
- Log pattern: auth failure captured, WorkerFailureState set
- WebGUI remains responsive on port 8080
- `status.html` updated to reflect FAILED state
- No destination deletions

**Evidence to collect:**
- WebGUI screenshot showing FAILED status
- WebGUI log
- `curl -s http://localhost:8080/status.html` output
- Journal state
- JVM process still running: `ps aux | grep webgui`

**Rollback:**
1. Stop WebGUI: `kill <PID>` or `./bin/webgui.sh --stop`
2. Restore correct `CONNECT_PASSWORD`
3. Delete `reports/journal_t07/`

**PASS criteria:**
- WebGUI JVM stays alive after migration failure
- Migration status is `FAILED`, not hung or crashed
- WebGUI remains accessible
- No destination deletions

**FAIL criteria:**
- WebGUI crashes / JVM exits
- Migration status incorrectly reported as SUCCESS
- WebGUI becomes unresponsive

---

### T08: SIGTERM — Ordered Cleanup, Hook Works, finish() Completes

**Prerequisites:**
- Long-running migration in progress (use a large item type or artificially slow SDK operations)
- Process PID known

**Configuration:**

```properties
SOURCE_SSID=<SANDBOX_SOURCE_SSID>
DEST_SSID=<SANDBOX_DEST_SSID>
MIGRATE_ITEMTYPES=<TEST_ITEM_TYPE>
DB_PATH=reports/journal_t08
CASCADE_DELETE_ON_MISSING=false
```

**Command:**

```bash
./bin/cm-run.sh safe conf/migration.properties &
CM_PID=$!
sleep 10
kill -TERM $CM_PID
wait $CM_PID
echo "Exit code: $?"
```

**Expected Result:**
- Exit code: non-zero (signal-induced)
- Log pattern: `Received SIGTERM`, shutdown hook invoked
- Log pattern: `finish()` or cleanup method called (connection pool closed, journal flushed, monitor stopped)
- Journal: properly closed (not corrupted; H2 can reopen)
- No orphaned temp files
- No dangling monitor process on port

**Evidence to collect:**
- Full run log (including shutdown sequence)
- Exit code
- Journal integrity: `./bin/cm-run.sh status conf/migration.properties` (journal should be readable)
- Check for orphaned processes: `ps aux | grep cm-migrator`
- Check for orphaned temp files: `ls /tmp/cm_migrator_*` (if applicable)

**Rollback:**
1. Delete `reports/journal_t08/`
2. Clean any orphaned temp files
3. Kill any lingering processes

**PASS criteria:**
- `finish()` / cleanup logged after SIGTERM
- Journal closed cleanly (readable on re-open)
- No orphaned processes or temp files
- Shutdown hook fires correctly

**FAIL criteria:**
- SIGTERM causes immediate exit without cleanup
- Journal corrupted (cannot be opened)
- Orphaned processes remain
- Temp files not cleaned

---

### T09: Resume after Controlled Abort (Existing Journal)

**Prerequisites:**
- A prior migration run that was interrupted (not failed — e.g., SIGTERM after partial processing)
- Existing journal at `reports/journal_t09/` with a mix of SUCCESS and unprocessed entries
- Source items still exist for unprocessed entries

**Configuration:**

```properties
SOURCE_SSID=<SANDBOX_SOURCE_SSID>
DEST_SSID=<SANDBOX_DEST_SSID>
MIGRATE_ITEMTYPES=<TEST_ITEM_TYPE>
DB_PATH=reports/journal_t09
CASCADE_DELETE_ON_MISSING=false
```

**Command:**

```bash
# First run — let it process partially, then SIGTERM
./bin/cm-run.sh migration conf/migration.properties &
CM_PID=$!
sleep 20
kill -TERM $CM_PID
wait $CM_PID

# Resume with same journal
./bin/cm-run.sh migration conf/migration.properties
```

**Expected Result:**
- First run: partial processing, exit non-zero (SIGTERM)
- Second run: resumes from journal
- Log pattern: `Resuming from journal` or equivalent
- Already-SUCCESS items are skipped (not re-processed)
- Remaining items are processed normally
- Second run exit code: `0` (all items processed)
- Journal: all entries SUCCESS (no duplicates)

**Evidence to collect:**
- First run log (abort point)
- Second run log (resume and completion)
- Journal before resume: `./bin/cm-run.sh status conf/migration.properties`
- Journal after resume: `./bin/cm-run.sh status conf/migration.properties`
- Reports from both runs

**Rollback:**
1. Delete `reports/journal_t09/`
2. Clean any partial reports

**PASS criteria:**
- Resume correctly skips already-processed items
- Final journal state: all SUCCESS
- No duplicate processing
- Exit code 0 after resume completes

**FAIL criteria:**
- Resume re-processes already-SUCCESS items
- Journal corruption prevents resume
- Duplicate entries in destination

---

### T10: Large Files > 2 GiB — Hash and Temp File Handling

**Prerequisites:**
- Source items of `<TEST_ITEM_TYPE_LARGE>` with document content > 2 GiB
- Sufficient temp disk space for file staging

**Configuration:**

```properties
SOURCE_SSID=<SANDBOX_SOURCE_SSID>
DEST_SSID=<SANDBOX_DEST_SSID>
MIGRATE_ITEMTYPES=<TEST_ITEM_TYPE_LARGE>
DB_PATH=reports/journal_t10
CASCADE_DELETE_ON_MISSING=false
```

**Command:**

```bash
./bin/cm-run.sh safe conf/migration.properties
```

**Expected Result:**
- Exit code: `0`
- Log pattern: SHA-256 computed on stream (not buffered entirely in memory)
- Temp files created during transfer, cleaned up after verification
- No `OutOfMemoryError`
- Verification SHA-256 matches source and destination
- Journal: SUCCESS for all large items

**Evidence to collect:**
- Memory usage during run: `top -b -n1 | grep java`
- Run log (confirm streaming hash, no OOM)
- Temp directory state after run (should be clean)
- Verification report

**Rollback:**
1. Delete `reports/journal_t10/`
2. Clean any leftover temp files

**PASS criteria:**
- All large files migrated and verified
- SHA-256 hashes match source ↔ destination
- No `OutOfMemoryError`
- Temp files cleaned after run
- Streaming hash used (no full file in memory)

**FAIL criteria:**
- `OutOfMemoryError`
- Hash mismatch
- Temp files not cleaned
- File corruption (truncation at 2 GiB boundary)

---

### T11: JNI / Native Library Loading — SDK Connectivity

**Prerequisites:**
- IBM CM 8.7 SDK native libraries (`libcmb*.so` or equivalent) in `lib/` or system path
- `cmbicmsrvs.ini` and `cmbcmenv.properties` correctly configured for sandbox
- No prior IBM connection from this JVM instance (cold start)

**Configuration:**

```properties
SOURCE_SSID=<SANDBOX_SOURCE_SSID>
DEST_SSID=<SANDBOX_DEST_SSID>
MIGRATE_ITEMTYPES=<TEST_ITEM_TYPE>
DB_PATH=reports/journal_t11
CASCADE_DELETE_ON_MISSING=false
```

**Command:**

```bash
./bin/cm-run.sh migration conf/migration.properties
```

**Expected Result:**
- Exit code: `0`
- Log pattern: native libraries loaded successfully (no `UnsatisfiedLinkError`)
- Log pattern: IBM CM connection established (SDK login / session creation)
- Log pattern: discovery query returns items from source
- Migration completes with items processed
- No `java.lang.UnsatisfiedLinkError` or `ClassNotFoundException` for IBM classes

**Evidence to collect:**
- Full run log (confirm native library loading)
- `ldd lib/libcmb*.so` output (pre-run snapshot)
- Journal status after run
- Report

**Rollback:**
1. Delete `reports/journal_t11/`

**PASS criteria:**
- Native libraries load without error
- IBM CM SDK connects successfully
- Discovery returns real items
- Migration completes normally

**FAIL criteria:**
- `UnsatisfiedLinkError` during JNI load
- `ClassNotFoundException` for IBM SDK classes
- Connection fails despite valid config
- Discovery returns 0 items when items exist

---

### T12: Representative Performance Test

**Prerequisites:**
- Source populated with a representative mix of `<TEST_ITEM_TYPE>` items (varied sizes, metadata complexity)
- At least 100 items for meaningful throughput measurement
- System under test is otherwise idle

**Configuration:**

```properties
SOURCE_SSID=<SANDBOX_SOURCE_SSID>
DEST_SSID=<SANDBOX_DEST_SSID>
MIGRATE_ITEMTYPES=<TEST_ITEM_TYPE>
DB_PATH=reports/journal_t12
POOL_SIZE=8
WORKER_TIMEOUT_SECONDS=3600
CASCADE_DELETE_ON_MISSING=false
```

**Command:**

```bash
time ./bin/cm-run.sh safe conf/migration.properties
```

**Expected Result:**
- Exit code: `0`
- All items migrated and verified
- SHA-256 verification passes for all items
- Throughput metrics calculable from log timestamps
- Batch processing distributes work across pool workers
- No worker starvation or timeout during normal operation

**Evidence to collect:**
- Wall-clock time (`time` output)
- Run log (capture per-item start/end timestamps)
- Report: `reports/acceptance_t12/report.html`
- CPU/memory profile during run: `vmstat 5 > perf_t12_vmstat.log &`
- Batch processing log: confirm parallel worker distribution
- Journal: 100% SUCCESS

**Rollback:**
1. Delete `reports/journal_t12/`

**PASS criteria:**
- 100% items processed successfully
- All SHA-256 hashes verified
- Throughput measurable and documented
- Parallel workers utilized (not single-threaded)
- No worker timeout during normal processing

**FAIL criteria:**
- Items skipped or failed unexpectedly
- Hash mismatches
- Single-threaded processing (no parallelism)
- Worker starvation or excessive timeouts
- Throughput below <MIN_ACCEPTABLE_ITEMS_PER_MINUTE>

---

## 5. Evidence Collection Checklist

For each test case, collect and archive:

| Artifact | Location |
|----------|----------|
| Run log | `reports/acceptance_<TEST>/cm-migrator.log` (or stdout capture) |
| HTML report | `reports/acceptance_<TEST>/report.html` |
| CSV report | `reports/acceptance_<TEST>/report.csv` |
| Journal status | `./bin/cm-run.sh status conf/migration.properties` output |
| Exit code | Captured from shell |
| Environment snapshot | `java -version`, `uname -a`, `ldd lib/*.so` |
| Destination integrity | Item count before/after each test |

---

## 6. Sign-Off Table

| Test | Description | Executed By | Date | Result | Evidence Path |
|------|-------------|-------------|------|--------|---------------|
| T01 | Source EXISTS — normal verification | `<TESTER_NAME>` | `<DATE>` | ☐ PASS / ☐ FAIL | `<PATH>` |
| T02 | Source NOT_FOUND — cascade-delete guard | `<TESTER_NAME>` | `<DATE>` | ☐ PASS / ☐ FAIL | `<PATH>` |
| T03 | Auth failure — ERROR, no delete | `<TESTER_NAME>` | `<DATE>` | ☐ PASS / ☐ FAIL | `<PATH>` |
| T04 | Network timeout — ERROR, no delete | `<TESTER_NAME>` | `<DATE>` | ☐ PASS / ☐ FAIL | `<PATH>` |
| T05 | Permission error — ERROR, no delete | `<TESTER_NAME>` | `<DATE>` | ☐ PASS / ☐ FAIL | `<PATH>` |
| T06 | Producer/Discovery failure — cleanup, exit 1 | `<TESTER_NAME>` | `<DATE>` | ☐ PASS / ☐ FAIL | `<PATH>` |
| T07 | WebGUI error — FAILED state, JVM alive | `<TESTER_NAME>` | `<DATE>` | ☐ PASS / ☐ FAIL | `<PATH>` |
| T08 | SIGTERM — ordered cleanup, hook works | `<TESTER_NAME>` | `<DATE>` | ☐ PASS / ☐ FAIL | `<PATH>` |
| T09 | Resume after controlled abort | `<TESTER_NAME>` | `<DATE>` | ☐ PASS / ☐ FAIL | `<PATH>` |
| T10 | Large files > 2 GiB | `<TESTER_NAME>` | `<DATE>` | ☐ PASS / ☐ FAIL | `<PATH>` |
| T11 | JNI/Native library loading | `<TESTER_NAME>` | `<DATE>` | ☐ PASS / ☐ FAIL | `<PATH>` |
| T12 | Representative performance test | `<TESTER_NAME>` | `<DATE>` | ☐ PASS / ☐ FAIL | `<PATH>` |

---

## 7. Acceptance Gate

All 12 tests must PASS before IBM-CM Live E2E acceptance is granted. Any FAIL requires a documented root cause, fix, and re-execution of the failed test case plus any dependent tests.

**Acceptance decision:**

+☐ ACCEPTED — all tests pass

+☐ CONDITIONAL — noted exceptions (attach risk assessment)

+☐ REJECTED — blockers found (attach issue list)

+**Approver:** `<APPROVER_NAME>`
**Date:** `<APPROVAL_DATE>`
