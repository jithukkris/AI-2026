package com.healpay.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Primary DiagnosisService when app.ai.provider=mock (the default). */
@Service
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockDiagnosisService implements DiagnosisService {

    private final RuleBasedDiagnosisService rules;

    public MockDiagnosisService(RuleBasedDiagnosisService rules) {
        this.rules = rules;
    }

    @Override
    public AiDiagnosis diagnose(DiagnosisContext context) {
        return rules.diagnose(context);
    }

    @Override
    public String sourceName() {
        return "rules";
    }
}
