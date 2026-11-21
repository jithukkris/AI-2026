package com.healpay.ai;

import com.healpay.domain.HealingAction;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Optional;

/**
 * Deterministic "mock AI" - lets the whole system run with no API key, and acts as
 * the safety fallback if the Claude API is unreachable.
 *
 * It is also used directly by {@link ClaudeDiagnosisService} on failure, so it is
 * always registered (not conditional), but only becomes the primary
 * {@link DiagnosisService} when app.ai.provider=mock.
 */
@Service
public class RuleBasedDiagnosisService {

    public AiDiagnosis diagnose(DiagnosisContext c) {
        DiagnosisContext.TxnInfo t = c.transaction();
        return switch (c.type()) {
            case STATUS_MISMATCH -> statusMismatch(c, t);
            case DUPLICATE_DEBIT -> new AiDiagnosis(
                    "Duplicate capture (customer retried / double tap)",
                    "The gateway captured " + c.gatewayRecords().stream().filter(g -> "SUCCESS".equals(g.status())).count()
                            + " successful payments for one order " + t.txnRef() + ". This usually happens when the customer "
                            + "retries after a slow response. The extra capture of " + c.amountImpact()
                            + " INR should be refunded to the customer.",
                    HealingAction.REFUND_DUPLICATE.name(), 0.95, null);
            case FEE_VARIANCE -> new AiDiagnosis(
                    "Gateway fee differs from contract (likely GST on MDR)",
                    "The gateway deducted a fee that differs from the contracted rate by " + c.amountImpact()
                            + " INR. A variance of about 18% of the fee typically means GST was applied on the MDR. "
                            + "Posting a fee adjustment entry keeps the ledger balanced while finance reviews the contract.",
                    HealingAction.POST_FEE_ADJUSTMENT.name(), 0.88, null);
            case SETTLEMENT_DELAYED -> new AiDiagnosis(
                    "Settlement still inside T+1 window",
                    "The payment was captured " + c.hoursSinceCapture() + " hour(s) ago and the bank credit has not "
                            + "arrived yet. This is normal settlement lag, so the safest action is to wait and re-check.",
                    HealingAction.RECHECK_LATER.name(), 0.9, null);
            case MISSING_IN_BANK -> missingInBank(c, t);
            case MISSING_IN_GATEWAY -> new AiDiagnosis(
                    "Ledger success without gateway capture",
                    "Our ledger marked " + t.txnRef() + " as successful, but the gateway has no record. This may be a "
                            + "bug in the success callback or a manual entry. It needs investigation by operations.",
                    HealingAction.ESCALATE.name(), 0.7, null);
            case AMOUNT_MISMATCH -> new AiDiagnosis(
                    "Amount mismatch between systems",
                    "The amounts recorded by two systems differ by " + c.amountImpact() + " INR. Partial captures or "
                            + "currency conversion can cause this; it is not safe to auto-correct amounts.",
                    HealingAction.ESCALATE.name(), 0.6, null);
            case ORPHAN_BANK_CREDIT -> new AiDiagnosis(
                    "Unidentified bank credit",
                    "Money arrived in the bank account that cannot be traced to a gateway settlement. If no "
                            + "transaction claims it after the next cycle, operations should contact the bank.",
                    HealingAction.ESCALATE.name(), 0.6, null);
        };
    }

    private AiDiagnosis statusMismatch(DiagnosisContext c, DiagnosisContext.TxnInfo t) {
        boolean ledgerSaysSuccess = "SUCCESS".equals(t.ledgerStatus());
        if (ledgerSaysSuccess) {
            return new AiDiagnosis(
                    "Ledger marked success, gateway failed",
                    "Our ledger recorded " + t.txnRef() + " as successful, but the gateway reports the payment failed, "
                            + "so no money was received. The ledger success entry of " + t.amount()
                            + " INR should be reversed so the merchant is not credited for money that never arrived.",
                    HealingAction.REVERSE_LEDGER_ENTRY.name(), 0.9, null);
        }
        return new AiDiagnosis(
                "Missed gateway webhook",
                "The gateway captured the payment successfully, but our ledger still shows " + t.ledgerStatus()
                        + ". This is the classic sign of a missed or failed success webhook. Syncing the status from "
                        + "the gateway (the source of truth for capture) fixes it without moving money.",
                HealingAction.SYNC_STATUS_FROM_GATEWAY.name(), 0.93, null);
    }

    private AiDiagnosis missingInBank(DiagnosisContext c, DiagnosisContext.TxnInfo t) {
        Optional<DiagnosisContext.BankCandidate> best = c.bankCandidates().stream()
                .filter(DiagnosisContext.BankCandidate::amountMatches)
                .max(Comparator.comparingDouble(DiagnosisContext.BankCandidate::referenceSimilarity));
        if (best.isPresent() && best.get().referenceSimilarity() >= 0.4) {
            DiagnosisContext.BankCandidate b = best.get();
            double confidence = Math.min(0.99, 0.5 + 0.5 * b.referenceSimilarity());
            return new AiDiagnosis(
                    "Bank credit with truncated / missing UTR",
                    "No bank credit carries the expected UTR, but bank credit #" + b.bankRecordId() + " has exactly the "
                            + "expected net amount and its narration \"" + b.narration() + "\" partially matches the "
                            + "payment reference (similarity " + Math.round(b.referenceSimilarity() * 100) + "%). "
                            + "Banks often truncate references, so linking the two is very likely correct.",
                    HealingAction.LINK_BANK_RECORD.name(), confidence, b.bankRecordId());
        }
        return new AiDiagnosis(
                "Settlement missing beyond T+1",
                "The payment was captured " + c.hoursSinceCapture() + " hours ago and no matching bank credit exists. "
                        + "The settlement may have failed or been batched under a different reference.",
                HealingAction.ESCALATE.name(), 0.65, null);
    }
}
