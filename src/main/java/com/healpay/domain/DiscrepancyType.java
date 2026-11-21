package com.healpay.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Kinds of mismatch the deterministic reconciliation engine can detect.
 * Each type carries the set of healing actions that are ever allowed for it -
 * this is a guardrail: the AI can only choose from this list.
 */
public enum DiscrepancyType {
    STATUS_MISMATCH("Ledger and gateway disagree on payment status",
            EnumSet.of(HealingAction.SYNC_STATUS_FROM_GATEWAY, HealingAction.REVERSE_LEDGER_ENTRY, HealingAction.ESCALATE)),
    DUPLICATE_DEBIT("Customer was charged more than once",
            EnumSet.of(HealingAction.REFUND_DUPLICATE, HealingAction.ESCALATE)),
    FEE_VARIANCE("Gateway fee differs from contracted rate",
            EnumSet.of(HealingAction.POST_FEE_ADJUSTMENT, HealingAction.ESCALATE)),
    SETTLEMENT_DELAYED("Captured payment not yet settled in bank (within lag window)",
            EnumSet.of(HealingAction.RECHECK_LATER, HealingAction.ESCALATE)),
    MISSING_IN_BANK("Captured payment has no matching bank credit",
            EnumSet.of(HealingAction.LINK_BANK_RECORD, HealingAction.RECHECK_LATER, HealingAction.ESCALATE)),
    MISSING_IN_GATEWAY("Ledger shows success but gateway has no record",
            EnumSet.of(HealingAction.ESCALATE)),
    AMOUNT_MISMATCH("Amounts differ between systems",
            EnumSet.of(HealingAction.ESCALATE)),
    ORPHAN_BANK_CREDIT("Bank credit with no known payment",
            EnumSet.of(HealingAction.ESCALATE));

    private final String label;
    private final Set<HealingAction> allowedActions;

    DiscrepancyType(String label, Set<HealingAction> allowedActions) {
        this.label = label;
        this.allowedActions = allowedActions;
    }

    public String getLabel() {
        return label;
    }

    public Set<HealingAction> getAllowedActions() {
        return allowedActions;
    }
}
