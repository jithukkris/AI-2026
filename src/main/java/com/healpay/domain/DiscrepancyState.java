package com.healpay.domain;

import java.util.EnumSet;
import java.util.Set;

/** Lifecycle of a detected discrepancy (an "exception" in ops language). */
public enum DiscrepancyState {
    OPEN,
    WAITING,
    AWAITING_APPROVAL,
    ESCALATED,
    RESOLVED,
    REJECTED;

    public static final Set<DiscrepancyState> ACTIVE = EnumSet.of(OPEN, WAITING, AWAITING_APPROVAL, ESCALATED);

    public boolean isActive() {
        return ACTIVE.contains(this);
    }
}
