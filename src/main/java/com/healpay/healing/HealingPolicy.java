package com.healpay.healing;

import com.healpay.config.AppProperties;
import com.healpay.domain.HealingAction;
import com.healpay.domain.LedgerStatus;
import com.healpay.domain.LedgerTransaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * "The AI proposes, the policy disposes."
 *
 * Plain Java rules decide whether a proposed fix may run automatically.
 * This is the main safety layer and is fully unit-tested.
 */
@Component
public class HealingPolicy {

    public enum Mode { AUTO, APPROVAL, WAIT, ESCALATE }

    public record Decision(Mode mode, String reason) {}

    private final AppProperties props;

    public HealingPolicy(AppProperties props) {
        this.props = props;
    }

    public Decision decide(HealingAction action, double confidence, BigDecimal amountImpact, LedgerTransaction txn) {
        if (action == null || action == HealingAction.ESCALATE) {
            return new Decision(Mode.ESCALATE, "No safe automatic fix - routed to operations");
        }
        if (action == HealingAction.RECHECK_LATER) {
            return new Decision(Mode.WAIT, "Within settlement window - will re-check automatically");
        }
        if (action.isMovesMoney()) {
            return new Decision(Mode.APPROVAL, action.getLabel() + " moves money - human approval always required");
        }
        if (action == HealingAction.SYNC_STATUS_FROM_GATEWAY && txn != null
                && txn.getLedgerStatus() == LedgerStatus.SUCCESS) {
            return new Decision(Mode.APPROVAL, "Sync would downgrade a SUCCESS ledger entry - needs approval");
        }
        double minConfidence = props.healing().minConfidence();
        if (confidence < minConfidence) {
            return new Decision(Mode.APPROVAL, String.format(java.util.Locale.ROOT,
                    "AI confidence %.2f is below threshold %.2f", confidence, minConfidence));
        }
        // Status sync only aligns our ledger with the gateway (source of truth), so the amount
        // limit applies to actions that change balances or matching: fee adjustments and links.
        boolean changesBalances = action == HealingAction.POST_FEE_ADJUSTMENT || action == HealingAction.LINK_BANK_RECORD;
        BigDecimal limit = props.healing().autoHealLimit();
        if (changesBalances && amountImpact != null && limit != null && amountImpact.compareTo(limit) > 0) {
            return new Decision(Mode.APPROVAL, "Amount at risk INR " + amountImpact + " exceeds auto-heal limit INR " + limit);
        }
        return new Decision(Mode.AUTO, String.format(java.util.Locale.ROOT,
                "Safe action, confidence %.2f, amount within auto-heal limit", confidence));
    }
}
