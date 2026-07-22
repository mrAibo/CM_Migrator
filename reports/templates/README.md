# CM Migrator - Prüfprotokoll-Vorlagen

Diese HTML5/CSS3-Vorlagen dienen der Generierung von Prüfprotokollen für Migration und Verifikation von IBM Content Manager-Daten.

## Vorlagen-Übersicht

| Datei | Zweck | Badge-Farbe |
|-------|-------|-------------|
| `migration_protocol_template.html` | Migrations-Protokoll pro ItemType | Orange |
| `verification_protocol_template.html` | Verifikations-Protokoll pro ItemType | Blau |
| `summary_protocol_template.html` | Kombiniertes Gesamtprotokoll | Lila |

## Platzhalter-Referenz

### Allgemeine Platzhalter (alle Vorlagen)

| Platzhalter | Beschreibung | Beispielwert |
|-------------|--------------|--------------|
| `{{COMPANY_LOGO}}` | HTML-Code für Firmenlogo (img-Tag oder leer) | `<img src="logo.png">` |
| `{{COMPANY_NAME}}` | Firmenname | `ACME Corporation` |
| `{{PROTOCOL_ID}}` | Eindeutige Protokoll-ID | `MIG-2026-0129-001` |
| `{{ITEMTYPE_SOURCE}}` | ItemType-Name im Quellsystem | `AOK_Dokument` |
| `{{ITEMTYPE_DEST}}` | ItemType-Name im Zielsystem | `AOK_Dokument_V2` |
| `{{SOURCE_SSID}}` | Quellsystem Server-ID | `LSDB` |
| `{{DEST_SSID}}` | Zielsystem Server-ID | `AOKHB-ITU` |
| `{{GENERATED_DATE}}` | Erstellungsdatum | `29.01.2026` |
| `{{GENERATED_TIME}}` | Erstellungszeit | `20:45:30` |

### Migration-spezifische Platzhalter

| Platzhalter | Beschreibung | Datentyp |
|-------------|--------------|----------|
| `{{MIG_TOTAL}}` | Gesamtanzahl Items zur Migration | Integer |
| `{{MIG_SUCCESS}}` | Erfolgreich migrierte Items | Integer |
| `{{MIG_FAILED}}` | Fehlgeschlagene Items | Integer |
| `{{MIG_SKIPPED}}` | Übersprungene Items | Integer |
| `{{MIG_SUCCESS_PERCENT}}` | Erfolgsrate in Prozent (für Balken) | 0-100 |
| `{{MIG_FAILED_PERCENT}}` | Fehlerrate in Prozent (für Balken) | 0-100 |
| `{{MIG_SUCCESS_RATE}}` | Erfolgsrate formatiert | `98.5` |
| `{{MIG_SUCCESS_RATE_CLASS}}` | CSS-Klasse für Erfolgsrate | `success`/`warning`/`error` |
| `{{ERROR_COUNT}}` | Anzahl Fehler | Integer |
| `{{ERROR_ITEMS}}` | HTML-Liste der Fehler | Siehe unten |

### Verifikation-spezifische Platzhalter

| Platzhalter | Beschreibung | Datentyp |
|-------------|--------------|----------|
| `{{VER_TOTAL}}` | Gesamtanzahl geprüfter Items | Integer |
| `{{VER_OK}}` | Items mit gültigem Hash | Integer |
| `{{VER_MISMATCH}}` | Items mit Hash-Abweichung | Integer |
| `{{VER_ORPHANED}}` | Verwaiste Items (nur im Ziel) | Integer |
| `{{VER_DELETED}}` | Gelöschte Items (Kaskadenlöschung) | Integer |
| `{{VER_OK_PERCENT}}` | OK-Rate in Prozent (für Balken) | 0-100 |
| `{{VER_MISMATCH_PERCENT}}` | Mismatch-Rate in Prozent (für Balken) | 0-100 |
| `{{VER_SUCCESS_RATE}}` | Verifikationsrate formatiert | `99.2` |
| `{{VER_SUCCESS_RATE_CLASS}}` | CSS-Klasse für Verifikationsrate | `success`/`warning`/`error` |
| `{{MISMATCH_COUNT}}` | Anzahl Mismatches | Integer |
| `{{MISMATCH_ITEMS}}` | HTML-Liste der Mismatches | Siehe unten |
| `{{CHECKSUM_SAMPLES}}` | Tabelle mit Checksummen-Stichproben | Siehe unten |

### Gesamtprotokoll-spezifische Platzhalter

| Platzhalter | Beschreibung | Datentyp |
|-------------|--------------|----------|
| `{{OVERALL_STATUS_CLASS}}` | CSS-Klasse für Gesamtstatus | `success`/`warning`/`error` |
| `{{OVERALL_STATUS_ICON}}` | Status-Icon (Emoji/Unicode) | `✓` / `⚠` / `✗` |
| `{{OVERALL_STATUS_TEXT}}` | Status-Text | `Erfolgreich` / `Warnung` / `Fehler` |
| `{{ERROR_SUMMARY}}` | Zusammenfassung aller Fehler | HTML-Block |

## HTML-Fragmente für dynamische Inhalte

### Fehler-Liste (ERROR_ITEMS / MISMATCH_ITEMS)

**Bei Fehlern:**
```html
<div class="error-list">
    <div class="error-item">Item-ID: 12345 - Fehler: Connection timeout</div>
    <div class="error-item">Item-ID: 12346 - Fehler: Duplicate key</div>
</div>
```

**Bei keinen Fehlern:**
```html
<div class="error-list empty">Keine Fehler aufgetreten</div>
```

### Checksummen-Tabelle (CHECKSUM_SAMPLES)

```html
<table class="checksum-table">
    <thead>
        <tr>
            <th>Item-ID</th>
            <th>Quell-Hash</th>
            <th>Ziel-Hash</th>
            <th>Status</th>
        </tr>
    </thead>
    <tbody>
        <tr>
            <td class="item-id">12345</td>
            <td class="checksum">a1b2c3d4...f8g9</td>
            <td class="checksum">a1b2c3d4...f8g9</td>
            <td class="status"><span class="status-icon ok">✓</span></td>
        </tr>
    </tbody>
</table>
```

### Fehler-Zusammenfassung (ERROR_SUMMARY)

**Bei Fehlern:**
```html
<div class="error-summary">
    <div class="error-count">3 Fehler gefunden</div>
    <div class="error-list">
        • Migration: Item-ID 12345 fehlgeschlagen<br>
        • Verifikation: Hash-Mismatch bei Item-ID 12346
    </div>
</div>
```

**Bei keinen Fehlern:**
```html
<div class="error-summary empty">Keine Fehler - alle Prüfungen bestanden</div>
```

## CSS-Klassen für Erfolgsraten

| Klasse | Bedingung | Farbe |
|--------|-----------|-------|
| `success` | Rate ≥ 98% | Grün (#16a34a) |
| `warning` | Rate ≥ 90% und < 98% | Orange (#f97316) |
| `error` | Rate < 90% | Rot (#dc2626) |

## Verwendung in Java

```java
// Beispiel: Template laden und Platzhalter ersetzen
String template = Files.readString(Path.of("reports/templates/migration_protocol_template.html"));

String filled = template
    .replace("{{COMPANY_NAME}}", config.getCompanyName())
    .replace("{{PROTOCOL_ID}}", generateProtocolId())
    .replace("{{MIG_TOTAL}}", String.valueOf(stats.migTotal))
    .replace("{{MIG_SUCCESS}}", String.valueOf(stats.migSuccess))
    // ... weitere Ersetzungen
    ;

// HTML speichern
Files.writeString(
    Path.of("reports/migration_AOK_Dokument_2026-01-29.html"), 
    filled
);
```

## Design-Features

- **Digital Paper Look**: Dezente Linierung, Schatten, professionelles Erscheinungsbild
- **A4-Druckoptimierung**: `@page` Regeln für korrekten PDF-Export
- **CSS-Grid Layout**: Moderne, responsive Statistik-Darstellung
- **Inter Font**: Moderne Sans-Serif für beste Lesbarkeit
- **Farbcodierung**: Grün (Erfolg), Orange (Warnung), Rot (Fehler), Blau (Info)
- **Unterschriftsfeld**: Rechtsgültiger Bereich für Prüferunterschrift

## Datei-Konventionen

Generierte Protokolle sollten folgendem Namensschema folgen:

```
reports/
├── migration_[ITEMTYPE]_[DATUM].html
├── verification_[ITEMTYPE]_[DATUM].html
└── summary_[ITEMTYPE]_[DATUM].html
```

Beispiel: `reports/migration_AOK_Dokument_2026-01-29.html`

---
*Erstellt für CM Migrator v2.2.0*
