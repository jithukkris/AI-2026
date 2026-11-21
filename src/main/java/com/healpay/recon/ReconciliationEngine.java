package com.healpay.recon;

import com.healpay.config.AppProperties;
import com.healpay.domain.Actor;
import com.healpay.domain.BankRecord;
import com.healpay.domain.Discrepancy;
import com.healpay.domain.DiscrepancyState;
import com.healpay.domain.DiscrepancyType;
import com.healpay.domain.GatewayRecord;
import com.healpay.domain.GatewayStatus;
import com.healpay.domain.LedgerStatus;
import com.healpay.domain.LedgerTransaction;
import com.healpay.domain.ReconStatus;
import com.healpay.events.AuditService;
import com.healpay.repo.BankRecordRepository;
import com.healpay.repo.DiscrepancyRepository;
import com.healpay.repo.GatewayRecordRepository;
import com.healpay.repo.LedgerTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deterministic three-way reconciliation: Ledger <-> Gateway <-> Bank.
 *
 * Important design choice: money math and matching are pure rules, never AI.
 * The AI only explains and proposes fixes for what this engine detects.
 */
@Service
public class ReconciliationEngine {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private final LedgerTransactionRepository txnRepo;
    private final GatewayRecordRepository gatewayRepo;
    private final BankRecordRepository bankRepo;
    private final DiscrepancyRepository discrepancyRepo;
    private final AuditService audit;
    private final AppProperties props;

    public ReconciliationEngine(LedgerTransactionRepository txnRepo, GatewayRecordRepository gatewayRepo,
                                BankRecordRepository bankRepo, DiscrepancyRepository discrepancyRepo,
                                AuditService audit, AppProperties props) {
        this.txnRepo = txnRepo;
        this.gatewayRepo = gatewayRepo;
        this.bankRepo = bankRepo;
        this.discrepancyRepo = discrepancyRepo;
        this.audit = audit;
        this.props = props;
    }

    /** A detected problem, before it is persisted as a {@link Discrepancy}. */
    public record Issue(DiscrepancyType type, String detail, BigDecimal impact) {}

    public record Outcome(int created, int resolved) {
        Outcome plus(Outcome o) {
            return new Outcome(created + o.created, resolved + o.resolved);
        }
    }

    public record RunResult(int transactionsChecked, int newDiscrepancies, int resolved) {}

    // ------------------------------------------------------------------ full run

    @Transactional
    public RunResult reconcileAll() {
        List<LedgerTransaction> all = txnRepo.findAll();
        Outcome total = new Outcome(0, 0);
        for (LedgerTransaction t : all) {
            total = total.plus(reconcileOne(t));
        }
        total = total.plus(reconcileOrphanBankCredits());
        return new RunResult(all.size(), total.created(), total.resolved());
    }

    // ------------------------------------------------------------------ one transaction

    /**
     * Detects issues for one transaction, opens new discrepancies, closes those that
     * are no longer detected (this is the "Verify" step of the healing loop) and
     * refreshes the transaction's reconciliation status.
     */
    @Transactional
    public Outcome reconcileOne(LedgerTransaction t) {
        List<Issue> issues = detect(t);
        Set<DiscrepancyType> found = issues.stream().map(Issue::type).collect(Collectors.toSet());
        int created = 0;
        int resolved = 0;

        for (Issue issue : issues) {
            Optional<Discrepancy> existing =
                    discrepancyRepo.findTopByTxnRefAndTypeOrderByCreatedAtDesc(t.getTxnRef(), issue.type());
            if (existing.isPresent() && existing.get().getState() != DiscrepancyState.RESOLVED) {
                // Still the same problem: keep the record (including REJECTED - no re-proposing loops).
                Discrepancy d = existing.get();
                d.setDetail(issue.detail());
                d.setAmountImpact(issue.impact());
            } else {
                Discrepancy d = discrepancyRepo.save(
                        new Discrepancy(t.getTxnRef(), null, issue.type(), issue.detail(), issue.impact()));
                audit.record(t.getTxnRef(), d.getId(), "DETECTED", Actor.SYSTEM,
                        issue.type().getLabel() + ": " + issue.detail());
                created++;
            }
        }

        List<Discrepancy> active = discrepancyRepo.findByTxnRefAndStateIn(t.getTxnRef(), DiscrepancyState.ACTIVE);
        for (Discrepancy d : active) {
            if (!found.contains(d.getType())) {
                String msg = issues.isEmpty()
                        ? "Verified: ledger, gateway and bank now balance"
                        : "No longer detected (superseded by " + found + ")";
                d.resolve(msg, Actor.SYSTEM);
                audit.record(t.getTxnRef(), d.getId(), "VERIFIED", Actor.SYSTEM, msg);
                resolved++;
            }
        }

        refreshStatus(t, issues);
        return new Outcome(created, resolved);
    }

    /** Re-derives the user-facing status from current issues and discrepancy states. */
    @Transactional
    public void refreshStatus(LedgerTransaction t) {
        refreshStatus(t, detect(t));
    }

    private void refreshStatus(LedgerTransaction t, List<Issue> issues) {
        List<Discrepancy> history = discrepancyRepo.findByTxnRefOrderByCreatedAtDesc(t.getTxnRef());
        ReconStatus status;
        if (issues.isEmpty()) {
            status = history.isEmpty() ? ReconStatus.MATCHED : ReconStatus.HEALED;
        } else {
            Set<DiscrepancyType> found = issues.stream().map(Issue::type).collect(Collectors.toSet());
            Set<DiscrepancyState> states = EnumSet.noneOf(DiscrepancyState.class);
            for (Discrepancy d : history) {
                if (found.contains(d.getType()) && d.getState() != DiscrepancyState.RESOLVED) {
                    states.add(d.getState());
                }
            }
            if (states.contains(DiscrepancyState.AWAITING_APPROVAL)) {
                status = ReconStatus.AWAITING_APPROVAL;
            } else if (states.contains(DiscrepancyState.ESCALATED) || states.contains(DiscrepancyState.REJECTED)) {
                status = ReconStatus.MANUAL_REVIEW;
            } else if (!states.isEmpty() && states.stream().allMatch(s -> s == DiscrepancyState.WAITING)) {
                status = ReconStatus.SETTLEMENT_PENDING;
            } else {
                status = ReconStatus.MISMATCH;
            }
        }
        t.setReconStatus(status);
        txnRepo.save(t);
    }

    // ------------------------------------------------------------------ detection rules

    /** Pure read: what is wrong with this transaction right now? */
    public List<Issue> detect(LedgerTransaction t) {
        List<Issue> out = new ArrayList<>();
        String ref = t.getTxnRef();
        List<GatewayRecord> gws = gatewayRepo.findByTxnRefOrderByCapturedAtAsc(ref);

        if (gws.isEmpty()) {
            if (t.getLedgerStatus() == LedgerStatus.SUCCESS) {
                out.add(new Issue(DiscrepancyType.MISSING_IN_GATEWAY,
                        "Ledger marks " + ref + " as SUCCESS but the gateway has no record of it", t.getAmount()));
            }
            return out;
        }

        List<GatewayRecord> captured = gws.stream().filter(g -> g.getStatus() == GatewayStatus.SUCCESS).toList();
        if (captured.size() > 1) {
            String refs = captured.stream().map(GatewayRecord::getGatewayRef).collect(Collectors.joining(", "));
            out.add(new Issue(DiscrepancyType.DUPLICATE_DEBIT,
                    "Gateway captured " + captured.size() + " payments for " + ref + " (" + refs + ")",
                    t.getAmount().multiply(BigDecimal.valueOf(captured.size() - 1L))));
        }

        GatewayRecord primary = captured.isEmpty() ? gws.get(gws.size() - 1) : captured.get(0);
        GatewayStatus gs = primary.getStatus();
        LedgerStatus ls = t.getLedgerStatus();

        if (gs == GatewayStatus.SUCCESS && ls != LedgerStatus.SUCCESS) {
            out.add(new Issue(DiscrepancyType.STATUS_MISMATCH,
                    "Gateway shows SUCCESS (" + primary.getGatewayRef() + ") but ledger shows " + ls, t.getAmount()));
        } else if ((gs == GatewayStatus.FAILED || gs == GatewayStatus.REFUNDED) && ls == LedgerStatus.SUCCESS) {
            out.add(new Issue(DiscrepancyType.STATUS_MISMATCH,
                    "Ledger shows SUCCESS but gateway shows " + gs + " (" + primary.getGatewayRef() + ")", t.getAmount()));
        }

        if (gs != GatewayStatus.SUCCESS) {
            return out;
        }

        if (differs(primary.getAmount(), t.getAmount())) {
            out.add(new Issue(DiscrepancyType.AMOUNT_MISMATCH,
                    "Gateway captured " + primary.getAmount() + " but ledger expected " + t.getAmount(),
                    primary.getAmount().subtract(t.getAmount()).abs()));
        }

        BigDecimal expectedFee = expectedFee(t);
        BigDecimal variance = primary.getFee().subtract(expectedFee);
        if (variance.abs().compareTo(TOLERANCE) > 0) {
            out.add(new Issue(DiscrepancyType.FEE_VARIANCE,
                    "Gateway charged fee " + primary.getFee() + " but contract says " + expectedFee
                            + " (variance " + variance + ")", variance.abs()));
        }

        Optional<BankRecord> bank = findBankCredit(primary, ref);
        if (bank.isEmpty()) {
            long hours = Duration.between(primary.getCapturedAt(), Instant.now()).toHours();
            if (hours < props.recon().settlementLagHours()) {
                out.add(new Issue(DiscrepancyType.SETTLEMENT_DELAYED,
                        "Captured " + hours + "h ago; settlement of " + primary.netAmount() + " (UTR "
                                + primary.getUtr() + ") not yet in bank feed", primary.netAmount()));
            } else {
                out.add(new Issue(DiscrepancyType.MISSING_IN_BANK,
                        "No bank credit found for UTR " + primary.getUtr() + " (expected net "
                                + primary.netAmount() + ", captured " + hours + "h ago)", primary.netAmount()));
            }
        } else if (differs(bank.get().getAmount(), primary.netAmount())) {
            out.add(new Issue(DiscrepancyType.AMOUNT_MISMATCH,
                    "Bank credited " + bank.get().getAmount() + " but expected net " + primary.netAmount(),
                    bank.get().getAmount().subtract(primary.netAmount()).abs()));
        }
        return out;
    }

    public BigDecimal expectedFee(LedgerTransaction t) {
        return t.getAmount().multiply(props.recon().expectedFeeRate())
                .setScale(2, RoundingMode.HALF_UP)
                .add(t.getFeeAdjustment());
    }

    public Optional<BankRecord> findBankCredit(GatewayRecord primary, String txnRef) {
        Optional<BankRecord> byUtr = primary.getUtr() == null ? Optional.empty() : bankRepo.findFirstByUtr(primary.getUtr());
        return byUtr.isPresent() ? byUtr : bankRepo.findFirstByLinkedTxnRef(txnRef);
    }

    // ------------------------------------------------------------------ orphan bank credits

    private Outcome reconcileOrphanBankCredits() {
        Set<String> knownUtrs = new HashSet<>(gatewayRepo.findAllUtrs());
        int created = 0;
        int resolved = 0;

        // Credits that a "missing in bank" exception is already trying to claim (same net amount)
        // are not reported twice as orphans while that exception is being worked on.
        Set<String> claimedAmounts = discrepancyRepo.findByStateInOrderByCreatedAtDesc(DiscrepancyState.ACTIVE).stream()
                .filter(d -> d.getType() == DiscrepancyType.MISSING_IN_BANK)
                .map(d -> amountKey(d.getAmountImpact()))
                .collect(Collectors.toSet());

        for (BankRecord b : bankRepo.findByLinkedTxnRefIsNull()) {
            boolean known = b.getUtr() != null && knownUtrs.contains(b.getUtr());
            if (known || claimedAmounts.contains(amountKey(b.getAmount()))) {
                continue;
            }
            Optional<Discrepancy> existing = discrepancyRepo
                    .findTopByBankRecordIdAndTypeOrderByCreatedAtDesc(b.getId(), DiscrepancyType.ORPHAN_BANK_CREDIT);
            if (existing.isEmpty() || existing.get().getState() == DiscrepancyState.RESOLVED) {
                Discrepancy d = discrepancyRepo.save(new Discrepancy(null, b.getId(), DiscrepancyType.ORPHAN_BANK_CREDIT,
                        "Bank credit #" + b.getId() + " of " + b.getAmount() + " (\"" + b.getNarration()
                                + "\") does not match any gateway settlement", b.getAmount()));
                audit.record(null, d.getId(), "DETECTED", Actor.BANK,
                        "Orphan bank credit #" + b.getId() + " of " + b.getAmount());
                created++;
            }
        }

        for (Discrepancy d : discrepancyRepo.findByStateInOrderByCreatedAtDesc(DiscrepancyState.ACTIVE)) {
            if (d.getType() != DiscrepancyType.ORPHAN_BANK_CREDIT || d.getBankRecordId() == null) {
                continue;
            }
            Optional<BankRecord> b = bankRepo.findById(d.getBankRecordId());
            boolean nowMatched = b.isEmpty() || b.get().getLinkedTxnRef() != null
                    || (b.get().getUtr() != null && knownUtrs.contains(b.get().getUtr()));
            if (nowMatched) {
                String msg = b.isPresent() && b.get().getLinkedTxnRef() != null
                        ? "Bank credit linked to " + b.get().getLinkedTxnRef()
                        : "Bank credit now matches a gateway settlement";
                d.resolve(msg, Actor.SYSTEM);
                audit.record(b.map(BankRecord::getLinkedTxnRef).orElse(null), d.getId(), "VERIFIED", Actor.SYSTEM, msg);
                resolved++;
            }
        }
        return new Outcome(created, resolved);
    }

    private static String amountKey(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static boolean differs(BigDecimal a, BigDecimal b) {
        return a.subtract(b).abs().compareTo(TOLERANCE) > 0;
    }
}
