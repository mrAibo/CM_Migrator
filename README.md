# CM Migrator

Java-based migration and verification tooling for IBM Content Manager 8.7.

## Deployment auf der Zielmaschine

1. Projekt auf die Zielmaschine kopieren und entpacken:

   ```bash
   mkdir -p /opt/cm-migrator
   cd /opt/cm-migrator
   # Projektinhalt hier entpacken oder aus einem internen Git-Remote beziehen.
   ```

2. Java 11 oder neuer bereitstellen:

   ```bash
   java -version
   javac -version
   ```

3. Lokale Konfigurationen aus den Vorlagen erstellen:

   ```bash
   cp conf/migration.properties.example conf/migration.properties
   cp conf/webgui.properties.example conf/webgui.properties
   cp conf/cmbicmenv.ini.example conf/cmbicmenv.ini
   chmod 600 conf/migration.properties conf/webgui.properties conf/cmbicmenv.ini
   ```

   Die lokalen Dateien sind absichtlich durch `.gitignore` ausgeschlossen. Passwörter, reversible IBM-CM-Credentialwerte, Keystores und produktive Konfigurationen dürfen nicht committed werden.

4. Konfigurationen lokal anpassen. Für die WebGUI wird vorzugsweise eine Umgebungsvariable verwendet:

   ```bash
   export WEBGUI_ADMIN_PASSWORD='use-a-long-random-password'
   ```

5. Kompilieren:

   ```bash
   ./bin/compile.sh
   ```

6. Den empfohlenen Operator-Wrapper verwenden:

   ```bash
   ./bin/cm-run.sh safe conf/migration.properties
   ```

   Einzelne Komponenten können weiterhin direkt gestartet werden:

   ```bash
   ./bin/start.sh conf/migration.properties
   ./bin/verify.sh conf/migration.properties
   ./bin/webgui.sh
   ```

## Sicherheit

- Die WebGUI standardmäßig nur auf `127.0.0.1` betreiben und über einen SSH-Tunnel öffnen.
- Die Legacy-Passwortkodierung ist reversibel und keine Verschlüsselung.
- Generierte Reports, Logs, H2-Journale und WebGUI-Run-Snapshots können sensible Betriebsdaten enthalten und gehören nicht ins Repository.
- Bereits veröffentlichte Zugangsdaten müssen rotiert und zusätzlich aus der Git-Historie entfernt werden.
- Weitere Hinweise stehen in [SECURITY.md](SECURITY.md).

## Projektinhalt

- `src/` – Java-Quellcode
- `lib/` – IBM-CM-SDK und weitere Laufzeitbibliotheken
- `bin/` – Build-, Start- und Operator-Skripte
- `conf/` – ausschließlich versionierbare Vorlagen und nicht geheime Konfiguration
- `webapp/` – WebGUI
- `reports/templates/` – wiederverwendbare Reportvorlagen
- `docs` und `*.md` – technische und betriebliche Dokumentation

## Hinweise

- Java wird nicht mitgeliefert.
- Produktive Konfigurationsdateien müssen außerhalb der Versionsverwaltung gesichert werden.
- Vor dem Merge eines Security-Branches müssen alle betroffenen Zugangsdaten unabhängig von der Git-Bereinigung rotiert werden.
