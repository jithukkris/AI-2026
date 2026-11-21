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
 * A detected mismatch plus everything the AI and the healing engine decided about it.
 * This is the "exception" an ops person would otherwise handle by hand.
 */
@Entity
@Table(name = "discrepancy")
public class Discrepancy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null for orphan bank credits. */
    @Column(length = 40)
    private String txnRef;

    /** Set for orphan bank credits. */
    private Long bankRecordId;

    @Enumerated(EnumType.STRING)
    @Column(name = "discrepancy_type", nullable = false, length = 30)
    private DiscrepancyType type;

    @Column(nullable = false, length = 500)
    private String detail;

    /** Money at risk if this is not fixed. */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amountImpact = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DiscrepancyState state = DiscrepancyState.OPEN;

    // ---- AI diagnosis ----
    @Column(length = 200)
    private String aiRootCause;

    @Column(length = 2000)
    private String aiExplanation;

    private Double aiConfidence;

    /** "claude", "rules" or "rules (AI fallback)". */
    @Column(length = 40)
    private String aiSource;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private HealingAction proposedAction;

    /** For LINK_BANK_RECORD: which bank credit the AI wants to link. */
    private Long proposedBankRecordId;

    /** Why the policy engine chose auto / approval / escalate. */
    @Column(length = 300)
    private String policyReason;

    // ---- outcome ----
    @Column(length = 300)
    private String resolution;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Actor resolvedBy;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant resolvedAt;

    protected Discrepancy() {
    }

    public Discrepancy(String txnRef, Long bankRecordId, DiscrepancyType type, String detail, BigDecimal amountImpact) {
        this.txnRef = txnRef;
        this.bankRecordId = bankRecordId;
        this.type = type;
        this.detail = truncate(detail, 500);
        this.amountImpact = amountImpact == null ? BigDecimal.ZERO : amountImpact;
        this.createdAt = Instant.now();
    }

    public void resolve(String resolution, Actor by) {
        this.state = DiscrepancyState.RESOLVED;
        this.resolution = truncate(resolution, 300);
        this.resolvedBy = by;
        this.resolvedAt = Instant.now();
    }

    public void reject(String resolution) {
        this.state = DiscrepancyState.REJECTED;
        this.resolution = truncate(resolution, 300);
        this.resolvedBy = Actor.USER;
        this.resolvedAt = Instant.now();
    }

    public static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    public Long getId() { return id; }
    public String getTxnRef() { return txnRef; }
    public Long getBankRecordId() { return bankRecordId; }
    public DiscrepancyType getType() { return type; }
    public String getDetail() { return detail; }
    public BigDecimal getAmountImpact() { return amountImpact; }
    public DiscrepancyState getState() { return state; }
    public String getAiRootCause() { return aiRootCause; }
    public String getAiExplanation() { return aiExplanation; }
    public Double getAiConfidence() { return aiConfidence; }
    public String getAiSource() { return aiSource; }
    public HealingAction getProposedAction() { return proposedAction; }
    public Long getProposedBankRecordId() { return proposedBankRecordId; }
    public String getPolicyReason() { return policyReason; }
    public String getResolution() { return resolution; }
    public Actor getResolvedBy() { return resolvedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getResolvedAt() { return resolvedAt; }

    public void setDetail(String detail) { this.detail = truncate(detail, 500); }
    public void setAmountImpact(BigDecimal amountImpact) { this.amountImpact = amountImpact; }
    public void setState(DiscrepancyState state) { this.state = state; }
    public void setAiRootCause(String aiRootCause) { this.aiRootCause = truncate(aiRootCause, 200); }
    public void setAiExplanation(String aiExplanation) { this.aiExplanation = truncate(aiExplanation, 2000); }
    public void setAiConfidence(Double aiConfidence) { this.aiConfidence = aiConfidence; }
    public void setAiSource(String aiSource) { this.aiSource = aiSource; }
    public void setProposedAction(HealingAction proposedAction) { this.proposedAction = proposedAction; }
    public void setProposedBankRecordId(Long proposedBankRecordId) { this.proposedBankRecordId = proposedBankRecordId; }
    public void setPolicyReason(String policyReason) { this.policyReason = truncate(policyReason, 300); }
}
