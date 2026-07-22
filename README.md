# CM Migrator - Source Code (v$VERSION)

## Deployment auf Zielmaschine

1. Projekt auf Zielmaschine kopieren:
   \`\`\`bash
   scp cm-migrator-source-v$VERSION.7z user@zielmaschine:/tmp/
   # Oder lokaler Transfer (USB-Stick, Netzwerk-Freigabe)
   \`\`\`

2. Auf Zielmaschine entpacken:
   \`\`\`bash
   cd /opt/cm-migrator
   7z x /tmp/cm-migrator-source-v$VERSION.7z
   \`\`\`

3. Java Runtime installieren (falls noch nicht vorhanden):
   \`\`\`bash
   # Fedora/RHEL/CentOS:
   sudo dnf install java-11-openjdk-devel
   
   # Debian/Ubuntu:
   sudo apt install openjdk-11-jdk
   \`\`\`

4. Konfiguration erstellen:
   \`\`\`bash
   cp conf/migration.properties.example conf/migration.properties
   # Anpassen (Verbindungsdaten, ItemTypes, etc.)
   \`\`\`

5. Kompilieren:
   \`\`\`bash
   ./bin/compile.sh
   \`\`\`

6. Migration starten:
   \`\`\`bash
   ./bin/start.sh
   # Oder WebGUI:
   ./bin/webgui.sh
   # Browser: http://localhost:8080
   \`\`\`

## Hinweise

- Dieses Paket enthält den Quellcode (.java Dateien), Bibliotheken und Tools
- Java Runtime wird NICHT mitgeliefert (zu groß für Deployment)
- Bitte Java 11+ auf dem Zielsystem installieren vor dem Kompilieren
- Für Entwicklung mit Java IDE einfach das src/ Verzeichnis öffnen
- Für vorkompilierte Builds nutzen Sie ./bin/build-release.sh --all

## Inhalt

- \`src/\` - Java Quellcode
- \`lib/\` - Externe Bibliotheken (IBM CM SDK, H2, Log4j, etc.)
- \`tools/\` - Build-Tools (ProGuard, Keystore)
- \`conf/\` - Konfigurationsdateien
- \`webapp/\` - WebGUI Dashboard
- \`bin/\` - Shell-Skripte (start.sh, webgui.sh, compile.sh, etc.)
- \`*.md\` - Dokumentation (README, BETRIEBSHANDBUCH, etc.)

---
CM Migrator v$VERSION | Erstellt: $(date '+%d.%m.%Y %H:%M')
