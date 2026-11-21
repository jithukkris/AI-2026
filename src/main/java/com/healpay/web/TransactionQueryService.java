package com.healpay.web;

import com.healpay.domain.AuditEvent;
import com.healpay.domain.BankRecord;
import com.healpay.domain.Discrepancy;
import com.healpay.domain.DiscrepancyState;
import com.healpay.domain.GatewayRecord;
import com.healpay.domain.LedgerTransaction;
import com.healpay.domain.ReconStatus;
import com.healpay.repo.AuditEventRepository;
import com.healpay.repo.BankRecordRepository;
import com.healpay.repo.DiscrepancyRepository;
import com.healpay.repo.GatewayRecordRepository;
import com.healpay.repo.LedgerTransactionRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Read-side queries shared by the UI pages and the REST API. */
@Service
public class TransactionQueryService {

    private final LedgerTransactionRepository txnRepo;
    private final GatewayRecordRepository gatewayRepo;
    private final BankRecordRepository bankRepo;
    private final DiscrepancyRepository discrepancyRepo;
    private final AuditEventRepository auditRepo;

    public TransactionQueryService(LedgerTransactionRepository txnRepo, GatewayRecordRepository gatewayRepo,
                                   BankRecordRepository bankRepo, DiscrepancyRepository discrepancyRepo,
                                   AuditEventRepository auditRepo) {
        this.txnRepo = txnRepo;
        this.gatewayRepo = gatewayRepo;
        this.bankRepo = bankRepo;
        this.discrepancyRepo = discrepancyRepo;
        this.auditRepo = auditRepo;
    }

    public record TransactionDetail(
            LedgerTransaction transaction,
            List<GatewayRecord> gatewayRecords,
            List<BankRecord> bankCredits,
            List<Discrepancy> exceptions,
            List<AuditEvent> timeline) {}

    /**
     * Simple search over the latest 500 transactions (enough for a demo).
     * For production volumes replace with a paged JPA Specification / indexed query.
     */
    public List<LedgerTransaction> search(String q, ReconStatus status) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return txnRepo.findTop500ByOrderByCreatedAtDesc().stream()
                .filter(t -> status == null || t.getReconStatus() == status)
                .filter(t -> needle.isEmpty()
                        || t.getTxnRef().toLowerCase(Locale.ROOT).contains(needle)
                        || t.getMerchant().toLowerCase(Locale.ROOT).contains(needle)
                        || (t.getGatewayRef() != null && t.getGatewayRef().toLowerCase(Locale.ROOT).contains(needle)))
                .toList();
    }

    public Optional<TransactionDetail> detail(String txnRef) {
        String ref = txnRef == null ? "" : txnRef.trim().toUpperCase(Locale.ROOT);
        return txnRepo.findByTxnRef(ref).map(t -> {
            List<GatewayRecord> gws = gatewayRepo.findByTxnRefOrderByCapturedAtAsc(ref);
            List<BankRecord> credits = new ArrayList<>();
            for (GatewayRecord g : gws) {
                if (g.getUtr() != null) {
                    bankRepo.findFirstByUtr(g.getUtr()).ifPresent(credits::add);
                }
            }
            bankRepo.findFirstByLinkedTxnRef(ref).filter(b -> credits.stream().noneMatch(c -> c.getId().equals(b.getId()))).ifPresent(credits::add);
            return new TransactionDetail(t, gws, credits,
                    discrepancyRepo.findByTxnRefOrderByCreatedAtDesc(ref),
                    auditRepo.findByTxnRefOrderByOccurredAtAscIdAsc(ref));
        });
    }

    public List<Discrepancy> exceptions(String stateFilter) {
        if (stateFilter == null || stateFilter.isBlank() || "ACTIVE".equalsIgnoreCase(stateFilter)) {
            return discrepancyRepo.findByStateInOrderByCreatedAtDesc(DiscrepancyState.ACTIVE);
        }
        if ("ALL".equalsIgnoreCase(stateFilter)) {
            return discrepancyRepo.findTop200ByOrderByCreatedAtDesc();
        }
        DiscrepancyState s = DiscrepancyState.valueOf(stateFilter.trim().toUpperCase(Locale.ROOT));
        return discrepancyRepo.findByStateInOrderByCreatedAtDesc(Set.of(s));
    }

    public Optional<Discrepancy> exception(Long id) {
        return discrepancyRepo.findById(id);
    }

    public List<AuditEvent> recentEvents() {
        return auditRepo.findTop40ByOrderByIdDesc();
    }
}
