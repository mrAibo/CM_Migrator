/*
 * Projekt: CM Migrator 2.2.1.
 */
package com.ibm.ecm.migration;

import java.text.SimpleDateFormat;
import java.util.Date;

/** Renders the printable A4 audit protocol from the unified report model. */
public final class AuditProtocolGenerator {

    private static final String VERSION = OperatorConsole.VERSION;

    // ponytail: embedded, offline-safe CSS keeps the protocol deployable as one file.
    private static final String CSS = "\n<style>\n"
            + "@page{size:A4 portrait;margin:16mm}\n"
            + "*{box-sizing:border-box}\n"
            + "body{margin:0;background:#fff;color:#1d2c40;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif;font-size:10pt;line-height:1.45}\n"
            + ".protocol{max-width:800px;margin:0 auto;padding:18px}\n"
            + ".top{display:flex;justify-content:space-between;gap:24px;border-bottom:2px solid #142944;padding-bottom:13px;margin-bottom:15px}\n"
            + ".eyebrow{color:#e76617;font-size:7pt;font-weight:700;letter-spacing:.12em;text-transform:uppercase}\n"
            + "h1{margin:4px 0 2px;font-size:19pt;text-transform:uppercase}\n"
            + ".sub{color:#64748b;font-size:8pt}\n"
            + ".doc-id{text-align:right;color:#64748b;font-size:7pt}.doc-id b{color:#142944}\n"
            + ".verdict{display:grid;grid-template-columns:2fr 1fr 1fr;border:1px solid #d9b66f;background:#fff9ed;margin-bottom:18px}\n"
            + ".verdict>div{padding:13px;border-right:1px solid #d9b66f}.verdict>div:last-child{border-right:0;text-align:center}\n"
            + ".verdict small,.meta small{display:block;color:#7b6750;font-size:7pt;text-transform:uppercase;letter-spacing:.08em}\n"
            + ".verdict strong{display:block;color:#765019;font-size:15pt;margin:4px 0}.verdict b{font-size:16pt}.bad{color:#b93838}.ok{color:#16794b}\n"
            + ".section{margin:0 0 16px}.section h2{font-size:10pt;border-bottom:2px solid #8b99aa;padding-bottom:4px;margin:0 0 8px}\n"
            + ".meta{display:grid;grid-template-columns:1fr 1fr;border:1px solid #d7dfe8;background:#f8fafc}\n"
            + ".meta div{padding:8px 10px;border-right:1px solid #d7dfe8;border-bottom:1px solid #d7dfe8}.meta div:nth-child(2n){border-right:0}\n"
            + ".meta b{display:block;margin-top:2px;font-size:8pt}\n"
            + "table{width:100%;border-collapse:collapse;font-size:8pt}th{background:#142944;color:#fff;text-align:left;font-size:7pt;padding:7px}td{padding:7px;border-bottom:1px solid #dce3ea;vertical-align:top}\n"
            + ".result{font-weight:700;color:#16794b;text-transform:uppercase}.result.review{color:#946117}\n"
            + ".mono{font-family:Consolas,'Courier New',monospace}\n"
            + ".approval{border-top:1px solid #8090a4;margin-top:20px;padding-top:10px}.approval-head{display:flex;justify-content:space-between;font-size:8pt;font-weight:700}\n"
            + ".signatures{display:grid;grid-template-columns:1fr 1fr;gap:25px;margin-top:22px}.signatures div{border-bottom:1px solid #7e8ca0;color:#79879a;font-size:7pt;padding-bottom:3px}\n"
            + ".footer{display:flex;justify-content:space-between;border-top:1px solid #dce3ea;margin-top:18px;padding-top:7px;color:#7b8899;font-size:7pt}\n"
            + "@media print{.protocol{padding:0}}\n"
            + "</style>";

    private AuditProtocolGenerator() { }

    /** Render one complete, printable protocol for the delivered report. */
    public static String render(UnifiedReport r) {
        StringBuilder sb = new StringBuilder(8192);
        String verdict = r.status() == OverallStatus.SUCCESS ? "Freigegeben" : "Bedingt freigegeben";
        String generated = new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(r.endTimeMs()));
        long verified = 0;
        long mismatches = 0;
        boolean hasVerification = false;
        for (ItemTypeResult it : r.itemTypes()) {
            if (it.verified() >= 0) {
                hasVerification = true;
                verified += it.verified();
                mismatches += Math.max(0, it.mismatches());
            }
        }

        sb.append("<!DOCTYPE html><html lang=\"de\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>CM Migrator Prüfprotokoll</title>").append(CSS)
                .append("</head><body><main class=\"protocol\">");

        sb.append("<header class=\"top\"><div><div class=\"eyebrow\">Revisionsnachweis · Migration &amp; Verifikation</div>"
                + "<h1>Prüfprotokoll</h1><div class=\"sub\">")
                .append(esc(operationLabel(r))).append(" · ")
                .append(esc(r.sourceSSID())).append(" → ").append(esc(r.destSSID()))
                .append("</div></div><div class=\"doc-id\">DOKUMENT-ID<br><b>")
                .append(esc(r.operationId())).append("</b><br>Version ").append(VERSION)
                .append("</div></header>");

        sb.append("<section class=\"verdict\"><div><small>Prüfergebnis</small><strong>")
                .append(verdict).append("</strong><span>")
                .append(r.failed() > 0
                        ? n(r.failed()) + " technische Abweichungen erfordern dokumentierte Nachbearbeitung."
                        : "Der Lauf wurde ohne offene technische Abweichungen abgeschlossen.")
                .append("</span></div><div><small>Erfolgsquote</small><b class=\"ok\">")
                .append(String.format("%.1f%%", r.successRate())).append("</b></div><div><small>Offen</small><b class=\"")
                .append(r.failed() > 0 ? "bad" : "ok").append("\">").append(n(r.failed()))
                .append("</b></div></section>");

        sb.append("<section class=\"section\"><h2>Prüfumfang und Herkunft</h2><div class=\"meta\">")
                .append(meta("Quelle", r.sourceSSID()))
                .append(meta("Ziel", r.destSSID()))
                .append(meta("Run-ID", r.operationId()))
                .append(meta("Datenstand", "Nach Journalabschluss"))
                .append(meta("Beginn / Ende", time(r.startTimeMs()) + " / " + time(r.endTimeMs())))
                .append(meta("Erzeugt", generated))
                .append("</div></section>");

        sb.append("<section class=\"section\"><h2>Prüfgegenstände</h2><table><thead><tr>"
                + "<th>Kontrolle</th><th>Nachweis</th><th>Ergebnis</th></tr></thead><tbody>")
                .append(evidence("Vollständigkeit",
                        n(r.success()) + " erfolgreich von " + n(r.total()) + " Objekten",
                        r.failed() == 0 ? "Bestanden" : "Nacharbeit", r.failed() > 0))
                .append(evidence("Fehlerbehandlung",
                        n(r.failed()) + " Fehler protokolliert, kein stiller Verlust",
                        r.failed() == 0 ? "Bestanden" : "Nacharbeit", r.failed() > 0));
        if (hasVerification) {
            sb.append(evidence("Integrität", n(verified) + " verifiziert, " + n(mismatches) + " Abweichungen",
                    mismatches == 0 ? "Bestanden" : "Nacharbeit", mismatches > 0));
        }
        sb.append(evidence("Journalabschluss", "Bericht nach Abschluss der Verarbeitung erzeugt", "Bestanden", false))
                .append("</tbody></table></section>");

        sb.append("<section class=\"section\"><h2>Ergebnis nach ItemType</h2><table><thead><tr>"
                + "<th>Mapping</th><th>Erfolg</th><th>Fehler</th><th>Verifikation</th></tr></thead><tbody>");
        for (ItemTypeResult it : r.itemTypes()) {
            sb.append("<tr><td class=\"mono\">").append(esc(it.sourceType())).append(" → ")
                    .append(esc(it.destType())).append("</td><td>").append(n(it.success()))
                    .append(" / ").append(n(it.total())).append("</td><td>").append(n(Math.max(0, it.failed())))
                    .append("</td><td>").append(it.verified() >= 0 ? n(it.verified()) : "Nicht ausgeführt")
                    .append("</td></tr>");
        }
        sb.append("</tbody></table></section>");

        sb.append("<section class=\"section\"><h2>Offene Maßnahmen</h2><table><thead><tr>"
                + "<th>Objekt</th><th>Ursache</th><th>Maßnahme</th><th>Verantwortung</th></tr></thead><tbody>");
        if (r.errors().isEmpty()) {
            sb.append("<tr><td colspan=\"4\" class=\"ok\">Keine offenen Maßnahmen</td></tr>");
        } else {
            for (ReportError error : r.errors()) {
                sb.append("<tr><td class=\"mono\">").append(esc(error.itemId())).append("</td><td>")
                        .append(esc(error.message())).append("</td><td>Objekt prüfen und erneut migrieren</td>"
                                + "<td>Betrieb</td></tr>");
            }
        }
        sb.append("</tbody></table></section>");

        sb.append("<section class=\"approval\"><div class=\"approval-head\"><span>Freigabe nach Abschluss der Maßnahmen</span><span>Status: ")
                .append(r.failed() > 0 ? "offen" : "bereit").append("</span></div>"
                        + "<div class=\"signatures\"><div>Datum / Name</div><div>Unterschrift / Ticketreferenz</div></div></section>")
                .append("<footer class=\"footer\"><span>CM Migrator ").append(VERSION)
                .append(" · Auditprotokoll</span><span>Seite 1 von 1 · ").append(esc(r.operationId()))
                .append("</span></footer></main></body></html>");
        return sb.toString();
    }

    private static String meta(String label, String value) {
        return "<div><small>" + esc(label) + "</small><b>" + esc(value) + "</b></div>";
    }

    private static String evidence(String control, String proof, String result, boolean review) {
        return "<tr><td>" + esc(control) + "</td><td>" + esc(proof) + "</td><td class=\"result"
                + (review ? " review" : "") + "\">" + esc(result) + "</td></tr>";
    }

    private static String operationLabel(UnifiedReport r) {
        switch (r.operationType()) {
            case VERIFICATION: return "Verifikation";
            case DELETE: return "Löschung";
            default: return "Migration";
        }
    }

    private static String time(long millis) {
        return new SimpleDateFormat("HH:mm:ss").format(new Date(millis));
    }

    private static String n(long value) {
        return String.format("%,d", value);
    }

    private static String esc(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
