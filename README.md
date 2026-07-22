# CM Migrator

CM Migrator ist ein Java-Werkzeug für die Migration und Verifikation von Dokumenten zwischen IBM-Content-Manager-8.7-Systemen. Der aktuelle Stand unterstützt parallele Verarbeitung, H2-basierte Journale zur Wiederaufnahme, HTML-/CSV-Berichte, eine WebGUI und laufende Statusausgaben.

Der Branch `hardening/integration-readiness` baut auf `hardening/security-baseline` auf und ergänzt die lokal lösbaren Readiness-Härtungen: bereinigtes Config-/Runtime-Tracking, fail-closed WebGUI-Auth, einen einbettbaren WebGUI-Delete-Pfad, entfernte einmalige Worker-Patch-Hilfen sowie dependency-freie GitHub-CI. IBM-CM-Live-E2E, Credential-Rotation, Git-History-Purge, produktive Performance-/JNI-Abnahme und die Entscheidung zur 24h-/unbegrenzten-Warte-Policy bleiben offen.

Für Installation, Betrieb, Konfigurationsreferenz, Fehlerbehebung und Wiederanlauf siehe das [Betriebshandbuch](BETRIEBSHANDBUCH.md).

## Funktionsübersicht

Im aktuellen Code vorhanden sind:

- Dokumentmigration zwischen getrennt konfigurierten Source- und Destination-Systemen;
- expliziter Delete-Betrieb über `OPERATION_MODE=DELETE`;
- parallele Discovery-/Producer- und Consumer-Verarbeitung;
- H2-Journal pro Source-ItemType mit den Zuständen `SUCCESS`, `FAILED`, `SKIPPED` und `DELETED`;
- Wiederaufnahme durch erneuten Lauf mit demselben Journal;
- SHA-256-Verifikation von Source- und Destination-Inhalten;
- sichere CLI-Workflows über `bin/cm-run.sh`;
- Source-Lookup-Tri-State `EXISTS`, `NOT_FOUND`, `ERROR`;
- zentrale Weiterleitung des ersten asynchronen Producer-/Discovery-Fehlers;
- HTML-Protokolle, Top-Level-Reports und Non-OK-CSV-Exporte;
- ein während des Laufs aktualisiertes `status.html`;
- optionale, eingebettete WebGUI;
- optionaler statischer HTTP-Monitor über `bin/monitor.sh`;
- optionale E-Mail-Benachrichtigung über lokal installiertes `mutt` oder `mailx`;
- lokales JMX-MBean für Migrationsmetriken.

Nicht jeder historische oder generierte Bestandteil des Repositorys ist ein unterstützter Einstieg. `run.sh` gehört zum separaten Prototyp unter `src/main/java/com/example/migrator/`, erwartet jedoch ein anders gebautes Root-JAR, das `bin/compile.sh` nicht erzeugt. Verwenden Sie für den dokumentierten aktuellen Betrieb `bin/cm-run.sh`.

## Sicherheitsmodell

### Cascade Delete

`CASCADE_DELETE_ON_MISSING=true` erlaubt dem Verifier grundsätzlich, ein Destination-Objekt zu löschen, wenn das zugehörige Source-Objekt eindeutig als nicht vorhanden klassifiziert wurde.

Die Schutzschichten sind:

1. `bin/verify.sh` blockiert aktiviertes Cascade Delete über `bin/cascade-delete-guard.sh` mit Exitcode `2`.
2. `bin/webgui.sh` prüft beim Start `conf/migration.properties` und blockiert dort einen aktivierten Zustand ebenfalls mit Exitcode `2`.
3. `bin/cm-run.sh verification` und die Verify-Phase von `bin/cm-run.sh safe` laufen über `bin/verify.sh` und erben dessen Guard. Bei `safe` liegt diese Prüfung erst nach der Migrationsphase.
4. `Verifier.shouldCascadeDelete(...)` erlaubt eine Löschentscheidung nur bei `CASCADE_DELETE_ON_MISSING=true` **und** `SourceLookupStatus.NOT_FOUND`.

`EXISTS` führt zur normalen Verifikation. `ERROR` darf niemals löschen. Timeout-, Authentifizierungs-, Berechtigungs-, Netzwerk- und unbekannte Fehler werden nicht als „fehlend“ angenommen, sondern fail-closed als `ERROR` behandelt. Auch ein generischer Text wie „not found“ genügt nicht: Der aktuelle Classifier verlangt ein enges Meldungsmuster mit exakter PID-Zuordnung.

Besitzt die Auditzeile bereits einen gespeicherten Checksum, läuft der Verifier Destination-only und führt keinen Source-Lookup aus; Cascade Delete ist in diesem Pfad nicht erreichbar.

Die erkannten Meldungsmuster sind eine konservative Implementierungsheuristik, keine zugesicherte IBM-CM-Spezifikation. Bis ein kontrollierter IBM-Live-E2E-Test und eine ausdrückliche Betriebsfreigabe vorliegen, gilt:

```properties
CASCADE_DELETE_ON_MISSING=false
```

Direkte Java-Aufrufe und `bin/start.sh` besitzen keinen eigenen Cascade-Delete-Guard. Details und Freigabeverfahren stehen im [Betriebshandbuch](BETRIEBSHANDBUCH.md#13-cascade-delete).

### Asynchrone Worker-/Producer-Fehler

`WorkerFailureState` speichert atomar den ersten Fehler aus dem asynchronen Producer-/Discovery-Pfad. Der Fehler fordert globalen Shutdown an und wird nach dem Cleanup an den koordinierenden Aufrufer weitergereicht.

Bei einem solchen Abbruch:

- werden keine normalen Poison Pills als Erfolgssignal gesendet;
- werden normale Abschlussreports und E-Mail-Benachrichtigung übersprungen;
- erscheint keine `Migration completed!`-Erfolgsmeldung;
- werden Worker, Connection Pool, Journal und Monitor vor der Fehlerweitergabe bereinigt;
- bleibt der ursprüngliche Fehler als Cause erhalten.

Diese Lifecycle-Eigenschaften sind lokal und strukturell getestet; ein Live-E2E-Test mit IBM-CM-Verbindungen steht aus.

## Voraussetzungen

### Erforderlich

- Linux/Unix-artige Umgebung mit Bash;
- JDK **11 oder neuer** mit `java`, `javac` und `jar` für Build und Laufzeit;
- die zum eingesetzten IBM Content Manager 8.7 passenden Java- und nativen SDK-Bibliotheken;
- die im Code erwarteten IBM-Verbindungsressourcen, insbesondere `cmbicmsrvs.ini` und `cmbcmenv.properties`, sicher im Classpath/IBM-Setup;
- Netzwerk-, DNS-, Authentifizierungs- und Berechtigungszugriff auf Source und Destination;
- Schreibrechte für Journal, Logs, Reports, temporäre Dateien, `status.html` und gegebenenfalls WebGUI-Run-Snapshots;
- genügend freier Speicher für H2-Journale, Reports und Content-Temporärdateien.

`pom.xml` deklariert Java 11 als Source-/Target-Level. `bin/compile.sh` ruft `javac` jedoch ohne `--release`/`-target` auf; das erzeugte JAR benötigt daher mindestens die Java-Version des verwendeten Build-JDKs. Für ein Java-11-Artefakt mit JDK 11 bauen oder den Bytecode separat verifizieren. `bin/compile.sh`, `bin/start.sh` und `bin/verify.sh` verwenden lokale JARs aus `lib/`. Der systemweite IBM-Pfad `/opt/IBM/cm87_api/lib` ist optional im Skript hinterlegt; fehlt er, wird gewarnt. Ein erfolgreicher Build mit lokalen JARs beweist noch keine funktionierende IBM-Live-Verbindung oder JNI-Kompatibilität.

### Optional

- `python3` oder `python` für `bin/monitor.sh`;
- `mutt` oder `mailx` für E-Mail;
- `ss`, `lsof` oder `fuser` für Portprüfung und Serviceverwaltung im Wrapper;
- ein SSH-Client für den empfohlenen Tunnel zur localhost-gebundenen WebGUI;
- ProGuard- und JDK-Signing-Werkzeuge nur für `bin/build-release.sh`.

## Repository-Struktur

| Pfad | Inhalt |
|---|---|
| `src/com/ibm/ecm/migration/` | aktueller, von `bin/compile.sh` gebauter Java-Quellbaum |
| `src/main/java/com/example/migrator/` | separater Maven-/Legacy-Prototyp; nicht Teil des dokumentierten Builds |
| `bin/` | Build-, Launcher-, Guard-, Monitor- und Release-Skripte; erzeugtes `cm-migrator.jar` bleibt ungetrackt |
| `conf/` | neutrale Vorlagen sowie versionierte Log4j2-/Logging-Konfiguration; reale IBM-/Anwendungskonfigurationen bleiben lokal |
| `lib/` | lokale Java-Abhängigkeiten einschließlich IBM-CM-, H2- und Logging-JARs |
| `tests/` | dependency-freie Shell-/Java-Tests sowie der lokal IBM-abhängige Verifier-Test |
| `webapp/` | statische Dateien der WebGUI |
| `reports/` | wiederverwendbare HTML-Templates; Laufreports bleiben ungetrackt |
| `tools/` | optionale Release-/Obfuscation-/Signing-Werkzeuge; Signing-Keystores werden extern bereitgestellt |
| `.github/workflows/test.yml` | read-only CI für Shell-Syntax und IBM-unabhängige Security-Tests |

Ein `docs/`-Verzeichnis ist im aktuellen Branch nicht vorhanden. Projektdokumentation liegt auf Top-Level.

## Installation

```bash
git clone https://github.com/mrAibo/CM_Migrator.git
cd CM_Migrator
git checkout hardening/integration-readiness

cp conf/migration.properties.example conf/migration.properties
cp conf/webgui.properties.example conf/webgui.properties
cp conf/cmbcmenv.properties.example conf/cmbcmenv.properties
cp conf/cmbicmsrvs.ini.example conf/cmbicmsrvs.ini
# Optional, nur falls das freigegebene IBM-SDK-Setup sie benötigt:
# cp conf/ibmcmconfig.properties.example conf/ibmcmconfig.properties
chmod 600 conf/migration.properties conf/webgui.properties \
  conf/cmbcmenv.properties conf/cmbicmsrvs.ini
chmod +x bin/*.sh

bash bin/compile.sh
```

Die drei IBM-Vorlagen enthalten keine Host-, Benutzer- oder Credentialwerte. Sie müssen lokal nach der freigegebenen IBM-CM-Installation ausgefüllt werden; die realen Dateien bleiben durch `.gitignore` ungetrackt. Ein Release-Signing-Keystore wird ebenfalls extern bereitgestellt.

Vor dem Build und erst recht vor einem Lauf:

1. lokale `lib/*.jar` auf Herkunft, Version und Kompatibilität prüfen;
2. `SOURCE_SSID`, `DEST_SSID`, Credentials und `MIGRATE_ITEMTYPES` lokal setzen;
3. Dateirechte auf Konfiguration, IBM-Dateien und Keystores einschränken;
4. `CASCADE_DELETE_ON_MISSING=false` bestätigen;
5. keine Secrets, Reports, Journale oder Run-Snapshots committen.

## Schnellstart

Empfohlener CLI-Einstieg:

```bash
./bin/cm-run.sh safe conf/migration.properties
```

Mit statischem Monitor:

```bash
./bin/cm-run.sh safe conf/migration.properties --monitor
```

**Achtung:** `bin/monitor.sh` startet `python3 -m http.server` aus dem Projektverzeichnis ohne Authentifizierung oder TLS und bindet standardmäßig nicht nur an localhost. Dadurch können neben `status.html` weitere Projektdateien erreichbar werden. `--monitor` nur in einem isolierten, vertrauenswürdigen Netz beziehungsweise mit zusätzlicher Zugriffskontrolle verwenden.

`cm-run.sh safe` führt Migration, Verifikation, Statusprüfung und bei Bedarf eine Non-OK-Reverifikation aus. Es startet keine Remigration automatisch. Der WebGUI-Modus `safe` ist dagegen nur ein zweistufiger In-Prozess-Ablauf aus Migration und Verifikation und ersetzt den CLI-Wrapper nicht.

## Wichtige Kommandos

| Zweck | Befehl |
|---|---|
| Hilfe/Modi | `./bin/cm-run.sh --help` |
| Build | `bash bin/compile.sh` |
| Sicherer Gesamtworkflow | `./bin/cm-run.sh safe conf/migration.properties` |
| Migration | `./bin/cm-run.sh migration conf/migration.properties` |
| Verifikation | `./bin/cm-run.sh verification conf/migration.properties` |
| Non-OK erneut verifizieren | `./bin/cm-run.sh verification-nonok conf/migration.properties` |
| Journalstatus | `./bin/cm-run.sh status conf/migration.properties` |
| WebGUI auf localhost | `./bin/webgui.sh --port 8080` |
| Statischer Monitor | `./bin/monitor.sh --port 8000` |
| Wiederaufnahme | `./bin/cm-run.sh migration conf/migration.properties` mit unverändertem, geprüftem `DB_PATH` |

Der Modus `delete` ist real vorhanden, aber destruktiv und deshalb kein Schnellstart. Voraussetzungen und Freigabeschritte stehen im Betriebshandbuch.

## Konfiguration

Wichtige Dateien:

- `conf/migration.properties.example`: neutrale Vorlage für Migration, Verifikation, Journal und Reports;
- `conf/webgui.properties.example`: separate WebGUI-Auth-Vorlage; `conf/webgui.properties` hat beim Laden Vorrang vor `conf/migration.properties`;
- `conf/cmbcmenv.properties.example` und `conf/cmbicmsrvs.ini.example`: neutrale Schemas für die vom IBM-SDK geladenen lokalen Ressourcen;
- `conf/ibmcmconfig.properties.example`: optionale neutrale IBM-Installationsvorlage;
- `conf/log4j2.xml` und `conf/cmblogconfig.properties`: versionierte Logging-Konfiguration.

Mit `webgui.auth.enabled=true` startet die WebGUI nur mit nichtleerem Admin-Benutzer und entweder `WEBGUI_ADMIN_PASSWORD`, explizitem Passwort oder gültigem 64-Hex-SHA-256-Hash. Es wird kein Passwort erzeugt oder geloggt; ungültige/fehlende Werte brechen vor dem Port-Bind ab. SHA-256 bleibt eine ungesalzene Legacy-Grenze, keine moderne Passwort-KDF.

Properties-Dateien können Klartext-Credentials oder reversibel kodierte Legacy-Werte enthalten. `*_PASSWORD_CRYPT` ist **keine sichere Verschlüsselung**. Konfigurationsdateien auf `0600` begrenzen, extern sichern und nie versionieren.

Java-Properties unterstützen keine Inline-Kommentare hinter Werten. Zeilen wie `POOL_BORROW_TIMEOUT=5000 # Kommentar` werden als kompletter String gelesen und fallen beim Integer-Parsing auf den Code-Default zurück. Kommentare immer in eine eigene Zeile schreiben.

Die vollständige, codegeprüfte Referenz mit Pflichtstatus, Defaults, Profilwerten und Wirkung steht in [BETRIEBSHANDBUCH.md](BETRIEBSHANDBUCH.md#6-konfigurationsreferenz).

## Tests

Vorhandene Security-/Regressionstests:

```bash
bash tests/test-cascade-delete-guard.sh
bash tests/test-source-lookup-classifier.sh
bash tests/test-verifier-source-lookup-decision.sh
bash tests/test-worker-failure-state.sh
bash tests/test-webgui-auth-fail-closed.sh
bash tests/test-webgui-delete-lifecycle.sh
bash tests/test-tracked-config-hygiene.sh
```

`.github/workflows/test.yml` führt Shell-Syntax, `git diff --check` und alle IBM-unabhängigen Tests mit Java 17 aus. Der Hosted Runner materialisiert `lib/` nicht. `test-verifier-source-lookup-decision.sh` und `bin/compile.sh` benötigen die lokalen IBM-/Third-Party-Libraries und bleiben daher ein lokales beziehungsweise privates Gate; CI täuscht dafür keinen grünen Build vor.

Ein grüner lokaler Testlauf bestätigt die jeweils getesteten Shell-, Unit- oder Strukturpfade. Er ersetzt keinen IBM-CM-Live-E2E-Test.

## Build

```bash
bash bin/compile.sh
```

Der Build:

- kompiliert `src/com/ibm/ecm/migration/*.java` nach `target/`;
- schreibt Compiler-Ausgabe nach `compile.log`;
- erzeugt `bin/META-INF/MANIFEST.MF` mit `com.ibm.ecm.migration.Main`;
- erstellt `bin/cm-migrator.jar`.

Der separate Tree `src/main/java/com/example/migrator/` gehört zum Maven-/Legacy-Prototyp und wird von diesem Build nicht kompiliert.

`target/`, `compile.log` und `bin/cm-migrator.jar` sind Build-Artefakte und laut `.gitignore` nicht für neue Commits vorgesehen. Der Build verwendet `javac` direkt und lädt keine Dependencies nach.

## Bekannte Einschränkungen

- Kein bestätigter IBM-CM-Live-E2E-Test für Cascade-Delete-Tri-State, Worker-Fehlerweiterleitung oder WebGUI-Delete.
- Hosted CI deckt nur IBM-unabhängige Tests ab; Verifier-Test und Gesamtbuild bleiben lokales/privates Gate mit freigegebenen Libraries.
- Bereits veröffentlichte Credentials müssen unabhängig rotiert und in einer separat koordinierten Aktion aus der Git-Historie entfernt werden; dieser Branch führt keinen History-Rewrite aus.
- Laufzeit und Build hängen von lokalen `lib/*.jar` sowie gegebenenfalls einem installationsspezifischen IBM-Systempfad ab.
- Der optionale Pfad `/opt/IBM/cm87_api/lib` ist auf einem Build-Host möglicherweise nicht vorhanden.
- `SourceLookupClassifier` hängt von beobachteten Exceptionmeldungen ab; unbekannte Meldungen werden sicher als `ERROR` behandelt.
- Die reversible Legacy-Credential-Kodierung ist keine sichere Verschlüsselung.
- WebGUI Basic Auth läuft ohne eingebautes TLS; der kompatible Passwort-Hash ist ein ungesalzener SHA-256-Hash.
- `monitor.sh` stellt das Projektverzeichnis ohne Authentifizierung bereit.
- H2-Journaling ist asynchron; ein Queue-Überlauf oder ein Writer, der beim Shutdown nicht rechtzeitig endet, ist ein offenes Betriebsrisiko.
- Der separate `com.example.migrator`-Prototyp besitzt eigene Config-, Journal- und Delete-Semantik und ist nicht durch `bin/compile.sh` oder die aktuelle Testmatrix abgedeckt.
- Der aktuelle Build kann Deprecation-/Unchecked-Warnungen ausgeben; diese sind nicht automatisch Buildfehler.
- Produktive Performance und JNI-Kompatibilität benötigen eine Abnahme in der Zielumgebung.
- Nach dem nominellen 24h-Worker-Timeout kann auf laufende SDK-Aufrufe unbegrenzt weitergewartet werden; diese Policy benötigt eine ausdrückliche Betriebsentscheidung.

## Weitere Dokumentation

- [BETRIEBSHANDBUCH.md](BETRIEBSHANDBUCH.md) – detaillierter Betrieb, Sicherheit, Konfiguration, Fehlerbehebung und Wiederanlauf
- [SECURITY.md](SECURITY.md) – Disclosure- und allgemeine Security-Regeln
- [ARCHITEKTUR.md](ARCHITEKTUR.md) – historische Architekturübersicht; bei Widersprüchen gelten aktueller Code und Betriebshandbuch
- [SECURITY_P0_INTEGRATION_BERICHT.md](SECURITY_P0_INTEGRATION_BERICHT.md) – Integrationsnachweise und Testgrenzen der P0-Härtungen
- [PR3_FAIL_CLOSED_REPORT.md](PR3_FAIL_CLOSED_REPORT.md) – Detailbericht zum Source-Lookup-Tri-State
- [P0_WORKER_FAILURE_PROPAGATION_BERICHT.md](P0_WORKER_FAILURE_PROPAGATION_BERICHT.md) – Detailbericht zur asynchronen Fehlerweiterleitung

## Lizenz und Copyright

Quellheader nennen „Aleksej Voronin, Sven Lindt“ als Autoren. Eine eigenständige `LICENSE`-Datei oder eindeutige öffentliche Lizenz ist im Repository nicht vorhanden.

**Eine öffentliche Lizenz ist derzeit nicht ausgewiesen.**
