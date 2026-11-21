package com.healpay.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Primary AssistantService when app.ai.provider=mock (the default). */
@Service
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockAssistantService implements AssistantService {

    private final RuleBasedAssistantService rules;

    public MockAssistantService(RuleBasedAssistantService rules) {
        this.rules = rules;
    }

    @Override
    public String ask(String question) {
        return rules.ask(question);
    }

    @Override
    public String sourceName() {
        return "rules";
    }
}
