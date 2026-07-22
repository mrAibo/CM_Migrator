# Abschlussbericht: Security-Baseline und P0-Integration

> GitHub: [Draft-PR #1](https://github.com/mrAibo/CM_Migrator/pull/1)
> Integration-Readiness: [PR #6, gemergt](https://github.com/mrAibo/CM_Migrator/pull/6)
> Geprüfter Stand: `hardening/security-baseline` bei `914d28c9b3385f2f361bcb5190688a4980ac03cc`
> Urteil: **Neuer lokaler Fix-Branch erforderlich; PR #1 bleibt Draft und wird nicht nach `main` gemergt.**

## Status

**Draft — nicht mergen.** PR #6 ist per Merge-Commit in `hardening/security-baseline` integriert. PR #1 bündelt diesen Stand gegen `main`, bleibt aber wegen neu bestätigter lokaler Safety-/Hygiene-Blocker und externer Abnahmen Draft.

- PR #1 Base: `main`
- PR #1 Head: `hardening/security-baseline`
- geprüfter Head: `914d28c9b3385f2f361bcb5190688a4980ac03cc`
- GitHub-Status beim Re-Review: mergeable/clean, zwei erfolgreiche Actions-Runs, keine Review-Threads oder Kommentare
- kein Merge nach `main`, kein History-Rewrite und keine Credential-Rotation in diesem Audit

## Reviewgrundlage

- vollständiger Diff `main...HEAD` sowie konfliktfreier Merge-Tree gegen den aktuellen PR-Base-SHA
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

### Integration-Readiness — gemergter PR #6

- reale Environment-Configs, Laufzeit-/Reportartefakte, viele Backups, generiertes JAR und historischer Keystore aus dem Tracking entfernt; der frische Tip-Scan fand jedoch noch drei datierte Source-/Web-Snapshots
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

Erhalten beziehungsweise neu erstellt wurden neutrale Vorlagen für Migration, WebGUI, `cmbcmenv.properties`, `cmbicmsrvs.ini` und optional `ibmcmconfig.properties`. Der Schemaabgleich bestätigte, dass der kanonische Schlüssel `FILTER_PREDICATE` die historische Form `FILTERPREDICATE` abdeckt. `test-tracked-config-hygiene.sh` verhindert die erneute Aufnahme ignorierter lokaler Dateien und prüft leere sensible Templatefelder.

### 2. WebGUI-Authentifizierung

Bei aktivierter Authentifizierung führen fehlender Benutzer, fehlende Credentials, ein ungültiger Auth-Schalter oder ein ungültiger SHA-256-Hex-Hash nun vor dem Port-Bind zum Startfehler. Es wird kein Passwort generiert oder geloggt; Fehlertexte enthalten keinen eingegebenen Secretwert. Explizit konfigurierte Passwort-/Hash-Pfade bleiben erhalten, Klartext über `WEBGUI_ADMIN_PASSWORD` ist bevorzugt. SHA-256 bleibt ungesalzen und nur Legacy-Kompatibilität.

### 3. WebGUI-Delete-Lifecycle

Der Delete-Zweig ruft nicht mehr `Main.main()`, sondern den vorhandenen exception-basierten `Main.startMigration()`-Kern auf. `runOperation()` fängt Fehler ab, markiert den Run `FAILED` und lässt die WebGUI-JVM weiterlaufen. Der CLI-Pfad behält sein Exit-1-Verhalten.

### 4. Worker-Patch-/Apply-Artefakte

Patch, Python-/Shell-Applier und ihre zwei reinen Integrationshilfstests wurden entfernt. Die integrierte Produktionslogik und `test-worker-failure-state.sh` bleiben erhalten. Es werden nicht zwei Implementierungsvarianten parallel gepflegt.

### 5. Cascade-Guard-Texte

Java-Tri-State ist implementiert. Kommentare und Fehlermeldung beschreiben den Launcher-Guard als bewusstes temporäres betriebliches Containment. Der aktuelle Re-Review bestätigt jedoch, dass dieses Containment nicht alle direkten Java-/WebGUI-Pfade abdeckt.

### 6. Dependency-freie CI

`.github/workflows/test.yml` nutzt minimale Read-only-Rechte, Ubuntu, Temurin 17 und offizielle GitHub-Actions. Ein Sparse-Checkout materialisiert `lib/` nicht. Shell-Syntax, der committed PR-/Push-Diff und IBM-unabhängige Tests laufen in CI; ein IBM-abhängiger grüner Build wird nicht vorgetäuscht.

## Neu bestätigte lokale Blocker nach Merge von PR #6

### P0: Cascade-Delete-Containment ist nicht global

`webgui.sh` prüft nur die Standardkonfiguration. Die WebGUI kann andere Profile wählen, kopiert deren Properties in einen Run-Snapshot und ruft den Verifier direkt auf. Damit ist die geforderte vollständige Blockade einer aktivierten Cascade-Delete-Konfiguration nicht garantiert. Kleinster Root-Cause-Fix: die Sperre zusätzlich im gemeinsamen Java-Verifierpfad erzwingen und den WebGUI-Profilfall regressionsfest testen.

### P0: Verifier-Fehler- und Timeoutvertrag ist nicht fail-closed

Der äußere Catch von `Verifier.main()` loggt Exceptions, gibt sie aber nicht weiter. Der WebGUI-Run kann dadurch `COMPLETED` melden. Nach dem 24-h-Wait kann der Verifier außerdem Reports/Cleanup beginnen, ohne die Workerterminierung nachzuweisen. Kleinster Root-Cause-Fix: einen werfenden Verifier-Kern für CLI und WebGUI verwenden, Timeout/Interrupt als eindeutigen Abbruch behandeln und Cleanup erst nach definierter Terminierung ausführen.

### P1: Restliche Repository-Hygiene

Drei datierte Source-/Web-Snapshots bleiben getrackt; ältere Dokumentationsbeispiele enthalten nicht neutrale System-/Benutzerkennungen und schwache Passwortbeispiele. Die Dateien sind bereits in `main`, werden vom PR also nicht neu eingeführt, verletzen aber den verlangten sauberen Branch-Tip. Ein kleiner Hygiene-Fix soll die Snapshots entfernen, neutrale Platzhalter setzen und das passende Dateinamensmuster ignorieren/testen.

### P1: WebGUI-Run-Snapshots

Run-Snapshots übernehmen vollständige Properties einschließlich möglicher Credentials, werden mit den Standard-Dateirechten des Prozesses angelegt und nicht automatisch entfernt. `.gitignore` verhindert nur Git-Tracking. Der minimale Fix muss Run-Verzeichnis und Dateien restriktiv anlegen sowie eine sichere, Resume-/Audit-taugliche Aufbewahrungs- und Cleanup-Regel festlegen.

### P2: PR-Beschreibung

Die Beschreibung von PR #1 nennt noch den früheren Head-/CI-Zustand und muss vor `Ready for Review` auf den dann geprüften Fix-Head aktualisiert werden.

## Weiterhin offene externe Blocker / Abnahmen

### 1. IBM-CM-Live-E2E

Auf einem echten, isolierten IBM-CM-System bleiben mindestens `EXISTS`, bestätigtes `NOT_FOUND`, Auth-/Netz-/Timeout-/Berechtigungsfehler als `ERROR`, Delete-Berechtigung, Worker-Cleanup und WebGUI-Delete-Lifecycle abzunehmen. Bei `ERROR` darf kein Destination-Delete erfolgen. Bis zum lokalen Root-Cause-Fix darf kein WebGUI-/Direktaufruf als vollständiges Cascade-Delete-Containment gelten.

### 2. Credential-Rotation

Möglicherweise bereits veröffentlichte Credentials, interne Endpunkte und Signing-Werte sind unabhängig von der Branch-Tip-Bereinigung zu rotieren. Das ist nicht durch Entfernen aus dem Index erledigt.

### 3. Git-History-Purge

Historische Blobs bleiben erreichbar. Ein History-Rewrite wurde in diesem Auftrag ausdrücklich nicht durchgeführt und muss separat koordiniert werden.

### 4. Produktive Performance-/JNI-Abnahme

Der lokale Build beweist weder JNI-/Native-Library-Kompatibilität noch produktive Kapazität, Latenz oder Stabilität in der Zielumgebung.

### 5. 24h-Timeout-/unbegrenzte-Warte-Policy

Nach dem nominellen Worker-Timeout kann weiter unbegrenzt auf blockierte SDK-Aufrufe gewartet werden. Dafür fehlt weiterhin eine ausdrückliche Betriebsentscheidung.

## Dauerhafte Release-Gate-Übersicht

| Gate | Status | Benötigte Umgebung | Nächster Schritt | Erfolgskriterium | Verantwortliche Rolle | Vor Merge nach `main` |
|---|---|---|---|---|---|---|
| Lokale Safety-/Hygiene-Fixes | BLOCKED | lokaler Checkout, CI, privater IBM-Build | minimalen Fix-Branch reviewen und vollständige Regression ausführen | keine Guard-Umgehung, korrekte Fehler-/Timeoutsignale, sauberer Tip, grüne Matrix | Maintainer + Security Reviewer | ja |
| IBM-CM-Live-E2E | BLOCKED | isolierte Source-/Destination-Testsysteme | freigegebenen Safe-/Resume-/Verify-/Fehlerlauf durchführen | Journal, Logs und Reports konsistent; kein Delete bei `ERROR`; Cascade Delete bleibt aus | IBM-CM Test Lead + Operator | ja |
| Credential-Rotation | OFFEN | betroffene IAM-/CM-/DB-/Mail-/Keystore-Systeme | Eigentümer bestimmen, rotieren, alte Werte sperren und Nutzung prüfen | alte Werte ungültig, neue Werte nur außerhalb Git, Smoke-Tests grün | jeweiliger System Owner + Security | ja |
| Git-History-Purge | OFFEN | Spiegel-/Testklon und koordiniertes Wartungsfenster | Pfad-/Blob-Inventar und `git filter-repo`-Dry-Run erstellen | Scan über alle Branches/Tags ohne Zielfunde; Nutzer klonen neu | Repository Admin + Security | ja |
| JNI-/Performance-Abnahme | BLOCKED | produktionsnahe IBM-SDK-/JNI-Testumgebung | Größenklassen, Laststufen und Resume mit Metriken testen | keine Korruption/Leaks; vereinbarte SLOs und Stopkriterien erfüllt | Performance Engineer + IBM-CM Admin | ja |
| Timeout-Policy | ENTSCHEIDUNG OFFEN | Staging mit kontrolliert blockierbaren Calls | Heartbeat-/Fortschrittsmodell und gestuften Shutdown festlegen/testen | eindeutige Zustände/Exitcodes, kein falscher Erfolg, sicherer Resume/Cleanup | Application Owner + Operations/SRE | ja |

## Nicht blockierende Hinweise

- Der lokale Build kann die bestehende Deprecation-Warnung für `OperatingSystemMXBean#getTotalPhysicalMemorySize()` melden.
- `/opt/IBM/cm87_api/lib` war lokal nicht vorhanden; gebaut wurde gegen die vorhandenen, nicht für Hosted CI verwendeten `lib/*`.
- Der Verifier-Test kann eine nicht blockierende Annotation-Processing-Warnung melden.

## Vor dem Wechsel aus Draft

- [x] vollständiger Diff und betroffene Callflows inventarisiert
- [x] lokale Test-/Build-/Diff-Matrix grün
- [ ] verbleibende datierte Snapshots entfernt und ältere Beispiele vollständig neutralisiert
- [x] globales Cascade-Delete-Containment und Verifier-Fehler-/Timeoutvertrag geschlossen
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

Der Merge von PR #6 ist lokal und in CI reproduzierbar grün, aber der aktuelle Re-Review hat neue lokale P0-/P1-Blocker bestätigt. Deshalb ist ein kleiner separater Fix-Branch erforderlich. Danach müssen lokale Matrix und PR #1 erneut gegen `main` geprüft werden; die externen Gates bleiben zusätzlich offen. PR #1 bleibt Draft und wird nicht nach `main` gemergt.
