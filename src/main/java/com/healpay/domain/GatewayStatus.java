package com.healpay.domain;

/** Status of a payment as reported by the payment gateway (source of truth for capture). */
public enum GatewayStatus {
    PENDING,
    SUCCESS,
    FAILED,
    REFUNDED
}
