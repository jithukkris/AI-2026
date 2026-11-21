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

/** A payment as recorded in our internal ledger. */
@Entity
@Table(name = "ledger_txn")
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String txnRef;

    @Column(nullable = false, length = 80)
    private String merchant;

    @Column(length = 20)
    private String paymentMode;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LedgerStatus ledgerStatus;

    @Column(length = 40)
    private String gatewayRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReconStatus reconStatus = ReconStatus.UNRECONCILED;

    /** Sum of fee adjustment entries posted by healing (accepted fee difference). */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal feeAdjustment = BigDecimal.ZERO;

    /** Simulator scenario label - useful for demos and tests, null for real data. */
    @Column(length = 40)
    private String scenario;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected LedgerTransaction() {
    }

    public LedgerTransaction(String txnRef, String merchant, String paymentMode, BigDecimal amount,
                             LedgerStatus ledgerStatus, String gatewayRef, Instant createdAt) {
        this.txnRef = txnRef;
        this.merchant = merchant;
        this.paymentMode = paymentMode;
        this.amount = amount;
        this.ledgerStatus = ledgerStatus;
        this.gatewayRef = gatewayRef;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getTxnRef() { return txnRef; }
    public String getMerchant() { return merchant; }
    public String getPaymentMode() { return paymentMode; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public LedgerStatus getLedgerStatus() { return ledgerStatus; }
    public String getGatewayRef() { return gatewayRef; }
    public ReconStatus getReconStatus() { return reconStatus; }
    public BigDecimal getFeeAdjustment() { return feeAdjustment; }
    public String getScenario() { return scenario; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setLedgerStatus(LedgerStatus ledgerStatus) { this.ledgerStatus = ledgerStatus; touch(); }
    public void setReconStatus(ReconStatus reconStatus) { this.reconStatus = reconStatus; }
    public void setFeeAdjustment(BigDecimal feeAdjustment) { this.feeAdjustment = feeAdjustment; touch(); }
    public void setScenario(String scenario) { this.scenario = scenario; }
}
