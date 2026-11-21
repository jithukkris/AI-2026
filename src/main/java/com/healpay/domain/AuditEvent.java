package com.healpay.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Append-only audit trail. Every stage of a transaction and every decision the
 * system makes (detect, diagnose, heal, approve, verify) is written here.
 * It also powers the per-transaction status timeline and the live feed.
 */
@Entity
@Table(name = "audit_event", indexes = @Index(name = "idx_audit_txn", columnList = "txn_ref"))
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "txn_ref", length = 40)
    private String txnRef;

    private Long discrepancyId;

    /** e.g. INITIATED, CAPTURED, SETTLED, DETECTED, DIAGNOSED, AUTO_HEALED, APPROVAL_REQUESTED, VERIFIED. */
    @Column(nullable = false, length = 40)
    private String stage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Actor actor;

    @Column(nullable = false, length = 1000)
    private String message;

    @Column(nullable = false)
    private Instant occurredAt;

    protected AuditEvent() {
    }

    public AuditEvent(String txnRef, Long discrepancyId, String stage, Actor actor, String message, Instant occurredAt) {
        this.txnRef = txnRef;
        this.discrepancyId = discrepancyId;
        this.stage = stage;
        this.actor = actor;
        this.message = Discrepancy.truncate(message, 1000);
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public String getTxnRef() { return txnRef; }
    public Long getDiscrepancyId() { return discrepancyId; }
    public String getStage() { return stage; }
    public Actor getActor() { return actor; }
    public String getMessage() { return message; }
    public Instant getOccurredAt() { return occurredAt; }
}
