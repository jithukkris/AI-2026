package com.healpay.domain;

/**
 * The closed set of fixes the system knows how to perform.
 * {@code movesMoney} actions always require human approval, whatever the AI says.
 */
public enum HealingAction {
    SYNC_STATUS_FROM_GATEWAY("Sync ledger status from gateway", false),
    RECHECK_LATER("Wait for settlement and re-check", false),
    POST_FEE_ADJUSTMENT("Post fee adjustment entry", false),
    LINK_BANK_RECORD("Link unmatched bank credit", false),
    REFUND_DUPLICATE("Refund duplicate charge", true),
    REVERSE_LEDGER_ENTRY("Reverse ledger success entry", true),
    ESCALATE("Escalate to operations team", false);

    private final String label;
    private final boolean movesMoney;

    HealingAction(String label, boolean movesMoney) {
        this.label = label;
        this.movesMoney = movesMoney;
    }

    public String getLabel() {
        return label;
    }

    public boolean isMovesMoney() {
        return movesMoney;
    }

    /** Lenient parse for AI output ("sync_status_from_gateway", " LINK_BANK_RECORD "). */
    public static HealingAction parse(String value) {
        if (value == null) {
            return null;
        }
        String norm = value.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        for (HealingAction a : values()) {
            if (a.name().equals(norm)) {
                return a;
            }
        }
        return null;
    }
}
