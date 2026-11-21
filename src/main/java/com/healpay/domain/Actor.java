package com.healpay.domain;

/** Who performed a step in the audit trail. */
public enum Actor {
    SYSTEM,
    AI,
    USER,
    GATEWAY,
    BANK
}
