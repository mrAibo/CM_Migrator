package com.example.migrator.worker;

import com.example.migrator.connection.ConnectionManager;
import com.example.migrator.journal.MigrationJournal;
import com.ibm.mm.sdk.common.*;
import com.ibm.mm.sdk.server.DKDatastoreICM;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Worker-Thread, der Items aus der Queue nimmt und via CM API migriert.
 */
public class ApiItemWorker implements Runnable {
    private static final Logger logger = LogManager.getLogger(ApiItemWorker.class);

    private final BlockingQueue<String> queue;
    private final MigrationJournal journal;
    private final String targetItemType;
    private final String mode;
    private final boolean dryRun;
    private volatile boolean running = true;

    public ApiItemWorker(BlockingQueue<String> queue, MigrationJournal journal, String targetItemType) {
        this.queue = queue;
        this.journal = journal;
        this.targetItemType = targetItemType;
        this.mode = com.example.migrator.config.ConfigManager.get("process.mode", "COPY").toUpperCase();
        this.dryRun = com.example.migrator.config.ConfigManager.getBoolean("process.delete.dryrun", true);
    }

    public void stop() {
        this.running = false;
    }

    @Override
    public void run() {
        dkDatastore dsSource = null;
        dkDatastore dsTarget = null;

        try {
            // Verbindungen holen
            dsSource = ConnectionManager.getSourceConnection();
            // Ziel-Verbindung nur nötig für COPY und MOVE
            if (!"DELETE".equals(mode)) {
                dsTarget = ConnectionManager.getTargetConnection();
            }
            
            DKDatastoreICM dsICMSource = (DKDatastoreICM) dsSource;
            DKDatastoreICM dsICMTarget = (DKDatastoreICM) dsTarget;

            while (running || !queue.isEmpty()) {
                String pid = queue.poll(1, TimeUnit.SECONDS);
                if (pid == null) continue;

                try {
                    processItem(dsICMSource, dsICMTarget, pid);
                } catch (Exception e) {
                    logger.error("Fehler bei Verarbeitung von PID " + pid, e);
                    try {
                        journal.markFailed(pid, e.getMessage());
                    } catch (Exception je) {
                        logger.error("Konnte Fehlerstatus für PID " + pid + " nicht schreiben", je);
                    }
                }
            }

        } catch (Exception e) {
            logger.error("Worker Thread abgestürzt", e);
        } finally {
            ConnectionManager.returnSourceConnection(dsSource);
            ConnectionManager.returnTargetConnection(dsTarget);
        }
    }

    private void processItem(DKDatastoreICM dsSource, DKDatastoreICM dsTarget, String pid) throws Exception {
        journal.markInProgress(pid);

        // 1. DELETE Modus
        if ("DELETE".equals(mode)) {
            if (dryRun) {
                logger.info("[DRY-RUN] Würde löschen: " + pid);
                journal.markCompleted(pid, "DELETED_DRY_RUN", null);
            } else {
                DKDDO item = dsSource.createDDO(pid);
                item.del();
                logger.info("Gelöscht: " + pid);
                journal.markCompleted(pid, "DELETED", null);
            }
            return;
        }

        // 2. COPY / MOVE Modus
        // Quell-Item laden (Nur Metadaten)
        DKDDO sourceItem = dsSource.createDDO(pid);
        sourceItem.retrieve(DKConstant.DK_CM_CONTENT_ATTRONLY | DKConstant.DK_CM_CONTENT_ITEMTREE);

        if (!(sourceItem instanceof DKLobICM)) {
            throw new UnsupportedOperationException("Nur LOB-Items unterstützt. PID: " + pid);
        }
        DKLobICM sourceLob = (DKLobICM) sourceItem;

        // Ziel-Item erstellen
        DKLobICM targetLob = (DKLobICM) dsTarget.createDDO(targetItemType, DKConstant.DK_CM_DOCUMENT);
        targetLob.setMimeType(sourceLob.getMimeType());
        targetLob.setOrgFileName(sourceLob.getOrgFileName());
        
        // Content streamen und Hashen
        String checksum;
        long size = sourceLob.getSize();
        
        try (InputStream sourceStream = sourceLob.getInputStream(DKConstant.DK_CM_CONTENT_YES)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream dis = new DigestInputStream(sourceStream, digest)) {
                targetLob.add(dis, size);
                byte[] hashBytes = digest.digest();
                checksum = bytesToHex(hashBytes);
            }
        }

        // Speichern im Ziel
        targetLob.add();
        String targetPid = targetLob.getPidObject().pidString();

        // MOVE: Quelle löschen
        if ("MOVE".equals(mode)) {
            if (dryRun) {
                logger.info("[DRY-RUN] Würde Quelle löschen nach Move: " + pid);
            } else {
                sourceItem.del();
                logger.info("Quelle gelöscht (Move): " + pid);
            }
        }

        // Journal aktualisieren
        journal.markCompleted(pid, targetPid, checksum);
        logger.info(mode + ": " + pid + " -> " + targetPid + " [SHA-256: " + checksum + "]");
    }

    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
