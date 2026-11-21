package com.healpay.healing;

import com.healpay.config.AppProperties;
import com.healpay.domain.HealingAction;
import com.healpay.domain.LedgerStatus;
import com.healpay.domain.LedgerTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class HealingPolicyTest {

    private final AppProperties props = new AppProperties(
            "Asia/Kolkata",
            new AppProperties.Ai("mock"),
            new AppProperties.Healing(true, new BigDecimal("5000"), 0.80, 30000),
            new AppProperties.Recon(new BigDecimal("0.02"), 24),
            new AppProperties.Simulator(false, 0, 90),
            new AppProperties.Users("admin", "a", "viewer", "v"));

    private final HealingPolicy policy = new HealingPolicy(props);

    private LedgerTransaction txn(LedgerStatus status) {
        return new LedgerTransaction("TXN-1", "Acme", "UPI", new BigDecimal("2500.00"), status, "pay_x", Instant.now());
    }

    @Test
    void moneyMovingActionsAlwaysNeedApproval() {
        HealingPolicy.Decision d = policy.decide(HealingAction.REFUND_DUPLICATE, 0.99, new BigDecimal("10"), txn(LedgerStatus.SUCCESS));
        assertThat(d.mode()).isEqualTo(HealingPolicy.Mode.APPROVAL);

        d = policy.decide(HealingAction.REVERSE_LEDGER_ENTRY, 0.99, new BigDecimal("10"), txn(LedgerStatus.SUCCESS));
        assertThat(d.mode()).isEqualTo(HealingPolicy.Mode.APPROVAL);
    }

    @Test
    void confidentStatusSyncHealsAutomatically() {
        HealingPolicy.Decision d = policy.decide(HealingAction.SYNC_STATUS_FROM_GATEWAY, 0.93,
                new BigDecimal("9000"), txn(LedgerStatus.PENDING));
        assertThat(d.mode()).isEqualTo(HealingPolicy.Mode.AUTO);
    }

    @Test
    void syncThatDowngradesSuccessNeedsApproval() {
        HealingPolicy.Decision d = policy.decide(HealingAction.SYNC_STATUS_FROM_GATEWAY, 0.99,
                new BigDecimal("100"), txn(LedgerStatus.SUCCESS));
        assertThat(d.mode()).isEqualTo(HealingPolicy.Mode.APPROVAL);
    }

    @Test
    void lowConfidenceNeedsApproval() {
        HealingPolicy.Decision d = policy.decide(HealingAction.LINK_BANK_RECORD, 0.73,
                new BigDecimal("100"), txn(LedgerStatus.SUCCESS));
        assertThat(d.mode()).isEqualTo(HealingPolicy.Mode.APPROVAL);
        assertThat(d.reason()).contains("confidence");
    }

    @Test
    void largeBalanceChangeNeedsApproval() {
        HealingPolicy.Decision d = policy.decide(HealingAction.LINK_BANK_RECORD, 0.95,
                new BigDecimal("7500"), txn(LedgerStatus.SUCCESS));
        assertThat(d.mode()).isEqualTo(HealingPolicy.Mode.APPROVAL);
        assertThat(d.reason()).contains("limit");
    }

    @Test
    void recheckWaitsAndEscalateEscalates() {
        assertThat(policy.decide(HealingAction.RECHECK_LATER, 0.9, BigDecimal.ONE, null).mode())
                .isEqualTo(HealingPolicy.Mode.WAIT);
        assertThat(policy.decide(HealingAction.ESCALATE, 0.9, BigDecimal.ONE, null).mode())
                .isEqualTo(HealingPolicy.Mode.ESCALATE);
        assertThat(policy.decide(null, 0.9, BigDecimal.ONE, null).mode())
                .isEqualTo(HealingPolicy.Mode.ESCALATE);
    }
}
