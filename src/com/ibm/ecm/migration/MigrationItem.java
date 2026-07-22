/*
 * Projekt: CM Migrator 2.2.1.
 * @Author: Aleksej Voronin, Sven Lindt
 * @Date:   26.01.2026
 */
package com.ibm.ecm.migration;

/**
 * Stellt ein zu migrierendes Element dar.
 */
public class MigrationItem {
    public static final MigrationItem POISON_PILL = new MigrationItem("POISON", "POISON", "POISON");

    private final String itemId;
    private final String sourceItemType;
    private final String destItemType;
    private String checksum;
    private String destItemId;

    public MigrationItem(String itemId, String sourceItemType, String destItemType) {
        this.itemId = itemId;
        this.sourceItemType = sourceItemType;
        this.destItemType = destItemType;
    }

    public String getItemId() {
        return itemId;
    }

    public String getSourceItemType() {
        return sourceItemType;
    }

    public String getDestItemType() {
        return destItemType;
    }
    
    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public String getDestItemId() {
        return destItemId;
    }

    public void setDestItemId(String destItemId) {
        this.destItemId = destItemId;
    }
    
    public boolean isPoisonPill() {
        return this == POISON_PILL;
    }
}
