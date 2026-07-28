#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

javac_cmd="${JAVAC_CMD:-javac}"
java_cmd="${JAVA_CMD:-java}"

if [[ -d lib ]]; then
    "$javac_cmd" -d "$work_dir" -cp "lib/*" -sourcepath src \
        src/com/ibm/ecm/migration/Verifier.java \
        src/com/ibm/ecm/migration/MigrationConfig.java \
        src/com/ibm/ecm/migration/OperationalPolicy.java \
        src/com/ibm/ecm/migration/RunTerminationException.java \
        src/com/ibm/ecm/migration/WorkerTermination.java \
        tests/java/com/ibm/ecm/migration/VerifierRuntimeSafetyTest.java

    "$java_cmd" -cp "$work_dir:lib/*" com.ibm.ecm.migration.VerifierRuntimeSafetyTest
else
    printf 'SKIP: VerifierRuntimeSafetyTest Java runtime portion requires private lib/; structure checks continue\n'
fi

python3 - <<'PY'
from pathlib import Path

verifier = Path("src/com/ibm/ecm/migration/Verifier.java").read_text()
main = Path("src/com/ibm/ecm/migration/Main.java").read_text()
web = Path("src/com/ibm/ecm/migration/WebServer.java").read_text()

run = verifier[verifier.find("public static void run("):]
policy = run.find("OperationalPolicy.enforceCascadeDeleteDisabled(config);")
run_config = run.find("OperationalPolicy.validateRunConfiguration(config);")
connect = run.find("new CMConnectionPool(config)")
workers = run.find("new ThreadPoolExecutor(")
journal_write = run.find("new VerificationLogger()")
report = run.find("ReportDeliveryService.deliver(report")
if min(policy, run_config, connect, workers, journal_write, report) < 0:
    raise SystemExit("FAIL: could not locate verifier policy/callflow markers")
if "collector.collect(OperationType.VERIFICATION)" not in run:
    raise SystemExit("FAIL: Verifier reports must use OperationType.VERIFICATION")
if not policy < run_config < connect and run_config < workers and run_config < journal_write and run_config < report:
    raise SystemExit("FAIL: run policies must execute before connect/workers/journal/report")

main_run = main[main.find("public static void startMigration("):]
main_policy = main_run.find("OperationalPolicy.validateRunConfiguration(config);")
main_journal = main_run.find("new MigrationJournal(")
if main_policy < 0 or main_journal < 0 or main_policy >= main_journal:
    raise SystemExit("FAIL: migration run configuration must be validated before journal startup")
if "POOL CONTAMINATION DETECTED" in Path("src/com/ibm/ecm/migration/ItemMigrator.java").read_text():
    raise SystemExit("FAIL: equal SSIDs are supported and must not be logged as pool contamination")

if "Verifier.main(new String[]{runConfigFile})" in web:
    raise SystemExit("FAIL: WebGUI must call the throwing Verifier core, never CLI main")
if web.count("Verifier.run(runConfigFile)") < 2:
    raise SystemExit("FAIL: WebGUI verify and safe flows must call Verifier.run")

common = verifier[verifier.find("public static void run("):]
if "System.exit(" in common:
    raise SystemExit("FAIL: common verifier core must not terminate the JVM")
if 'state.status = "COMPLETED"' not in web:
    raise SystemExit("FAIL: expected WebGUI success state not found")
if 'state.status = e.getWebStatus();' not in web:
    raise SystemExit("FAIL: WebGUI must map typed terminal outcomes to their status")
if 'state.status = "INTERRUPTED";' not in web or 'state.status = "FAILED";' not in web:
    raise SystemExit("FAIL: WebGUI fallback terminal statuses are missing")

for source in (main, verifier):
    if "WorkerTermination.await(" not in source:
        raise SystemExit("FAIL: both Main and Verifier must use bounded two-stage termination")
    if "isTerminationConfirmed()" not in source and "termination.terminated()" not in source:
        raise SystemExit("FAIL: cleanup/reporting must be gated by confirmed termination")

# Lifecycle: one bounded hook owned by CLI; shared core remains hook-free.
run_cli = verifier[verifier.find("public static int runCli"):verifier.find("public static void run(")]
common = verifier[verifier.find("public static void run("):]
if "CliShutdownLifecycle" not in run_cli or "lifecycle.register()" not in run_cli:
    raise SystemExit("FAIL: CLI must own one bounded shutdown lifecycle")
if "lifecycle.finish(terminationConfirmed)" not in run_cli:
    raise SystemExit("FAIL: CLI must signal completion and remove its hook")
if "CliShutdownLifecycle" in common or "addShutdownHook" in common:
    raise SystemExit("FAIL: shared verifier core must remain hook-free")
if "ShutdownCoordinator.reset()" not in run_cli:
    raise SystemExit("FAIL: CLI must reset shutdown coordinator before run")

# WebGUI: Interrupt must block run slot
runs = web.find("private void runOperation(")
rune = web.find("// PROCESS HANDLER", runs)
runop = web[runs:rune]
if 'releaseRunSlot = true' in runop.split('catch (InterruptedException')[0] and \
   'releaseRunSlot = false' not in web[max(0, web.find('catch (InterruptedException')):min(len(web), web.find('catch (InterruptedException')+200)]:
    pass  # checked below
# ponytail: check that the InterruptedException handler sets releaseRunSlot=false
ie_start = web.find('catch (InterruptedException', runs)
ie_end = web.find('catch (Exception', ie_start)
if ie_start < 0 or ie_end < 0:
    raise SystemExit("FAIL: could not locate WebGUI InterruptedException handler")
ie_block = web[ie_start:ie_end]
if 'releaseRunSlot = false' not in ie_block:
    raise SystemExit("FAIL: WebGUI InterruptedException must set releaseRunSlot=false to block new runs")

print("VerifierRuntimeSafetyStructureTest: PASS")
PY
