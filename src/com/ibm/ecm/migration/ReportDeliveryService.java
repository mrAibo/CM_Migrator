package com.ibm.ecm.migration;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Writes report.html + optional errors.csv to {REPORT_OUTPUT_DIR}/{operationId}/,
 * then sends email via mutt (preferred) or mailx (body-only fallback).
 */
public class ReportDeliveryService {
    private static final Logger logger = LogManager.getLogger(ReportDeliveryService.class);

    public static DeliveryResult deliver(UnifiedReport report, MigrationConfig config) {
        // 0. Check REPORT_ENABLED (issue 10)
        if ("false".equalsIgnoreCase(config.getProperty("REPORT_ENABLED", "true"))) {
            logger.info("REPORT_ENABLED=false — skipping report generation and email.");
            return new DeliveryResult(false, false, "none",
                    "REPORT_ENABLED=false", null);
        }

        // 1. Determine output dir from config (issue 5)
        String outputDir = config.getProperty("REPORT_OUTPUT_DIR", "reports");
        String baseDir = outputDir + File.separator + report.operationId();

        // Directory collision handling (issue 12)
        String dir = baseDir;
        int collision = 1;
        while (new File(dir).exists()) {
            collision++;
            dir = outputDir + File.separator + report.operationId() + "_" + collision;
        }
        new File(dir).mkdirs();
        String reportPath = new File(dir, "report.html").getAbsolutePath();

        // 2. Write report.html
        try (PrintWriter w = new PrintWriter(
                new FileOutputStream(reportPath), false, StandardCharsets.UTF_8)) {
            w.print(ReportRenderer.renderFullReport(report));
        } catch (Exception e) {
            logger.error("Failed to write report.html: {}", e.getMessage(), e);
            return new DeliveryResult(false, false, "none",
                    "Failed to write report: " + e.getMessage(), reportPath);
        }
        logger.info("Report written to {}", reportPath);

        // 3. Write errors.csv (configurable — issue 8)
        boolean csvEnabled = !"false".equalsIgnoreCase(
                config.getProperty("REPORT_ERROR_CSV", "true"));
        List<ReportError> allErrors = new ArrayList<>(report.errors());
        boolean hasMismatches = false;
        for (ItemTypeResult it : report.itemTypes()) {
            if (it.mismatches() > 0 || it.orphaned() > 0) hasMismatches = true;
            for (ReportError re : it.errors()) {
                if (!allErrors.contains(re)) allErrors.add(re);
            }
        }
        boolean hasErrors = !allErrors.isEmpty() || hasMismatches;

        String errorsCsvPath = null;
        if (csvEnabled && hasErrors && !allErrors.isEmpty()) {
            errorsCsvPath = new File(dir, "errors.csv").getAbsolutePath();
            try (PrintWriter w = new PrintWriter(
                    new FileOutputStream(errorsCsvPath), false, StandardCharsets.UTF_8)) {
                w.println("operation_id,operation_type,item_type,item_id,status,timestamp,message");
                for (ReportError re : allErrors) {
                    w.println(csvEsc(report.operationId()) + ","
                            + csvEsc(report.operationType().name()) + ","
                            + csvEsc(re.itemType()) + ","
                            + csvEsc(re.itemId()) + ","
                            + csvEsc(re.status()) + ","
                            + csvEsc(re.timestamp()) + ","
                            + csvEsc(re.message()));
                }
            } catch (Exception e) {
                logger.error("Failed to write errors.csv: {}", e.getMessage(), e);
            }
            logger.info("Errors CSV written to {}", errorsCsvPath);
        }

        // 4. Email
        String emailTo = config.getEmailTo();
        if (emailTo == null || emailTo.trim().isEmpty()) {
            logger.info("No EMAIL_TO configured — skipping email delivery.");
            return new DeliveryResult(false, false, "none",
                    "No EMAIL_TO configured", reportPath);  // issue 11: errorMessage not null
        }

        emailTo = emailTo.trim();
        boolean debugMail = "true".equalsIgnoreCase(
                config.getProperty("REPORT_DEBUG_MAIL", "false"));

        // Debug mail: dump then CONTINUE to send (issue 6)
        if (debugMail) {
            File debugDir = new File("debug_mail");
            debugDir.mkdirs();
            String debugFile = "debug_mail/mail_"
                    + report.operationType().name().toLowerCase()
                    + "_" + System.currentTimeMillis() + ".html";
            try (FileOutputStream fos = new FileOutputStream(debugFile)) {
                fos.write(ReportRenderer.renderEmailBody(report).getBytes(StandardCharsets.UTF_8));
                logger.info("DEBUG: email body dumped to {}", debugFile);
            } catch (Exception ex) {
                logger.warn("DEBUG dump failed: {}", ex.getMessage());
            }
            // ponytail: continue to send after debug dump — no early return
        }

        String subject = ReportRenderer.emailSubject(report);
        String htmlBody = ReportRenderer.renderEmailBody(report);

        // Attachments — configurable (issue 7)
        boolean attachEnabled = !"false".equalsIgnoreCase(
                config.getProperty("REPORT_ATTACH", "true"));
        List<String> attachments = new ArrayList<>();
        if (attachEnabled) {
            attachments.add(reportPath);
            if (errorsCsvPath != null) {
                attachments.add(errorsCsvPath);
            }
        }

        String transport = detectMailCommand();
        if (transport == null) {
            logger.error("Neither mutt nor mailx found. Cannot send email.");
            return new DeliveryResult(false, false, "none",
                    "No mail command found on system", reportPath);  // issue 11
        }

        try {
            boolean sent;
            boolean attIncluded;
            if ("mailx".equals(transport)) {
                // mailx: body-only, attachments unreliable (issue 9)
                sent = sendWith(transport, emailTo, subject, htmlBody, List.of());
                attIncluded = false;
            } else {
                sent = sendWith(transport, emailTo, subject, htmlBody, attachments);
                attIncluded = attachEnabled;
            }
            return new DeliveryResult(sent, attIncluded, transport,
                    sent ? null : "Email send returned non-zero exit code", reportPath);
        } catch (Exception e) {
            logger.error("Email delivery failed: {}", e.getMessage(), e);
            return new DeliveryResult(false, false, transport,
                    "Email delivery exception: " + e.getMessage(), reportPath);
        }
    }

    // ---- mail sending ----

    private static boolean sendWith(String transport, String to, String subject,
            String htmlBody, List<String> attachments) throws Exception {
        List<String> cmd = new ArrayList<>();
        if ("mutt".equals(transport)) {
            cmd.add("mutt");
            cmd.add("-e");
            cmd.add("set content_type=text/html");
            cmd.add("-s");
            cmd.add(subject);
            for (String att : attachments) {
                cmd.add("-a");
                cmd.add(att);
            }
            cmd.add("--");
            cmd.add(to);
        } else { // mailx — body-only, no file attachments
            cmd.add("mailx");
            cmd.add("-a");
            cmd.add("Content-Type: text/html; charset=UTF-8");
            cmd.add("-s");
            cmd.add(subject);
            cmd.add(to);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        Process p = pb.start();

        try (OutputStreamWriter w = new OutputStreamWriter(
                p.getOutputStream(), StandardCharsets.UTF_8)) {
            w.write(htmlBody);
            w.flush();
        }

        int exit = p.waitFor();
        if (exit != 0) {
            StringBuilder err = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getErrorStream()))) {
                String line;
                while ((line = r.readLine()) != null) err.append(line).append("\n");
            }
            logger.error("{} exited with {} — {}", transport, exit, err.toString().trim());
            return false;
        }
        logger.info("Email sent via {} to {}", transport, to);
        return true;
    }

    // ponytail: test-only override via static field (package-private).
    // Set to "mutt", "mailx", or "none" before calling deliver().
    // Null means use system detection (production default).
    static String mailPathOverride = null;

    private static String detectMailCommand() {
        if (mailPathOverride != null) {
            switch (mailPathOverride) {
                case "mutt":  return "mutt";
                case "mailx": return "mailx";
                case "none":  return null;
                default:      break;  // fall through to which
            }
        }
        if (commandExists("mutt")) return "mutt";
        if (commandExists("mailx")) return "mailx";
        return null;
    }

    private static boolean commandExists(String cmd) {
        try {
            return Runtime.getRuntime()
                    .exec(new String[]{"which", cmd})
                    .waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- CSV escaping ----

    private static String csvEsc(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
