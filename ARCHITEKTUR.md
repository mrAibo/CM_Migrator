# IBM CM Migrator v2.2.1 - Architektur & Quellcode-Dokumentation

**Letzte Aktualisierung:** 23. Juli 2026
**Version:** 2.2.1

---

## 1. System-Überblick

Der IBM CM Migrator ist ein hochperformantes, Java 11-basiertes Migrationstool für IBM Content Manager. Es ersetzt das veraltete `icmbatch` und bietet signifikant verbesserte Performance durch Multi-Threading, asynchrones Journaling und intelligente Caching-Mechanismen.

### 1.1 Architektur-Diagramm

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                            CM Migrator v2.2.1                                     │
├──────────────────────────────────────────────────────────────────────────────────┤
│                                                                                   │
│  ┌──────────────────┐              ┌────────────────────┐                        │
│  │ CliLifecycleRunner│─────────────│  MigrationConfig   │                        │
│  │ CliShutdownLifecycle│           │  (Konfiguration)   │                        │
│  │ (CLI Lifecycle)  │              │  ConfigAutoDetector│                        │
│  └────────┬─────────┘              └─────────┬──────────┘                        │
│           │                                  │                                    │
│           ▼                                  ▼                                    │
│  ┌──────────────────┐              ┌────────────────────┐                        │
│  │    Main.java     │─────────────▶│ OperationalPolicy  │                        │
│  │  (Bootstrap)     │              │ (Policy Enforcement)│                        │
│  └────────┬─────────┘              └─────────┬──────────┘                        │
│           │                                  │                                    │
│           │                    ┌─────────────┘                                    │
│           ▼                    ▼                                                  │
│  ┌──────────────────┐     ┌──────────────────────┐     ┌───────────────────┐     │
│  │    Producer      │────▶│   BlockingQueue      │────▶│    Consumer(s)    │     │
│  │  (Discovery)     │     │   (MigrationItem)    │     │    (Workers)      │     │
│  │  - ItemType      │     └──────────────────────┘     └─────────┬─────────┘     │
│  │    Discovery     │                                            │               │
│  │  - SDK Counting  │                                            ▼               │
│  │  - Filtering     │                               ┌───────────────────────┐    │
│  └────────┬─────────┘                               │    ItemMigrator       │    │
│           │                                         │  - Attribute Copy     │    │
│           ▼                                         │  - Part Migration     │    │
│  ┌──────────────────┐                               │  - SHA-256 Hashing    │    │
│  │ ShutdownCoordinator│                             └───────────┬───────────┘    │
│  │ WorkerFailureState│                                          │                │
│  │ WorkerTermination │                                          │                │
│  └──────────────────┘                                          │                │
│                                                                │                │
│  ┌──────────────────────────────────────────────────────────────┴──────────────┐ │
│  │                        CMConnectionPool                                     │ │
│  │  ┌─────────────────┐                    ┌─────────────────┐                │ │
│  │  │  Source Pool    │                    │   Dest Pool     │                │ │
│  │  │  (CMConnection) │                    │  (CMConnection) │                │ │
│  │  └─────────────────┘                    └─────────────────┘                │ │
│  └─────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                   │
│  ┌──────────────────────────────────────────────────────────────────────────────┐ │
│  │                     MigrationJournal (H2 Database)                            │ │
│  │  ┌─────────────────┐  ┌────────────────┐  ┌──────────────────────────┐      │ │
│  │  │  Async Writer   │  │  LRU Cache     │  │  Per-ItemType DB Files  │      │ │
│  │  │  (Background)   │  │  (5M Entries)  │  │  (journal_*.mv.db)      │      │ │
│  │  └─────────────────┘  └────────────────┘  └──────────────────────────┘      │ │
│  └──────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                   │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────────────┐     │
│  │ProgressMonitor│ │ReportGenerator│ │ EmailNotifier│ │  ResourceGuardian   │     │
│  │ (Live Status) │ │ProtocolReport │ │ (mutt/mailx) │ │  (Temp-File Cleanup)│     │
│  │  ConsoleUI    │ │  Generator    │ │              │ │                     │     │
│  └──────────────┘ └──────────────┘ └──────────────┘ └──────────────────────┘     │
│                                                                                   │
│  ┌──────────────────────────────────────────────────────────────────────────────┐ │
│  │                           WebGUI (optional)                                   │ │
│  │  ┌──────────────┐  ┌──────────────────┐  ┌──────────────────────────────┐    │ │
│  │  │  WebServer   │  │   AuthHandler    │  │  WebGuiRunSlot               │    │ │
│  │  │  (HTTP)      │  │ (Basic Auth +    │  │ (Atomic Run Reservation)     │    │ │
│  │  │              │  │  Rate Limiting)  │  │  RunConfigSnapshot           │    │ │
│  │  └──────────────┘  └──────────────────┘  └──────────────────────────────┘    │ │
│  └──────────────────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Quellcode-Dokumentation

### 2.1 Verzeichnisstruktur

```
src/com/ibm/ecm/migration/
│
├── Start, Konfiguration und Konsole ─────────────────────────────────────
├── Main.java                        # Entry Point, Orchestrierung
├── MigrationConfig.java             # Konfigurationsloader mit Profile Engine
├── ConfigAutoDetector.java          # Automatische Config-Dateierkennung
├── ConsoleUI.java                   # ANSI-Konsolenausgabe (pretty/plain)
├── SdkCapabilityProbe.java          # Sondierung von IBM-SDK-Fähigkeiten
│
├── Migration und CM-Zugriff ─────────────────────────────────────────────
├── Producer.java                    # Item-Discovery & Enqueueing
├── Consumer.java                    # Worker-Threads mit Batch-Processing
├── ItemMigrator.java                # Migrations-Logik (Copy & Hash)
├── MigrationItem.java               # Data Transfer Object
├── CMConnection.java                # Einzelne IBM CM Verbindung
├── CMConnectionPool.java            # Connection-Pool-Management
├── PermanentMigrationException.java # Nicht-wiederholbare Migrationsfehler
│
├── Lifecycle und Status ─────────────────────────────────────────────────
├── ShutdownCoordinator.java         # Globale kooperative Shutdown-Anforderung
├── WorkerFailureState.java          # Speichert ersten asynchronen Worker-Fehler
├── WorkerTermination.java           # Begrenzte Zwei-Phasen-Executor-Terminierung
├── ResourceGuardian.java            # Temp-File-Registrierung und Cleanup
├── MigrationStats.java              # Thread-sichere Statistiken
├── MigrationMetrics.java            # JMX Monitoring (v1.30)
├── MigrationMetricsMBean.java       # JMX MBean Interface
├── ProgressMonitor.java             # Live-Dashboard Generator
│
├── Journal und Reports ──────────────────────────────────────────────────
├── MigrationJournal.java            # H2-Persistenz mit LRU-Cache
├── VerificationLogger.java          # Batch-Logging für Verifikation
├── ReportGenerator.java             # HTML-Report-Erstellung
├── ProtocolReportGenerator.java     # Formelle Protokollberichte pro ItemType
├── AuditProtocolGenerator.java      # Formelle Prüfprotokolle
├── ProtocolData.java                # Datencontainer für Protokoll-Zeilen
│
├── Verifikation und Sicherheit ──────────────────────────────────────────
├── Verifier.java                    # Post-Migration Integritätsprüfung
├── SourceLookupStatus.java          # Tri-State Enum (EXISTS/NOT_FOUND/ERROR)
├── SourceLookupClassifier.java      # Konservative Fehlerklassifikation (fail-closed)
│
├── Architektur-Sicherheit und Policy ────────────────────────────────────
├── OperationalPolicy.java           # Betriebssicherheits-Policy vor Main/Verifier
├── RunTerminationException.java     # Strukturierter Exit mit Reason/Exitcode
├── CliShutdownLifecycle.java        # Begrenzter JVM-Shutdown-Hook (1 CLI-Invocation)
├── CliLifecycleRunner.java          # Gemeinsamer CLI-Exit/Cleanup-Vertrag
│
├── Web und Benachrichtigung ─────────────────────────────────────────────
├── WebServer.java                   # WebGUI HTTP-Server
├── AuthHandler.java                 # HTTP Basic Auth mit Rate-Limiting
├── WebGuiRunSlot.java               # Atomare WebGUI-Run-Reservierung
├── RunConfigSnapshot.java           # Owner-only WebGUI Run-Konfigurations-Snapshots
├── EmailNotifier.java               # E-Mail-Versand (mutt/mailx)
│
└── Legacy- und Diagnosewerkzeuge ────────────────────────────────────────
    ├── RemigrationTool.java          # Fehlgeschlagene Items erneut migrieren
    ├── SDKTest.java                  # IBM SDK Direkttest
    └── StandaloneParserTest.java     # Parser-Unit-Test (Standalone)
```

### 2.2 Komponenten-Beschreibung

---

#### **Main.java** - Bootstrap & Orchestrierung

**Zweck:** Entry Point der Anwendung. Initialisiert alle Komponenten und orchestriert den Migrationsprozess.

**Hauptaufgaben:**
- Laden der Konfiguration (`MigrationConfig`, `ConfigAutoDetector`)
- Durchlaufen der `OperationalPolicy` vor Migration/Verifikation
- Initialisierung des Journals (`MigrationJournal`)
- Aufbau der Transfer-Queue (`LinkedBlockingQueue`)
- Starten des Connection Pools (`CMConnectionPool`)
- Spawnen von Producer und Consumer Threads
- `WorkerFailureState`-Propagation und `ShutdownCoordinator`-Integration
- Graceful Shutdown über `CliShutdownLifecycle` / `CliLifecycleRunner`

**Wichtige Methoden:**
```java
public static void main(String[] args)           // Entry Point
CliLifecycleRunner.executeCli(...)                // CLI Lifecycle
private static void setupShutdownHook(...)        // SIGTERM/INT Handling
private static void startResourceMonitor()        // Memory Monitoring
```

---

#### **MigrationConfig.java** - Konfiguration

**Zweck:** Lädt und verwaltet alle Konfigurationsparameter aus `migration.properties`.

**Features:**
- **Diamond Profile Engine (v1.28):** Vordefinierte Lastprofile (KLEIN, MITTEL, GROSS, EXTREM, ULTI)
- **ConfigAutoDetector:** Automatische Erkennung der Konfigurationsdatei
- Fuzzy Key Matching (MIGRATE_ITEMTYPES = MIGRATEITEMTYPES)
- Passwort-Verschlüsselung (Base64 mit Reverse)
- Separate Credentials für Source und Destination
- WebGUI-Konfiguration (`webgui.properties`)

**Profile-Defaults:**

| Profil | THREAD_COUNT | BATCH_SIZE | QUEUE_SIZE | LOG_BATCH |
|--------|--------------|------------|------------|-----------|
| KLEIN  | 5            | 50         | 1,000      | false     |
| MITTEL | 20           | 200        | 5,000      | true      |
| GROSS  | 50           | 500        | 10,000     | true      |
| EXTREM | 100          | 1,000      | 20,000     | true      |
| ULTI   | 200          | 2,000      | 50,000     | true      |

---

#### **OperationalPolicy.java** - Sicherheits-Policy (v2.2.1)

**Zweck:** Erzwingt betriebliche Sicherheitsregeln nach dem Laden der effektiven Konfiguration, **bevor** Main oder Verifier gestartet werden.

**Policy:**
- `CASCADE_DELETE_ON_MISSING` muss deaktiviert sein (`false`)
- Bei Verstoß: `RunTerminationException` mit `Reason.POLICY` und Exit-Code 2
- Wird sowohl vom CLI- als auch vom WebGUI-Pfad durchlaufen

---

#### **RunTerminationException.java** - Strukturierter Exit (v2.2.1)

**Zweck:** Terminaler Run-Ausgang, den Aufrufer ohne Terminierung von Library-/WebGUI-Code abbilden können.

**Reasons:**
| Reason | Exit Code | Web-Status | Bedeutung |
|--------|-----------|------------|-----------|
| `FAILED` | 1 | `FAILED` | Allgemeiner Fehler |
| `POLICY` | 2 | `POLICY_REFUSED` | Policy-Verstoß |
| `TIMEOUT` | 124 | `TIMED_OUT` | Timeout |
| `INTERRUPTED` | 130 | `INTERRUPTED` | Externer Interrupt |

Trägt `terminationConfirmed` für die Lebenszyklus-Koordination.

---

#### **CliShutdownLifecycle.java** - Begrenzter Shutdown-Hook (v2.2.1)

**Zweck:** Ein begrenzter JVM-Shutdown-Hook pro CLI-Aufruf. Verhindert unbefristetes Warten in Shutdown-Hooks.

**Verhalten:**
- Registriert einen `Runtime.addShutdownHook`
- Bei Signal: setzt `ShutdownCoordinator.requestShutdown()` und wartet maximal `graceSeconds`
- `finish(confirmed)` entfernt den Hook und signalisiert Abschluss
- Kein Logging, keine VM-Beendigung — reiner Koordinationsmechanismus

---

#### **CliLifecycleRunner.java** - CLI Lifecycle-Vertrag (v2.2.1)

**Zweck:** Gemeinsamer CLI-Exit/Cleanup-Vertrag. Abhängigkeitsfrei, damit Tests und echtes Main denselben Produktionspfad ausführen können — auch wenn `lib/` in CI fehlt.

**Ablauf:**
1. `CliShutdownLifecycle` registrieren
2. Operation ausführen
3. Bei `RunTerminationException`: Grund und Exit-Code extrahieren
4. Bei anderer Exception: Exit-Code 1, `terminationConfirmed = false`
5. Im `finally`: `lifecycle.finish(terminationConfirmed)`
6. Gibt `CliRunResult` mit Exit-Code, Failure und Confirmation zurück

---

#### **ShutdownCoordinator.java** - Globale Shutdown-Koordination (v2.2.1)

**Zweck:** Thread-sicheres, globales Shutdown-Flag für kooperativen Abbruch.

**API:**
- `requestShutdown()` — setzt das Flag
- `reset()` — setzt zurück (nur nach exklusiver WebGUI-Reservierung)
- `isShuttingDown()` — prüft Flag **und** `Thread.interrupted()`

Consumer, Producer und Verifier prüfen dieses Flag in ihren Hauptschleifen.

---

#### **WorkerFailureState.java** - Fehlerweiterleitung (v2.2.1)

**Zweck:** Speichert atomar den ersten asynchronen Worker-Fehler (Producer/Discovery) zur späteren Propagation an den koordinierenden Thread.

**Verhalten:**
- `record(Throwable)` — nur der erste Fehler wird gespeichert (AtomicReference#compareAndSet)
- Spätere Fehler werden nur geloggt
- `throwIfPresent(message)` — wirft `IllegalStateException` mit ursprünglichem Cause
- Deckt nicht jede Consumer-Exception ab; Consumer-Einzelfehler erscheinen im Journal/Report

---

#### **WorkerTermination.java** - Zwei-Phasen-Terminierung (v2.2.1)

**Zweck:** Begrenzte ExecutorService-Terminierung mit zwei Timeout-Phasen. Behauptet nie, dass native SDK-Aufrufe gestoppt wurden.

**Phasen:**
1. `shutdown()` + `awaitTermination(waitSeconds)`
2. Bei Timeout: `shutdownRequest` (kooperativer Shutdown) + `awaitTermination(graceSeconds)`

Hilfsmethoden: `awaitGrace()` für Shutdown-Request + Grace-Phase, `awaitGraceAfterInterrupt()` mit Interrupt-Flag-Wiederherstellung.

---

#### **ResourceGuardian.java** - Ressourcen-Tracking

**Zweck:** Verhindert Festplatten-Vermüllung durch nicht aufgeräumte temporäre Dateien.

**Features:**
- ThreadLocal-Registry für temporäre Dateien
- `register(File)` / `unregister(File)` / `cleanup()`
- Fallback: `deleteOnExit()` bei fehlgeschlagenem Löschen

---

#### **Producer.java** - Item-Discovery

**Zweck:** Entdeckt zu migrierende Items und legt sie in die Queue.

**Workflow:**
1. Parallelisiert Discovery per ItemType (max. 10 Threads)
2. **PASS 1:** Zählt Items (SQL COUNT oder SDK Cursor)
3. **PASS 2:** Enqueued Items, überspringt bereits migrierte
4. Sendet Poison-Pills zum Beenden der Consumer
5. Bei Producer-/Discovery-Fehler: `WorkerFailureState.record()` + `ShutdownCoordinator.requestShutdown()`

**v2.2.1:** Bei Shutdown oder Producer-Fehler werden **keine** normalen Poison-Pills gesendet; Consumer verlassen den Loop über das globale Shutdown-Signal.

**Discovery-Strategien:**
- `HYBRID` (Default): SQL COUNT mit SDK Fallback
- `COUNT_SQL`: Nur nativer SQL Count
- `SDK_CURSOR`: Nur IBM SDK Counting

---

#### **Consumer.java** - Worker-Threads

**Zweck:** Konsumiert Items aus der Queue und führt Migration durch.

**Features:**
- Batch-Processing mit konfigurierbarer Batch-Größe
- Iteratives Batch-Splitting bei Fehlern
- Exponentielles Backoff bei transienten Fehlern
- Adaptive Batch-Größe basierend auf Erfolgsrate
- Prüft `ShutdownCoordinator.isShuttingDown()` in der Hauptschleife

**Retry-Logik:**
```
Versuch 1 → Fehler → 250ms warten
Versuch 2 → Fehler → 500ms warten  
Versuch 3 → Fehler → Batch splitten oder Fehlschlag loggen
```

---

#### **ItemMigrator.java** - Migrations-Engine

**Zweck:** Führt die eigentliche Migration eines einzelnen Items durch.

**Phasen:**
1. **Retrieve:** Item vom Source laden (mit DKRetrieveOptionsICM)
2. **Copy Attributes:** Alle Attribute kopieren (mit Type-Konvertierung)
3. **Copy Children:** Child Collections rekursiv kopieren
4. **Copy Parts:** Binärdaten mit Single-Pass SHA-256 Hashing
5. **Add:** Neues Item im Ziel erstellen

**Performance-Features:**
- ThreadLocal Attribute-Cache (vermeidet redundante dataId Lookups)
- Single-Pass Hashing während des Streamings
- Optional: Stream-Upload ohne Temp-Files (via JVM Flag)

---

#### **MigrationJournal.java** - Persistenz

**Zweck:** Verwaltet den Migrationsstatus in H2-Datenbanken.

**Features:**
- **LRU-bounded Cache:** Max. 5M Einträge (konfigurierbar)
- **Async Journaling:** Schreib-Queue mit Background-Writer
- **Per-ItemType DBs:** Separate Datei pro ItemType
- `VERIFICATION_LOG` in derselben Datenbank

**Schema (AUDIT_LOG):**
```sql
CREATE TABLE AUDIT_LOG (
    ITEM_ID VARCHAR(255) PRIMARY KEY,
    DEST_ITEM_ID VARCHAR(255),
    ITEM_TYPE VARCHAR(100),
    STATUS VARCHAR(20),      -- SUCCESS, FAILED, SKIPPED, DELETED
    CHECKSUM VARCHAR(64),    -- SHA-256
    MESSAGE VARCHAR(4000),
    MIGRATION_TIME TIMESTAMP
);
```

---

#### **CMConnectionPool.java** - Connection Management

**Zweck:** Verwaltet IBM CM Verbindungen für Source und Destination.

**Features:**
- Separate Pools für Source und Dest
- SSID-Validierung (verhindert Cross-Pool Kontamination)
- Async Refill bei Connection-Verlust
- Connection Rotation (Age/Usage-basiert)
- Pool-Metriken (Borrow-Wait-Time, Refill-Stats)

**Deadlock-Prävention:**
- Non-blocking `poll()` mit Timeout statt blocking `take()`
- Emergency Fallback: Neue Connection bei leerem Pool

---

#### **Verifier.java** - Integritätsprüfung

**Zweck:** Verifiziert migrierte Daten durch Hash-Vergleich.

**Modi:**
1. **Dest-Only (schnell):** Nutzt gespeicherten Hash aus Journal
2. **Full-Check (langsam):** Lädt Source und Dest neu

**Source-Lookup-Entscheidung (Tri-State, v2.2.1):**

| Status | Bedeutung | Aktion | Destination löschen |
|--------|-----------|--------|---------------------|
| `EXISTS` | Source eindeutig gefunden | Normal verifizieren | Nein |
| `NOT_FOUND` | Exception konservativ als fehlend klassifiziert | `ORPHANED` oder Cascade | Nur bei aktivierter Option |
| `ERROR` | Technischer/unbekannter Fehler | `ERROR` protokollieren | **Nie** |

`SourceLookupClassifier` ist fail-closed: Timeout, Authentifizierung, DNS/Netzwerk und unbekannte Meldungen werden als `ERROR` behandelt.

---

#### **SourceLookupClassifier.java** - Fail-Closed Klassifikation (v2.2.1)

**Zweck:** Konservative Klassifikation von IBM-CM Source-Lookup-Fehlern. False-Negatives sind akzeptabel, False-Positives nicht — `NOT_FOUND` kann Destination-Löschung autorisieren.

**Regeln:**
- Verlangt exakte PID-Übereinstimmung in der Fehlermeldung
- Beschränkte Cause-Chain-Tiefe (max. 16)
- Erkennt enge Muster: "item/object/document not found/does not exist/no longer exists" + PID
- Auch: "dkc_unknown while retrieving" + PID
- Alles andere → `ERROR`

---

#### **WebGUI-Komponenten (v2.2.1)**

**WebServer.java:** Embedded HTTP-Server (`com.sun.net.httpserver`). Bindet standardmäßig `127.0.0.1`. Endpunkte: UI, `/api/status`, `/api/config`, `/api/run`, `/api/health` (öffentlich).

**AuthHandler.java:** HTTP Basic Authentication Wrapper. Features:
- SHA-256 Passwort-Hashing (ungesalzen, Legacy)
- Rate-Limiting: 5 Fehlversuche → 5 Minuten Sperre pro Client-IP
- Fail-Closed: Fehlende/ungültige Auth-Werte brechen vor Port-Bind ab
- Kein temporäres Passwort, keine Secrets in Fehlertexten

**WebGuiRunSlot.java:** Atomare Run-Reservierung über `AtomicBoolean`. Nur ein WebGUI-Run gleichzeitig. Setzt `ShutdownCoordinator` bei exklusiver Reservierung zurück.

**RunConfigSnapshot.java:** Erstellt Owner-only (0600) Konfigurations-Snapshots unter `data/webgui-runs/`. Validiert Run-ID (alphanumerisch), verweigert Symlinks, verhindert Überschreiben existierender Snapshots.

---

#### **ProgressMonitor.java** - Live-Dashboard

**Zweck:** Generiert `status.html` für Live-Monitoring.

**Features:**
- Auto-Refresh (5 Sekunden)
- Fortschrittsbalken
- ETA-Berechnung
- Items/Sekunde Metrik
- Atomisches Schreiben über `.status.html.tmp` → `status.html`

---

#### **ConsoleUI.java** - Konsolenausgabe (v2.2.1)

**Zweck:** ANSI-basierte Konsolenausgabe mit zwei Modi: `pretty` (farbig, Fortschrittsbalken) und `plain` (einfach). Wird durch `CM_CONSOLE_MODE` oder `NO_COLOR`/`TERM` gesteuert.

---

#### **ConfigAutoDetector.java** - Automatische Config-Erkennung (v2.2.1)

**Zweck:** Erkennt die zu verwendende Konfigurationsdatei, wenn keine explizit übergeben wurde. Durchsucht `conf/` nach `migration.properties`.

---

#### **SdkCapabilityProbe.java** - SDK-Fähigkeitssondierung (v2.2.1)

**Zweck:** Sondiert IBM-SDK-Fähigkeiten vor dem Start, um nicht-unterstützte Features frühzeitig zu erkennen.

---

#### **MigrationMetrics.java / MigrationMetricsMBean.java** - JMX

**Zweck:** Stellt Prozessmetriken als JMX MBean `com.ibm.ecm.migration:type=MigrationEngine` bereit. Nicht remote konfiguriert.

---

### 2.3 Shell-Skripte

| Skript | Beschreibung |
|--------|--------------|
| `bin/cm-run.sh` | Operator-Wrapper für Migration, Delete, Verify, Safe, Status |
| `bin/start.sh` | Startet Migration mit optimierten JVM-Parametern |
| `bin/verify.sh` | Startet Verifikation (mit Cascade-Delete-Guard) |
| `bin/webgui.sh` | Startet WebGUI (localhost default) |
| `bin/compile.sh` | Kompiliert Sources und erstellt JAR |
| `bin/monitor.sh` | Startet Python HTTP-Server für Dashboard |
| `bin/cascade-delete-guard.sh` | Eigenständiger Containment-Guard |
| `bin/build-release.sh` | Release-Paketierung |
| `bin/deploy-source.sh` | Quellpaket-Erstellung |
| `run.sh` | Historischer Launcher für Legacy-Prototyp |

**v2.2.1 Verbesserungen:**
- Einheitliche JVM-Parameter in allen Skripten
- Fehlerbehandlung mit `set -e`
- Validierung von JAR/Config vor Start
- Cascade-Delete-Guard in `verify.sh` und `cm-run.sh verification*`
- WebGUI-Launcher mit Port/Bind-Optionen und Auth-Guard

---

## 3. Datenfluss

```
┌─────────────┐                                           ┌─────────────┐
│ IBM CM      │                                           │ IBM CM      │
│ Source      │                                           │ Destination │
└──────┬──────┘                                           └──────▲──────┘
       │                                                         │
       │ DKDatastoreICM.execute()                               │
       │ (XQPE Query)                                           │
       ▼                                                         │
┌──────────────┐                                                 │
│   Producer   │                                                 │
│              │                                                 │
│ 1. Count     │                                                 │
│ 2. Enqueue   │                                                 │
│ 3. Skip      │                                                 │
│    migrated  │                                                 │
└──────┬───────┘                                                 │
       │                                                         │
       ▼                                                         │
┌──────────────┐    ┌─────────────┐    ┌─────────────────┐      │
│ShutdownCoord │    │    Queue    │───▶│    Consumer     │──────┤
│WorkerFailure │    │             │    │                 │      │
│   State      │    │ Capacity:   │    │ 1. migrateBatch │      │
└──────────────┘    │ 10,000-     │    │ 2. SHA-256 Hash │      │
                    │ 50,000      │    │ 3. Log Journal  │      │
                    └─────────────┘    │ 4. Retry/Split  │      │
                                       └────────┬────────┘      │
                                                │               │
                                                ▼               │
                                       ┌─────────────────┐      │
                                       │ MigrationJournal│      │
                                       │  (H2 + Cache)   │      │
                                       └─────────────────┘      │
                                                │               │
                                                ▼               │
                                       ┌─────────────────┐      │
                                       │  Operational    │      │
                                       │    Policy       │      │
                                       │ (Cascade-Delete │      │
                                       │  Containment)   │      │
                                       └────────┬────────┘      │
                                                │               │
                                                ▼               │
                                       ┌─────────────────┐      │
                                       │    Verifier     │──────┘
                                       │ + SourceLookup  │
                                       │   Classifier    │
                                       │ (Fail-Closed)   │
                                       └─────────────────┘
```

**Ablauf (v2.2.1):**

1. `Main` lädt Konfiguration, durchläuft `OperationalPolicy`
2. Producer entdeckt Items (2-Pass SDK)
3. Consumer migrieren Batches, prüfen `ShutdownCoordinator.isShuttingDown()`
4. Bei Producer-/Discovery-Fehler: `WorkerFailureState.record()` → `ShutdownCoordinator.requestShutdown()`
5. Nach Migration: `Verifier` prüft über `OperationalPolicy` → `SourceLookupClassifier` (fail-closed)
6. `CliLifecycleRunner` garantiert `CliShutdownLifecycle.finish()` im `finally`-Block
7. `WorkerTermination` führt begrenzte Zwei-Phasen-Executor-Terminierung durch

---

## 4. Sicherheitsarchitektur (v2.2.1)

### 4.1 Cascade-Delete-Containment

Die schwerwiegendste Sicherheitsentscheidung ist Cascade Delete — das Löschen von Destination-Objekten bei fehlenden Source-Objekten.

**Drei-Schichten-Containment:**

| Schicht | Komponente | Mechanismus |
|---------|------------|-------------|
| 1. Launcher | `bin/cascade-delete-guard.sh`, `bin/verify.sh`, `bin/webgui.sh` | Blockiert `CASCADE_DELETE_ON_MISSING=true` vor Java-Start |
| 2. Policy | `OperationalPolicy.enforceCascadeDeleteDisabled()` | Java-seitige Prüfung nach Config-Laden; wirft `RunTerminationException(Reason.POLICY)` |
| 3. Java-Logik | `Verifier.shouldCascadeDelete()`, `SourceLookupClassifier` | Tri-State: nur `NOT_FOUND` + `true` aktiviert; `ERROR` führt **nie** zu Delete |

- `OperationalPolicy` wird **vor** jedem Migrations-/Verifikationslauf durchlaufen
- Der Launcher-Guard bleibt als temporäres betriebliches Containment bis zur IBM-Live-Abnahme
- `verify.sh` blockiert jede aktivierte Option mit Exit 2
- WebGUI prüft nur `conf/migration.properties` beim Serverstart

### 4.2 Fail-Closed Source-Lookup (Tri-State)

`SourceLookupClassifier` klassifiziert Source-Lookup-Fehler konservativ:

- **`NOT_FOUND` nur bei engen Mustern mit exakter PID:** "item/object/document not found/does not exist/no longer exists" + PID oder "dkc_unknown while retrieving" + PID
- **Alles andere → `ERROR`:** Timeout, Authentifizierung, Berechtigung, DNS/Netzwerk, unbekannte Meldungen
- **Keine False Positives:** `ERROR` kann nie zu Destination-Löschung führen
- Begrenzte Cause-Chain-Tiefe (max. 16) verhindert endlose Traversierung

### 4.3 CLI Lifecycle

`CliShutdownLifecycle` + `CliLifecycleRunner` ersetzen den vorherigen unbegrenzten Shutdown-Hook:

- **Begrenzte Wartezeit:** `graceSeconds` (default 60s) statt unbegrenztem Wait
- **Strukturierter Exit:** `RunTerminationException` mit Reason/Exit-Code/Web-Status
- **Garantiertes Cleanup:** `finally`-Block in `executeCli()` ruft immer `lifecycle.finish()`
- **Abhängigkeitsfrei:** `CliLifecycleRunner` benötigt kein IBM-SDK; identischer Pfad in CI und Produktion

### 4.4 WebGUI Run-Sicherheit

- **WebGuiRunSlot:** Atomare Run-Reservierung via `AtomicBoolean.compareAndSet` — nur ein Run gleichzeitig
- **RunConfigSnapshot:** Owner-only (0600) Snapshots; validiert Run-ID, verweigert Symlinks, verhindert Überschreiben
- **AuthHandler Fail-Closed:** Fehlende/ungültige Auth-Werte brechen vor Port-Bind ab; kein temporäres Passwort, keine Secrets in Fehlertexten
- **Rate-Limiting:** 5 Fehlversuche → 5 Minuten Sperre pro Client-IP
- **Standardbindung:** `127.0.0.1`; `--bind-all` nur mit explizitem JVM-Flag

### 4.5 Worker-Fehlerweiterleitung

`WorkerFailureState` + `ShutdownCoordinator` ersetzen die vorherige stille Fehlerunterdrückung:

1. Erster Producer-/Discovery-Fehler → `WorkerFailureState.record()`
2. `ShutdownCoordinator.requestShutdown()` wird gesetzt
3. Producer sendet **keine** normalen Poison-Pills
4. Normale Migrations-Abschlussreports/E-Mail werden **nicht** erzeugt
5. Consumer verlassen Loop über `isShuttingDown()`-Prüfung
6. `WorkerFailureState.throwIfPresent()` propagiert Cause nach Cleanup
7. CLI: `Main.main` → Exit 1; WebGUI: Run-Status `FAILED`

---

## 5. Version History

| Version | Datum | Änderungen |
|---------|-------|------------|
| **v2.2.1** | Juli 2026 | **Sicherheitsarchitektur:** `OperationalPolicy` (Cascade-Delete-Containment vor Main/Verifier), `SourceLookupClassifier` (fail-closed Tri-State), `SourceLookupStatus` (EXISTS/NOT_FOUND/ERROR), `CliShutdownLifecycle`/`CliLifecycleRunner` (begrenzter CLI-Lifecycle), `RunTerminationException` (strukturierter Exit), `ShutdownCoordinator` (globale kooperative Shutdown-Anforderung), `WorkerFailureState`/`WorkerTermination` (Fehlerweiterleitung + Zwei-Phasen-Terminierung), `WebGuiRunSlot` (atomare Run-Reservierung), `RunConfigSnapshot` (sichere Web-Config-Snapshots), `AuthHandler` (Basic Auth + Rate-Limiting, fail-closed). **Neue Klassen:** `ConfigAutoDetector`, `ConsoleUI`, `SdkCapabilityProbe`, `PermanentMigrationException`, `MigrationMetricsMBean`, `ProtocolData`, `ProtocolReportGenerator`, `WebServer`. **Legacy/Diagnose:** `SDKTest`, `StandaloneParserTest` dokumentiert. **Shell-Skripte:** `cm-run.sh` (Operator-Wrapper), `webgui.sh`, `cascade-delete-guard.sh`, `build-release.sh`, `deploy-source.sh` |
| **v2.1.31** | Jan 2026 | Producer Poison-Pill Fix (N Pills für N Consumer), MigrationJournal LRU-Cache (bounded, 5M), Shell-Skript Hardening (Fehlerbehandlung, JVM-Alignment), Consumer Cleanup |
| **v1.30** | — | ResourceGuardian (Temp-File Cleanup), MigrationMetrics (JMX), iteratives Batch-Splitting, Memory Monitoring |
| **v1.28** | — | Diamond Profile Engine (KLEIN–ULTI), Fuzzy Key Matching |
| **v1.26** | — | Adaptive Batch-Größe, Consumer-Doublecheck |

---

**Ende der Architektur-Dokumentation**
