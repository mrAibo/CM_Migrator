# Abschlussbericht: P0 Worker Failure Propagation

## 1. Metadaten

| Feld | Wert |
|---|---|
| Repository | `mrAibo/CM_Migrator` |
| Remote | `https://github.com/mrAibo/CM_Migrator.git` |
| Arbeitsbranch | `hardening/p0-worker-failure-propagation` |
| Basisbranch des PR | `hardening/p0-cascade-delete-tristate` |
| Draft-PR | [#5 – P0 groundwork: propagate asynchronous worker failures](https://github.com/mrAibo/CM_Migrator/pull/5) |
| Implementierungscommit | `f3eb0ff42fef2f835ecac450dd7f076d41a14305` |
| Commit-Nachricht | `fix: propagate asynchronous producer failures` |
| Berichtsstand | 22.07.2026, nach Implementierung, Tests, Build, Push und PR-Verifikation |
| Arbeitsmodus | Ponytail `full`: minimale Root-Cause-Änderung, keine neue Dependency, keine fachfremden Refactorings |

## 2. Zusammenfassung

Die fehlende Integration der vorbereiteten Worker-Fehlerweiterleitung wurde in `Producer.java` und `Main.java` vollständig umgesetzt.

Vor der Änderung konnten Exceptions aus asynchronen Discovery-Tasks lediglich geloggt oder innerhalb von `processItemType(...)` verschluckt werden. Der koordinierende Main-Thread erhielt dadurch kein zuverlässiges Fehlersignal. Ein fehlerhafter Discovery-Lauf konnte Poison Pills senden, Reports erzeugen und mit `Migration completed!` wie ein normaler Erfolg enden.

Nach der Änderung gilt:

- Der erste asynchrone Producer-/Discovery-Fehler wird atomar in einem gemeinsamen `WorkerFailureState` gespeichert.
- Ungültige ItemTypes und Discovery-Exceptions lösen einen globalen Shutdown aus.
- `processItemType(...)` lässt fatale Exceptions bis zum Discovery-Task propagieren.
- Bei Worker-Fehler oder Shutdown werden keine Poison Pills als normales Erfolgssignal versendet.
- `Main` erkennt den gespeicherten Fehler und unterdrückt Reports, E-Mail und Erfolgsmeldung.
- Producer, Discovery-Tasks und Consumer werden vor dem Schließen von Pool und Journal vollständig und ohne `shutdownNow()` beendet.
- Der erste ursprüngliche Fehler wird erst nach dem Cleanup erneut ausgelöst und bleibt als Cause erhalten.
- Der normale Erfolgspfad bleibt funktional unverändert.

Der reguläre Build und alle lokal relevanten Tests waren erfolgreich. Der Implementierungscommit wurde auf den Zielbranch gepusht und ist in Draft-PR #5 enthalten.

## 3. Ausgangszustand

Zu Beginn war unter `/home/aibo` kein lokaler Checkout des Repositorys vorhanden. Das private Repository wurde daher über die bereits konfigurierte GitHub-Authentifizierung nach `/home/aibo/CM_Migrator` geklont.

Die vorgeschriebenen Git-Prüfungen ergaben:

- `origin` zeigte auf `https://github.com/mrAibo/CM_Migrator.git`.
- Der Arbeitsbaum war sauber.
- Der Zielbranch `hardening/p0-worker-failure-propagation` existierte auf `origin`.
- Der Branch wurde ausgecheckt und per `git pull --ff-only` auf den Remote-Stand gebracht.
- Der Ausgangs-HEAD vor der Integration war `1f8dc7f`.
- Es gab keine fremden uncommitteten Änderungen, die hätten überschrieben oder gelöscht werden können.

Ein Repository-`AGENTS.md` war nicht vorhanden. Gelesen und berücksichtigt wurden insbesondere:

- `README.md`
- die Ponytail-Regeln aus dem vorgegebenen externen `AGENTS.md`
- `patches/p0-worker-failure-propagation.patch`
- `bin/apply-worker-failure-propagation.py`
- `bin/apply-worker-failure-propagation.sh`
- `src/com/ibm/ecm/migration/WorkerFailureState.java`
- `src/com/ibm/ecm/migration/Producer.java`
- `src/com/ibm/ecm/migration/Main.java`
- `src/com/ibm/ecm/migration/ShutdownCoordinator.java`
- `src/com/ibm/ecm/migration/Consumer.java`
- `src/com/ibm/ecm/migration/WebServer.java`
- `src/com/ibm/ecm/migration/CMConnectionPool.java`
- `src/com/ibm/ecm/migration/MigrationJournal.java`
- `src/com/ibm/ecm/migration/ProgressMonitor.java`
- die Worker-Failure-Shell- und Java-Tests
- weitere relevante Shell-Tests
- `pom.xml`, `bin/compile.sh`, `bin/build-release.sh` und `bin/start.sh`

Zusätzlich wurden sämtliche relevanten Aufrufer und Verwendungen gesucht:

- `new Producer(...)`
- `WorkerFailureState`
- `ShutdownCoordinator.reset()`
- `ShutdownCoordinator.requestShutdown()`
- Shutdown-/Interrupt-Pfade aus `WebServer`
- Consumer-Abbruchbedingungen
- Cleanup-Aufrufe für Worker-Pool, Connection-Pool, Journal und Monitor

## 4. Root-Cause-Analyse

### 4.1 Asynchrone Fehler waren für `Main` unsichtbar

`Producer` startet Discovery-Aufgaben in einem separaten Executor. Exceptions in diesen Tasks wurden geloggt, aber nicht an den koordinierenden Main-Thread weitergegeben. Ein Executor überträgt eine Task-Exception nicht automatisch an den Thread, der nur auf einen anderen Executor wartet.

### 4.2 `processItemType(...)` verschluckte fatale Exceptions

`processItemType(...)` hatte einen internen Catch-all für `Exception`. Dadurch sah selbst der umgebende Discovery-Task den Fehler nicht und konnte weder einen gemeinsamen Fehlerzustand setzen noch Shutdown anfordern.

### 4.3 Poison Pills konnten einen Fehler als normalen Abschluss darstellen

Nach Abschluss des Producers wurden Poison Pills in die Consumer-Queue gelegt. Ohne gemeinsamen Fehlerzustand war für den Producer nicht unterscheidbar, ob Discovery erfolgreich beendet oder intern fehlgeschlagen war. Consumer konnten den Lauf daher normal beenden, obwohl die Discovery unvollständig war.

### 4.4 `Main` erzeugte Erfolgsausgaben und Reports

`Main` wartete nur auf das Ende des Worker-Executors. Da kein Fehlerzustand vom Producer zurückkam, blieben Report-Erstellung, E-Mail-Benachrichtigung und `Migration completed!` aktiv.

### 4.5 Cleanup-Race mit separatem Discovery-Executor

Der vorbereitete Integrationsstand wartete bei Shutdown zunächst nur fünf Sekunden auf den separaten `discoveryExecutor`. Danach konnte der Producer zurückkehren, obwohl ein anderer Discovery-Task noch in einem IBM-SDK-Aufruf arbeitete oder das Journal verwendete. `Main` konnte anschließend Pool und Journal schließen.

Mögliches Interleaving:

1. Discovery-Task A schlägt fehl und fordert Shutdown an.
2. Discovery-Task B befindet sich noch in einem IBM-SDK-Aufruf.
3. Producer beendet seine Wartezeit nach fünf Sekunden.
4. Consumer beenden sich wegen Shutdown.
5. `Main.awaitTermination(...)` sieht den Worker-Pool als beendet.
6. `Main` schließt Journal und Pool, während Task B noch arbeitet.

Das hätte sekundäre SDK-/Journal-Fehler erzeugen und die ursprüngliche Fehlerursache überlagern können.

### 4.6 WebGUI-Interrupt konnte Cleanup zu früh auslösen

`WebServer` fordert beim Stoppen Shutdown an und interruptet den Thread, der `Main.startMigration(...)` ausführt. Der ursprüngliche Main-Catch stellte den Interruptstatus sofort wieder her und lief anschließend direkt in den Cleanup. Dadurch konnte derselbe Journal-/Pool-Race auch bei externem WebGUI-Shutdown auftreten.

## 5. Implementierte Änderungen

### 5.1 `Producer.java`

`WorkerFailureState` wurde als bestehende gemeinsame Dependency in `Producer` aufgenommen. Es wurde keine neue Abstraktion und keine neue Bibliothek eingeführt.

Der Konstruktor lautet nun sinngemäß:

```java
new Producer(queue, config, journal, stats, workerFailureState)
```

#### Ungültige ItemTypes

Bei ungültigem Source- oder Destination-ItemType wird jetzt:

1. eine aussagekräftige `IllegalArgumentException` erzeugt,
2. der erste Fehler über `workerFailureState.record(...)` gespeichert,
3. der Fehler vollständig geloggt,
4. `ShutdownCoordinator.requestShutdown()` aufgerufen,
5. die Discovery abgebrochen statt erfolgreich fortgesetzt.

#### Exceptions in Discovery-Tasks

Der asynchrone Task-Catch:

- speichert die Exception,
- loggt sie einschließlich Stacktrace,
- fordert globalen Shutdown an.

Es wird bewusst `Exception` und nicht pauschal `Throwable` gefangen. Die verbindliche Anforderung bezieht sich auf Exceptions. JVM-`Error`s wie `OutOfMemoryError` allgemein einzufangen, weiterzulaufen oder in normale Anwendungsfehler umzudeuten wäre eine zusätzliche, nicht angeforderte Semantikänderung.

#### Fehlerpropagation aus `processItemType(...)`

Der interne Catch, der fatale Exceptions verschluckte, wurde entfernt. `processItemType(...)` deklariert die Exception und lässt sie bis zum Discovery-Task propagieren. Damit existiert nur noch ein gemeinsamer Ort für Record, Logging und Shutdown.

#### Poison-Pill-Gate

Poison Pills werden nur noch gesendet, wenn weder globaler Shutdown noch ein gespeicherter Worker-Fehler vorliegt:

```java
ShutdownCoordinator.isShuttingDown() || workerFailureState.hasFailure()
```

Bei Fehler stoppen Consumer über den gemeinsamen Shutdown-Zustand und nicht über ein falsches Erfolgssignal.

#### Graceful Discovery-Cleanup

Nach einem Shutdown wartet der Producer bis zur natürlichen Terminierung des separaten Discovery-Executors. Laufende IBM-SDK-Aufrufe werden nicht per `shutdownNow()` unterbrochen. Dadurch kann `Producer.run()` erst enden, wenn seine Discovery-Tasks das Journal nicht mehr verwenden.

Ein Producer-Interrupt:

- wird im `WorkerFailureState` gespeichert,
- wird vollständig geloggt,
- fordert globalen Shutdown an,
- verhindert Poison Pills.

### 5.2 `Main.java`

#### Laufbezogener Zustand

Am Anfang jedes neuen Migrationslaufs werden jetzt ausgeführt:

```java
ShutdownCoordinator.reset();
WorkerFailureState workerFailureState = new WorkerFailureState();
```

Damit beeinflusst ein alter Shutdown-Zustand keinen späteren Lauf. Derselbe `WorkerFailureState` wird an den Producer übergeben.

#### Abbruch- und Report-Gate

Nach dem Warten auf die Worker setzt sowohl globaler Shutdown als auch ein gespeicherter Worker-Fehler den Lauf auf `aborted`.

Bei `aborted` werden nicht erzeugt beziehungsweise ausgeführt:

- Protokollreports,
- Legacy-Migrationsreport,
- E-Mail-Benachrichtigung,
- `Migration completed!`.

Stattdessen wird der Lauf als abgebrochen protokolliert.

#### Graceful Worker-Wartephase

Bei normalem Lauf wartet `Main` wie bisher auf den Worker-Pool.

Bei:

- Worker-Fehler,
- WebGUI-/Main-Interrupt,
- 24-Stunden-Timeout

wird Shutdown angefordert und anschließend ohne `shutdownNow()` bis zur tatsächlichen Terminierung des Worker-Pools gewartet. Da der Producer seinerseits bis zur Terminierung des Discovery-Executors wartet, entsteht folgende sichere Reihenfolge:

1. Discovery-Tasks beenden ihre laufenden SDK-Aufrufe.
2. Producer beendet sich ohne Poison Pills.
3. Consumer beenden ihre laufenden Arbeiten und stoppen wegen Shutdown.
4. Worker-Pool terminiert.
5. Pool und Journal werden geschlossen.
6. Monitor wird gestoppt.
7. Der gespeicherte ursprüngliche Fehler wird erneut ausgelöst.

#### Interrupt-Wiederherstellung

Ein externer Interrupt wird während der Cleanup-Phase nur gemerkt. Der Interruptstatus wird erst nach Pool-, Journal- und Monitor-Cleanup wiederhergestellt. So werden insbesondere blockierende Cleanup-Operationen nicht sofort durch den bereits gesetzten Interrupt übersprungen.

#### Fehlerweitergabe nach Cleanup

Nach der Ressourcenbereinigung wird ausgeführt:

```java
workerFailureState.throwIfPresent("Migration worker failed");
```

`WorkerFailureState` speichert den ersten tatsächlichen Fehler atomar. `throwIfPresent(...)` erzeugt den koordinierenden Laufzeitfehler mit dem gespeicherten Throwable als Cause. Der bestehende Fehlerpfad von `main()` loggt diesen Fehler und beendet den Prozess mit Exit-Code 1.

## 6. Sicherheitsinvarianten nach der Änderung

| Invariante | Ergebnis |
|---|---|
| Discovery-Fehler kann nicht zu `Migration completed!` führen | erfüllt |
| Discovery-Fehler kann nicht durch Poison Pills als normaler Abschluss erscheinen | erfüllt |
| Der erste tatsächliche Fehler bleibt als Cause erhalten | erfüllt |
| Reports und E-Mail werden bei Worker-Fehler unterdrückt | erfüllt |
| Cleanup erfolgt vor erneuter Fehlerauslösung | erfüllt |
| Pool und Journal werden nicht geschlossen, solange Discovery-/Worker-Tasks laufen | erfüllt |
| Kein `shutdownNow()` im regulären Worker-Fehler-, WebGUI- oder Timeout-Cleanup-Pfad; der direkte Producer-Interrupt-Pfad behält das bestehende `shutdownNow()` bei. | erfüllt |
| Normaler Erfolgspfad bleibt funktional erhalten | erfüllt |
| Externer Shutdown bleibt ein Abbruch | erfüllt |
| Keine neuen Dependencies | erfüllt |
| Keine unbeteiligten Refactorings oder Formatierungen | erfüllt |

## 7. Patch- und Wrapper-Prüfung

Der rohe Patch war nicht als normaler `git apply`-Patch direkt verwendbar. Daher wurde, wie verlangt, der vorhandene sichere Wrapper benutzt:

```bash
bin/apply-worker-failure-propagation.sh
```

Ergebnis bei der ersten Anwendung:

```text
Applied worker failure propagation changes.
```

Bei späteren Verifikationsläufen erkannte der Wrapper den bereits integrierten Zustand idempotent:

```text
Worker failure propagation already applied.
```

Die Wrapper- und Patch-Tests waren erfolgreich. Die nach unabhängiger Concurrency-Prüfung zusätzlich notwendigen graceful-wait-Sicherungen wurden minimal direkt in `Producer.java` und `Main.java` ergänzt; die vorbereiteten Patch-/Wrapper-Artefakte wurden nicht um diese nachgelagerte Lifecycle-Härtung erweitert.

## 8. Diff und Zeilenenden

Der Implementierungscommit änderte ausschließlich zwei Java-Dateien:

```text
src/com/ibm/ecm/migration/Main.java     | 23 +++++++++++++---
src/com/ibm/ecm/migration/Producer.java | 47 ++++++++++++++++++---------------
2 files changed, 45 insertions(+), 25 deletions(-)
```

Numstat:

```text
20  3   src/com/ibm/ecm/migration/Main.java
25  22  src/com/ibm/ecm/migration/Producer.java
```

`git diff --check` war erfolgreich und lieferte keinen Befund.

Die Zeilenenden wurden vor und nach der Änderung geprüft:

```text
src/com/ibm/ecm/migration/Producer.java: ... with CRLF line terminators
src/com/ibm/ecm/migration/Main.java: ... with CRLF line terminators
CRLF-only: PASS
```

Es gab keine vollständigen Dateidiffs durch CRLF-zu-LF-Konvertierung und keine unbeteiligten Whitespace-Änderungen.

## 9. Tests

Folgende vorgeschriebenen Tests wurden ausgeführt:

| Test | Ergebnis |
|---|---|
| `bash tests/test-worker-failure-state.sh` | `WorkerFailureStateTest: PASS` |
| `bash tests/test-worker-failure-patch.sh` | `PASS: worker failure propagation patch` |
| `bash tests/test-worker-failure-apply-script.sh` | `PASS: worker failure apply script` |

Zusätzlich wurden relevante vorhandene Regressionstests ausgeführt:

| Test | Ergebnis |
|---|---|
| `bash tests/test-source-lookup-classifier.sh` | `SourceLookupClassifierTest: PASS` |
| `bash tests/test-cascade-delete-guard.sh` | `PASS: cascade delete guard` |

Ein zusätzlicher nichtpersistenter Strukturcheck verifizierte die kritische Reihenfolge von:

- Record vor Shutdown,
- graceful Discovery-Wait,
- Worker-Wait vor Cleanup,
- Report-Gate vor Erfolgsausgabe,
- Cleanup vor Interrupt-Wiederherstellung,
- Cleanup vor `throwIfPresent(...)`.

Ergebnis:

```text
PASS: propagation, graceful waits, cleanup, interrupt restore, and cause rethrow ordering
```

Alle `new Producer(...)`-Aufrufe wurden kontrolliert. Der reguläre Build bestätigt zusätzlich, dass alle Aufrufer zur neuen Signatur passen.

## 10. Build

Der vorgesehene reguläre Build wurde aus `README.md` und `bin/compile.sh` ermittelt und ausgeführt:

```bash
bash bin/compile.sh
```

Reale Build-Ausgabe:

```text
Found 34 Java source files
Compilation successful! (78 class files)
Created: bin/cm-migrator.jar (227K)
Build completed successfully!
```

Im erzeugten JAR wurden unter anderem verifiziert:

```text
com/ibm/ecm/migration/Main.class
com/ibm/ecm/migration/Producer.class
com/ibm/ecm/migration/WorkerFailureState.class
```

Das versionierte Build-JAR wurde nach der Verifikation auf den Repository-Stand zurückgesetzt und nicht mitcommittet.

### IBM-Abhängigkeiten

Der Build meldete:

```text
WARNING: CM Library path not found: /opt/IBM/cm87_api/lib
Using only local libs. Some features may not compile.
```

Der optionale systemweite IBM-Pfad war auf dem Build-Host nicht vorhanden. Die im Repository vorhandenen lokalen `lib/*`-Artefakte reichten jedoch aus, um alle 34 Java-Quellen erfolgreich zu kompilieren und 78 Klassen zu erzeugen.

Es wurde kein realer End-to-End-Migrationslauf gegen ein IBM-Content-Manager-System durchgeführt.

## 11. Unabhängige Reviews

Es wurden mehrere voneinander unabhängige Read-only-Reviews durchgeführt.

### Erstes Review

Gefundene reale Punkte:

- Ein Producer-Interrupt speicherte den Fehler, forderte aber noch keinen globalen Shutdown an.
- Der Producer wartete im Fehlerpfad nur fünf Sekunden auf den separaten Discovery-Executor.

Beide Punkte wurden korrigiert.

Ein Reviewer schlug zusätzlich vor, im Discovery-Task `Throwable` statt `Exception` zu fangen. Dieser Punkt wurde nach Abgleich mit der verbindlichen Spezifikation bewusst nicht übernommen: gefordert ist Exception-Propagation; das pauschale Abfangen von JVM-`Error`s wäre eine zusätzliche Semantikänderung.

### Zweites Review

Das Review bestätigte den korrigierten Discovery-Fehlerpfad, fand aber den externen WebGUI-Interrupt-Race in `Main`: Der Main-Thread konnte den Worker-Wait verlassen und Ressourcen schließen, während der Producer noch auf Discovery-Tasks wartete.

Daraufhin wurde der gemeinsame graceful Worker-Wait für Interrupt und Timeout ergänzt und die Interrupt-Wiederherstellung hinter den Cleanup verschoben.

### Finales Follow-up

Finales Ergebnis:

```text
APPROVED
```

Bestätigt wurden:

- Worker-Fehler kann nicht als Erfolg oder Poison-Pill-Abschluss erscheinen.
- Der erste Cause bleibt erhalten.
- Pool und Journal schließen bei Worker-Fehler, WebGUI-Interrupt und Timeout erst nach Ende von Producer, Consumer und Discovery.
- Der normale Erfolgspfad bleibt unverändert.

## 12. Zwischenfälle und sichere Behandlung

### Fehlendes Java/Javac

Der erste Lauf von `tests/test-worker-failure-state.sh` scheiterte, weil auf dem Host zunächst kein `javac` verfügbar war. Es wurde ein temporäres JDK 11 außerhalb des Repositorys verwendet. Nach Tests und Build wurde die temporäre JDK-Umgebung wieder entfernt.

Der fehlgeschlagene Test wurde nach Bereitstellung des JDK vollständig wiederholt und bestand.

### Zielgerichtete Fuzzy-Ersetzungen

Bei zwei zielgerichteten Dateiersetzungen erfasste das Fuzzy-Matching jeweils eine unmittelbar angrenzende Java-Klammer beziehungsweise `finally`. Die Diff-Vorschau zeigte dies sofort. Der betroffene Bereich wurde neu gelesen und die einzelne Abweichung vor jeder weiteren Verifikation wiederhergestellt.

Anschließend bestanden Java-Kompilierung, vollständiger Build, Strukturcheck und unabhängiges Review. Es blieb kein Syntax- oder Kontrollflussfehler zurück.

### Erster Push-Versuch

Ein gewöhnlicher `git push` verwendete den vorhandenen `GITHUB_TOKEN` nicht automatisch und scheiterte mit:

```text
Schwerwiegend: could not read Username for 'https://github.com':
Kein passendes Gerät bzw. keine passende Adresse gefunden
```

Der Ansatz wurde nicht unverändert wiederholt. Stattdessen wurde der vorhandene Token ausschließlich über einen kommando-lokalen Credential-Helper an Git übergeben:

- kein Token in der Remote-URL,
- keine persistente Credential-Konfiguration,
- kein interaktiver Login,
- keine Änderung des Repository-Remotes.

Der anschließende Push war erfolgreich.

## 13. Commit und Push

Implementierungscommit:

```text
f3eb0ff42fef2f835ecac450dd7f076d41a14305
fix: propagate asynchronous producer failures
```

Autor und Committer:

```text
Hermes Backup <hermes@aibo.local>
```

Push-Ergebnis:

```text
1f8dc7f..f3eb0ff  hardening/p0-worker-failure-propagation -> hardening/p0-worker-failure-propagation
```

Nach dem Push waren lokaler und Remote-SHA identisch:

```text
local  = f3eb0ff42fef2f835ecac450dd7f076d41a14305
remote = f3eb0ff42fef2f835ecac450dd7f076d41a14305
```

Der Arbeitsbaum war anschließend sauber.

## 14. Status von Draft-PR #5

Zum Zeitpunkt der abschließenden Verifikation:

| Feld | Wert |
|---|---|
| URL | [https://github.com/mrAibo/CM_Migrator/pull/5](https://github.com/mrAibo/CM_Migrator/pull/5) |
| Status | offen |
| Draft | ja |
| Merge-State | `CLEAN` |
| Head-Branch | `hardening/p0-worker-failure-propagation` |
| Base-Branch | `hardening/p0-cascade-delete-tristate` |
| Implementierungscommit enthalten | ja |

Der letzte Implementierungscommit des PR wurde über die GitHub-API bestätigt:

```text
f3eb0ff42fef2f835ecac450dd7f076d41a14305
fix: propagate asynchronous producer failures
```

Für den Commit waren keine GitHub-Status-Contexts und keine Check-Runs konfiguriert:

```text
status contexts: 0
check runs: 0
```

GitHub kann den kombinierten Commit-Status bei null vorhandenen Contexts als `pending` darstellen. Dies ist kein fehlgeschlagener Check; es existiert schlicht kein konfigurierter CI-Check.

## 15. Verbleibende Risiken und offene Punkte

1. **Kein IBM-Live-E2E-Test:** Build und lokale Tests sind erfolgreich, aber ein realer Lauf gegen IBM Content Manager wurde nicht durchgeführt.
2. **Keine GitHub-CI-Checks:** PR #5 hat aktuell weder Status-Contexts noch Check-Runs. Die lokale Verifikation ist daher die einzige ausgeführte technische Build-/Test-Gate.
3. **Graceful Wait kann lange dauern:** Wenn ein IBM-SDK-Aufruf dauerhaft nicht zurückkehrt, wartet der Shutdown entsprechend lange. Dies ist eine bewusste Sicherheitsentscheidung: aktive SDK-Aufrufe und Verbindungen werden nicht aggressiv geschlossen.
4. **`Exception` statt `Throwable`:** JVM-`Error`s werden nicht in normale Migrationsfehler umgewandelt. Das entspricht der expliziten Exception-Anforderung und vermeidet das Weiterlaufen nach fundamentalen JVM-Fehlern.
5. **Unterstützende Apply-Artefakte:** Patch und Wrapper bilden die vorbereitete Failure-Propagation ab. Die im Review zusätzlich ergänzte Lifecycle-Härtung befindet sich im integrierten Quellcode und wurde nicht rückwirkend in diese Hilfsartefakte aufgenommen.

## 16. Schlussbewertung

Die eigentliche Ursache wurde behoben: Asynchrone Producer-/Discovery-Fehler sind nun ein expliziter Bestandteil des Migrations-Lifecycles und können nicht mehr als erfolgreicher Abschluss erscheinen.

Die Umsetzung bleibt Ponytail-konform:

- nur zwei Produktionsdateien im Implementierungscommit,
- Wiederverwendung von `WorkerFailureState` und `ShutdownCoordinator`,
- keine neue Dependency,
- keine neue Architektur- oder Abstraktionsschicht,
- keine vollständige Neuformatierung,
- keine Zeilenendungs-Massendiffs,
- ein gemeinsamer Fehlerpfad statt mehrfacher lokaler Workarounds,
- Tests, Build, Strukturcheck und unabhängiges Review erfolgreich.

Der Branch ist gepusht, Draft-PR #5 enthält den Implementierungscommit und der Repository-Arbeitsbaum war nach Abschluss sauber.
