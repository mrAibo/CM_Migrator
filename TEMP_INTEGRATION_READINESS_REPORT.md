# Temporärer vollständiger Integration-Readiness-Bericht

**Erstellt:** 2026-07-22T19:06:46Z
**Repository:** `mrAibo/CM_Migrator`
**Arbeitsbranch:** `hardening/integration-readiness`
**Zielbase:** `hardening/security-baseline`
**Ausgangs-HEAD:** `f2467420a42372021647387ee8f28f858dab936f`
**Geprüfter Implementierungs-HEAD vor diesem reinen Report-Commit:** `ab22291af4556eb24e91ebf2b51f394a3ea636ca`
**Draft-PR:** https://github.com/mrAibo/CM_Migrator/pull/6
**Bestehender PR #1:** weiterhin `OPEN` und Draft; nicht gemergt

> Dieser temporäre Bericht enthält bewusst keine Credential-, Passwort-, Hash-, Host-, SSID-, Token-, Keystore- oder sonstigen Secret-Werte.

## 1. Ergebnis

Die lokal lösbaren Integration-Readiness-Blocker wurden in sieben kleinen thematischen Commits geschlossen. Der Branch wurde normal, ohne Force-Push, gepusht. PR #6 ist ein Draft-PR gegen `hardening/security-baseline`; es erfolgte kein Merge nach `main` und kein Branch wurde gelöscht.

Der Stand ist lokal und in dependency-freier GitHub-CI grün. Er ist trotzdem **nicht produktionsbereit oder final mergebereit**, weil IBM-CM-Live-E2E, Credential-Rotation, Git-History-Purge, produktive Performance-/JNI-Abnahme und die ausdrückliche 24h-Timeout-/unbegrenzte-Warte-Policy-Entscheidung offen bleiben.

## 2. Reale Configs, Backups und Laufartefakte

### 2.1 Tracking-Bereinigung

Aus dem Branch-Tip entfernt wurden reale Environment-Konfigurationen, Backups, Laufreports, Debug-Mail-Ausgaben, generierte Statusseiten, historische Source-Kopien, ein generiertes JAR und der historische Signing-Keystore. Lokale Operator-Dateien wurden durch `git rm --cached` beziehungsweise die äquivalente Indexbereinigung nicht vom lokalen System gelöscht.

Besonders sensible oder environment-spezifische Pfade wurden ohne Ausgabe ihrer Inhalte bereinigt, darunter:

- `conf/delete_sandbox_archive.properties`
- `conf/migration.properties.bak`
- `conf/migration_FFCPDM_PRI_01_T200_S3.properties`
- `conf/migration_test_to_sandbox.properties`
- `conf/cmbicmsrvs.ini`
- `conf/ibmcmconfig.properties`
- `conf/cmbcmenv.properties`
- `conf/cmbcmenv.properties.bac`
- `conf/cmlog/connectors/dklog.log`
- `tools/cm-migrator.keystore`

Die Branch-Tip-Bereinigung entfernt historische Git-Blobs nicht. Möglicherweise veröffentlichte Credentials, interne Endpunkte und Signing-Werte müssen unabhängig davon rotiert werden.

### 2.2 Neutrale Vorlagen

Getrackte neutrale Vorlagen:

- `conf/migration.properties.example`
- `conf/webgui.properties.example`
- `conf/cmbcmenv.properties.example`
- `conf/cmbicmsrvs.ini.example`
- `conf/ibmcmconfig.properties.example`

Die Vorlagen enthalten nur neutrale Platzhalter, sichere boolesche Defaults und den generischen WebGUI-Benutzer. Ein Schemaabgleich der entfernten Migrationsprofile gegen die von `MigrationConfig` unterstützten Keys ergab keinen Verlust eines kanonischen unterstützten Schlüssels; die historische Form `FILTERPREDICATE` wird durch den kanonischen Vorlagenschlüssel `FILTER_PREDICATE` abgedeckt.

### 2.3 Regressionstest

`tests/test-tracked-config-hygiene.sh` stellt dependency-frei sicher, dass reale Runtime-Konfigurationspfade, Backups, Reports, Logs, Source-Snapshots, JARs und Keystores nicht erneut getrackt werden und dass sensible Felder in den Vorlagen neutral bleiben.

## 3. WebGUI-Authentifizierung

### Vorher

Bei aktivierter Authentifizierung konnten fehlender Benutzer oder fehlende Credentials zu still deaktivierter Authentifizierung beziehungsweise zur Erzeugung und WARN-Ausgabe eines temporären Passworts führen.

### Nachher

- Nur explizites `webgui.auth.enabled=false` deaktiviert Authentifizierung.
- Aktivierte Authentifizierung verlangt vor dem Port-Bind einen nichtleeren Benutzer und entweder Passwort oder exakt gültigen SHA-256-Hash.
- Fehlende oder ungültige Konfiguration führt zu einer allgemeinen `IllegalStateException` ohne Secret-Wert.
- Es wird kein Passwort erzeugt oder geloggt.
- `WEBGUI_ADMIN_PASSWORD` bleibt der bevorzugte Klartextpfad; die Property bleibt aus Kompatibilitätsgründen möglich.
- Der 64-Hex-SHA-256-Pfad bleibt als ungesalzene Legacy-Kompatibilität dokumentiert, nicht als moderne Passwort-KDF.

TDD-Nachweis:

- `tests/test-webgui-auth-fail-closed.sh`
- `tests/java/com/ibm/ecm/migration/AuthHandlerConfigurationTest.java`
- kleine dependency-freie Log4j-Teststubs unter `tests/stubs/`

Der Test war vor der Produktionsänderung erwartungsgemäß rot und danach grün.

## 4. WebGUI-Delete-Lifecycle

### Vorher

`WebServer` rief für `mode=delete` `Main.main()` auf. Bei propagierten Workerfehlern rief der CLI-Wrapper `System.exit(1)` auf und konnte damit die gesamte WebGUI-JVM beenden, bevor der vorhandene Run-Handler `FAILED` setzen konnte.

### Nachher

`WebServer` ruft für Delete den exception-basierten eingebetteten Kern `Main.startMigration(runConfigFile)` auf. Die vorhandene Exceptionbehandlung markiert den Run als `FAILED`; die WebGUI-JVM bleibt aktiv. Das CLI-Verhalten von `Main.main()` mit Exitcode `1` bleibt unverändert.

Test:

- `tests/test-webgui-delete-lifecycle.sh`

## 5. Worker-Patch-Artefakte

Nach Callsite- und Anwendbarkeitsprüfung wurden als einmalige, veraltete Integrationshilfen entfernt:

- `patches/p0-worker-failure-propagation.patch`
- `bin/apply-worker-failure-propagation.py`
- `bin/apply-worker-failure-propagation.sh`
- `tests/test-worker-failure-patch.sh`
- `tests/test-worker-failure-apply-script.sh`

Der Patch war gegen den aktuellen Stand nicht anwendbar und beschrieb eine ältere Implementierungsvariante. Er wurde von keinem Produktionslauncher und keiner CI verwendet. Die aktuelle Produktionsimplementierung `WorkerFailureState` und `tests/test-worker-failure-state.sh` bleiben bestehen.

## 6. Cascade-Delete-Guard

Aktualisiert wurden:

- `bin/cascade-delete-guard.sh`
- `bin/verify.sh`
- `bin/webgui.sh`
- README, Betriebshandbuch und Integrationsbericht

Die Texte stellen nun klar, dass die Java-Tri-State-Klassifizierung `EXISTS` / `NOT_FOUND` / `ERROR` implementiert ist. Der Launcher-Guard bleibt als bewusstes betriebliches Containment bestehen und blockiert jede aktivierte Cascade-Delete-Konfiguration bis zur IBM-CM-Live-Abnahme und ausdrücklichen Freigabe. Das konservative Verhalten wurde nicht gelockert.

## 7. Dependency-freie GitHub-CI

Workflow: `.github/workflows/test.yml`

Eigenschaften:

- minimale Berechtigung `contents: read`
- Ubuntu Runner
- Temurin 17
- nur offizielle GitHub-Actions
- keine persistierten Checkout-Credentials
- Sparse-Checkout, der `lib/` nicht materialisiert
- Shell-Syntax, `git diff --check` und alle IBM-unabhängigen Tests
- kein Upload proprietärer Libraries, Buildartefakte oder Konfiguration

Ein erster Entwurf kombinierte irrtümlich `filter` und `sparse-checkout`; laut offizieller `actions/checkout@v4`-Dokumentation überschreibt `filter` den Sparse-Checkout. Das wurde in `ab22291` korrigiert. Eine separate lokale Sparse-Worktree-Simulation bestätigte, dass `lib/` nicht materialisiert wird.

Nicht in Hosted CI ausgeführt:

- `tests/test-verifier-source-lookup-decision.sh`
- `bin/compile.sh`
- JNI-/IBM-CM-Verbindungs- und Live-E2E-Tests

Diese Gates benötigen lokal beziehungsweise privat bereitgestellte und lizenzrechtlich freigegebene IBM-/Third-Party-Libraries. Hosted CI täuscht keinen IBM-Build vor.

### GitHub-Runs

- Push: **SUCCESS** — https://github.com/mrAibo/CM_Migrator/actions/runs/29949448579
- Pull Request: **SUCCESS** — https://github.com/mrAibo/CM_Migrator/actions/runs/29949495782

## 8. Finale lokale Verifikation

Erfolgreich ausgeführt:

- `bash -n bin/*.sh tests/*.sh`
- `tests/test-cascade-delete-guard.sh`
- `tests/test-source-lookup-classifier.sh`
- `tests/test-tracked-config-hygiene.sh`
- `tests/test-verifier-source-lookup-decision.sh`
- `tests/test-webgui-auth-fail-closed.sh`
- `tests/test-webgui-delete-lifecycle.sh`
- `tests/test-worker-failure-state.sh`
- `bash bin/compile.sh` mit Temurin 17
- `git diff --check`
- Tracking-, Template-, CRLF-, Lifecycle- und Redaktionsprüfungen
- separate Sparse-CI-Simulation ohne `lib/`

Buildresultat:

- 34 Java-Quelldateien
- 79 Klassendateien
- Build erfolgreich
- verbleibende bekannte Warnung: eine JDK-Deprecation in `ConfigAutoDetector`

Alle lokalen Gates: **PASS**.

## 9. Thematische Commits

1. `b4da0f3855234e4dbedccd969f069a9424ff850b` — `security: remove tracked runtime configurations`
2. `43e406281b3fed6f4d4a15b3dbecbeb2bb930bd7` — `security: fail closed on missing webgui credentials`
3. `37b5769e337f26a2a246da411c57d57876a9e409` — `fix: keep webgui alive after delete failures`
4. `0ed4e575d383aa3c8c780ac454d28fe8830c95f5` — `chore: remove obsolete worker patch artifacts`
5. `7429304ac93ccc2582a670866c47d0fd0dd7f40b` — `ci: add dependency-free security checks`
6. `43366db25a02ed0e16ff69c36a3813728a15f816` — `docs: update integration readiness status`
7. `ab22291af4556eb24e91ebf2b51f394a3ea636ca` — `ci: correct sparse checkout configuration`

## 10. Vollständiges Pfadinventar vor diesem Report-Commit

Statuscodes: `A` hinzugefügt, `M` geändert, `D` aus dem Tracking entfernt, `R052` als Rename erkannt.

```text
A .github/workflows/test.yml
M .gitignore
M BETRIEBSHANDBUCH.md
M README.md
M SECURITY_P0_INTEGRATION_BERICHT.md
D bin/apply-worker-failure-propagation.py
D bin/apply-worker-failure-propagation.sh
D bin/build-release.sh.bac
M bin/cascade-delete-guard.sh
D bin/cm-migrator.jar
M bin/verify.sh
M bin/webgui.sh
D conf/cmbcmenv.properties
R052 conf/cmbcmenv.properties.bac -> conf/cmbcmenv.properties.example
D conf/cmbicmenv.ini.example
D conf/cmbicmsrvs.ini
A conf/cmbicmsrvs.ini.example
D conf/cmlog/connectors/dklog.log
D conf/delete_sandbox_archive.properties
D conf/ibmcmconfig.properties
A conf/ibmcmconfig.properties.example
D conf/migration.properties.bak
M conf/migration.properties.example
D conf/migration_FFCPDM_PRI_01_T200_S3.properties
D conf/migration_test_to_sandbox.properties
M conf/webgui.properties.example
D debug_mail/mail_migrate_success_1778656598582.html
D debug_mail/mail_migrate_success_1778659684173.html
D debug_mail/mail_migrate_success_1778662468028.html
D debug_mail/mail_verify_error_1778675890326.html
D debug_mail/mail_verify_success_1778656604736.html
D debug_mail/mail_verify_success_1778659937548.html
D debug_mail/mail_verify_success_1778662737415.html
D debug_mail/mail_verify_success_1778678662435.html
D deletion_report.html
D migration_plan.html
D migration_report.html
D patches/p0-worker-failure-propagation.patch
D reports/migration_FFCPDM_PRI_01_2026-02-04.html
D reports/migration_FFEPDM_DOC_99_2026-02-04.html
D reports/migration_FFEPDM_DOC_99_2026-05-12.html
D reports/migration_FFEPDM_DOC_99_2026-05-13.html
D reports/migration_FHBPDM_DOC_05_2026-01-30.html
D reports/migration_FHBPDM_DOC_05_2026-02-04.html
D reports/migration_FHBPDM_DOC_05_2026-05-07.html
D reports/migration_FHBPDM_DOC_05_2026-05-11.html
D reports/migration_FHBPDM_DOC_05_2026-05-13.html
D reports/old_mismatch_ids_FHBPDM_DOC_05.csv
D reports/summary_combined_FHBPDM_DOC_05_2026-01-30.html
D reports/summary_combined_FHBPDM_DOC_05_2026-05-07.html
D reports/summary_combined_FHBPDM_DOC_05_2026-05-08.html
D reports/summary_combined_FHBPDM_DOC_05_2026-05-11.html
D reports/summary_combined_FHBPDM_DOC_05_2026-05-13.html
D reports/verification_FHBPDM_DOC_05_2026-01-30.html
D reports/verification_FHBPDM_DOC_05_2026-05-07.html
D reports/verification_FHBPDM_DOC_05_2026-05-08.html
D reports/verification_FHBPDM_DOC_05_2026-05-11.html
D reports/verification_FHBPDM_DOC_05_2026-05-13.html
D reports/verification_non_ok_FHBPDM_DOC_05.csv
M src/com/ibm/ecm/migration/AuthHandler.java
D src/com/ibm/ecm/migration/CMConnection.java.pre-round1
D src/com/ibm/ecm/migration/CMConnection.java.pre-round2
D src/com/ibm/ecm/migration/CMConnectionPool.java.pre-round2
D src/com/ibm/ecm/migration/Consumer.java.pre-round3
D src/com/ibm/ecm/migration/ItemMigrator.java.pre-round1
D src/com/ibm/ecm/migration/ItemMigratorjava.bac_040226
D src/com/ibm/ecm/migration/Main.java.a
D src/com/ibm/ecm/migration/MigrationJournal.java.pre-round4
D src/com/ibm/ecm/migration/Producer.java.bac.040226
D src/com/ibm/ecm/migration/ProgressMonitor.java.a
D src/com/ibm/ecm/migration/ProgressMonitor.java.pre-round5
D src/com/ibm/ecm/migration/Verifier.java.bac.040226
M src/com/ibm/ecm/migration/WebServer.java
D status.html
A tests/java/com/ibm/ecm/migration/AuthHandlerConfigurationTest.java
A tests/stubs/org/apache/logging/log4j/LogManager.java
A tests/stubs/org/apache/logging/log4j/Logger.java
A tests/test-tracked-config-hygiene.sh
A tests/test-webgui-auth-fail-closed.sh
A tests/test-webgui-delete-lifecycle.sh
D tests/test-worker-failure-apply-script.sh
D tests/test-worker-failure-patch.sh
D tools/cm-migrator.keystore
D verification_report.html
```

Umfang vor diesem reinen Report-Commit:

- 84 geänderte Pfade
- 63 gelöschte Pfade
- 612 Einfügungen
- 20.178 Löschungen

## 11. Weiterhin offene Blocker und Entscheidungen

### 11.1 IBM-CM-Live-E2E

Auf einem echten, isolierten IBM-CM-System müssen mindestens `EXISTS`, bestätigtes `NOT_FOUND`, Auth-/Netz-/Timeout-/Berechtigungsfehler als `ERROR`, Delete-Berechtigung, Worker-Cleanup und WebGUI-Delete-Lifecycle abgenommen werden. Bei `ERROR` darf kein Destination-Delete erfolgen.

### 11.2 Credential-Rotation

Möglicherweise bereits veröffentlichte Credentials, interne Endpunkte, WLAN-Informationen und Signing-Werte sind unabhängig von der Branch-Tip-Bereinigung zu rotieren.

### 11.3 Git-History-Purge

Historische Blobs bleiben erreichbar. Ein History-Rewrite wurde ausdrücklich nicht durchgeführt und muss separat koordiniert werden.

### 11.4 Produktive Performance-/JNI-Abnahme

Der lokale Build beweist weder JNI-/Native-Library-Kompatibilität noch produktive Kapazität, Latenz oder Stabilität in der Zielumgebung.

### 11.5 24h-Timeout-/unbegrenzte-Warte-Policy

Nach dem nominellen Worker-Timeout kann weiter unbegrenzt auf blockierte SDK-Aufrufe gewartet werden. Dafür fehlt eine ausdrückliche Betriebsentscheidung.

## 12. Schlussurteil

Die lokal lösbaren Integration-Readiness-Blocker dieses Auftrags sind geschlossen und verifiziert. PR #6 ist korrekt als Draft gegen `hardening/security-baseline` geöffnet; PR #1 bleibt unverändert Draft. Die dependency-freien GitHub-Runs für Push und Pull Request sind grün.

Der Branch darf erst nach den fünf offenen externen Abnahmen beziehungsweise Entscheidungen als produktionsbereit oder final mergebereit bewertet werden.
