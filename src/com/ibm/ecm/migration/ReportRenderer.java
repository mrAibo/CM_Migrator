package com.ibm.ecm.migration;

import java.util.ArrayList;
import java.util.List;

/** Renders UnifiedReport to offline HTML and an Outlook-safe email body. */
public final class ReportRenderer {

    private static final String VERSION = OperatorConsole.VERSION;

    // ponytail: one embedded stylesheet keeps generated reports portable and offline.
    private static final String CSS = "\n<style>\n"
            + "*{box-sizing:border-box}body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif;background:#edf1f5;color:#1c2b40;margin:0;padding:22px;line-height:1.45}\n"
            + ".container{max-width:1040px;margin:0 auto;background:#fff;border:1px solid #d6dee8}\n"
            + ".mast{display:flex;justify-content:space-between;gap:24px;background:#142944;color:#fff;padding:25px 30px}\n"
            + ".eyebrow{color:#b8c5d5;font-size:.72rem;letter-spacing:.12em;text-transform:uppercase}.mast h1{font-size:1.55rem;margin:6px 0}.sub{color:#aebdd0;font-size:.78rem}\n"
            + ".decision{min-width:245px;background:#fff5e8;border-left:4px solid #e76617;color:#78460f;padding:13px 15px}.decision b{display:block;font-size:1.05rem;margin-bottom:4px}.decision span{font-size:.75rem}\n"
            + ".meta{display:grid;grid-template-columns:2fr repeat(3,1fr);border-bottom:1px solid #d7dfe8}.meta div{padding:12px 16px;border-right:1px solid #d7dfe8}.meta div:last-child{border-right:0}.meta small,.summary small{display:block;color:#758398;font-size:.65rem;text-transform:uppercase;letter-spacing:.08em}.meta b{font-size:.8rem}\n"
            + ".body{padding:22px 30px}.priority{display:grid;grid-template-columns:1fr 1.4fr;gap:14px;margin-bottom:24px}.summary{padding:17px;border:1px solid #d4dde7}.summary.alert{background:#fdf0f0;border:0;border-left:4px solid #b93838}.summary strong{display:block;font-size:1.55rem;margin:5px 0}.summary.alert strong{color:#b93838}.progress{height:5px;background:#e1e7ee;margin-top:10px}.progress span{display:block;height:100%;background:#16794b}\n"
            + ".section{margin:0 0 24px}.section-title{display:flex;justify-content:space-between;align-items:end;border-bottom:2px solid #d4dde7;padding-bottom:6px;margin-bottom:8px}.section-title h2{font-size:1rem;margin:0}.section-title span{font-size:.68rem;color:#768497}\n"
            + "table{width:100%;border-collapse:collapse;font-size:.78rem}th{padding:8px 10px;text-align:left;background:#eaf0f5;color:#617085;font-size:.64rem;text-transform:uppercase;letter-spacing:.06em}td{padding:9px 10px;border-bottom:1px solid #dde4ec;vertical-align:top}.mono{font-family:Consolas,'Courier New',monospace}.bad{color:#b93838;font-weight:700}.ok{color:#16794b;font-weight:700}.muted{color:#8290a1}.severity{display:inline-block;background:#fbe5e5;color:#b93838;padding:2px 6px;font-size:.62rem;font-weight:800}\n"
            + ".kpis{display:grid;grid-template-columns:repeat(4,1fr);gap:1px;background:#d7dfe8;margin-bottom:24px}.kpi{background:#f8fafc;padding:13px;text-align:center}.kpi b{display:block;font-size:1.05rem}.kpi small{color:#758398;font-size:.6rem;text-transform:uppercase}\n"
            + ".footer{display:flex;justify-content:space-between;background:#f6f8fa;border-top:1px solid #d7dfe8;padding:10px 30px;color:#7b8898;font-size:.65rem}\n"
            + "@media(max-width:720px){body{padding:0}.mast,.priority{display:block}.decision{margin-top:14px}.meta{grid-template-columns:1fr 1fr}.kpis{grid-template-columns:1fr 1fr}.body{padding:18px}.table-wrap{overflow-x:auto}}\n"
            + "</style>";

    private ReportRenderer() { }

    /** Full offline HTML report. */
    public static String renderFullReport(UnifiedReport r) {
        List<ReportError> errors = collectAllErrors(r);
        StringBuilder sb = new StringBuilder(12288);
        sb.append("<!DOCTYPE html><html lang=\"de\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>CM Migrator Abschlussbericht</title>").append(CSS)
                .append("</head><body><main class=\"container\">");

        renderMast(sb, r);
        renderMeta(sb, r);
        sb.append("<div class=\"body\">");
        renderPriority(sb, r);
        if (!errors.isEmpty()) renderErrorTable(sb, errors);
        renderItemTypes(sb, r.itemTypes());
        renderKpis(sb, r);
        sb.append("</div><footer class=\"footer\"><span>CM Migrator ").append(VERSION)
                .append(" · Abschlussbericht</span><span>").append(esc(r.operationId()))
                .append("</span></footer></main></body></html>");
        return sb.toString();
    }

    /** Compact, table-based HTML for Outlook and mailx. */
    public static String renderEmailBody(UnifiedReport r) {
        List<ReportError> errors = collectAllErrors(r);
        boolean review = needsReview(r);
        StringBuilder sb = new StringBuilder(8192);
        sb.append("<!DOCTYPE html><html lang=\"de\"><head><meta charset=\"UTF-8\"></head>"
                + "<body style=\"margin:0;padding:0;background:#f2f5f8;font-family:Arial,Helvetica,sans-serif;color:#1c2b40\">"
                + "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background:#f2f5f8;padding:20px 0\"><tr><td align=\"center\">"
                + "<table width=\"600\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"width:100%;max-width:600px;background:#fff;border:1px solid #d7dfe8\">");

        sb.append("<tr><td style=\"background:#142944;padding:20px 24px;color:#fff\">"
                + "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td>"
                + "<span style=\"display:block;color:#b8c5d5;font-size:10px;letter-spacing:1px\">CM MIGRATOR · LAUF ABGESCHLOSSEN</span>"
                + "<span style=\"display:block;font-size:20px;font-weight:bold;margin-top:5px\">")
                .append(esc(resultTitle(r))).append("</span><span style=\"display:block;color:#aebdd0;font-size:11px;margin-top:4px\">")
                .append(esc(r.sourceSSID())).append(" → ").append(esc(r.destSSID())).append(" · ")
                .append(esc(r.operationId())).append("</span></td></tr></table></td></tr>");

        sb.append("<tr><td style=\"padding:14px 24px;background:")
                .append(review ? "#fff5e8;border-left:4px solid #e76617;color:#78460f" : "#edf9f2;border-left:4px solid #16794b;color:#155f3c")
                .append("\"><span style=\"display:block;font-size:16px;font-weight:bold\">")
                .append(review ? "Prüfung erforderlich" : "Freigabebereit")
                .append("</span><span style=\"font-size:11px\">")
                .append(review
                        ? n(r.failed()) + " Abweichungen vor Freigabe bearbeiten."
                        : "Keine offenen technischen Abweichungen.")
                .append("</span></td></tr>");

        sb.append("<tr><td style=\"padding:18px 24px\"><table width=\"100%\" cellpadding=\"0\" cellspacing=\"8\"><tr>")
                .append(mailKpi(n(r.total()), "TOTAL", "#1c2b40"))
                .append(mailKpi(n(r.success()), "SUCCESS", "#16794b"))
                .append(mailKpi(n(r.failed()), "FAILED", review ? "#b93838" : "#758398"))
                .append(mailKpi(r.formattedDuration(), "DURATION", "#1c2b40"))
                .append("</tr></table></td></tr>");

        if (review) {
            sb.append("<tr><td style=\"padding:0 24px 14px\"><table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#fff8ed;border:1px solid #ead5b4\"><tr><td style=\"padding:11px 13px;color:#704b17;font-size:11px\">"
                    + "<b style=\"display:block;color:#4e3210;margin-bottom:4px\">Nächster Schritt</b>"
                    + "Betroffene Objekte im Zielsystem prüfen, anschließend Einzel-Retry ausführen und das Prüfprotokoll freigeben."
                    + "</td></tr></table></td></tr>");
        }

        if (!errors.isEmpty()) {
            sb.append("<tr><td style=\"padding:4px 24px 16px\"><span style=\"font-size:11px;font-weight:bold;color:#263950\">OFFENE ABWEICHUNGEN</span>"
                    + "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin-top:6px\">");
            int max = Math.min(errors.size(), 5);
            for (int i = 0; i < max; i++) {
                ReportError error = errors.get(i);
                sb.append("<tr><td style=\"padding:8px 0;border-bottom:1px solid #e1e7ee;font-size:11px\"><b style=\"color:#253950\">")
                        .append(esc(error.itemType())).append(" / ").append(esc(error.itemId()))
                        .append("</b><br><span style=\"color:#6d7b8c\">").append(esc(trunc(error.message(), 100)))
                        .append(" · Objekt prüfen und erneut migrieren</span></td></tr>");
            }
            if (errors.size() > max) {
                sb.append("<tr><td style=\"padding-top:6px;color:#8190a2;font-size:10px\">… und ")
                        .append(errors.size() - max).append(" weitere</td></tr>");
            }
            sb.append("</table></td></tr>");
        }

        sb.append("<tr><td style=\"padding:4px 24px 18px\"><span style=\"font-size:11px;font-weight:bold;color:#263950\">ERGEBNIS NACH ITEMTYPE</span>"
                + "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin-top:6px\">");
        for (ItemTypeResult it : r.itemTypes()) {
            sb.append("<tr><td style=\"padding:6px 0;border-bottom:1px solid #e1e7ee;font-family:monospace;font-size:11px\">")
                    .append(esc(it.sourceType())).append(" → ").append(esc(it.destType()))
                    .append("</td><td align=\"right\" style=\"padding:6px 0;border-bottom:1px solid #e1e7ee;font-size:11px\">")
                    .append(n(it.success())).append(" / ").append(n(it.total())).append("</td></tr>");
        }
        sb.append("</table></td></tr><tr><td style=\"background:#142944;padding:11px 24px;text-align:center;color:#98a9bd;font-size:10px;letter-spacing:1px\">CM MIGRATOR ")
                .append(VERSION).append(" · AUTOMATISCHE BETRIEBSNACHRICHT</td></tr>"
                        + "</table></td></tr></table></body></html>");
        return sb.toString();
    }

    /** Decision-first email subject line without emoji. */
    public static String emailSubject(UnifiedReport r) {
        String decision = needsReview(r) ? "PRÜFUNG" : "ERFOLGREICH";
        if (needsReview(r)) {
            return String.format("[CM Migrator] %s %s — %d Abweichungen — %s → %s",
                    decision, r.operationType().name(), r.failed(), safe(r.sourceSSID(), "SOURCE"), safe(r.destSSID(), "DEST"));
        }
        return String.format("[CM Migrator] %s %s — %,d Objekte — %s → %s",
                decision, r.operationType().name(), r.success(), safe(r.sourceSSID(), "SOURCE"), safe(r.destSSID(), "DEST"));
    }

    private static void renderMast(StringBuilder sb, UnifiedReport r) {
        boolean review = needsReview(r);
        sb.append("<header class=\"mast\"><div><div class=\"eyebrow\">CM Migrator · Abschlussbericht</div><h1>")
                .append(esc(resultTitle(r))).append("</h1><div class=\"sub\">")
                .append(esc(r.sourceSSID())).append(" → ").append(esc(r.destSSID())).append(" · Run ")
                .append(esc(r.operationId())).append("</div></div><div class=\"decision\"><b>")
                .append(review ? "Prüfung erforderlich" : "Freigabebereit").append("</b><span>")
                .append(review ? n(r.failed()) + " Abweichungen vor Freigabe bearbeiten" : "Keine offenen technischen Abweichungen")
                .append("</span></div></header>");
    }

    private static void renderMeta(StringBuilder sb, UnifiedReport r) {
        sb.append("<section class=\"meta\"><div><small>Geltungsbereich</small><b>")
                .append(r.itemTypes().size()).append(" ItemTypes · ").append(esc(r.operationType().name()))
                .append("</b></div><div><small>Dauer</small><b>").append(esc(r.formattedDuration()))
                .append("</b></div><div><small>Durchsatz</small><b>").append(String.format("%.1f/s", r.throughputPerSec()))
                .append("</b></div><div><small>Version</small><b>").append(VERSION).append("</b></div></section>");
    }

    private static void renderPriority(StringBuilder sb, UnifiedReport r) {
        sb.append("<section class=\"priority\"><div class=\"summary alert\"><small>Offene Abweichungen</small><strong>")
                .append(n(r.failed())).append("</strong><span>")
                .append(r.failed() > 0 ? "Bearbeitung vor Freigabe erforderlich" : "Keine offenen Fehler")
                .append("</span></div><div class=\"summary\"><small>Erfolgreich verarbeitet</small><strong>")
                .append(n(r.success())).append(" / ").append(n(r.total())).append("</strong><div class=\"progress\"><span style=\"width:")
                .append(Math.max(0.0, Math.min(100.0, r.successRate()))).append("%\"></span></div></div></section>");
    }

    private static void renderErrorTable(StringBuilder sb, List<ReportError> errors) {
        sb.append("<section class=\"section\"><div class=\"section-title\"><h2>Prüfpflichtige Objekte</h2><span>")
                .append(errors.size()).append(" Einträge</span></div><div class=\"table-wrap\"><table><thead><tr>"
                        + "<th>Priorität</th><th>Objekt</th><th>Ursache</th><th>Nächster Schritt</th></tr></thead><tbody>");
        for (ReportError error : errors) {
            sb.append("<tr><td><span class=\"severity\">FEHLER</span></td><td class=\"mono\">")
                    .append(esc(error.itemId())).append("</td><td>").append(esc(trunc(error.message(), 140)))
                    .append("</td><td>Objekt prüfen und erneut migrieren</td></tr>");
        }
        sb.append("</tbody></table></div></section>");
    }

    private static void renderItemTypes(StringBuilder sb, List<ItemTypeResult> items) {
        sb.append("<section class=\"section\"><div class=\"section-title\"><h2>Ergebnis nach ItemType</h2><span>vollständige Statistik</span></div>"
                + "<div class=\"table-wrap\"><table><thead><tr><th>Mapping</th><th>Erfolg</th><th>Fehler</th><th>Übersprungen</th><th>Verifikation</th></tr></thead><tbody>");
        for (ItemTypeResult it : items) {
            sb.append("<tr><td class=\"mono\">").append(esc(it.sourceType())).append(" → ").append(esc(it.destType()))
                    .append("</td><td class=\"ok\">").append(n(it.success())).append(" / ").append(n(it.total()))
                    .append("</td><td class=\"").append(it.failed() > 0 ? "bad" : "muted").append("\">")
                    .append(n(Math.max(0, it.failed()))).append("</td><td>").append(n(Math.max(0, it.skipped())))
                    .append("</td><td>").append(it.verified() >= 0 ? n(it.verified()) : "Nicht ausgeführt")
                    .append("</td></tr>");
        }
        sb.append("</tbody></table></div></section>");
    }

    private static void renderKpis(StringBuilder sb, UnifiedReport r) {
        sb.append("<section class=\"section\"><div class=\"section-title\"><h2>Technische Kennzahlen</h2><span>vollständiger Lauf</span></div><div class=\"kpis\">");
        kpi(sb, n(r.total()), "TOTAL");
        kpi(sb, n(r.success()), "SUCCESS");
        kpi(sb, n(r.failed()), "FAILED");
        kpi(sb, n(r.skipped()), "SKIPPED");
        kpi(sb, String.format("%.1f/s", r.throughputPerSec()), "THROUGHPUT");
        kpi(sb, String.format("%.1f%%", r.successRate()), "SUCCESS RATE");
        kpi(sb, r.formattedDuration(), "DURATION");
        kpi(sb, String.valueOf(r.itemTypes().size()), "ITEM TYPES");
        sb.append("</div></section>");
    }

    private static void kpi(StringBuilder sb, String value, String label) {
        sb.append("<div class=\"kpi\"><b>").append(esc(value)).append("</b><small>").append(esc(label)).append("</small></div>");
    }

    private static String mailKpi(String value, String label, String color) {
        return "<td width=\"25%\" style=\"padding:10px 5px;text-align:center;background:#f8fafc;border:1px solid #e1e7ee\">"
                + "<span style=\"display:block;font-size:16px;font-weight:bold;color:" + color + "\">" + esc(value) + "</span>"
                + "<span style=\"font-size:8px;color:#758398\">" + esc(label) + "</span></td>";
    }

    private static String resultTitle(UnifiedReport r) {
        String operation;
        switch (r.operationType()) {
            case VERIFICATION: operation = "Verifikation"; break;
            case DELETE: operation = "Löschung"; break;
            default: operation = "Migration"; break;
        }
        if (needsReview(r)) return operation + " mit Abweichungen abgeschlossen";
        return operation + " erfolgreich abgeschlossen";
    }

    private static boolean needsReview(UnifiedReport r) {
        return r.status() != OverallStatus.SUCCESS || r.failed() > 0;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** Collect all distinct errors from the report and its item-type details. */
    static List<ReportError> collectAllErrors(UnifiedReport r) {
        List<ReportError> all = new ArrayList<>(r.errors());
        for (ItemTypeResult it : r.itemTypes()) {
            for (ReportError error : it.errors()) {
                if (!all.contains(error)) all.add(error);
            }
        }
        return all;
    }

    static String n(long value) {
        return String.format("%,d", value);
    }

    static String esc(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    static String trunc(String value, int max) {
        if (value == null) return "";
        if (max <= 3) return value.length() <= max ? value : value.substring(0, Math.max(0, max));
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }
}
