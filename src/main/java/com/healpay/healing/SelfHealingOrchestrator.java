package com.healpay.healing;

import com.healpay.ai.AiDiagnosis;
import com.healpay.ai.DiagnosisContext;
import com.healpay.ai.DiagnosisContextBuilder;
import com.healpay.ai.DiagnosisService;
import com.healpay.config.AppProperties;
import com.healpay.domain.Actor;
import com.healpay.domain.Discrepancy;
import com.healpay.domain.DiscrepancyState;
import com.healpay.domain.HealingAction;
import com.healpay.domain.LedgerTransaction;
import com.healpay.events.AuditService;
import com.healpay.recon.ReconciliationEngine;
import com.healpay.repo.DiscrepancyRepository;
import com.healpay.repo.LedgerTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The self-healing loop:
 *
 *   DETECT    deterministic 3-way reconciliation        (ReconciliationEngine)
 *   DIAGNOSE  AI root cause + proposed action            (DiagnosisService - Claude or rules)
 *   DECIDE    guardrail policy: auto / approval / wait   (HealingPolicy)
 *   ACT       idempotent healing action                  (HealingExecutor)
 *   VERIFY    re-reconcile, resolve or escalate          (HealingExecutor + ReconciliationEngine)
 *
 * AI calls happen OUTSIDE database transactions so a slow model never holds DB locks.
 */
@Service
public class SelfHealingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SelfHealingOrchestrator.class);

    private final ReconciliationEngine engine;
    private final DiagnosisContextBuilder contextBuilder;
    private final DiagnosisService diagnosisService;
    private final HealingPolicy policy;
    private final HealingExecutor executor;
    private final DiscrepancyRepository discrepancyRepo;
    private final LedgerTransactionRepository txnRepo;
    private final AuditService audit;
    private final AppProperties props;
    private final TransactionTemplate tx;
    private final ReentrantLock lock = new ReentrantLock();

    public SelfHealingOrchestrator(ReconciliationEngine engine, DiagnosisContextBuilder contextBuilder,
                                   DiagnosisService diagnosisService, HealingPolicy policy, HealingExecutor executor,
                                   DiscrepancyRepository discrepancyRepo, LedgerTransactionRepository txnRepo,
                                   AuditService audit, AppProperties props, TransactionTemplate tx) {
        this.engine = engine;
        this.contextBuilder = contextBuilder;
        this.diagnosisService = diagnosisService;
        this.policy = policy;
        this.executor = executor;
        this.discrepancyRepo = discrepancyRepo;
        this.txnRepo = txnRepo;
        this.audit = audit;
        this.props = props;
        this.tx = tx;
    }

    public record CycleReport(boolean ran, int transactionsChecked, int newDiscrepancies, int resolvedByVerification,
                              int diagnosed, int autoHealed, int sentForApproval, int waiting, int escalated,
                              long durationMs, String aiProvider) {
        static CycleReport skipped() {
            return new CycleReport(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, null);
        }
    }

    @Scheduled(initialDelayString = "5000", fixedDelayString = "${app.healing.cycle-interval-ms}")
    public void scheduledCycle() {
        if (!props.healing().enabled()) {
            return;
        }
        CycleReport r = runCycle();
        if (r.ran() && (r.newDiscrepancies() > 0 || r.diagnosed() > 0 || r.resolvedByVerification() > 0)) {
            log.info("Healing cycle: {}", r);
        }
    }

    /** Runs one full detect -> verify cycle. Safe to call from the UI ("Run now"). */
    public CycleReport runCycle() {
        if (!lock.tryLock()) {
            return CycleReport.skipped();
        }
        long start = System.currentTimeMillis();
        try {
            // 1. DETECT (+ verify anything that healed itself, e.g. delayed settlement arrived)
            ReconciliationEngine.RunResult recon = engine.reconcileAll();

            int diagnosed = 0, auto = 0, approval = 0, waiting = 0, escalated = 0;
            List<Discrepancy> open = discrepancyRepo.findByStateOrderByCreatedAtAsc(DiscrepancyState.OPEN);
            for (Discrepancy d : open) {
                // 2. DIAGNOSE - outside any DB transaction
                DiagnosisContext ctx = contextBuilder.build(d);
                AiDiagnosis diagnosis = diagnosisService.diagnose(ctx);
                diagnosed++;

                // 3-5. DECIDE, ACT, VERIFY - one short transaction per discrepancy
                HealingPolicy.Mode mode = tx.execute(status -> decideAndAct(d.getId(), diagnosis));
                if (mode == null) {
                    continue;
                }
                switch (mode) {
                    case AUTO -> auto++;
                    case APPROVAL -> approval++;
                    case WAIT -> waiting++;
                    case ESCALATE -> escalated++;
                }
            }
            return new CycleReport(true, recon.transactionsChecked(), recon.newDiscrepancies(), recon.resolved(),
                    diagnosed, auto, approval, waiting, escalated, System.currentTimeMillis() - start,
                    diagnosisService.sourceName());
        } finally {
            lock.unlock();
        }
    }

    private HealingPolicy.Mode decideAndAct(Long discrepancyId, AiDiagnosis diagnosis) {
        Discrepancy d = discrepancyRepo.findById(discrepancyId).orElse(null);
        if (d == null || d.getState() != DiscrepancyState.OPEN) {
            return null; // changed while the AI was thinking
        }

        // Guardrail: the AI may only choose from the allowed actions for this discrepancy type.
        HealingAction action = HealingAction.parse(diagnosis.recommendedAction());
        String guardrailNote = "";
        if (action == null || !d.getType().getAllowedActions().contains(action)) {
            guardrailNote = "AI proposed '" + diagnosis.recommendedAction() + "', not allowed for " + d.getType()
                    + " - overridden to ESCALATE. ";
            action = HealingAction.ESCALATE;
        }
        if (action == HealingAction.LINK_BANK_RECORD && diagnosis.bankRecordId() == null) {
            guardrailNote = "AI proposed a link without choosing a bank credit - overridden to ESCALATE. ";
            action = HealingAction.ESCALATE;
        }
        double confidence = Math.max(0.0, Math.min(1.0, diagnosis.confidence()));

        d.setAiRootCause(diagnosis.rootCause());
        d.setAiExplanation(diagnosis.explanation());
        d.setAiConfidence(confidence);
        d.setAiSource(diagnosisService.sourceName());
        d.setProposedAction(action);
        d.setProposedBankRecordId(action == HealingAction.LINK_BANK_RECORD ? diagnosis.bankRecordId() : null);
        audit.record(d.getTxnRef(), d.getId(), "DIAGNOSED", Actor.AI,
                String.format(Locale.ROOT, "%s -> proposes %s (confidence %.0f%%)",
                        diagnosis.rootCause(), action.getLabel(), confidence * 100));

        LedgerTransaction t = d.getTxnRef() == null ? null : txnRepo.findByTxnRef(d.getTxnRef()).orElse(null);
        HealingPolicy.Decision decision = policy.decide(action, confidence, d.getAmountImpact(), t);
        d.setPolicyReason(guardrailNote + decision.reason());

        switch (decision.mode()) {
            case AUTO -> executor.applyAndVerify(d, Actor.AI, "HealPay AI");
            case APPROVAL -> {
                d.setState(DiscrepancyState.AWAITING_APPROVAL);
                audit.record(d.getTxnRef(), d.getId(), "APPROVAL_REQUESTED", Actor.SYSTEM,
                        "Waiting for human approval: " + decision.reason());
            }
            case WAIT -> {
                d.setState(DiscrepancyState.WAITING);
                audit.record(d.getTxnRef(), d.getId(), "WAITING", Actor.SYSTEM, decision.reason());
            }
            case ESCALATE -> {
                d.setState(DiscrepancyState.ESCALATED);
                audit.record(d.getTxnRef(), d.getId(), "ESCALATED", Actor.SYSTEM, guardrailNote + decision.reason());
            }
        }
        if (t != null) {
            engine.refreshStatus(t);
        }
        return decision.mode();
    }

    // ------------------------------------------------------------------ human decisions

    public Discrepancy approve(Long discrepancyId, String username) {
        return tx.execute(status -> {
            Discrepancy d = requireAwaitingApproval(discrepancyId);
            audit.record(d.getTxnRef(), d.getId(), "APPROVED", Actor.USER,
                    username + " approved: " + d.getProposedAction().getLabel());
            executor.applyAndVerify(d, Actor.USER, username);
            refresh(d);
            return d;
        });
    }

    public Discrepancy reject(Long discrepancyId, String username, String reason) {
        return tx.execute(status -> {
            Discrepancy d = requireAwaitingApproval(discrepancyId);
            String why = (reason == null || reason.isBlank()) ? "no reason given" : reason.trim();
            d.reject("Rejected by " + username + ": " + why);
            audit.record(d.getTxnRef(), d.getId(), "REJECTED", Actor.USER,
                    username + " rejected " + d.getProposedAction().getLabel() + " (" + why + ") - manual handling");
            refresh(d);
            return d;
        });
    }

    private Discrepancy requireAwaitingApproval(Long id) {
        Discrepancy d = discrepancyRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exception #" + id + " not found"));
        if (d.getState() != DiscrepancyState.AWAITING_APPROVAL) {
            throw new IllegalStateException("Exception #" + id + " is " + d.getState() + ", not awaiting approval");
        }
        return d;
    }

    private void refresh(Discrepancy d) {
        if (d.getTxnRef() != null) {
            txnRepo.findByTxnRef(d.getTxnRef()).ifPresent(engine::refreshStatus);
        }
    }
}
