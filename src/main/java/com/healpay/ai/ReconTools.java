package com.healpay.ai;

import com.healpay.domain.AuditEvent;
import com.healpay.domain.Discrepancy;
import com.healpay.domain.DiscrepancyState;
import com.healpay.domain.GatewayRecord;
import com.healpay.domain.GatewayStatus;
import com.healpay.domain.LedgerTransaction;
import com.healpay.domain.ReconStatus;
import com.healpay.recon.ReconSummaryService;
import com.healpay.repo.AuditEventRepository;
import com.healpay.repo.DiscrepancyRepository;
import com.healpay.repo.GatewayRecordRepository;
import com.healpay.repo.LedgerTransactionRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only tools Claude may call (Spring AI tool calling).
 *
 * Guardrail: there are deliberately NO write tools here. The model can look things up
 * and propose an action; only the Java policy + executor can change data.
 */
@Component
public class ReconTools {

    private final LedgerTransactionRepository txnRepo;
    private final GatewayRecordRepository gatewayRepo;
    private final DiscrepancyRepository discrepancyRepo;
    private final AuditEventRepository auditRepo;
    private final ReconSummaryService summaryService;
    private final DiagnosisContextBuilder contextBuilder;

    public ReconTools(LedgerTransactionRepository txnRepo, GatewayRecordRepository gatewayRepo,
                      DiscrepancyRepository discrepancyRepo, AuditEventRepository auditRepo,
                      ReconSummaryService summaryService, DiagnosisContextBuilder contextBuilder) {
        this.txnRepo = txnRepo;
        this.gatewayRepo = gatewayRepo;
        this.discrepancyRepo = discrepancyRepo;
        this.auditRepo = auditRepo;
        this.summaryService = summaryService;
        this.contextBuilder = contextBuilder;
    }

    public record TxnView(String txnRef, String merchant, BigDecimal amount, String ledgerStatus, String reconStatus,
                          String gatewayRef, List<String> openIssues, List<String> timeline) {}

    public record IssueView(Long id, String txnRef, String type, String state, BigDecimal amountImpact,
                            String aiRootCause, String proposedAction) {}

    @Tool(description = "Get one ledger transaction by its reference (e.g. TXN-100045), with its open issues "
            + "and the last 10 timeline events.")
    public TxnView getTransaction(@ToolParam(description = "Transaction reference like TXN-100045") String txnRef) {
        String ref = txnRef == null ? "" : txnRef.trim().toUpperCase(Locale.ROOT);
        return txnRepo.findByTxnRef(ref).map(this::toView).orElse(null);
    }

    @Tool(description = "List up to 20 transactions with the given reconciliation status. Valid statuses: "
            + "UNRECONCILED, MATCHED, MISMATCH, SETTLEMENT_PENDING, AWAITING_APPROVAL, MANUAL_REVIEW, HEALED.")
    public List<TxnView> listTransactionsByStatus(@ToolParam(description = "Reconciliation status") String status) {
        ReconStatus s;
        try {
            s = ReconStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return List.of();
        }
        return txnRepo.findTop50ByReconStatusOrderByCreatedAtDesc(s).stream().limit(20).map(this::toView).toList();
    }

    @Tool(description = "List currently active exceptions (open, waiting, awaiting approval, escalated).")
    public List<IssueView> listActiveExceptions() {
        return discrepancyRepo.findByStateInOrderByCreatedAtDesc(DiscrepancyState.ACTIVE).stream()
                .limit(30).map(ReconTools::toIssue).toList();
    }

    @Tool(description = "Get the overall reconciliation summary: counts by status, auto-heal rate and amount at risk.")
    public Map<String, Object> getReconSummary() {
        return summaryService.summary().asMap();
    }

    @Tool(description = "Find unlinked bank credits that could belong to a transaction, scored by amount match "
            + "and reference similarity (0..1). Use when a bank credit is missing.")
    public List<DiagnosisContext.BankCandidate> findBankCandidates(
            @ToolParam(description = "Transaction reference like TXN-100045") String txnRef) {
        String ref = txnRef == null ? "" : txnRef.trim().toUpperCase(Locale.ROOT);
        LedgerTransaction t = txnRepo.findByTxnRef(ref).orElse(null);
        if (t == null) {
            return List.of();
        }
        GatewayRecord primary = gatewayRepo.findByTxnRefOrderByCapturedAtAsc(ref).stream()
                .filter(g -> g.getStatus() == GatewayStatus.SUCCESS).findFirst().orElse(null);
        return primary == null ? List.of() : contextBuilder.findCandidates(primary, t);
    }

    private TxnView toView(LedgerTransaction t) {
        List<String> issues = discrepancyRepo.findByTxnRefAndStateIn(t.getTxnRef(), DiscrepancyState.ACTIVE).stream()
                .map(d -> d.getType() + " [" + d.getState() + "]: " + d.getDetail()).toList();
        List<AuditEvent> events = auditRepo.findByTxnRefOrderByOccurredAtAscIdAsc(t.getTxnRef());
        List<String> timeline = events.stream()
                .skip(Math.max(0, events.size() - 10))
                .map(e -> e.getOccurredAt() + " " + e.getStage() + " (" + e.getActor() + "): " + e.getMessage())
                .toList();
        return new TxnView(t.getTxnRef(), t.getMerchant(), t.getAmount(), t.getLedgerStatus().name(),
                t.getReconStatus().name(), t.getGatewayRef(), issues, timeline);
    }

    private static IssueView toIssue(Discrepancy d) {
        return new IssueView(d.getId(), d.getTxnRef(), d.getType().name(), d.getState().name(), d.getAmountImpact(),
                d.getAiRootCause(), d.getProposedAction() == null ? null : d.getProposedAction().name());
    }
}
