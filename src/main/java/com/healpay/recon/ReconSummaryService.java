package com.healpay.recon;

import com.healpay.domain.Actor;
import com.healpay.domain.Discrepancy;
import com.healpay.domain.DiscrepancyState;
import com.healpay.domain.ReconStatus;
import com.healpay.repo.DiscrepancyRepository;
import com.healpay.repo.LedgerTransactionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** Numbers for the dashboard KPI cards and the AI assistant. */
@Service
public class ReconSummaryService {

    private final LedgerTransactionRepository txnRepo;
    private final DiscrepancyRepository discrepancyRepo;

    public ReconSummaryService(LedgerTransactionRepository txnRepo, DiscrepancyRepository discrepancyRepo) {
        this.txnRepo = txnRepo;
        this.discrepancyRepo = discrepancyRepo;
    }

    public record Summary(
            long totalTransactions,
            long matched,
            long healed,
            long mismatch,
            long settlementPending,
            long awaitingApproval,
            long manualReview,
            long activeExceptions,
            long autoHealedByAi,
            long fixedAfterApproval,
            long selfResolved,
            BigDecimal amountAtRisk) {

        /** Share of resolved exceptions that needed no human. */
        public double autoHealRate() {
            long resolved = autoHealedByAi + fixedAfterApproval + selfResolved;
            return resolved == 0 ? 0.0 : (double) (autoHealedByAi + selfResolved) / resolved;
        }

        public double balancedRate() {
            return totalTransactions == 0 ? 0.0 : (double) (matched + healed) / totalTransactions;
        }

        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("totalTransactions", totalTransactions);
            m.put("matched", matched);
            m.put("healed", healed);
            m.put("mismatch", mismatch);
            m.put("settlementPending", settlementPending);
            m.put("awaitingApproval", awaitingApproval);
            m.put("manualReview", manualReview);
            m.put("activeExceptions", activeExceptions);
            m.put("autoHealedByAi", autoHealedByAi);
            m.put("fixedAfterApproval", fixedAfterApproval);
            m.put("selfResolved", selfResolved);
            m.put("amountAtRiskInr", amountAtRisk);
            m.put("autoHealRate", Math.round(autoHealRate() * 100) + "%");
            m.put("balancedRate", Math.round(balancedRate() * 100) + "%");
            return m;
        }
    }

    public Summary summary() {
        BigDecimal atRisk = discrepancyRepo.findByStateInOrderByCreatedAtDesc(DiscrepancyState.ACTIVE).stream()
                .map(Discrepancy::getAmountImpact)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long active = DiscrepancyState.ACTIVE.stream().mapToLong(discrepancyRepo::countByState).sum();
        return new Summary(
                txnRepo.count(),
                txnRepo.countByReconStatus(ReconStatus.MATCHED),
                txnRepo.countByReconStatus(ReconStatus.HEALED),
                txnRepo.countByReconStatus(ReconStatus.MISMATCH),
                txnRepo.countByReconStatus(ReconStatus.SETTLEMENT_PENDING),
                txnRepo.countByReconStatus(ReconStatus.AWAITING_APPROVAL),
                txnRepo.countByReconStatus(ReconStatus.MANUAL_REVIEW),
                active,
                discrepancyRepo.countByStateAndResolvedBy(DiscrepancyState.RESOLVED, Actor.AI),
                discrepancyRepo.countByStateAndResolvedBy(DiscrepancyState.RESOLVED, Actor.USER),
                discrepancyRepo.countByStateAndResolvedBy(DiscrepancyState.RESOLVED, Actor.SYSTEM),
                atRisk);
    }
}
