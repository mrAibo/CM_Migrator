/*
 * Projekt: CM Migrator 2.2.1.
 * @Author: Aleksej Voronin, Sven Lindt
 * @Date:   26.01.2026
 */
package com.ibm.ecm.migration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Outlook-kompatibles E-Mail-Benachrichtigungssystem für Migrationsberichte.
 * Unterscheidet zwischen Migration und Verifizierung, zeigt Elementtypen an.
 */
public class EmailNotifier {
    private static final Logger logger = LogManager.getLogger(EmailNotifier.class);

    // Design System Tokens
    private static final String COLOR_BG_BODY = "#f8fafc";
    private static final String COLOR_BG_CARD = "#ffffff";
    private static final String COLOR_BORDER = "#e2e8f0";
    private static final String COLOR_TEXT_MAIN = "#0f172a";
    private static final String COLOR_TEXT_MUTED = "#64748b";
    private static final String COLOR_TEXT_INVERSE = "#ffffff";
    
    private static final String COLOR_SUCCESS_MAIN = "#10b981";
    private static final String COLOR_SUCCESS_BG = "#ecfdf5";
    private static final String COLOR_SUCCESS_BORDER = "#a7f3d0";
    private static final String COLOR_SUCCESS_TEXT = "#047857";
    
    private static final String COLOR_INFO_MAIN = "#3b82f6";
    private static final String COLOR_INFO_TEXT = "#1d4ed8";
    
    private static final String COLOR_ERROR_MAIN = "#ef4444";
    private static final String COLOR_ERROR_BG = "#fef2f2";
    private static final String COLOR_ERROR_BORDER = "#fecaca";
    private static final String COLOR_ERROR_TEXT = "#b91c1c";
    
    private static final String COLOR_WARNING_MAIN = "#f59e0b";
    private static final String COLOR_WARNING_BG = "#fffbeb";
    private static final String COLOR_WARNING_BORDER = "#fde68a";
    private static final String COLOR_WARNING_TEXT = "#92400e";
    
    private static final String COLOR_FOOTER_BG = "#0f172a";
    private static final String COLOR_FOOTER_TEXT = "#94a3b8";

    /**
     * Success-Template für MIGRATION
     */
    private static final String HTML_TEMPLATE_MIGRATION_SUCCESS = 
        "<!DOCTYPE html>" +
        "<html><head><meta charset=\"UTF-8\"></head>" +
        "<body style=\"margin:0; padding:0; background-color:" + COLOR_BG_BODY + "; font-family:Inter, Arial, Helvetica, sans-serif;\">" +
        "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background-color:" + COLOR_BG_BODY + "; padding:20px 0;\">" +
        "  <tr><td align=\"center\">" +
        "    <!--[if mso]><table width=\"600\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td><![endif]-->" +
        "    <table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background-color:" + COLOR_BG_CARD + "; border:1px solid " + COLOR_BORDER + "; max-width:600px; width:100%; margin:0 auto;\">" +
        "      <tr><td style=\"background-color:" + COLOR_INFO_TEXT + "; padding:30px; border-bottom:4px solid " + COLOR_INFO_MAIN + ";\">" +
        "        <table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\">" +
        "          <tr>" +
        "            <td style=\"color:" + COLOR_TEXT_INVERSE + "; font-size:14px; font-weight:bold; letter-spacing:2px;\">MIGRATION STATUS</td>" +
        "            <td align=\"right\" style=\"color:" + COLOR_TEXT_INVERSE + "; font-size:11px;\">" +
        "              <div style=\"color:rgba(255,255,255,0.7);\">TIMESTAMP</div>" +
        "              <div style=\"font-size:13px; margin-top:2px;\">{{timestamp}}</div>" +
        "            </td>" +
        "          </tr>" +
        "        </table>" +
        "      </td></tr>" +
        "      <tr><td style=\"background-color:" + COLOR_SUCCESS_BG + "; border-left:6px solid " + COLOR_SUCCESS_MAIN + "; padding:24px;\">" +
        "        <table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\">" +
        "          <tr>" +
        "            <td width=\"40\" style=\"font-size:28px; vertical-align:middle; color:" + COLOR_SUCCESS_TEXT + ";\">&#10003;</td>" +
        "            <td style=\"vertical-align:middle;\">" +
        "              <div style=\"font-size:20px; font-weight:bold; color:" + COLOR_SUCCESS_TEXT + "; margin-bottom:4px;\">MIGRATION SUCCESSFUL</div>" +
        "              <div style=\"font-size:13px; color:" + COLOR_SUCCESS_MAIN + ";\">All data transferred successfully</div>" +
        "            </td>" +
        "          </tr>" +
        "        </table>" +
        "      </td></tr>" +
        "      <tr><td style=\"background-color:" + COLOR_BG_BODY + "; padding:30px;\">" +
        "        <table width=\"100%\" cellpadding=\"0\" cellspacing=\"10\" border=\"0\" style=\"margin-bottom:20px;\">" +
        "          <tr>" +
        "            <td width=\"50%\" style=\"background-color:" + COLOR_BG_CARD + "; border:1px solid " + COLOR_BORDER + "; padding:20px; vertical-align:top;\">" +
        "              <div style=\"font-size:32px; font-weight:bold; color:" + COLOR_TEXT_MAIN + "; margin-bottom:8px;\">{{success_items}}</div>" +
        "              <div style=\"font-size:11px; color:" + COLOR_TEXT_MUTED + "; text-transform:uppercase; letter-spacing:1px;\">OBJECTS MIGRATED</div>" +
        "            </td>" +
        "            <td width=\"50%\" style=\"background-color:" + COLOR_BG_CARD + "; border:1px solid " + COLOR_BORDER + "; padding:20px; vertical-align:top;\">" +
        "              <div style=\"font-size:32px; font-weight:bold; color:" + COLOR_TEXT_MAIN + "; margin-bottom:8px;\">{{duration}}</div>" +
        "              <div style=\"font-size:11px; color:" + COLOR_TEXT_MUTED + "; text-transform:uppercase; letter-spacing:1px;\">EXECUTION TIME</div>" +
        "            </td>" +
        "          </tr>" +
        "          <tr>" +
        "            <td width=\"50%\" style=\"background-color:" + COLOR_BG_CARD + "; border:1px solid " + COLOR_BORDER + "; padding:20px; vertical-align:top;\">" +
        "              <div style=\"font-size:32px; font-weight:bold; color:" + COLOR_INFO_MAIN + "; margin-bottom:8px;\">{{speed}}/s</div>" +
        "              <div style=\"font-size:11px; color:" + COLOR_TEXT_MUTED + "; text-transform:uppercase; letter-spacing:1px;\">AVERAGE SPEED</div>" +
        "            </td>" +
        "            <td width=\"50%\" style=\"background-color:" + COLOR_BG_CARD + "; border:1px solid " + COLOR_BORDER + "; padding:20px; vertical-align:top;\">" +
        "              <div style=\"font-size:32px; font-weight:bold; color:" + COLOR_SUCCESS_MAIN + "; margin-bottom:8px;\">100%</div>" +
        "              <div style=\"font-size:11px; color:" + COLOR_TEXT_MUTED + "; text-transform:uppercase; letter-spacing:1px;\">SUCCESS RATE</div>" +
        "            </td>" +
        "          </tr>" +
        "        </table>" +
        "        <table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background-color:" + COLOR_BG_CARD + "; border:1px solid " + COLOR_BORDER + "; border-left:4px solid " + COLOR_INFO_MAIN + "; margin-bottom:20px;\">" +
        "          <tr><td style=\"padding:20px;\">" +
        "            <div style=\"font-size:12px; font-weight:bold; color:" + COLOR_TEXT_MAIN + "; margin-bottom:12px; text-transform:uppercase;\">SUMMARY</div>" +
        "            <div style=\"font-size:14px; line-height:1.6; color:" + COLOR_TEXT_MUTED + ";\">" +
        "              Successfully migrated <strong style=\"color:" + COLOR_TEXT_MAIN + ";\">{{success_items}} objects</strong> " +
        "              ({{itemtypes}}) from <strong style=\"color:" + COLOR_TEXT_MAIN + ";\">{{source}}</strong> to " +
        "              <strong style=\"color:" + COLOR_TEXT_MAIN + ";\">{{destination}}</strong>. Data integrity verified." +
        "            </div>" +
        "          </td></tr>" +
        "        </table>" +
        "        <table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\">" +
        "          <tr><td align=\"center\" style=\"padding-top:10px;\">" +
        "            <a href=\"file://{{report_path}}\" style=\"display:inline-block; background-color:" + COLOR_INFO_MAIN + "; color:" + COLOR_TEXT_INVERSE + "; padding:14px 32px; text-decoration:none; font-weight:bold; font-size:13px; text-transform:uppercase;\">VIEW DETAILED REPORT</a>" +
        "          </td></tr>" +
        "        </table>" +
        "      </td></tr>" +
        "      <tr><td style=\"background-color:" + COLOR_BG_BODY + "; border-top:1px solid " + COLOR_BORDER + "; padding:24px;\">" +
        "        <table width=\"100%\" cellpadding=\"8\" cellspacing=\"0\" border=\"0\">" +
        "          <tr>" +
        "            <td width=\"33%\" style=\"vertical-align:top;\">" +
        "              <div style=\"font-size:11px; color:" + COLOR_TEXT_MUTED + "; font-weight:bold; margin-bottom:4px;\">SOURCE</div>" +
        "              <div style=\"font-size:12px; color:" + COLOR_TEXT_MAIN + "; font-family:'Courier New', monospace;\">{{source}}</div>" +
        "            </td>" +
        "            <td width=\"33%\" style=\"vertical-align:top;\">" +
        "              <div style=\"font-size:11px; color:" + COLOR_TEXT_MUTED + "; font-weight:bold; margin-bottom:4px;\">TARGET</div>" +
        "              <div style=\"font-size:12px; color:" + COLOR_TEXT_MAIN + "; font-family:'Courier New', monospace;\">{{destination}}</div>" +
        "            </td>" +
        "            <td width=\"33%\" style=\"vertical-align:top;\">" +
        "              <div style=\"font-size:11px; color:" + COLOR_TEXT_MUTED + "; font-weight:bold; margin-bottom:4px;\">MIGRATION ID</div>" +
        "              <div style=\"font-size:12px; color:" + COLOR_TEXT_MAIN + "; font-family:'Courier New', monospace;\">{{migration_id}}</div>" +
        "            </td>" +
        "          </tr>" +
        "          <tr>" +
        "            <td colspan=\"3\" style=\"vertical-align:top; padding-top:8px;\">" +
        "              <div style=\"font-size:11px; color:" + COLOR_TEXT_MUTED + "; font-weight:bold; margin-bottom:4px;\">ITEM TYPES</div>" +
        "              <div style=\"font-size:12px; color:" + COLOR_TEXT_MAIN + "; font-family:'Courier New', monospace;\">{{itemtypes}}</div>" +
        "            </td>" +
        "          </tr>" +
        "        </table>" +
        "      </td></tr>" +
        "      <tr><td style=\"background-color:" + COLOR_FOOTER_BG + "; padding:20px; text-align:center;\">" +
        "        <div style=\"color:" + COLOR_FOOTER_TEXT + "; font-size:11px; letter-spacing:1px;\">CM MIGRATOR v6.3 — AUTOMATED SYSTEM NOTIFICATION</div>" +
        "      </td></tr>" +
        "    </table>" +
        "    <!--[if mso]></td></tr></table><![endif]-->" +
        "  </td></tr>" +
        "</table>" +
        "</body></html>";

    /**
     * Success-Template für VERIFICATION
     */
    private static final String HTML_TEMPLATE_VERIFICATION_SUCCESS = 
        "<!DOCTYPE html>" +
        "<html><head><meta charset=\"UTF-8\"></head>" +
        "<body style=\"margin:0; padding:0; background-color:" + COLOR_BG_BODY + "; font-family:Inter, Arial, Helvetica, sans-serif;\">" +
        "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background-color:" + COLOR_BG_BODY + "; padding:20px 0;\">" +
        "  <tr><td align=\"center\">" +
        "    <!--[if mso]><table width=\"600\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td><![endif]-->" +
        "    <table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background-color:" + COLOR_BG_CARD + "; border:1px solid " + COLOR_BORDER + "; max-width:600px; width:100%; margin:0 auto;\">" +
        "      <tr><td style=\"background-color:" + COLOR_SUCCESS_TEXT + "; padding:30px; border-bottom:4px solid " + COLOR_SUCCESS_MAIN + ";\">" +
        "        <table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\">" +
        "          <tr>" +
        "            <td style=\"color:" + COLOR_TEXT_INVERSE + "; font-size:14px; font-weight:bold; letter-spacing:2px;\">VERIFICATION STATUS</td>" +
        "            <td align=\"right\" style=\"color:" + COLOR_TEXT_INVERSE + "; font-size:11px;\">" +
        "              <div style=\"color:rgba(255,255,255,0.7);\">TIMESTAMP</div>" +
        "              <div style=\"font-size:13px; margin-top:2px;\">{{timestamp}}</div>" +
        "            </td>" +
        "          </tr>" +
        "        </table>" +
        "      </td></tr>" +
        "      <tr><td style=\"background-color:" + COLOR_SUCCESS_BG + "; border-left:6px solid " + COLOR_SUCCESS_MAIN + "; padding:24px;\">" +
        "        <table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\">" +
        "          <tr>" +
        "            <td width=\"40\" style=\"font-size:28px; vertical-align:middle; color:" + COLOR_SUCCESS_TEXT + ";\">&#10003;</td>" +
        "            <td style=\"vertical-align:middle;\">" +
        "              <div style=\"font-size:20px; font-weight:bold; color:" + COLOR_SUCCESS_TEXT + "; margin-bottom:4px;\">VERIFICATION SUCCESSFUL</div>" +
        "              <div style=\"font-size:13px; color:" + COLOR_SUCCESS_MAIN + ";\">All data verified successfully</div>" +
        "            </td>" +
        "          </tr>" +
        "        </table>" +
        "      </td></tr>" +
        "      <tr><td style=\"background-color:" + COLOR_BG_BODY + "; padding:30px;\">" +
        "        <table width=\"100%\" cellpadding=\"0\" cellspacing=\"10\" border=\"0\" style=\"margin-bottom:20px;\">" +
        "          <tr>" +
        "            <td width=\"50%\" style=\"background-color:" + COLOR_BG_CARD + "; border:1px solid " + COLOR_BORDER + "; padding:20px; vertical-align:top;\">" +
        "              <div style=\"font-size:32px; font-weight:bold; color:" + COLOR_TEXT_MAIN + "; margin-bottom:8px;\">{{success_items}}</div>" +
        "              <div style=\"font-size:11px; color:" + COLOR_TEXT_MUTED + "; text-transform:uppercase; letter-spacing:1px;\">OBJECTS VERIFIED</div>" +
        "            </td>" +
        "            <td width=\"50%\" style=\"background-color:" + COLOR_BG_CARD + "; border:1px solid " + COLOR_BORDER + "; padding:20px; vertical-align:top;\">" +
        "              <div style=\"font-size:32px; font-weight:bold; color:" + COLOR_TEXT_MAIN + "; margin-bottom:8px;\">{{duration}}</div>" +
        "              <div style=\"font-size:11px; color:" + COLOR_TEXT_MUTED + "; text-transform:uppercase; letter-spacing:1px;\">EXECUTION TIME</div>" +
        "            </td>" +
        "          </tr>" +
        "          <tr>" +
        "            <td width=\"50%\" style=\"background-color:" + COLOR_BG_CARD + "; border:1px solid " + COLOR_BORDER + "; padding:20px; vertical-align:top;\">" +
        "              <div style=\"font-size:32px; font-weight:bold; color:" + COLOR_INFO_MAIN + "; margin-bottom:8px;\">{{speed}}/s</div>" +
        "              <div style=\"font-size:11px; color:" + COLOR_TEXT_MUTED + "; text-transform:uppercase; letter-spacing:1px;\">AVERAGE SPEED</div>" +
        "            </td>" +
        "            <td width=\"50%\" style=\"background-color:" + COLOR_BG_CARD + "; border:1px solid " + COLOR_BORDER + "; padding:20px; vertical-align:top;\">" +
        "              <div style=\"font-size:32px; font-weight:bold; color:" + COLOR_SUCCESS_MAIN + "; margin-bottom:8px;\">100%</div>" +
        "              <div style=\"font-size:11px; color:" + COLOR_TEXT_MUTED + "; text-transform:uppercase; letter-spacing:1px;\">VERIFICATION RATE</div>" +
        "            </td>" +
        "          </tr>" +
        "        </table>" +
        "        <table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background-color:" + COLOR_BG_CARD + "; border:1px solid " + COLOR_BORDER + "; border-left:4px solid " + COLOR_SUCCESS_MAIN + "; margin-bottom:20px;\">" +
        "          <tr><td style=\"padding:20px;\">" +
        "            <div style=\"font-size:12px; font-weight:bold; color:" + COLOR_TEXT_MAIN + "; margin-bottom:12px; text-transform:uppercase;\">SUMMARY</div>" +
        "            <div style=\"font-size:14px; line-height:1.6; color:" + COLOR_TEXT_MUTED + ";\">" +
        "              Successfully verified <strong style=\"color:" + COLOR_TEXT_MAIN + ";\">{{success_items}} objects</strong> " +
        "              ({{itemtypes}}) in database <strong style=\"color:" + COLOR_TEXT_MAIN + ";\">{{destination}}</strong>. " +
        "              All data integrity checks passed." +
        "            </div>" +
        "          </td></tr>" +
        "        </table>" +
        "        <table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\">" +
        "          <tr><td align=\"center\" style=\"padding-top:10px;\">" +
        "            <a href=\"file://{{report_path}}\" style=\"display:inline-block; background-color:" + COLOR_SUCCESS_MAIN + "; color:" + COLOR_TEXT_INVERSE + "; padding:14px 32px; text-decoration:none; font-weight:bold; font-size:13px; text-transform:uppercase;\">VIEW VERIFICATION REPORT</a>" +
        "          </td></tr>" +
        "        </table>" +
        "      </td></tr>" +
        "      <tr><td style=\"background-color:" + COLOR_BG_BODY + "; border-top:1px solid " + COLOR_BORDER + "; padding:24px;\">" +
        "        <table width=\"100%\" cellpadding=\"8\" cellspacing=\"0\" border=\"0\">" +
        "          <tr>" +
        "            <td width=\"50%\" style=\"vertical-align:top;\">" +
        "              <div style=\"font-size:11px; color:" + COLOR_TEXT_MUTED + "; font-weight:bold; margin-bottom:4px;\">VERIFIED DATABASE</div>" +
        "              <div style=\"font-size:12px; color:" + COLOR_TEXT_MAIN + "; font-family:'Courier New', monospace;\">{{destination}}</div>" +
        "            </td>" +
        "            <td width=\"50%\" style=\"vertical-align:top;\">" +
        "              <div style=\"font-size:11px; color:" + COLOR_TEXT_MUTED + "; font-weight:bold; margin-bottom:4px;\">VERIFICATION ID</div>" +
        "              <div style=\"font-size:12px; color:" + COLOR_TEXT_MAIN + "; font-family:'Courier New', monospace;\">{{migration_id}}</div>" +
        "            </td>" +
        "          </tr>" +
        "          <tr>" +
        "            <td colspan=\"2\" style=\"vertical-align:top; padding-top:8px;\">" +
        "              <div style=\"font-size:11px; color:" + COLOR_TEXT_MUTED + "; font-weight:bold; margin-bottom:4px;\">ITEM TYPES</div>" +
        "              <div style=\"font-size:12px; color:" + COLOR_TEXT_MAIN + "; font-family:'Courier New', monospace;\">{{itemtypes}}</div>" +
        "            </td>" +
        "          </tr>" +
        "        </table>" +
        "      </td></tr>" +
        "      <tr><td style=\"background-color:" + COLOR_FOOTER_BG + "; padding:20px; text-align:center;\">" +
        "        <div style=\"color:" + COLOR_FOOTER_TEXT + "; font-size:11px; letter-spacing:1px;\">CM MIGRATOR v6.3 — AUTOMATED SYSTEM NOTIFICATION</div>" +
        "      </td></tr>" +
        "    </table>" +
        "    <!--[if mso]></td></tr></table><![endif]-->" +
        "  </td></tr>" +
        "</table>" +
        "</body></html>";

    /**
     * Error-Template (für beide Modi)
     */
    private static final String HTML_TEMPLATE_ERROR = 
        "<!DOCTYPE html>" +
        "<html><head><meta charset=\"UTF-8\"></head>" +
        "<body style=\"margin:0; padding:0; background-color:" + COLOR_BG_BODY + "; font-family:Inter, Arial, Helvetica, sans-serif;\">" +
        "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background-color:" + COLOR_BG_BODY + "; padding:20px 0;\">" +
        "  <tr><td align=\"center\">" +
        "    <!--[if mso]><table width=\"600\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td><![endif]-->" +
        "    <table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background-color:" + COLOR_BG_CARD + "; border:1px solid " + COLOR_BORDER + "; max-width:600px; width:100%; margin:0 auto;\">" +
        "      <tr><td style=\"background-color:" + COLOR_ERROR_TEXT + "; padding:30px; border-bottom:4px solid " + COLOR_ERROR_MAIN + ";\">" +
        "        <table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\">" +
        "          <tr>" +
        "            <td style=\"color:" + COLOR_TEXT_INVERSE + "; font-size:14px; font-weight:bold; letter-spacing:2px;\">" +
        "              &#9888; SYSTEM ALERT " +
        "              <span style=\"background-color:rgba(255,255,255,0.2); color:" + COLOR_TEXT_INVERSE + "; padding:4px 12px; border-radius:12px; font-size:10px; margin-left:12px;\">CRITICAL</span>" +
        "            </td>" +
        "            <td align=\"right\" style=\"color:" + COLOR_TEXT_INVERSE + "; font-size:11px;\">" +
        "              <div style=\"color:rgba(255,255,255,0.7);\">TIMESTAMP</div>" +
        "              <div style=\"font-size:13px; margin-top:2px;\">{{timestamp}}</div>" +
        "            </td>" +
        "          </tr>" +
        "        </table>" +
        "      </td></tr>" +
        "      <tr><td style=\"background-color:" + COLOR_ERROR_BG + "; border-left:6px solid " + COLOR_ERROR_MAIN + "; padding:24px;\">" +
        "        <table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\">" +
        "          <tr>" +
        "            <td width=\"40\" style=\"font-size:28px; vertical-align:middle; color:" + COLOR_ERROR_TEXT + ";\">&#10005;</td>" +
        "            <td style=\"vertical-align:middle;\">" +
        "              <div style=\"font-size:20px; font-weight:bold; color:" + COLOR_ERROR_TEXT + "; margin-bottom:4px;\">ERRORS DETECTED</div>" +
        "              <div style=\"font-size:13px; color:" + COLOR_ERROR_MAIN + ";\">Immediate action required</div>" +
        "            </td>" +
        "          </tr>" +
        "        </table>" +
        "      </td></tr>" +
        "      <tr><td style=\"background-color:" + COLOR_BG_BODY + "; padding:30px;\">" +
        "        <table width=\"100%\" cellpadding=\"0\" cellspacing=\"10\" border=\"0\" style=\"margin-bottom:20px;\">" +
        "          <tr>" +
        "            <td width=\"50%\" style=\"background-color:" + COLOR_BG_CARD + "; border:1px solid " + COLOR_BORDER + "; padding:20px; vertical-align:top;\">" +
        "              <div style=\"font-size:32px; font-weight:bold; color:" + COLOR_TEXT_MAIN + "; margin-bottom:8px;\">{{total_items}}</div>" +
        "              <div style=\"font-size:11px; color:" + COLOR_TEXT_MUTED + "; text-transform:uppercase; letter-spacing:1px;\">TOTAL SCOPE</div>" +
        "            </td>" +
        "            <td width=\"50%\" style=\"background-color:" + COLOR_BG_CARD + "; border:1px solid " + COLOR_BORDER + "; padding:20px; vertical-align:top;\">" +
        "              <div style=\"font-size:32px; font-weight:bold; color:" + COLOR_ERROR_MAIN + "; margin-bottom:8px;\">{{failed_items}}</div>" +
        "              <div style=\"font-size:11px; color:" + COLOR_TEXT_MUTED + "; text-transform:uppercase; letter-spacing:1px;\">FAILURES</div>" +
        "            </td>" +
        "          </tr>" +
        "          <tr>" +
        "            <td width=\"50%\" style=\"background-color:" + COLOR_BG_CARD + "; border:1px solid " + COLOR_BORDER + "; padding:20px; vertical-align:top;\">" +
        "              <div style=\"font-size:32px; font-weight:bold; color:" + COLOR_SUCCESS_MAIN + "; margin-bottom:8px;\">{{success_items}}</div>" +
        "              <div style=\"font-size:11px; color:" + COLOR_TEXT_MUTED + "; text-transform:uppercase; letter-spacing:1px;\">SUCCESSFUL</div>" +
        "            </td>" +
        "            <td width=\"50%\" style=\"background-color:" + COLOR_BG_CARD + "; border:1px solid " + COLOR_BORDER + "; padding:20px; vertical-align:top;\">" +
        "              <div style=\"font-size:32px; font-weight:bold; color:" + COLOR_WARNING_MAIN + "; margin-bottom:8px;\">{{success_rate}}%</div>" +
        "              <div style=\"font-size:11px; color:" + COLOR_TEXT_MUTED + "; text-transform:uppercase; letter-spacing:1px;\">SUCCESS RATE</div>" +
        "            </td>" +
        "          </tr>" +
        "        </table>" +
        "        <table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background-color:" + COLOR_WARNING_BG + "; border:1px solid " + COLOR_WARNING_BORDER + "; border-left:4px solid " + COLOR_WARNING_MAIN + "; margin-bottom:20px;\">" +
        "          <tr><td style=\"padding:20px;\">" +
        "            <div style=\"font-size:12px; font-weight:bold; color:" + COLOR_WARNING_TEXT + "; margin-bottom:12px; text-transform:uppercase;\">&#9888; WARNING</div>" +
        "            <div style=\"font-size:14px; line-height:1.6; color:" + COLOR_WARNING_TEXT + ";\">" +
        "              {{error_context}}" +
        "            </div>" +
        "          </td></tr>" +
        "        </table>" +
        "        <table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\">" +
        "          <tr><td align=\"center\" style=\"padding-top:10px;\">" +
        "            <a href=\"file://{{report_path}}\" style=\"display:inline-block; background-color:" + COLOR_ERROR_MAIN + "; color:" + COLOR_TEXT_INVERSE + "; padding:14px 32px; text-decoration:none; font-weight:bold; font-size:13px; text-transform:uppercase;\">VIEW ERROR LOG</a>" +
        "          </td></tr>" +
        "        </table>" +
        "      </td></tr>" +
        "      <tr><td style=\"background-color:" + COLOR_BG_BODY + "; border-top:1px solid " + COLOR_BORDER + "; padding:24px;\">" +
        "        <table width=\"100%\" cellpadding=\"8\" cellspacing=\"0\" border=\"0\">" +
        "          <tr>" +
        "            <td width=\"33%\" style=\"vertical-align:top;\">" +
        "              <div style=\"font-size:11px; color:" + COLOR_TEXT_MUTED + "; font-weight:bold; margin-bottom:4px;\">ERROR CODE</div>" +
        "              <div style=\"font-size:12px; color:" + COLOR_TEXT_MAIN + "; font-family:'Courier New', monospace;\">ERR_MIG_01</div>" +
        "            </td>" +
        "            <td width=\"33%\" style=\"vertical-align:top;\">" +
        "              <div style=\"font-size:11px; color:" + COLOR_TEXT_MUTED + "; font-weight:bold; margin-bottom:4px;\">SEVERITY</div>" +
        "              <div style=\"font-size:12px; color:" + COLOR_TEXT_MAIN + "; font-family:'Courier New', monospace;\">HIGH</div>" +
        "            </td>" +
        "            <td width=\"33%\" style=\"vertical-align:top;\">" +
        "              <div style=\"font-size:11px; color:" + COLOR_TEXT_MUTED + "; font-weight:bold; margin-bottom:4px;\">OPERATION ID</div>" +
        "              <div style=\"font-size:12px; color:" + COLOR_TEXT_MAIN + "; font-family:'Courier New', monospace;\">{{migration_id}}</div>" +
        "            </td>" +
        "          </tr>" +
        "          <tr>" +
        "            <td colspan=\"2\" style=\"vertical-align:top; padding-top:8px;\">" +
        "              <div style=\"font-size:11px; color:" + COLOR_TEXT_MUTED + "; font-weight:bold; margin-bottom:4px;\">ITEM TYPES</div>" +
        "              <div style=\"font-size:12px; color:" + COLOR_TEXT_MAIN + "; font-family:'Courier New', monospace;\">{{itemtypes}}</div>" +
        "            </td>" +
        "            <td style=\"vertical-align:top; padding-top:8px;\">" +
        "              <div style=\"font-size:11px; color:" + COLOR_TEXT_MUTED + "; font-weight:bold; margin-bottom:4px;\">ACTION REQUIRED</div>" +
        "              <div style=\"font-size:12px; color:" + COLOR_TEXT_MAIN + "; font-family:'Courier New', monospace;\">Manual review</div>" +
        "            </td>" +
        "          </tr>" +
        "        </table>" +
        "      </td></tr>" +
        "      <tr><td style=\"background-color:" + COLOR_FOOTER_BG + "; padding:20px; text-align:center;\">" +
        "        <div style=\"color:" + COLOR_FOOTER_TEXT + "; font-size:11px; letter-spacing:1px;\">CM MIGRATOR v6.3 — AUTOMATED SYSTEM NOTIFICATION</div>" +
        "      </td></tr>" +
        "    </table>" +
        "    <!--[if mso]></td></tr></table><![endif]-->" +
        "  </td></tr>" +
        "</table>" +
        "</body></html>";

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9]+\\.)+[a-zA-Z]{2,7}$"
    );

    private static String renderTemplate(String template, Map<String, String> context) {
        String result = template;
        for (Map.Entry<String, String> entry : context.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            if (result.contains(placeholder)) {
                // SPEZIALBEHANDLUNG: Bestimmte Felder enthalten bereits sicheres HTML
                if ("itemtypes".equals(entry.getKey()) || 
                    "error_context".equals(entry.getKey())) {
                    // NICHT escapen - enthält bereits sicheres HTML
                    result = result.replace(placeholder, entry.getValue());
                } else {
                    // Normal escapen für Sicherheit
                    result = result.replace(placeholder, escapeHtml(entry.getValue()));
                }
            }
        }
        return result;
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    /**
     * @deprecated Replaced by {@link ReportDeliveryService} unified pipeline.
     *             Kept for backward compatibility — delegates to new pipeline.
     */
    @Deprecated
    public static void sendReport(MigrationConfig config, String reportPath, 
                                   String operationMode, MigrationStats stats) {
        logger.warn("EmailNotifier.sendReport() is deprecated — use ReportDeliveryService.deliver() instead.");
        if (stats == null) {
            logger.warn("E-Mail kann nicht gesendet werden: MigrationStats ist null");
            return;
        }

        List<String> validAttachments = new ArrayList<>();
        if (reportPath != null && new File(reportPath).exists()) {
            validAttachments.add(reportPath);
        } else if (reportPath != null) {
            logger.warn("Report-Datei nicht gefunden: " + reportPath);
        }

        long durationMs = System.currentTimeMillis() - stats.getStartTime();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"));

        // Bestimme ob Migration oder Verification
        boolean isVerification = operationMode != null && 
            (operationMode.toUpperCase().contains("VERIFY") || 
             operationMode.toUpperCase().contains("VERIFICATION"));

        // Itemtypes aus Config holen und formatieren
        String itemTypes = getItemTypesDisplay(config);

        Map<String, String> ctx = new HashMap<>();
        ctx.put("timestamp", timestamp);
        ctx.put("source", config.getSourceSSID() != null ? config.getSourceSSID() : "SOURCE_DB");
        ctx.put("destination", config.getDestSSID() != null ? config.getDestSSID() : "TARGET_DB");
        ctx.put("report_path", reportPath != null ? new File(reportPath).getAbsolutePath() : "N/A");
        ctx.put("total_items", formatNumber(stats.getTotalItems()));
        ctx.put("processed_items", formatNumber(stats.getProcessedItems()));
        ctx.put("success_items", formatNumber(stats.getSuccessItems()));
        ctx.put("failed_items", formatNumber(stats.getFailedItems()));
        ctx.put("duration", formatDuration(Duration.ofMillis(durationMs)));
        ctx.put("speed", String.format("%.2f", calculateSpeed(stats.getProcessedItems(), durationMs)));
        ctx.put("success_rate", String.format("%.1f", calculateSuccessRate(stats)));
        ctx.put("migration_id", generateOperationId(stats.getStartTime(), isVerification));
        ctx.put("itemtypes", itemTypes);

        // Kontext für Fehlermeldungen
        if (stats.getFailedItems() > 0) {
            String errorMsg = String.format("Process encountered <strong style=\"color:%s;\">%s errors</strong> " +
                                           "with item types <strong style=\"color:%s;\">%s</strong>",
                                           COLOR_WARNING_MAIN, formatNumber(stats.getFailedItems()),
                                           COLOR_WARNING_MAIN, itemTypes);
            
            if (isVerification) {
                ctx.put("error_context", errorMsg + " during verification of <strong>" + ctx.get("destination") + "</strong>. Immediate review required.");
            } else {
                ctx.put("error_context", errorMsg + " during transfer from <strong>" + ctx.get("source") + 
                                         "</strong> to <strong>" + ctx.get("destination") + "</strong>. Immediate review required.");
            }
        } else {
            ctx.put("error_context", ""); // Fallback
        }

        boolean hasErrors = stats.getFailedItems() > 0;
        String template;
        String subject;

        if (hasErrors) {
            template = HTML_TEMPLATE_ERROR;
            subject = String.format("⚠️ FEHLER [%s] - %s Fehler bei %s Objekten (%s)",
                isVerification ? "VERIFICATION" : "MIGRATION",
                formatNumber(stats.getFailedItems()),
                formatNumber(stats.getProcessedItems()),
                itemTypes
            );
        } else {
            if (isVerification) {
                template = HTML_TEMPLATE_VERIFICATION_SUCCESS;
                subject = String.format("✅ ERFOLG [VERIFICATION] - %s Objekte (%s) verifiziert in %s",
                    formatNumber(stats.getSuccessItems()),
                    itemTypes,
                    config.getDestSSID() != null ? config.getDestSSID() : "TARGET_DB"
                );
            } else {
                template = HTML_TEMPLATE_MIGRATION_SUCCESS;
                subject = String.format("✅ ERFOLG [MIGRATION] - %s Objekte (%s) von %s nach %s",
                    formatNumber(stats.getSuccessItems()),
                    itemTypes,
                    config.getSourceSSID() != null ? config.getSourceSSID() : "SOURCE",
                    config.getDestSSID() != null ? config.getDestSSID() : "TARGET"
                );
            }
        }

        String htmlBody = renderTemplate(template, ctx);

        // DEBUG: Dump HTML to file for verification
        try {
            File debugDir = new File("debug_mail");
            if (!debugDir.exists()) debugDir.mkdirs();
            String fileName = "debug_mail/mail_" + (isVerification ? "verify" : "migrate") + "_" + 
                             (hasErrors ? "error" : "success") + "_" + System.currentTimeMillis() + ".html";
            try (FileOutputStream fos = new FileOutputStream(fileName)) {
                fos.write(htmlBody.getBytes(StandardCharsets.UTF_8));
            }
            logger.info("DEBUG: Rendered HTML dumped to " + fileName);
        } catch (Exception e) {
            logger.warn("Could not dump debug HTML: " + e.getMessage());
        }

        String emailTo = config.getEmailTo();
        if (emailTo == null || emailTo.trim().isEmpty()) {
            logger.info("E-Mail-Benachrichtigung deaktiviert (EMAIL_TO nicht konfiguriert)");
            return;
        }
        if (!isValidEmail(emailTo)) {
            logger.error("Ungültige E-Mail-Adresse: " + emailTo);
            return;
        }

        sendEmail(emailTo, subject, htmlBody, validAttachments);
    }

    /**
     * Extrahiert und formatiert Itemtypes aus der Config.
     * Beispiel: "DmDocument, DmFolder" oder "ALL"
     */
    private static String getItemTypesDisplay(MigrationConfig config) {
        Map<String, String> mapping = config.getItemTypeMapping();
        
        if (mapping == null || mapping.isEmpty()) {
            return "ALL";
        }
        
        List<String> mappingList = new ArrayList<>();
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String source = entry.getKey();
            String dest = entry.getValue();
            
            if (source.equals(dest)) {
                mappingList.add(source);
            } else {
                mappingList.add(source + " → " + dest);
            }
        }
        
        if (mappingList.size() > 3) {
            List<String> first3 = mappingList.subList(0, 3);
            int remaining = mappingList.size() - 3;
            return String.join("<br>", first3) + "<br>... (" + remaining + " more)";
        }
        
        return String.join("<br>", mappingList);
    }

    private static void sendEmail(String to, String subject, String htmlBody, List<String> attachments) {
        try {
            String mailCommand = detectMailCommand();
            if (mailCommand == null) {
                logger.error("Weder mutt noch mailx gefunden. E-Mail kann nicht gesendet werden.");
                return;
            }

            List<String> command = new ArrayList<>();
            if ("mutt".equals(mailCommand)) {
                command.add("mutt");
                command.add("-e");
                command.add("set content_type=text/html");
                command.add("-s");
                command.add(subject);
                for (String attachment : attachments) {
                    command.add("-a");
                    command.add(attachment);
                }
                command.add("--");
                command.add(to);
            } else {
                command.add("mailx");
                command.add("-a");
                command.add("Content-Type: text/html; charset=UTF-8");
                command.add("-s");
                command.add(subject);
                command.add(to);
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectInput(ProcessBuilder.Redirect.PIPE);
            Process process = pb.start();

            try (OutputStreamWriter writer = new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(htmlBody);
                writer.flush();
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                logger.info("✅ E-Mail erfolgreich gesendet an: " + to);
            } else {
                String errorOutput = getProcessErrorOutput(process);
                logger.error("❌ E-Mail-Versand fehlgeschlagen. Exit Code: " + exitCode + ". Fehler: " + errorOutput);
            }
        } catch (Exception e) {
            logger.error("Fehler beim Senden der E-Mail", e);
        }
    }

    private static String detectMailCommand() {
        if (commandExists("mutt")) return "mutt";
        if (commandExists("mailx")) return "mailx";
        return null;
    }

    private static boolean commandExists(String command) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            String[] checkCmd;
            if (os.contains("win")) {
                checkCmd = new String[]{"cmd", "/c", "where", command};
            } else {
                checkCmd = new String[]{"which", command};
            }
            Process process = Runtime.getRuntime().exec(checkCmd);
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String getProcessErrorOutput(Process process) {
        StringBuilder errorOutput = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                errorOutput.append(line).append("\n");
            }
        } catch (Exception e) {
            logger.warn("Konnte Fehlerausgabe nicht lesen", e);
        }
        return errorOutput.toString().trim();
    }

    private static double calculateSpeed(long processed, long elapsedMillis) {
        if (elapsedMillis <= 0) return 0.0;
        return processed / (elapsedMillis / 1000.0);
    }

    private static double calculateSuccessRate(MigrationStats stats) {
        long total = stats.getTotalItems();
        if (total == 0) return 0.0;
        return ((double) stats.getSuccessItems() / total) * 100.0;
    }

    private static String formatDuration(Duration duration) {
        long totalSeconds = duration.getSeconds();
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) return String.format("%dh %dm", hours, minutes);
        if (minutes > 0) return String.format("%dm %ds", minutes, seconds);
        return String.format("%ds", seconds);
    }

    private static String formatNumber(long number) {
        return String.format("%,d", number).replace(',', '.');
    }

    private static String generateOperationId(long startTime, boolean isVerification) {
        LocalDateTime date = LocalDateTime.ofEpochSecond(startTime / 1000, 0, 
            java.time.ZoneOffset.systemDefault().getRules().getOffset(java.time.Instant.now()));
        String prefix = isVerification ? "VER" : "MIG";
        return String.format("%s_%d_%03d", prefix, date.getYear(), date.getDayOfYear());
    }

    private static boolean isValidEmail(String emailInput) {
        if (emailInput == null || emailInput.trim().isEmpty()) return false;
        String[] emails = emailInput.split("[,;]");
        for (String email : emails) {
            String trimmed = email.trim();
            if (trimmed.isEmpty()) continue;
            if (!EMAIL_PATTERN.matcher(trimmed).matches()) {
                logger.warn("Ungültiges E-Mail-Format erkannt: " + trimmed);
                return false;
            }
        }
        return true;
    }
}
