package com.healpay.ai;

/**
 * Structured output returned by the AI (Spring AI maps the model's JSON into this record).
 *
 * @param rootCause          short label, e.g. "Missed gateway webhook"
 * @param explanation        2-4 sentences in plain language for an ops person
 * @param recommendedAction  one of the allowed HealingAction names
 * @param confidence         0.0 - 1.0
 * @param bankRecordId       only for LINK_BANK_RECORD: the bank credit to link
 */
public record AiDiagnosis(
        String rootCause,
        String explanation,
        String recommendedAction,
        double confidence,
        Long bankRecordId) {
}
