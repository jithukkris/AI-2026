package com.healpay.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FuzzyMatcherTest {

    @Test
    void strongPartialReferenceScoresHigh() {
        double s = FuzzyMatcher.similarity("NEFT CR PAY8F3KD92L ACMERE SETTL", "pay_8F3kd92LmQ");
        assertThat(s).isGreaterThan(0.8);
    }

    @Test
    void shortFragmentScoresMedium() {
        double s = FuzzyMatcher.similarity("NEFT CR 8F3KD9 ACMERE SETTL", "pay_8F3kd92LmQ");
        assertThat(s).isBetween(0.4, 0.6);
    }

    @Test
    void unrelatedNarrationScoresZero() {
        assertThat(FuzzyMatcher.similarity("NEFT CR UNKNOWN REMITTER REF 99812", "pay_8F3kd92LmQ")).isZero();
        assertThat(FuzzyMatcher.similarity(null, "pay_8F3kd92LmQ")).isZero();
    }

    @Test
    void bestOfSeveralReferencesWins() {
        double s = FuzzyMatcher.similarity("IMPS TXN100045 REFUND", "pay_zzzzzzzzzz", "TXN-100045");
        assertThat(s).isEqualTo(1.0);
    }
}
