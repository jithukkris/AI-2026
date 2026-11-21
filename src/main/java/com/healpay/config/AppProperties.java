package com.healpay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * All tunable settings live under the {@code app.*} prefix in application.yml.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String displayZone,
        Ai ai,
        Healing healing,
        Recon recon,
        Simulator simulator,
        Users users) {

    /** provider = "mock" (rule-based, no API key) or "claude" (Spring AI + Anthropic). */
    public record Ai(String provider) {}

    public record Healing(
            boolean enabled,
            /* Max amount (INR) the system may heal without a human. */
            BigDecimal autoHealLimit,
            /* Minimum AI confidence for an automatic fix. */
            double minConfidence,
            long cycleIntervalMs) {}

    public record Recon(
            /* Contracted gateway fee rate, e.g. 0.02 = 2%. */
            BigDecimal expectedFeeRate,
            /* Settlements younger than this are "delayed", not "missing". */
            int settlementLagHours) {}

    public record Simulator(
            boolean seedOnStartup,
            int seedCount,
            /* Delayed settlements arrive in the bank feed after this many seconds. */
            int delayedSettlementSeconds) {}

    public record Users(
            String adminUsername,
            String adminPassword,
            String viewerUsername,
            String viewerPassword) {}
}
