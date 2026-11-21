package com.healpay.domain;

/** Reconciliation status of a ledger transaction, shown to users in the UI. */
public enum ReconStatus {
    UNRECONCILED,
    MATCHED,
    MISMATCH,
    SETTLEMENT_PENDING,
    AWAITING_APPROVAL,
    MANUAL_REVIEW,
    HEALED
}
