# Abschlussbericht: Security-Baseline und P0-Integration

> GitHub: [Draft-PR #1](https://github.com/mrAibo/CM_Migrator/pull/1)
> Urteil: **Draft beibehalten; nicht nach `main` mergen, bis die dokumentierten Readiness-Blocker geschlossen sind.**

## Status

**Draft — nicht mergen.** Dieser PR bündelt die Security-Baseline und die drei gestapelten P0-Hardening-PRs für die abschließende Prüfung gegen `main`.

- Base: `main` (`e95c5bd5a7fb1abcef7c4b0876bb2ced1a1bb1e1`)
- Geprüfter Funktionsstand: `4c6c87df2cb4ab4af1cf2d80b719dcc83c0683b2`
- Berichtsbranch: `hardening/security-baseline`
- Geprüfter Funktionsumfang vor den reinen Doku-Commits dieses Berichts: 46 Commits, 31 Dateien, +2238/−136
- `main` ist direkter Vorfahr; es gibt 0 `main`-only Commits.
- Synthetischer Merge-Tree: konfliktfrei.

## Reviewgrundlage

- vollständiger Diff `origin/main...hardening/security-baseline`
- unabhängiger Read-only-Security-/Config-Review
- unabhängiger Read-only-Runtime-/Integrationsreview
- redaktionssicherer Scan der getrackten Konfigurationsdateien; keine Werte wurden veröffentlicht
- lokale Build-, Java-11-, Shell-, Diff-, Apply- und Regressionstest-Matrix

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

## Vollständige Testmatrix

| Prüfung | Ergebnis |
|---|---|
| `git merge-tree --write-tree origin/main origin/hardening/security-baseline` | PASS, konfliktfrei |
| `git diff --check origin/main...HEAD` | PASS |
| geänderte Textdateien auf gemischte Zeilenenden | PASS; LF/CRLF jeweils konsistent |
| `bash -n` über alle 17 getrackten Shell-Skripte | PASS |
| `tests/test-cascade-delete-guard.sh` | PASS |
| `tests/test-source-lookup-classifier.sh` | PASS |
| `tests/test-verifier-source-lookup-decision.sh` | PASS |
| `tests/test-worker-failure-apply-script.sh` | PASS |
| `tests/test-worker-failure-patch.sh` | PASS |
| `tests/test-worker-failure-state.sh` | PASS |
| Apply-Skript gegen ursprünglichen Stand `f597800` | PASS |
| `bash bin/compile.sh` | PASS: 34 Quellen, 79 Klassen |
| `javac --release 11 ...` | PASS |
| finaler Arbeitsbaum am geprüften Funktionsstand | sauber |

## Readiness-Blocker / offene Risiken

### 1. IBM-CM-Live-E2E fehlt

Vor „Ready for review“ müssen auf einem echten IBM-CM-System mindestens geprüft werden:

- existierendes Source-Objekt → `EXISTS`
- sicher gelöschtes Source-Objekt mit realer SDK-Exception-Chain → `NOT_FOUND`
- Authentifizierungs-, Netzwerk-, Timeout- und Berechtigungsfehler → `ERROR`
- bei `ERROR` darf kein Destination-Delete erfolgen
- Worker-/Discovery-Fehler müssen Cleanup auslösen, Abschlussreports/E-Mail überspringen und im CLI-Pfad Exit-Code 1 liefern

Der Launcher-Guard bleibt bis dahin absichtlich strenger als der neue Java-Tri-State und verweigert `CASCADE_DELETE_ON_MISSING=true` vollständig. Dadurch bleibt die nun fail-closed implementierte Cascade-Funktion über die offiziell unterstützten Launcher vorerst unerreichbar.

### 2. Keine GitHub-CI-Checks

Für den geprüften Funktionsstand existieren:

- 0 Status-Contexts
- 0 Check-Runs

Die lokale Matrix ist grün, ersetzt aber keine reproduzierbare CI-Ausführung.

### 3. Bereits in `main` getrackte Config-/Backup-Dateien

Ein redaktionssicherer Scan fand weitere, durch diesen Branch **nicht neu eingeführte**, getrackte Dateien mit nichtleeren credential-/hostartigen Feldern, unter anderem:

- `conf/delete_sandbox_archive.properties`
- `conf/migration.properties.bak`
- `conf/migration.properties.example`
- `conf/migration_FFCPDM_PRI_01_T200_S3.properties`
- `conf/migration_test_to_sandbox.properties`
- `conf/cmbicmsrvs.ini`
- `conf/ibmcmconfig.properties`

Die Werte wurden im Review nicht offengelegt. Vor „Ready for review“ muss der Owner sie als Platzhalter oder echte/reversible Credentials beziehungsweise interne Produktionsdaten klassifizieren, gegebenenfalls rotieren und aus dem Tracking entfernen. `.gitignore` entfernt bereits getrackte Dateien nicht.

Das ist konkret sichtbar: `conf/cmbicmenv.properties.bac`, `conf/cmlog/connectors/dklog.log` und `conf/migration.properties.bak` werden von den neuen Ignore-Regeln getroffen, bleiben aber weiterhin getrackt. Dadurch ist auch die README-Aussage, `conf/` enthalte nur Templates beziehungsweise nicht geheime Konfiguration, noch nicht vollständig erfüllt.

Die neue WebGUI-Vorlage aktiviert Authentifizierung und setzt einen Admin-Benutzer, aber kein Passwort. Im bestehenden `AuthHandler` erzeugt dieser Pfad ein temporäres Passwort und schreibt es auf Warn-Level ins Log. Vor Freigabe muss entschieden werden, ob dieses Bootstrap-Verhalten betrieblich akzeptiert oder durch einen fail-closed Startfehler ersetzt wird.

### 4. Git-Historie und Rotation

Das Entfernen aus dem Branch-Tip löscht veröffentlichte Werte nicht aus der Git-Historie. Betroffene Credentials müssen unabhängig rotiert und historische Blobs in einer separat koordinierten History-Rewrite-Aktion bereinigt werden.

### 5. WebGUI-Delete-Aufrufer

`WebServer.runOperation()` verwendet für `safe`/`migration` korrekt `Main.startMigration()`, ruft im `delete`-Pfad aber weiterhin `Main.main()` auf. Eine jetzt propagierte Worker-Ausnahme kann dadurch `System.exit(1)` auslösen und die gesamte WebGUI-JVM beenden, statt den Run kontrolliert als `FAILED` zu markieren. Vor „Ready for review“ muss dieser Pfad auf einem Testsystem bestätigt oder minimal auf den exception-basierten Aufruf umgestellt werden.

### 6. Worker-Patch-Artefakt

`patches/p0-worker-failure-propagation.patch` ist kein gültiger `git apply`-Patch (`git apply --check` endet mit Exit 128 bei Zeile 4) und beschreibt außerdem eine ältere Implementierungsvariante mit `workerFailures` statt des aktuellen `workerFailureState`. Der vorhandene Patch-Test prüft nur Textfragmente und erkennt diese Abweichung nicht. Der Python-Applier wurde separat erfolgreich und idempotent geprüft; das veraltete Patch-Artefakt muss vor Freigabe entfernt, regeneriert oder klar als nicht ausführbare Dokumentation gekennzeichnet werden.

## Nicht blockierende Hinweise

- Die Guard-Kommentare/-Fehlermeldung beschreiben noch den Zustand vor dem Tri-State-Fix. Das Laufzeitverhalten ist konservativ korrekt, der Text sollte aber vor Freigabe aktualisiert oder bewusst als temporärer Containment-Hinweis bestätigt werden.
- `bin/apply-worker-failure-propagation.sh:20` erzeugt ShellCheck `SC2053`, weil die rechte Seite von `!=` unquoted als Pattern behandelt wird; mit den aktuellen Boolean-Literalen ist das Risiko gering.
- Nach dem nominellen 24-Stunden-Worker-Timeout wartet `Main` absichtlich unbegrenzt auf laufende SDK-Aufrufe. Der Timeout begrenzt damit nicht die Prozesslaufzeit; diese Betriebsentscheidung muss vor Freigabe ausdrücklich akzeptiert werden.
- Der lokale Build meldet die bestehende Deprecation-Warnung für `OperatingSystemMXBean#getTotalPhysicalMemorySize()`.
- `/opt/IBM/cm87_api/lib` war lokal nicht vorhanden; kompiliert wurde erfolgreich gegen `lib/*`.
- Der Verifier-Test meldet eine nicht blockierende Annotation-Processing-Warnung.

## Vor dem Wechsel aus Draft

- [x] vollständiger Diff gegen `main` inventarisiert
- [x] Stack #2 → #3 → #5 in `hardening/security-baseline` integriert
- [x] lokale Test-/Build-/Diff-Matrix grün
- [x] Merge-Tree konfliktfrei
- [ ] IBM-CM-Live-E2E abgeschlossen
- [ ] GitHub-CI vorhanden und grün oder bewusst formal ersetzt
- [ ] verbleibende getrackte Config-/Credential-Kandidaten klassifiziert
- [ ] bereits getrackte Ignore-Treffer und WebGUI-Passwort-Bootstrap bereinigt beziehungsweise akzeptiert
- [ ] Credential-Rotation und History-Purge koordiniert
- [ ] WebGUI-Delete-Aufrufer geprüft beziehungsweise korrigiert
- [ ] ungültiges/veraltetes Worker-Patch-Artefakt bereinigt
- [ ] 24h-Timeout-/unbegrenzte-Warte-Policy ausdrücklich akzeptiert
- [ ] temporäre Guard-Texte geprüft

## Gesamturteil

Der kombinierte Code-Stand ist lokal build- und regressionstestbar, der Merge-Tree ist konfliktfrei und die neue Java-Logik behandelt Source-Lookup-Fehler fail-closed. Der PR ist dennoch nicht mergebereit: IBM-CM-Live-E2E und GitHub-CI fehlen; verbleibende getrackte Credential-/Runtime-Konfigurationen, der WebGUI-Delete-Aufrufer, das ungültige Worker-Patch-Artefakt sowie die temporäre Cascade-Guard-Policy müssen vor Freigabe geklärt werden.
