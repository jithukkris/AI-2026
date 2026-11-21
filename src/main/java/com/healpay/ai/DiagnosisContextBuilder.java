package com.healpay.ai;

import com.healpay.domain.BankRecord;
import com.healpay.domain.Discrepancy;
import com.healpay.domain.GatewayRecord;
import com.healpay.domain.GatewayStatus;
import com.healpay.domain.LedgerTransaction;
import com.healpay.recon.ReconciliationEngine;
import com.healpay.repo.BankRecordRepository;
import com.healpay.repo.GatewayRecordRepository;
import com.healpay.repo.LedgerTransactionRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Collects ledger, gateway and bank data around a discrepancy for the AI. */
@Component
public class DiagnosisContextBuilder {

    private final LedgerTransactionRepository txnRepo;
    private final GatewayRecordRepository gatewayRepo;
    private final BankRecordRepository bankRepo;
    private final ReconciliationEngine engine;

    public DiagnosisContextBuilder(LedgerTransactionRepository txnRepo, GatewayRecordRepository gatewayRepo,
                                   BankRecordRepository bankRepo, ReconciliationEngine engine) {
        this.txnRepo = txnRepo;
        this.gatewayRepo = gatewayRepo;
        this.bankRepo = bankRepo;
        this.engine = engine;
    }

    public DiagnosisContext build(Discrepancy d) {
        DiagnosisContext.TxnInfo txnInfo = null;
        List<DiagnosisContext.GatewayInfo> gatewayInfos = List.of();
        DiagnosisContext.BankInfo matched = null;
        List<DiagnosisContext.BankCandidate> candidates = List.of();
        BigDecimal expectedFee = null;
        Long hours = null;

        Optional<LedgerTransaction> txn = d.getTxnRef() == null ? Optional.empty() : txnRepo.findByTxnRef(d.getTxnRef());
        if (txn.isPresent()) {
            LedgerTransaction t = txn.get();
            txnInfo = new DiagnosisContext.TxnInfo(t.getTxnRef(), t.getMerchant(), t.getPaymentMode(), t.getAmount(),
                    t.getLedgerStatus().name(), t.getGatewayRef(), t.getFeeAdjustment());
            expectedFee = engine.expectedFee(t);

            List<GatewayRecord> gws = gatewayRepo.findByTxnRefOrderByCapturedAtAsc(t.getTxnRef());
            gatewayInfos = gws.stream().map(g -> new DiagnosisContext.GatewayInfo(g.getGatewayRef(), g.getAmount(),
                    g.getFee(), g.getStatus().name(), g.getUtr(), g.getCapturedAt().toString())).toList();

            Optional<GatewayRecord> primary = gws.stream().filter(g -> g.getStatus() == GatewayStatus.SUCCESS).findFirst();
            if (primary.isPresent()) {
                GatewayRecord p = primary.get();
                hours = Duration.between(p.getCapturedAt(), Instant.now()).toHours();
                matched = engine.findBankCredit(p, t.getTxnRef()).map(DiagnosisContextBuilder::toInfo).orElse(null);
                if (matched == null) {
                    candidates = findCandidates(p, t);
                }
            }
        } else if (d.getBankRecordId() != null) {
            matched = bankRepo.findById(d.getBankRecordId()).map(DiagnosisContextBuilder::toInfo).orElse(null);
        }

        return new DiagnosisContext(d.getId(), d.getType(), d.getDetail(), d.getAmountImpact(), txnInfo, gatewayInfos,
                matched, candidates, expectedFee, hours, d.getType().getAllowedActions());
    }

    /** Unlinked bank credits scored by amount match and reference similarity (top 5). */
    public List<DiagnosisContext.BankCandidate> findCandidates(GatewayRecord primary, LedgerTransaction t) {
        BigDecimal net = primary.netAmount();
        return bankRepo.findByLinkedTxnRefIsNull().stream()
                .map(b -> new DiagnosisContext.BankCandidate(b.getId(), b.getAmount(), b.getNarration(),
                        b.getValueDate().toString(),
                        round(FuzzyMatcher.similarity(b.getNarration(), primary.getGatewayRef(), t.getTxnRef(), primary.getUtr())),
                        b.getAmount().subtract(net).abs().compareTo(new BigDecimal("0.01")) <= 0))
                .filter(c -> c.amountMatches() || c.referenceSimilarity() >= 0.4)
                .sorted(Comparator.comparing((DiagnosisContext.BankCandidate c) -> c.amountMatches()).reversed()
                        .thenComparing(Comparator.comparingDouble(DiagnosisContext.BankCandidate::referenceSimilarity).reversed()))
                .limit(5)
                .toList();
    }

    private static DiagnosisContext.BankInfo toInfo(BankRecord b) {
        return new DiagnosisContext.BankInfo(b.getId(), b.getUtr(), b.getAmount(), b.getNarration(), b.getValueDate().toString());
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
