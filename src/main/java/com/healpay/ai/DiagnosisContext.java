package com.healpay.ai;

import com.healpay.domain.DiscrepancyType;
import com.healpay.domain.HealingAction;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Everything the AI needs to diagnose one discrepancy, gathered up-front by Java code.
 * (The Claude implementation can additionally call tools to look up more data.)
 */
public record DiagnosisContext(
        Long discrepancyId,
        DiscrepancyType type,
        String detail,
        BigDecimal amountImpact,
        TxnInfo transaction,
        List<GatewayInfo> gatewayRecords,
        BankInfo matchedBankCredit,
        List<BankCandidate> bankCandidates,
        BigDecimal expectedFee,
        Long hoursSinceCapture,
        Set<HealingAction> allowedActions) {

    public record TxnInfo(String txnRef, String merchant, String paymentMode, BigDecimal amount,
                          String ledgerStatus, String gatewayRef, BigDecimal feeAdjustment) {}

    public record GatewayInfo(String gatewayRef, BigDecimal amount, BigDecimal fee, String status,
                              String utr, String capturedAt) {}

    public record BankInfo(Long id, String utr, BigDecimal amount, String narration, String valueDate) {}

    /** An unlinked bank credit that might belong to this transaction, with a fuzzy-match score 0..1. */
    public record BankCandidate(Long bankRecordId, BigDecimal amount, String narration, String valueDate,
                                double referenceSimilarity, boolean amountMatches) {}

    /** Compact plain-text rendering used inside the LLM prompt. */
    public String toPromptText() {
        StringBuilder sb = new StringBuilder();
        sb.append("DISCREPANCY #").append(discrepancyId).append('\n')
          .append("type: ").append(type).append(" - ").append(type.getLabel()).append('\n')
          .append("detail: ").append(detail).append('\n')
          .append("amount_at_risk_inr: ").append(amountImpact).append('\n');
        if (transaction != null) {
            sb.append("\nLEDGER TRANSACTION\n").append(transaction).append('\n');
            sb.append("contracted_fee_inr: ").append(expectedFee).append('\n');
        }
        if (gatewayRecords != null && !gatewayRecords.isEmpty()) {
            sb.append("\nGATEWAY RECORDS\n");
            gatewayRecords.forEach(g -> sb.append("- ").append(g).append('\n'));
        }
        if (hoursSinceCapture != null) {
            sb.append("hours_since_capture: ").append(hoursSinceCapture).append('\n');
        }
        sb.append("\nMATCHED BANK CREDIT\n").append(matchedBankCredit == null ? "none" : matchedBankCredit).append('\n');
        if (bankCandidates != null && !bankCandidates.isEmpty()) {
            sb.append("\nUNLINKED BANK CREDIT CANDIDATES (similarity computed by code)\n");
            bankCandidates.forEach(c -> sb.append("- ").append(c).append('\n'));
        }
        sb.append("\nALLOWED ACTIONS: ").append(allowedActions).append('\n');
        return sb.toString();
    }
}
