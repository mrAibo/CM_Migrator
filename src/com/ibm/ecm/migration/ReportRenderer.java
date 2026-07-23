package com.ibm.ecm.migration;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders UnifiedReport to HTML — full report + compact email body.
 * Offline-compatible: no external fonts, CSS, or JS.
 */
public class ReportRenderer {

    private static final String VERSION = OperatorConsole.VERSION;

    // ---- embedded CSS (offline-safe) ----
    private static final String CSS = """
            <style>
            body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif;
              background:#f8fafc;color:#1e293b;margin:0;padding:20px;line-height:1.5}
            .container{max-width:1000px;margin:0 auto;background:#fff;border:1px solid #e2e8f0;
              border-radius:8px;overflow:hidden}
            .head{padding:24px 30px;background:#0f172a;color:#fff}
            .head h1{margin:0;font-size:1.4rem;font-weight:600}
            .head .sub{font-size:.8rem;color:#94a3b8;margin-top:4px}
            .badge{display:inline-block;padding:3px 12px;border-radius:99px;font-size:.75rem;
              font-weight:700;letter-spacing:1px}
            .badge.ok{background:#dcfce7;color:#166534}
            .badge.err{background:#fef2f2;color:#991b1b}
            .badge.warn{background:#fffbeb;color:#92400e}
            .kpis{display:flex;flex-wrap:wrap;gap:1px;background:#e2e8f0;border-bottom:3px solid #f97316}
            .kpi{flex:1 1 130px;background:#fff;padding:18px 14px;text-align:center}
            .kpi .v{font-size:1.6rem;font-weight:700}
            .kpi .l{font-size:.65rem;color:#64748b;text-transform:uppercase;letter-spacing:1px;margin-top:4px}
            .section{padding:20px 30px}
            .section h2{font-size:1rem;border-bottom:2px solid #e2e8f0;padding-bottom:6px;margin-bottom:12px}
            table{width:100%;border-collapse:collapse;font-size:.85rem}
            th,td{padding:8px 10px;text-align:left;border-bottom:1px solid #e2e8f0}
            th{background:#f1f5f9;font-weight:600;text-transform:uppercase;font-size:.7rem;letter-spacing:.5px}
            .card{border:1px solid #e2e8f0;border-radius:6px;margin-bottom:12px}
            .card-head{padding:10px 16px;background:#f8fafc;font-weight:600;border-bottom:1px solid #e2e8f0}
            .card-body{padding:12px 16px;display:flex;flex-wrap:wrap;gap:16px}
            .card-body .stat{flex:0 0 auto}
            .card-body .stat .v{font-size:1.1rem;font-weight:700}
            .card-body .stat .l{font-size:.6rem;color:#64748b}
            .err{color:#dc2626}.ok{color:#16a34a}.warn{color:#f59e0b}.muted{color:#94a3b8}
            </style>""";

    private static final String PAGE_TOP = "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>CM Migrator Report</title>" + CSS + "</head><body><div class=\"container\">";

    private static final String PAGE_BOT = "</div></body></html>";

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    /** Full offline HTML report. */
    public static String renderFullReport(UnifiedReport r) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append(PAGE_TOP);
        renderHeader(sb, r);
        renderKpis(sb, r);
        renderItemTypeCards(sb, r.itemTypes());
        // Aggregate all errors from item types for error table
        List<ReportError> allErrors = collectAllErrors(r);
        if (!allErrors.isEmpty()) {
            renderErrorTable(sb, allErrors);
        }
        sb.append(PAGE_BOT);
        return sb.toString();
    }

    /** Compact, table-based HTML for email (Outlook-safe). */
    public static String renderEmailBody(UnifiedReport r) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head>"
                + "<body style=\"margin:0;padding:0;background:#f8fafc;font-family:Arial,Helvetica,sans-serif\">"
                + "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\""
                + " style=\"background:#f8fafc;padding:20px 0\">"
                + "<tr><td align=\"center\">"
                + "<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\""
                + " style=\"max-width:600px;width:100%;background:#fff;border:1px solid #e2e8f0\">");

        long failed = r.failed();
        String accent = failed > 0 ? "#ef4444" : "#10b981";
        String badgeText = failed > 0 ? "ERRORS DETECTED" : "OPERATION SUCCESSFUL";
        String opTypeName = r.operationType().name();

        sb.append("<tr><td style=\"background:#0f172a;padding:20px 24px\">"
                + "<span style=\"color:#fff;font-size:14px;font-weight:bold;letter-spacing:1px\">")
                .append(esc(opTypeName)).append(" STATUS</span></td></tr>");

        sb.append("<tr><td style=\"background:").append(accent).append("15;padding:16px 24px;"
                + "border-left:4px solid ").append(accent).append("\">"
                + "<span style=\"font-size:16px;font-weight:bold;color:").append(accent).append("\">")
                .append(esc(badgeText)).append("</span></td></tr>");

        // KPIs
        sb.append("<tr><td style=\"padding:20px 24px\">"
                + "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"8\" border=\"0\">"
                + "<tr>"
                + "<td style=\"background:#f8fafc;padding:12px;border:1px solid #e2e8f0\">"
                + "<span style=\"font-size:24px;font-weight:bold\">").append(n(r.total())).append("</span><br>"
                + "<span style=\"font-size:10px;color:#64748b\">TOTAL</span></td>"
                + "<td style=\"background:#f8fafc;padding:12px;border:1px solid #e2e8f0\">"
                + "<span style=\"font-size:24px;font-weight:bold;color:#16a34a\">").append(n(r.success())).append("</span><br>"
                + "<span style=\"font-size:10px;color:#64748b\">SUCCESS</span></td>"
                + "</tr><tr>"
                + "<td style=\"background:#f8fafc;padding:12px;border:1px solid #e2e8f0\">"
                + "<span style=\"font-size:24px;font-weight:bold;color:").append(failed > 0 ? "#ef4444" : "#64748b").append("\">")
                .append(n(failed)).append("</span><br>"
                + "<span style=\"font-size:10px;color:#64748b\">FAILED</span></td>"
                + "<td style=\"background:#f8fafc;padding:12px;border:1px solid #e2e8f0\">"
                + "<span style=\"font-size:24px;font-weight:bold\">").append(r.formattedDuration()).append("</span><br>"
                + "<span style=\"font-size:10px;color:#64748b\">DURATION</span></td>"
                + "</tr></table></td></tr>");

        // Item types summary
        sb.append("<tr><td style=\"padding:8px 24px 16px\">"
                + "<span style=\"font-size:11px;color:#64748b;font-weight:bold\">ITEM TYPES</span><br>");
        for (ItemTypeResult it : r.itemTypes()) {
            sb.append("<span style=\"font-size:12px;font-family:monospace\">")
                    .append(esc(it.sourceType())).append(" \u2192 ").append(esc(it.destType()))
                    .append(" (").append(n(it.success())).append("/").append(n(it.total())).append(")</span><br>");
        }
        sb.append("</td></tr>");

        // Errors (max 5 from global list)
        List<ReportError> globErrs = r.errors();
        if (!globErrs.isEmpty()) {
            sb.append("<tr><td style=\"padding:8px 24px 16px\">"
                    + "<span style=\"font-size:11px;color:#ef4444;font-weight:bold\">RECENT ERRORS</span><br>");
            int max = Math.min(globErrs.size(), 5);
            for (int i = 0; i < max; i++) {
                ReportError re = globErrs.get(i);
                sb.append("<span style=\"font-size:11px;color:#b91c1c\">")
                        .append(esc(re.itemType())).append(" / ").append(esc(re.itemId()))
                        .append(": ").append(esc(trunc(re.message(), 80)))
                        .append("</span><br>");
            }
            if (globErrs.size() > 5) {
                sb.append("<span style=\"font-size:10px;color:#94a3b8\">... and ")
                        .append(globErrs.size() - 5).append(" more</span><br>");
            }
            sb.append("</td></tr>");
        }

        // Footer
        sb.append("<tr><td style=\"background:#0f172a;padding:12px 24px;text-align:center\">"
                + "<span style=\"color:#94a3b8;font-size:10px;letter-spacing:1px\">CM MIGRATOR v")
                .append(VERSION).append(" \u2014 AUTO-GENERATED</span></td></tr>");

        sb.append("</table></td></tr></table></body></html>");
        return sb.toString();
    }

    /** Email subject line — no emojis. */
    public static String emailSubject(UnifiedReport r) {
        long failed = r.failed();
        String outcome = failed > 0 ? "FAILED" : "SUCCESS";
        String src = r.sourceSSID() != null ? r.sourceSSID() : "SOURCE";
        String dst = r.destSSID() != null ? r.destSSID() : "DEST";
        String label = r.operationType().name();

        if (failed > 0) {
            return String.format("[CM Migrator] %s %s \u2014 %d errors \u2014 %s \u2192 %s",
                    outcome, label, failed, src, dst);
        }
        return String.format("[CM Migrator] %s %s \u2014 %,d objects \u2014 %s \u2192 %s",
                outcome, label, r.success(), src, dst);
    }

    // =========================================================================
    // INTERNAL
    // =========================================================================

    private static void renderHeader(StringBuilder sb, UnifiedReport r) {
        long failed = r.failed();
        String badgeClass = failed > 0 ? "err" : "ok";
        String badgeText = failed > 0 ? "ERRORS" : "SUCCESS";
        sb.append("<div class=\"head\"><h1>")
                .append(esc(r.operationType().name())).append(" REPORT</h1>")
                .append("<div class=\"sub\">").append(esc(r.operationId())).append(" | ")
                .append(r.formattedDuration()).append(" | ").append(r.itemTypes().size()).append(" item types</div>")
                .append("<span class=\"badge ").append(badgeClass).append("\">").append(badgeText).append("</span>")
                .append("</div>");
    }

    private static void renderKpis(StringBuilder sb, UnifiedReport r) {
        long failed = r.failed();
        sb.append("<div class=\"kpis\">");
        kpi(sb, n(r.total()), "TOTAL", "");
        kpi(sb, n(r.success()), "SUCCESS", "ok");
        kpi(sb, n(failed), "FAILED", failed > 0 ? "err" : "muted");
        kpi(sb, n(r.skipped()), "SKIPPED", "muted");
        if (r.deleted() > 0) kpi(sb, n(r.deleted()), "DELETED", "muted");
        kpi(sb, String.format("%.1f/s", r.throughputPerSec()), "THROUGHPUT", "");
        kpi(sb, String.format("%.1f%%", r.successRate()), "SUCCESS RATE", r.successRate() >= 99 ? "ok" : "");
        kpi(sb, r.formattedDuration(), "DURATION", "");
        sb.append("</div>");
    }

    private static void kpi(StringBuilder sb, String val, String label, String cls) {
        sb.append("<div class=\"kpi\"><div class=\"v ").append(cls).append("\">")
                .append(esc(val)).append("</div><div class=\"l\">").append(esc(label)).append("</div></div>");
    }

    private static void renderItemTypeCards(StringBuilder sb, List<ItemTypeResult> items) {
        sb.append("<div class=\"section\"><h2>Per Item-Type Detail</h2>");
        for (ItemTypeResult it : items) {
            sb.append("<div class=\"card\">");
            sb.append("<div class=\"card-head\">").append(esc(it.sourceType()))
                    .append(" \u2192 ").append(esc(it.destType())).append("</div>");
            sb.append("<div class=\"card-body\">");
            stat(sb, n(it.total()), "TOTAL", "");
            stat(sb, n(it.success()), "SUCCESS", "ok");
            stat(sb, n(it.failed()), "FAILED", it.failed() > 0 ? "err" : "muted");
            stat(sb, n(it.skipped()), "SKIPPED", "muted");
            if (it.deleted() > 0) stat(sb, n(it.deleted()), "DELETED", "muted");
            if (it.verified() >= 0) stat(sb, n(it.verified()), "VERIFIED", "ok");
            if (it.mismatches() > 0) stat(sb, n(it.mismatches()), "MISMATCHES", "err");
            if (it.orphaned() > 0) stat(sb, n(it.orphaned()), "ORPHANED", "err");
            sb.append("</div></div>");
        }
        sb.append("</div>");
    }

    private static void stat(StringBuilder sb, String val, String label, String cls) {
        sb.append("<div class=\"stat\"><div class=\"v ").append(cls).append("\">")
                .append(esc(val)).append("</div><div class=\"l\">").append(esc(label)).append("</div></div>");
    }

    private static void renderErrorTable(StringBuilder sb, List<ReportError> errors) {
        sb.append("<div class=\"section\"><h2>Errors</h2>"
                + "<table><thead><tr>"
                + "<th>Item Type</th><th>Item ID</th><th>Status</th><th>Message</th><th>Timestamp</th>"
                + "</tr></thead><tbody>");
        for (ReportError re : errors) {
            sb.append("<tr>")
                    .append("<td>").append(esc(re.itemType())).append("</td>")
                    .append("<td>").append(esc(re.itemId())).append("</td>")
                    .append("<td>").append(esc(re.status())).append("</td>")
                    .append("<td class=\"err\">").append(esc(trunc(re.message(), 100))).append("</td>")
                    .append("<td>").append(esc(re.timestamp())).append("</td>")
                    .append("</tr>");
        }
        sb.append("</tbody></table></div>");
    }

    /** Collect all errors from every item type for the error table. */
    private static List<ReportError> collectAllErrors(UnifiedReport r) {
        List<ReportError> all = new ArrayList<>(r.errors());
        for (ItemTypeResult it : r.itemTypes()) {
            for (ReportError re : it.errors()) {
                if (!all.contains(re)) all.add(re);
            }
        }
        return all;
    }

    // ---- helpers ----

    static String n(long v) { return String.format("%,d", v); }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
