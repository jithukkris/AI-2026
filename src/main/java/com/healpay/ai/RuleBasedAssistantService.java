package com.healpay.ai;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Keyword-based assistant so the demo works without an API key. Also the Claude fallback. */
@Service
public class RuleBasedAssistantService {

    private static final Pattern TXN_REF = Pattern.compile("TXN-?\\d{4,}", Pattern.CASE_INSENSITIVE);

    private final ReconTools tools;

    public RuleBasedAssistantService(ReconTools tools) {
        this.tools = tools;
    }

    public String ask(String question) {
        String q = question == null ? "" : question.trim();
        Matcher m = TXN_REF.matcher(q);
        if (m.find()) {
            String ref = m.group().toUpperCase(Locale.ROOT);
            if (!ref.contains("-")) {
                ref = "TXN-" + ref.substring(3);
            }
            ReconTools.TxnView t = tools.getTransaction(ref);
            if (t == null) {
                return "I could not find transaction " + ref + ".";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(t.txnRef()).append(" (").append(t.merchant()).append(", INR ").append(t.amount()).append(")\n")
              .append("Ledger status: ").append(t.ledgerStatus()).append(" | Reconciliation: ").append(t.reconStatus()).append('\n');
            if (t.openIssues().isEmpty()) {
                sb.append("No open issues - ledger, gateway and bank agree.\n");
            } else {
                sb.append("Open issues:\n");
                t.openIssues().forEach(i -> sb.append(" - ").append(i).append('\n'));
            }
            if (!t.timeline().isEmpty()) {
                sb.append("Latest event: ").append(t.timeline().get(t.timeline().size() - 1));
            }
            return sb.toString();
        }

        String lower = q.toLowerCase(Locale.ROOT);
        if (lower.contains("approv") || lower.contains("pending") || lower.contains("waiting")) {
            List<ReconTools.IssueView> issues = tools.listActiveExceptions().stream()
                    .filter(i -> "AWAITING_APPROVAL".equals(i.state())).toList();
            if (issues.isEmpty()) {
                return "Nothing is waiting for approval right now.";
            }
            return "Waiting for human approval:\n" + issues.stream()
                    .map(i -> " - #" + i.id() + " " + i.txnRef() + " " + i.type() + " -> " + i.proposedAction()
                            + " (INR " + i.amountImpact() + ")")
                    .collect(Collectors.joining("\n"));
        }
        if (lower.contains("exception") || lower.contains("issue") || lower.contains("problem") || lower.contains("open")) {
            List<ReconTools.IssueView> issues = tools.listActiveExceptions();
            if (issues.isEmpty()) {
                return "There are no active exceptions. Everything is balanced.";
            }
            return issues.size() + " active exception(s):\n" + issues.stream().limit(10)
                    .map(i -> " - #" + i.id() + " " + (i.txnRef() == null ? "bank credit" : i.txnRef()) + " " + i.type()
                            + " [" + i.state() + "]")
                    .collect(Collectors.joining("\n"));
        }
        if (lower.contains("summary") || lower.contains("status") || lower.contains("how many")
                || lower.contains("overview") || lower.contains("risk")) {
            Map<String, Object> s = tools.getReconSummary();
            return "Reconciliation summary:\n" + s.entrySet().stream()
                    .map(e -> " - " + e.getKey() + ": " + e.getValue())
                    .collect(Collectors.joining("\n"));
        }
        return """
                I can help with:
                 - "Why is TXN-100012 not reconciled?"
                 - "What is waiting for approval?"
                 - "Show open exceptions"
                 - "Give me a reconciliation summary"
                (Running in rule-based mode. Enable the 'claude' profile for free-form answers.)""";
    }
}
