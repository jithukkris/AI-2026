package com.healpay.simulator;

import com.healpay.config.AppProperties;
import com.healpay.domain.Actor;
import com.healpay.domain.BankRecord;
import com.healpay.domain.GatewayRecord;
import com.healpay.domain.GatewayStatus;
import com.healpay.domain.LedgerStatus;
import com.healpay.domain.LedgerTransaction;
import com.healpay.events.AuditService;
import com.healpay.repo.BankRecordRepository;
import com.healpay.repo.GatewayRecordRepository;
import com.healpay.repo.LedgerTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Generates realistic ledger + gateway + bank data, including broken cases,
 * so the self-healing loop has something to do in demos and tests.
 *
 * In production this class would be replaced by real ingestion
 * (gateway settlement reports via API/SFTP, bank MT940/CSV statements).
 */
@Service
public class PaymentSimulator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PaymentSimulator.class);

    private static final String[] MERCHANTS = {
            "Acme Retail", "Chai Point Express", "Urban Kicks", "BookNest", "FreshBasket Grocers",
            "Zenith Electronics", "Metro Pharmacy", "CloudKitchen Co"
    };
    private static final String[] MODES = {"UPI", "CARD", "NETBANKING"};
    private static final String[] BANKS = {"HDFCN", "ICICN", "SBINN", "UTIBN"};
    private static final String ALNUM = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";

    private final LedgerTransactionRepository txnRepo;
    private final GatewayRecordRepository gatewayRepo;
    private final BankRecordRepository bankRepo;
    private final AuditService audit;
    private final AppProperties props;
    private final Random random = new SecureRandom();
    private long nextSeq = -1;

    public PaymentSimulator(LedgerTransactionRepository txnRepo, GatewayRecordRepository gatewayRepo,
                            BankRecordRepository bankRepo, AuditService audit, AppProperties props) {
        this.txnRepo = txnRepo;
        this.gatewayRepo = gatewayRepo;
        this.bankRepo = bankRepo;
        this.audit = audit;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (props.simulator().seedOnStartup() && txnRepo.count() == 0) {
            Map<Scenario, Integer> created = seed(props.simulator().seedCount());
            log.info("Seeded demo data: {}", created);
        }
    }

    /** Seeds {@code count} transactions: one of every anomaly first, then mostly clean traffic. */
    @Transactional
    public synchronized Map<Scenario, Integer> seed(int count) {
        List<Scenario> plan = new ArrayList<>(List.of(Scenario.ANOMALIES));
        while (plan.size() < count) {
            int roll = random.nextInt(100);
            plan.add(roll < 80 ? Scenario.CLEAN : roll < 90 ? Scenario.FAILED_CLEAN
                    : Scenario.ANOMALIES[random.nextInt(Scenario.ANOMALIES.length)]);
        }
        return generate(plan);
    }

    /** One of every anomaly plus a few clean payments - the "Generate demo incidents" button. */
    @Transactional
    public synchronized Map<Scenario, Integer> generateIncidents() {
        List<Scenario> plan = new ArrayList<>(List.of(Scenario.ANOMALIES));
        plan.add(Scenario.CLEAN);
        plan.add(Scenario.CLEAN);
        return generate(plan);
    }

    @Transactional
    public synchronized Map<Scenario, Integer> generate(List<Scenario> plan) {
        Map<Scenario, Integer> counts = new EnumMap<>(Scenario.class);
        for (Scenario s : plan) {
            create(s);
            counts.merge(s, 1, Integer::sum);
        }
        return counts;
    }

    /** Delayed settlements "arrive" in the bank feed after a short wait. */
    @Scheduled(initialDelay = 15000, fixedDelay = 15000)
    @Transactional
    public void deliverDelayedSettlements() {
        Instant cutoff = Instant.now().minusSeconds(props.simulator().delayedSettlementSeconds());
        for (GatewayRecord g : gatewayRepo.findByStatusAndSettledFalse(GatewayStatus.SUCCESS)) {
            if (g.getCapturedAt().isAfter(cutoff)) {
                continue;
            }
            bankRepo.save(new BankRecord(g.getUtr(), g.netAmount(),
                    "NEFT CR " + g.getUtr() + " GATEWAY SETTLEMENT", today(), Instant.now()));
            g.setSettled(true);
            gatewayRepo.save(g);
            audit.record(g.getTxnRef(), null, "SETTLED", Actor.BANK,
                    "Delayed settlement credit of INR " + g.netAmount() + " received (UTR " + g.getUtr() + ")");
        }
    }

    // ------------------------------------------------------------------ scenario builders

    private void create(Scenario s) {
        if (s == Scenario.ORPHAN_CREDIT) {
            BigDecimal amt = amount(300, 3000);
            bankRepo.save(new BankRecord(utr(), amt, "NEFT CR UNKNOWN REMITTER REF " + digits(5),
                    today(), Instant.now()));
            audit.record(null, null, "BANK_FEED", Actor.BANK, "Unidentified credit of INR " + amt + " in bank statement");
            return;
        }

        boolean recent = s == Scenario.DELAYED_SETTLEMENT;
        Instant created = recent
                ? Instant.now().minusSeconds(30)
                : Instant.now().minus(26 + random.nextInt(40), ChronoUnit.HOURS);
        BigDecimal amount = s == Scenario.TRUNCATED_REF_STRONG ? amount(500, 4500) : amount(199, 9999);
        String ref = nextTxnRef();
        String gwRef = gatewayRef();
        String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
        String mode = MODES[random.nextInt(MODES.length)];
        BigDecimal fee = amount.multiply(props.recon().expectedFeeRate()).setScale(2, RoundingMode.HALF_UP);
        Instant captured = created.plusSeconds(3 + random.nextInt(20));
        Instant settledAt = captured.plus(20, ChronoUnit.HOURS);

        LedgerStatus ledger = switch (s) {
            case MISSED_WEBHOOK -> LedgerStatus.PENDING;
            case FAILED_CLEAN -> LedgerStatus.FAILED;
            default -> LedgerStatus.SUCCESS;
        };
        LedgerTransaction t = new LedgerTransaction(ref, merchant, mode, amount, ledger, gwRef, created);
        t.setScenario(s.name());
        txnRepo.save(t);
        audit.record(ref, null, "INITIATED", Actor.SYSTEM,
                "Payment of INR " + amount + " initiated via " + mode + " for " + merchant, created);

        switch (s) {
            case CLEAN -> {
                GatewayRecord g = gateway(gwRef, ref, amount, fee, GatewayStatus.SUCCESS, captured, true);
                ledgerPosted(ref, captured);
                bankCredit(g, g.getUtr(), "NEFT CR " + g.getUtr() + " GATEWAY SETTLEMENT", settledAt);
            }
            case FAILED_CLEAN -> {
                gateway(gwRef, ref, amount, BigDecimal.ZERO, GatewayStatus.FAILED, captured, true);
                audit.record(ref, null, "FAILED", Actor.GATEWAY, "Payment declined by issuer bank", captured);
            }
            case MISSED_WEBHOOK -> {
                GatewayRecord g = gateway(gwRef, ref, amount, fee, GatewayStatus.SUCCESS, captured, true);
                audit.record(ref, null, "WEBHOOK_FAILED", Actor.GATEWAY,
                        "Success webhook delivery failed (HTTP 504) - ledger left PENDING", captured.plusSeconds(1));
                bankCredit(g, g.getUtr(), "NEFT CR " + g.getUtr() + " GATEWAY SETTLEMENT", settledAt);
            }
            case DUPLICATE_CAPTURE -> {
                GatewayRecord g1 = gateway(gwRef, ref, amount, fee, GatewayStatus.SUCCESS, captured, true);
                GatewayRecord g2 = gateway(gatewayRef(), ref, amount, fee, GatewayStatus.SUCCESS,
                        captured.plusSeconds(40), true);
                ledgerPosted(ref, captured);
                audit.record(ref, null, "RETRY", Actor.GATEWAY,
                        "Customer retried; gateway captured again as " + g2.getGatewayRef(), captured.plusSeconds(41));
                bankCredit(g1, g1.getUtr(), "NEFT CR " + g1.getUtr() + " GATEWAY SETTLEMENT", settledAt);
                bankCredit(g2, g2.getUtr(), "NEFT CR " + g2.getUtr() + " GATEWAY SETTLEMENT", settledAt);
            }
            case FEE_GST -> {
                BigDecimal feeWithGst = fee.multiply(new BigDecimal("1.18")).setScale(2, RoundingMode.HALF_UP);
                GatewayRecord g = gateway(gwRef, ref, amount, feeWithGst, GatewayStatus.SUCCESS, captured, true);
                ledgerPosted(ref, captured);
                bankCredit(g, g.getUtr(), "NEFT CR " + g.getUtr() + " GATEWAY SETTLEMENT", settledAt);
            }
            case TRUNCATED_REF_STRONG, TRUNCATED_REF_WEAK -> {
                GatewayRecord g = gateway(gwRef, ref, amount, fee, GatewayStatus.SUCCESS, captured, true);
                ledgerPosted(ref, captured);
                String norm = gwRef.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
                String fragment = s == Scenario.TRUNCATED_REF_STRONG ? norm.substring(0, 11) : norm.substring(3, 9);
                String code = merchant.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
                code = code.substring(0, Math.min(6, code.length()));
                // Bank file lost the UTR - only a (partial) reference survives in the narration.
                bankCredit(g, null, "NEFT CR " + fragment + " " + code + " SETTL", settledAt);
            }
            case GATEWAY_FAILED_LEDGER_SUCCESS -> {
                gateway(gwRef, ref, amount, BigDecimal.ZERO, GatewayStatus.FAILED, captured, true);
                audit.record(ref, null, "FAILED", Actor.GATEWAY, "Payment failed at gateway (bank timeout)", captured);
                audit.record(ref, null, "LEDGER_POSTED", Actor.SYSTEM,
                        "Ledger booked SUCCESS from client redirect (bug: did not wait for gateway)", captured.plusSeconds(2));
            }
            case DELAYED_SETTLEMENT -> {
                gateway(gwRef, ref, amount, fee, GatewayStatus.SUCCESS, captured, false);
                ledgerPosted(ref, captured);
            }
            case ORPHAN_CREDIT -> { /* handled above */ }
        }
    }

    private GatewayRecord gateway(String gwRef, String txnRef, BigDecimal amount, BigDecimal fee,
                                  GatewayStatus status, Instant at, boolean settled) {
        String utr = status == GatewayStatus.SUCCESS ? utr() : null;
        GatewayRecord g = gatewayRepo.save(new GatewayRecord(gwRef, txnRef, amount, fee, status, utr, at, settled));
        if (status == GatewayStatus.SUCCESS) {
            audit.record(txnRef, null, "CAPTURED", Actor.GATEWAY,
                    "Captured by gateway " + gwRef + " (fee INR " + fee + ", UTR " + utr + ")", at);
        }
        return g;
    }

    private void ledgerPosted(String txnRef, Instant captured) {
        audit.record(txnRef, null, "LEDGER_POSTED", Actor.SYSTEM, "Ledger marked SUCCESS via webhook", captured.plusSeconds(1));
    }

    private void bankCredit(GatewayRecord g, String utr, String narration, Instant at) {
        bankRepo.save(new BankRecord(utr, g.netAmount(), narration, at.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate(), at));
        audit.record(g.getTxnRef(), null, "SETTLED", Actor.BANK,
                "Bank credit of INR " + g.netAmount() + " received (\"" + narration + "\")", at);
    }

    // ------------------------------------------------------------------ random helpers

    private String nextTxnRef() {
        if (nextSeq < 0) {
            nextSeq = 100001 + txnRepo.count();
        }
        String ref;
        do {
            ref = "TXN-" + nextSeq++;
        } while (txnRepo.findByTxnRef(ref).isPresent());
        return ref;
    }

    private String gatewayRef() {
        StringBuilder sb = new StringBuilder("pay_");
        for (int i = 0; i < 10; i++) {
            sb.append(ALNUM.charAt(random.nextInt(ALNUM.length())));
        }
        return sb.toString();
    }

    private String utr() {
        return BANKS[random.nextInt(BANKS.length)] + LocalDate.now().getYear() + digits(8);
    }

    private String digits(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }

    private BigDecimal amount(int minRupees, int maxRupees) {
        int paise = minRupees * 100 + random.nextInt((maxRupees - minRupees) * 100);
        return BigDecimal.valueOf(paise, 2);
    }

    private LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Kolkata"));
    }
}
