# PR #3 – P0 Cascade Delete Source Lookup Fail-Closed Report

## 1. Executive Summary

Pull Request #3 closes a P0 cascade-delete safety defect in `Verifier`.

Before this change, the source lookup returned a boolean. Every IBM CM lookup exception was collapsed to `false`, so a confirmed missing source object could not be distinguished from a timeout, SDK failure, authentication or permission problem, DNS/network failure, or any other technical error. With cascade delete enabled, those unrelated errors could reach the destination delete path.

The implementation replaces that unsafe boolean decision with the existing tri-state model:

- `EXISTS`
- `NOT_FOUND`
- `ERROR`

Only classifier-confirmed `NOT_FOUND`, together with the existing cascade-delete enable flag, may authorize deletion. Every uncertain or technical result fails closed as `ERROR`.

- **PR:** https://github.com/mrAibo/CM_Migrator/pull/3
- **Implementation commit:** `096ebb3ef2a5f14473172a777a6130179085be4e`
- **Implementation commit message:** `fix: make verifier source lookup fail closed`
- **Branch:** `hardening/p0-cascade-delete-tristate`
- **Base branch:** `hardening/p0-cascade-delete-containment`
- **PR state at implementation completion:** open, Draft, mergeable and clean

---

## 2. Original Defect

The active verifier flow called `checkSourceExists(...)`, which returned a boolean:

- successful source retrieval returned `true`
- every exception returned `false`

That collapsed multiple semantically different states into the same value:

- the source object genuinely no longer exists
- the IBM CM server timed out
- the IBM SDK failed
- authentication or authorization failed
- access was denied
- DNS or network resolution failed
- the connection failed
- the response was unknown or malformed

The caller interpreted `false` as a missing source object. If `CASCADE_DELETE_ON_MISSING` was enabled, the same branch could call `cascadeDeleteDest(...)` and delete the destination object.

This violated the required safety invariant:

> A destination object may be cascade-deleted only when the exact requested source object is positively and unambiguously confirmed as missing.

---

## 3. Root Cause

The root cause was not the delete method itself. The root cause was the lossy boolean contract at the shared source-lookup decision point.

The safe root-cause fix therefore replaces the boolean classification where all active callers route through it, rather than adding another local guard around one symptom.

The IBM lookup operation itself remains unchanged:

1. `DKDatastoreICM.createDDOFromPID(sourcePid)`
2. metadata retrieval through `DKDDO.retrieve(...)`
3. successful retrieval returns `EXISTS`
4. an exception is classified by `SourceLookupClassifier.fromFailure(exception, sourcePid)`

The source PID continues to originate from the worklist/journal `ITEMID` and is passed as `sourcePid` to the classifier.

---

## 4. Tri-State Decision Model

| Source lookup status | Verifier behavior | Cascade delete |
| --- | --- | --- |
| `EXISTS` | Continue the existing normal hash-verification path | Never authorized by this result |
| `NOT_FOUND` | Record the source as deleted/orphaned and use the existing cascade-delete configuration | Authorized only when cascade delete is enabled |
| `ERROR` | Record an `ERROR`, increment `totalErrors`, return the item as failed, and continue other executor tasks | Never authorized |
| Unknown/default | Fail closed exactly like `ERROR` | Never authorized |
| `null` | The delete decision returns false; an unexpected switch failure is handled by the existing per-item failure path | Never authorized |

The package-private delete decision is deliberately minimal:

```java
return cascadeDeleteEnabled
        && sourceStatus == SourceLookupStatus.NOT_FOUND;
```

This is the final safety gate even if a future caller passes `ERROR`, `null`, or another status.

---

## 5. Conservative Source Lookup Classification

`SourceLookupClassifier` is intentionally biased toward false negatives because false positives may authorize destructive behavior.

### 5.1 Positive requirements

`NOT_FOUND` requires all of the following:

1. a non-null exception
2. a non-empty requested source PID
3. a complete, anchored match of a known object-not-found message form
4. the exact requested PID, protected with `Pattern.quote(...)`
5. a required separator between the not-found phrase and PID
6. every non-empty message in the traversed cause chain independently satisfying the same complete not-found/PID condition
7. completion of the cause-chain traversal within the defensive depth limit

Recognized positive shapes are limited to the known Item/Object/Document not-found forms and the known `DKC_UNKNOWN while retrieving <PID>` form.

### 5.2 Fail-closed cases

The classifier returns `ERROR` for:

- generic `not found` without the exact PID
- an unrelated PID
- PID prefix/suffix collisions such as requested `PID-1` versus `PID-10`
- embedded collisions such as `XPID-1Z`
- timeout or connection errors
- host/DNS errors
- authentication, authorization, or permission failures
- an unknown IBM SDK response
- a technical wrapper over a later not-found cause
- contradictory technical and not-found text in the same message
- a technical suffix after a not-found phrase
- a missing separator such as `Object not foundPID-1`
- a null exception
- a cause chain deeper than 16 levels
- any future unrecognized message shape

### 5.3 Cause-chain dominance

A later not-found cause cannot override an earlier technical failure.

Examples that must remain `ERROR`:

```text
Authentication failed
  caused by: Object not found: PID-1
```

```text
Connection timed out; object not found: PID-1
```

```text
Object not found: PID-1; permission denied
```

This ensures that contradictory or uncertain evidence never authorizes deletion.

---

## 6. Verifier Lifecycle

### 6.1 `EXISTS`

The normal hash-verification path remains unchanged.

### 6.2 `NOT_FOUND`

The verifier:

1. increments the existing source-deleted counter
2. records `SOURCE_DELETED`
3. checks the existing cascade-delete enable flag through `shouldCascadeDelete(...)`
4. either deletes the destination object or records it as orphaned
5. returns the item as not successfully verified

### 6.3 `ERROR`

The verifier:

1. logs the original lookup exception with stacktrace in the process log
2. records a persistent verifier result with status `ERROR`
3. increments `totalErrors`
4. returns `false` for the affected item
5. does not enter hash verification
6. does not call `cascadeDeleteDest(...)`
7. returns borrowed source and destination connections through the existing `finally` block
8. allows independent executor tasks to continue

The per-type result therefore treats the item as a failure, and the report summary cannot present the run as fully successful.

Retry and reconnect behavior was not changed.

---

## 7. Cascade Delete Call Inventory

### 7.1 Active production code

The only active production call is in `src/com/ibm/ecm/migration/Verifier.java`:

```java
if (shouldCascadeDelete(sourceStatus, cascadeDeleteEnabled)) {
    boolean deleteSuccess = cascadeDeleteDest(destConn.getDatastore(), destPid);
    // ...
}
```

It is located inside `case NOT_FOUND` and is additionally protected by:

```java
sourceStatus == SourceLookupStatus.NOT_FOUND
```

The other active `cascadeDeleteDest(...)` occurrence in `Verifier.java` is the method definition, not another caller.

### 7.2 Historical backup

`src/com/ibm/ecm/migration/Verifier.java.bac.040226` still contains the previous boolean-based call and method definition.

The file:

- has no `.java` extension
- is not compiled
- has no active caller
- was not modified because it is historical backup material outside the requested production change

### 7.3 Tests

`tests/test-verifier-source-lookup-decision.sh` contains only a structural search pattern for `cascadeDeleteDest(...)`; it does not invoke deletion.

No other active production caller was found.

---

## 8. Changed Files

### Production code

- `src/com/ibm/ecm/migration/Verifier.java`
- `src/com/ibm/ecm/migration/SourceLookupClassifier.java`

### Tests

- `tests/java/com/ibm/ecm/migration/SourceLookupClassifierTest.java`
- `tests/java/com/ibm/ecm/migration/VerifierSourceLookupDecisionTest.java`
- `tests/test-verifier-source-lookup-decision.sh`

`SourceLookupStatus.java` already contained the required `EXISTS`, `NOT_FOUND`, and `ERROR` enum values and did not require modification.

No dependency, configuration, connection-pool, launcher, guard, or unrelated production file was changed.

Implementation diff:

```text
5 files changed, 173 insertions(+), 63 deletions(-)
```

---

## 9. Test-Driven Corrections

Independent reviewers found real destructive-safety edge cases. Each applicable finding was reproduced with a failing test before the implementation was corrected.

### 9.1 PID substring collision

Unsafe behavior found:

```text
requested PID: PID-1
message: Object not found: PID-10
```

The original `contains(...)` check classified this as `NOT_FOUND`.

Correction: exact anchored PID matching with `Pattern.quote(...)`.

### 9.2 Technical wrapper over not-found cause

Unsafe behavior found:

```text
Authentication failed
  caused by: Object not found: PID-1
```

Correction: every non-empty cause message must independently be a complete positive not-found match; technical wrappers force `ERROR`.

### 9.3 Defensive cause-depth cap

Unsafe behavior found: after processing 16 positive-looking causes, a deeper technical cause could remain unexamined.

Correction: if a cause remains after the traversal cap, classification is `ERROR`.

### 9.4 Contradictory text in one message

Unsafe behavior found:

```text
Authentication failed; object not found: PID-1
```

Correction: replace substring-positive classification with a fully anchored positive grammar. Arbitrary prefixes or suffixes cannot pass.

### 9.5 Missing separator

Unsafe behavior found:

```text
Object not foundPID-1
```

Correction: require whitespace or an explicit `:`, `=`, or `-` separator before the exact PID.

### 9.6 Final review state

- final Delete-Safety review: **APPROVE**
- final Verifier-Lifecycle review: **APPROVE**

---

## 10. Validation Results

### 10.1 Classifier test

```text
bash tests/test-source-lookup-classifier.sh
SourceLookupClassifierTest: PASS
```

Covered cases include:

- confirmed not-found
- message-less nested wrapper
- timeout
- permission failure
- authentication failure
- host/DNS failure
- generic not-found
- unrelated PID
- PID prefix/suffix collisions
- contradictory cause chains
- technical prefixes/suffixes in the same message
- missing required separator
- cause-chain depth overflow
- null failure

### 10.2 Existing cascade-delete containment guard

```text
bash tests/test-cascade-delete-guard.sh
PASS: cascade delete guard
```

### 10.3 Verifier delete-decision and structural test

```text
bash tests/test-verifier-source-lookup-decision.sh
VerifierSourceLookupDecisionTest: PASS
Verifier source lookup decision structure: PASS
```

The test proves:

- `EXISTS` never authorizes delete
- `NOT_FOUND` with cascade enabled authorizes delete
- `NOT_FOUND` with cascade disabled does not authorize delete
- `ERROR` never authorizes delete
- `null` never authorizes delete
- the concrete `cascadeDeleteDest(...)` call is structurally inside the `NOT_FOUND` branch
- `ERROR/default` increments `totalErrors` and returns

### 10.4 Worker-failure scripts

The following requested regression scripts do not exist on this older stacked branch:

```text
tests/test-worker-failure-state.sh
tests/test-worker-failure-patch.sh
tests/test-worker-failure-apply-script.sh
```

Each invocation returned exit code `127` with `Datei oder Verzeichnis nicht gefunden`.

No PASS is claimed for these unavailable tests.

---

## 11. Build and Compatibility Verification

Regular project build:

```text
bash bin/compile.sh
Found 33 Java source files
Compilation successful! (78 class files)
Build completed successfully!
```

Confirmed generated classes:

```text
target/com/ibm/ecm/migration/Verifier.class
target/com/ibm/ecm/migration/SourceLookupStatus.class
target/com/ibm/ecm/migration/SourceLookupClassifier.class
```

Additional compatibility compilation:

```text
javac --release 11 ... src/com/ibm/ecm/migration/*.java
exit=0
```

Observed non-blocking warnings:

- existing deprecation warning in `ConfigAutoDetector.java`
- implicit annotation-processing warning during the focused test compile
- `/opt/IBM/cm87_api/lib` was not present locally; the successful build used repository-local `lib/*`

The build-generated tracked `bin/cm-migrator.jar` was restored before committing and is not part of the implementation diff.

---

## 12. Diff and Line-Ending Verification

Final checks before commit:

```text
git diff --check: PASS
git diff --cached --check: PASS
```

All changed and added files were verified as LF-only:

- `SourceLookupClassifier.java`
- `Verifier.java`
- `SourceLookupClassifierTest.java`
- `VerifierSourceLookupDecisionTest.java`
- `test-verifier-source-lookup-decision.sh`

No CRLF mass conversion, bare carriage returns, or unrelated whitespace changes were introduced.

The new shell test follows the repository convention and is committed with mode `100644`; it is invoked through `bash`.

---

## 13. Git and Pull Request Verification

### 13.1 Implementation commit

```text
096ebb3ef2a5f14473172a777a6130179085be4e
fix: make verifier source lookup fail closed
```

The implementation was pushed without force-push.

At implementation completion:

- local head and remote head were identical
- the working tree was clean
- the PR base was an ancestor of the head
- `base_only=0`
- the PR head was seven commits ahead of its stacked base
- local `git merge-tree --write-tree` succeeded without conflicts

### 13.2 PR metadata

- **Title:** `P0: make cascade delete source lookup fail closed`
- **URL:** https://github.com/mrAibo/CM_Migrator/pull/3
- **Head branch:** `hardening/p0-cascade-delete-tristate`
- **Base branch:** `hardening/p0-cascade-delete-containment`
- **Draft:** `true`

The PR description was updated to include:

- the original boolean safety defect
- the tri-state decision table
- the exact fail-closed error behavior
- build and test results
- missing IBM live E2E validation
- missing CI checks
- implementation commit
- remaining risks

The stale statement that the verifier was not yet integrated was removed.

### 13.3 Mergeability

GitHub reported:

```text
mergeable=true
mergeable_state=clean
```

The previously observed `mergeable:false` was not caused by a real conflict or outdated branch:

- the base is an ancestor of the head
- the base has no commits missing from the PR branch
- the local merge tree is conflict-free
- GitHub now reports the PR as clean

The best-supported explanation is a temporary or stale asynchronous GitHub mergeability calculation. No rebase or force-push was required.

---

## 14. CI and External Validation

GitHub currently exposes:

```text
status contexts: 0
check runs: 0
```

The combined status endpoint reports `pending` only because no status/check provider has submitted a result. There are no failing or running GitHub checks to inspect.

No IBM live environment was available. Therefore the following were not executed end-to-end against IBM CM:

- a real source object lookup
- a real confirmed-not-found response
- a real cascade deletion
- a real technical-failure response from the IBM SDK

The safety behavior is instead covered by dependency-free classifier tests, runtime decision tests, structural source-flow checks, regular compilation, Java 11 compatibility compilation, and independent read-only reviews.

---

## 15. Remaining Risks

### 15.1 IBM exception wording

Classification necessarily depends on known IBM exception message forms containing the exact requested PID.

Unknown wording fails closed as `ERROR`. This is safe against deletion but may create operational false negatives that require log review or future classifier updates.

### 15.2 Persistent error detail

The original exception and stacktrace are available in the process log. The persistent verifier record contains the generic lookup-error description rather than the full original stacktrace.

### 15.3 No IBM live E2E

The real IBM CM lookup and delete lifecycle has not been exercised in a live IBM environment.

### 15.4 Historical backup

`Verifier.java.bac.040226` retains the legacy boolean implementation. It is inactive and uncompiled but may confuse future text searches unless its historical nature is understood.

### 15.5 Pre-existing successful cascade-reporting semantics

A successful `NOT_FOUND -> CASCADE_DELETED` item is a per-type non-success but does not increment `totalErrors`. This pre-existing reporting behavior is separate from the new fail-closed `ERROR` path, which consistently increments both error views.

### 15.6 Stacked-branch containment

Supported launchers remain contained by the base-branch cascade-delete guard. The enabled delete decision is validated by tests and source-structure checks rather than a live IBM delete.

---

## 16. Final Conclusion

The P0 root cause has been fixed at the shared source-lookup decision point.

The destructive invariant is now explicit and independently reviewed:

> `cascadeDeleteDest(...)` can be reached only for classifier-confirmed `NOT_FOUND` of the exact requested source PID and only when cascade delete is enabled.

Timeouts, IBM SDK failures, authentication/permission failures, DNS/network failures, contradictory causes, malformed messages, unknown future cases, and uncertain lookup results all fail closed as `ERROR` and cannot authorize destination deletion.
