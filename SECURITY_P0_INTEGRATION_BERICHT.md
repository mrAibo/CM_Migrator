# Abschlussbericht: Security-Baseline und P0-Integration

> GitHub: [Draft-PR #1](https://github.com/mrAibo/CM_Migrator/pull/1)
> Urteil: **Draft beibehalten; nicht nach `main` mergen, bis die dokumentierten Readiness-Blocker geschlossen sind.**

## Status

**Draft — nicht mergen.** Dieser PR bündelt die Security-Baseline und die drei gestapelten P0-Hardening-PRs für die abschließende Prüfung gegen `main`.

- Ziel-Base dieses Readiness-PRs: `hardening/security-baseline` (`f2467420a42372021647387ee8f28f858dab936f`)
- Arbeitsbranch: `hardening/integration-readiness`
- Ausgangs-HEAD: `f2467420a42372021647387ee8f28f858dab936f`
- PR #1 bleibt Draft und wird durch diese Arbeit weder gemergt noch nach `main` weitergeführt.
- Der genaue End-HEAD wird im externen Abschlussbericht/PR festgehalten; dieses Dokument beschreibt den Inhalt des Branch-Tips.

## Reviewgrundlage

- vollständiger Diff `origin/main...hardening/security-baseline`
- unabhängiger Read-only-Security-/Config-Review
- unabhängiger Read-only-Runtime-/Integrationsreview
- redaktionssicherer Scan der getrackten Konfigurationsdateien; keine Werte wurden veröffentlicht
- lokale Build-, Java-17-, Shell-, Diff- und Regressionstest-Matrix sowie Sparse-Checkout-CI-Simulation

## Integrierte Änderungen

### Security-Baseline

- repositoryweite Ignore-Regeln für Build-/Runtime-Ausgaben, lokale Konfiguration, Credentials, Schlüssel und Backups
- `SECURITY.md` mit Rotation-, History-Purge- und Local-Config-Vorgaben
- Entfernung der getrackten Dateien `conf/cmbicmenv.ini`, `conf/migration.properties` und `conf/webgui.properties`
- sichere `.example`-Vorlagen und aktualisierter lokaler Setup-Workflow
- Archiv-Ref `archive/pre-hardening-2026-07-22` zeigt auf den ursprünglichen `main`-Stand

### Cascade-Delete-Containment — PR #2

- gemeinsamer Preflight-Guard für CLI-Verifier und WebGUI
- destructive verification wird bei aktivem `CASCADE_DELETE_ON_MISSING` verweigert
- Merge-Commit: `617a464cc7a45b1ff367c07be0591a595aa07671`

### Fail-closed Tri-State-Lookup — PR #3

- explizite Zustände `EXISTS`, `NOT_FOUND`, `ERROR`
- nur classifier-bestätigtes `NOT_FOUND` kann den Delete-Pfad autorisieren
- Netzwerk-, Auth-, Timeout-, unbekannte und widersprüchliche Fehler enden in `ERROR` und verweigern Cascade Delete
- Merge-Commit: `ec88e8892b34834f3fe5c65b48178c6c0c1a1218`

### Worker-Fehlerweiterleitung — PR #5

- erster asynchroner Producer-/Discovery-Fehler wird threadsicher festgehalten
- Shutdown wird angefordert, Abschlussreports/E-Mail werden bei Abbruch übersprungen
- Fehler wird nach geordnetem Cleanup an den Main-Thread weitergereicht und führt im CLI-Pfad zu Exit-Code 1
- Merge-Commit: `4c6c87df2cb4ab4af1cf2d80b719dcc83c0683b2`

### Integration-Readiness — dieser Branch

- reale Environment-Configs, Backups, Laufzeit-/Reportartefakte, Source-Backups, generiertes JAR und historischer Keystore aus dem Tracking entfernt; lokale Operator-Dateien blieben erhalten
- neutrale, loadergerechte `.example`-Dateien und erweiterte Ignore-/Hygiene-Regeln
- WebGUI-Auth fail-closed vor Port-Bind; keine Passwortgenerierung oder Secret-Ausgabe
- WebGUI-Delete nutzt `Main.startMigration()`; Fehler werden als `FAILED` behandelt, die WebGUI-JVM bleibt aktiv
- einmalige Worker-Patch-/Apply-Artefakte samt ausschließlich dazugehörigen Tests entfernt
- Guard-Texte an den implementierten Tri-State angepasst; konservatives Blockieren bleibt unverändert
- read-only GitHub-CI für IBM-unabhängige Tests; IBM-Build bleibt lokales/privates Gate

## Vollständige Testmatrix

| Prüfung | Ergebnis |
|---|---|
| `bash -n bin/*.sh tests/*.sh` | PASS |
| `git diff --check` | PASS |
| `tests/test-cascade-delete-guard.sh` | PASS |
| `tests/test-source-lookup-classifier.sh` | PASS |
| `tests/test-verifier-source-lookup-decision.sh` | PASS, lokal mit vorhandenen Libraries |
| `tests/test-worker-failure-state.sh` | PASS |
| `tests/test-webgui-auth-fail-closed.sh` | PASS, ohne `lib/*` |
| `tests/test-webgui-delete-lifecycle.sh` | PASS |
| `tests/test-tracked-config-hygiene.sh` | PASS |
| `bash bin/compile.sh` mit Temurin 17 | PASS, lokaler/privater IBM-Library-Build |
| Sparse-Checkout-Simulation ohne materialisiertes `lib/` | PASS |

Die GitHub-CI führt nur die IBM-unabhängigen Tests aus. `test-verifier-source-lookup-decision.sh`, Gesamtbuild, JNI- und IBM-CM-Verbindungstests bleiben bewusst außerhalb des Hosted Runners. Lokale Tests ersetzen keinen IBM-CM-Live-E2E-Test.

## Lokal geschlossene Readiness-Blocker

### 1. Getrackte Konfigurationen und Laufzeitartefakte

Der redaktionssichere Scan wurde abgeschlossen; Werte wurden nicht veröffentlicht. Aus dem Tracking entfernt wurden insbesondere:

- `conf/delete_sandbox_archive.properties`
- `conf/migration.properties.bak`
- `conf/migration_FFCPDM_PRI_01_T200_S3.properties`
- `conf/migration_test_to_sandbox.properties`
- `conf/cmbicmsrvs.ini`
- `conf/ibmcmconfig.properties`
- `conf/cmbicmenv.properties` und dessen Backup
- `conf/cmlog/connectors/dklog.log`
- weitere bereits ignorierte Laufreports, Debug-Mails, Statusseiten, Source-/Script-Backups, das generierte JAR und der historische Signing-Keystore

Erhalten beziehungsweise neu erstellt wurden neutrale Vorlagen für Migration, WebGUI, `cmbcmenv.properties`, `cmbicmsrvs.ini` und optional `ibmcmconfig.properties`. `test-tracked-config-hygiene.sh` verhindert die erneute Aufnahme ignorierter lokaler Dateien und prüft leere sensible Templatefelder.

### 2. WebGUI-Authentifizierung

Bei aktivierter Authentifizierung führen fehlender Benutzer, fehlende Credentials, ein ungültiger Auth-Schalter oder ein ungültiger SHA-256-Hex-Hash nun vor dem Port-Bind zum Startfehler. Es wird kein Passwort generiert oder geloggt; Fehlertexte enthalten keinen eingegebenen Secretwert. Explizit konfigurierte Passwort-/Hash-Pfade bleiben erhalten, Klartext über `WEBGUI_ADMIN_PASSWORD` ist bevorzugt. SHA-256 bleibt ungesalzen und nur Legacy-Kompatibilität.

### 3. WebGUI-Delete-Lifecycle

Der Delete-Zweig ruft nicht mehr `Main.main()`, sondern den vorhandenen exception-basierten `Main.startMigration()`-Kern auf. `runOperation()` fängt Fehler ab, markiert den Run `FAILED` und lässt die WebGUI-JVM weiterlaufen. Der CLI-Pfad behält sein Exit-1-Verhalten.

### 4. Worker-Patch-/Apply-Artefakte

Patch, Python-/Shell-Applier und ihre zwei reinen Integrationshilfstests wurden entfernt. Die integrierte Produktionslogik und `test-worker-failure-state.sh` bleiben erhalten. Es werden nicht zwei Implementierungsvarianten parallel gepflegt.

### 5. Cascade-Guard-Texte

Java-Tri-State ist implementiert. Kommentare und Fehlermeldung beschreiben den Launcher-Guard nun als bewusstes temporäres betriebliches Containment. Sein Verhalten ist unverändert: jede aktivierte Cascade-Delete-Konfiguration bleibt bis IBM-Live-Abnahme und ausdrücklicher Freigabe blockiert.

### 6. Dependency-freie CI

`.github/workflows/test.yml` nutzt minimale Read-only-Rechte, Ubuntu, Temurin 17 und offizielle GitHub-Actions. Ein Sparse-Checkout materialisiert `lib/` nicht. Shell-Syntax, Diff-Check und IBM-unabhängige Tests laufen in CI; ein IBM-abhängiger grüner Build wird nicht vorgetäuscht.

## Weiterhin offene Blocker / Abnahmen

### 1. IBM-CM-Live-E2E

Auf einem echten, isolierten IBM-CM-System bleiben mindestens `EXISTS`, bestätigtes `NOT_FOUND`, Auth-/Netz-/Timeout-/Berechtigungsfehler als `ERROR`, Delete-Berechtigung, Worker-Cleanup und WebGUI-Delete-Lifecycle abzunehmen. Bei `ERROR` darf kein Destination-Delete erfolgen. Der Launcher-Guard blockiert Cascade Delete bis dahin vollständig.

### 2. Credential-Rotation

Möglicherweise bereits veröffentlichte Credentials, interne Endpunkte und Signing-Werte sind unabhängig von der Branch-Tip-Bereinigung zu rotieren. Das ist nicht durch Entfernen aus dem Index erledigt.

### 3. Git-History-Purge

Historische Blobs bleiben erreichbar. Ein History-Rewrite wurde in diesem Auftrag ausdrücklich nicht durchgeführt und muss separat koordiniert werden.

### 4. Produktive Performance-/JNI-Abnahme

Der lokale Build beweist weder JNI-/Native-Library-Kompatibilität noch produktive Kapazität, Latenz oder Stabilität in der Zielumgebung.

### 5. 24h-Timeout-/unbegrenzte-Warte-Policy

Nach dem nominellen Worker-Timeout kann weiter unbegrenzt auf blockierte SDK-Aufrufe gewartet werden. Dafür fehlt weiterhin eine ausdrückliche Betriebsentscheidung.

## Nicht blockierende Hinweise

- Der lokale Build kann die bestehende Deprecation-Warnung für `OperatingSystemMXBean#getTotalPhysicalMemorySize()` melden.
- `/opt/IBM/cm87_api/lib` war lokal nicht vorhanden; gebaut wurde gegen die vorhandenen, nicht für Hosted CI verwendeten `lib/*`.
- Der Verifier-Test kann eine nicht blockierende Annotation-Processing-Warnung melden.

## Vor dem Wechsel aus Draft

- [x] vollständiger Diff und betroffene Callflows inventarisiert
- [x] lokale Test-/Build-/Diff-Matrix grün
- [x] Config-/Runtime-/Backup-Tracking bereinigt und neutrale Vorlagen vorhanden
- [x] WebGUI-Auth fail-closed
- [x] WebGUI-Delete exception-basiert eingebettet
- [x] veraltete Worker-Patch-/Apply-Artefakte entfernt
- [x] temporäre Guard-Texte aktualisiert, Guard-Verhalten beibehalten
- [x] dependency-freier CI-Workflow vorhanden und lokal ohne `lib/` simuliert
- [ ] IBM-CM-Live-E2E abgeschlossen
- [ ] Credential-Rotation abgeschlossen
- [ ] Git-History-Purge koordiniert und abgeschlossen
- [ ] produktive Performance-/JNI-Abnahme abgeschlossen
- [ ] 24h-Timeout-/unbegrenzte-Warte-Policy ausdrücklich akzeptiert

## Gesamturteil

Die lokal lösbaren Readiness-Blocker dieses Auftrags sind im Branch `hardening/integration-readiness` geschlossen und durch lokale Tests beziehungsweise Strukturtests abgesichert. Der Branch ist **nicht** als produktionsbereit oder mergebereit zu deklarieren: IBM-CM-Live-E2E, Credential-Rotation, Git-History-Purge, produktive Performance-/JNI-Abnahme und die ausdrückliche Timeout-/Warte-Policy-Entscheidung bleiben offen. PR #1 bleibt Draft; weder dieser Branch noch PR #1 werden nach `main` gemergt.
