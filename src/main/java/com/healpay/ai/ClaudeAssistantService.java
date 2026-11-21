package com.healpay.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Claude-powered assistant: answers questions by calling read-only {@link ReconTools}. */
@Service
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "claude")
public class ClaudeAssistantService implements AssistantService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeAssistantService.class);

    private static final String SYSTEM_PROMPT = """
            You are the HealPay operations assistant. You answer questions about payment
            transactions, reconciliation status and exceptions.
            Always use the tools to look up real data before answering - never guess numbers.
            Keep answers short (under 120 words), use INR for amounts, and when a transaction is
            stuck, say what the system is waiting for and whether a human needs to approve something.
            If data is not found, say so plainly.
            """;

    private final ChatClient chatClient;
    private final ReconTools tools;
    private final RuleBasedAssistantService fallback;

    public ClaudeAssistantService(ChatClient.Builder builder, ReconTools tools, RuleBasedAssistantService fallback) {
        this.chatClient = builder.defaultSystem(SYSTEM_PROMPT).build();
        this.tools = tools;
        this.fallback = fallback;
    }

    @Override
    public String ask(String question) {
        try {
            return chatClient.prompt()
                    .user(u -> u.text("{question}").param("question", question))
                    .tools(tools)
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("Claude assistant failed, using rule-based fallback: {}", e.getMessage());
            return fallback.ask(question) + "\n\n(AI unavailable - rule-based answer)";
        }
    }

    @Override
    public String sourceName() {
        return "claude";
    }
}
