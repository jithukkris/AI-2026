package com.healpay;

import com.healpay.domain.Discrepancy;
import com.healpay.domain.DiscrepancyState;
import com.healpay.domain.DiscrepancyType;
import com.healpay.domain.LedgerTransaction;
import com.healpay.domain.ReconStatus;
import com.healpay.healing.SelfHealingOrchestrator;
import com.healpay.repo.DiscrepancyRepository;
import com.healpay.repo.LedgerTransactionRepository;
import com.healpay.simulator.PaymentSimulator;
import com.healpay.simulator.Scenario;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: inject one of every incident, run one healing cycle (rule-based AI),
 * and check that safe issues healed while risky ones wait for a human.
 */
@SpringBootTest
@ActiveProfiles("test")
class SelfHealingFlowTest {

    @Autowired PaymentSimulator simulator;
    @Autowired SelfHealingOrchestrator orchestrator;
    @Autowired LedgerTransactionRepository txnRepo;
    @Autowired DiscrepancyRepository discrepancyRepo;

    @Test
    void healsSafeIncidentsAndQueuesRiskyOnes() {
        simulator.generateIncidents();

        SelfHealingOrchestrator.CycleReport report = orchestrator.runCycle();
        assertThat(report.ran()).isTrue();
        assertThat(report.newDiscrepancies()).isGreaterThanOrEqualTo(7);
        assertThat(report.autoHealed()).isGreaterThanOrEqualTo(3);

        assertThat(status(Scenario.CLEAN)).isEqualTo(ReconStatus.MATCHED);
        assertThat(status(Scenario.MISSED_WEBHOOK)).isEqualTo(ReconStatus.HEALED);
        assertThat(status(Scenario.FEE_GST)).isEqualTo(ReconStatus.HEALED);
        assertThat(status(Scenario.TRUNCATED_REF_STRONG)).isEqualTo(ReconStatus.HEALED);
        assertThat(status(Scenario.DUPLICATE_CAPTURE)).isEqualTo(ReconStatus.AWAITING_APPROVAL);
        assertThat(status(Scenario.GATEWAY_FAILED_LEDGER_SUCCESS)).isEqualTo(ReconStatus.AWAITING_APPROVAL);
        assertThat(status(Scenario.TRUNCATED_REF_WEAK)).isEqualTo(ReconStatus.AWAITING_APPROVAL);
        assertThat(status(Scenario.DELAYED_SETTLEMENT)).isEqualTo(ReconStatus.SETTLEMENT_PENDING);

        // A human approves the duplicate refund -> applied and verified.
        LedgerTransaction dup = txn(Scenario.DUPLICATE_CAPTURE);
        Discrepancy pending = discrepancyRepo.findByTxnRefOrderByCreatedAtDesc(dup.getTxnRef()).stream()
                .filter(d -> d.getType() == DiscrepancyType.DUPLICATE_DEBIT)
                .findFirst().orElseThrow();
        Discrepancy approved = orchestrator.approve(pending.getId(), "test-admin");
        assertThat(approved.getState()).isEqualTo(DiscrepancyState.RESOLVED);
        assertThat(status(Scenario.DUPLICATE_CAPTURE)).isEqualTo(ReconStatus.HEALED);

        // A second cycle must not re-open anything that was healed.
        SelfHealingOrchestrator.CycleReport second = orchestrator.runCycle();
        assertThat(second.autoHealed()).isZero();
        assertThat(status(Scenario.MISSED_WEBHOOK)).isEqualTo(ReconStatus.HEALED);
    }

    private LedgerTransaction txn(Scenario s) {
        List<LedgerTransaction> all = txnRepo.findAll().stream()
                .filter(t -> s.name().equals(t.getScenario()))
                .toList();
        assertThat(all).as("transaction for scenario " + s).isNotEmpty();
        return all.get(all.size() - 1);
    }

    private ReconStatus status(Scenario s) {
        return txnRepo.findByTxnRef(txn(s).getTxnRef()).orElseThrow().getReconStatus();
    }
}
