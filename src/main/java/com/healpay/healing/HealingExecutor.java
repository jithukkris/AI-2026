package com.healpay.healing;

import com.healpay.domain.Actor;
import com.healpay.domain.BankRecord;
import com.healpay.domain.Discrepancy;
import com.healpay.domain.DiscrepancyState;
import com.healpay.domain.DiscrepancyType;
import com.healpay.domain.GatewayRecord;
import com.healpay.domain.GatewayStatus;
import com.healpay.domain.HealingAction;
import com.healpay.domain.LedgerStatus;
import com.healpay.domain.LedgerTransaction;
import com.healpay.events.AuditService;
import com.healpay.recon.ReconciliationEngine;
import com.healpay.repo.BankRecordRepository;
import com.healpay.repo.DiscrepancyRepository;
import com.healpay.repo.GatewayRecordRepository;
import com.healpay.repo.LedgerTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Performs healing actions. Every action:
 *  1. re-validates its preconditions (never trusts the AI blindly),
 *  2. is idempotent (re-running it changes nothing),
 *  3. writes to the audit trail,
 *  4. is followed by a verification re-reconcile ("did it actually balance?").
 */
@Service
public class HealingExecutor {

    private final LedgerTransactionRepository txnRepo;
    private final GatewayRecordRepository gatewayRepo;
    private final BankRecordRepository bankRepo;
    private final DiscrepancyRepository discrepancyRepo;
    private final ReconciliationEngine engine;
    private final AuditService audit;

    public HealingExecutor(LedgerTransactionRepository txnRepo, GatewayRecordRepository gatewayRepo,
                           BankRecordRepository bankRepo, DiscrepancyRepository discrepancyRepo,
                           ReconciliationEngine engine, AuditService audit) {
        this.txnRepo = txnRepo;
        this.gatewayRepo = gatewayRepo;
        this.bankRepo = bankRepo;
        this.discrepancyRepo = discrepancyRepo;
        this.engine = engine;
        this.audit = audit;
    }

    /** Thrown when a precondition does not hold - the fix is not applied. */
    public static class HealingRejectedException extends RuntimeException {
        public HealingRejectedException(String message) {
            super(message);
        }
    }

    /**
     * Applies the discrepancy's proposed action and verifies the outcome.
     *
     * @return true if the discrepancy is resolved after verification
     */
    @Transactional
    public boolean applyAndVerify(Discrepancy d, Actor actor, String actorName) {
        HealingAction action = d.getProposedAction();
        String applied;
        try {
            applied = apply(d, action);
        } catch (HealingRejectedException e) {
            d.setState(DiscrepancyState.ESCALATED);
            d.setPolicyReason("Fix not applied: " + e.getMessage());
            audit.record(d.getTxnRef(), d.getId(), "FIX_BLOCKED", Actor.SYSTEM,
                    "Guardrail blocked " + action + ": " + e.getMessage());
            return false;
        }
        audit.record(d.getTxnRef(), d.getId(), "FIX_APPLIED", actor, applied + " (by " + actorName + ")");

        // Verify: re-run reconciliation for this transaction.
        if (d.getTxnRef() != null) {
            txnRepo.findByTxnRef(d.getTxnRef()).ifPresent(engine::reconcileOne);
        }

        if (d.getState() == DiscrepancyState.RESOLVED) {
            String stage = actor == Actor.AI ? "AUTO_HEALED" : "HEALED_AFTER_APPROVAL";
            d.resolve(action.getLabel() + " - verified balanced", actor);
            audit.record(d.getTxnRef(), d.getId(), stage, actor,
                    "Healed: " + action.getLabel() + ". Ledger, gateway and bank now balance.");
            return true;
        }
        d.setState(DiscrepancyState.ESCALATED);
        d.setPolicyReason("Fix applied but verification still shows a mismatch");
        audit.record(d.getTxnRef(), d.getId(), "VERIFY_FAILED", Actor.SYSTEM,
                "Fix applied but records still do not balance - escalated to operations");
        return false;
    }

    private String apply(Discrepancy d, HealingAction action) {
        if (action == null) {
            throw new HealingRejectedException("no action proposed");
        }
        if (!d.getType().getAllowedActions().contains(action)) {
            throw new HealingRejectedException(action + " is not allowed for " + d.getType());
        }
        return switch (action) {
            case SYNC_STATUS_FROM_GATEWAY -> syncStatus(d);
            case REVERSE_LEDGER_ENTRY -> reverseLedger(d);
            case REFUND_DUPLICATE -> refundDuplicate(d);
            case POST_FEE_ADJUSTMENT -> postFeeAdjustment(d);
            case LINK_BANK_RECORD -> linkBankRecord(d);
            case RECHECK_LATER, ESCALATE -> throw new HealingRejectedException(action + " is not an executable fix");
        };
    }

    private String syncStatus(Discrepancy d) {
        LedgerTransaction t = txn(d);
        GatewayRecord g = primaryGateway(t);
        LedgerStatus target = switch (g.getStatus()) {
            case SUCCESS -> LedgerStatus.SUCCESS;
            case FAILED, REFUNDED -> LedgerStatus.FAILED;
            case PENDING -> LedgerStatus.PENDING;
        };
        LedgerStatus before = t.getLedgerStatus();
        if (before == target) {
            return "Ledger already " + target + " (no change)";
        }
        t.setLedgerStatus(target);
        txnRepo.save(t);
        return "Ledger status " + before + " -> " + target + " (synced from gateway " + g.getGatewayRef() + ")";
    }

    private String reverseLedger(Discrepancy d) {
        LedgerTransaction t = txn(d);
        GatewayRecord g = primaryGateway(t);
        if (g.getStatus() == GatewayStatus.SUCCESS) {
            throw new HealingRejectedException("gateway shows SUCCESS - reversing would lose a real payment");
        }
        if (t.getLedgerStatus() == LedgerStatus.FAILED) {
            return "Ledger entry already reversed (no change)";
        }
        t.setLedgerStatus(LedgerStatus.FAILED);
        txnRepo.save(t);
        return "Reversed ledger SUCCESS entry of INR " + t.getAmount() + " (gateway " + g.getStatus() + ")";
    }

    private String refundDuplicate(Discrepancy d) {
        LedgerTransaction t = txn(d);
        List<GatewayRecord> captured = gatewayRepo.findByTxnRefOrderByCapturedAtAsc(t.getTxnRef()).stream()
                .filter(g -> g.getStatus() == GatewayStatus.SUCCESS).toList();
        if (captured.size() < 2) {
            return "No duplicate capture left (no change)";
        }
        StringBuilder refunded = new StringBuilder();
        for (GatewayRecord extra : captured.subList(1, captured.size())) {
            extra.setStatus(GatewayStatus.REFUNDED);
            gatewayRepo.save(extra);
            refunded.append(extra.getGatewayRef()).append(' ');
        }
        return "Refund initiated via gateway for duplicate capture(s) " + refunded.toString().trim()
                + " (INR " + t.getAmount() + " each)";
    }

    private String postFeeAdjustment(Discrepancy d) {
        LedgerTransaction t = txn(d);
        GatewayRecord g = primaryGateway(t);
        BigDecimal variance = g.getFee().subtract(engine.expectedFee(t));
        if (variance.signum() == 0) {
            return "Fee already adjusted (no change)";
        }
        if (variance.abs().compareTo(t.getAmount()) >= 0) {
            throw new HealingRejectedException("fee variance is larger than the payment itself");
        }
        t.setFeeAdjustment(t.getFeeAdjustment().add(variance));
        txnRepo.save(t);
        return "Posted fee adjustment entry of INR " + variance + " (gateway fee " + g.getFee() + ")";
    }

    private String linkBankRecord(Discrepancy d) {
        LedgerTransaction t = txn(d);
        GatewayRecord g = primaryGateway(t);
        Long bankId = d.getProposedBankRecordId();
        if (bankId == null) {
            throw new HealingRejectedException("no bank credit selected");
        }
        BankRecord b = bankRepo.findById(bankId)
                .orElseThrow(() -> new HealingRejectedException("bank credit #" + bankId + " not found"));
        if (t.getTxnRef().equals(b.getLinkedTxnRef())) {
            return "Bank credit #" + bankId + " already linked (no change)";
        }
        if (b.getLinkedTxnRef() != null) {
            throw new HealingRejectedException("bank credit #" + bankId + " is already linked to " + b.getLinkedTxnRef());
        }
        if (b.getAmount().subtract(g.netAmount()).abs().compareTo(new BigDecimal("0.01")) > 0) {
            throw new HealingRejectedException("bank credit amount " + b.getAmount() + " != expected net " + g.netAmount());
        }
        b.setLinkedTxnRef(t.getTxnRef());
        bankRepo.save(b);

        // The same credit was probably also flagged as an orphan - close that too.
        discrepancyRepo.findTopByBankRecordIdAndTypeOrderByCreatedAtDesc(bankId, DiscrepancyType.ORPHAN_BANK_CREDIT)
                .filter(o -> o.getState().isActive())
                .ifPresent(o -> {
                    o.resolve("Linked to " + t.getTxnRef() + " by healing of discrepancy #" + d.getId(), Actor.SYSTEM);
                    audit.record(t.getTxnRef(), o.getId(), "VERIFIED", Actor.SYSTEM,
                            "Orphan bank credit #" + bankId + " resolved by link");
                });
        return "Linked bank credit #" + bankId + " (\"" + b.getNarration() + "\") to " + t.getTxnRef();
    }

    private LedgerTransaction txn(Discrepancy d) {
        if (d.getTxnRef() == null) {
            throw new HealingRejectedException("discrepancy has no transaction");
        }
        return txnRepo.findByTxnRef(d.getTxnRef())
                .orElseThrow(() -> new HealingRejectedException("transaction " + d.getTxnRef() + " not found"));
    }

    private GatewayRecord primaryGateway(LedgerTransaction t) {
        List<GatewayRecord> gws = gatewayRepo.findByTxnRefOrderByCapturedAtAsc(t.getTxnRef());
        Optional<GatewayRecord> success = gws.stream().filter(g -> g.getStatus() == GatewayStatus.SUCCESS).findFirst();
        if (success.isPresent()) {
            return success.get();
        }
        if (gws.isEmpty()) {
            throw new HealingRejectedException("no gateway record for " + t.getTxnRef());
        }
        return gws.get(gws.size() - 1);
    }
}
