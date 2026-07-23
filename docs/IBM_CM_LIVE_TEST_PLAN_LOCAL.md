# IBM-CM Live E2E — Local Operator Checklist

**Erstellt:** 2026-07-23
**Repository:** mrAibo/CM_Migrator @ `main` (HEAD `11b6b13`)
**Runbook:** `docs/IBM_CM_LIVE_ACCEPTANCE.md`
**Zweck:** Konkrete Vorbereitungsschritte für einen Operator mit IBM-CM-8.7-Sandbox-Zugang.

---

## 1. Environment Readiness

| Prerequisite | Status | Details |
|---|---|---|
| JDK 11+ | ✅ Present | `/tmp/cm-migrator-temurin-17.0.19+10/bin/java` (Temurin 17) |
| IBM SDK JARs | ✅ Present | `lib/cmbicmsdk81.jar` + 13 weitere JARs |
| Native Libraries | ❌ **BLOCKED** | `/opt/IBM/cm87_api/lib` nicht vorhanden; `libcmbicmsdk81.so` muss vom IBM-SDK-Installationsverzeichnis verlinkt/kopiert werden |
| `conf/migration.properties` | ❌ **BLOCKED** | Operator muss aus `conf/migration.properties.example` erstellen und ausfüllen |
| `conf/cmbcmenv.properties` | ❌ **BLOCKED** | Operator muss aus `conf/cmbcmenv.properties.example` erstellen |
| `conf/cmbicmsrvs.ini` | ❌ **BLOCKED** | Operator muss aus `conf/cmbicmsrvs.ini.example` erstellen |
| Source CM 8.7 Server | ❌ **BLOCKED** | `<SANDBOX_SOURCE_HOST>` — Bereitstellung durch IBM-CM-Admin |
| Destination CM 8.7 Server | ❌ **BLOCKED** | `<SANDBOX_DEST_HOST>` — Bereitstellung durch IBM-CM-Admin |
| Netzwerk | ❌ **BLOCKED** | Konnektivität zu beiden IBM-Instanzen prüfen: `ping` + `telnet <host> <port>` |
| Disk | ✅ 161G frei | `/dev/nvme0n1p2` |
| RAM | ✅ 15G gesamt, ~5G verfügbar | Für parallele Consumer ausreichend |
| Build-Artefakt | ✅ `bin/cm-migrator.jar` | `bash bin/compile.sh` erfolgreich |

---

## 2. Required Configuration Values

| Key | Source | Value | Status |
|---|---|---|---|
| `SOURCE_SSID` | IBM-CM-Admin | **BLOCKED** | `<SANDBOX_SOURCE_SSID>` |
| `DEST_SSID` | IBM-CM-Admin | **BLOCKED** | `<SANDBOX_DEST_SSID>` |
| `CONNECT_USER` | IBM-CM-Admin | **BLOCKED** | `<SANDBOX_CONNECT_USER>` |
| `CONNECT_PASSWORD` | IBM-CM-Admin | **BLOCKED** | `<SANDBOX_CONNECT_PASSWORD>` |
| `SOURCE_USER` | IBM-CM-Admin | Optional — Fallback auf `CONNECT_USER` |
| `DEST_USER` | IBM-CM-Admin | Optional — Fallback auf `CONNECT_USER` |
| `MIGRATE_ITEMTYPES` | Operator | **BLOCKED** | `<TEST_ITEM_TYPE>:<TEST_ITEM_TYPE>` |
| `DB_PATH` | Operator | `reports/journal_acceptance_<SESSION>` — pro Test-Session eindeutig |
| `CASCADE_DELETE_ON_MISSING` | Fix | `false` — niemals ändern |
| `-Dcm.migrator.shutdown.graceSeconds` | JVM-Arg | `60` (default) |
| `JAVA_HOME` | Operator | `/tmp/cm-migrator-temurin-17.0.19+10` oder systemweit |

---

## 3. Test Data Requirements

| Artefakt | Beschreibung | Status |
|---|---|---|
| EXISTS-Objekte (~50) | Normale Items im Source-System, Typ `<TEST_ITEM_TYPE>` | **BLOCKED** — Operator muss anlegen |
| NOT_FOUND-Test-ID | Item-ID eines Objekts, das NICHT im Source existiert (entweder nach Migration gelöscht oder direkt auf Destination eingefügt) | **BLOCKED** — Operator muss vorbereiten |
| >2-GiB-Objekte (~3) | Items im Source mit Content > 2,147,483,648 Bytes | **BLOCKED** — Operator muss anlegen |
| Restricted-User | Testbenutzer ohne Leseberechtigung auf `<TEST_ITEM_TYPE_READONLY>` | **BLOCKED** — IBM-CM-Admin muss einrichten |
| Wrong-Password | Bewusst falsches Passwort für T03 | Operator setzt temporär `CONNECT_PASSWORD=<WRONG_PASSWORD>` |

---

## 4. Test Execution Matrix

### Phase 1: Read-Only / Safety (T01–T05)

| Test | Befehl | Config-Besonderheit | Erwarteter Exit |
|---|---|---|---|
| **T01** EXISTS | `./bin/cm-run.sh verification conf/migration.properties` | Baseline-Config | 0 |
| **T02** NOT_FOUND | `./bin/cm-run.sh verification conf/migration.properties` | Destination hat Waisen-Items | ≠0 |
| **T03** Auth Error | `./bin/cm-run.sh safe conf/migration.properties` | `CONNECT_PASSWORD=<WRONG_PASSWORD>` | ≠0 |
| **T04** Timeout | `./bin/cm-run.sh safe conf/migration.properties` | `POOL_BORROW_TIMEOUT=1` + Firewall-Delay | ≠0 |
| **T05** Permission | `./bin/cm-run.sh safe conf/migration.properties` | `CONNECT_USER=<RESTRICTED_SANDBOX_USER>` + `<TEST_ITEM_TYPE_READONLY>` | ≠0 |

### Phase 2: Lifecycle / Signal (T06–T08)

| Test | Befehl | Config-Besonderheit |
|---|---|---|
| **T06** Producer Failure | `./bin/cm-run.sh safe conf/migration.properties` | Item-Type dessen Discovery serverseitig fehlschlägt |
| **T07** WebGUI Error | `./bin/webgui.sh --port 8080 &` dann Migration über WebUI triggern | `WEBGUI_ENABLED=true`, falsche Credentials |
| **T08** SIGTERM | `./bin/cm-run.sh safe conf/migration.properties & sleep 10; kill -TERM $PID` | Lange laufende Migration |

### Phase 3: Stateful (T09–T10)

| Test | Befehl |
|---|---|
| **T09** Resume | `./bin/cm-run.sh safe conf/migration.properties` → SIGTERM → erneut starten mit gleichem `DB_PATH` |
| **T10** >2 GiB | `./bin/cm-run.sh safe conf/migration.properties` mit `<TEST_ITEM_TYPE_LARGE>` |

### Phase 4: JNI / Performance (T11–T12)

| Test | Befehl |
|---|---|
| **T11** JNI | `./bin/cm-run.sh verification conf/migration.properties` mit korrektem `java.library.path` |
| **T12** Performance | `./bin/cm-run.sh safe conf/migration.properties` mit `PROFILE=GROSS` oder `PROFILE=EXTREM` |

---

## 5. Log, Report & Journal Paths

| Artefakt | Pfad | Notizen |
|---|---|---|
| Haupt-Log | `migration.log` | Wird pro Run überschrieben — vor neuem Run sichern |
| Verify-Error-Log | `verification_errors.log` | Nur ERROR-Level |
| Status-Dashboard | `status.html` | Läuft live, nach Run archivieren |
| Reports | `reports/acceptance_<SESSION>/*.html`, `*.csv` | Pro Test-Session eindeutiges `REPORT_DIR` setzen |
| Journal (H2) | `reports/journal_acceptance_<SESSION>/*.mv.db` | Pro Test-Session eindeutiger `DB_PATH` |
| WebGUI-Log | `logs/webgui.log` | Nur bei T07 |

---

## 6. Backup & Rollback

| Aktion | Befehl |
|---|---|
| Config sichern | `cp conf/migration.properties conf/migration.properties.bak_$(date +%Y%m%d_%H%M%S)` |
| Journal sichern | `cp -r reports/journal_acceptance_<SESSION> reports/journal_acceptance_<SESSION>.bak` |
| Destination-Items zählen (vor Test) | Über IBM-CM-Admin-Client oder SDK-Query |
| Nach T03/T04/T05 Config wiederherstellen | `cp conf/migration.properties.bak_* conf/migration.properties` |
| Ganzen Test-Session-Ordner löschen | `rm -rf reports/journal_acceptance_<SESSION> reports/acceptance_<SESSION>` |

---

## 7. Performance Baseline (T12)

| Metrik | Default | Empfohlen für T12 |
|---|---|---|
| `PROFILE` | (unset) | `GROSS` oder `EXTREM` |
| `THREAD_COUNT` | 5 | 20–50 |
| `BATCH_SIZE` | 100 | 200–500 |
| `QUEUE_SIZE` | 10000 | 20000–50000 |
| Item-Anzahl | ~50 (Funktionstest) | ≥1000 für Performance-Test |
| Metriken erfassen | — | `top`, `iostat`, `vmstat` während Lauf; `migration.log` auf Slow-Item-Warnungen prüfen |

---

## 8. Operator Actions Required

1. **IBM-CM-Sandbox bereitstellen** — Source + Destination CM 8.7, isoliert, keine Produktionsdaten.
2. **Native Libraries installieren** — `/opt/IBM/cm87_api/lib/libcmbicmsdk81.so` (oder äquivalent) + `java.library.path` in `bin/start.sh` prüfen.
3. **Config-Dateien aus `.example`-Vorlagen erstellen** — `conf/migration.properties`, `conf/cmbcmenv.properties`, `conf/cmbicmsrvs.ini`, alle `chmod 600`.
4. **Test-Objekte anlegen** — EXISTS (~50 Normal), NOT_FOUND (1–5 Waisen), >2 GiB (3 große Items), READONLY (10 Items + Restricted User).
5. **T01–T12 der Reihe nach ausführen**, Ergebnisse pro Test in dieser Checkliste dokumentieren.
6. **Bei FAIL: Log + Journal + Report sichern**, nicht löschen, zur Analyse bereitstellen.
7. **Nach allen Tests: `CASCADE_DELETE_ON_MISSING=false` verifizieren**, Test-Sessions aufräumen.

---

## 9. Status Summary

| Metrik | Wert |
|---|---|
| Environment ready | **NO** |
| Runnable tests | **0** (kein IBM-CM-Sandbox-System) |
| Blocked tests | **12** (T01–T12) |
| Code ready (lokal) | ✅ 10/10 Tests, `bin/compile.sh` OK, CI grün |
| Kritische Voraussetzung | IBM-CM-8.7-Sandbox + Native-Libraries + Config |

---

**Nächster Schritt:** Operator (mit IBM-CM-Admin-Zugang) füllt §2 dieser Checkliste aus und führt §4 aus.
