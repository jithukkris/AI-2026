package com.healpay.domain;

/** Status of a payment in our own ledger (what our system believes). */
public enum LedgerStatus {
    INITIATED,
    PENDING,
    SUCCESS,
    FAILED
}
