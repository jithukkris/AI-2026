package com.healpay.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** A credit line from the bank statement (settlement from the gateway). */
@Entity
@Table(name = "bank_record")
public class BankRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** UTR may be missing or mangled in real bank files - that is what fuzzy matching is for. */
    @Column(length = 40)
    private String utr;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 160)
    private String narration;

    @Column(nullable = false)
    private LocalDate valueDate;

    /** Set when a healing action links this credit to a ledger transaction. */
    @Column(length = 40)
    private String linkedTxnRef;

    @Column(nullable = false)
    private Instant receivedAt;

    protected BankRecord() {
    }

    public BankRecord(String utr, BigDecimal amount, String narration, LocalDate valueDate, Instant receivedAt) {
        this.utr = utr;
        this.amount = amount;
        this.narration = narration;
        this.valueDate = valueDate;
        this.receivedAt = receivedAt;
    }

    public Long getId() { return id; }
    public String getUtr() { return utr; }
    public BigDecimal getAmount() { return amount; }
    public String getNarration() { return narration; }
    public LocalDate getValueDate() { return valueDate; }
    public String getLinkedTxnRef() { return linkedTxnRef; }
    public Instant getReceivedAt() { return receivedAt; }

    public void setLinkedTxnRef(String linkedTxnRef) { this.linkedTxnRef = linkedTxnRef; }
}
