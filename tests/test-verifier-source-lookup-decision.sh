#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

javac_cmd="${JAVAC_CMD:-javac}"
java_cmd="${JAVA_CMD:-java}"

"$javac_cmd" -d "$work_dir" -cp "lib/*" -sourcepath src \
    src/com/ibm/ecm/migration/Verifier.java \
    tests/java/com/ibm/ecm/migration/VerifierSourceLookupDecisionTest.java

"$java_cmd" -cp "$work_dir:lib/*" com.ibm.ecm.migration.VerifierSourceLookupDecisionTest

awk '
    /switch \(sourceStatus\)/ { in_switch = 1; found_switch = 1 }
    in_switch && /case ERROR:/ { found_error_case = 1 }
    in_switch && /default:/ { found_default = 1 }
    in_switch && /counters\.errors\.incrementAndGet\(\)/ && found_error_case { found_error_count = 1 }
    in_switch && /return false;/ && found_error_case { found_error_return = 1 }
    in_switch && /case NOT_FOUND:/ { current_case = "NOT_FOUND" }
    in_switch && /case ERROR:/ { current_case = "ERROR" }
    in_switch && /cascadeDeleteDest\(/ {
        delete_calls++
        if (current_case != "NOT_FOUND") unsafe_delete = 1
    }
    in_switch && /Source exists - proceed with normal hash verification/ { in_switch = 0 }
    END {
        if (!found_switch || !found_error_case || !found_default || !found_error_count || !found_error_return) exit 1
        if (delete_calls != 1 || unsafe_delete) exit 1
    }
' src/com/ibm/ecm/migration/Verifier.java

printf 'Verifier source lookup decision structure: PASS\n'
