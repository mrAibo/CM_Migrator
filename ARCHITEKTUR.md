# IBM CM Migrator v2.1.31 - Architektur & Quellcode-Dokumentation

**Letzte Aktualisierung:** 11. Januar 2026  
**Version:** 2.1.31

---

## 1. System-Überblick

Der IBM CM Migrator ist ein hochperformantes, Java 11-basiertes Migrationstool für IBM Content Manager. Es ersetzt das veraltete `icmbatch` und bietet signifikant verbesserte Performance durch Multi-Threading, asynchrones Journaling und intelligente Caching-Mechanismen.

### 1.1 Architektur-Diagramm

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            CM Migrator v2.1.31                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌───────────────┐              ┌────────────────────┐                      │
│  │    Main.java  │──────────────│  MigrationConfig   │                      │
│  │  (Bootstrap)  │              │  (Konfiguration)   │                      │
│  └───────┬───────┘              └────────────────────┘                      │
│          │                                                                   │
│          ▼                                                                   │
│  ┌───────────────┐     ┌──────────────────────┐     ┌───────────────────┐   │
│  │   Producer    │────▶│   BlockingQueue      │────▶│    Consumer(s)    │   │
│  │ (Discovery)   │     │   (MigrationItem)    │     │    (Workers)      │   │
│  │               │     └──────────────────────┘     └─────────┬─────────┘   │
│  │  - ItemType   │                                            │             │
│  │    Discovery  │                                            ▼             │
│  │  - SQL Count  │                               ┌───────────────────────┐  │
│  │  - Filtering  │                               │    ItemMigrator       │  │
│  └───────────────┘                               │  - Attribute Copy     │  │
│                                                  │  - Part Migration     │  │
│                                                  │  - SHA-256 Hashing    │  │
│                                                  └───────────┬───────────┘  │
│                                                              │              │
│  ┌────────────────────────────────────────────────────────────┴────────────┐│
│  │                        CMConnectionPool                                 ││
│  │  ┌─────────────────┐                    ┌─────────────────┐            ││
│  │  │  Source Pool    │                    │   Dest Pool     │            ││
│  │  │  (CMConnection) │                    │  (CMConnection) │            ││
│  │  └─────────────────┘                    └─────────────────┘            ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │                     MigrationJournal (H2 Database)                      ││
│  │  ┌─────────────────┐  ┌────────────────┐  ┌──────────────────────────┐ ││
│  │  │  Async Writer   │  │  LRU Cache     │  │  Per-ItemType DB Files  │ ││
│  │  │  (Background)   │  │  (5M Entries)  │  │  (journal_*.mv.db)      │ ││
│  │  └─────────────────┘  └────────────────┘  └──────────────────────────┘ ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  ┌────────────────┐  ┌────────────────┐  ┌─────────────────┐               │
│  │ProgressMonitor │  │ ReportGenerator│  │  EmailNotifier  │               │
│  │ (Live Status)  │  │ (HTML Reports) │  │  (mutt/mailx)   │               │
│  └────────────────┘  └────────────────┘  └─────────────────┘               │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Quellcode-Dokumentation

### 2.1 Verzeichnisstruktur

```
src/com/ibm/ecm/migration/
├── Main.java                   # Entry Point, Orchestrierung
├── MigrationConfig.java        # Konfigurationsloader mit Profile Engine
├── MigrationItem.java          # Data Transfer Object
├── MigrationStats.java         # Thread-sichere Statistiken
├── MigrationJournal.java       # H2-Persistenz mit LRU-Cache
├── Producer.java               # Item-Discovery & Enqueueing
├── Consumer.java               # Worker-Threads mit Batch-Processing
├── ItemMigrator.java           # Migrations-Logik (Copy & Hash)
├── CMConnection.java           # Einzelne IBM CM Verbindung
├── CMConnectionPool.java       # Connection-Pool-Management
├── Verifier.java               # Post-Migration Integritätsprüfung
├── VerificationLogger.java     # Batch-Logging für Verifikation
├── ProgressMonitor.java        # Live-Dashboard Generator
├── ReportGenerator.java        # HTML-Report-Erstellung
├── EmailNotifier.java          # E-Mail-Versand
├── AuditProtocolGenerator.java # Formelle Prüfprotokolle
├── RemigrationTool.java        # Fehlgeschlagene Items erneut migrieren
├── ResourceGuardian.java       # Temp-File Cleanup (v1.30)
└── MigrationMetrics.java       # JMX Monitoring (v1.30)
```

### 2.2 Komponenten-Beschreibung

---

#### **Main.java** - Bootstrap & Orchestrierung

**Zweck:** Entry Point der Anwendung. Initialisiert alle Komponenten und orchestriert den Migrationsprozess.

**Hauptaufgaben:**
- Laden der Konfiguration (`MigrationConfig`)
- Initialisierung des Journals (`MigrationJournal`)
- Aufbau der Transfer-Queue (`LinkedBlockingQueue`)
- Starten des Connection Pools (`CMConnectionPool`)
- Spawnen von Producer und Consumer Threads
- Graceful Shutdown mit Shutdown-Hook

**Wichtige Methoden:**
```java
public static void main(String[] args)           // Entry Point
private static void setupShutdownHook(...)       // SIGTERM/INT Handling
private static void startResourceMonitor()       // Memory Monitoring (v1.30)
```

---

#### **MigrationConfig.java** - Konfiguration

**Zweck:** Lädt und verwaltet alle Konfigurationsparameter aus `migration.properties`.

**Features:**
- **Diamond Profile Engine (v1.28):** Vordefinierte Lastprofile (KLEIN, MITTEL, GROSS, EXTREM, ULTI)
- Fuzzy Key Matching (MIGRATE_ITEMTYPES = MIGRATEITEMTYPES)
- Passwort-Verschlüsselung (Base64 mit Reverse)
- Separate Credentials für Source und Destination

**Profile-Defaults:**

| Profil | THREAD_COUNT | BATCH_SIZE | QUEUE_SIZE | LOG_BATCH |
|--------|--------------|------------|------------|-----------|
| KLEIN  | 5            | 50         | 1,000      | false     |
| MITTEL | 20           | 200        | 5,000      | true      |
| GROSS  | 50           | 500        | 10,000     | true      |
| EXTREM | 100          | 1,000      | 20,000     | true      |
| ULTI   | 200          | 2,000      | 50,000     | true      |

---

#### **Producer.java** - Item-Discovery

**Zweck:** Entdeckt zu migrierende Items und legt sie in die Queue.

**Workflow:**
1. Parallelisiert Discovery per ItemType (max. 10 Threads)
2. **PASS 1:** Zählt Items (SQL COUNT oder SDK Cursor)
3. **PASS 2:** Enqueued Items, überspringt bereits migrierte
4. Sendet Poison-Pills zum Beenden der Consumer

**v2.1.31 Fix:** Sendet jetzt eine Poison-Pill pro Consumer-Thread (vorher nur eine).

**Discovery-Strategien:**
- `HYBRID` (Default): SQL COUNT mit SDK Fallback
- `COUNT_SQL`: Nur nativer SQL Count
- `SDK_CURSOR`: Nur IBM SDK Counting

---

#### **Consumer.java** - Worker-Threads

**Zweck:** Konsumiert Items aus der Queue und führt Migration durch.

**Features:**
- Batch-Processing mit konfigurierbarer Batch-Größe
- Iteratives Batch-Splitting bei Fehlern (v1.30)
- Exponentielles Backoff bei transienten Fehlern
- Adaptive Batch-Größe basierend auf Erfolgsrate (v1.26)

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

#### **MigrationJournal.java** - Persistenz (v2.1.31)

**Zweck:** Verwaltet den Migrationsstatus in H2-Datenbanken.

**v2.1.31 Verbesserungen:**
- **LRU-bounded Cache:** Max. 5M Einträge (konfigurierbar)
- **Async Journaling:** Schreib-Queue mit Background-Writer
- **Per-ItemType DBs:** Separate Datei pro ItemType

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

**Status-Codes:**
- `OK` - Hashes stimmen überein
- `MISMATCH` - Daten unterschiedlich
- `MISSING` - Item im Ziel nicht gefunden
- `CASCADE_DELETED` - Quelle gelöscht, Ziel auch gelöscht

---

#### **ProgressMonitor.java** - Live-Dashboard

**Zweck:** Generiert `status.html` für Live-Monitoring.

**Features:**
- Auto-Refresh (3 Sekunden)
- Fortschrittsbalken
- ETA-Berechnung
- Items/Sekunde Metrik

---

### 2.3 Shell-Skripte

| Skript | Beschreibung |
|--------|--------------|
| `start.sh` | Startet Migration mit optimierten JVM-Parametern |
| `verify.sh` | Startet Verifikation (v2.1.31: JVM-Params angeglichen) |
| `compile.sh` | Kompiliert Sources und erstellt JAR |
| `monitor.sh` | Startet Python HTTP-Server für Dashboard |
| `remigrate.sh` | Markiert fehlgeschlagene Items für Re-Migration |

**v2.1.31 Verbesserungen:**
- Einheitliche JVM-Parameter in allen Skripten
- Fehlerbehandlung mit `set -e`
- Validierung von JAR/Config vor Start
- Warnungen bei fehlenden Library-Pfaden

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
┌──────────────┐    ┌─────────────┐    ┌─────────────────┐      │
│   Producer   │───▶│    Queue    │───▶│    Consumer     │──────┤
│              │    │             │    │                 │      │
│ 1. Count     │    │ Capacity:   │    │ 1. migrateBatch │      │
│ 2. Enqueue   │    │ 10,000-     │    │ 2. SHA-256 Hash │      │
│ 3. Skip      │    │ 50,000      │    │ 3. Log Journal  │      │
│    migrated  │    │             │    │ 4. Retry/Split  │      │
└──────────────┘    └─────────────┘    └────────┬────────┘      │
                                                │               │
                                                ▼               │
                                       ┌─────────────────┐      │
                                       │ MigrationJournal│      │
                                       │  (H2 + Cache)   │      │
                                       └─────────────────┘      │
                                                │               │
                                                ▼               │
                                       ┌─────────────────┐      │
                                       │    Verifier     │──────┘
                                       │ (Post-Check)    │
                                       └─────────────────┘
```

---

## 4. Konfigurationsreferenz

### 4.1 Verbindungsparameter

```properties
SOURCE_SSID=ICMNLSDB
SOURCE_USER=admin
SOURCE_PASSWORD_CRYPT=encodedPassword

DEST_SSID=ICMNLSDB2  
DEST_USER=admin
DEST_PASSWORD_CRYPT=encodedPassword
```

### 4.2 Performance-Parameter

```properties
# Für 500M+ Items empfohlen:
PROFILE=EXTREM

# Oder manuell:
THREAD_COUNT=50
BATCH_SIZE=500
QUEUE_SIZE=20000
SOURCE_POOL_SIZE=55
DEST_POOL_SIZE=50

# Discovery-Strategie
PRODUCER_COUNT_STRATEGY=COUNT_SQL

# Sparse Logging
LOG_ITEMS_BATCHED=true
LOG_BATCH_INTERVAL=10000

# H2 Tuning
DB_URL_APPEND=;LOG=0;CACHE_SIZE=65536
```

### 4.3 JVM-Parameter (start.sh)

```bash
# Für 500M+ Items:
JAVA_OPTS="-Xms8g -Xmx32g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+UseStringDeduplication \
  -Djava.io.tmpdir=/dev/shm"
```

---

## 5. Version History (v2.1.31)

| Änderung | Beschreibung |
|----------|--------------|
| **Producer Poison-Pill Fix** | Sendet jetzt N Poison-Pills für N Consumer |
| **MigrationJournal LRU-Cache** | Bounded Cache verhindert OutOfMemoryError |
| **Shell-Skript Hardening** | Fehlerbehandlung, Validierung, JVM-Alignment |
| **Consumer Cleanup** | Entfernte Poison-Pill Weiterleitung |

---

**Ende der Architektur-Dokumentation**
