package com.healpay.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A payment as reported by the payment gateway (e.g. Razorpay / Stripe / PayU).
 * In this project the gateway is simulated, but the shape mirrors a real settlement report.
 */
@Entity
@Table(name = "gateway_record")
public class GatewayRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String gatewayRef;

    @Column(length = 40)
    private String txnRef;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal fee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GatewayStatus status;

    /** Bank reference (UTR) of the settlement credit. */
    @Column(length = 40)
    private String utr;

    @Column(nullable = false)
    private Instant capturedAt;

    /** True once the settlement has been sent to the bank. */
    @Column(nullable = false)
    private boolean settled;

    protected GatewayRecord() {
    }

    public GatewayRecord(String gatewayRef, String txnRef, BigDecimal amount, BigDecimal fee,
                         GatewayStatus status, String utr, Instant capturedAt, boolean settled) {
        this.gatewayRef = gatewayRef;
        this.txnRef = txnRef;
        this.amount = amount;
        this.fee = fee;
        this.status = status;
        this.utr = utr;
        this.capturedAt = capturedAt;
        this.settled = settled;
    }

    /** Net amount the bank should receive. */
    public BigDecimal netAmount() {
        return amount.subtract(fee);
    }

    public Long getId() { return id; }
    public String getGatewayRef() { return gatewayRef; }
    public String getTxnRef() { return txnRef; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getFee() { return fee; }
    public GatewayStatus getStatus() { return status; }
    public String getUtr() { return utr; }
    public Instant getCapturedAt() { return capturedAt; }
    public boolean isSettled() { return settled; }

    public void setStatus(GatewayStatus status) { this.status = status; }
    public void setSettled(boolean settled) { this.settled = settled; }
}
