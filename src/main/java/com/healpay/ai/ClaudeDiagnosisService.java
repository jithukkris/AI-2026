package com.healpay.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Real AI diagnosis with Claude through Spring AI.
 *
 * - Structured output: the response is mapped straight into {@link AiDiagnosis}.
 * - Tool calling: Claude can call read-only {@link ReconTools} to look up more context.
 * - Fallback: any API error falls back to the deterministic rules, so healing never stalls.
 */
@Service
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "claude")
public class ClaudeDiagnosisService implements DiagnosisService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeDiagnosisService.class);

    static final String SYSTEM_PROMPT = """
            You are HealPay's reconciliation analyst for an Indian payments business.
            You receive one discrepancy found by a deterministic three-way reconciliation
            (internal ledger vs payment gateway vs bank statement).

            Your job:
            1. Identify the most likely root cause (short label, max 8 words).
            2. Explain it in 2-4 plain sentences an operations person can act on. Mention amounts in INR.
            3. Recommend exactly ONE action from ALLOWED ACTIONS. Never invent other actions.
            4. Give a confidence between 0 and 1. Be conservative: below 0.8 means a human should decide.
            5. For LINK_BANK_RECORD you MUST set bankRecordId to a candidate whose amountMatches is true.

            Rules of thumb:
            - The gateway is the source of truth for whether money was captured.
            - Ledger PENDING/INITIATED but gateway SUCCESS usually means a missed webhook -> SYNC_STATUS_FROM_GATEWAY.
            - Ledger SUCCESS but gateway FAILED -> REVERSE_LEDGER_ENTRY.
            - Two successful captures for one order -> REFUND_DUPLICATE.
            - Fee about 18% above contract usually means GST on MDR -> POST_FEE_ADJUSTMENT.
            - Bank credits often have truncated references; rely on the similarity score computed by code.
            - Inside the settlement lag window, prefer RECHECK_LATER.
            - If unsure, choose ESCALATE.
            You may call the provided tools to look up more data. You cannot change data.
            """;

    private final ChatClient chatClient;
    private final ReconTools tools;
    private final RuleBasedDiagnosisService fallback;

    public ClaudeDiagnosisService(ChatClient.Builder builder, ReconTools tools, RuleBasedDiagnosisService fallback) {
        this.chatClient = builder.defaultSystem(SYSTEM_PROMPT).build();
        this.tools = tools;
        this.fallback = fallback;
    }

    @Override
    public AiDiagnosis diagnose(DiagnosisContext context) {
        try {
            AiDiagnosis result = chatClient.prompt()
                    .user(u -> u.text("Diagnose this discrepancy and answer in the required JSON format.\n\n{context}")
                            .param("context", context.toPromptText()))
                    .tools(tools)
                    .call()
                    .entity(AiDiagnosis.class);
            if (result == null || result.recommendedAction() == null) {
                throw new IllegalStateException("Empty AI response");
            }
            return result;
        } catch (Exception e) {
            log.warn("Claude diagnosis failed for discrepancy #{}, using rule-based fallback: {}",
                    context.discrepancyId(), e.getMessage());
            AiDiagnosis rules = fallback.diagnose(context);
            return new AiDiagnosis(rules.rootCause(), rules.explanation() + " [AI unavailable - rule-based diagnosis]",
                    rules.recommendedAction(), rules.confidence(), rules.bankRecordId());
        }
    }

    @Override
    public String sourceName() {
        return "claude";
    }
}
