# Benutzeranleitung: CM Migrator auf SLES 15

Diese Anleitung beschreibt die Installation und Ausführung des CM Migrators auf einem SUSE Linux Enterprise Server 15 (SLES 15) ohne Maven.

## 1. Voraussetzungen

Stellen Sie sicher, dass folgende Software auf dem Server installiert ist:

*   **Java 11 JDK** (z.B. `java-11-openjdk-devel`)
*   **IBM Content Manager V8.7 SDK** (Client/Toolkit Installation)
    *   Standardpfad: `/opt/IBM/db2cmv8`
*   **Zugriff auf DB2 (Source) und Oracle (Target)**

## 2. Installation & Setup

### 2.1 Projektdateien kopieren
Kopieren Sie den gesamten Projektordner auf den Server, z.B. nach `/opt/cm-migrator`.

### 2.2 Abhängigkeiten (JARs) bereitstellen
Da kein Maven verwendet wird, müssen die benötigten Bibliotheken manuell in den Ordner `lib/` gelegt werden.

**Erforderliche JARs in `lib/`:**
1.  `h2-2.2.224.jar` (Datenbank für Journal)
2.  `log4j-api-2.20.0.jar` (Logging)
3.  `log4j-core-2.20.0.jar` (Logging)
4.  `commons-cli-1.5.0.jar` (Argument Parsing)
5.  `commons-io-2.11.0.jar` (IO Utils)

*Hinweis: Die IBM CM JARs (`cmbsdk81.jar`, etc.) werden automatisch aus `/opt/IBM/db2cmv8/lib` geladen. Falls diese dort nicht liegen, kopieren Sie sie ebenfalls nach `lib/`.*

### 2.3 Konfiguration anpassen
Bearbeiten Sie die Datei `migrator.properties` und tragen Sie Ihre Verbindungsdaten ein:

```properties
# Source (DB2)
source.cm.database=ICMNLSDB
source.cm.user=icmadmin
source.cm.password=password123

# Target (Oracle)
dest.cm.database=ICMNLSDB_ORA
dest.cm.user=icmadmin
dest.cm.password=password123

# Performance
process.writer.threads=8
```

## 3. Kompilieren (Build)

Führen Sie das Build-Skript aus, um den Java-Code zu kompilieren und das JAR zu erstellen:

```bash
chmod +x build.sh
./build.sh
```

Bei Erfolg erscheint: `Build Complete: cm-migrator.jar`

## 4. Ausführung

Starten Sie die Migration mit dem `run.sh` Skript. Sie müssen den ItemType angeben, der migriert werden soll.

### Syntax
```bash
chmod +x run.sh
./run.sh -i <ItemTypeName> [-c <ConfigPath>]
```

### Beispiele

**Standard-Start:**
```bash
./run.sh -i MyDocumentType
```

**Mit alternativer Config:**
```bash
./run.sh -i MyDocumentType -c production.properties
```

## 5. Überwachung & Troubleshooting

*   **Logs:** Die Anwendung schreibt Logs in die Konsole und (je nach `log4j2.xml`) in eine Log-Datei.
*   **Journal:** Der Status wird in der H2-Datenbank (`migration_journal.mv.db`) gespeichert.
    *   Bei einem Neustart werden bereits migrierte Items (`COMPLETED`) automatisch übersprungen.
    *   Fehlgeschlagene Items (`FAILED`) müssen manuell geprüft oder zurückgesetzt werden (SQL Update auf H2).

### Häufige Fehler
*   **ClassNotFoundException:** Ein JAR fehlt im `lib/` Ordner oder der `CM_SDK_HOME` Pfad in `run.sh` ist falsch.
*   **Connection Refused:** Prüfen Sie die Datenbank-Verbindung und Firewall-Ports (DB2: 50000, Oracle: 1521).
*   **OutOfMemory:** Passen Sie den Heap-Speicher in `run.sh` an (z.B. `java -Xmx4g ...`).
