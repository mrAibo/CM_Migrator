# Betriebshandbuch CM Migrator

Dieses Handbuch beschreibt den auf `hardening/security-baseline` implementierten Stand. Für den kompakten Einstieg siehe [README.md](README.md).

## 1. Zweck und Geltungsbereich

Abgedeckt sind:

- Build und lokaler Start der Java-Anwendung;
- Migration und expliziter Source-Delete-Betrieb;
- Verifikation einschließlich Source-Lookup-Tri-State und Cascade Delete;
- H2-Journal, Wiederaufnahme und Verifikationslog;
- Reports, Logging, Monitoring, optionale E-Mail und WebGUI;
- Shutdown, Fehlerweiterleitung, Wiederanlauf, Backup und Wartung;
- lokale Tests und ein noch ausstehendes IBM-Live-E2E-Abnahmeverfahren.

Angenommen werden eine Linux/Unix-artige Umgebung mit Bash, ein JDK ab Version 11, lokal verfügbare IBM-CM-8.7-SDK-Bibliotheken und zwei getrennt konfigurierte IBM-Content-Manager-Systeme. Die Laufzeit muss mindestens der Version des mit `bin/compile.sh` verwendeten Build-JDKs entsprechen. IBM-SDK-, Native-Library-, DNS-, Netzwerk-, Authentifizierungs- und Berechtigungsdetails sind installationsspezifisch.

Nicht abgedeckt sind die Installation oder Administration von IBM Content Manager, fachliche Migrationsregeln, Firewall-/Reverse-Proxy-Betrieb, öffentliche Webexposition und produktive Kapazitätsplanung. Für die Härtungen aus PR #2, #3 und #5 fand kein kontrollierter IBM-CM-Live-E2E-Test statt. Lokale Unit-, Shell- und Strukturtests ersetzen diesen Test nicht.

## 2. Systemübersicht

Der empfohlene CLI-Fluss ist:

```text
conf/migration.properties
          |
          v
  bin/cm-run.sh
    |           |
    |           +--> bin/verify.sh --> Verifier
    v
bin/start.sh --> Main
                 |
                 +--> Producer / Discovery --(begrenzte Queue)--> Consumer
                 |                                               |
                 |                                               v
                 |                                         ItemMigrator
                 |                                               |
                 +------------------- CMConnectionPool <---------+
                 |
                 +--> MigrationJournal (H2)
                 +--> ProgressMonitor / JMX / status.html
                 +--> Reports --> optionale E-Mail
```

`Verifier` ist ein eigener Laufpfad. `cm-run.sh safe` koordiniert Migration, Verifikation, Journalstatus und gegebenenfalls eine Non-OK-Reverifikation. Der WebGUI-Modus `safe` ruft dagegen nur `Main.startMigration(...)` und anschließend `Verifier.main(...)` in derselben JVM auf.

## 3. Komponenten und Verantwortlichkeiten

| Komponente | Verantwortung |
|---|---|
| `bin/cm-run.sh` | Operator-Wrapper für `migration`, `delete`, `verification`, `verification-nonok`, `safe` und `status`; H2-Risikoprüfungen und optionale Hintergrundservices |
| `bin/start.sh` | niedriger Java-Launcher für `Main`; kein eigener Cascade-Delete-Guard |
| `bin/verify.sh` | Java-Launcher für `Verifier`; Cascade-Delete-Guard und Verify-JVM-Schalter |
| `bin/webgui.sh` | Launcher für `WebServer`; localhost als Java-Default, Port-/Bind-Optionen und Guard für die Standardkonfiguration |
| `bin/monitor.sh` | nicht authentifizierter statischer Python-HTTP-Server aus dem Projektverzeichnis |
| `Main` | Konfiguration, Journal, Queue, Pool, Worker-Lifecycle, Cleanup, normale Reports und E-Mail |
| `Producer` | ItemType-Validierung, zweipassige SDK-Discovery, Resume-Skip, Queue-Befüllung und Poison Pills bei normalem Abschluss |
| `Consumer` | Batchentnahme, Migration oder Source Delete, Retry und Journalstatus |
| `ItemMigrator` | Source-/Destination-Objekte, Metadaten, Content, Hash, Transaktionen und Source Delete |
| `Verifier` | Destination- und gegebenenfalls Source-Lookup, SHA-256-Vergleich, Tri-State, Verifikationslog, Reports und optionales Cascade Delete |
| `SourceLookupClassifier` | konservative Klassifikation einer Source-Lookup-Exception als `NOT_FOUND` oder `ERROR` |
| `CMConnectionPool` | getrennte Source-/Destination-Pools, Borrow-Timeouts, Validierung und Reconnect |
| `MigrationJournal` | asynchrones H2-`AUDIT_LOG` pro Source-ItemType und Resume-Cache |
| `VerificationLogger` | asynchrones H2-`VERIFICATION_LOG` |
| `ReportGenerator`, `ProtocolReportGenerator` | Top-Level-HTML und Protokollberichte |
| `ProgressMonitor` | Konsole und atomisch aktualisiertes `status.html` |
| `MigrationMetrics` | lokales JMX-MBean `com.ibm.ecm.migration:type=MigrationEngine` |
| `EmailNotifier` | HTML-Mail über `mutt` oder `mailx`; Mailfehler sind nicht fatal |
| `WebServer`, `AuthHandler` | WebGUI, Config-API, Run-Snapshots, Prozesszustand und Basic Auth |
| `ShutdownCoordinator` | globale, kooperative Shutdown-Anforderung |
| `WorkerFailureState` | speichert atomar den ersten Producer-/Discovery-Fehler und fordert Shutdown an |
| `ResourceGuardian` | verfolgt temporäre Dateien und offene Ressourcen für Cleanup |

`WorkerFailureState` erfasst nicht pauschal jede Consumer-Exception. Die implementierte Weiterleitung betrifft Producer-/Discovery-Fehler; per Item behandelte Consumer-Fehler erscheinen im Journal/Report und führen nicht zwingend zu einem Nonzero-Prozess-Exit.

### 3.1 Vollständiges Launcher-/Skriptinventar

| Datei | Einordnung |
|---|---|
| `bin/cm-run.sh` | empfohlener Operator-Wrapper |
| `bin/start.sh` | direkter Migrations-/Delete-Launcher |
| `bin/verify.sh` | direkter Verify-Launcher mit Guard |
| `bin/webgui.sh` | WebGUI-Launcher |
| `bin/monitor.sh` | statischer HTTP-Monitor |
| `bin/compile.sh` | lokaler Compile-/JAR-Build |
| `bin/cascade-delete-guard.sh` | eigenständig testbarer Containment-Guard |
| `bin/build-release.sh` | Release-Paketierung; Distribution ist nicht vollständig belegt |
| `bin/deploy-source.sh` | erstellt ein Quellpaket unter dem Benutzer-Downloadverzeichnis; kein Laufzeit-Launcher |
| `bin/apply-worker-failure-propagation.sh` und `.py` | historische/technische Patch-Anwendung; kein Betriebsweg des bereits integrierten Branches |
| `bin/build-release.sh.bac` | historisches Backup; nicht ausführen |
| `run.sh` | historischer Launcher für den separaten `com.example.migrator`-Prototyp; erwartet ein von `bin/compile.sh` nicht erzeugtes Root-JAR |
| `bin/META-INF/MANIFEST.MF`, `bin/cm-migrator.jar` | getrackte/erzeugte Buildartefakte, keine Skripte |

`bin/common.sh` ist im aktuellen Branch nicht vorhanden und daher weder Launcher noch gemeinsame Runtime-Abhängigkeit.

`FastBatchItemMigrator` akzeptiert im Legacy-Tree `-c`/`--config`, `-i`/`--itemType` und `-h`/`--help`. `run.sh` reicht diese Argumente weiter, ist im aktuellen Checkout aber kein unterstützter ausführbarer Pfad, weil das erwartete Root-JAR nicht durch `bin/compile.sh` entsteht.

### 3.2 Vollständiges Java-Klasseninventar

- **Start, Konfiguration und Konsole:** `Main`, `MigrationConfig`, `ConfigAutoDetector`, `ConsoleUI`, `SdkCapabilityProbe`.
- **Migration und CM-Zugriff:** `Producer`, `Consumer`, `ItemMigrator`, `MigrationItem`, `CMConnection`, `CMConnectionPool`, `PermanentMigrationException`.
- **Lifecycle und Status:** `ShutdownCoordinator`, `WorkerFailureState`, `ResourceGuardian`, `MigrationStats`, `MigrationMetrics`, `MigrationMetricsMBean`, `ProgressMonitor`.
- **Journal und Reports:** `MigrationJournal`, `VerificationLogger`, `ReportGenerator`, `ProtocolReportGenerator`, `AuditProtocolGenerator`, `ProtocolData`.
- **Verifikation und Sicherheit:** `Verifier`, `SourceLookupStatus`, `SourceLookupClassifier`.
- **Web und Benachrichtigung:** `WebServer`, `AuthHandler`, `EmailNotifier`.
- **Legacy-/Diagnosewerkzeuge im Produktionsquellbaum:** `RemigrationTool`, `SDKTest`, `StandaloneParserTest`. Diese Namen belegen keine Empfehlung als Operatorweg.
- **Separater Maven-/Legacy-Prototyp unter `src/main/java`:** `FastBatchItemMigrator`, `ConfigManager`, `ConnectionManager`, `com.example.migrator.journal.MigrationJournal`, `MigrationStatus`, `JdbcItemReader`, `ApiItemWorker`. Dieser Tree wird von `bin/compile.sh` nicht gebaut und gehört nicht zur oben beschriebenen aktuellen Laufzeitarchitektur.

## 4. Verzeichnisse und Dateien

| Typ/Pfad | Zweck | Sensitivität und Aufbewahrung | Backup / Git |
|---|---|---|---|
| `conf/*.properties`, `conf/*.ini` | Anwendung, WebGUI und IBM-SDK | können Benutzer, Passwörter, SSIDs und interne Endpunkte enthalten; `0600` | verschlüsselt und getrennt sichern; produktive Dateien nicht versionieren |
| `conf/*.example` | neutrale Vorlagen | keine produktiven Werte eintragen | dürfen versioniert werden |
| `conf/log4j2.xml` | Log-Level und Rotation | kann Pfade beeinflussen | mit Release sichern; darf versioniert werden |
| `lib/*.jar` | lokale IBM-, H2-, Logging- und JDBC-Abhängigkeiten | Herkunft/Lizenz/Integrität prüfen | versionsgebunden sichern; keine unkontrollierte Ersetzung |
| `${DB_PATH}/journal_<ItemType>.mv.db` | H2-`AUDIT_LOG` und `VERIFICATION_LOG` | hochsensitiv: PIDs, Ziel-IDs, Hashes, Fehlertexte | nur bei gestopptem Prozess konsistent sichern; nicht versionieren |
| `migration.log` | Hauptlog | kann SSIDs, Benutzer, PIDs, Pfade und Exceptiontexte enthalten | nach Policy rotieren/sichern; nicht versionieren |
| `verification_errors.log` | ERROR-only-Verifikationslog | wie Hauptlog | nach Policy rotieren/sichern; nicht versionieren |
| `reports/` | HTML-/CSV-Ergebnisse und Templates | Reports können PIDs, Metadaten und Prüfergebnisse enthalten | Templates versionierbar; Laufreports extern archivieren, nicht neu committen |
| `status.html`, `.status.html.tmp` | laufendes Dashboard und atomarer Zwischenstand | Betriebs-/Systemdaten | kurzlebig; nicht versionieren |
| `data/webgui-runs/*.properties` | WebGUI-Run-Snapshots | **hochsen­sitiv**, vollständige Properties einschließlich Credentials möglich | restriktiv schützen; nach Freigabe bereinigen; nicht versionieren |
| `debug_mail/*.html` | lokal erzeugte E-Mail-Bodies | Reportdaten/PIDs möglich | kurzzeitig aufbewahren; nicht versionieren |
| `target/`, `bin/cm-migrator.jar`, `compile.log` | Build-Artefakte | kein Primärdatenbestand | reproduzierbar; nicht versionieren |
| temporäre Contentdateien | Fallback für Content-Upload | können Originalcontent enthalten | nach Lauf auf Reste prüfen; nie versionieren |
| `webapp/` | statische WebGUI-Dateien | keine Secrets einbetten | mit Release versionieren/sichern |
| `tools/cm-migrator.keystore` | historisch getracktes Signing-Artefakt | als potenziell kompromittiert behandeln | nicht als produktiven Keystore übernehmen; extern ersetzen/rotieren |
| `*.bak`, `*.bac`, historische Source-/Reportkopien | Altbestand | kann alte Credentials oder interne Daten enthalten | klassifizieren und kontrolliert bereinigen; nicht als aktive Config verwenden |

Code-Defaults für temporäre Dateien: kleine Dateien verwenden `java.io.tmpdir`; ab `cm.migrator.tmpdir.largeThresholdBytes` wird standardmäßig `/var/tmp/cm-migrator` verwendet. `bin/start.sh` setzt `java.io.tmpdir` auf `/dev/shm`. Diese Pfade müssen beschreibbar sein und ausreichend Platz besitzen.

### 4.1 Vollständiges Konfigurationsinventar

- **Vorlagen:** `conf/migration.properties.example`, `conf/webgui.properties.example`, `conf/cmbicmenv.ini.example`.
- **Logging:** `conf/log4j2.xml`, `conf/cmblogconfig.properties`, `conf/cmlog/connectors/dklog.log`.
- **IBM-Runtime-Ressourcen:** `conf/cmbcmenv.properties`, `conf/cmbicmsrvs.ini` sowie eine historische `.bac`-Kopie.
- **Bereits getrackte lokale/Legacy-Konfigurationen:** fünf weitere Propertydateien für Delete-, IBM-CM-, Backup- und umgebungsspezifische Migrationsprofile. Ihre internen Bezeichner und Werte werden hier bewusst nicht reproduziert. Sie sind als credentialfähig zu klassifizieren, zu rotieren und nicht als neutrale Vorlagen zu verwenden.
- **Build-/Legacy-Konfiguration:** `pom.xml`, `tools/proguard.conf` und `src/main/resources/log4j2.xml`. Letztere gehört zum separaten `com.example.migrator`-Tree; für dessen `migrator.properties` existiert keine neutrale Vorlage.

### 4.2 Vollständiges Testinventar

- Shell-Runner: `tests/test-cascade-delete-guard.sh`, `tests/test-source-lookup-classifier.sh`, `tests/test-verifier-source-lookup-decision.sh`, `tests/test-worker-failure-state.sh`, `tests/test-worker-failure-patch.sh`, `tests/test-worker-failure-apply-script.sh`.
- Java-Testprogramme: `SourceLookupClassifierTest`, `VerifierSourceLookupDecisionTest`, `WorkerFailureStateTest` unter `tests/java/com/ibm/ecm/migration/`.

### 4.3 Berichts- und Dokumentationsinventar

- Technische Top-Level-Dokumente: `README.md`, `BETRIEBSHANDBUCH.md`, `SECURITY.md`, `ARCHITEKTUR.md`, `ANLEITUNG_SLES15.md`, `SECURITY_P0_INTEGRATION_BERICHT.md`, `PR3_FAIL_CLOSED_REPORT.md`, `P0_WORKER_FAILURE_PROPAGATION_BERICHT.md`.
- Getrackte generische Laufartefakte: `migration_report.html`, `deletion_report.html`, `verification_report.html`, `migration_plan.html`, `status.html` und Dateien unter `debug_mail/`.
- `reports/` enthält 26 getrackte Dateien. Neutrale Vorlagen sind `reports/templates/migration_protocol_template.html`, `verification_protocol_template.html`, `summary_protocol_template.html`, die Template-Dokumentation und ein Bildasset. Die übrigen historischen, laufbezogenen Reports werden wegen interner Bezeichner nicht einzeln in diese Dokumentation kopiert.
- `patches/p0-worker-failure-propagation.patch` ist ein technisches Integrationsartefakt, kein Laufreport und kein Operatorbefehl.

### 4.4 Verzeichnisinventar

Getrackte Projektbereiche sind `src/`, `bin/`, `conf/`, `lib/`, `tests/`, `webapp/`, `reports/`, `patches/`, `tools/` und `debug_mail/`. `src/` enthält den aktuellen `com.ibm.ecm.migration`-Tree und zusätzlich den separaten Legacy-Maven-Tree unter `src/main/java`. Zur Laufzeit beziehungsweise beim Build entstehen zusätzlich `data/`, `logs/`, `target/`, temporäre Contentpfade und gegebenenfalls `java_env/`. Ein `docs/`-Verzeichnis und GitHub-Workflow-Verzeichnis sind im aktuellen Branch nicht vorhanden.

## 5. Installation und Erstinbetriebnahme

### 5.1 Software bereitstellen

```bash
git clone https://github.com/mrAibo/CM_Migrator.git
cd CM_Migrator
git checkout hardening/security-baseline
git status --short
```

Ein leerer letzter Output ist vor lokaler Konfiguration erwartet.

### 5.2 Java und Libraries prüfen

```bash
java -version
javac -version
test -r lib/cmbicmsdk81.jar
compgen -G 'lib/h2-*.jar' >/dev/null
```

Der Build benötigt JDK 11+. `pom.xml` deklariert Source/Target 11, aber `bin/compile.sh` ruft `javac` ohne `--release`/`-target` auf. Sein JAR benötigt deshalb mindestens die Version des verwendeten Build-JDKs; für ein Java-11-Artefakt mit JDK 11 bauen oder den Bytecode separat verifizieren. Die Java-Version muss zusätzlich mit dem installierten IBM-CM-SDK und dessen Native Libraries kompatibel sein. Ein vorhandenes JAR beweist keine JNI- oder Serverkompatibilität.

### 5.3 Vorlagen kopieren und Rechte setzen

```bash
cp conf/migration.properties.example conf/migration.properties
cp conf/webgui.properties.example conf/webgui.properties
chmod 600 conf/migration.properties conf/webgui.properties
chmod +x bin/*.sh
```

`conf/cmbicmenv.ini.example` wird nicht direkt durch den Java-Code geladen. Der Code sucht `cmbicmsrvs.ini` und `cmbcmenv.properties` im Arbeitsverzeichnis oder Classpath; diese IBM-spezifischen Dateien sicher aus der freigegebenen Installation bereitstellen.

### 5.4 Konfiguration ausfüllen

Mindestens explizit setzen:

- `SOURCE_SSID`, `DEST_SSID`;
- Source-/Destination- oder gemeinsame Credentials;
- ein explizites `MIGRATE_ITEMTYPES`-Mapping;
- `DB_PATH` auf ein beschreibbares, gesichertes Verzeichnis;
- `CASCADE_DELETE_ON_MISSING=false`;
- passende Parallelitäts- und Poolwerte.

Ein leeres Mapping ist im `MigrationConfig`-Kommentar zwar als „alle“ beschrieben, aber `Producer` führt damit keine Discovery aus und `Verifier` bricht ab. Unterstützter Betrieb verlangt daher ein explizites Mapping.

### 5.5 Build und read-only Statusprüfung

```bash
bash bin/compile.sh
./bin/cm-run.sh status conf/migration.properties
```

`bin/compile.sh` kompiliert ausschließlich `src/com/ibm/ecm/migration/`. Der Maven-/Legacy-Tree unter `src/main/java/com/example/migrator/` ist weder im erzeugten `bin/cm-migrator.jar` noch in der aktuellen Testmatrix enthalten.

`status` prüft Journalinhalte, nicht IBM-Konnektivität. H2-Warnungen sind als fehlgeschlagener Preflight zu behandeln, auch wenn der Wrapper einzelne H2-Abfragen intern auf `0` zurückfallen lässt.

### 5.6 Sicherer Testlauf

Es gibt keinen allgemeinen Migrations-Dry-Run. `DRY_RUN` wirkt nur im expliziten Source-Delete-Pfad. Ein sicherer Erstlauf braucht deshalb eine isolierte IBM-Testumgebung und kleine, nicht produktive Source-/Destination-Objekte:

```bash
./bin/cm-run.sh safe conf/migration.properties
printf 'exit=%s\n' "$?"
```

Danach Exitcode, `migration.log`, Journal, `verification_errors.log` und Reports gemeinsam prüfen. Exit `0` allein bedeutet weder null Consumer-Fehler noch null Verify-Abweichungen.

## 6. Konfigurationsreferenz

### 6.1 Auflösung und Profile

`MigrationConfig` akzeptiert bei vielen Keys zusätzlich die Variante ohne Unterstriche. Explizite Propertywerte gewinnen vor Profilwerten. Ungültige Integer werden protokolliert und auf den Code-Default zurückgesetzt. Java-Properties unterstützen keine Inline-Kommentare hinter Werten; Kommentare gehören in eigene Zeilen.

| `PROFILE` | `THREAD_COUNT` | `BATCH_SIZE` | `QUEUE_SIZE` |
|---|---:|---:|---:|
| `KLEIN` | 5 | 50 | 1.000 |
| `MITTEL` | 20 | 200 | 5.000 |
| `GROSS` | 50 | 500 | 10.000 |
| `EXTREM` | 100 | 1.000 | 20.000 |
| `ULTI` | 200 | 2.000 | 50.000 |

Für `PROFILE` selbst existiert kein Code-Default. Der Vorlagenwert ist keine automatische Vorgabe des Codes.

### 6.2 Source CM

| Property | Pflicht | Standardwert | Bedeutung | Sicherheitshinweis |
|---|---|---|---|---|
| `SOURCE_SSID` | ja | kein verifizierter Standardwert | Source-Systemkennung | leere SSID wird fail-fast verweigert |
| `SOURCE_USER` | nein, wenn `CONNECT_USER` gesetzt | Fallback `CONNECT_USER` | Source-Benutzer | nicht versionieren |
| `SOURCE_PASSWORD` | nein, wenn andere Passwortquelle gesetzt | kein verifizierter Standardwert | Source-Klartextpasswort | nur restriktive Datei; bevorzugt Secret-Management |
| `SOURCE_PASSWORD_CRYPT` | nein | kein verifizierter Standardwert | reversibel kodiertes Legacy-Passwort | **keine Verschlüsselung** |
| `CONNECT_USER` | ja, wenn keine getrennten User | leer | gemeinsamer Fallback-User | nicht versionieren |
| `CONNECT_PASSWORD` | nein, wenn `_CRYPT` oder getrennt gesetzt | kein verifizierter Standardwert | gemeinsames Klartextpasswort | nicht versionieren |
| `CONNECT_PASSWORD_CRYPT` | nein | kein verifizierter Standardwert | gemeinsames reversibles Legacy-Passwort | rotieren und ersetzen |

### 6.3 Destination CM

| Property | Pflicht | Standardwert | Bedeutung | Sicherheitshinweis |
|---|---|---|---|---|
| `DEST_SSID` | ja für Migration/Verify | kein verifizierter Standardwert | Destination-Systemkennung | leere SSID wird fail-fast verweigert |
| `DEST_USER` | nein, wenn `CONNECT_USER` gesetzt | Fallback `CONNECT_USER` | Destination-Benutzer | Least Privilege |
| `DEST_PASSWORD` | nein, wenn andere Passwortquelle gesetzt | kein verifizierter Standardwert | Destination-Klartextpasswort | nicht versionieren |
| `DEST_PASSWORD_CRYPT` | nein | kein verifizierter Standardwert | reversibel kodiertes Legacy-Passwort | **keine Verschlüsselung** |

### 6.4 Migration

| Property | Pflicht | Standardwert | Bedeutung | Sicherheitshinweis |
|---|---|---|---|---|
| `MIGRATE_ITEMTYPES` | ja im unterstützten Betrieb | leer im Config-Modell | Mapping `SourceType:DestinationType`; mehrere Einträge komma­getrennt | explizit setzen; leer migriert praktisch nicht „alle“ |
| `OPERATION_MODE` | nein | `MIGRATE` | `MIGRATE`, `DELETE` oder Snapshot-Tracewert `VERIFY` | `DELETE` löscht Source-Objekte |
| `FILTER_PREDICATE` | nein | leer | XQPE-Prädikat, an `/<ItemType>` angehängt | kein SQL; nur geprüfte Prädikate verwenden |
| `DRY_RUN` | nein | `false` | verhindert reale Source-Löschung im `DELETE`-Pfad | wirkt nicht als Migrations-Dry-Run |
| `STREAM_UPLOAD` | nein | `false` | setzt bei `true` eine JVM-Property | der aktuelle Uploader prüft das Enable-Flag nicht; nicht als verlässlicher Schalter behandeln |

### 6.5 Parallelität

| Property | Pflicht | Standardwert | Bedeutung | Sicherheitshinweis |
|---|---|---|---|---|
| `PROFILE` | nein | kein verifizierter Standardwert | Profilwerte für Threads/Batch/Queue | explizite Einzelwerte gewinnen |
| `THREAD_COUNT` | nein | 5, Bereich 1–200 | Consumer-Anzahl | nach IBM-/DB-/Heap-Test dimensionieren |
| `SOURCE_POOL_SIZE` | nein | `THREAD_COUNT + 1`, Bereich 1–500 | Source-Pool | im Delete-Pfad besonders relevant |
| `DEST_POOL_SIZE` | nein | `THREAD_COUNT`, Bereich 1–500 | Destination-Pool | im Delete-Modus effektiv 0 |
| `PRODUCER_COUNT_STRATEGY` | nein | `HYBRID` | wird gelesen und geloggt | Producer führt aktuell unabhängig davon SDK-Pass-1 aus |

### 6.6 Batch- und Queue-Verhalten

| Property | Pflicht | Standardwert | Bedeutung | Sicherheitshinweis |
|---|---|---|---|---|
| `BATCH_SIZE` | nein | 100, Bereich 1–10.000 | Consumer-Batchgröße | größere Batches erhöhen Transaktions-/Rollback-Auswirkung |
| `QUEUE_SIZE` | nein | 10.000, Bereich 100–1.000.000 | Producer-/Consumer-Queue | Heap und Backpressure beobachten |
| `CONSUMER_DOUBLECHECK` | nein | `false` | erneute Journalprüfung vor Verarbeitung | bei Mehrfach-/Resume-Risiko erwägen; kein Ersatz für Exklusivität |
| `LOG_ITEMS_BATCHED` | nein | `false` | reduziert Item-Progresslogs | Fehlerlogging bleibt separat |
| `LOG_BATCH_INTERVAL` | nein | 10.000, Bereich 1–1.000.000 | Intervall für Batch-Progress | nur bei `LOG_ITEMS_BATCHED=true` |
| `LOG_ERRORS_IMMEDIATE` | nein | `true` | vorbereitetes Config-Flag | kein aktueller Verbraucher nachgewiesen |

### 6.7 Retry und Timeout

| Property / Schalter | Pflicht | Standardwert | Bedeutung | Sicherheitshinweis |
|---|---|---|---|---|
| `POOL_BORROW_TIMEOUT` | nein | 5.000 ms, Bereich 100–60.000 | erster Pool-Borrow-Wartewert | Inline-Kommentar im Wert vermeiden |
| `POOL_MAX_WAIT_TIME` | nein | 10.000 ms, Bereich 100–300.000 | maximale Poolwartezeit | Fehler sind nicht „Source fehlt“ |
| `CM_H2_LOCK_TIMEOUT_MS` | nein | 5.000 ms | nur H2-Abfragen des Wrappers | wirkt nicht auf Java-Journalverbindungen |
| `-Dcm.migrator.shutdown.graceSeconds` | nein | 60 s | Shutdown-Hook-Wartezeit | danach werden aktive SDK-Calls nicht hart getrennt |
| `-Dcm.migrator.slowItemWarnMs` | nein | 60.000 ms | Slow-Item-Warnung | nur Diagnose |
| `-Dcm.migrator.slowPhaseWarnMs` | nein | 30.000 ms | Slow-Phase-Warnung | nur Diagnose |
| `-Dcm.migrator.slowPartWarnMs` | nein | 30.000 ms | Slow-Part-Warnung | nur Diagnose |

Consumer-Retries sind nicht als Properties konfiguriert: Batch und Single-Item-Verarbeitung verwenden fest verdrahtete Versuchs-/Backoff-Logik. Abweichende Retry-/Connection-Timeout-Properties aus historischen Texten werden vom aktuellen Produktionscode nicht nachweislich konsumiert und sind hier deshalb nicht als Optionen aufgeführt.

Der 24-Stunden-Wert in `Main.awaitTermination(...)` ist kein harter Laufzeit-Timeout. Nach Ablauf beziehungsweise Interrupt wird kooperativer Shutdown angefordert und weiter gewartet, bis laufende SDK-Aufrufe tatsächlich enden.

### 6.8 Journal und Resume

| Property | Pflicht | Standardwert | Bedeutung | Sicherheitshinweis |
|---|---|---|---|---|
| `DB_PATH` | nein | `./data/migration_journal` | Basis für H2-Journale je ItemType | exklusiver, beschreibbarer, gesicherter Pfad |
| `DB_URL_APPEND` | nein | leer | wird unverändert an H2-JDBC-URLs angehängt | nur mit getesteter H2-Version und exklusivem Zugriff einsetzen |
| `DATA_DIR` | nein | `./data` | vorbereiteter Datenpfad | kein aktueller Verbraucher nachgewiesen |

`JOURNAL_DIR` ist eine Wrapper-Ableitung/Fallback-Bezeichnung, keine von `MigrationConfig` gelesene Anwendungsproperty. Maßgeblich ist `DB_PATH`.

### 6.9 Verify

| Property / Schalter | Pflicht | Standardwert | Bedeutung | Sicherheitshinweis |
|---|---|---|---|---|
| `AUTO_MARK_FOR_REMIGRATION` | nein | `true` | setzt bei Mismatch `AUDIT_LOG` auf `FAILED` | `cm-run verification*` überschreibt sicher auf `false` |
| `CM_VERIFY_AUTO_MARK` | nein | nur gesetzt wirksam | JVM-Override für Auto-Mark | direkte `verify.sh`-Läufe können Config-Default nutzen |
| `CM_VERIFY_SORT_MODE` | nein | `migrator` | Sortierung mehrteiliger Hashes | Wrapper setzt `migrator` |
| `CM_VERIFY_WORKLIST_MODE` | nein | `default` | `default` oder `nonOk` | `nonOk` braucht vorhandenes `VERIFICATION_LOG` |
| `-Dcm.migrator.verify.bufferSize` | nein | 1 MiB, mindestens 64 KiB | Hash-Puffer | Heap/I/O testen |
| `-Dcm.migrator.verify.slowHashWarnMs` | nein | 10.000 ms | Warnschwelle; `0` deaktiviert | Diagnose, kein Abbruch |

### 6.10 Cascade Delete

| Property | Pflicht | Standardwert | Bedeutung | Sicherheitshinweis |
|---|---|---|---|---|
| `CASCADE_DELETE_ON_MISSING` | nein | `false` | erlaubt Destination Delete nur bei `NOT_FOUND` | derzeit durch `verify.sh` bei `true` blockiert; ausgeschaltet lassen |

### 6.11 Reports

| Property | Pflicht | Standardwert | Bedeutung | Sicherheitshinweis |
|---|---|---|---|---|
| `GENERATE_AUDIT_PROTOCOL` | nein | `true` | aktiviert Protokollberichte | Reports können sensible IDs enthalten |
| `PROTOCOL_COMPANY_NAME` | nein | `Unbekannt` | Überschrift im Protokoll | keine internen Daten in öffentliche Artefakte übernehmen |
| `PROTOCOL_COMPANY_LOGO` | nein | leer | lokales Bild, als Base64 in HTML | Herkunft und Dateirechte prüfen |
| `AUDIT_PROTOCOL_OUTPUT_DIR` | nein | `./reports` | vorbereiteter Output-Pfad | aktueller Generator konsumiert ihn nicht nachweislich |
| `PROTOCOL_OUTPUT_DIR` | nein | `./reports` | vorbereiteter Output-Pfad | aktueller Generator schreibt fest nach `reports/` |

### 6.12 E-Mail

| Property | Pflicht | Standardwert | Bedeutung | Sicherheitshinweis |
|---|---|---|---|---|
| `EMAIL_TO` | nein | leer | Empfänger; leer deaktiviert Versand | Zieladresse und Reportinhalt schützen |

Der Versand verwendet lokal `mutt`, sonst `mailx`. Fehlen beide oder scheitert das Kommando, wird geloggt; der Hauptlauf wird dadurch nicht automatisch fehlerhaft. Vor Versand entsteht ein HTML-Body unter `debug_mail/`.

### 6.13 WebGUI

| Property / Env | Pflicht | Standardwert | Bedeutung | Sicherheitshinweis |
|---|---|---|---|---|
| `webgui.auth.enabled` | empfohlen ja | `true` | Basic-Auth-Schalter | ohne Admin-User ist Auth faktisch deaktiviert |
| `webgui.admin.user` | ja für Auth | Env-Fallback `WEBGUI_ADMIN_USER`, sonst kein verifizierter Wert | Admin-User | Propertywert gewinnt vor Env-Fallback |
| `webgui.admin.password.hash` | empfohlen | kein verifizierter Standardwert | SHA-256-Hash | ungesalzen; Datei schützen |
| `webgui.admin.password` | alternativ | Env-Fallback `WEBGUI_ADMIN_PASSWORD` | Klartextpasswort | nicht committen; Hash bevorzugen |
| `WEBGUI_ALLOW_PASSWORDLESS_CM_LOGIN` | nein | `false` | erlaubt WebGUI-Run ohne CM-Passwortproperty | ausgeschaltet lassen |
| `-Dcm.migrator.webgui.bindAll` | nein | `false` | Bindung an alle Interfaces | nur mit TLS/Proxy/Netzschutz |
| `-Dcm.migrator.webgui.bindAddress` | nein | kein verifizierter Standardwert | konkrete Bind-Adresse | localhost bevorzugen |
| `-Dcm.migrator.monitor.port` | nein | 8000 | nur Linkziel im WebGUI-Zustand | startet keinen Monitor |
| `WEBGUI_RUN_ID` | nein; intern erzeugt | kein verifizierter Standardwert | Run-ID im WebGUI-Snapshot | nicht manuell als Freigabemerkmal setzen |
| `WEBGUI_SOURCE_CONFIG` | nein; intern erzeugt | kein verifizierter Standardwert | Herkunftsdatei im WebGUI-Snapshot | kann interne Pfade enthalten |

Fehlt bei aktivierter Auth ein Passwort, erzeugt und loggt `AuthHandler` ein temporäres Passwort. Das Log ist dann ein Secret-Artefakt. Fehlversuche werden pro ermittelter Client-IP gezählt; nach fünf Fehlern gilt eine fünfminütige Sperre. Ein ungeprüftes `X-Forwarded-For` kann die IP-Ermittlung beeinflussen.

### 6.14 Monitoring und Logging

| Schalter | Pflicht | Standardwert | Bedeutung | Sicherheitshinweis |
|---|---|---|---|---|
| `CM_CONSOLE_MODE` | nein | Java `auto`; Wrapper Migration `pretty`, Verify `plain` | Konsolendarstellung | keine fachliche Wirkung |
| `NO_COLOR`, `TERM` | nein | umgebungsabhängig | ANSI-Farben | keine fachliche Wirkung |
| `CM_JAVA_OPTS` | nein | leer | zusätzliche JVM-Argumente | nur freigegebene Werte; keine Secrets in Prozessliste |

`conf/log4j2.xml` setzt `INFO` für Konsole und `migration.log`; `verification_errors.log` erhält `ERROR`. Beide Rolling Files rotieren bei 10 MB mit bis zu fünf Archiven. `JVM_OPTS` als Eintrag in einer Properties-Datei wird von den Launchern nicht als JVM-Option gelesen.

`src/main/resources/log4j2.xml` gehört nur zum Legacy-Maven-Tree und schreibt separat nach `logs/migrator.log`; es konfiguriert den aktuellen `bin/compile.sh`-Betrieb nicht.

Die Launcher setzen außerdem interne JVM-Properties wie `cm.migrator.run.config`, `cm.migrator.cmHome`, `cm.home`, `log4j.configurationFile` und `java.library.path`. Die Verify-Umgebungsvariablen werden auf `cm.migrator.verify.sortMode`, `cm.migrator.verify.worklistMode` und `cm.migrator.verify.autoMarkForRemigration` abgebildet; `CM_CONSOLE_MODE` wird als `cm.migrator.console.mode` weitergegeben. Diese internen Übergabewerte nicht dauerhaft in `migration.properties` duplizieren.

### 6.15 Content- und Large-File-JVM-Schalter

| Schalter | Standardwert | Bedeutung / Grenze |
|---|---|---|
| `-Dcm.migrator.tmpdir.largeThresholdBytes` | 100 MiB | Grenze zwischen kleinem und großem Temp-Pfad |
| `-Dcm.migrator.tmpdir.large` | `/var/tmp/cm-migrator` | großer Temp-Pfad |
| `-Dcm.migrator.tmpdir.small` | `java.io.tmpdir` | kleiner Temp-Pfad |
| `-Dcm.migrator.directAdd.enable` | `false` | opt-in; nur bei bestätigter Resource-Manager-Kompatibilität |
| `-Dcm.migrator.directAdd.disable` | `false` | Hard-Disable gewinnt vor Enable |
| `-Dcm.migrator.streamUpload.disable` | `false` | deaktiviert Stream-Upload |
| `-Dcm.migrator.largeFile.failFastAtStartup` | `false` | bricht bei fehlendem sicheren >2-GB-Pfad optional bereits beim Start ab |

`cm.migrator.largeFile.failFast` wird nur in einem Kommentar erwähnt; eine Code-Lesestelle wurde nicht gefunden. Es ist daher kein dokumentierter wirksamer Schalter.

### 6.16 Properties des separaten Legacy-Prototyps

Der nicht von `bin/compile.sh` gebaute `com.example.migrator`-Tree liest eigene Keys: `source.cm.database`, `source.cm.user`, `source.cm.password`, `dest.cm.database`, `dest.cm.user`, `dest.cm.password`, `process.writer.threads`, `process.mode`, `process.delete.dryrun` und `target.itemType`. `process.mode` hat dort den Code-Default `COPY`, `process.delete.dryrun` den Default `true`; für die Verbindungswerte existiert keine neutrale vollständige Vorlage. Diese Keys nicht mit `MigrationConfig` mischen und den Prototyp nicht als freigegebenen Betriebsweg verwenden.

## 7. Credential- und Secret-Handling

Secrets können enthalten sein in:

- `conf/migration.properties`, `conf/webgui.properties` und IBM-Konfigurationsdateien;
- WebGUI-Run-Snapshots unter `data/webgui-runs/`;
- Logs, Reports, `debug_mail/`, Journale und Backup-Dateien;
- Keystores und Release-Umgebungsvariablen.

Mindestregeln:

```bash
chmod 600 conf/migration.properties conf/webgui.properties
chmod 700 data logs reports debug_mail 2>/dev/null || true
```

- keine produktiven Configs, Journale, Logs, Reports oder Run-Snapshots committen;
- `_PASSWORD_CRYPT` als reversible Legacy-Kodierung behandeln, nicht als Verschlüsselung;
- Credentials regelmäßig rotieren und nach Incident/Repo-Leak sofort ersetzen;
- Backups verschlüsseln, Zugriff protokollieren und Restore-Rechte prüfen;
- WebGUI-Admin-Passwort nicht als Kommandozeilenargument verwenden;
- Plaintext-Env nur pro Prozess bereitstellen und danach entfernen;
- `KEYSTORE_STOREPASS` und `KEYSTORE_KEYPASS` nur für den Release-Signing-Prozess setzen und danach entfernen; keine Werte in Shell-History oder Dokumentation übernehmen;
- IBM-CM-Credentials nach Least Privilege trennen; Verifier-Cascade-Delete erfordert besonders restriktive Destination-Rechte;
- Keystores extern verwalten; den historisch getrackten Keystore nicht als vertrauenswürdiges Produktionssecret übernehmen.

## 8. Standardbetriebsablauf

1. Freigegebenen Branch/Commit und sauberen Arbeitsbaum prüfen.
2. Config, Mapping, Source/Destination, Dateirechte und Secret-Herkunft prüfen.
3. Sicherstellen, dass `CASCADE_DELETE_ON_MISSING=false` ist.
4. Java-/IBM-Library-Stand und Build-Artefakt prüfen beziehungsweise neu bauen.
5. Prozess-, Port-, Speicherplatz- und Journal-Exklusivität prüfen.
6. Journal, Config und erforderliche Keystores sichern.
7. `cm-run.sh status` ausführen; jede H2-Warnung klären.
8. In freigegebener Umgebung `cm-run.sh safe` starten.
9. Konsole, `migration.log`, `verification_errors.log`, `status.html` und gegebenenfalls JMX beobachten.
10. Exitcode **und** Journal-/Verify-/Reportzustände prüfen.
11. Reports und Journal konsistent archivieren.
12. Branch/Commit, Config-Hash außerhalb des Repositorys, Start/Ende, Exit, Ergebnis und Abweichungen im Betriebsprotokoll ergänzen.

## 9. Start, Status, Monitoring und Stop

### Kommandooberfläche

```bash
./bin/cm-run.sh <mode> <config> [--webgui] [--monitor] \
  [--webgui-port PORT] [--monitor-port PORT]
```

| Modus | Aliase | Wirkung |
|---|---|---|
| `migration` | `migrate` | Migration über `start.sh` |
| `delete` | keine | expliziter Source-Delete-Betrieb |
| `verification` | `verify` | Standardverifikation |
| `verification-nonok` | `verify-nonok`, `nonok` | vorhandene Non-OK-Worklist erneut prüfen |
| `safe` | keine | fünfstufiger kontrollierter CLI-Workflow |
| `status` | `check` | read-only H2-Statusausgabe |

`--webgui` beziehungsweise `--monitor` starten die jeweiligen Hintergrundservices; `--webgui-port PORT` und `--monitor-port PORT` akzeptieren Werte 1–65535.

Direkt kopierbare Beispiele:

```bash
./bin/start.sh conf/migration.properties
./bin/verify.sh conf/migration.properties
./bin/webgui.sh --port 8080
./bin/monitor.sh --port 8000
./bin/cascade-delete-guard.sh conf/migration.properties
```

`start.sh` und `verify.sh` akzeptieren je eine optionale Configdatei. `webgui.sh` unterstützt `--port PORT`, `--bind-all`, `--bind-address IP` und `--help`/`-h`. `monitor.sh` unterstützt `--port PORT`, `--help`/`-h` und den Port als einzelnes Positionsargument. Unbekannte Argumente behandelt nicht jeder Legacy-Launcher gleich streng, deshalb nur die dokumentierte Syntax verwenden.

### 9.1 CLI-Betrieb

```bash
./bin/cm-run.sh safe conf/migration.properties
```

Der Wrapper startet `bin/start.sh`, danach `bin/verify.sh` und H2-Prüfungen. Logs liegen primär in `migration.log` und `verification_errors.log`; Console/`status.html` zeigen Fortschritt.

Stop: `Ctrl+C` beziehungsweise `SIGTERM` fordert kooperativen Shutdown an. Laufende IBM-SDK-Aufrufe werden nicht hart unterbrochen. Der Shutdown-Hook wartet standardmäßig 60 Sekunden; bei weiter aktiven Workern lässt er Pool/Journal bewusst offen, damit In-Flight-SDK-Aufrufe nicht durch Disconnect beschädigt werden. Der endgültige Signal-Exitcode ist nicht als stabile Anwendungsschnittstelle implementiert.

Wiederanlauf: erst nach Prozessende, Log-/Journalbackup und Ursachenanalyse erneut mit demselben geprüften `DB_PATH` starten.

### 9.2 Direkte Migration

```bash
./bin/cm-run.sh migration conf/migration.properties
```

Dieser Modus prüft vor Start auf riskante `FAILED`-Zeilen mit vorhandener `DEST_ITEM_ID` und verweigert sie mit Exit `2`. Er führt keine anschließende Verifikation durch.

`bin/start.sh conf/migration.properties` ist ein niedrigerer Launcher ohne Wrapper-H2-Prüfung und ohne eigenen Cascade-Delete-Guard. Nur für gezielte Diagnose verwenden.

### 9.3 Verify-Betrieb

```bash
./bin/cm-run.sh verification conf/migration.properties
./bin/cm-run.sh verification-nonok conf/migration.properties
```

Der Wrapper setzt `sort=migrator`, `autoMark=false` und den passenden Worklist-Modus. `verify.sh` blockiert `CASCADE_DELETE_ON_MISSING=true` mit Exit `2`.

Exit `0` bedeutet nur, dass der Java-Prozess aus Sicht des Launchers nicht abgestürzt ist. Mismatches, `ERROR`, `ORPHANED` und andere Non-OK-Ergebnisse stehen im Journal, Verifikationslog, CSV und HTML-Report.

### 9.4 Status

```bash
./bin/cm-run.sh status conf/migration.properties
```

Der Status liest H2-Journale pro konfiguriertem Source-ItemType. Er testet keine IBM-Verbindung. H2-Fehler können als Warnung mit Nullwerten erscheinen; solche Ausgaben sind kein sauberer Status.

### 9.5 Monitor

```bash
./bin/monitor.sh --port 8000
```

Der Prozess stellt das **gesamte Projektverzeichnis** per Python-HTTP-Server bereit, ohne Auth oder TLS und ohne explizite Loopback-Bindung. Nur in isolierter Umgebung einsetzen. Stop über `Ctrl+C`. `cm-run.sh --monitor` verwaltet den Prozess im Hintergrund und schreibt `logs/monitor.log`; bei Portkonflikten kann der Wrapper über `fuser` einen vorhandenen Prozess auf diesem Port beenden.

### 9.6 WebGUI

```bash
./bin/webgui.sh --port 8080
```

Standardbindung ist `127.0.0.1`. Stop über `Ctrl+C`; ein Run-Stop in der GUI setzt Shutdown und interruptet den Run-Thread kooperativ. Laufzustände existieren nur im Prozessspeicher, Snapshots verbleiben jedoch unter `data/webgui-runs/`.

### 9.7 Source Delete

```bash
./bin/cm-run.sh delete conf/migration.properties
```

Nur nach separater schriftlicher Freigabe. Der Wrapper verlangt `OPERATION_MODE=DELETE`. `ItemMigrator` löscht Source-Objekte; `DRY_RUN=true` unterdrückt reale Deletes. Vor echtem Lauf Journal-/Reportprüfung, Backup und isolierten Test durchführen. Dieser Modus ist nicht Cascade Delete: Cascade Delete betrifft Destination-Objekte im Verifier.

## 10. Sicherer Startmodus

```bash
./bin/cm-run.sh safe conf/migration.properties
```

Realer Ablauf:

1. Migration;
2. Verifikation über den Guard-geschützten `verify.sh`-Launcher;
3. Journal-/Verifikationsstatus;
4. falls Non-OK vorhanden: zeitgestempelter CSV-Export;
5. Non-OK-Reverifikation und erneute Statusprüfung.

Bleiben Non-OK-Zeilen oder riskante Duplikat-/Journalzustände, liefert der Wrapper Exit `2`. Eine automatische Remigration wird nicht gestartet. Bei erfolgreicher Non-OK-Reverifikation können zuvor `FAILED` markierte Auditzeilen wieder auf `SUCCESS` gesetzt werden.

Vor beziehungsweise während des Flusses geprüft werden unter anderem Config-/JAR-/Java-Verfügbarkeit, explizites ItemType-Mapping, Portwerte, H2-Schema und riskante Journalzustände. Blockiert werden insbesondere:

- fehlende/ungültige Grundargumente;
- `delete` ohne `OPERATION_MODE=DELETE`;
- Migration mit `FAILED` plus vorhandener `DEST_ITEM_ID`;
- Verify-Phase mit aktiviertem Cascade Delete;
- Safe-Abschluss mit verbleibendem Non-OK.

Nicht zuverlässig geprüft werden:

- IBM-Live-Verbindung, Berechtigung, fachliche Mappingkorrektheit oder Content;
- freier Speicher und Native-Library-Kompatibilität;
- jeder spätere WebGUI-Profilwert;
- Cascade Delete **vor** der Migrationsphase von `safe`;
- H2-Inhalt, wenn Wrapper-Abfragen selbst warnen/fehlschlagen;
- Consumer-Einzelfehler als stabiler Nonzero-Exit;
- harter Abbruch blockierter IBM-SDK-Calls.

## 11. Migration

Eingangsdaten sind `MIGRATE_ITEMTYPES` und ein optionales XQPE-`FILTER_PREDICATE`. `Producer` validiert ItemType-Namen und durchläuft je Source-ItemType zwei SDK-Cursor:

1. Pass 1 zählt Treffer;
2. Pass 2 überspringt terminale Journalzustände und legt übrige Items in die begrenzte Queue.

`Producer` und Consumer teilen den `CMConnectionPool`. Consumer nehmen Batches, führen Migration oder Source Delete aus und schreiben `SUCCESS`, `FAILED`, `SKIPPED` beziehungsweise `DELETED` asynchron ins Journal. Erfolgreiche Migration speichert SHA-256 und Destination-ID.

Bei normalem Producer-Ende wird je Consumer eine Poison Pill gesendet. Bei Shutdown oder Producer-/Discovery-Fehler werden keine normalen Poison Pills gesendet; Consumer verlassen den Loop über das globale Shutdown-Signal.

Per-Item-Consumerfehler werden geloggt/journaled und können Retries auslösen. Sie führen nicht zwingend zu einem Prozessfehler. Ein Producer-/Discovery-Fehler wird dagegen zentral gespeichert, fordert Shutdown an und wird nach Cleanup weitergereicht.

Parallelität wird über Threads, Queue, Batch und Pools begrenzt. `PRODUCER_COUNT_STRATEGY` ändert im aktuellen Producer den tatsächlich ausgeführten SDK-Pass-1 nicht nachweislich.

Der separate `com.example.migrator`-Prototyp besitzt mit `JdbcItemReader` eine andere, direkte DB2-Discovery. Sie ist nicht Teil von `cm-run.sh`, `bin/compile.sh`, der Safety-Härtungen oder der dokumentierten Tests und wird daher nicht als Alternative empfohlen.

## 12. Verifikation

Der Verifier verarbeitet standardmäßig erfolgreich migrierte Auditzeilen, die noch nicht `OK` verifiziert sind.

- Mit gespeichertem Journal-Checksum: Destination-only-Hash und Vergleich gegen den gespeicherten Hash; kein Source-Lookup und kein Größenvergleich.
- Ohne gespeicherten Hash: Source und Destination werden gehasht; Größenabweichungen werden zunächst nur gewarnt.
- Mehrteilige Inhalte werden in konfigurierbarer Reihenfolge per SHA-256 gestreamt.

Damit ist Cascade Delete bei vorhandenem Journal-Checksum nicht erreichbar. Es ist kein allgemeiner Abgleich aller inzwischen in der Source fehlenden Objekte.

### Source-Lookup-Entscheidung

| Status | Bedeutung | Aktion | Destination löschen |
|---|---|---|---|
| `EXISTS` | Source für die exakte PID eindeutig gefunden | normal verifizieren | Nein |
| `NOT_FOUND` | Exception konservativ als fehlendes Source-Objekt für die exakte PID klassifiziert | `ORPHANED` oder Cascade-Verarbeitung | nur zusätzlich bei erlaubt aktivierter Option |
| `ERROR` | technischer, unbekannter oder unsicherer Fehler | `ERROR` protokollieren, Fehlerzähler erhöhen, Item fehlschlagen lassen | **Nie** |

`ERROR` ist nicht „gelöscht“. Timeout, Authentifizierung, Berechtigung, Netzwerk/DNS und unbekannte Meldungen werden fail-closed als `ERROR` behandelt. Ein generisches „not found“ reicht nicht; der Classifier verlangt eines seiner engen Muster und exakte PID-Zuordnung entlang einer begrenzten Cause-Kette.

Die Meldungsmuster sind keine verbindliche IBM-Spezifikation. Ihre Eignung wurde nicht gegen ein echtes IBM-CM-System bestätigt.

Verifikationsstatus sind `OK`, `MISMATCH`, `ORPHANED`, `CASCADE_DELETED`, `CASCADE_DELETE_FAILED` und `ERROR`. Ein erfolgreiches Cascade Delete zählt historisch nicht als Verifikations-`OK` und erhöht den allgemeinen Fehlerzähler nicht. Die statischen Verifier-Zähler werden bei mehreren Läufen in derselben JVM nicht vollständig pro Run zurückgesetzt; insbesondere WebGUI-Folgeläufe daher anhand Journal/Report, nicht nur Summenzähler bewerten.

## 13. Cascade Delete

### Zweck

Cascade Delete entfernt ein Destination-Objekt, wenn das zugehörige Source-Objekt eindeutig nicht mehr existiert. Es ist destruktiv und von Source Delete (`OPERATION_MODE=DELETE`) getrennt.

### Voraussetzungen

- explizite, schriftliche Betriebsfreigabe;
- kontrollierter IBM-Live-E2E-Test für `EXISTS`, `NOT_FOUND`, technische Fehler und Delete-Berechtigung;
- gesicherte Journale/Reports und isolierte Testobjekte;
- nachweislich exakte Source-/Destination-PID-Zuordnung;
- Vier-Augen-Review der Config und des erwarteten Delete-Sets;
- Rollback-/Restore-Plan, soweit IBM CM dies ermöglicht.

### Launcher-Containment

`bin/cascade-delete-guard.sh` normalisiert den Key und wertet den letzten gesetzten Wert. Fehlender oder explizit falscher Wert ist erlaubt; wahrheitsähnliche beziehungsweise unsichere Aktivierung liefert `2`.

- `verify.sh` blockiert jede aktivierte Option.
- `cm-run verification*` erbt diese Blockade.
- `cm-run safe` erreicht die Blockade erst nach der Migration.
- `webgui.sh` prüft nur `conf/migration.properties` beim Serverstart; später ausgewählte Profile sind damit nicht vollständig abgedeckt.
- `start.sh`, direkte Java-Aufrufe und der In-Prozess-WebGUI-Pfad besitzen keine allgemeine Launcher-Garantie.

Die Guard-Kommentare sprechen noch von einer unsicheren alten Boolean-Implementierung; die aktuelle Java-Logik ist bereits Tri-State. Der Guard bleibt dennoch als konservative Containment-Policy aktiv.

### Java-Level-Entscheidung

`Verifier.shouldCascadeDelete(sourceLookupStatus, cascadeEnabled)` liefert nur bei `NOT_FOUND` und `true` wahr.

Die Entscheidung wird nur in dem Verifikationspfad erreicht, der tatsächlich einen Source-Lookup ausführt. Bei vorhandenem Journal-Checksum arbeitet der Verifier Destination-only und kann keinen `NOT_FOUND`-Status erzeugen.

| Konfiguration/Status | Verhalten |
|---|---|
| Option `false` | nie löschen |
| `true` + `EXISTS` | normal verifizieren, nicht löschen |
| `true` + `NOT_FOUND` | Destination Delete grundsätzlich möglich |
| `true` + `ERROR` | Fehler protokollieren, nie löschen |

Tests des Classifiers und der finalen Entscheidung führen keinen echten IBM-Delete aus. Sie beweisen weder Servermeldungen noch Transaktionen, Rechte, Netzwerkfehler oder Restore.

**Empfohlene Regel:**

```properties
CASCADE_DELETE_ON_MISSING=false
```

Diese Regel gilt, solange kein kontrollierter IBM-Live-E2E-Test und keine explizite Betriebsfreigabe vorliegen. Weil der unterstützte Verify-Launcher `true` derzeit blockiert, darf produktive Aktivierung nicht durch Umgehung des Launchers improvisiert werden; sie braucht eine separat reviewte Code-/Policy-Änderung.

## 14. Worker- und Producer-Fehler

`WorkerFailureState` speichert atomar den ersten `Throwable`. Spätere Producerfehler können geloggt werden, ersetzen den Cause aber nicht.

Ablauf bei Producer-/Discovery-Fehler:

1. erster Fehler wird gespeichert;
2. `ShutdownCoordinator.requestShutdown()` wird gesetzt;
3. Producer sendet keine normalen Poison Pills;
4. `Main` erkennt Shutdown/Failure und markiert den Lauf als abgebrochen;
5. normale Migrations-Abschlussreports und E-Mail werden nicht erzeugt;
6. Monitor wird beendet, Workerterminierung abgewartet, Pool und Journal werden geschlossen;
7. `WorkerFailureState.throwIfPresent()` wirft `IllegalStateException` mit ursprünglichem Cause.

Ein externer Interrupt wird nach Cleanup wiederhergestellt. Ein direkt im Producer eintreffender Interrupt darf weiterhin `shutdownNow()` auf den Discovery-Executor anwenden; das ist vom koordinierten `Main`-Shutdown zu unterscheiden.

Restrisiko: Der koordinierte Hauptpfad erzwingt keine harte Unterbrechung blockierter IBM-SDK-Aufrufe. Nach dem nominellen 24-h-Wait kann der Prozess unbegrenzt weiter warten.

## 15. Journal und Wiederaufnahme

Pro Source-ItemType entsteht eine H2-Datenbank `${DB_PATH}/journal_<ItemType>.mv.db`. `AUDIT_LOG` enthält mindestens Item-ID, ItemType, Status, Checksum, Destination-ID, Nachricht und Zeitstempel. `VERIFICATION_LOG` wird in derselben Datenbank verwaltet.

Resume ist kein eigener CLI-Modus. Ein erneuter Migrations-/Safe-Lauf mit demselben Journal lädt terminale Zustände in einen Cache:

- Migration überspringt `SUCCESS`;
- Delete überspringt `DELETED`;
- `FAILED` und `SKIPPED` werden erneut versucht.

Der Wrapper blockiert Migration, wenn eine `FAILED`-Zeile bereits eine Destination-ID trägt. Das reduziert Duplikatrisiko, ersetzt aber keine fachliche Prüfung. `CONSUMER_DOUBLECHECK=true` kann vor Verarbeitung erneut prüfen; parallele Prozesse mit demselben Journal sind trotzdem nicht als sicher belegt.

Journalgrenzen:

- asynchrone Queue mit Kapazität 100.000;
- bei voller Queue wird ein Statusupdate verworfen;
- DB-Batches verwenden Transaktion/Rollback;
- `close()` wartet maximal 30 Sekunden auf den Writer;
- ein abruptes Prozessende kann In-Memory-Status und H2-Stand auseinanderlaufen lassen;
- `DB_URL_APPEND` kann Locking/Konsistenz beeinflussen.

Sichere Wiederaufnahme:

1. sicherstellen, dass kein CM-Migrator-/H2-Prozess mehr zugreift;
2. Logs und kompletten `DB_PATH` sichern;
3. H2-/Wrapperwarnungen klären;
4. `AUDIT_LOG` und `VERIFICATION_LOG` read-only auswerten;
5. riskante `FAILED`+Destination-ID nicht löschen oder blind ummarkieren;
6. Root Cause beheben;
7. `cm-run.sh status` erneut ausführen;
8. mit gleicher, geprüfter Mapping-/Journalzuordnung neu starten;
9. Ergebnis verifizieren.

## 16. Reports und Ergebnisse

Erzeugte Artefakte:

- `migration_report.html` oder `deletion_report.html`;
- `verification_report.html`;
- Protokollberichte pro ItemType unter `reports/`;
- `reports/verification_non_ok_<ItemType>.csv`;
- im Safe-Workflow `reports/non_ok_ids_<ItemType>_<timestamp>.csv`;
- laufendes `status.html`;
- optionale Mail-Bodies unter `debug_mail/`.

Normale Migrationsreports/E-Mail entstehen nur, wenn `Main` den Lauf nicht als Shutdown/WorkerFailure-Abbruch markiert. Ein gespeicherter Producer-/Discovery-Fehler darf daher nicht als Erfolg erscheinen.

Consumer-Einzelfehler können dagegen in einem normal abschließenden Report stehen. „Report erzeugt“ und Exit `0` bedeuten nicht „alle Items erfolgreich“.

Der Verifier erzeugt nach seinem Waitpfad Reports/CSV/E-Mail auch dann, wenn Mismatches oder Verifikationsfehler vorliegen; nach Timeout/Interrupt ist seine Suppression nicht mit `Main` identisch. `verify.sh`-Exit und Reports zusammen auswerten.

HTML/CSV können PIDs, Mappings, Fehlertexte und Metadaten enthalten. Zugriff, Versand, Retention und Löschung nach Datenschutz-/Betriebsvorgaben steuern.

## 17. WebGUI

Start:

```bash
./bin/webgui.sh --port 8080
```

Standardbindung: `127.0.0.1`. Empfohlener Remotezugriff:

```bash
ssh -L 8080:127.0.0.1:8080 <operator>@<server>
```

Danach lokal `http://127.0.0.1:8080/` öffnen. Nur `/api/health` ist absichtlich unauthentifiziert; UI, `/api/status`, Config-, Prozess-, Benchmark- und Operations-Endpunkte sind vom Auth-Wrapper umfasst.

Sicherheitsregeln:

- `webgui.auth.enabled=true`, Admin-User und starkes Passwort/Hash setzen;
- niemals `--bind-all` oder öffentliche Bind-Adresse ohne TLS, Reverse Proxy, 2FA/Netzschutz und Freigabe;
- Basic Auth selbst bietet kein TLS;
- fehlender Admin-User deaktiviert Auth faktisch;
- temporär geloggtes Passwort sofort als Secret behandeln;
- Config-API kann Properties unter `conf/` verändern;
- Run-Snapshots unter `data/webgui-runs/` enthalten potenziell vollständige Credentials und werden nicht automatisch entfernt;
- WebGUI-`safe` ist nur Migration plus Verifikation, nicht der fünfstufige CLI-Safe-Workflow;
- `Verifier.main` kann äußere Exceptions nur loggen und zurückkehren; ein WebGUI-Verify/`safe` kann deshalb formal `COMPLETED` sein, obwohl Logs oder Journal einen technischen Fehler zeigen;
- beim Run-Stop wird kooperativ Shutdown+Interrupt angefordert; blockierte SDK-Aufrufe können fortbestehen;
- Prozess-/Run-Zustände sind nicht dauerhaft außerhalb des laufenden WebServer-Prozesses gespeichert.

## 18. Logging und Monitoring

### Logs

- `migration.log`: allgemeine Migration, Connection Pool, Worker, Reports und WebGUI-Backendlogs;
- `verification_errors.log`: `ERROR`-Verifikationsmeldungen;
- `compile.log`: Buildausgabe;
- `logs/webgui.log`, `logs/monitor.log`: nur bei Hintergrundstart über `cm-run.sh`;
- WebGUI-In-Memory-Runlog: über geschützte API/UI, nicht dauerhaft garantiert.

`conf/log4j2.xml` verwendet `INFO` und 10-MB-Rotation mit fünf Archiven. Debug kann zusätzliche SSID/User-/Pfaddiagnose schreiben. Passwörter werden nicht absichtlich ausgegeben, aber Fehlerketten und externe Tools können sensitive Informationen enthalten.

### Monitoring

- `ProgressMonitor`: 5-s-Takt, Konsole und atomisches `status.html`;
- Ressourcenmonitor: Heapwarnung über 90 % des JVM-Maximums;
- JMX-MBean: Prozessmetriken, nicht remote konfiguriert;
- `monitor.sh`: ungeschützter statischer HTTP-Server, keine Prozesssteuerung;
- WebGUI: eigener Prozesszustand und Links, kein Ersatz für Journalprüfung.

Typische normale Meldung ist `Migration completed!`. Bei Producer-/Discovery-Abbruch fehlt sie und der Cause wird nach Cleanup geworfen. `ERROR`, `CASCADE_DELETE_FAILED`, H2-Warnungen, `Journal queue full`, `Shutdown signal received` und fehlende Abschlussreports sind zu eskalieren.

Retention: Logs/Reports gemäß Schutzbedarf archivieren; Rotation reicht nicht als Auditarchiv. Vor Löschung muss die Abnahme abgeschlossen und Journal/Report-Korrelation gesichert sein.

## 19. Exitcodes und Abschlussstatus

| Fall | Nachweisbare Semantik |
|---|---|
| Erfolg eines Shell-/Java-Prozesses | üblicherweise Exit `0`; fachliche Ergebnisse zusätzlich prüfen |
| fehlende Config/JAR/Java, ungültiger Modus/Port, Buildfehler | Launcher/Build beendet mit Nonzero, typischerweise `1` |
| Cascade-Delete-Guard blockiert | Exit `2` |
| `cascade-delete-guard.sh` ohne Argument | Exit `64` |
| riskanter Journalzustand oder Safe mit verbleibendem Non-OK | `cm-run.sh` Exit `2` |
| Producer-/Discovery-Workerfehler im CLI-Main | Cause nach Cleanup weitergereicht; `Main.main` beendet mit Exit `1` |
| Consumer-Einzelfehler | kein stabiler Nonzero-Exit; Journal/Report prüfen |
| Verify-Mismatch/`ERROR` | kein stabiler Nonzero-Exit; `VERIFICATION_LOG`, CSV und Report prüfen |
| Verifier äußere Exception | wird geloggt; `Verifier.main` kann dennoch normal zurückkehren |
| Timeout | kein stabiler numerischer Timeout-Exit; `Main` kann weiter warten |
| Interrupt/Signal | Shutdown semantisch, numerischer Exit nicht als stabile Schnittstelle implementiert |
| WebGUI-Runfehler | Runstatus `FAILED`; WebServerprozess muss nicht enden |

Ein Abschluss ist nur erfolgreich, wenn Prozess beendet, kein relevanter Abbruch/Workerfehler vorliegt und Journal, Verifikationslog, Fehlerzähler und Reports gemeinsam das freigegebene Ergebnis zeigen.

## 20. Fehlerbehebung

| Symptom | Wahrscheinliche Ursache | Prüfung | Sichere Maßnahme |
|---|---|---|---|
| Java nicht gefunden | JRE/JDK fehlt oder PATH falsch | `java -version`; `javac -version` | kompatibles JDK 11+ bereitstellen; nicht blind System-Java ersetzen |
| IBM-Library fehlt | unvollständiges `lib/`/IBM-Setup | Build-/Classpath-Fehler, Library-Herkunft prüfen | freigegebene IBM-JARs/Native Libraries wiederherstellen |
| Source-Verbindung scheitert | SSID, SDK-Datei, Netz, Credential | `migration.log`, DNS/Port außerhalb App prüfen | Config/IBM-Setup korrigieren; keine Destination-Aktion |
| Destination-Verbindung scheitert | SSID, Berechtigung, Pool | Log und Rollen prüfen | Least-Privilege-Credential korrigieren; Lauf stoppen |
| Authentifizierung schlägt fehl | falsches/abgelaufenes Secret | CM-/WebGUI-Log ohne Secretkopie prüfen | Secret rotieren; reversible Legacy-Kodierung ersetzen |
| Timeout | Poolerschöpfung oder blockierter SDK-Call | Thread-/Pool-/Slow-Warnungen, Prozessstatus | keinen Kill während Commit; Ursache klären, kontrolliert stoppen |
| DNS-/Hostfehler | Resolver/Netz | System-DNS und Log prüfen | Infrastruktur korrigieren; wird als `ERROR`, nie `NOT_FOUND` behandelt |
| Journal gesperrt | paralleler Prozess/H2-Konfiguration | Prozessliste, H2-Warnung, `DB_URL_APPEND` | zweiten Lauf stoppen; Journal sichern; nicht löschen |
| Lauf bleibt stehen | In-Flight-IBM-SDK-Aufruf, volle Queue/Pool | Threadzustand, Logs, JMX, `status.html` | kooperativen Stop anfordern; keinen voreiligen Journal-Delete |
| Workerfehler | Producer-/Discovery-Exception | erster Cause im Log | Logs/Journal sichern, Root Cause beheben, Resume prüfen |
| Verify meldet `ERROR` | technischer/unsicherer Source-Lookup oder Hashfehler | `verification_errors.log`, `VERIFICATION_LOG` | Infrastruktur/Permission korrigieren; niemals als „fehlend“ umdeuten |
| Cascade Delete wird blockiert | Option aktiv | Guard-Ausgabe, letzte Propertydefinition | auf `false` setzen; Aktivierung nicht durch Direktaufruf umgehen |
| WebGUI nicht erreichbar | localhost-Bindung, Port, Java/Auth | `logs/webgui.log`, `ss`/`lsof`, localhost testen | SSH-Tunnel verwenden; keine ungeschützte öffentliche Bindung |
| Build scheitert | JDK/IBM-JAR/Sourcefehler | `compile.log` | kleinste Ursache beheben; keine Dependency nachladen ohne Review |
| Report fehlt | Main-Abbruch, WorkerFailure, Pfad/Rechte | Log, Exit, Journal, `reports/` | Abbruch als Fehler behandeln; Rechte/Ursache korrigieren |
| Monitor findet keinen Lauf | falsches Verzeichnis/Port oder kein `status.html` | `logs/monitor.log`, Datei/Port prüfen | Monitor neu im Projektroot starten; nicht öffentlich exponieren |

Riskante Maßnahmen wie Journal löschen, Status manuell umschreiben, Destination-Objekte löschen oder Cascade Delete aktivieren sind keine Standardfehlerbehebung. Vor jeder solchen Aktion: vollständiges Backup, Review, Testumgebung und explizite Freigabe.

## 21. Wiederanlauf nach Fehler

1. Prozess- und Child-Prozessstatus prüfen; keinen zweiten Lauf starten.
2. Logs, Reports, Run-Snapshots und gesamten Journalpfad unverändert sichern.
3. Branch/Commit, Config und Exit-/Runstatus festhalten.
4. ersten Cause und alle nachfolgenden Itemfehler getrennt analysieren.
5. keine manuelle Source-/Destination-Löschung durchführen.
6. Config, Library, Netz, Berechtigung oder Code-Root-Cause beheben.
7. Preflight/Build/Tests erneut ausführen.
8. Journal read-only auf `SUCCESS`, `FAILED`, `SKIPPED`, Destination-IDs und Verify-Status prüfen.
9. Resume nur mit bestätigter Journal-/Mapping-Zuordnung starten.
10. Ergebnis vollständig verifizieren und Betriebsprotokoll ergänzen.

## 22. Backup und Restore

### Sichern

Bei vollständig gestoppten CM-Migrator-/WebGUI-Runs:

```bash
mkdir -p backups
cp -a data/migration_journal "backups/migration_journal-$(date +%Y%m%d-%H%M%S)"
cp -a conf/migration.properties "backups/migration.properties-$(date +%Y%m%d-%H%M%S)"
```

Pfade an den tatsächlichen `DB_PATH` anpassen. Zusätzlich sichern:

- freigegebene Configs und IBM-SDK-Konfiguration;
- Journale vollständig, nicht nur einzelne Tabellen;
- Reports/Logs für Audit und Fehleranalyse;
- externe Keystores getrennt und verschlüsselt;
- Release-/Commitkennung und Library-Checksums.

Nicht als Betriebsbackup verwenden: `target/`, `compile.log`, temporäre Contentdateien, beliebige WebGUI-Run-Snapshots mit veralteten Secrets. Snapshots nur für Incidentzwecke verschlüsselt aufbewahren.

### Restore

1. Restore in einer isolierten Kopie testen.
2. Alle zugreifenden Prozesse stoppen.
3. aktuellen Pfad separat sichern, niemals blind überschreiben.
4. vollständige Journalkopie unter dem erwarteten `DB_PATH` wiederherstellen.
5. Owner/Rechte auf `u=rwX,go=` korrigieren.
6. H2-Version, Mapping und Config-Stand abgleichen.
7. zunächst `cm-run.sh status`, dann nicht produktive Verifikation ausführen.

Beispiel nach bestätigten Pfaden:

```bash
mv data/migration_journal data/migration_journal.pre-restore
cp -a backups/<geprüfte-journal-kopie> data/migration_journal
chmod -R u=rwX,go= data/migration_journal
```

## 23. Wartung

- Java-Updates zuerst gegen IBM-CM-SDK/Native Libraries prüfen.
- IBM-CM-Library-Updates mit Herkunft, Version und Checksums dokumentieren.
- nach jedem Java-/Library-/Config-Update Build und vollständige Regressionstests ausführen;
- Configvergleich ohne Secretwerte durchführen;
- Logrotation und freien Journal-/Temp-Speicher regelmäßig prüfen;
- Journale erst nach fachlicher/technischer Abnahme und Backup bereinigen;
- Credentials, WebGUI-Passwort und Keystores rotieren;
- historische getrackte Backup-/Credential-/Reportartefakte separat klassifizieren;
- `bin/build-release.sh` nicht als nachweislich vollständige Betriebsdistribution annehmen: es kopiert nur eine Teilmenge aktueller Operator-Skripte und referenziert einen nicht vorhandenen `remigrate.sh`;
- Branch, Commit und Release-Artefakt in jeder Betriebsfreigabe festhalten;
- keine Performancezusage ohne Lasttest in der Zielumgebung.

Reale Wartungs-/Paketierungsbefehle:

```bash
./bin/build-release.sh --help
./bin/deploy-source.sh
```

`build-release.sh` unterstützt `--obfuscate`, `--sign`, `--all` und `--version VER`. Ein echter Build löscht und erzeugt seinen Buildbereich neu; Signierung braucht einen separat verwalteten Keystore. `deploy-source.sh` schreibt ein Quellpaket unter das Benutzer-Downloadverzeichnis. Beide schreibenden Abläufe nur außerhalb eines aktiven Laufs und nach Prüfung ihrer Ausgabe verwenden.

## 24. Test- und Abnahmeverfahren

### 24.1 Lokale Testmatrix

```bash
bash tests/test-cascade-delete-guard.sh
bash tests/test-source-lookup-classifier.sh
bash tests/test-verifier-source-lookup-decision.sh
bash tests/test-worker-failure-state.sh
bash tests/test-worker-failure-patch.sh
bash tests/test-worker-failure-apply-script.sh
bash bin/compile.sh
git diff --check
```

Einordnung:

| Test | Evidenzklasse |
|---|---|
| `test-cascade-delete-guard.sh` | lokaler Shell-Funktionstest des Guards |
| `test-source-lookup-classifier.sh` | lokaler Unit-Test des dependency-armen Classifiers |
| `test-verifier-source-lookup-decision.sh` | Unit-Test der finalen Delete-Entscheidung plus Strukturprüfung des Verifiers |
| `test-worker-failure-state.sh` | lokaler Unit-Test des ersten Fehlerzustands/Cause |
| `test-worker-failure-patch.sh` | Strukturtest des Patch-Artefakts |
| `test-worker-failure-apply-script.sh` | Struktur-/Dry-Run-Test des Apply-Skripts |
| `bin/compile.sh` | lokaler Java-11-Compile-/JAR-Build mit vorhandenen Libraries |

Keine dieser Prüfungen ist ein IBM-CM-Live-E2E-Test.

### 24.2 Fehlender IBM-Live-E2E-Test

Nur in einer isolierten Testumgebung mit gesicherten, nicht produktiven Source-/Destination-Objekten durchführen. Mindestens prüfen:

- bestätigter `EXISTS`-Fall mit korrektem Hash;
- bestätigter `NOT_FOUND`-Fall mit exakt zugeordneter PID;
- Source-Lookup-Timeout;
- Authentifizierungsfehler;
- Berechtigungsfehler;
- DNS-/Netzwerkfehler;
- unbekannte/abweichende IBM-Exceptionmeldung;
- Cascade Delete deaktiviert;
- Cascade Delete kontrolliert aktiviert, mit vorab dokumentiertem Delete-Set;
- Destination-Delete-Fehler/Rollback;
- Producer-/Discovery-Fehler und Cause-Weiterleitung;
- Consumer-Einzelfehler;
- externer Interrupt während Discovery, Consumer und SDK-Aufruf;
- Resume nach kontrolliertem Abbruch;
- Journal-Queue/Drain unter Last;
- Report-/Mail-Unterdrückung bei Workerfehler;
- Journal-, CSV-, HTML-, Exit- und Logkorrelation.

Akzeptanz nur, wenn `ERROR` in keinem technischen Fehlerfall zu Delete führt, `NOT_FOUND` ausschließlich beim bestätigten Objektfall entsteht und Workerfehler keinen Erfolg/reportierten Normalabschluss erzeugen.

## 25. Betriebssicherheits-Checkliste

- [ ] richtiger Branch/Release/Commit bestätigt
- [ ] Arbeitsbaum und Build-Artefakt geprüft
- [ ] Java- und IBM-Library-Stand freigegeben
- [ ] Config und explizites ItemType-Mapping geprüft
- [ ] Secrets nicht im Repository oder in Kommandozeilen
- [ ] Config-/Journal-/Keystore-Backup vorhanden und Restore geprüft
- [ ] `CASCADE_DELETE_ON_MISSING=false` oder separate schriftliche Freigabe plus Live-Test
- [ ] Source und Destination erreichbar; Rechte nach Least Privilege
- [ ] Journal exklusiv und beschreibbar
- [ ] ausreichend Speicher für Journal, Reports und Temp-Content
- [ ] Monitoring gewählt, ohne ungeschützte Netzwerkexposition
- [ ] Logs/Reports werden geschützt gesichert
- [ ] Worker-/Consumer-/Verify-Status und Exit gemeinsam geprüft
- [ ] Abschlussstatus und Abweichungen protokolliert

## 26. Bekannte Einschränkungen und offene Risiken

- kein IBM-CM-Live-E2E-Test der neuen Sicherheitsänderungen;
- keine GitHub-Workflow-/CI-Checks im aktuellen Branch;
- lokale, installationsabhängige IBM- und Third-Party-Libraries unter `lib/`;
- Classifier hängt von beobachteten IBM-Exceptiontexten ab; unbekannte Meldungen werden sicher `ERROR`;
- unterstützter Verify-Launcher blockiert aktiviertes Cascade Delete trotz implementiertem Tri-State;
- `cm-run safe` prüft Cascade Delete erst in der Verify-Phase nach Migration;
- WebGUI-Profilwahl ist nicht vollständig vom Start-Guard erfasst;
- WebGUI-`safe` ist schwächer als CLI-`safe`;
- WebGUI-Verify/`safe` kann wegen der Exceptionbehandlung in `Verifier.main` einen irreführenden `COMPLETED`-Status liefern;
- statische Verifier-Zähler sind bei mehreren Runs derselben JVM nicht sauber isoliert;
- Main-24-h-Wait ist kein harter Timeout; SDK-Calls können länger blockieren;
- Consumer-Einzelfehler und Verify-Mismatches besitzen keinen stabilen Nonzero-Prozesscode;
- asynchrones Journal kann bei Queue-Überlauf Updates verwerfen; Drain ist auf 30 s begrenzt;
- VerificationLogger kann nach wiederholten Flushproblemen bei voller Queue Einträge verlieren;
- `monitor.sh` exponiert ohne zusätzliche Schutzmaßnahmen das Projektverzeichnis;
- WebGUI Basic Auth hat kein TLS und verwendet einen ungesalzenen SHA-256-Hash;
- WebGUI-Run-Snapshots können Credentials dauerhaft hinterlassen;
- historische Backup-, Report-, Debug-Mail-, Source-Kopie- und Keystore-Dateien sind teilweise getrackt;
- der separate Maven-/Legacy-Tree besitzt eigene COPY/MOVE/DELETE- und Journal-Semantik, ist nicht Teil der aktuellen Safety-Tests und darf nicht mit dem Primärbetrieb gleichgesetzt werden;
- `conf/migration.properties.example` enthält numerische Werte mit Inline-Kommentaren, die auf Code-Defaults zurückfallen können;
- `DATA_DIR`, `LOG_ERRORS_IMMEDIATE`, `AUDIT_PROTOCOL_OUTPUT_DIR`, `PROTOCOL_OUTPUT_DIR` und mehrere historische Optionen haben keine nachgewiesene Laufzeitwirkung;
- bestehende Java-Deprecation-/Unchecked-Warnungen sind möglich;
- keine belastbare Aussage über produktive Performance ohne Umgebungstest.

## 27. Änderungs- und Freigabeprozess

Vor Merge beziehungsweise produktiver Nutzung:

1. Diff und Aufrufer-/Laufzeitfluss reviewen;
2. Secrets/produktive Werte im Diff und in historischen Artefakten prüfen;
3. sechs lokale Tests, Build und `git diff --check` ausführen;
4. Security-Review für Delete, Lookup-Klassifikation, Shutdown und Journal durchführen;
5. kontrollierten IBM-Live-E2E-Test nach Abschnitt 24 dokumentieren;
6. Betriebsfreigabe mit Branch/Commit, Configklasse, Ergebnissen und Restrisiken festhalten;
7. Rollback definieren: vorheriges Artefakt, Config-/Journalbackup, Restore-Test und Stopkriterien;
8. erst danach nach `main` mergen und Releaseartefakt verifizieren;
9. nach Rollout Smoke-Test, Logs, Journal und Reports prüfen.

Organisatorische Rollen werden hier nicht festgelegt. Review, Live-Test und Freigabe müssen jedoch nachweisbar und voneinander nachvollziehbar sein.

---

**Dokumentationsgrenze:** Dieses Handbuch beschreibt den aktuellen Code- und Skriptstand. Bei Widersprüchen zu historischen Berichten gelten der ausgeführte Commit, die aktuelle Implementierung und frisch reproduzierte Testergebnisse. Allgemeine Security- und Disclosure-Regeln stehen in [SECURITY.md](SECURITY.md).
