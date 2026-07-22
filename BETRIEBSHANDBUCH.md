# Betriebshandbuch: IBM CM Migrator - Betriebshandbuch v2.2 (Diamond Grade Enterprise)
*Letzte Aktualisierung: 11. Januar 2026 (v2.2 WebGUI + Auto-Detection)*

> **Weiterführende Dokumentation:**
> - [ARCHITEKTUR.md](ARCHITEKTUR.md) - Technische Architektur und Quellcode-Dokumentation
> - [ANALYSE_BERICHT.md](ANALYSE_BERICHT.md) - Analyse-Findings und Optimierungsempfehlungen

Dieses Handbuch dokumentiert Installation, Konfiguration, Betrieb und Troubleshooting des CM Migrator.

---

## 1. Technologie-Stack

| Komponente | Technologie | Beschreibung |
| :--- | :--- | :--- |
| **Sprache** | Java 11 (LTS) | Modernes JDK mit NIO und HTTP/2 |
| **Journal-DB** | H2 Database (Embedded) | Lokale Datei im `data/` Ordner. Keine Installation nötig. |
| **IBM API** | DK SDK v8.7 | High-Performance Native SDK Bindings |
| **Logging** | Log4j 2.x | Diamond Grade: Atomic Traceability (Log4j MDC) |
| **I/O** | Single-Pass SHA-256 | Ultra-I/O: Hashing während des Streamings |

---

## 2. Verzeichnisstruktur

Nach der Installation unter `/opt/cm-migrator`:

```text
/opt/cm-migrator/
├── bin/                    # Skripte
│   ├── cm-migrator.jar     # Kompilierte Anwendung
│   ├── compile.sh          # Build
│   ├── start.sh            # Migration starten
│   ├── verify.sh           # Verifikation starten
│   └── monitor.sh          # Web-Server für Dashboard
├── conf/
│   ├── migration.properties # Hauptkonfiguration
│   └── log4j2.xml          # Logging-Konfiguration
├── data/                   # Journal-Datenbanken (auto-erstellt)
│   └── journal_*.mv.db
├── lib/                    # Externe Bibliotheken
│   ├── h2-2.2.224.jar
│   ├── log4j-core.jar
│   └── ...
└── src/                    # Quellcode
```

---

## 3. Konfiguration (Vollständige Referenz)

Bearbeite `conf/migration.properties`.

### 3.1 Verbindung & Authentifizierung

| Parameter | Pflicht | Beschreibung | Beispiel |
| :--- | :--- | :--- | :--- |
| `SOURCE_SSID` | Ja | Quell-Datenbank (Library Server Name) | `ICMNLSDB` |
| `DEST_SSID` | Bei MIGRATE | Ziel-Datenbank | `ICMNLSDB2` |
| `CONNECT_USER` | Ja | Admin-User für beide Systeme | `icmadmin` |
| `CONNECT_PASSWORD_CRYPT` | (Ja) | Verschlüsseltes Passwort (empfohlen) | `VGVzdA==` |
| `CONNECT_PASSWORD` | (Ja) | Klartext-Passwort (nur Test!) | `geheim123` |
| `DEST_USER` | Nein | Override: Eigener User für Ziel | `dest_admin` |
| `PROFILE` | Nein | v1.28 Diamond Profile Engine (siehe 3.3) | `EXTREM` |

**Passwort verschlüsseln:**
```bash
java -cp bin/cm-migrator.jar:lib/* com.ibm.ecm.migration.MigrationConfig "MeinGeheimesPW"
# Ausgabe: ZWluR2VoZWltPT0=
```

### 3.2 Prozess-Steuerung

| Parameter | Beschreibung | Default |
| :--- | :--- | :--- |
| `OPERATION_MODE` | `MIGRATE` (Kopieren) oder `DELETE` (Löschen) | `MIGRATE` |
| `MIGRATE_ITEMTYPES` | Komma-Liste. Mapping: `Alt:Neu`. | (leer = alle) |
| `FILTER_PREDICATE` | XQPE Filter. Z.B. `[@Status = "Aktiv"]` | (leer = alle) |
| `DRY_RUN` | `true` = Simulation. `false` = Ausführen. | `false` |
| `PRODUCER_COUNT_STRATEGY`| v1.27 Strategy (HYBRID, COUNT_SQL, SDK_CURSOR) | `HYBRID` |
| `QUEUE_SIZE` | Größe der Transfer-Queue | `10.000` |

### 3.3 Diamond Profile Engine (v1.28)
Anstatt alle Parameter manuell zu setzen, bietet der Migrator v1.28 "Smart Defaults" über das `PROFILE` Attribut.

| Profil | Ziel-Szenario | THREADS | BATCH | QUEUE | LOG | Strategy |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **KLEIN** | <10M Items | 5 | 50 | 1k | Sparse: Aus | HYBRID |
| **MITTEL** | 10-100M Items | 20 | 200 | 5k | Sparse: An | HYBRID |
| **GROSS** | 100-500M Items | 50 | 500 | 10k | Sparse: An | HYBRID |
| **EXTREM** | 500M+ Items | 100 | 1.000 | 20k | Sparse: An | COUNT_SQL |
| **ULTI** | 1.000M+ Items | 200 | 2.000 | 50k | Sparse: An | COUNT_SQL |

**HINWEIS:** Manuelle Einträge in `migration.properties` überschreiben die Profil-Werte immer. So kannst du z.B. `PROFILE=EXTREM` setzen, aber `THREAD_COUNT=80` manuell korrigieren.

### 3.4 Performance-Tuning (Manuelle Parameter)

| Parameter | Beschreibung | Default | Max |
| :--- | :--- | :--- | :--- |
| `THREAD_COUNT` | Anzahl paralleler Worker (Consumer) | `10` | `200` |
| `BATCH_SIZE` | Items pro Commit/Transaktion | `100` | `10.000` |
| `QUEUE_SIZE` | Größe der internen Transfer-Queue | `10.000` | `1.000.000` |
| `SOURCE_POOL_SIZE` | Connection Pool Quelle | `THREAD_COUNT + 1` | 500 |
| `DEST_POOL_SIZE` | Connection Pool Ziel | `THREAD_COUNT` | 500 |
| `PRODUCER_COUNT_STRATEGY`| v1.27 Strategy (HYBRID, COUNT_SQL, SDK_CURSOR) | `HYBRID` | - |
| `DB_URL_APPEND` | v1.28 H2 Tuning (z.B. `;LOG=0;CACHE_SIZE=64k`) | (leer) | - |
| `LOG_ITEMS_BATCHED` | v1.28 Sparse Logging (Nur alle X Items loggen) | `false` | - |
| `LOG_BATCH_INTERVAL` | v1.28 Intervall für Sparse Logging | `10.000` | - |
| `LOG_ERRORS_IMMEDIATE` | v1.28 Fehler trotz Batch-Logging sofort anzeigen | `true` | - |
| `POOL_BORROW_TIMEOUT` | v1.28 Max. Wartezeit auf freie Connection (ms) | `5000` | - |
| `POOL_MAX_WAIT_TIME` | v1.28 Max. Gesamt-Wartezeit bei Retry (ms) | `10.000` | - |
| `CONSUMER_DOUBLECHECK` | Doppelte Prüfung (Idempotenz-Overhead) | `false` | - |
| `CASCADE_DELETE_ON_MISSING` | Kaskaden-Löschung bei Verifikation | `false` | - |
| `GENERATE_AUDIT_PROTOCOL` | Erstellung HTML Prüfprotokoll | `true` | - |
| `AUDIT_PROTOCOL_OUTPUT_DIR` | Pfad für Prüfprotokolle | `./reports` | - |

### 3.4 E-Mail & Reporting

| Parameter | Beschreibung | Beispiel |
| :--- | :--- | :--- | :--- |
| `EMAIL_TO` | Empfänger für Report-Mails (komma-getrennt) | `admin@firma.de,team@firma.de` |
| `DB_PATH` | Pfad zur H2-Datenbank (ohne `.mv.db`) | `./data/migration_journal` | - |

**E-Mail-Voraussetzung:** `mutt` oder `mailx` muss auf dem Server installiert sein.

---

## 4. Betrieb

### 4.1 Migration Starten

```bash
./bin/start.sh
```
Der Prozess läuft im Vordergrund. Für Hintergrund: `nohup ./bin/start.sh &`

### 4.2 Überwachung (3 Methoden)

1. **Live-Dashboard:**
    ```bash
    ./bin/monitor.sh
    ```
    Öffne `http://<server-ip>:8000/status.html` im Browser.
    
2. **Logfile:**
    ```bash
    tail -f migration.log | grep -E "PROGRESS|ERROR"
    ```

3. **E-Mail:**
    Konfiguriere `EMAIL_TO`. Du erhältst einen Report am Ende.

### 4.3 Stoppen & Fortsetzen

Drücke `Ctrl+C`. Das Tool speichert den Fortschritt im H2-Journal. Beim nächsten Start werden bereits migrierte Items übersprungen.

### 4.4 Reset (Alles neu)

Lösche die Journal-Dateien:
```bash
rm data/journal_*.mv.db
```

---

## 5. H2-Datenbank Inspektion

Du kannst den Status direkt in der Datenbank prüfen.

### 5.1 Zugriff via SQL-Shell

1. **Stoppe den Migrator!** (H2 erlaubt nur einen Prozess)
2.  Starte die Shell:
    ```bash
    java -cp lib/h2-2.2.224.jar org.h2.tools.Shell \
      -url "jdbc:h2:./data/journal_Rechnung" \
      -user "sa" -password ""
    ```

### 5.2 SQL-Beispiele

**Status-Übersicht:**
```sql
SELECT STATUS, COUNT(*) FROM AUDIT_LOG GROUP BY STATUS;
```

**Fehler analysieren:**
```sql
SELECT ITEM_ID, MESSAGE FROM AUDIT_LOG WHERE STATUS='FAILED' LIMIT 20;
```

**Item zurücksetzen (Neu-Versuch):**
```sql
DELETE FROM AUDIT_LOG WHERE ITEM_ID='A1001001A...';
```

---

## 6. Verifikation

Der Verifier ist ein separater Prozess. Er prüft die Integrität der migrierten Daten.

### 6.1 Starten

```bash
./bin/verify.sh
```

### 6.2 Ablauf

1.  Liest alle `SUCCESS` Einträge aus dem Journal.
2.  **Nutzt den gespeicherten SHA-256 Hash aus der Spalte `CHECKSUM`.** Kein erneuter Download von der Quelle!
3.  Vergleicht Dateigrößen (Metadaten). Ungleich = sofort FAILED.
4.  Lädt die Datei vom **Ziel** und berechnet dessen SHA-256.
5.  Vergleicht: Gespeicherter Hash == Ziel-Hash.
6.  Speichert Ergebnis in `VERIFICATION_LOG`.

### 6.3 Report

Öffne `verification_report.html` im Browser.
*   **OK:** Hash identisch.
*   **MISMATCH:** Daten verändert (Alarmstufe Rot!).
*   **MISSING:** Item im Ziel nicht gefunden (Alarmstufe Rot!).

### 6.4 Kaskaden-Löschung (v1.25)

Wenn ein Item im Quellsystem gelöscht wurde, kann der Migrator dies während der Verifikation erkennen und das entsprechende Objekt im Zielsystem ebenfalls entfernen.

*   **Voraussetzung:** `CASCADE_DELETE_ON_MISSING=true`
*   **Logik:** Wenn die Source-Existenzprüfung fehlschlägt, wird das Ziel-Objekt gelöscht.
*   **Journal-Status:** Gelöschte Items erscheinen als `CASCADE_DELETED`. Ohne Config-Flag als `ORPHANED`.

### 6.5 Offizielle Prüfprotokoll (v1.25)

Nach Abschluss der Verifikation wird pro ItemType ein formelles Prüfprotokoll generiert.

*   **Dateiname:** `reports/PRUEFPROTOKOLL_{ItemType}_{Datum}.html`
*   **Layout:** Optimiert für A4-Druck.
*   **Inhalt:**
    *   Statistiken zu Migration & Verifikation.
    *   Liste der ersten 50 Fehler/Missing Files.
    *   **Unterschriftenfelder:** Vorgesehen für die manuelle Abnahme durch den Prüfer/Admin.

*   **Gesamtprüfprotokoll (Master Audit Protocol):** Zusätzlich wird eine Datei `reports/PRUEFPROTOKOLL_GESAMT_{Datum}.html` erstellt, die eine aggregierte Übersicht über alle ItemTypes sowie ein finales Abnahmefeld enthält.

### 6.6 Auto-Remigration (v2.1.31 NEU!)

Ab Version 2.1.31 markiert der Verifier bei MISMATCH automatisch Items zur Re-Migration. Dies vereinfacht den Workflow erheblich.

**Konfiguration:**
```properties
# Default: true (aktiviert)
AUTO_MARK_FOR_REMIGRATION=true
```

**Neuer vereinfachter Workflow:**
```
1. ./bin/start.sh    # Migration durchführen
2. ./bin/verify.sh   # Verifikation + automatische Markierung bei Fehlern
3. ./bin/start.sh    # Re-Migration der fehlgeschlagenen Items (automatisch!)
```

**Alter Workflow (vor v2.1.31):**
```
1. ./bin/start.sh
2. ./bin/verify.sh
3. ./bin/remigrate.sh verification_errors.log  # <-- Entfällt jetzt!
4. ./bin/start.sh
```

**Hinweis:** Das Skript `./bin/remigrate.sh` ist ab v2.1.31 **DEPRECATED**. Es kann weiterhin manuell verwendet werden, falls benötigt.

**So funktioniert es:**
1. Verifier findet MISMATCH zwischen Source-Checksum und Dest-Checksum
2. Verifier schreibt in VERIFICATION_LOG mit Status "MISMATCH"
3. **NEU:** Verifier setzt auch AUDIT_LOG.STATUS auf "FAILED"
4. Beim nächsten `start.sh` sieht Producer: STATUS=FAILED → Item wird erneut migriert

---

### 6.7 Technische Hinweise: Diamond Grade Architektur

Der CM Migrator v2.1 setzt neue Standards in der industriellen Robustheit:

**1. Ultra-Robust Resource Cleanup (safeDeleteTempFile):**
*   Jede temporäre Datei wird über einen spezialisierten Helfer gelöscht.
*   **Defensive Checks:** Pfade und Größen werden fehler-tolerant ermittelt.
*   **Failover-Deletion:** Schlägt ein Löschversuch (z.B. wegen Dateisperren) fehl, wird die Datei präzise für `deleteOnExit()` registriert. Dies verhindert "Disk Leakage" in Hochlast-Szenarien.

**2. Atomic Traceability (ThreadContext):**
*   Nutzt Log4j 2 MDC, um jede Logzeile (auch SDK-interne) mit der `sourcePid` und `destPid` zu verknüpfen.
*   **Kontext-Hygiene:** Ein striktes per-Item Cleanup verhindert das "Leaking" von IDs zwischen verschiedenen Objekten im selben Worker-Thread.

**3. Native Memory Management (IBM SDK v8.7):**
*   Da SDK v8.7 keine explizite `destroy()` Methode für `DKDDO` Objekte besitzt, nutzt der Migrator einen **Best-Effort Lifecycle**:
    *   SDK-Objekte werden im `finally`-Block sofort auf `null` gesetzt (GC-Signal).
    *   Verbindungen werden im `CMConnectionPool` aktiv rotiert (`MAX_USAGE_COUNT`), um nativen Heap-Speicher durch regelmäßige Disconnects freizugeben.
    *   **v1.26 (Stability & Security Patch)**
        *   **Ressourcen:** Explizites Nulling von `DKDDO` Objekten zur nativen Speicher-Bereinigung.
        *   **Concurrency:** Non-blocking Connection-Pool mit Emergency-Fallback zur Deadlock-Prävention.
        *   **Isolierung:** SSID Deep-Validation verhindert Cross-Environment Leaks.
        *   **Resilienz:** Adaptive Batch-Größe bei hoher Fehlerrate und robustes Item-Counting.
        *   **Sicherheit:** ItemType-Validierung (Regex) und XSS-Audit für Dashboards.
        *   **Forensik:** Shutdown-Hooks für sauberen Programmabbruch und batch-optimiertes Remigrations-Tool.
    *   **v1.27 (Performance Patch)**
        *   **Discovery:** Umstellung auf Single-Pass Discovery.
        *   **SQL-Count:** Einführung von Fast SQL-based Count für PASS 1 (Überspringen des SDK-Fetchings beim Zählen).
        *   **Native Access:** Sicherer Zugriff auf native JDBC-Connections via Reflection.
    *   **v1.28 (Enterprise Scaling)**
        *   **Sparse Logging:** Einführung von `LOG_ITEMS_BATCHED` zur Reduktion von Log-Speicherplatz und I/O-Last bei 500M+ Items.
        *   **H2 Tuning:** Dynamische JDBC-Parameter (`DB_URL_APPEND`) zur Optimierung der Journal-Datenbanken (z.B. Deaktivierung des H2-Transaction-Logs für Speed).
        *   **Pool Scaling:** Standard-Pool-Limit auf 100 erhöht für extreme Parallelität.

**4. Concurrent Safety (Anti-Deadlock Pool):**
*   Das Beziehen von Verbindungen (`borrowSource`/`borrowDest`) wurde von blockierendem `take()` auf zeitgesteuertes `poll()` (5s) umgestellt.
*   **Emergency Fallback**: Falls der Pool leer bleibt (z.B. während Massen-Reconnection), erstellt der Worker-Thread synchron eine Extra-Verbindung, anstatt unendlich zu hängen.

**5. Forensische Härten (v1.25+):**
*   **Schema Detection:** Dynamische Erkennung von H2-Tabellen über `DatabaseMetaData` API (unterstützt新旧 Schema).
*   **Batch-Updates:** Journal-Updates werden in Batches von 1000 Einträgen durchgeführt, um I/O-Overhead zu minimieren.
*   **Shutdown-Hooks:** Sichere Ressourcen-Freigabe bei SIGTERM/INT über Hook-Threads.
*   **Audit-Protokolle:** Formelle Prüfprotokolle mit digitaler Unterschrift für Compliance.

---

## 7. E-Mail-Reports

Das Tool sendet automatisch einen HTML-Report per E-Mail.

### Konfiguration

Füge in `migration.properties` hinzu:
```properties
EMAIL_TO=admin@firma.de
```

### Was wird gesendet?

*   **Zeitpunkt:** Am Ende des Laufs (Migration, Verifikation, Löschung).
*   **Format:** HTML-Datei als E-Mail-Body.
*   **Betreffzeile:** `[OK] MIGRATION: 1234 Items in 2h 30m (99.5% OK, 5 Fehler)`

### Voraussetzungen

*   `mutt` (bevorzugt) oder `mailx` muss installiert sein.
*   Der Server muss E-Mails versenden können (lokaler MTA oder Relay).

---

## 8. DELETE Mode (Löschen)

Das Tool kann Daten im Quellsystem massenhaft löschen.

### Ablauf

1.  Konfiguration erstellen:
    ```properties
    OPERATION_MODE=DELETE
    MIGRATE_ITEMTYPES=Alt_Dokumente
    FILTER_PREDICATE=[@CreationTime < "2015-01-01"]
    DRY_RUN=true
    ```
2.  **Simulation:**
    ```bash
    ./bin/start.sh
    ```
    Prüfe Log: `[DRY-RUN] Would DELETE ...`

3.  **Scharf schalten:**
    `DRY_RUN=false`. Starte erneut.

---

## 9. Troubleshooting

### Häufige Fehler

| Meldung | Lösung |
| :--- | :--- |
| `DKGL0300A: Library not found` | Prüfe `LD_LIBRARY_PATH` in `start.sh`. IBM SDK Pfad korrekt? |
| `SQLRecoverableException` | Netzwerkabbruch. Tool reconnectet automatisch. |
| `OutOfMemoryError` | Erhöhe `-Xmx` in `start.sh` (z.B. `-Xmx4g`). |
| `DGL7333A: ItemType not found` | Prüfe `MIGRATE_ITEMTYPES`. Name exakt wie im CM-System? |
| `Email konnte nicht gesendet werden` | Prüfe, ob `mutt` oder `mailx` installiert ist. |

### Performance-Probleme

*   **CPU < 80%:** Erhöhe `THREAD_COUNT`.
*   **Viele Timeouts:** Reduziere `THREAD_COUNT` oder erhöhe `POOL_BORROW_TIMEOUT`.
*   **H2 Locks:** Stelle sicher, dass nur ein Prozess läuft.

### 9.2 JVM-Tuning für 500M+ Szenarien
Für extrem große Migrationen wird folgende JVM-Konfiguration empfohlen:
```bash
# In start.sh oder Umgebungsvariable setzen
JVM_OPTS="-Xmx32g -XX:+UseG1GC -XX:+UseStringDeduplication -XX:+OptimizeStringConcat"
```
*   **-Xmx32g**: 32GB Heap (bei 500M Items empfohlen).
*   **-XX:+UseG1GC**: G1 Garbage Collector für niedrige Pausenzeiten.
*   **-XX:+UseStringDeduplication**: Spart massiv Speicher bei vielen identischen Attributnamen/Werten.

---

## 10. WebGUI (v2.2 NEU!)

Ab Version 2.2 bietet der CM Migrator eine optionale Web-Oberfläche zur Konfiguration und Überwachung.

### 10.1 WebGUI starten

```bash
./bin/webgui.sh                  # Standard-Port 8080
./bin/webgui.sh --port 9000      # Alternativer Port
```

**Ausgabe bei erfolgreichem Start:**
```
🌐 WebGUI available at:
   • Local:   http://localhost:8080
   • Network: http://192.168.1.100:8080
```

### 10.2 Funktionen der WebGUI

| Funktion | Beschreibung |
|----------|--------------|
| **Konfiguration** | Visuelle Bearbeitung der migration.properties |
| **Source/Dest User** | Separate Benutzer für Quell- und Zielsystem |
| **FILTER_PREDICATE** | SQL WHERE-Klausel für selektive Migration |
| **Import/Export** | Konfiguration als .properties Datei laden/speichern |
| **Live-Dashboard** | Fortschritt, Speed, ETA in Echtzeit |
| **Benchmark** | CPU, RAM, I/O und Netzwerk-Performance messen |
| **Profil-Empfehlung** | Automatische Empfehlung basierend auf Benchmark |
| **Delete & Verify** | Löschung und Verifikation über WebGUI |

### 10.3 FILTER_PREDICATE (SQL WHERE-Klausel)

Die WebGUI unterstützt direkte SQL-Filter zur selektiven Migration:

**Beispiele:**
```sql
-- Nur Items ab 2020
Erstelldatum > '2020-01-01'

-- Nur bestimmte Status
Status = 'AKTIV'

-- Kombiniert
Erstelldatum > '2020-01-01' AND Status = 'AKTIV'

-- Mit LIKE
Dokumentname LIKE 'Rechnung%'
```

**Felder in der WebGUI:**
- `FILTER_PREDICATE` - SQL WHERE-Ausdruck (optional)
- `MIGRATE_ITEMTYPES` - ItemType-Mapping (z.B. `Rechnung:Rechnung_V2`)

### 10.4 Import/Export der Konfiguration

**Export:**
```bash
1. In WebGUI klicken: "📤 Export"
2. Dateinamen eingeben (Default: migration_2026-01-12T15-06.properties)
3. Download startet automatisch
```

**Sicherheits-Hinweis:** Passwörter werden aus Sicherheitsgründen NICHT exportiert!

**Import:**
```bash
1. In WebGUI klicken: "📥 Import"
2. .properties Datei auswählen
3. Formular wird automatisch ausgefüllt
4. Mit "✓ Apply" auf Server speichern
```

### 10.5 Konfigurations-Buttons

| Button | Funktion |
|--------|----------|
| **✓ Apply** | Speichert Konfig auf Server (`conf/migration.properties`) |
| **📤 Export** | Exportiert Konfig als .properties Datei (mit Dateinamen-Abfrage) |
| **📥 Import** | Importiert .properties Datei und füllt Formular aus |

### 10.6 REST API Endpoints

| Endpoint | Methode | Beschreibung |
|----------|---------|--------------|
| `/api/config` | GET | Aktuelle Konfiguration abrufen |
| `/api/config` | POST | Konfiguration speichern |
| `/api/benchmark` | POST | Benchmark starten |
| `/api/benchmark/status` | GET | Benchmark-Ergebnisse abrufen |
| `/api/migration/start` | POST | Migration starten |
| `/api/migration/stop` | POST | Migration stoppen |
| `/api/migration/status` | GET | Aktueller Migrations-Status |
| `/api/delete/start` | POST | Löschung im Quellsystem starten (mit Warnung) |
| `/api/verify/start` | POST | Verifikation starten |
| `/api/itemtypes` | GET | Verfügbare ItemTypes |

### 10.7 Separate Benutzer für Source/Destination

Die WebGUI unterstützt unterschiedliche Credentials:

| Feld | Properties-Key | Beschreibung |
|------|----------------|--------------|
| SOURCE_USER | `SOURCE_USER` | Benutzer für Quellsystem |
| SOURCE_PASSWORD | `SOURCE_PASSWORD` | Passwort Quellsystem |
| DEST_USER | `DEST_USER` | Benutzer für Zielsystem (optional) |
| DEST_PASSWORD | `DEST_PASSWORD` | Passwort Zielsystem (optional) |

**Beispiel-Konfiguration:**
```properties
SOURCE_SSID=ICMNLSDB_PROD
SOURCE_USER=source_readonly
SOURCE_PASSWORD_CRYPT=...

DEST_SSID=ICMNLSDB_NEW
DEST_USER=dest_admin
DEST_PASSWORD_CRYPT=...
```

### 10.8 WebGUI Authentifizierung (v2.3 NEU!)

Ab Version 2.3 ist das WebGUI durch HTTP Basic Authentication geschützt. Nur autorisierte Administratoren können auf das Dashboard und kritische Funktionen zugreifen.

**Öffentliche Endpunkte (KEIN Login erforderlich):**
- `/api/status` - Lightweight Monitoring-Endpunkt
- `/api/health` - Health-Check für Load Balancer

**Geschützte Endpunkte (Login erforderlich):**
- `/` - Dashboard (index.html)
- `/api/config` - Konfiguration lesen/schreiben
- `/api/benchmark` - Benchmark starten
- `/api/migration/*` - Migration steuern
- `/api/delete/*` - Löschoperationen
- `/api/verify/*` - Verifikation

#### Konfiguration

**Option 1: In migration.properties (empfohlen)**
```properties
# Auth aktivieren (Standard: true)
webgui.auth.enabled=true

# Admin-Credentials
webgui.admin.user=admin
webgui.admin.password=MeinSicheresPasswort
```

**Option 2: Mit Passwort-Hash (sicherer)**
```bash
# Hash generieren
java -cp bin/cm-migrator.jar com.ibm.ecm.migration.AuthHandler "MeinPasswort"
# Ausgabe: a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e
```
```properties
webgui.admin.user=admin
webgui.admin.password.hash=a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e
```

**Option 3: Umgebungsvariablen**
```bash
export WEBGUI_ADMIN_USER=admin
export WEBGUI_ADMIN_PASSWORD=MeinSicheresPasswort
./bin/webgui.sh
```

#### Sicherheitsfunktionen

| Feature | Beschreibung |
|---------|-------------|
| **Rate-Limiting** | Nach 5 Fehlversuchen wird die IP für 5 Minuten gesperrt |
| **SHA-256 Hashing** | Passwörter werden nie im Klartext verglichen |
| **Timing-Attack Schutz** | Konstante Vergleichszeit verhindert Analyse |
| **Audit-Logging** | Alle Login-Versuche werden geloggt |
| **X-Forwarded-For** | Unterstützung für Reverse-Proxies |

#### Authentifizierung deaktivieren (nur für Tests!)

```properties
webgui.auth.enabled=false
```

⚠️ **WARNUNG:** Deaktivieren Sie die Authentifizierung niemals in Produktionsumgebungen!

#### Monitoring ohne Login

Externe Monitoring-Systeme können weiterhin den Status abrufen:

```bash
# Health Check
curl http://server:8080/api/health
# {"status":"healthy","version":"2.3.0","timestamp":1736776800000}

# Migration Status (öffentlich)
curl http://server:8080/api/status
# {"migrationRunning":true,"percent":45.2,"processed":452000,"total":1000000}
```

### 10.9 Auto-Detection & Benchmark

Der ConfigAutoDetector analysiert:

1. **System-Ressourcen:** CPU-Cores, JVM-Heap, RAM
2. **I/O-Performance:** Disk Read/Write, H2 Insert-Speed
3. **Netzwerk:** Latenz und Throughput zu Source/Destination
4. **Bottleneck-Erkennung:** Identifiziert den limitierenden Faktor

**Benchmark-Ergebnis-Beispiel:**
```
System: 8 cores, 16384 MB heap
IOBenchmark: diskWrite=250.5MB/s, diskRead=480.2MB/s, h2Insert=45000 rows/s
NetworkBenchmark[ICMNLSDB]: latency=45.2ms, throughput=28.5MB/s
Recommended profile: GROSS (based on 8 cores, 16384 MB RAM)
Bottleneck: BALANCED
```

---

## 11. Release-Build (build-release.sh)

Für die Erstellung eines deploy-fertigen Release-Pakets steht `build-release.sh` zur Verfügung.

### 11.1 Verwendung

```bash
cd /opt/cm-migrator
./bin/build-release.sh [OPTIONS]
```

### 11.2 Optionen

| Option | Beschreibung |
|--------|-------------|
| `--obfuscate` | Obfuskiere JAR mit ProGuard (erhöht Schutz) |
| `--sign` | Signiere JAR mit digitaler Signatur |
| `--native` | Erstelle GraalVM Native Image (höchster Schutz) |
| `--all` | Aktiviere alle Schutzmaßnahmen (--obfuscate --sign) |
| `--version VER` | Setze Version (default: 2.2.0) |
| `--help` | Zeige Hilfe an |

### 11.3 Beispiele

```bash
# Basis-Release (nur kompilierter JAR)
./bin/build-release.sh

# Mit Code-Obfuskierung
./bin/build-release.sh --obfuscate

# Obfuskiert und signiert
./bin/build-release.sh --obfuscate --sign

# Maximaler Schutz
./bin/build-release.sh --all

# Andere Version
./bin/build-release.sh --version 2.3.0
```

### 11.4 Signierung

Das Skript verwendet automatisch das lokale JDK aus `java_env/jdk-11.0.2/`.

**Keystore:**
- Pfad: `tools/cm-migrator.keystore`
- Erstellt automatisch falls nicht vorhanden
- Alias: `cm-migrator`
- Passwort: `changeit` (Standard)

**Manuelles Erstellen eines Keystores:**
```bash
cd /opt/cm-migrator
java_env/jdk-11.0.2/bin/keytool -genkey -alias cm-migrator \
    -keyalg RSA -keysize 2048 -validity 3650 \
    -keystore tools/cm-migrator.keystore \
    -storepass "changeit" \
    -keypass "changeit" \
    -dname "CN=CM Migrator, OU=IT, O=Company, L=City, ST=State, C=DE" \
    -noprompt
```

### 11.5 Release-Paket Struktur

Nach erfolgreichem Build:
```
build/
├── cm-migrator-v2.2.0/
│   ├── bin/
│   │   ├── cm-migrator.jar    # (optional signiert)
│   │   ├── start.sh
│   │   ├── verify.sh
│   │   ├── monitor.sh
│   │   └── webgui.sh
│   ├── lib/
│   │   ├── h2-2.2.224.jar
│   │   ├── log4j-core.jar
│   │   └── ...
│   ├── conf/
│   │   ├── migration.properties.example
│   │   └── log4j2.xml
│   ├── webapp/
│   │   └── index.html
│   ├── data/                    # (leer, wird beim Start erstellt)
│   ├── reports/                 # (leer, wird bei Reports erstellt)
│   ├── README.txt
│   └── VERSION
├── cm-migrator-v2.2.0.tar.gz
├── cm-migrator-v2.2.0.zip
├── cm-migrator-v2.2.0.tar.gz.sha256
└── cm-migrator-v2.2.0.zip.sha256
```

### 11.6 Checksummen

Beide Archive werden mit SHA-256 Checksummen versehen:
```
cm-migrator-v2.2.0.tar.gz.sha256
cm-migrator-v2.2.0.zip.sha256
```

**Verifizieren:**
```bash
sha256sum -c cm-migrator-v2.2.0.tar.gz.sha256
```

---

## 12. Backup & Restore

### Status sichern

```bash
tar -czf migration_backup_$(date +%F).tar.gz /opt/cm-migrator/data
```

### Wiederherstellen

```bash
tar -xzf backups/migration_backup_*.tar.gz -C /opt/cm-migrator
```

---

**Ende des Handbuchs.**
